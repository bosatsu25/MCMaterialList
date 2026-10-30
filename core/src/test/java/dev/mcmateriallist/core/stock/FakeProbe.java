package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A tiny live world: blocks, chest pairs, unloaded chunks and contents by coordinates. Records every call. */
final class FakeProbe implements ContainerProbe {
    final Map<List<Integer>, String> blocks = new HashMap<>();
    final Map<List<Integer>, List<Integer>> partners = new HashMap<>();
    final Set<List<Integer>> unloaded = new HashSet<>();
    final Map<List<Integer>, Map<String, Long>> items = new HashMap<>();
    final Set<List<Integer>> unreadable = new HashSet<>();
    final List<String> calls = new ArrayList<>();

    static List<Integer> key(int x, int y, int z) { return List.of(x, y, z); }
    static List<Integer> key(ContainerPosition p) { return key(p.x(), p.y(), p.z()); }
    FakeProbe block(int x, int y, int z, String id, Map<String, Long> contents) { blocks.put(key(x, y, z), id); items.put(key(x, y, z), contents); return this; }
    FakeProbe join(List<Integer> a, List<Integer> b) { partners.put(a, b); partners.put(b, a); return this; }
    FakeProbe split(List<Integer> a) { partners.remove(partners.remove(a)); return this; }

    @Override public Shape shape(String dimension, ContainerPosition registered) {
        calls.add("shape " + key(registered));
        List<Integer> here = key(registered);
        if (unloaded.contains(here)) return new Unloaded();
        String id = blocks.getOrDefault(here, "minecraft:air");
        List<Integer> other = partners.get(here);
        if (other == null) return Present.single(id);
        return new Present(id, new ContainerPosition(other.get(0), other.get(1), other.get(2), id), !unloaded.contains(other));
    }
    @Override public Reading read(String dimension, List<ContainerPosition> halves) {
        calls.add("read " + halves.stream().map(FakeProbe::key).toList());
        if (halves.stream().anyMatch(h -> unreadable.contains(key(h)))) return new Unreadable();
        Map<String, Long> total = new HashMap<>();
        for (ContainerPosition half : halves) items.getOrDefault(key(half), Map.of()).forEach((id, n) -> total.merge(id, n, Long::sum));
        return new Contents(total);
    }
}
