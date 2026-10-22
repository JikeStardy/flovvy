package com.jikestardy.flovvy;

/** No Android/JUnit dependencies: execute this harness on the JDK17 runner. */
public final class SessionStateTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void rejects(Runnable action, String label) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }

    public static void main(String[] args) {
        SessionState state = new SessionState();
        long first = state.beginConnection();
        check(state.accepts(first), "current connection accepted");
        long second = state.beginConnection();
        check(!state.accepts(first) && state.accepts(second), "old connection invalidated");
        state.invalidate();
        state.invalidate();
        check(!state.accepts(second), "repeated stop cannot revive callbacks");
        long resumed = state.beginConnection();
        check(state.accepts(resumed) && !state.accepts(second), "reconnect uses new epoch");
        SessionState.Request old = state.beginRequest(resumed, "owner", "request-a");
        check(state.accepts(old), "admitted request accepted");
        SessionState.Request fresh = state.beginRequest(resumed, "owner", "request-b");
        check(!state.accepts(old) && state.accepts(fresh), "late previous request ignored");
        state.stopOutput();
        state.stopOutput();
        check(state.accepts(fresh), "output stop does not cancel text task request");
        state.invalidate();
        check(!state.accepts(fresh), "exit rejects pending text callback");
        check(SessionState.origin("https://example.test:8765/").equals("https://example.test:8765"), "origin normalized");
        rejects(() -> SessionState.origin("http://example.test"), "cleartext rejected");
        rejects(() -> SessionState.origin("https://user:secret@example.test"), "URL credentials rejected");
        rejects(() -> SessionState.origin("https://example.test/path"), "path rejected");
        rejects(() -> SessionState.origin("https://example.test?token=secret"), "query rejected");
        rejects(() -> SessionState.origin("https://example.test#fragment"), "fragment rejected");
        SessionState.validateText("中文 😀 \"quoted\"\n");
        rejects(() -> SessionState.validateText(" \n"), "blank message rejected");
        rejects(() -> SessionState.validateText("\uD800"), "unpaired surrogate rejected");
        rejects(() -> SessionState.validateText(repeat("x", 16001)), "codepoint bound enforced");
        SessionState.validateText(repeat("😀", 16000));
        rejects(() -> SessionState.validateBody(new byte[65537]), "UTF8 JSON body bound enforced");
        SessionState.validateBody(new byte[65536]);
        check(!SessionState.acceptsCursor(5, 12, 9), "late valid event response ignored after newer snapshot");
        check(SessionState.acceptsCursor(5, 12, 12), "equal cursor response remains valid");
        rejects(() -> SessionState.acceptsCursor(5, 12, 4), "server cannot move behind its requested cursor");
        check(state.outputEpoch() > 0, "local output invalidation tracked independently");
        retainedDraftAndBinding();
        System.out.println("SessionState harness PASS: " + checks + " checks");
    }

    private static void retainedDraftAndBinding() {
        HostAccess.Binding a = new HostAccess.Binding("https://a.example.test", repeat("a", 32), repeat("a", 24));
        HostAccess.Binding sameA = new HostAccess.Binding("https://a.example.test", repeat("a", 32), repeat("a", 24));
        HostAccess.Binding b = new HostAccess.Binding("https://b.example.test", repeat("b", 32), repeat("b", 24));
        HostAccess.Binding newToken = new HostAccess.Binding("https://a.example.test", repeat("c", 32), repeat("a", 24));
        HostAccess.Binding newDevice = new HostAccess.Binding("https://a.example.test", repeat("a", 32), repeat("d", 24));
        check(a.same(sameA) && !a.same(b) && !a.same(newToken) && !a.same(newDevice), "binding closes origin/device/credential");
        TextDraft draft = new TextDraft(null);
        draft.bind(a);
        draft.edit("ordinary unsent draft");
        TextDraft restoredDraft = new TextDraft(draft.retain());
        restoredDraft.bind(sameA);
        check(restoredDraft.text().equals("ordinary unsent draft"), "configuration recreation preserves unsent draft");
        TextDraft.Pending pending = draft.submit(a, "logical-x", "unknown original text");
        SessionState oldActivity = new SessionState();
        long oldEpoch = oldActivity.beginBoundConnection(a);
        check(oldActivity.snapshotReady(oldEpoch, a), "first authenticated snapshot accepted");
        SessionState.Request oldCallback = oldActivity.beginRequest(oldEpoch, "owner", pending.requestId);
        oldActivity.invalidate(); // The actual Activity invalidates before closing its client.
        TextDraft restored = new TextDraft(draft.retain());
        draft.newMessage();
        restored.bind(sameA);
        SessionState replacement = new SessionState();
        long freshEpoch = replacement.beginBoundConnection(sameA);
        check(restored.pending().requestId.equals("logical-x") && restored.text().equals("unknown original text"),
                "recreation retains original logical ID/text in pure state");
        check(restored.pending() != draft.pending(), "old owner mutation cannot clear immutable handoff state");
        check(oldEpoch != freshEpoch && !oldActivity.accepts(oldCallback) && !replacement.accepts(oldCallback)
                && !replacement.maySend(freshEpoch, sameA),
                "old callback rejected and fresh snapshot required before manual retry");
        check(replacement.snapshotReady(freshEpoch, sameA) && replacement.maySend(freshEpoch, sameA),
                "fresh snapshot permits explicit same-binding action");
        TextDraft.Pending retry = restored.submit(sameA, "must-not-replace-id", "must-not-replace-text");
        check(retry.requestId.equals("logical-x") && retry.text.equals("unknown original text"),
                "manual retry keeps original ID/text after recreation");
        check(!restored.accept("other-id", a), "unrelated snapshot receipt cannot clear pending");
        check(restored.accept("logical-x", sameA) && restored.pending() == null, "matching snapshot confirms old receipt without resending");
        check(new TextDraft(null).pending() == null, "process restart has no stored plaintext or automatic replay");

        // Controlled callback order: unknown A -> pair B -> another draft -> premature send.
        SessionState ui = new SessionState();
        TextDraft text = new TextDraft(null);
        text.bind(a);
        long connectedA = ui.beginBoundConnection(a);
        ui.snapshotReady(connectedA, a);
        text.submit(a, "pending-a", "private original A");
        long pairB = ui.beginPairing();
        text.newMessage();
        text.edit("new text while B is pairing");
        check(ui.connection() == SessionState.Connection.PAIRING && !ui.maySend(pairB, a)
                && !ui.mayRead(pairB, a), "pairing client never inherits saved A authorization");
        int[] privateWrites = {0};
        if (HostAccess.permits("/api/messages", true, "")) privateWrites[0]++;
        check(privateWrites[0] == 0, "actual transport admission refuses unauthenticated body before work");
        check(HostAccess.permits("/api/pair", true, "") && HostAccess.permits("/api/info", false, ""), "closed public endpoint list");
        check(!HostAccess.permits("/api/pair", false, "") && !HostAccess.permits("/api/info?extra=1", false, "")
                && !HostAccess.permits("/api/snapshot", false, "") && !HostAccess.permits("/api/events?after=0", false, ""),
                "public access cannot spread to other methods/queries/private reads");
        ui.invalidate(); // Pair failed: credentials A remain recoverable, but not connected.
        check(ui.connection() == SessionState.Connection.DISCONNECTED && !ui.maySend(pairB, a), "pair failure leaves disconnected state");
        long recoveredA = ui.beginBoundConnection(a);
        check(!ui.snapshotReady(pairB, b) && !ui.maySend(recoveredA, a), "stale pair callback cannot authenticate recovered A");
        check(ui.snapshotReady(recoveredA, a) && ui.maySend(recoveredA, a), "saved A recovers after a fresh snapshot");

        TextDraft preserved = new TextDraft(null);
        preserved.bind(a);
        preserved.submit(a, "unknown-before-pair", "original before pair failure");
        ui.beginPairing();
        ui.invalidate();
        long retryA = ui.beginBoundConnection(a);
        ui.snapshotReady(retryA, a);
        TextDraft.Pending recoveredPending = preserved.submit(a, "ignored-after-failure", "ignored after failure");
        check(ui.maySend(retryA, a) && recoveredPending.requestId.equals("unknown-before-pair")
                && recoveredPending.text.equals("original before pair failure"), "failed pairing preserves same-binding manual retry");

        text.submit(a, "old-a", "do not migrate this text");
        rejects(() -> text.submit(b, "new-b", "ignored"), "pending cannot be retried at another host");
        rejects(() -> text.submit(newToken, "new-token", "ignored"), "pending cannot be retried with another token");
        rejects(() -> text.submit(newDevice, "new-device", "ignored"), "pending cannot be retried with another device");
        long pairingAgain = ui.beginPairing();
        check(ui.accepts(pairingAgain) && !ui.maySend(pairingAgain, b), "pair success alone never opens text gate");
        text.bind(b); // Actual successful pair changes the binding and clears old plaintext.
        check(text.pending() == null && text.text().isEmpty(), "successful rebind clears original host draft/pending");
        long connectedB = ui.beginBoundConnection(b);
        check(!ui.maySend(connectedB, b) && ui.snapshotReady(connectedB, b) && ui.maySend(connectedB, b),
                "new pair requires snapshot before normal sending");
        check(!ui.maySend(connectedB, a) && !ui.snapshotReady(connectedB, a), "client and saved credentials must match binding");
        check(HostAccess.permits("/api/messages", true, repeat("b", 32))
                && HostAccess.permits("/api/snapshot", false, repeat("b", 32)), "authenticated normal endpoints admitted");
        check(!HostAccess.permits("/api/unreviewed", true, repeat("b", 32)), "unknown endpoint not admitted");
        TextDraft.Pending normal = text.submit(b, "new-b", "normal text");
        SessionState.Request independent = ui.beginRequest(connectedB, "owner", normal.requestId);
        text.newMessage();
        check(ui.accepts(independent), "another draft does not cancel the independent Host task");
        text.edit("cleared on credential rotation");
        text.bind(newToken);
        check(text.text().isEmpty(), "even a same-origin token change cannot migrate a draft");
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
}
