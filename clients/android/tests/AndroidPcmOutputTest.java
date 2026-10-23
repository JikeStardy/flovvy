package com.jikestardy.flovvy;

import android.media.AudioTrack;

/** Actual adapter against first-party platform fixtures. No real Android/audio claims. */
public final class AndroidPcmOutputTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static RuntimeException fails(Runnable action) {
        try { action.run(); }
        catch (RuntimeException expected) { checks++; return expected; }
        throw new AssertionError("expected explicit threshold failure");
    }

    public static void main(String[] args) {
        fixtureReproducesDefaultThreshold();
        finiteShortSegments();
        shortTailAfterUnderrun();
        unsupportedThreshold();
        changedSinkThreshold();
        System.out.println("AndroidPcmOutput fixture harness PASS: " + checks + " checks; no device evidence");
    }

    private static void fixtureReproducesDefaultThreshold() {
        android.media.AudioFormat format = new android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build();
        AudioTrack baseline = new AudioTrack.Builder().setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(6400).build();
        baseline.play();
        baseline.write(new short[320], 0, 320, AudioTrack.WRITE_NON_BLOCKING);
        check(baseline.getStartThresholdInFrames() == 3200 && baseline.getPlaybackHeadPosition() == 0,
                "fixture independently represents default buffer-capacity startup");
        baseline.release();
    }

    private static void finiteShortSegments() {
        for (int bytes : new int[]{2, 640}) {
            PcmOutput output = AndroidPcmOutput.create();
            PcmOutput.Lease lease = output.begin();
            AudioTrack actual = AudioTrack.lastCreated;
            check(actual.getStartThresholdInFrames() == 1, "actual adapter establishes one-frame startup");
            output.offer(lease, 0, new byte[bytes]);
            output.finish(lease);
            output.pump(lease);
            output.pump(lease);
            check(!output.isCurrent(lease) && !output.cleanupPending() && actual.releases == 1,
                    "legal finite short segment naturally settles original device");
        }
    }

    private static void shortTailAfterUnderrun() {
        PcmOutput output = AndroidPcmOutput.create();
        PcmOutput.Lease lease = output.begin();
        AudioTrack actual = AudioTrack.lastCreated;
        output.offer(lease, 0, new byte[6400]);
        for (int i = 0; i < 10; i++) output.pump(lease);
        check(actual.getPlaybackHeadPosition() == 3200, "first audio drained to simulated underrun");
        output.offer(lease, 1, new byte[2]);
        output.finish(lease);
        output.pump(lease);
        output.pump(lease);
        check(!output.isCurrent(lease) && actual.releases == 1, "single-frame tail starts after underrun");
    }

    private static void unsupportedThreshold() {
        for (boolean setter : new boolean[]{true, false}) {
            if (setter) AudioTrack.nextSetterResult = 2;
            else AudioTrack.nextGetterResult = 2;
            PcmOutput output = AndroidPcmOutput.create();
            RuntimeException error = fails(output::begin);
            AudioTrack actual = AudioTrack.lastCreated;
            check("AUDIO_OUTPUT_START_THRESHOLD".equals(error.getMessage())
                    && actual.plays == 0 && actual.releases == 1 && !output.cleanupPending(),
                    "unsupported actual threshold fails before play and settles original object");
        }
    }

    private static void changedSinkThreshold() {
        for (boolean beforeWrite : new boolean[]{true, false}) {
            PcmOutput output = AndroidPcmOutput.create();
            PcmOutput.Lease lease = output.begin();
            AudioTrack actual = AudioTrack.lastCreated;
            output.offer(lease, 0, new byte[2]);
            output.finish(lease);
            if (!beforeWrite) output.pump(lease);
            actual.simulateSinkThresholdChange(2);
            RuntimeException error = fails(() -> output.pump(lease));
            check("AUDIO_OUTPUT_START_THRESHOLD".equals(error.getMessage()) && !output.isCurrent(lease)
                    && actual.releases == 1 && actual.plays == 1,
                    "changed output threshold stops original segment instead of waiting forever/replaying");
        }
    }
}
