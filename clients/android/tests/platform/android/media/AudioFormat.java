package android.media;

/** Minimal JDK-only value fixture for compiling the actual adapter in its unit harness. */
public final class AudioFormat {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int CHANNEL_OUT_MONO = 4;
    public static final int CHANNEL_IN_MONO = 16;
    int encoding;
    int sampleRate;
    int channelMask;
    public static final class Builder {
        private final AudioFormat value = new AudioFormat();
        public Builder setEncoding(int encoding) { value.encoding = encoding; return this; }
        public Builder setSampleRate(int rate) { value.sampleRate = rate; return this; }
        public Builder setChannelMask(int mask) { value.channelMask = mask; return this; }
        public AudioFormat build() { return value; }
    }
}
