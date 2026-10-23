package android.media;

/** JDK-only API-call fixture. It does not model real capture, timing or permissions. */
public final class AudioRecord {
    public static final int STATE_UNINITIALIZED = 0, STATE_INITIALIZED = 1;
    public static final int RECORDSTATE_STOPPED = 1, RECORDSTATE_RECORDING = 3;
    public static final int READ_NON_BLOCKING = 1;
    public static AudioRecord lastCreated;
    public static int minimum = 6400, nextState = STATE_INITIALIZED;
    public static Integer nextRate, nextChannels, nextEncoding, nextSource, nextFrames;
    public static Runnable nextBuildAction;
    public static RuntimeException nextReleaseError;
    public int state, rate, channels, encoding, source, frames;
    public int recording = RECORDSTATE_STOPPED;
    public int starts, reads, stops, releases, readResult = 320;
    public short[] words = {0, 32767, -32768, -1};
    public Runnable afterRead;
    public RuntimeException releaseError;
    public boolean releaseBeforeError;

    private AudioRecord(AudioFormat format, int source, int bytes) {
        state = nextState;
        rate = nextRate == null ? format.sampleRate : nextRate;
        channels = nextChannels == null ? (format.channelMask == AudioFormat.CHANNEL_IN_MONO ? 1 : 2) : nextChannels;
        encoding = nextEncoding == null ? format.encoding : nextEncoding;
        this.source = nextSource == null ? source : nextSource;
        frames = nextFrames == null ? bytes / 2 : nextFrames;
        releaseError = nextReleaseError;
        nextState = STATE_INITIALIZED;
        nextRate = nextChannels = nextEncoding = nextSource = nextFrames = null;
        nextReleaseError = null;
        lastCreated = this;
        Runnable action = nextBuildAction; nextBuildAction = null;
        if (action != null) action.run();
    }
    public static int getMinBufferSize(int rate, int channel, int encoding) { return minimum; }
    public int getState() { return state; }
    public int getSampleRate() { return rate; }
    public int getChannelCount() { return channels; }
    public int getAudioFormat() { return encoding; }
    public int getAudioSource() { return source; }
    public int getBufferSizeInFrames() { return frames; }
    public int getRecordingState() { return recording; }
    public void startRecording() { starts++; recording = RECORDSTATE_RECORDING; }
    public int read(short[] data, int offset, int count, int mode) {
        if (offset != 0 || count < 1 || count > 320 || mode != READ_NON_BLOCKING
                || state != STATE_INITIALIZED || recording != RECORDSTATE_RECORDING) {
            throw new IllegalStateException("nonblocking short-array capture fixture");
        }
        reads++;
        int result = readResult < 0 || readResult > 320 ? readResult : Math.min(readResult, count);
        if (result >= 0 && result <= count) for (int i = 0; i < result; i++) data[i] = words[i % words.length];
        if (afterRead != null) afterRead.run();
        return result;
    }
    public void stop() { stops++; recording = RECORDSTATE_STOPPED; }
    public void release() {
        releases++;
        if (releaseBeforeError || releaseError == null) { state = STATE_UNINITIALIZED; recording = RECORDSTATE_STOPPED; }
        if (releaseError != null) throw releaseError;
    }
    public static final class Builder {
        private AudioFormat format;
        private int source, bytes;
        public Builder setAudioSource(int source) { this.source = source; return this; }
        public Builder setAudioFormat(AudioFormat format) { this.format = format; return this; }
        public Builder setBufferSizeInBytes(int bytes) { this.bytes = bytes; return this; }
        public AudioRecord build() { return new AudioRecord(format, source, bytes); }
    }
}
