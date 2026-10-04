# MCMaterialList

Local material gathering and region building work state integrated with Litematica. Phase 2A adds durable material-row assignment, completion, notes, progress and information controls. Region task controls and multiplayer synchronization remain future work.

Pinned baseline: Minecraft **26.2**, Fabric Loader **0.19.3**, Fabric API **0.154.0+26.2**, Litematica **0.28.3**, MaLiLib **0.29.2**, JDK **25**, Gradle **9.7.1**, Loom **1.17.20**.

Open a saved placement's material list and select **Track materials** to start local tracking. Opening existing work only reads it. Click a face for Claim/Release and a multiline note (explicit **Save note**); the green check completes a row and the red X undoes completion. Show Info expands rows; Hide Done only filters the view. Progress always counts all current tracked material types. Counts, Ignore, Refresh, HUD and exports retain upstream behavior.

**Refresh work** reconciles the latest completed upstream observation and preserves work. Definition changes show a notice; removed definitions are kept in read-only Archived work. Storage failure disables editing and retains the last confirmed view. Recovery requires **Recover backup**, then **Confirm recovery**, and may lose the last action. Material controls never create/change a region snapshot or alter `.litematic` files.

The existing `MCMaterialList` footer buttons retain the Phase 0 message on ordinary clicks. **Shift-click** on either screen still runs the temporary Phase 1 diagnostic, explicitly creating/loading/refreshing both local datasets.

## Build and verification

Use JDK 25 as `JAVA_HOME`:

```powershell
.\gradlew.bat clean build
.\gradlew.bat :fabric:verifyClientRestart
# Only after accepting https://aka.ms/MinecraftEULA:
.\gradlew.bat :fabric:verifyServerSmoke -PacceptMinecraftEula=true
# Full Phase 0/1 regression and Phase 2A material UI verification:
.\gradlew.bat clean verifyPhase2 -PacceptMinecraftEula=true
```

`verifyPhase0` and `verifyPhase1` remain available and run the strengthened client suite. Linux/macOS use `./gradlew`; Linux client tests need a display, for example `xvfb-run -a ./gradlew verifyPhase2 -PacceptMinecraftEula=true`.

The distribution is `fabric/build/libs/fabric-0.1.0-phase.2a.jar` and includes the core library. Fabric API is required on both sides; pinned Litematica/MaLiLib are client-only. Unsupported or missing client mods disable the adapter. The dedicated server needs neither optional client mod. `:fabric:runClient` launches the normal development client. Minecraft 26.2 is unobfuscated: Loom uses `jar`, without external mappings or `remapJar`.

## Structure and persistence

- `core`: immutable domain, explicit work commands, progress, reconciliation, strict JSON codec, atomic local store and serial I/O service. No Minecraft/Fabric/Litematica dependency.
- `fabric/src/main`: common entrypoint, without client references.
- `fabric/src/client`: version gate, optional mixins, upstream adapters and diagnostic.
- `fabric/src/gametest` and `fabric/src/serverSmoke`: verification code excluded from distribution.

Placement identity wraps upstream's persistent `getHashId()` UUID (`hash_code` in placement JSON). Phase 1 requires a registered saved placement and rejects duplicate identities. Materials use upstream aggregated item registry IDs; regions use durable task UUIDs mapped conservatively by schematic key, relative origin and signed dimensions. Materials and regions have separate dataset UUIDs and snapshot files.

Data lives under `config/mcmateriallist/placements/<placement-uuid>/materials.json` and `regions.json`, with previous-good backups. No work state is written into `.litematic`. Corrupt/future snapshots block writes; backup recovery is explicit, never a silent blank reset. Work UUIDs and notes are stored only as required local data and are not logged by the application.

Test worlds, synthetic fixtures, screenshots and traces remain under ignored build directories. Do not distribute these as user data.

See the [authoritative v0.1 design](litematica-collaboration-design-v0.1.md), [Phase 2A UI and verification](docs/phase-2-litematica-ui.md), [Phase 1 storage contract](docs/phase-1-local-work-state.md), and [Phase 0 evidence](docs/phase-0-compatibility-spike.md).
