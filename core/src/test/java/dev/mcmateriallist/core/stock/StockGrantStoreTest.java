package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.persistence.StoreStatus;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StockGrantStoreTest {
    private static final String OVERWORLD = "minecraft:overworld";
    @TempDir Path root;
    private final UUID project = UUID.randomUUID(), admin = UUID.randomUUID();
    private final StockGrantCodec codec = new StockGrantCodec();
    private final ContainerPosition chest = new ContainerPosition(10, 64, -3, "minecraft:chest");

    @Test void atomicSaveWithExpectedGenerationAndOnlyCanonicalPaths() throws Exception {
        var store = new StockGrantStore(root);
        assertEquals(StoreStatus.MISSING, store.load(project).status());
        var first = new StockGrants(project, 0, List.of(new StockGrant(OVERWORLD, 10, 64, -3, admin, 1_000)));
        assertEquals(StoreStatus.CONFLICT, store.save(new StockGrants(project, 1, first.grants()), -1).status());
        assertEquals(StoreStatus.OK, store.save(first, -1).status());
        assertEquals(StoreStatus.CONFLICT, store.save(first, -1).status());
        var second = first.revoke(OVERWORLD, List.of(chest));
        assertEquals(StoreStatus.CONFLICT, store.save(second, 5).status());
        assertEquals(StoreStatus.OK, store.save(second, 0).status());
        assertEquals(StoreStatus.CONFLICT, store.save(second.grant(OVERWORLD, List.of(chest), admin, 2), 0).status());
        assertEquals(second, new StockGrantStore(root).load(project).value());
        Path grants = root.resolve("mcmateriallist-stock").resolve("grants");
        Set<Path> allowedDirs = Set.of(root, root.resolve("mcmateriallist-stock"), grants);
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.toList()) {
                if (Files.isDirectory(path)) { assertTrue(allowedDirs.contains(path), path.toString()); continue; }
                assertTrue(Set.of(project + ".json", project + ".json.bak", project + ".json.lock").contains(path.getFileName().toString()), path.toString());
            }
        }
        assertEquals(first, codec.decode(Files.readAllBytes(grants.resolve(project + ".json.bak"))));
    }
    @Test void updateCreatesAppliesAndSkipsUnchangedWrites() throws Exception {
        var store = new StockGrantStore(root);
        assertEquals(StockGrants.empty(project), store.update(project, g -> g.revoke(OVERWORLD, List.of(chest))).value());
        assertFalse(Files.exists(root.resolve("mcmateriallist-stock").resolve("grants").resolve(project + ".json")));
        var created = store.update(project, g -> g.grant(OVERWORLD, List.of(chest), admin, 7));
        assertEquals(StoreStatus.OK, created.status()); assertEquals(0, created.value().generation());
        var again = store.update(project, g -> g.grant(OVERWORLD, List.of(chest), UUID.randomUUID(), 9));
        assertEquals(created.value(), again.value());
        List<ContainerPosition> many = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_GRANTS; i++) many.add(new ContainerPosition(i, 0, 0, "minecraft:barrel"));
        assertEquals(StoreStatus.TOO_LARGE, store.update(project, g -> g.grant(OVERWORLD, many, admin, 1)).status());
        var revoked = store.update(project, g -> g.revoke(OVERWORLD, List.of(chest)));
        assertEquals(1, revoked.value().generation()); assertTrue(revoked.value().grants().isEmpty());
        assertEquals(StoreStatus.CORRUPT, store.update(project, g -> StockGrants.empty(UUID.randomUUID())).status());
    }
    @Test void contentMustMatchItsPath() throws Exception {
        var store = new StockGrantStore(root); saved(store);
        var other = UUID.randomUUID(); Path dir = root.resolve("mcmateriallist-stock").resolve("grants");
        Files.copy(dir.resolve(project + ".json"), dir.resolve(other + ".json"));
        assertEquals(StoreStatus.CORRUPT, store.load(other).status());
    }
    @Test void recoveryIsExplicitAndPreservesDamagedBytes() throws Exception {
        var store = new StockGrantStore(root); var first = saved(store);
        var second = first.grant(OVERWORLD, List.of(new ContainerPosition(1, 1, 1, "minecraft:barrel")), admin, 5); assertTrue(store.save(second, 0).ok());
        Path primary = root.resolve("mcmateriallist-stock").resolve("grants").resolve(project + ".json");
        Files.writeString(primary, "damaged");
        var load = store.load(project); assertEquals(StoreStatus.RECOVERY_REQUIRED, load.status()); assertEquals(first, load.value());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(first.grant(OVERWORLD, List.of(new ContainerPosition(2, 2, 2, "minecraft:chest")), admin, 1), 0).status());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.update(project, g -> g.revoke(OVERWORLD, List.of(chest))).status());
        assertEquals("damaged", Files.readString(primary));
        assertEquals(StoreStatus.OK, store.recover(project).status()); assertEquals(first, store.load(project).value());
        try (var files = Files.list(primary.getParent())) {
            assertEquals(List.of("damaged"), files.filter(p -> p.getFileName().toString().startsWith("grants-damaged-")).map(StockGrantStoreTest::text).toList());
        }
        Files.delete(primary);
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(project).status());
        assertEquals(StoreStatus.CONFLICT, store.save(first, -1).status());
        assertEquals(StoreStatus.OK, store.recover(project).status());
        var broken = UUID.randomUUID(); Files.writeString(primary.resolveSibling(broken + ".json"), "{bad");
        assertEquals(StoreStatus.CORRUPT, store.recover(broken).status()); assertEquals("{bad", Files.readString(primary.resolveSibling(broken + ".json")));
    }
    @Test void futureSchemaIsNeverOverwritten() throws Exception {
        var store = new StockGrantStore(root); var first = saved(store);
        var second = first.grant(OVERWORLD, List.of(new ContainerPosition(1, 1, 1, "minecraft:barrel")), admin, 5); assertTrue(store.save(second, 0).ok());
        Path primary = root.resolve("mcmateriallist-stock").resolve("grants").resolve(project + ".json"), backup = primary.resolveSibling(project + ".json.bak");
        String original = Files.readString(primary), future = codec.encode(second).replace("\"schema\":1", "\"schema\":2");
        Files.writeString(primary, future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(project).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(second.revoke(OVERWORLD, List.of(chest)), 1).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.update(project, g -> g.revoke(OVERWORLD, List.of(chest))).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(project).status());
        assertEquals(future, Files.readString(primary));
        Files.writeString(primary, original); String backupFuture = "{\"schema\":2}"; Files.writeString(backup, backupFuture);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(second.revoke(OVERWORLD, List.of(chest)), 1).status());
        Files.writeString(primary, "{broken");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(project).status()); assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(project).status());
        assertEquals("{broken", Files.readString(primary)); assertEquals(backupFuture, Files.readString(backup));
    }
    @Test void symlinksAreRejected() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside")), world = Files.createDirectory(root.resolve("world"));
        try { Files.createSymbolicLink(world.resolve("mcmateriallist-stock"), outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) { Assumptions.assumeTrue(false, "Symbolic links unavailable"); }
        var store = new StockGrantStore(world);
        assertEquals(StoreStatus.INVALID_PATH, store.save(StockGrants.empty(project), -1).status());
        assertEquals(StoreStatus.INVALID_PATH, store.load(project).status());
        assertEquals(StoreStatus.INVALID_PATH, store.update(project, g -> g.grant(OVERWORLD, List.of(chest), admin, 1)).status());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        var real = new StockGrantStore(root); saved(real);
        Path dir = root.resolve("mcmateriallist-stock").resolve("grants"); var linked = UUID.randomUUID();
        Files.createSymbolicLink(dir.resolve(linked + ".json"), dir.resolve(project + ".json"));
        assertEquals(StoreStatus.INVALID_PATH, real.load(linked).status());
        // As in the other stores, a world root reached through a link is refused too.
        Path linkedRoot = root.resolve("link-to-world"); Files.createSymbolicLink(linkedRoot, Files.createDirectory(root.resolve("world2")));
        assertEquals(StoreStatus.INVALID_PATH, new StockGrantStore(linkedRoot).save(StockGrants.empty(project), -1).status());
        try (var files = Files.list(root.resolve("world2"))) { assertEquals(0, files.count()); }
    }
    @Test void injectedFailureBeforeReplaceKeepsPriorPrimaryAndHeldLockIsBusy() throws Exception {
        var store = new StockGrantStore(root); var first = saved(store); var next = first.revoke(OVERWORLD, List.of(chest));
        Path primary = root.resolve("mcmateriallist-stock").resolve("grants").resolve(project + ".json"); byte[] before = Files.readAllBytes(primary);
        for (var stage : StockGrantStore.WriteStage.values()) {
            var failing = new StockGrantStore(root, s -> { if (s == stage) throw new IOException("Injected write failure"); });
            assertEquals(StoreStatus.IO_FAILURE, failing.save(next, 0).status());
            assertArrayEquals(before, Files.readAllBytes(primary)); assertEquals(first, store.load(project).value());
        }
        try (var files = Files.list(primary.getParent())) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        try (var channel = FileChannel.open(primary.resolveSibling(project + ".json.lock"), StandardOpenOption.WRITE); var held = channel.lock()) {
            assertTrue(held.isValid());
            assertEquals(StoreStatus.BUSY, store.save(next, 0).status()); assertEquals(StoreStatus.BUSY, store.recover(project).status());
            assertEquals(StoreStatus.OK, store.load(project).status());
        }
        assertEquals(StoreStatus.OK, store.save(next, 0).status());
        assertEquals(StoreStatus.TOO_LARGE, writeAndLoad(store, primary, new byte[StockGrantStore.MAX_FILE_BYTES + 1]));
    }
    @Test void codecIsStrict() {
        var grants = StockGrants.empty(project).grant(OVERWORLD, List.of(chest, new ContainerPosition(-1, 0, 0, "minecraft:barrel")), admin, 3);
        String json = codec.encode(grants); assertEquals(grants, codec.decode(json));
        String entry = "{\"dimension\":\"minecraft:overworld\",\"pos\":[1,2,3],\"approver\":\"" + admin + "\",\"grantedAt\":1}";
        String head = "{\"schema\":1,\"kind\":\"STOCK_GRANTS\",\"projectId\":\"" + project + "\",\"generation\":0,\"grants\":[";
        assertEquals(1, codec.decode(head + entry + "]}").grants().size());
        for (String bad : List.of(head + entry + "]} x", head + entry + "],\"extra\":1}", head + entry.replace("[1,2,3]", "[1,2]") + "]}",
                head + entry.replace("\"grantedAt\":1", "\"grantedAt\":1.5") + "]}", head + entry.replace("\"grantedAt\":1", "\"grantedAt\":-1") + "]}",
                head + entry.replace(admin.toString(), admin.toString().toUpperCase(java.util.Locale.ROOT)) + "]}", head + entry + "," + entry + "]}",
                head.replace(project.toString(), "../x") + "]}", head.replace("STOCK_GRANTS", "LOCATION_PROFILE") + "]}",
                head + entry.replace("[1,2,3]", "[1,4096,3]") + "]}", head + entry.replace("\"dimension\":\"minecraft:overworld\",", "\"dimension\":\"minecraft:overworld\",\"dimension\":\"x:y\",") + "]}",
                // Out of canonical order.
                head + entry.replace("[1,2,3]", "[5,2,3]") + "," + entry + "]}"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bad), bad);
        assertThrows(dev.mcmateriallist.core.persistence.UnsupportedSchemaException.class, () -> codec.decode(json.replace("\"schema\":1", "\"schema\":2")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[]{(byte) 0xff}));
    }

    private StockGrants saved(StockGrantStore store) {
        var grants = new StockGrants(project, 0, List.of(new StockGrant(OVERWORLD, chest.x(), chest.y(), chest.z(), admin, 1_000)));
        assertEquals(StoreStatus.OK, store.save(grants, -1).status()); return grants;
    }
    private StoreStatus writeAndLoad(StockGrantStore store, Path file, byte[] bytes) throws IOException { Files.write(file, bytes); return store.load(project).status(); }
    private static String text(Path path) {
        try { return Files.readString(path); } catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
}
