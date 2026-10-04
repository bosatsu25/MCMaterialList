package dev.mcmateriallist.fabric.test;

import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Keeps upstream per-world configuration from importing another test's placements. */
final class FixtureWorlds {
    private FixtureWorlds() {}

    static TestSingleplayerContext create(ClientGameTestContext context, String phase) {
        // World saves are deleted by Fabric, but Litematica retains their configuration.
        // A fresh name also isolates repeated invocations in the same durable run directory.
        String name = "MCMaterialList " + phase + " " + Long.toUnsignedString(System.nanoTime());
        TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setName(name)).create();
        boolean empty = context.computeOnClient(mc -> DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty());
        if (!empty) {
            world.close();
            throw new AssertionError("Fixture world imported unrelated upstream placements");
        }
        return world;
    }
}
