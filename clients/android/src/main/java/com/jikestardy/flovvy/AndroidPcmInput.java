package com.jikestardy.flovvy;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Fixed-format recorder adapter. The foreground controller owns lifecycle and authority. */
public final class AndroidPcmInput {
    private AndroidPcmInput() {}
    public static PcmInput create(Context context) {
        if (context == null) throw new IllegalArgumentException("INPUT_CONTEXT");
        Context application = context.getApplicationContext();
        if (application == null) throw new IllegalArgumentException("INPUT_CONTEXT");
        return new PcmInput(() -> open(application));
    }
    private static void permission(Context context) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("AUDIO_INPUT_PERMISSION");
        }
    }
    private static PcmInput.Source open(Context context) {
        permission(context);
        int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0 || minimum > 64000) throw new IllegalStateException("AUDIO_INPUT_FORMAT");
        int buffer = Math.max(6400, minimum); if ((buffer & 1) != 0) buffer++;
        AudioRecord recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(buffer).build();
        // Validate only after the core retains this exact actual recorder.
        return new Device(context, recorder);
    }
    private static final class Device implements PcmInput.Source {
        private final Context context;
        private final AudioRecord recorder;
        private boolean released, releaseAttempted, initializedBeforeRelease;
        private Device(Context context, AudioRecord recorder) { this.context = context; this.recorder = recorder; }
        private void format() {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED || recorder.getSampleRate() != 16000
                    || recorder.getChannelCount() != 1 || recorder.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT
                    || recorder.getAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    || recorder.getBufferSizeInFrames() <= 0 || recorder.getBufferSizeInFrames() > 32000) {
                throw new IllegalStateException("AUDIO_INPUT_FORMAT");
            }
        }
        private void recording() {
            permission(context); format();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AUDIO_INPUT_STATE");
            }
        }
        @Override public void start() {
            permission(context); format(); recorder.startRecording(); recording();
        }
        @Override public int read(short[] samples, int count) {
            recording();
            int result = recorder.read(samples, 0, count, AudioRecord.READ_NON_BLOCKING);
            recording();
            return result;
        }
        @Override public void stop() {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        }
        @Override public void release() {
            if (released()) return;
            initializedBeforeRelease |= recorder.getState() == AudioRecord.STATE_INITIALIZED;
            releaseAttempted = true; recorder.release(); released = true;
        }
        @Override public boolean released() {
            return released || (releaseAttempted && initializedBeforeRelease
                    && recorder.getState() == AudioRecord.STATE_UNINITIALIZED);
        }
    }
}
