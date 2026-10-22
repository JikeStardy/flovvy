package com.jikestardy.flovvy;

import android.os.Handler;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Owns finite HTTPS work; callbacks return to this Activity's private Handler. */
public final class HostClient implements AutoCloseable {
    public interface Callback {
        void success(JSONObject response);
        void failure(Failure failure);
    }

    public static final class Failure {
        public final String code;
        public final boolean uncertain;
        Failure(String code, boolean uncertain) { this.code = code; this.uncertain = uncertain; }
    }

    private final String origin;
    private final String token;
    private final HostAccess.Binding binding;
    private final Handler handler;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());
    private final Set<HttpsURLConnection> active = new HashSet<>();
    private volatile boolean closed;

    public HostClient(String origin, String token, String deviceId, Handler handler) {
        this.origin = SessionState.origin(origin);
        if (token == null || (!token.isEmpty() && !token.matches("[A-Za-z0-9_-]{20,256}"))) {
            throw new IllegalArgumentException("INVALID_TOKEN");
        }
        this.token = token;
        this.binding = token.isEmpty() ? null : new HostAccess.Binding(this.origin, token, deviceId);
        this.handler = handler;
    }

    public boolean isBoundTo(HostAccess.Binding candidate) {
        return !closed && binding != null && binding.same(candidate);
    }

    public void pair(String code, Callback callback) {
        if (code == null || code.isEmpty() || code.length() > 80 || !code.matches("[A-Za-z0-9_-]+")) {
            callback.failure(new Failure("PAIR_CODE", false));
            return;
        }
        try {
            request("/api/pair", new JSONObject().put("code", code).put("name", "Android"), false, callback);
        } catch (JSONException exception) { callback.failure(new Failure("REQUEST_FORMAT", false)); }
    }

    public void snapshot(Callback callback) { request("/api/snapshot", null, false, callback); }

    public void events(long after, Callback callback) {
        if (after < 0) { callback.failure(new Failure("EVENT_CURSOR", false)); return; }
        request("/api/events?after=" + after, null, true, callback);
    }

    public void send(String content, String requestId, Callback callback) {
        if (!admit("/api/messages", true, callback)) return;
        try {
            SessionState.validateText(content);
            if (requestId == null || !requestId.matches("[A-Za-z0-9:-]{1,120}")) {
                throw new IllegalArgumentException("INVALID_REQUEST");
            }
            request("/api/messages", new JSONObject().put("content", content)
                    .put("request_id", requestId).put("conversation_id", "owner")
                    .put("private", false), false, callback);
        } catch (IllegalArgumentException | JSONException exception) {
            callback.failure(new Failure("INPUT_LIMIT", false));
        }
    }

    private void request(String path, JSONObject body, boolean poll, Callback callback) {
        if (!admit(path, body != null, callback)) return;
        final byte[] encoded;
        try {
            encoded = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded != null) SessionState.validateBody(encoded);
        } catch (IllegalArgumentException exception) {
            callback.failure(new Failure("INPUT_LIMIT", false));
            return;
        }
        try {
            executor.execute(() -> perform(path, encoded, poll, callback));
        } catch (RejectedExecutionException exception) {
            deliver(callback, null, new Failure("CLOSED", false));
        }
    }

    private void perform(String path, byte[] body, boolean poll, Callback callback) {
        if (!admit(path, body != null, callback)) return;
        HttpsURLConnection connection = null;
        boolean submitted = false;
        try {
            connection = (HttpsURLConnection) new URL(origin + path).openConnection();
            synchronized (active) {
                if (closed) return;
                active.add(connection);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(poll ? 25000 : 10000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                submitted = true;
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                String code = status == 401 ? "REVOKED" : (status >= 300 && status < 400 ? "REDIRECT" : "HTTP_ERROR");
                deliver(callback, null, new Failure(code, body != null && status >= 500));
                return;
            }
            String type = connection.getHeaderField("Content-Type");
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                deliver(callback, null, new Failure("RESPONSE_FORMAT", submitted));
                return;
            }
            byte[] bytes;
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (closed) return;
                    if (result.size() + count > 2 * 1024 * 1024) {
                        deliver(callback, null, new Failure("RESPONSE_LIMIT", submitted));
                        return;
                    }
                    result.write(buffer, 0, count);
                }
                bytes = result.toByteArray();
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            deliver(callback, new JSONObject(text), null);
        } catch (CharacterCodingException | JSONException exception) {
            deliver(callback, null, new Failure("RESPONSE_FORMAT", submitted));
        } catch (IOException | SecurityException exception) {
            deliver(callback, null, new Failure("NETWORK", submitted));
        } finally {
            if (connection != null) {
                synchronized (active) { active.remove(connection); }
                connection.disconnect();
            }
        }
    }

    private boolean admit(String path, boolean post, Callback callback) {
        if (HostAccess.permits(path, post, token)) return true;
        deliver(callback, null, new Failure("UNAUTHENTICATED", false));
        return false;
    }

    private void deliver(Callback callback, JSONObject result, Failure failure) {
        if (closed) return;
        handler.post(() -> {
            if (closed) return;
            if (failure != null) callback.failure(failure);
            else callback.success(result);
        });
    }

    @Override public void close() {
        HttpsURLConnection[] connections;
        synchronized (active) {
            if (closed) return;
            closed = true;
            connections = active.toArray(new HttpsURLConnection[0]);
        }
        executor.shutdownNow();
        for (HttpsURLConnection connection : connections) connection.disconnect();
    }
}
