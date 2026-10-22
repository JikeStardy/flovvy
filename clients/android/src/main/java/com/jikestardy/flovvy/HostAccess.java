package com.jikestardy.flovvy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Closed transport admission and non-secret identity used by UI/draft state. */
public final class HostAccess {
    private HostAccess() {}

    public static boolean permits(String path, boolean post, String token) {
        if ("/api/pair".equals(path)) return post;
        if ("/api/info".equals(path)) return !post;
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,256}")) return false;
        return (post && "/api/messages".equals(path)) || (!post && "/api/snapshot".equals(path))
                || (!post && path != null && path.matches("/api/events\\?after=[0-9]{1,19}"));
    }

    /** Only a credential fingerprint survives configuration changes, never a bearer. */
    public static final class Binding {
        private final String origin;
        private final String deviceId;
        private final byte[] credential;

        public Binding(String origin, String token, String deviceId) {
            this.origin = SessionState.origin(origin);
            if (token == null || !token.matches("[A-Za-z0-9_-]{20,256}")
                    || deviceId == null || !deviceId.matches("[a-f0-9]{24}")) {
                throw new IllegalArgumentException("BINDING_FORMAT");
            }
            this.deviceId = deviceId;
            try {
                credential = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA256_UNAVAILABLE");
            }
        }

        public boolean same(Binding other) {
            return other != null && origin.equals(other.origin) && deviceId.equals(other.deviceId)
                    && MessageDigest.isEqual(credential, other.credential);
        }
    }
}
