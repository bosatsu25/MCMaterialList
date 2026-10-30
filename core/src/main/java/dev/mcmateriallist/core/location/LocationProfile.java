package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.shared.ProjectScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable; every mutator returns a new generation. Positions are sorted by x, y, z with unique coordinates. */
public record LocationProfile(LocationProfileId id, long generation, String displayName, WorldIdentity world, String dimension,
    ProjectScope binding, List<ContainerPosition> positions) {
    public LocationProfile {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(world, "world"); Objects.requireNonNull(positions, "positions");
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        if (!LocationLimits.displayName(displayName)) throw new IllegalArgumentException("Invalid display name");
        if (!LocationLimits.namespacedId(dimension)) throw new IllegalArgumentException("Invalid dimension");
        if (binding != null && !binding.worldNamespace().equals(world.namespace())) throw new IllegalArgumentException("Binding belongs to another world");
        if (positions.size() > LocationLimits.MAX_CONTAINERS) throw new IllegalArgumentException("Too many containers");
        List<ContainerPosition> sorted = new ArrayList<>(positions); sorted.sort(ContainerPosition.ORDER);
        for (int index = 1; index < sorted.size(); index++)
            if (sorted.get(index - 1).sameCoordinates(sorted.get(index))) throw new IllegalArgumentException("Duplicate container position");
        positions = List.copyOf(sorted);
    }
    /** The name is stripped first; it is stored as a value only. */
    public static LocationProfile create(WorldIdentity world, String dimension, String displayName) {
        return new LocationProfile(LocationProfileId.create(), 0, LocationLimits.normalizeName(displayName), world, dimension, null, List.of());
    }
    public Optional<ContainerPosition> container(int x, int y, int z) { return positions.stream().filter(p -> p.at(x, y, z)).findFirst(); }
    public LocationProfile rename(String name) { return next(LocationLimits.normalizeName(name), binding, positions); }
    public LocationProfile withContainer(ContainerPosition position) {
        List<ContainerPosition> next = new ArrayList<>(positions); next.add(Objects.requireNonNull(position)); return next(displayName, binding, next);
    }
    public LocationProfile withoutContainer(int x, int y, int z) {
        List<ContainerPosition> next = new ArrayList<>(positions); next.removeIf(p -> p.at(x, y, z)); return next(displayName, binding, next);
    }
    public LocationProfile bind(ProjectScope scope) { return next(displayName, Objects.requireNonNull(scope), positions); }
    public LocationProfile unbind() { return next(displayName, null, positions); }
    private LocationProfile next(String name, ProjectScope scope, List<ContainerPosition> list) {
        return new LocationProfile(id, Math.incrementExact(generation), name, world, dimension, scope, list);
    }
}
