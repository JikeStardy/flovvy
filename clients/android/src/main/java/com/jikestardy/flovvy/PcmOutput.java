package com.jikestardy.flovvy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;

/** One explicitly started PCM16LE segment. Its caller owns scheduling and authority. */
public final class PcmOutput implements AutoCloseable {
    public interface Factory { Sink open(); }

    /** An actual device object, never a descriptor number or a replacement device. */
    public interface Sink {
        void play();
        int write(short[] samples, int count);
        long playedFrames();
        void pause();
        void flush();
        void release();
        boolean released();
    }

    public enum Offer { ACCEPTED, DUPLICATE, BACKPRESSURE, STALE }

    public static final class Lease {
        private Lease() {}
    }

    public static final class Failure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }

    private final Factory factory;
    private final Object gate = new Object();
    private final AtomicReference<Lease> active = new AtomicReference<>();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final short[] frame = new short[320];
    private volatile boolean closed;
    private Thread openingThread;
    private Lease segmentLease;
    private Sink owner;
    private Lease ownerLease;
    private int queued;
    private int headOffset;
    private int accepted;
    private int nextSequence;
    private byte[] lastHash;
    private boolean finished;
    private long lastPlayed;

    public PcmOutput(Factory factory) {
        if (factory == null) throw new IllegalArgumentException("OUTPUT_FACTORY");
        this.factory = factory;
    }

    /** Call on the owned audio worker, not on the UI thread. Stop may race with open. */
    public Lease begin() {
        final Lease lease = new Lease();
        synchronized (gate) {
            if (closed) throw new Failure("CLOSED");
            if (active.get() != null || openingThread != null || owner != null) throw new Failure("BUSY");
            clearQueue();
            accepted = 0;
            nextSequence = 0;
            lastHash = null;
            finished = false;
            lastPlayed = 0;
            segmentLease = lease;
            openingThread = Thread.currentThread();
            active.set(lease);
        }
        final Sink opened;
        try {
            opened = factory.open();
            if (opened == null) throw new Failure("DEVICE_OPEN");
        } catch (Throwable error) {
            synchronized (gate) {
                openingThread = null;
                active.compareAndSet(lease, null);
            }
            rethrow(error);
            throw new AssertionError("unreachable");
        }
        synchronized (gate) {
            openingThread = null;
            owner = opened;
            ownerLease = lease;
            if (closed || !isCurrent(lease)) {
                active.compareAndSet(lease, null);
                Throwable cleanup = cleanupLocked();
                if (cleanup != null) rethrow(cleanup);
                return lease;
            }
            try {
                if (owner.released()) throw new Failure("DEVICE_OPEN");
                owner.play();
            }
            catch (Throwable error) { failLocked(lease, error); }
        }
        return lease;
    }

    public boolean isCurrent(Lease lease) { return lease != null && active.get() == lease && !closed; }

    public Offer offer(Lease lease, int sequence, byte[] pcm) {
        if (!isCurrent(lease)) return Offer.STALE;
        synchronized (gate) {
            if (!isCurrent(lease)) return Offer.STALE;
            if (pcm == null || pcm.length < 2 || pcm.length > 6400 || pcm.length % 2 != 0) {
                failLocked(lease, new Failure("PCM_PACKET"));
            }
            byte[] copy = pcm.clone();
            final byte[] digest;
            try { digest = MessageDigest.getInstance("SHA-256").digest(copy); }
            catch (NoSuchAlgorithmException error) {
                failLocked(lease, new Failure("SHA256_UNAVAILABLE"));
                throw new AssertionError("unreachable");
            }
            if (sequence == nextSequence - 1 && lastHash != null && MessageDigest.isEqual(lastHash, digest)) {
                return Offer.DUPLICATE;
            }
            if (finished) failLocked(lease, new Failure("PCM_FINISHED"));
            if (sequence != nextSequence) failLocked(lease, new Failure("PCM_SEQUENCE"));
            if (accepted + pcm.length > 320000) failLocked(lease, new Failure("PCM_SEGMENT_LIMIT"));
            if (queued + pcm.length > 64000) return Offer.BACKPRESSURE;
            queue.addLast(copy);
            queued += pcm.length;
            accepted += pcm.length;
            nextSequence++;
            lastHash = digest;
            return Offer.ACCEPTED;
        }
    }

    public void finish(Lease lease) {
        synchronized (gate) {
            if (isCurrent(lease)) finished = true;
        }
    }

    /** One nonblocking write, at most20ms. Returns bytes handed to the device. */
    public int pump(Lease lease) {
        if (!isCurrent(lease)) return 0;
        synchronized (gate) {
            if (!isCurrent(lease) || owner == null || ownerLease != lease) return 0;
            if (queue.isEmpty()) {
                if (!finished) return 0;
                final long played;
                try {
                    played = owner.playedFrames();
                    if (played < lastPlayed || played < 0 || played > accepted / 2) {
                        throw new Failure("DEVICE_POSITION");
                    }
                } catch (Throwable error) {
                    failLocked(lease, error);
                    throw new AssertionError("unreachable");
                }
                lastPlayed = played;
                if (played == accepted / 2) {
                    active.compareAndSet(lease, null);
                    Throwable cleanup = cleanupLocked();
                    if (cleanup != null) rethrow(cleanup);
                }
                return 0;
            }
            byte[] head = queue.peekFirst();
            int count = Math.min(320, (head.length - headOffset) / 2);
            for (int i = 0; i < count; i++) {
                int offset = headOffset + i * 2;
                frame[i] = (short)((head[offset] & 255) | ((head[offset + 1] & 255) << 8));
            }
            final int written;
            try {
                written = owner.write(frame, count);
                if (written < 0 || written > count) throw new Failure("DEVICE_WRITE");
            } catch (Throwable error) {
                failLocked(lease, error);
                throw new AssertionError("unreachable");
            }
            int bytes = written * 2;
            headOffset += bytes;
            queued -= bytes;
            if (headOffset == head.length) {
                queue.removeFirst();
                headOffset = 0;
            }
            return bytes;
        }
    }

    /** Invalidate before entering the device gate; never waits for factory/network work. */
    public void stop() {
        Lease stopped = active.getAndSet(null);
        if (stopped == null) return;
        synchronized (gate) {
            if (segmentLease != stopped) return;
            clearQueue();
            lastHash = null;
            if (ownerLease == stopped) {
                Throwable cleanup = cleanupLocked();
                if (cleanup != null) rethrow(cleanup);
            }
        }
    }

    public void retryCleanup() {
        synchronized (gate) {
            if (active.get() != null || openingThread != null) throw new Failure("BUSY");
            Throwable cleanup = cleanupLocked();
            if (cleanup != null) rethrow(cleanup);
        }
    }

    public boolean cleanupPending() {
        synchronized (gate) {
            return (active.get() == null || closed) && (openingThread != null || owner != null);
        }
    }

    public int queuedBytes() { synchronized (gate) { return queued; } }

    @Override public void close() { closed = true; stop(); }

    private void clearQueue() { queue.clear(); queued = 0; headOffset = 0; }

    private void failLocked(Lease lease, Throwable primary) {
        active.compareAndSet(lease, null);
        clearQueue();
        lastHash = null;
        if (ownerLease == lease) primary = combine(primary, cleanupLocked());
        rethrow(primary);
    }

    private Throwable cleanupLocked() {
        if (owner == null) return null;
        Sink actual = owner;
        Throwable failure = null;
        boolean settled = false;
        try { settled = actual.released(); }
        catch (Throwable error) { failure = combine(failure, error); }
        if (!settled) {
            try { actual.pause(); } catch (Throwable error) { failure = combine(failure, error); }
            try { actual.flush(); } catch (Throwable error) { failure = combine(failure, error); }
            try { actual.release(); settled = true; }
            catch (Throwable error) { failure = combine(failure, error); }
            if (!settled) {
                try { settled = actual.released(); }
                catch (Throwable error) { failure = combine(failure, error); }
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
