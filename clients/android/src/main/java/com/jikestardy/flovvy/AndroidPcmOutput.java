package com.jikestardy.flovvy;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Fixed-format device adapter. The foreground controller owns focus, route and scheduling. */
public final class AndroidPcmOutput {
    private AndroidPcmOutput() {}

    public static PcmOutput create() { return new PcmOutput(AndroidPcmOutput::open); }

    private static PcmOutput.Sink open() {
        int minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0 || minimum > 64000) throw new IllegalStateException("AUDIO_OUTPUT_FORMAT");
        int buffer = Math.max(6400, minimum);
        if ((buffer & 1) != 0) buffer++;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
        // Validation and play occur only after PcmOutput has retained this actual object.
        return new Device(track);
    }

    private static final class Device implements PcmOutput.Sink {
        private final AudioTrack track;
        private boolean released;

        private Device(AudioTrack track) { this.track = track; }

        @Override public void play() {
            if (track.getState() != AudioTrack.STATE_INITIALIZED || track.getSampleRate() != 16000
                    || track.getChannelCount() != 1 || track.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT
                    || track.getBufferCapacityInFrames() <= 0 || track.getBufferCapacityInFrames() > 32000) {
                throw new IllegalStateException("AUDIO_OUTPUT_FORMAT");
            }
            // MODE_STREAM otherwise waits for the full buffer, including after an underrun.
            if (track.setStartThresholdInFrames(1) != 1) {
                throw new IllegalStateException("AUDIO_OUTPUT_START_THRESHOLD");
            }
            checkThreshold();
            track.play();
        }

        @Override public int write(short[] samples, int count) {
            checkThreshold();
            return track.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING);
        }

        @Override public long playedFrames() {
            checkThreshold();
            return Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        }
        private void checkThreshold() {
            // An output sink change can alter this actual value. Do not resume the old segment.
            if (track.getStartThresholdInFrames() != 1) {
                throw new IllegalStateException("AUDIO_OUTPUT_START_THRESHOLD");
            }
        }
        @Override public void pause() { track.pause(); }
        @Override public void flush() { track.flush(); }
        @Override public void release() {
            if (!released()) track.release();
            released = true;
        }
        @Override public boolean released() { return released || track.getState() == AudioTrack.STATE_UNINITIALIZED; }
    }
}
