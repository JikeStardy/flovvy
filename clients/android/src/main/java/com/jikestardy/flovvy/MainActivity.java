package com.jikestardy.flovvy;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/** Native foreground text presentation of the same authenticated Fairy Host. */
public final class MainActivity extends Activity {
    private final SessionState session = new SessionState();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CredentialStore store;
    private CredentialStore.Credentials credentials;
    private TextDraft draft;
    private HostClient client;
    private EditText origin;
    private EditText pairing;
    private EditText compose;
    private TextView status;
    private TextView history;
    private Button send;
    private Button newMessage;
    private boolean resumed;
    private boolean snapshotPending;
    private boolean sending;
    private long cursor;
    private long snapshotSerial;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        draft = new TextDraft(retained instanceof TextDraft.Retained ? (TextDraft.Retained) retained : null);
        store = new CredentialStore(this);
        buildViews();
        try {
            credentials = store.load();
            draft.bind(binding());
            if (credentials != null) origin.setText(credentials.origin);
        } catch (GeneralSecurityException | IOException exception) {
            draft.bind(null);
            notice("本机加密连接凭据不可用。请清除本机绑定并重新配对。");
        }
        renderDraft();
        updateSend();
    }

    private void buildViews() {
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(16);
        layout.setPadding(margin, margin, margin, margin);
        layout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
            view.setPadding(margin + bars.left, margin + bars.top, margin + bars.right,
                    margin + Math.max(bars.bottom, keyboard.bottom));
            return windowInsets;
        });
        TextView title = label("Fairy", 26);
        layout.addView(title);
        layout.addView(label("原生文字客户端 · " + BuildInfo.VERSION, 12));
        layout.addView(label("实时语音、资料阅读与学习进度尚未接入。后台任务继续由 Host 管理。", 13));
        origin = field("HTTPS Host 地址，例如 https://host.example:8765", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        origin.setSingleLine(true);
        origin.setSaveEnabled(false);
        layout.addView(origin);
        pairing = field("一次性配对码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pairing.setSingleLine(true);
        pairing.setSaveEnabled(false);
        pairing.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pairing.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        layout.addView(pairing);
        LinearLayout connection = new LinearLayout(this);
        connection.addView(button("配对", view -> pair()), new LinearLayout.LayoutParams(0, -2, 1));
        connection.addView(button("重连", view -> connectSaved()), new LinearLayout.LayoutParams(0, -2, 1));
        connection.addView(button("清除本机绑定", view -> forget()), new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(connection);
        status = label("在 Host 生成一次性配对码后连接。", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);
        layout.addView(button("刷新历史 / 核对回执", view -> {
            if (canRead(currentEpoch)) loadSnapshot(currentEpoch);
            else if (session.connection() == SessionState.Connection.PAIRING) notice("请等待当前配对完成，再读取已绑定 Host。");
            else connectSaved();
        }));
        history = label("连接后显示 Host 的最近 100 条历史及当前生成内容。", 16);
        history.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(history);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        compose = field("想和 Fairy 说什么…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        compose.setMinLines(2);
        compose.setMaxLines(5);
        compose.setSaveEnabled(false);
        compose.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        compose.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { draft.edit(text.toString()); }
        });
        layout.addView(compose);
        LinearLayout actions = new LinearLayout(this);
        send = button("发送", view -> sendMessage());
        send.setEnabled(false);
        actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        newMessage = button("另写新消息", view -> {
            if (sending) return;
            draft.newMessage();
            sending = false;
            renderDraft();
            snapshotSerial++;
            updateSend();
            notice("旧请求仍可能在 Host 完成；另写消息不会取消它。");
        });
        newMessage.setVisibility(View.GONE);
        actions.addView(newMessage, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(actions);
        setContentView(layout);
    }

    private long currentEpoch;

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (credentials != null) connectSaved();
    }

    @Override protected void onPause() {
        resumed = false;
        disconnect();
        super.onPause();
    }

    @Override protected void onStop() {
        disconnect();
        super.onStop();
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        rememberDraft();
        return draft.retain();
    }

    private void disconnect() {
        rememberDraft();
        session.invalidate();
        snapshotSerial++;
        handler.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        client = null;
        snapshotPending = false;
        pollPending = false;
        sending = false;
        if (send != null) updateSend();
    }

    private void pair() {
        if (!resumed) return;
        final String address;
        try { address = SessionState.origin(origin.getText().toString()); }
        catch (IllegalArgumentException exception) { notice("请填写没有路径、账号或参数的 HTTPS Host 地址。"); return; }
        String code = pairing.getText().toString().trim();
        disconnect();
        currentEpoch = session.beginPairing();
        final long epoch = currentEpoch;
        client = new HostClient(address, "", null, handler);
        updateSend();
        notice("正在配对；不会重试一次性配对请求。");
        client.pair(code, new HostClient.Callback() {
            @Override public void success(JSONObject result) {
                if (!session.accepts(epoch)) return;
                try {
                    CredentialStore.Credentials candidate = new CredentialStore.Credentials(address,
                            result.getString("token"), result.getString("device_id"));
                    store.save(candidate);
                    credentials = candidate;
                    draft.bind(candidate.binding);
                    renderDraft();
                    pairing.setText("");
                    connectSaved();
                } catch (GeneralSecurityException | IOException | JSONException | IllegalArgumentException exception) {
                    disconnect();
                    notice("配对返回或本机加密存储失败。请在 Host 核对设备并生成新的配对码。");
                }
            }
            @Override public void failure(HostClient.Failure failure) {
                if (!session.accepts(epoch)) return;
                disconnect();
                notice(failure.uncertain ? "配对回执未知；请在 Host 核对设备，重新生成配对码。" : "配对失败。请核对 HTTPS 地址与一次性配对码。");
            }
        });
    }

    private void connectSaved() {
        if (!resumed) return;
        if (credentials == null) { notice("请先完成配对。不会向未绑定地址发送消息。"); return; }
        disconnect();
        draft.bind(credentials.binding);
        currentEpoch = session.beginBoundConnection(credentials.binding);
        client = new HostClient(credentials.origin, credentials.token, credentials.deviceId, handler);
        renderDraft();
        updateSend();
        origin.setText(credentials.origin);
        cursor = 0;
        history.setText("");
        notice("正在连接已绑定的 Host…");
        loadSnapshot(currentEpoch);
    }

    private boolean forget() {
        disconnect();
        credentials = null;
        draft.bind(null);
        renderDraft();
        updateSend();
        history.setText("");
        pairing.setText("");
        try {
            store.clear();
            notice("已清除本机凭据；如需撤销服务端设备权限，请在 Host 管理界面操作。");
            return true;
        } catch (GeneralSecurityException | IOException exception) {
            notice("本机凭据清理未完成。请先在 Host 撤销此设备；本次不会继续连接。");
            return false;
        }
    }

    private void loadSnapshot(long epoch) {
        if (!canRead(epoch) || snapshotPending) return;
        final HostAccess.Binding snapshotBinding = binding();
        snapshotPending = true;
        final long serial = snapshotSerial;
        client.snapshot(new HostClient.Callback() {
            @Override public void success(JSONObject result) {
                if (!canRead(epoch)) return;
                snapshotPending = false;
                if (serial != snapshotSerial) { loadSnapshot(epoch); return; }
                try {
                    long latest = integer(result, "latest_event_id");
                    if (latest < cursor) { loadSnapshot(epoch); return; }
                    JSONArray messages = result.getJSONArray("messages");
                    if (messages.length() > 500) throw new JSONException("HISTORY_LIMIT");
                    StringBuilder text = new StringBuilder();
                    boolean confirmed = false;
                    TextDraft.Pending pending = draft.pending();
                    for (int index = 0; index < messages.length(); index++) {
                        JSONObject message = messages.getJSONObject(index);
                        String content = message.getString("content");
                        String role = message.getString("role");
                        text.append("assistant".equals(role) ? "Fairy" : "你");
                        if ("pending".equals(message.optString("status"))) text.append(" · 生成中");
                        text.append("\n").append(content).append("\n\n");
                        if (pending != null && pending.requestId.equals(message.optString("request_id"))) confirmed = true;
                    }
                    history.setText(text.length() == 0 ? "这里还没有历史。可以开始聊天。" : text.toString());
                    cursor = latest;
                    if (!session.snapshotReady(epoch, snapshotBinding)) return;
                    if (confirmed && !sending) acceptedMessage(pending.requestId, snapshotBinding);
                    renderDraft();
                    notice(draft.pending() == null ? "Host 历史已同步" : "上一条回执尚未确认；可核对历史或手动重试同一请求。");
                    updateSend();
                    schedulePoll(epoch, 0, 0);
                } catch (JSONException exception) { formatFailure(epoch); }
            }
            @Override public void failure(HostClient.Failure failure) {
                if (!canRead(epoch)) return;
                snapshotPending = false;
                handleFailure(epoch, failure);
            }
        });
    }

    private boolean pollPending;

    private void schedulePoll(long epoch, int failures, long delay) {
        if (!canRead(epoch) || pollPending) return;
        pollPending = true;
        handler.postDelayed(() -> {
            if (!canRead(epoch)) { pollPending = false; return; }
            final long requestedCursor = cursor;
            client.events(requestedCursor, new HostClient.Callback() {
                @Override public void success(JSONObject result) {
                    if (!canRead(epoch)) return;
                    pollPending = false;
                    try {
                        long next = integer(result, "cursor");
                        JSONArray events = result.getJSONArray("events");
                        if (events.length() > 250) throw new JSONException("EVENT_FORMAT");
                        if (!SessionState.acceptsCursor(requestedCursor, cursor, next)) {
                            schedulePoll(epoch, 0, 50);
                            return;
                        }
                        if (events.length() > 0) loadSnapshot(epoch);
                        else schedulePoll(epoch, 0, 50);
                    } catch (JSONException | IllegalArgumentException exception) { formatFailure(epoch); }
                }
                @Override public void failure(HostClient.Failure failure) {
                    if (!canRead(epoch)) return;
                    pollPending = false;
                    if ("REVOKED".equals(failure.code)) { handleFailure(epoch, failure); return; }
                    if (failures >= 3) {
                        notice("连接暂时中断；点击重连。后台任务仍由 Host 管理。");
                        return;
                    }
                    notice("读取状态失败，正在有界重连…");
                    schedulePoll(epoch, failures + 1, 1000L << failures);
                }
            });
        }, delay);
    }

    private void sendMessage() {
        if (!canSend()) { notice("当前尚未鉴权并核对历史；不会向未绑定地址发送文字。"); return; }
        final HostAccess.Binding requestBinding = binding();
        final TextDraft.Pending pending;
        try { pending = draft.submit(requestBinding, UUID.randomUUID().toString(), compose.getText().toString()); }
        catch (IllegalArgumentException exception) { notice("请输入非空文字，最多 16,000 个 Unicode 字符；不会截断发送。"); return; }
        final SessionState.Request request = session.beginRequest(currentEpoch, "owner", pending.requestId);
        sending = true;
        snapshotSerial++;
        compose.setEnabled(false);
        updateSend();
        notice("正在提交文字…");
        client.send(pending.text, request.requestId, new HostClient.Callback() {
            @Override public void success(JSONObject result) {
                if (!session.accepts(request) || !canRead(request.epoch)) return;
                try {
                    String turn = result.getString("turn_id");
                    if (turn.isEmpty() || turn.length() > 120) throw new JSONException("TURN_FORMAT");
                    acceptedMessage(request.requestId, requestBinding);
                    notice("Host 已接收；正在等待回复。");
                    loadSnapshot(request.epoch);
                } catch (JSONException exception) { unknownMessage(); }
            }
            @Override public void failure(HostClient.Failure failure) {
                if (!session.accepts(request) || !canRead(request.epoch)) return;
                sending = false;
                if (failure.uncertain) unknownMessage();
                else if ("REVOKED".equals(failure.code)) handleFailure(request.epoch, failure);
                else {
                    draft.rejected(request.requestId, requestBinding);
                    renderDraft();
                    notice("文字未被确认接收。请核对输入或连接；没有自动重发。");
                    updateSend();
                }
            }
        });
    }

    private void unknownMessage() {
        sending = false;
        notice("提交结果未知，未自动重发。请先刷新历史；手动重试会沿用同一请求标识。");
        updateSend();
    }

    private void acceptedMessage(String requestId, HostAccess.Binding candidate) {
        if (!draft.accept(requestId, candidate)) return;
        sending = false;
        renderDraft();
        updateSend();
    }

    private void updateSend() {
        send.setEnabled(canSend());
        send.setText(draft.pending() == null ? "发送" : "重试同一请求");
        newMessage.setVisibility(draft.pending() != null && !sending ? View.VISIBLE : View.GONE);
    }

    private HostAccess.Binding binding() { return credentials == null ? null : credentials.binding; }

    private boolean canRead(long epoch) {
        HostAccess.Binding candidate = binding();
        return resumed && client != null && client.isBoundTo(candidate) && session.mayRead(epoch, candidate);
    }

    private boolean canSend() {
        return canRead(currentEpoch) && !sending && draft.isBoundTo(binding()) && session.maySend(currentEpoch, binding());
    }

    private void rememberDraft() { if (draft != null && compose != null) draft.edit(compose.getText().toString()); }

    private void renderDraft() {
        String text = draft.text();
        if (!compose.getText().toString().equals(text)) {
            compose.setText(text);
            compose.setSelection(text.length());
        }
        compose.setEnabled(draft.pending() == null);
    }

    private void handleFailure(long epoch, HostClient.Failure failure) {
        if (!session.accepts(epoch)) return;
        if ("REVOKED".equals(failure.code)) {
            if (forget()) notice("设备权限无效或已撤销。连接已停止，请重新配对。");
            else notice("设备权限无效，连接已停止；本机凭据清理未完成，不能视为已清除。");
        } else notice("Host 读取失败。请核对 HTTPS 证书与网络，再点击重连。");
    }

    private void formatFailure(long epoch) {
        if (!session.accepts(epoch)) return;
        disconnect();
        notice("Host 返回格式或大小不受支持，连接已停止。已有文字没有被截断冒充完整结果。");
    }

    private static long integer(JSONObject value, String key) throws JSONException {
        Object number = value.get(key);
        if (!(number instanceof Integer) && !(number instanceof Long)) throw new JSONException("INTEGER_REQUIRED");
        long result = ((Number) number).longValue();
        if (result < 0) throw new JSONException("INTEGER_REQUIRED");
        return result;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); return view; }
    private EditText field(String hint, int type) { EditText view = new EditText(this); view.setHint(hint); view.setInputType(type); return view; }
    private Button button(String text, View.OnClickListener action) { Button view = new Button(this); view.setText(text); view.setOnClickListener(action); return view; }
    private void notice(String text) {
        SessionState.Connection connection = session.connection();
        String state = connection == SessionState.Connection.AUTHENTICATED ? "已鉴权"
                : connection == SessionState.Connection.PAIRING ? "配对中"
                : connection == SessionState.Connection.CONNECTING ? "正在核对绑定" : "未连接";
        status.setText(state + " · " + text);
    }
}
