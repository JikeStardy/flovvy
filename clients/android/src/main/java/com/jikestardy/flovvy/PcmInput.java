package com.jikestardy.flovvy;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** One explicit utterance; the foreground caller owns scheduling, upload and authority. */
public final class PcmInput implements AutoCloseable {
    public interface Factory { Source open(); }
    /** Factory owns partial construction until returning this actual recorder. */
    public interface Source {
        void start();
        int read(short[] samples, int count);
        void stop();
        void release();
        boolean released();
    }
    public static final class Lease { private Lease() {} }
    public static final class Packet {
        public final int sequence;
        private final byte[] pcm;
        private Packet(int sequence, byte[] pcm) { this.sequence = sequence; this.pcm = pcm; }
        public byte[] bytes() { return pcm.clone(); }
    }
    public static final class Failure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }

    private static final int PACKET_BYTES = 6400, QUEUE_BYTES = 64000, UTTERANCE_BYTES = 960000;
    private static final long CAPTURE_NANOS = 30_000_000_000L;
    private final Factory factory;
    private final LongSupplier clock;
    private final Object gate = new Object();
    private final AtomicReference<Lease> active = new AtomicReference<>();
    private final AtomicReference<Lease> capturing = new AtomicReference<>();
    private final ArrayDeque<Packet> packets = new ArrayDeque<>();
    private final byte[] partial = new byte[PACKET_BYTES];
    private final short[] frame = new short[320];
    private volatile boolean closed;
    private Thread openingThread;
    private Source owner;
    private Lease ownerLease, utteranceLease;
    private Packet lastAcknowledged;
    private int partialBytes, queued, captured, nextSequence;
    private boolean inputFinished;
    private long startedNanos, elapsedHighWater;

    public PcmInput(Factory factory) { this(factory, System::nanoTime); }
    PcmInput(Factory factory, LongSupplier clock) {
        if (factory == null || clock == null) throw new IllegalArgumentException("INPUT_FACTORY_CLOCK");
        this.factory = factory; this.clock = clock;
    }

    /** Owned audio worker only. No provider or permission UI is started here. */
    public Lease begin() {
        final Lease lease = new Lease();
        synchronized (gate) {
            if (closed) throw new Failure("CLOSED");
            if (active.get() != null || owner != null || openingThread != null) throw new Failure("BUSY");
            clearQueue(); captured = 0; nextSequence = 0; inputFinished = false; elapsedHighWater = 0;
            utteranceLease = lease; openingThread = Thread.currentThread();
            active.set(lease); capturing.set(lease);
        }
        final Source opened;
        try {
            opened = factory.open();
            if (opened == null) throw new Failure("INPUT_OPEN");
        } catch (Throwable error) {
            synchronized (gate) {
                openingThread = null; active.compareAndSet(lease, null); capturing.compareAndSet(lease, null);
            }
            rethrow(error); throw new AssertionError("unreachable");
        }
        synchronized (gate) {
            openingThread = null; owner = opened; ownerLease = lease;
            if (!isCurrent(lease) || !isCapturing(lease)) {
                active.compareAndSet(lease, null); capturing.compareAndSet(lease, null);
                Throwable cleanup = cleanupLocked(); if (cleanup != null) rethrow(cleanup);
                return lease;
            }
            try {
                if (owner.released()) throw new Failure("INPUT_OPEN");
                startedNanos = clock.getAsLong();
                owner.start();
            } catch (Throwable error) { failLocked(lease, error); }
        }
        return lease;
    }

    public boolean isCurrent(Lease lease) { return lease != null && active.get() == lease && !closed; }
    public boolean isCapturing(Lease lease) { return isCurrent(lease) && capturing.get() == lease; }

    /** At most one nonblocking20ms read; returns newly committed PCM bytes. */
    public int pump(Lease lease) {
        if (!isCapturing(lease)) return 0;
        synchronized (gate) {
            if (!isCapturing(lease) || owner == null || ownerLease != lease) return 0;
            final boolean expiredBefore;
            try { expiredBefore = expired(); }
            catch (Throwable error) { failLocked(lease, error); throw new AssertionError("unreachable"); }
            if (expiredBefore) { endLocked(lease); return 0; }
            int count = Math.min(320, Math.min((QUEUE_BYTES - queued) / 2, (UTTERANCE_BYTES - captured) / 2));
            if (count == 0) {
                failLocked(lease, new Failure("INPUT_BACKPRESSURE"));
                throw new AssertionError("unreachable");
            }
            final int read;
            final boolean expiredAfter;
            try {
                read = owner.read(frame, count);
                // Stop/finish claims invalidation before waiting for this gate.
                if (!isCapturing(lease)) return 0;
                expiredAfter = expired();
                if (read < 0 || read > count) throw new Failure("INPUT_READ");
            } catch (Throwable error) { failLocked(lease, error); throw new AssertionError("unreachable"); }
            if (expiredAfter) { endLocked(lease); return 0; }
            int bytes = read * 2;
            try {
                for (int i = 0; i < read; i++) {
                    partial[partialBytes++] = (byte)frame[i];
                    partial[partialBytes++] = (byte)(frame[i] >>> 8);
                    if (partialBytes == PACKET_BYTES) enqueuePartial();
                }
                captured += bytes; queued += bytes;
            } catch (Throwable error) { failLocked(lease, error); throw new AssertionError("unreachable"); }
            if (captured == UTTERANCE_BYTES) endLocked(lease);
            return bytes;
        }
    }

    public Packet nextPacket(Lease lease) {
        synchronized (gate) { return isCurrent(lease) ? packets.peekFirst() : null; }
    }

    /** The controller must first match the authenticated Host response to this packet. */
    public boolean acknowledge(Lease lease, Packet packet) {
        synchronized (gate) {
            if (!isCurrent(lease)) return false;
            if (packet != null && lastAcknowledged == packet) return true;
            if (packet == null || packets.peekFirst() != packet) {
                failLocked(lease, new Failure("INPUT_SEQUENCE"));
                throw new AssertionError("unreachable");
            }
            packets.removeFirst(); queued -= packet.pcm.length; lastAcknowledged = packet;
            return true;
        }
    }

    /** Normal end retains earlier committed packets; hard stop discards them. */
    public void finish(Lease lease) {
        capturing.compareAndSet(lease, null);
        synchronized (gate) { if (isCurrent(lease) && utteranceLease == lease) endLocked(lease); }
    }
    public boolean inputEnded(Lease lease) {
        synchronized (gate) { return isCurrent(lease) && inputFinished; }
    }
    public boolean complete(Lease lease) {
        synchronized (gate) {
            if (!isCurrent(lease) || !inputFinished || queued != 0 || owner != null || openingThread != null) return false;
            active.compareAndSet(lease, null); lastAcknowledged = null;
            return true;
        }
    }

    public void stop() {
        Lease stopped = active.getAndSet(null);
        if (stopped != null) stopOwned(stopped);
    }
    /** A late operation callback may stop only its original utterance. */
    public void stop(Lease lease) {
        if (lease != null && active.compareAndSet(lease, null)) stopOwned(lease);
    }
    private void stopOwned(Lease stopped) {
        capturing.compareAndSet(stopped, null);
        synchronized (gate) {
            if (utteranceLease != stopped) return;
            clearQueue();
            if (ownerLease == stopped) {
                Throwable cleanup = cleanupLocked(); if (cleanup != null) rethrow(cleanup);
            }
        }
    }
    public void retryCleanup() {
        synchronized (gate) {
            if (active.get() != null || openingThread != null) throw new Failure("BUSY");
            Throwable cleanup = cleanupLocked(); if (cleanup != null) rethrow(cleanup);
        }
    }
    public boolean cleanupPending() {
        synchronized (gate) { return (active.get() == null || closed) && (openingThread != null || owner != null); }
    }
    public int queuedBytes() { synchronized (gate) { return queued; } }
    public int capturedBytes() { synchronized (gate) { return captured; } }
    @Override public void close() { closed = true; stop(); }

    private boolean expired() {
        long elapsed = clock.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed < elapsedHighWater) throw new Failure("INPUT_CLOCK");
        elapsedHighWater = elapsed;
        return elapsed >= CAPTURE_NANOS;
    }
    private void enqueuePartial() {
        if (partialBytes == 0) return;
        packets.addLast(new Packet(nextSequence++, Arrays.copyOf(partial, partialBytes)));
        Arrays.fill(partial, (byte)0); partialBytes = 0;
    }
    private void clearQueue() {
        packets.clear(); partialBytes = 0; queued = 0; lastAcknowledged = null;
        Arrays.fill(partial, (byte)0); Arrays.fill(frame, (short)0);
    }
    private void endLocked(Lease lease) {
        capturing.compareAndSet(lease, null);
        if (!inputFinished) {
            try { enqueuePartial(); }
            catch (Throwable error) { failLocked(lease, error); throw new AssertionError("unreachable"); }
            inputFinished = true;
        }
        Throwable cleanup = cleanupLocked();
        if (cleanup != null) {
            active.compareAndSet(lease, null); clearQueue(); rethrow(cleanup);
        }
    }
    private void failLocked(Lease lease, Throwable primary) {
        active.compareAndSet(lease, null); capturing.compareAndSet(lease, null);
        if (utteranceLease == lease) clearQueue();
        if (ownerLease == lease) primary = combine(primary, cleanupLocked());
        rethrow(primary);
    }
    private Throwable cleanupLocked() {
        if (owner == null) return null;
        Source actual = owner; Throwable failure = null; boolean settled = false;
        try { settled = actual.released(); } catch (Throwable error) { failure = combine(failure, error); }
        if (!settled) {
            try { actual.stop(); } catch (Throwable error) { failure = combine(failure, error); }
            try { actual.release(); settled = true; } catch (Throwable error) { failure = combine(failure, error); }
            if (!settled) {
                try { settled = actual.released(); } catch (Throwable error) { failure = combine(failure, error); }
            }
        }
        if (settled) { owner = null; ownerLease = null; }
        return failure;
    }
    private static Throwable combine(Throwable primary, Throwable next) {
        if (primary == null) return next;
        if (next != null && next != primary) {
            for (Throwable old : primary.getSuppressed()) if (old == next) return primary;
            primary.addSuppressed(next);
        }
        return primary;
    }
    private static void rethrow(Throwable error) {
        if (error instanceof RuntimeException) throw (RuntimeException)error;
        if (error instanceof Error) throw (Error)error;
        throw new AssertionError(error);
    }
}
