package com.jikestardy.flovvy;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Logical capture/queue ownership only. No device, voice-quality or acoustic evidence. */
public final class PcmInputTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static RuntimeException fails(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { checks++; return error; }
        throw new AssertionError("expected explicit capture failure");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void join(Thread thread) throws InterruptedException {
        thread.join(3000);
        check(!thread.isAlive(), "actual harness thread settled");
    }

    private static final class Source implements PcmInput.Source {
        int result = 320;
        short[] words;
        int starts, reads, stops, releases, largestRead;
        boolean released, releaseBeforeError;
        RuntimeException startError, readError, stopError, releaseError;
        CountDownLatch readEntered, readRelease;
        Runnable afterRead;
        @Override public void start() { starts++; if (startError != null) throw startError; }
        @Override public int read(short[] data, int count) {
            reads++; largestRead = Math.max(largestRead, count);
            if (readEntered != null) { readEntered.countDown(); await(readRelease); }
            if (readError != null) throw readError;
            int size = result < 0 || result > 320 ? result : Math.min(result, count);
            if (size >= 0 && size <= count) {
                for (int i = 0; i < size; i++) data[i] = words == null ? (short)i : words[i % words.length];
            }
            if (afterRead != null) afterRead.run();
            return size;
        }
        @Override public void stop() { stops++; if (stopError != null) throw stopError; }
        @Override public void release() {
            releases++;
            if (releaseBeforeError || releaseError == null) released = true;
            if (releaseError != null) throw releaseError;
        }
        @Override public boolean released() { return released; }
    }

    public static void main(String[] args) throws Exception {
        orderedPartialAndEmpty();
        partialPacketBoundary();
        packetsAndAcknowledgements();
        newUtteranceIsolation();
        queueBound();
        sampleAndTimeBounds();
        failuresAndOriginalOwner();
        stopDuringOpen();
        stopAndFinishDuringRead();
        System.out.println("PcmInput harness PASS: " + checks + " checks; no device evidence");
    }

    private static void orderedPartialAndEmpty() {
        Source source = new Source(); source.result = 4;
        source.words = new short[]{0, 32767, -32768, -1};
        PcmInput input = new PcmInput(() -> source);
        PcmInput.Lease lease = input.begin();
        check(input.pump(lease) == 8 && input.nextPacket(lease) == null, "small read remains partial");
        source.result = 0;
        check(input.pump(lease) == 0 && input.capturedBytes() == 8, "zero read adds no samples");
        check(!input.complete(lease), "cannot complete a running capture");
        input.finish(lease);
        PcmInput.Packet packet = input.nextPacket(lease);
        check(packet != null && packet.sequence == 0 && Arrays.equals(packet.bytes(),
                new byte[]{0, 0, -1, 127, 0, -128, -1, -1}), "partial flush contains exact signed LE samples");
        byte[] changed = packet.bytes(); changed[0] = 99;
        check(packet.bytes()[0] == 0, "packet bytes are not caller-mutable");
        check(input.inputEnded(lease) && source.stops == 1 && source.releases == 1,
                "normal finish stops actual recorder but retains upload lease");
        check(!input.complete(lease) && input.acknowledge(lease, packet), "drain before explicit complete");
        check(input.acknowledge(lease, packet) && input.queuedBytes() == 0, "exact duplicate acknowledgement removes nothing twice");
        check(input.complete(lease) && !input.isCurrent(lease), "explicit drained completion");
        check(input.pump(lease) == 0 && input.nextPacket(lease) == null, "stale lease reads or delivers nothing");
        input.stop(); check(source.releases == 1, "repeated stop does not retry cleanup");
        input.close(); fails(input::begin);

        Source empty = new Source(); empty.result = 0;
        PcmInput nothing = new PcmInput(() -> empty);
        PcmInput.Lease blank = nothing.begin(); nothing.pump(blank); nothing.finish(blank);
        check(nothing.nextPacket(blank) == null && nothing.complete(blank), "empty utterance creates no packet");
    }

    private static void packetsAndAcknowledgements() {
        Source source = new Source(); PcmInput input = new PcmInput(() -> source);
        PcmInput.Lease lease = input.begin();
        for (int i = 0; i < 20; i++) input.pump(lease);
        PcmInput.Packet first = input.nextPacket(lease);
        check(first != null && first.sequence == 0 && first.bytes().length == 6400,
                "twenty-ms reads form a two-hundred-ms packet");
        check(input.acknowledge(lease, first), "first acknowledgement");
        PcmInput.Packet second = input.nextPacket(lease);
        check(second != null && second.sequence == 1 && second.bytes().length == 6400,
                "next packet keeps order");
        check(input.acknowledge(lease, first) && input.queuedBytes() == 6400,
                "duplicate prior acknowledgement cannot remove next head");
        Source otherSource = new Source(); PcmInput other = new PcmInput(() -> otherSource);
        PcmInput.Lease otherLease = other.begin(); other.pump(otherLease); other.finish(otherLease);
        RuntimeException error = fails(() -> input.acknowledge(lease, other.nextPacket(otherLease)));
        check("INPUT_SEQUENCE".equals(error.getMessage()) && !input.isCurrent(lease)
                && source.released && input.queuedBytes() == 0, "foreign packet stops only this capture");
        check(other.isCurrent(otherLease), "foreign owner unaffected"); other.stop();
    }

    private static void partialPacketBoundary() {
        Source source = new Source(); source.result = 3; source.words = new short[]{0, 1, 2};
        PcmInput input = new PcmInput(() -> source); PcmInput.Lease lease = input.begin();
        for (int i = 0; i < 1067; i++) input.pump(lease);
        input.finish(lease); PcmInput.Packet first = input.nextPacket(lease);
        byte[] pcm = first.bytes();
        check(pcm.length == 6400 && input.queuedBytes() == 6402, "partial read crosses exact packet boundary without loss");
        for (int i = 0; i < 3200; i++) check(pcm[i * 2] == (byte)(i % 3) && pcm[i * 2 + 1] == 0,
                "captured sample order spans read/packet boundary");
        input.acknowledge(lease, first); PcmInput.Packet tail = input.nextPacket(lease);
        check(tail.sequence == 1 && Arrays.equals(tail.bytes(), new byte[]{2, 0}), "remaining sample retained in final tail");
        input.acknowledge(lease, tail); check(input.complete(lease), "partial boundary capture drained");
    }

    private static void queueBound() {
        Source source = new Source(); PcmInput input = new PcmInput(() -> source);
        PcmInput.Lease lease = input.begin();
        for (int i = 0; i < 100; i++) check(input.pump(lease) == 640, "bounded read committed");
        check(input.queuedBytes() == 64000 && source.reads == 100 && source.largestRead == 320,
                "exact two-second unacknowledged bound");
        RuntimeException error = fails(() -> input.pump(lease));
        check("INPUT_BACKPRESSURE".equals(error.getMessage()) && source.reads == 100
                && source.released && !input.isCurrent(lease), "overflow fails before another read, never silently drops");
    }

    private static void newUtteranceIsolation() {
        AtomicReference<Source> actual = new AtomicReference<>();
        PcmInput input = new PcmInput(() -> { Source source = new Source(); source.result = 1; actual.set(source); return source; });
        PcmInput.Lease first = input.begin(); input.pump(first); input.finish(first);
        PcmInput.Packet oldPacket = input.nextPacket(first); input.acknowledge(first, oldPacket); input.complete(first);
        Source original = actual.get(); PcmInput.Lease next = input.begin(); Source replacement = actual.get();
        input.finish(first); input.stop(first);
        check(input.pump(first) == 0 && !input.acknowledge(first, oldPacket) && input.nextPacket(first) == null,
                "late old input callbacks cannot read or confirm a new utterance");
        check(input.isCapturing(next) && replacement != original && replacement.reads == 0 && replacement.releases == 0,
                "late scoped stop preserves the distinct current recorder");
        check(input.pump(next) == 2 && input.capturedBytes() == 2, "new capture has independent sample counters");
        input.stop(next); check(replacement.released && original.releases == 1, "explicit current stop closes only current recorder");
    }

    private static void sampleAndTimeBounds() {
        Source source = new Source(); AtomicLong clock = new AtomicLong(100);
        PcmInput input = new PcmInput(() -> source, clock::get);
        PcmInput.Lease lease = input.begin(); int packets = 0;
        for (int i = 0; i < 1500; i++) {
            check(input.pump(lease) == 640, "fixed sample allowance");
            PcmInput.Packet packet = input.nextPacket(lease);
            if (packet != null) { check(input.acknowledge(lease, packet), "current packet drained"); packets++; }
        }
        check(packets == 150 && input.capturedBytes() == 960000 && input.inputEnded(lease)
                && source.released && input.pump(lease) == 0 && input.complete(lease),
                "exact sample limit ends and releases, no automatic restart");
        check(source.starts == 1 && source.reads == 1500, "only original physical recorder");

        for (boolean during : new boolean[]{false, true}) {
            Source timed = new Source(); timed.result = 1;
            AtomicLong now = new AtomicLong(500);
            PcmInput bounded = new PcmInput(() -> timed, now::get);
            PcmInput.Lease current = bounded.begin(); bounded.pump(current);
            if (during) timed.afterRead = () -> now.set(30_000_000_500L);
            else now.set(30_000_000_500L);
            check(bounded.pump(current) == 0 && bounded.capturedBytes() == 2
                    && bounded.inputEnded(current) && timed.released, "monotonic limit publishes no expired read");
            check(timed.reads == (during ? 2 : 1), "deadline checked before and after read");
            PcmInput.Packet finalPacket = bounded.nextPacket(current);
            check(finalPacket != null && finalPacket.bytes().length == 2, "only pre-deadline data retained");
            bounded.acknowledge(current, finalPacket); bounded.complete(current);
        }
        Source backwards = new Source(); AtomicLong now = new AtomicLong(100);
        PcmInput badClock = new PcmInput(() -> backwards, now::get);
        PcmInput.Lease old = badClock.begin(); now.set(99);
        check("INPUT_CLOCK".equals(fails(() -> badClock.pump(old)).getMessage())
                && backwards.released, "backward clock fails instead of extending capture");
    }

    private static void failuresAndOriginalOwner() {
        for (int result : new int[]{-1, 321}) {
            Source source = new Source(); source.result = result;
            PcmInput input = new PcmInput(() -> source); PcmInput.Lease lease = input.begin();
            check("INPUT_READ".equals(fails(() -> input.pump(lease)).getMessage()) && source.released,
                    "invalid actual read count stops original source");
        }
        Source source = new Source(); RuntimeException primary = new IllegalStateException("primary");
        RuntimeException stop = new IllegalStateException("stop"), release = new IllegalStateException("release");
        source.readError = primary; source.stopError = stop; source.releaseError = release;
        PcmInput input = new PcmInput(() -> source); PcmInput.Lease lease = input.begin();
        check(fails(() -> input.pump(lease)) == primary && Arrays.equals(primary.getSuppressed(),
                new Throwable[]{stop, release}), "actual primary and cleanup identities preserved");
        check(input.cleanupPending() && source.stops == 1 && source.releases == 1, "unclosed source remains owned");
        fails(input::begin); input.stop(); check(source.releases == 1, "repeated stop is not implicit retry");
        source.stopError = null; source.releaseError = null; input.retryCleanup();
        check(source.released && source.releases == 2 && !input.cleanupPending(), "explicit retry closes same original source");

        Source failedStart = new Source(); failedStart.startError = primary;
        PcmInput neverStarted = new PcmInput(() -> failedStart);
        check(fails(neverStarted::begin) == primary && failedStart.released && !neverStarted.cleanupPending(),
                "start failure retained before original cleanup");
        Source afterSideEffect = new Source(); afterSideEffect.releaseBeforeError = true; afterSideEffect.releaseError = release;
        PcmInput settled = new PcmInput(() -> afterSideEffect); settled.begin();
        check(fails(settled::stop) == release && !settled.cleanupPending(), "actual post-release proof is settled but error survives");
        settled.stop(); check(afterSideEffect.releases == 1, "never release a settled original twice");

        Source finalFailure = new Source(); finalFailure.result = 1; finalFailure.releaseError = release;
        PcmInput failedEnd = new PcmInput(() -> finalFailure); PcmInput.Lease finishing = failedEnd.begin();
        failedEnd.pump(finishing);
        check(fails(() -> failedEnd.finish(finishing)) == release && failedEnd.cleanupPending()
                && !failedEnd.inputEnded(finishing) && failedEnd.nextPacket(finishing) == null,
                "normal-end cleanup failure cannot deliver a completed tail");
        failedEnd.finish(finishing); failedEnd.stop();
        check(finalFailure.releases == 1, "failed end cleanup is not implicitly retried");
        finalFailure.releaseError = null; failedEnd.retryCleanup();
        check(finalFailure.releases == 2 && !failedEnd.cleanupPending(), "explicit end cleanup retry settles original");
    }

    private static void stopDuringOpen() throws InterruptedException {
        Source source = new Source(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PcmInput input = new PcmInput(() -> { entered.countDown(); await(release); return source; });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread opener = new Thread(() -> { try { input.begin(); } catch (Throwable error) { failure.set(error); } });
        opener.start(); await(entered); input.stop();
        check(input.cleanupPending() && source.starts == 0, "stopped original opening still owned");
        fails(input::begin); release.countDown(); join(opener);
        check(failure.get() == null && source.starts == 0 && source.released && !input.cleanupPending(),
                "late actual recorder closes and never starts");
    }

    private static void stopAndFinishDuringRead() throws InterruptedException {
        for (boolean finish : new boolean[]{false, true}) {
            Source source = new Source(); source.result = 1;
            PcmInput input = new PcmInput(() -> source); PcmInput.Lease lease = input.begin(); input.pump(lease);
            source.readEntered = new CountDownLatch(1); source.readRelease = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> { try { input.pump(lease); } catch (Throwable error) { failure.compareAndSet(null, error); } });
            Thread stopper = new Thread(() -> {
                try { if (finish) input.finish(lease); else input.stop(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            reader.start(); await(source.readEntered); stopper.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (input.isCapturing(lease) && System.nanoTime() < until) Thread.yield();
            check(!input.isCapturing(lease), "event invalidates capture before blocked read gate");
            source.readRelease.countDown(); join(reader); join(stopper);
            check(failure.get() == null && source.released && input.capturedBytes() == 2,
                    "late read cannot publish samples after event");
            if (finish) {
                PcmInput.Packet packet = input.nextPacket(lease);
                check(packet != null && packet.bytes().length == 2 && input.inputEnded(lease), "finish preserves earlier committed partial");
                input.acknowledge(lease, packet); input.complete(lease);
            } else check(input.queuedBytes() == 0 && input.nextPacket(lease) == null, "stop discards unsent original audio");
        }
    }
}
