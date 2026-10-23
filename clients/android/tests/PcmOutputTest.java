package com.jikestardy.flovvy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Logical PCM ownership tests; this harness cannot measure audible device stop. */
public final class PcmOutputTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static RuntimeException fails(Runnable action, String label) {
        try { action.run(); }
        catch (RuntimeException expected) { checks++; return expected; }
        throw new AssertionError(label);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(3000);
        check(!thread.isAlive(), "owned harness thread settled");
    }

    private static final class Sink implements PcmOutput.Sink {
        final List<Short> samples = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        int allowance = 320;
        int maxWrite;
        long played;
        boolean released;
        RuntimeException pauseError;
        RuntimeException flushError;
        RuntimeException releaseError;
        RuntimeException writeError;
        RuntimeException playError;
        Integer writeResult;
        boolean releaseBeforeError;
        CountDownLatch writeEntered;
        CountDownLatch writeRelease;

        @Override public void play() {
            actions.add("play");
            if (playError != null) throw playError;
        }
        @Override public int write(short[] data, int size) {
            maxWrite = Math.max(size, maxWrite);
            if (writeEntered != null) { writeEntered.countDown(); await(writeRelease); }
            if (writeError != null) throw writeError;
            if (writeResult != null) return writeResult;
            int count = Math.min(size, allowance);
            for (int i = 0; i < count; i++) samples.add(data[i]);
            return count;
        }
        @Override public long playedFrames() { return played; }
        @Override public void pause() {
            actions.add("pause");
            if (pauseError != null) throw pauseError;
        }
        @Override public void flush() {
            actions.add("flush");
            if (flushError != null) throw flushError;
        }
        @Override public void release() {
            actions.add("release");
            if (releaseBeforeError || releaseError == null) released = true;
            if (releaseError != null) throw releaseError;
        }
        @Override public boolean released() { return released; }
    }

    public static void main(String[] args) throws Exception {
        orderedSamples();
        boundedPackets();
        sequences();
        actualDrain();
        cleanupFailures();
        deviceFailures();
        newSegment();
        stopDuringWrite();
        stopDuringOpen();
        System.out.println("PcmOutput harness PASS: " + checks + " checks");
    }

    private static void orderedSamples() {
        Sink sink = new Sink();
        PcmOutput output = new PcmOutput(() -> sink);
        PcmOutput.Lease lease = output.begin();
        byte[] pcm = {0, 0, -1, 127, 0, -128, -1, -1};
        check(output.offer(lease, 0, pcm) == PcmOutput.Offer.ACCEPTED, "PCM accepted");
        pcm[0] = 10;
        sink.allowance = 1;
        check(output.pump(lease) == 2 && output.queuedBytes() == 6, "partial write consumes exact samples");
        sink.allowance = 0;
        check(output.pump(lease) == 0 && output.queuedBytes() == 6, "zero write retains remainder");
        sink.allowance = 320;
        output.pump(lease);
        check(sink.samples.equals(Arrays.asList((short)0, (short)32767, (short)-32768, (short)-1)),
                "wire LE signed samples copied and decoded in order");
        check(output.isCurrent(lease), "written buffer is not yet audible completion");
        output.stop();
        check(sink.actions.equals(Arrays.asList("play", "pause", "flush", "release")), "stop order");
        check(!output.isCurrent(lease) && output.queuedBytes() == 0, "stopped lease invalidated");
        check(output.offer(lease, 1, new byte[2]) == PcmOutput.Offer.STALE, "old audio cannot revive");
        output.stop();
        check(sink.actions.size() == 4, "repeated stop does not release twice");
        output.close();
        fails(output::begin, "closed player cannot reopen");
    }

    private static void boundedPackets() {
        Sink sink = new Sink();
        PcmOutput output = new PcmOutput(() -> sink);
        PcmOutput.Lease lease = output.begin();
        for (int i = 0; i < 10; i++) output.offer(lease, i, new byte[6400]);
        check(output.queuedBytes() == 64000, "exact two-second queue bound accepted");
        check(output.offer(lease, 10, new byte[6400]) == PcmOutput.Offer.BACKPRESSURE,
                "queue pressure consumes neither sequence nor body");
        for (int i = 0; i < 10; i++) output.pump(lease);
        check(sink.maxWrite == 320 && output.queuedBytes() == 57600, "each nonblocking write at most20ms");
        check(output.offer(lease, 10, new byte[6400]) == PcmOutput.Offer.ACCEPTED,
                "same unconsumed packet accepted after draining");
        output.stop();
        for (int size : new int[]{0, 1, 6402}) {
            Sink invalidSink = new Sink();
            PcmOutput invalid = new PcmOutput(() -> invalidSink);
            PcmOutput.Lease invalidLease = invalid.begin();
            fails(() -> invalid.offer(invalidLease, 0, new byte[size]), "bad PCM rejected");
            check(invalidSink.released && !invalid.isCurrent(invalidLease), "bad packet stops only its sink");
        }
        Sink tenSeconds = new Sink();
        PcmOutput limited = new PcmOutput(() -> tenSeconds);
        PcmOutput.Lease bounded = limited.begin();
        for (int i = 0; i < 50; i++) {
            check(limited.offer(bounded, i, new byte[6400]) == PcmOutput.Offer.ACCEPTED, "segment packet accepted");
            for (int frame = 0; frame < 10; frame++) limited.pump(bounded);
        }
        fails(() -> limited.offer(bounded, 50, new byte[2]), "segment cannot silently exceed10s");
        check(tenSeconds.released, "segment overflow stops original output");
    }

    private static void sequences() {
        Sink sink = new Sink();
        PcmOutput output = new PcmOutput(() -> sink);
        PcmOutput.Lease lease = output.begin();
        output.offer(lease, 0, new byte[]{1, 2});
        check(output.offer(lease, 0, new byte[]{1, 2}) == PcmOutput.Offer.DUPLICATE
                && output.queuedBytes() == 2, "exact previous duplicate does not play twice");
        output.finish(lease);
        check(output.offer(lease, 0, new byte[]{1, 2}) == PcmOutput.Offer.DUPLICATE
                && output.isCurrent(lease), "sealed segment still acknowledges its exact previous packet");
        fails(() -> output.offer(lease, 0, new byte[]{2, 1}), "conflicting duplicate stops");
        check(sink.released, "conflicting duplicate released original sink");
        Sink gapSink = new Sink();
        PcmOutput gap = new PcmOutput(() -> gapSink);
        PcmOutput.Lease current = gap.begin();
        fails(() -> gap.offer(current, 1, new byte[2]), "sequence gap stops");
        check(gapSink.released, "gap cannot leave playback active");
    }

    private static void actualDrain() {
        Sink sink = new Sink();
        PcmOutput output = new PcmOutput(() -> sink);
        PcmOutput.Lease lease = output.begin();
        output.offer(lease, 0, new byte[640]);
        output.finish(lease);
        output.pump(lease);
        check(output.queuedBytes() == 0 && output.isCurrent(lease) && !sink.released,
                "queue drained does not claim played audio");
        sink.played = 320;
        output.pump(lease);
        check(sink.released && !output.isCurrent(lease), "played frame position settles finished segment");
        Sink afterFinish = new Sink();
        PcmOutput invalid = new PcmOutput(() -> afterFinish);
        PcmOutput.Lease next = invalid.begin();
        invalid.finish(next);
        fails(() -> invalid.offer(next, 0, new byte[2]), "audio after finish rejected");
    }

    private static void cleanupFailures() {
        Sink sink = new Sink();
        PcmOutput output = new PcmOutput(() -> sink);
        output.begin();
        RuntimeException pause = new IllegalStateException("pause fixture");
        RuntimeException flush = new IllegalStateException("flush fixture");
        RuntimeException release = new IllegalStateException("release fixture");
        sink.pauseError = pause;
        sink.flushError = flush;
        sink.releaseError = release;
        RuntimeException caught = fails(output::stop, "cleanup failure surfaced");
        check(caught == pause && Arrays.asList(caught.getSuppressed()).containsAll(Arrays.asList(flush, release)),
                "primary and distinct cleanup errors retained");
        check(output.cleanupPending() && !sink.released, "unclosed actual sink remains owned");
        fails(output::begin, "new device cannot replace pending original");
        output.stop();
        check(sink.actions.size() == 4, "ordinary repeated stop is not an implicit cleanup retry");
        sink.pauseError = null;
        sink.flushError = null;
        sink.releaseError = null;
        output.retryCleanup();
        check(sink.released && !output.cleanupPending(), "explicit cleanup retries same sink");

        Sink closedThenFails = new Sink();
        PcmOutput released = new PcmOutput(() -> closedThenFails);
        released.begin();
        closedThenFails.releaseBeforeError = true;
        closedThenFails.releaseError = release;
        check(fails(released::stop, "release error retained after side effect") == release,
                "original release error identity retained");
        check(!released.cleanupPending(), "actual API-settled resource not reported pending");
        released.retryCleanup();
        check(closedThenFails.actions.size() == 4, "settled resource never blindly released again");

        Sink writing = new Sink();
        PcmOutput failedWrite = new PcmOutput(() -> writing);
        PcmOutput.Lease lease = failedWrite.begin();
        failedWrite.offer(lease, 0, new byte[2]);
        RuntimeException write = new IllegalStateException("write fixture");
        writing.writeError = write;
        writing.releaseError = new IllegalStateException("write cleanup fixture");
        check(fails(() -> failedWrite.pump(lease), "write failure surfaced") == write
                && write.getSuppressed().length == 1 && failedWrite.cleanupPending(),
                "write failure retains same cleanup owner and original cause");
        check(writing.actions.size() == 4, "a failed pump does not retry cleanup inside its own catch");
        writing.releaseError = null;
        failedWrite.retryCleanup();
    }

    private static void deviceFailures() {
        for (int result : new int[]{-6, 321}) {
            Sink sink = new Sink();
            PcmOutput output = new PcmOutput(() -> sink);
            PcmOutput.Lease lease = output.begin();
            output.offer(lease, 0, new byte[640]);
            sink.writeResult = result;
            fails(() -> output.pump(lease), "invalid device transfer count stops");
            check(sink.released && !output.isCurrent(lease), "bad device transfer settles original");
        }
        Sink playSink = new Sink();
        playSink.playError = new IllegalStateException("play fixture");
        PcmOutput play = new PcmOutput(() -> playSink);
        check(fails(play::begin, "play failure reported") == playSink.playError
                && playSink.released && !play.cleanupPending(), "failed play cannot lose actual opened resource");
        PcmOutput opening = new PcmOutput(() -> { throw new IllegalStateException("open fixture"); });
        fails(opening::begin, "factory failure reported");
        check(!opening.cleanupPending(), "factory failure before transfer has no invented sink");

        Sink drain = new Sink();
        PcmOutput output = new PcmOutput(() -> drain);
        PcmOutput.Lease lease = output.begin();
        output.offer(lease, 0, new byte[640]);
        output.finish(lease);
        output.pump(lease);
        drain.played = 100;
        output.pump(lease);
        drain.played = 99;
        fails(() -> output.pump(lease), "unexpected playback-position reset stops");
        check(drain.released, "position failure closes actual sink");

        Sink terminal = new Sink();
        PcmOutput completed = new PcmOutput(() -> terminal);
        PcmOutput.Lease last = completed.begin();
        completed.finish(last);
        terminal.releaseError = new IllegalStateException("natural-completion cleanup fixture");
        check(fails(() -> completed.pump(last), "completion cleanup failure surfaced") == terminal.releaseError,
                "completion cleanup original error retained");
        check(completed.cleanupPending() && terminal.actions.size() == 4,
                "natural completion retains owner without immediate retry");
        terminal.releaseError = null;
        completed.retryCleanup();
    }

    private static void newSegment() {
        List<Sink> sinks = new ArrayList<>();
        PcmOutput output = new PcmOutput(() -> {
            Sink sink = new Sink();
            sinks.add(sink);
            return sink;
        });
        PcmOutput.Lease old = output.begin();
        output.offer(old, 0, new byte[2]);
        output.stop();
        PcmOutput.Lease fresh = output.begin();
        check(old != fresh && output.isCurrent(fresh), "explicit next segment uses new local identity");
        output.offer(fresh, 0, new byte[2]);
        output.finish(old);
        check(output.offer(old, 1, new byte[2]) == PcmOutput.Offer.STALE
                && output.pump(old) == 0 && output.queuedBytes() == 2,
                "late old segment cannot mutate new PCM, finish or device");
        check(sinks.get(0).released && !sinks.get(1).released, "each segment has one actual sink");
        output.close();
        check(sinks.get(1).released, "close settles current actual sink");
    }

    private static void stopDuringWrite() throws Exception {
        Sink sink = new Sink();
        sink.writeEntered = new CountDownLatch(1);
        sink.writeRelease = new CountDownLatch(1);
        PcmOutput output = new PcmOutput(() -> sink);
        PcmOutput.Lease lease = output.begin();
        output.offer(lease, 0, new byte[1280]);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { output.pump(lease); } catch (Throwable error) { failure.set(error); }
        });
        writer.start();
        await(sink.writeEntered);
        Thread stop = new Thread(() -> {
            try { output.stop(); } catch (Throwable error) { failure.set(error); }
        });
        stop.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (output.isCurrent(lease) && System.nanoTime() < deadline) Thread.yield();
        check(!output.isCurrent(lease), "stop invalidates before waiting on in-flight short write");
        sink.writeRelease.countDown();
        join(writer);
        join(stop);
        check(failure.get() == null && sink.released, "write/stop threads settle original sink");
        int samples = sink.samples.size();
        output.pump(lease);
        check(sink.samples.size() == samples && samples == 320, "late pump cannot write second old frame");
    }

    private static void stopDuringOpen() throws Exception {
        Sink sink = new Sink();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch continueOpen = new CountDownLatch(1);
        PcmOutput output = new PcmOutput(() -> {
            opened.countDown();
            await(continueOpen);
            return sink;
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<PcmOutput.Lease> late = new AtomicReference<>();
        Thread opening = new Thread(() -> {
            try { late.set(output.begin()); } catch (Throwable error) { failure.set(error); }
        });
        opening.start();
        await(opened);
        output.stop();
        check(output.cleanupPending(), "stopped original opening remains owned");
        fails(output::begin, "second open refused before first returns");
        continueOpen.countDown();
        join(opening);
        check(failure.get() == null && !output.isCurrent(late.get()) && sink.released,
                "late opened sink settles without restoring stopped lease");
        check(!sink.actions.contains("play") && !output.cleanupPending(), "late open never plays");
    }
}
