package com.jikestardy.flovvy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Pure state and input boundary shared by the Activity and the JDK harness. */
public final class SessionState {
    public enum Connection { DISCONNECTED, PAIRING, CONNECTING, AUTHENTICATED }
    private static long lastEpoch;
    private long epoch;
    private long requestSequence;
    private long outputEpoch;
    private boolean active;
    private Connection connection = Connection.DISCONNECTED;
    private HostAccess.Binding binding;

    public synchronized long beginConnection() {
        epoch = newEpoch();
        requestSequence = next(requestSequence);
        active = true;
        connection = Connection.DISCONNECTED;
        binding = null;
        return epoch;
    }

    public synchronized long beginPairing() {
        long result = beginConnection();
        connection = Connection.PAIRING;
        return result;
    }

    public synchronized long beginBoundConnection(HostAccess.Binding candidate) {
        if (candidate == null) throw new IllegalArgumentException("BINDING_REQUIRED");
        long result = beginConnection();
        binding = candidate;
        connection = Connection.CONNECTING;
        return result;
    }

    public synchronized boolean mayRead(long candidate, HostAccess.Binding identity) {
        return accepts(candidate) && binding != null && binding.same(identity)
                && (connection == Connection.CONNECTING || connection == Connection.AUTHENTICATED);
    }

    public synchronized boolean snapshotReady(long candidate, HostAccess.Binding identity) {
        if (!mayRead(candidate, identity)) return false;
        connection = Connection.AUTHENTICATED;
        return true;
    }

    public synchronized boolean maySend(long candidate, HostAccess.Binding identity) {
        return mayRead(candidate, identity) && connection == Connection.AUTHENTICATED;
    }

    public synchronized Connection connection() { return connection; }

    public synchronized void invalidate() {
        active = false;
        connection = Connection.DISCONNECTED;
        binding = null;
        epoch = newEpoch();
        requestSequence = next(requestSequence);
        stopOutput();
    }

    public synchronized boolean accepts(long candidate) {
        return active && candidate == epoch;
    }

    public synchronized Request beginRequest(long candidate, String conversation, String requestId) {
        if (!accepts(candidate) || conversation == null || !conversation.equals("owner")
                || requestId == null || !requestId.matches("[A-Za-z0-9:-]{1,120}")) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        requestSequence = next(requestSequence);
        return new Request(epoch, requestSequence, conversation, requestId);
    }

    public synchronized boolean accepts(Request request) {
        return request != null && accepts(request.epoch) && request.sequence == requestSequence;
    }

    public synchronized void stopOutput() { outputEpoch = next(outputEpoch); }
    public synchronized long outputEpoch() { return outputEpoch; }

    private static long next(long value) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException("GENERATION_EXHAUSTED");
        return value + 1;
    }

    private static synchronized long newEpoch() {
        lastEpoch = next(lastEpoch);
        return lastEpoch; // New Activity owners cannot accidentally reuse an old connection ID.
    }

    public static String origin(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("HTTPS_ORIGIN");
        try {
            URI uri = new URI(value.trim());
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (path != null && !path.isEmpty() && !path.equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("HTTPS_ORIGIN");
            }
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(), null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("HTTPS_ORIGIN");
        }
    }

    public static void validateText(String text) {
        if (text == null) throw new IllegalArgumentException("TEXT_REQUIRED");
        int count = 0;
        boolean content = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i))) {
                    throw new IllegalArgumentException("INVALID_UNICODE");
                }
                content = true;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("INVALID_UNICODE");
            } else if (!Character.isWhitespace(current) && !Character.isSpaceChar(current)) {
                content = true;
            }
            if (++count > 16000) throw new IllegalArgumentException("TEXT_TOO_LONG");
        }
        if (!content) throw new IllegalArgumentException("TEXT_REQUIRED");
    }

    public static void validateBody(byte[] bytes) {
        if (bytes == null || bytes.length > 65536) throw new IllegalArgumentException("REQUEST_TOO_LARGE");
    }

    public static boolean acceptsCursor(long requested, long current, long received) {
        if (requested < 0 || current < 0 || received < requested) {
            throw new IllegalArgumentException("EVENT_CURSOR");
        }
        return received >= current;
    }

    public static final class Request {
        public final long epoch;
        public final long sequence;
        public final String conversation;
        public final String requestId;

        private Request(long epoch, long sequence, String conversation, String requestId) {
            this.epoch = epoch;
            this.sequence = sequence;
            this.conversation = conversation;
            this.requestId = requestId;
        }
    }
}
