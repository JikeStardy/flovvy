package com.jikestardy.flovvy;

import android.content.Context;
import android.media.AudioRecord;
import java.util.Arrays;

/** Actual adapter with first-party JDK-only call fixtures, never device evidence. */
public final class AndroidPcmInputTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static RuntimeException fails(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { checks++; return error; }
        throw new AssertionError("expected explicit adapter failure");
    }
    public static void main(String[] args) {
        actualShortRead();
        permissionChanges();
        actualFormatAndState();
        releaseEvidence();
        System.out.println("AndroidPcmInput fixture harness PASS: " + checks + " checks; no device evidence");
    }
    private static void actualShortRead() {
        PcmInput input = AndroidPcmInput.create(new Context()); PcmInput.Lease lease = input.begin();
        AudioRecord actual = AudioRecord.lastCreated; actual.readResult = 4;
        check(input.pump(lease) == 8, "actual adapter reads shorts with fixed nonblocking arguments");
        input.finish(lease); PcmInput.Packet packet = input.nextPacket(lease);
        check(packet != null && Arrays.equals(packet.bytes(), new byte[]{0,0,-1,127,0,-128,-1,-1}),
                "actual adapter/core produce signed LE bytes");
        check(actual.stops == 1 && actual.releases == 1, "same actual recorder stops and releases");
        input.acknowledge(lease, packet); input.complete(lease);
    }
    private static void permissionChanges() {
        Context denied = new Context(); denied.permission = -1; AudioRecord.lastCreated = null;
        PcmInput noOpen = AndroidPcmInput.create(denied);
        check("AUDIO_INPUT_PERMISSION".equals(fails(noOpen::begin).getMessage())
                && AudioRecord.lastCreated == null && !noOpen.cleanupPending(), "permission checked before actual construction");
        Context revoked = new Context(); AudioRecord.nextBuildAction = () -> revoked.permission = -1;
        PcmInput noStart = AndroidPcmInput.create(revoked);
        check("AUDIO_INPUT_PERMISSION".equals(fails(noStart::begin).getMessage())
                && AudioRecord.lastCreated.starts == 0 && AudioRecord.lastCreated.releases == 1,
                "permission rechecked after construction while actual owner retained");
        for (boolean during : new boolean[]{false, true}) {
            Context context = new Context(); PcmInput input = AndroidPcmInput.create(context);
            PcmInput.Lease lease = input.begin(); AudioRecord actual = AudioRecord.lastCreated;
            if (during) actual.afterRead = () -> context.permission = -1; else context.permission = -1;
            check("AUDIO_INPUT_PERMISSION".equals(fails(() -> input.pump(lease)).getMessage())
                    && actual.reads == (during ? 1 : 0) && input.capturedBytes() == 0 && actual.releases == 1,
                    "permission loss before/during read prevents new samples and closes original");
        }
    }
    private static void actualFormatAndState() {
        for (int kind = 0; kind < 6; kind++) {
            if (kind == 0) AudioRecord.nextState = AudioRecord.STATE_UNINITIALIZED;
            if (kind == 1) AudioRecord.nextRate = 8000;
            if (kind == 2) AudioRecord.nextChannels = 2;
            if (kind == 3) AudioRecord.nextEncoding = 3;
            if (kind == 4) AudioRecord.nextSource = 1;
            if (kind == 5) AudioRecord.nextFrames = 32001;
            PcmInput input = AndroidPcmInput.create(new Context());
            check("AUDIO_INPUT_FORMAT".equals(fails(input::begin).getMessage())
                    && AudioRecord.lastCreated.starts == 0 && AudioRecord.lastCreated.releases == 1
                    && !input.cleanupPending(), "invalid actual format released without start/resampling");
        }
        for (boolean route : new boolean[]{true, false}) {
            PcmInput input = AndroidPcmInput.create(new Context()); PcmInput.Lease lease = input.begin();
            AudioRecord actual = AudioRecord.lastCreated;
            if (route) actual.frames = 32001; else actual.recording = AudioRecord.RECORDSTATE_STOPPED;
            check((route ? "AUDIO_INPUT_FORMAT" : "AUDIO_INPUT_STATE").equals(fails(() -> input.pump(lease)).getMessage())
                    && actual.reads == 0 && actual.releases == 1, "actual route capacity/state drift fails before read");
        }
        PcmInput changed = AndroidPcmInput.create(new Context()); PcmInput.Lease current = changed.begin();
        AudioRecord changedRecorder = AudioRecord.lastCreated; changedRecorder.afterRead = () -> changedRecorder.frames = 32001;
        check("AUDIO_INPUT_FORMAT".equals(fails(() -> changed.pump(current)).getMessage())
                && changed.capturedBytes() == 0 && changedRecorder.releases == 1,
                "route capacity change during read cannot publish a late frame");
        AudioRecord.minimum = 64002; AudioRecord.lastCreated = null;
        PcmInput oversized = AndroidPcmInput.create(new Context());
        check("AUDIO_INPUT_FORMAT".equals(fails(oversized::begin).getMessage())
                && AudioRecord.lastCreated == null, "oversized native minimum rejected before construction");
        AudioRecord.minimum = 6400;
    }
    private static void releaseEvidence() {
        RuntimeException release = new IllegalStateException("actual release");
        PcmInput input = AndroidPcmInput.create(new Context()); input.begin();
        AudioRecord actual = AudioRecord.lastCreated; actual.releaseBeforeError = true; actual.releaseError = release;
        check(fails(input::stop) == release && !input.cleanupPending(), "observed initialized-to-uninitialized transition settles API owner");
        input.stop(); check(actual.releases == 1, "settled device never released twice");
        AudioRecord.nextState = AudioRecord.STATE_UNINITIALIZED; AudioRecord.nextReleaseError = release;
        PcmInput uncertain = AndroidPcmInput.create(new Context());
        RuntimeException primary = fails(uncertain::begin); AudioRecord original = AudioRecord.lastCreated;
        check("AUDIO_INPUT_FORMAT".equals(primary.getMessage()) && uncertain.cleanupPending()
                && original.releases == 1 && Arrays.asList(primary.getSuppressed()).contains(release),
                "already-uninitialized state is not proof a failing release settled original");
        uncertain.stop(); check(original.releases == 1, "uninitialized failed release not automatically retried");
        original.releaseError = null; uncertain.retryCleanup();
        check(original.releases == 2 && !uncertain.cleanupPending(), "explicit same-object successful release settles uncertainty");
    }
}
