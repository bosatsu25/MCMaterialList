package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.time.Instant;
import java.util.*;

/**
 * The design §13 initial load profile: one project, 2000 material rows, 2000 regions, 20 participants. String sizes are
 * representative (registry-id-like material ids, short region names, a minority of short notes), not the legal maxima, which
 * SharedProtocolTest already covers. Deterministic for a given seed.
 */
final class Phase5AProfile {
    static final int MATERIALS = 2000, REGIONS = 2000, PARTICIPANTS = 20;
    final SharedManifest.Definition definition;
    final List<UUID> participants;
    final UUID owner;

    Phase5AProfile(long seed) {
        Random random = new Random(seed);
        Map<MaterialTaskId, Long> materials = new HashMap<>();
        String[] words = {"stone", "polished_blackstone_bricks", "oak_planks", "deepslate_tiles", "white_concrete", "glass_pane", "spruce_log", "mud"};
        for (int i = 0; i < MATERIALS; i++)
            materials.put(new MaterialTaskId(String.format(Locale.ROOT, "minecraft:%s_%04d", words[i % words.length], i)), 1L + random.nextInt(100_000));
        List<RegionDescriptor> regions = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++)
            regions.add(new RegionDescriptor(String.format(Locale.ROOT, "Region %04d", i), new Vector3((i % 50) * 32, 64, (i / 50) * 32), new Vector3(32, 24, 32)));
        definition = new SharedManifest.Definition("d".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), materials, regions);
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < PARTICIPANTS; i++) people.add(new UUID(0x5A5A_0000L + seed, i + 1));
        participants = List.copyOf(people); owner = participants.getFirst();
    }

    /** 1 administrator, 17 members and 2 viewers. */
    Map<UUID, ProjectRole> members() {
        Map<UUID, ProjectRole> members = new HashMap<>();
        for (int i = 0; i < participants.size(); i++)
            members.put(participants.get(i), i == 0 ? ProjectRole.ADMINISTRATOR : i >= participants.size() - 2 ? ProjectRole.VIEWER : ProjectRole.MEMBER);
        return members;
    }
    List<UUID> writers() { return participants.subList(0, participants.size() - 2); }

    /** A mid-project snapshot: about 30% done, 20% assigned and 10% with a short mixed-script note. */
    ProjectSnapshot progressed(ProjectScope scope, long seed) {
        Random random = new Random(seed);
        SharedManifest manifest = SharedManifest.initial(definition); Map<TaskId, TaskState> rows = new HashMap<>();
        for (TaskId id : manifest.taskIds()) {
            UUID who = participants.get(1 + random.nextInt(participants.size() - 3));
            boolean done = random.nextInt(10) < 3, assigned = random.nextInt(10) < 2, noted = random.nextInt(10) == 0;
            rows.put(id, new TaskState(id, assigned ? who : null, done, noted ? "屋根の確認 / check roof edge before glass " + random.nextInt(1000) : "",
                done ? who : null, done ? Instant.parse("2026-11-01T12:00:00Z") : null, 1 + random.nextInt(4)));
        }
        return new ProjectSnapshot(scope, "Phase 5A load profile", owner, members(), 1234, manifest, rows, Set.of());
    }
}
