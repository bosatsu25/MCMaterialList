# MCMaterialList

Phase 0 compatibility spike for Minecraft **26.2**, Fabric Loader **0.19.3**, Fabric API **0.154.0+26.2**, Litematica **0.28.3**, and MaLiLib **0.29.2**. Requires JDK **25**. Loom **1.17.20** and Gradle **9.7.1** are pinned.

Only two experimental `MCMaterialList` buttons and placement identity validation are implemented. Clicking a button displays `MCMaterialList Phase 0` inside the existing GUI. Assignment, completion, progress, sharing, networking, warehouse scanning, and the screenshot's full UI are outside this phase.

## Build and verification

Use a JDK 25 `JAVA_HOME`:

```powershell
.\gradlew.bat clean build
.\gradlew.bat :fabric:verifyClientRestart
# Only after accepting https://aka.ms/MinecraftEULA:
.\gradlew.bat :fabric:verifyServerSmoke -PacceptMinecraftEula=true
# All checks together:
.\gradlew.bat clean verifyPhase0 -PacceptMinecraftEula=true
```

Linux/macOS use `./gradlew`. Linux client tests require a display, for example `xvfb-run -a ./gradlew verifyPhase0 -PacceptMinecraftEula=true`.

The distribution is `fabric/build/libs/fabric-0.1.0-phase.0.jar`; the core library is nested in it. Install the Fabric API dependency on both sides. Install the pinned Litematica and MaLiLib versions on the client. The dedicated server does not need those two mods. Unsupported or missing client mods disable this experimental adapter. `:fabric:runClient` starts the normal development client.

Minecraft 26.2 is unobfuscated: this Loom plugin builds the distribution with `jar`; no external mappings or `remapJar` task are needed.

## Structure and identity

- `core`: immutable `LocalPlacementId` and JUnit tests; no Minecraft dependencies.
- `fabric/src/main`: common entrypoint; no client class references.
- `fabric/src/client`: the version gate, optional GUI mixins, and Litematica adapter.
- `fabric/src/gametest` and `fabric/src/serverSmoke`: test-only code, excluded from the distribution.

The adapter wraps Litematica's persistent random `getHashId()` UUID, stored by upstream as placement JSON `hash_code`. It is **not** Java `hashCode()`. Creating another placement from the same schematic creates a new UUID. No custom serialization, sidecar, or `.litematic` write is introduced. Copied placement JSON with duplicate UUIDs is rejected when identity is requested; placements marked unsavable or with unsaved schematic files are rejected. Persistence is verified for placements registered with the placement manager; the adapter does not verify manager membership.

Runtime test data, worlds, screenshots, and class-loading traces live under ignored `fabric/build/`. Test fixtures contain synthetic IDs and upstream placement JSON; do not distribute them as user data.

See [Phase 0 findings and evidence](docs/phase-0-compatibility-spike.md) for tested behavior, limitations, and the Phase 1 decision.
