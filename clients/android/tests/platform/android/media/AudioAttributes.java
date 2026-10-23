package android.media;

/** JDK-only first-party fixture. Never an Android implementation or APK input. */
public final class AudioAttributes {
    public static final int USAGE_VOICE_COMMUNICATION = 2;
    public static final int CONTENT_TYPE_SPEECH = 1;
    public static final class Builder {
        public Builder setUsage(int value) { return this; }
        public Builder setContentType(int value) { return this; }
        public AudioAttributes build() { return new AudioAttributes(); }
    }
}
