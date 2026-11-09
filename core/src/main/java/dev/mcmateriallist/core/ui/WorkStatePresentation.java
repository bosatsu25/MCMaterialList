package dev.mcmateriallist.core.ui;

import java.util.Objects;
import java.util.Set;

/**
 * Issue #22: the one user-visible state a work list is in, grouped from the reason keys the shared and local work views
 * already produce (keys below {@code mcmateriallist.shared.}). The grouping is presentation only and never decides what is
 * allowed. A network failure, an unconfirmed result or an unreadable store never maps to {@link State#SHARED}.
 */
public final class WorkStatePresentation {
    private WorkStatePresentation() {}

    /** Distinguishable states; each has its own translated label below {@code mcmateriallist.hub.state.}. */
    public enum State {
        /** Original local work (tracked or not yet tracked); nothing is shared. */
        LOCAL,
        /** Regions unshared into a detached local copy (#11). */
        DETACHED,
        /** Synchronized shared work the player may edit. */
        SHARED,
        /** Loading, saving, joining or waiting for a result that is not yet confirmed or settled. */
        SYNCING,
        /** Waiting for Litematica (a material count or a saved placement) before work can be read. */
        WAITING,
        /** No connection to the server; shared edits are off, local work stays available. */
        DISCONNECTED,
        /** The server has no compatible MCMaterialList, so real-time sharing is unavailable; local work stays available. */
        SHARING_UNAVAILABLE,
        /** Synchronized, but the role, account or a lost race does not permit the action. */
        NO_PERMISSION,
        /** The schematic, placement or counted definitions differ from the shared project. */
        DEFINITION_MISMATCH,
        /** Work or sharing settings could not be read or saved; nothing new is shown as saved. */
        SAVE_FAILURE,
        /** Anything else that needs a specific user action named by the reason text. */
        ACTION_REQUIRED
    }

    private static final Set<String> NO_PERMISSION = Set.of("viewer", "revoked", "account_mismatch", "task_permission_required",
        "result.DENIED", "unshare.account_mismatch");
    private static final Set<String> SYNCING = Set.of("loading", "saving", "negotiating", "discovering", "creating", "joining",
        "saving_intent", "awaiting_result", "checking_source", "definition_requested", "permissions_requested", "permissions_saved",
        "join_required", "unknown_outcome", "recovered_uncertain", "request_unknown", "state_changed", "selection_changed",
        "result.OK", "result.CONFLICT", "unshare.unresolved_operations", "unshare.recovered_uncertain", "unshare.join_required",
        "unshare.refresh_required", "unshare.saving", "unshare.state_changed");
    private static final Set<String> DEFINITION = Set.of("baseline_mismatch", "definitions_mismatch", "source_reload_required",
        "source_edited", "full_recount_required");
    private static final Set<String> SAVE_FAILURE = Set.of("local_storage_blocked", "result.SAVE_FAILED", "result.UNAVAILABLE");
    private static final Set<String> WAITING = Set.of("capture.MATERIALS_BUSY", "capture.MATERIALS_NOT_READY", "storage.BUSY");

    /** The state for a shared/sharing reason key, as returned by the shared work controller. */
    public static State ofSharedReason(String key) {
        Objects.requireNonNull(key, "key");
        if (key.equals("shared")) return State.SHARED;
        if (NO_PERMISSION.contains(key)) return State.NO_PERMISSION;
        if (key.equals("detached") || key.equals("detached_rejoin_required")) return State.DETACHED;
        if (key.equals("local_preserved") || key.equals("unshare.not_shared")) return State.LOCAL;
        if (key.equals("disconnected")) return State.DISCONNECTED;
        if (key.equals("server_unavailable") || key.equals("protocol_unsupported")) return State.SHARING_UNAVAILABLE;
        if (WAITING.contains(key)) return State.WAITING;
        if (SYNCING.contains(key)) return State.SYNCING;
        // Only a source that was read and found different is a mismatch; an unsaved, missing, unsafe, too large or
        // unreadable source could not be verified at all, so it needs action rather than claiming a difference.
        if (DEFINITION.contains(key) || key.equals("source.CHANGED")) return State.DEFINITION_MISMATCH;
        if (SAVE_FAILURE.contains(key) || key.startsWith("storage.")) return State.SAVE_FAILURE;
        return State.ACTION_REQUIRED;
    }
}
