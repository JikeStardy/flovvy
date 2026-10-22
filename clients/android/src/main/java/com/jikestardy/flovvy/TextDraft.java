package com.jikestardy.flovvy;

/** Process-only logical text state. It cannot own an Activity, callback or network. */
public final class TextDraft {
    private HostAccess.Binding binding;
    private String draft;
    private Pending pending;

    public TextDraft(Retained retained) {
        binding = retained == null ? null : retained.binding;
        draft = retained == null ? "" : retained.draft;
        pending = retained == null ? null : retained.pending;
    }

    public void bind(HostAccess.Binding candidate) {
        if (binding == null || !binding.same(candidate)) {
            draft = "";
            pending = null;
        }
        binding = candidate;
    }

    public boolean isBoundTo(HostAccess.Binding candidate) { return binding != null && binding.same(candidate); }
    public void edit(String text) { if (pending == null) draft = text; }
    public String text() { return pending == null ? draft : pending.text; }
    public Pending pending() { return pending; }
    public void newMessage() { draft = ""; pending = null; }

    public Pending submit(HostAccess.Binding candidate, String requestId, String text) {
        if (!isBoundTo(candidate)) throw new IllegalArgumentException("DRAFT_BINDING");
        if (pending != null) {
            if (!pending.binding.same(candidate)) throw new IllegalArgumentException("DRAFT_BINDING");
            return pending;
        }
        SessionState.validateText(text);
        if (requestId == null || !requestId.matches("[A-Za-z0-9:-]{1,120}")) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        draft = text;
        pending = new Pending(candidate, requestId, text);
        return pending;
    }

    public boolean accept(String requestId, HostAccess.Binding candidate) {
        if (!matches(requestId, candidate)) return false;
        newMessage();
        return true;
    }

    public void rejected(String requestId, HostAccess.Binding candidate) {
        if (matches(requestId, candidate)) pending = null; // Keep the editable original draft.
    }

    private boolean matches(String requestId, HostAccess.Binding candidate) {
        return pending != null && isBoundTo(candidate) && pending.binding.same(candidate)
                && pending.requestId.equals(requestId);
    }

    public Retained retain() { return new Retained(binding, draft, pending); }

    /** Immutable snapshot passed through the platform's non-configuration handoff. */
    public static final class Retained {
        private final HostAccess.Binding binding;
        private final String draft;
        private final Pending pending;
        private Retained(HostAccess.Binding binding, String draft, Pending pending) {
            this.binding = binding; this.draft = draft; this.pending = pending;
        }
    }

    public static final class Pending {
        private final HostAccess.Binding binding;
        public final String requestId;
        public final String text;
        private Pending(HostAccess.Binding binding, String requestId, String text) {
            this.binding = binding; this.requestId = requestId; this.text = text;
        }
    }
}
