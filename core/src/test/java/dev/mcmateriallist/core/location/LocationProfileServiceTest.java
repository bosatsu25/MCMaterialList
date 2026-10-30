package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocationProfileServiceTest {
    @TempDir Path root;
    private final WorldIdentity a = new WorldIdentity(UUID.randomUUID()), b = new WorldIdentity(UUID.randomUUID());
    private static final String OVERWORLD = "minecraft:overworld";

    @Test void profilesAndSelectionAreScopedToTheirWorld() throws Exception {
        try (var service = new LocationProfileService(root)) {
            var created = service.create(a, OVERWORLD, "  taichi_ms ").get();
            assertEquals(LocationOutcome.CHANGED, created.outcome()); var profile = created.storage().value();
            assertEquals("taichi_ms", profile.displayName()); assertEquals(0, profile.generation()); assertEquals(a, profile.world());
            assertEquals(LocationOutcome.CHANGED, service.select(a, profile.id()).get().outcome());
            assertEquals(List.of(), service.list(b).get().value());
            var otherCurrent = service.current(b).get();
            assertEquals(StoreStatus.MISSING, otherCurrent.selection().status()); assertNull(otherCurrent.profile());
            assertEquals(StoreStatus.MISSING, service.load(b, profile.id()).get().status());
            var rejected = service.select(b, profile.id()).get();
            assertEquals(LocationOutcome.PROFILE_UNAVAILABLE, rejected.outcome()); assertEquals(StoreStatus.MISSING, rejected.profile());
            assertFalse(Files.exists(root.resolve("location-files").resolve("worlds").resolve(b.toString())));
            var current = service.current(a).get();
            assertEquals(profile.id(), current.selection().value().profile()); assertEquals(profile, current.profile().value());
            assertEquals(LocationOutcome.UNCHANGED, service.select(a, profile.id()).get().outcome());
            var cleared = service.select(a, null).get();
            assertEquals(LocationOutcome.CHANGED, cleared.outcome()); assertNull(cleared.selection().value().profile()); assertNull(cleared.profile());
            assertNull(service.current(a).get().profile());
            assertEquals(LocationOutcome.UNCHANGED, service.select(a, null).get().outcome());
            assertEquals(LocationOutcome.UNCHANGED, service.select(b, null).get().outcome());
            assertEquals(LocationOutcome.INVALID_INPUT, service.create(a, "Overworld", "x").get().outcome());
            assertEquals(LocationOutcome.INVALID_INPUT, service.create(a, OVERWORLD, "‮").get().outcome());
            assertEquals(LocationOutcome.INVALID_INPUT, service.create(null, OVERWORLD, "x").get().outcome());
            assertEquals(1, service.list(a).get().value().size());
        }
    }

    @Test void containerEditsAreExplicitAndDurable() throws Exception {
        try (var service = new LocationProfileService(root)) {
            var profile = service.create(a, OVERWORLD, "storage").get().storage().value();
            var chest = new ContainerPosition(10, 64, -3, "minecraft:chest");
            var wrongDimension = service.addContainer(a, profile.id(), 0, "minecraft:the_nether", chest).get();
            assertEquals(LocationOutcome.DIMENSION_MISMATCH, wrongDimension.outcome()); assertEquals(profile, wrongDimension.storage().value());
            var added = service.addContainer(a, profile.id(), 0, OVERWORLD, chest).get();
            assertEquals(LocationOutcome.CHANGED, added.outcome()); assertEquals(1, added.storage().value().generation());
            Path file = root.resolve("location-files").resolve("worlds").resolve(a.toString()).resolve("profiles").resolve(profile.id() + ".json");
            byte[] before = Files.readAllBytes(file);
            var duplicate = service.addContainer(a, profile.id(), 1, OVERWORLD, new ContainerPosition(10, 64, -3, "minecraft:barrel")).get();
            assertEquals(LocationOutcome.DUPLICATE, duplicate.outcome()); assertEquals(1, duplicate.storage().value().generation());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(StoreStatus.CONFLICT, service.addContainer(a, profile.id(), 0, OVERWORLD, new ContainerPosition(1, 1, 1, "minecraft:chest")).get().storage().status());
            assertEquals(StoreStatus.MISSING, service.addContainer(b, profile.id(), 1, OVERWORLD, chest).get().storage().status());
            assertEquals(LocationOutcome.ABSENT, service.removeContainer(a, profile.id(), 1, OVERWORLD, 0, 0, 0).get().outcome());
            assertEquals(LocationOutcome.DIMENSION_MISMATCH, service.removeContainer(a, profile.id(), 1, "minecraft:the_end", 10, 64, -3).get().outcome());
            var otherScope = ProjectScope.create(b.namespace());
            assertEquals(LocationOutcome.WORLD_MISMATCH, service.bind(a, profile.id(), 1, otherScope).get().outcome());
            var scope = ProjectScope.create(a.namespace());
            assertEquals(scope, service.bind(a, profile.id(), 1, scope).get().storage().value().binding());
            assertEquals(LocationOutcome.UNCHANGED, service.bind(a, profile.id(), 2, scope).get().outcome());
            assertEquals(LocationOutcome.INVALID_INPUT, service.rename(a, profile.id(), 2, " \u0007 ").get().outcome());
            assertEquals(LocationOutcome.UNCHANGED, service.rename(a, profile.id(), 2, " storage ").get().outcome());
            assertEquals("../../evil", service.rename(a, profile.id(), 2, "../../evil").get().storage().value().displayName());
            assertEquals(LocationOutcome.CHANGED, service.removeContainer(a, profile.id(), 3, OVERWORLD, 10, 64, -3).get().outcome());
            assertNull(service.unbind(a, profile.id(), 4).get().storage().value().binding());
            assertEquals(LocationOutcome.UNCHANGED, service.unbind(a, profile.id(), 5).get().outcome());
        }
        try (var restarted = new LocationProfileService(root)) {
            var entries = restarted.list(a).get().value();
            assertEquals(1, entries.size()); var profile = entries.get(0).profile();
            assertEquals("../../evil", profile.displayName()); assertEquals(5, profile.generation()); assertTrue(profile.positions().isEmpty());
        }
    }

    @Test void selectedProfileFailuresAreReportedNeverSubstituted() throws Exception {
        try (var service = new LocationProfileService(root)) {
            var first = service.create(a, OVERWORLD, "first").get().storage().value();
            var second = service.create(a, OVERWORLD, "second").get().storage().value();
            assertEquals(LocationOutcome.CHANGED, service.select(a, first.id()).get().outcome());
            Path profiles = root.resolve("location-files").resolve("worlds").resolve(a.toString()).resolve("profiles");
            Files.writeString(profiles.resolve(first.id() + ".json"), "{broken");
            var current = service.current(a).get();
            assertEquals(first.id(), current.selection().value().profile());
            assertEquals(StoreStatus.CORRUPT, current.profile().status()); assertNull(current.profile().value());
            var reselect = service.select(a, first.id()).get();
            assertEquals(LocationOutcome.PROFILE_UNAVAILABLE, reselect.outcome()); assertEquals(StoreStatus.CORRUPT, reselect.profile());
            Files.delete(profiles.resolve(first.id() + ".json"));
            assertEquals(StoreStatus.MISSING, service.current(a).get().profile().status());
            assertEquals(LocationOutcome.CHANGED, service.select(a, second.id()).get().outcome());
            Path current2 = profiles.resolveSibling("current.json");
            Files.writeString(current2, "{bad");
            var unreadable = service.select(a, second.id()).get();
            assertEquals(StoreStatus.RECOVERY_REQUIRED, unreadable.selection().status()); assertNull(unreadable.outcome());
            assertEquals("{bad", Files.readString(current2));
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.current(a).get().selection().status());
            assertEquals(StoreStatus.OK, service.recoverSelection(a).get().status());
            assertEquals(StoreStatus.MISSING, service.current(a).get().profile().status());
            var stale = service.rename(a, second.id(), 0, "x").get();
            assertEquals(LocationOutcome.CHANGED, stale.outcome());
            Files.writeString(profiles.resolve(second.id() + ".json"), "{bad");
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.rename(a, second.id(), 1, "y").get().storage().status());
            assertEquals(StoreStatus.OK, service.recover(a, second.id()).get().status());
            assertEquals("second", service.load(a, second.id()).get().value().displayName());
        }
    }

    @Test void creationStopsAtTheListingCap() throws Exception {
        Path profiles = root.resolve("location-files").resolve("worlds").resolve(a.toString()).resolve("profiles");
        Files.createDirectories(profiles);
        for (int i = 0; i < LocationLimits.MAX_PROFILES; i++) Files.writeString(profiles.resolve(UUID.randomUUID() + ".json"), "");
        try (var service = new LocationProfileService(root)) {
            var refused = service.create(a, OVERWORLD, "one too many").get();
            assertEquals(LocationOutcome.CAPACITY, refused.outcome());
            assertEquals(LocationLimits.MAX_PROFILES, service.list(a).get().value().size());
        }
    }

    @Test void closeDrainsQueuedWorkThenReportsClosed() throws Exception {
        var service = new LocationProfileService(root);
        var profile = service.create(a, OVERWORLD, "drain").get().storage().value();
        List<CompletableFuture<LocationUpdate>> queued = new ArrayList<>();
        for (int i = 0; i < 20; i++) queued.add(service.addContainer(a, profile.id(), i, OVERWORLD, new ContainerPosition(i, 0, 0, "minecraft:chest")));
        service.close();
        for (var future : queued) { assertTrue(future.isDone()); assertEquals(LocationOutcome.CHANGED, future.get().outcome()); }
        assertEquals(StoreStatus.CLOSED, service.list(a).get().status());
        assertEquals(StoreStatus.CLOSED, service.create(a, OVERWORLD, "late").get().storage().status());
        assertEquals(StoreStatus.CLOSED, service.rename(a, profile.id(), 20, "late").get().storage().status());
        assertEquals(StoreStatus.CLOSED, service.select(a, profile.id()).get().selection().status());
        assertEquals(StoreStatus.CLOSED, service.current(a).get().selection().status());
        assertEquals(StoreStatus.CLOSED, service.recover(a, profile.id()).get().status());
        try (var restarted = new LocationProfileService(root)) {
            var loaded = restarted.load(a, profile.id()).get().value();
            assertEquals(20, loaded.positions().size()); assertEquals(20, loaded.generation());
        }
    }
}
