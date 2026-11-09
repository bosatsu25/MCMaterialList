package dev.mcmateriallist.core.ui;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.SharedEvents;
import dev.mcmateriallist.core.ui.WorkStatePresentation.State;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkStatePresentationTest {
    @Test void eachRequiredStateIsDistinguishable() {
        Map<String, State> expected = Map.ofEntries(
            Map.entry("local_preserved", State.LOCAL),
            Map.entry("shared", State.SHARED),
            Map.entry("detached", State.DETACHED),
            Map.entry("unknown_outcome", State.SYNCING),
            Map.entry("awaiting_result", State.SYNCING),
            Map.entry("join_required", State.SYNCING),
            Map.entry("recovered_uncertain", State.SYNCING),
            Map.entry("disconnected", State.DISCONNECTED),
            Map.entry("server_unavailable", State.SHARING_UNAVAILABLE),
            Map.entry("protocol_unsupported", State.SHARING_UNAVAILABLE),
            Map.entry("viewer", State.NO_PERMISSION),
            Map.entry("revoked", State.NO_PERMISSION),
            Map.entry("result.DENIED", State.NO_PERMISSION),
            Map.entry("baseline_mismatch", State.DEFINITION_MISMATCH),
            Map.entry("definitions_mismatch", State.DEFINITION_MISMATCH),
            Map.entry("source.CHANGED", State.DEFINITION_MISMATCH),
            Map.entry("full_recount_required", State.DEFINITION_MISMATCH),
            Map.entry("storage.IO_FAILURE", State.SAVE_FAILURE),
            Map.entry("storage.RECOVERY_REQUIRED", State.SAVE_FAILURE),
            Map.entry("result.SAVE_FAILED", State.SAVE_FAILURE),
            Map.entry("capture.MATERIALS_NOT_READY", State.WAITING),
            Map.entry("placement_capacity", State.ACTION_REQUIRED),
            Map.entry("wrong_world", State.ACTION_REQUIRED));
        expected.forEach((key, state) -> assertEquals(state, WorkStatePresentation.ofSharedReason(key), key));
    }

    @Test void noFailureUnconfirmedOrStorageReasonIsShownAsSharedSuccess() {
        var keys = new java.util.ArrayList<>(List.of("loading", "saving", "negotiating", "disconnected", "server_unavailable",
            "protocol_unsupported", "capacity", "request_failed", "request_unknown", "unknown_outcome", "awaiting_result",
            "saving_intent", "join_required", "local_storage_blocked", "viewer", "revoked", "placement_unavailable"));
        for (StoreStatus status : StoreStatus.values()) keys.add("storage." + status.name());
        for (SharedEvents.Status status : SharedEvents.Status.values()) keys.add("result." + status.name());
        for (String key : keys) assertNotEquals(State.SHARED, WorkStatePresentation.ofSharedReason(key), key);
    }

    @Test void unknownKeysNeedAnActionAndNullIsRejected() {
        assertEquals(State.ACTION_REQUIRED, WorkStatePresentation.ofSharedReason("something_new"));
        assertThrows(NullPointerException.class, () -> WorkStatePresentation.ofSharedReason(null));
    }
}
