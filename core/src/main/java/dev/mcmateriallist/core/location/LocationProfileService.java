package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lifecycle-owned serialized I/O; disk is authoritative and every mutation reloads first. */
public final class LocationProfileService implements AutoCloseable {
    private final LocationProfileStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCMaterialList location persistence"); thread.setDaemon(false); return thread;
    });
    public LocationProfileService(Path configRoot) { this(new LocationProfileStore(configRoot)); }
    LocationProfileService(LocationProfileStore store) { this.store = store; }

    private record Step(LocationProfile next, LocationOutcome outcome) {
        static Step to(LocationProfile next) { return new Step(next, LocationOutcome.CHANGED); }
        static Step stay(LocationOutcome outcome) { return new Step(null, outcome); }
    }

    public CompletableFuture<LocationResult<List<ProfileEntry>>> list(WorldIdentity world) {
        return submit(() -> world == null ? LocationResult.failure(StoreStatus.INVALID_DEFINITION) : store.list(world), () -> LocationResult.failure(StoreStatus.CLOSED));
    }
    public CompletableFuture<LocationResult<LocationProfile>> load(WorldIdentity world, LocationProfileId id) {
        return submit(() -> world == null || id == null ? LocationResult.failure(StoreStatus.INVALID_DEFINITION) : store.load(world, id), () -> LocationResult.failure(StoreStatus.CLOSED));
    }
    public CompletableFuture<LocationResult<LocationProfile>> recover(WorldIdentity world, LocationProfileId id) {
        return submit(() -> world == null || id == null ? LocationResult.failure(StoreStatus.INVALID_DEFINITION) : store.recover(world, id), () -> LocationResult.failure(StoreStatus.CLOSED));
    }
    public CompletableFuture<LocationResult<LocationSelection>> recoverSelection(WorldIdentity world) {
        return submit(() -> world == null ? LocationResult.failure(StoreStatus.INVALID_DEFINITION) : store.recoverSelection(world), () -> LocationResult.failure(StoreStatus.CLOSED));
    }
    /** Creation is refused at the listing cap so every profile of a world stays listable. */
    public CompletableFuture<LocationUpdate> create(WorldIdentity world, String dimension, String displayName) {
        return submit(() -> createNow(world, dimension, displayName), LocationProfileService::closedUpdate);
    }
    public CompletableFuture<LocationUpdate> rename(WorldIdentity world, LocationProfileId id, long generation, String displayName) {
        return mutate(world, id, generation, before -> {
            String name = LocationLimits.normalizeName(displayName);
            if (!LocationLimits.displayName(name)) return Step.stay(LocationOutcome.INVALID_INPUT);
            return name.equals(before.displayName()) ? Step.stay(LocationOutcome.UNCHANGED) : Step.to(before.rename(name));
        });
    }
    public CompletableFuture<LocationUpdate> addContainer(WorldIdentity world, LocationProfileId id, long generation, String dimension, ContainerPosition position) {
        return mutate(world, id, generation, before -> {
            if (position == null || dimension == null) return Step.stay(LocationOutcome.INVALID_INPUT);
            if (!before.dimension().equals(dimension)) return Step.stay(LocationOutcome.DIMENSION_MISMATCH);
            if (before.container(position.x(), position.y(), position.z()).isPresent()) return Step.stay(LocationOutcome.DUPLICATE);
            if (before.positions().size() >= LocationLimits.MAX_CONTAINERS) return Step.stay(LocationOutcome.CAPACITY);
            return Step.to(before.withContainer(position));
        });
    }
    public CompletableFuture<LocationUpdate> removeContainer(WorldIdentity world, LocationProfileId id, long generation, String dimension, int x, int y, int z) {
        return mutate(world, id, generation, before -> {
            if (dimension == null) return Step.stay(LocationOutcome.INVALID_INPUT);
            if (!before.dimension().equals(dimension)) return Step.stay(LocationOutcome.DIMENSION_MISMATCH);
            return before.container(x, y, z).isEmpty() ? Step.stay(LocationOutcome.ABSENT) : Step.to(before.withoutContainer(x, y, z));
        });
    }
    public CompletableFuture<LocationUpdate> bind(WorldIdentity world, LocationProfileId id, long generation, ProjectScope scope) {
        return mutate(world, id, generation, before -> {
            if (scope == null) return Step.stay(LocationOutcome.INVALID_INPUT);
            if (!scope.worldNamespace().equals(before.world().namespace())) return Step.stay(LocationOutcome.WORLD_MISMATCH);
            return scope.equals(before.binding()) ? Step.stay(LocationOutcome.UNCHANGED) : Step.to(before.bind(scope));
        });
    }
    public CompletableFuture<LocationUpdate> unbind(WorldIdentity world, LocationProfileId id, long generation) {
        return mutate(world, id, generation, before -> before.binding() == null ? Step.stay(LocationOutcome.UNCHANGED) : Step.to(before.unbind()));
    }
    /** Only a readable profile stored under this world can be selected; null clears the selection. */
    public CompletableFuture<SelectionUpdate> select(WorldIdentity world, LocationProfileId id) {
        return submit(() -> selectNow(world, id), () -> new SelectionUpdate(LocationResult.failure(StoreStatus.CLOSED), null, null));
    }
    public CompletableFuture<CurrentLocation> current(WorldIdentity world) {
        return submit(() -> currentNow(world), () -> new CurrentLocation(LocationResult.failure(StoreStatus.CLOSED), null));
    }

    private LocationUpdate createNow(WorldIdentity world, String dimension, String displayName) {
        String name = LocationLimits.normalizeName(displayName);
        if (world == null || !LocationLimits.namespacedId(dimension) || !LocationLimits.displayName(name))
            return new LocationUpdate(LocationResult.failure(StoreStatus.INVALID_DEFINITION), LocationOutcome.INVALID_INPUT);
        var existing = store.list(world);
        if (!existing.ok()) return new LocationUpdate(LocationResult.failure(existing.status()), null);
        if (existing.value().size() >= LocationLimits.MAX_PROFILES) return new LocationUpdate(LocationResult.failure(StoreStatus.TOO_LARGE), LocationOutcome.CAPACITY);
        LocationProfile created = LocationProfile.create(world, dimension, name);
        var saved = store.save(created, -1);
        return new LocationUpdate(saved, saved.ok() ? LocationOutcome.CHANGED : null);
    }
    private CompletableFuture<LocationUpdate> mutate(WorldIdentity world, LocationProfileId id, long generation, Function<LocationProfile, Step> change) {
        return submit(() -> {
            if (world == null || id == null) return new LocationUpdate(LocationResult.failure(StoreStatus.INVALID_DEFINITION), LocationOutcome.INVALID_INPUT);
            var loaded = store.load(world, id);
            if (!loaded.ok()) return new LocationUpdate(loaded, null);
            LocationProfile before = loaded.value();
            if (before.generation() != generation) return new LocationUpdate(LocationResult.failure(StoreStatus.CONFLICT), null);
            Step step;
            try { step = change.apply(before); }
            catch (ArithmeticException exception) { return new LocationUpdate(loaded, LocationOutcome.VERSION_EXHAUSTED); }
            catch (IllegalArgumentException exception) { return new LocationUpdate(loaded, LocationOutcome.INVALID_INPUT); }
            if (step.next() == null) return new LocationUpdate(loaded, step.outcome());
            var saved = store.save(step.next(), before.generation());
            return new LocationUpdate(saved, saved.ok() ? LocationOutcome.CHANGED : null);
        }, LocationProfileService::closedUpdate);
    }
    private SelectionUpdate selectNow(WorldIdentity world, LocationProfileId id) {
        if (world == null) return new SelectionUpdate(LocationResult.failure(StoreStatus.INVALID_DEFINITION), LocationOutcome.INVALID_INPUT, null);
        var loaded = store.loadSelection(world);
        if (!loaded.ok() && loaded.status() != StoreStatus.MISSING) return new SelectionUpdate(loaded, null, null);
        LocationSelection before = loaded.value();
        StoreStatus target = null;
        if (id != null) {
            // Another world's profile is never under this world's directory, so it is unavailable here.
            target = store.load(world, id).status();
            if (target != StoreStatus.OK) return new SelectionUpdate(loaded, LocationOutcome.PROFILE_UNAVAILABLE, target);
        }
        if (before == null ? id == null : id == null ? before.profile() == null : id.equals(before.profile()))
            return new SelectionUpdate(loaded, LocationOutcome.UNCHANGED, target);
        LocationSelection next;
        try { next = before == null ? new LocationSelection(world, id, 0) : before.select(id); }
        catch (ArithmeticException exception) { return new SelectionUpdate(loaded, LocationOutcome.VERSION_EXHAUSTED, target); }
        var saved = store.saveSelection(next, before == null ? -1 : before.generation());
        return new SelectionUpdate(saved, saved.ok() ? LocationOutcome.CHANGED : null, target);
    }
    private CurrentLocation currentNow(WorldIdentity world) {
        if (world == null) return new CurrentLocation(LocationResult.failure(StoreStatus.INVALID_DEFINITION), null);
        var selection = store.loadSelection(world);
        if (!selection.ok() || selection.value().profile() == null) return new CurrentLocation(selection, null);
        return new CurrentLocation(selection, store.load(world, selection.value().profile()));
    }
    private static LocationUpdate closedUpdate() { return new LocationUpdate(LocationResult.failure(StoreStatus.CLOSED), null); }
    private <T> CompletableFuture<T> submit(Supplier<T> operation, Supplier<T> closed) {
        try { return CompletableFuture.supplyAsync(operation, io); }
        catch (RejectedExecutionException exception) { return CompletableFuture.completedFuture(closed.get()); }
    }
    @Override public void close() {
        io.shutdown();
        try {
            // Shutdown is a lifecycle boundary, not a per-frame wait. Never cancel an in-flight replace.
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Location persistence did not finish during shutdown");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("Location persistence shutdown interrupted", exception); }
    }
}
