package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.SharedProjectService;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-only dedicated-server side of the Issue #8 multiplayer harness; excluded from the application JAR.
 * It never touches shared work itself: clients drive the real protocol, and this only reports readiness and
 * honours stop requests so the Gradle harness can restart the same world in a fresh process.
 */
public final class MultiplayerServerHarness implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist-multiplayer-server");
    private Path coordination;
    private String boot;
    private CompletableFuture<SharedProjectService.Ready> ready;
    private boolean announced, stopping;
    private int ticks;

    @Override public void onInitialize() {
        coordination = Path.of(System.getProperty("mcmateriallist.multiplayer.dir"));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var loader = FabricLoader.getInstance();
            if (loader.isModLoaded("litematica") || loader.isModLoaded("malilib"))
                throw new AssertionError("Multiplayer dedicated server classpath contains client mods");
            if (!loader.isModLoaded("mcmateriallist")) throw new AssertionError("Dedicated server did not load MCMaterialList");
            boot = read("boot");
            LOG.info("Multiplayer dedicated server isolation PASS boot={}", boot);
            ready = SharedServerNetworking.service(server).ready().toCompletableFuture();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ready == null) return;
            if (!announced) {
                if (++ticks > 1200) throw new AssertionError("Multiplayer shared authority readiness timed out");
                if (!ready.isDone()) return;
                var result = ready.getNow(null);
                if (result == null || result.status() != StoreStatus.OK || result.worldNamespace() == null)
                    throw new AssertionError("Multiplayer shared authority did not initialize safely: " + (result == null ? null : result.status()));
                announced = true;
                LOG.info("Multiplayer authority ready boot={} world={}", boot, result.worldNamespace());
                write("server-ready", boot);
            }
            if (!stopping && Files.exists(coordination.resolve("stop"))) {
                stopping = true;
                try { Files.deleteIfExists(coordination.resolve("stop")); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                LOG.info("Multiplayer server stop requested boot={}", boot);
                server.halt(false);
            }
        });
    }
    private String read(String name) {
        try { return Files.readString(coordination.resolve(name), StandardCharsets.UTF_8).trim(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void write(String name, String value) {
        try {
            Path temporary = coordination.resolve(name + ".server.tmp");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, coordination.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
