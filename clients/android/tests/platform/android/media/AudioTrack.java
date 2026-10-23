package android.media;

/**
 * JDK-only behavioral fixture for documented API31 streaming thresholds.
 * Playback consumes queued frames only once the threshold is reached, including
 * after each simulated underrun. This is not a device or acoustic measurement.
 */
public final class AudioTrack {
    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int MODE_STREAM = 1;
    public static final int WRITE_NON_BLOCKING = 1;
    public static AudioTrack lastCreated;
    public static Integer nextSetterResult;
    public static Integer nextGetterResult;
    private final AudioFormat format;
    private final int capacity;
    private final Integer setterResult;
    private final Integer getterResult;
    private int state = STATE_INITIALIZED;
    private int threshold;
    private int queued;
    private int played;
    private boolean playing;
    public int plays;
    public int releases;
    public int writes;

    private AudioTrack(AudioFormat format, int bytes) {
        this.format = format;
        capacity = bytes / 2;
        threshold = capacity;
        setterResult = nextSetterResult;
        getterResult = nextGetterResult;
        nextSetterResult = null;
        nextGetterResult = null;
        lastCreated = this;
    }

    public static int getMinBufferSize(int rate, int channel, int encoding) { return 6400; }
    public int getState() { return state; }
    public int getSampleRate() { return format.sampleRate; }
    public int getChannelCount() { return format.channelMask == AudioFormat.CHANNEL_OUT_MONO ? 1 : 2; }
    public int getAudioFormat() { return format.encoding; }
    public int getBufferCapacityInFrames() { return capacity; }
    public int getStartThresholdInFrames() { return getterResult == null ? threshold : getterResult; }
    public int setStartThresholdInFrames(int desired) {
        if (desired < 1 || desired > capacity) throw new IllegalArgumentException("threshold fixture");
        threshold = setterResult == null ? desired : setterResult;
        return threshold;
    }
    public void simulateSinkThresholdChange(int value) { threshold = value; }
    public void play() { playing = true; plays++; }
    public int write(short[] data, int offset, int count, int mode) {
        if (state != STATE_INITIALIZED || !playing || mode != WRITE_NON_BLOCKING || count > 320) {
            throw new IllegalStateException("nonblocking device fixture contract");
        }
        writes++;
        queued += count;
        if (queued >= threshold) { played += queued; queued = 0; }
        return count;
    }
    public int getPlaybackHeadPosition() { return played; }
    public void pause() { playing = false; }
    public void flush() { queued = 0; played = 0; }
    public void release() { state = STATE_UNINITIALIZED; releases++; }

    public static final class Builder {
        private AudioFormat format;
        private int bytes;
        public Builder setAudioAttributes(AudioAttributes value) { return this; }
        public Builder setAudioFormat(AudioFormat value) { format = value; return this; }
        public Builder setTransferMode(int value) {
            if (value != MODE_STREAM) throw new IllegalArgumentException("stream fixture");
            return this;
        }
        public Builder setBufferSizeInBytes(int value) { bytes = value; return this; }
        public AudioTrack build() { return new AudioTrack(format, bytes); }
    }
}
