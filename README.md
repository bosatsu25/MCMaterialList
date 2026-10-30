# MCMaterialList

Material gathering and region building work state integrated with Litematica. Phase 3 integration adds explicit server projects, Viewer/Member/Administrator role management, and shared work on the existing material and region screens while preserving durable local work. Issue #7 adds reconnect restoration and lost-outcome resolution by exact replay. Issue #8 verifies two real clients against a restarted dedicated server, on one machine with offline synthetic accounts ([details](docs/phase-3-multiplayer.md)); acceptance with authenticated players on the target server remains pending.

Pinned baseline: Minecraft **26.2**, Fabric Loader **0.19.3**, Fabric API **0.154.0+26.2**, Litematica **0.28.3**, MaLiLib **0.29.2**, JDK **25**, Gradle **9.7.1**, Loom **1.17.20**.

Open a saved placement's material list and select **Track materials** to start local tracking. Opening existing work only reads it. Click a face for Claim/Release and a multiline note (explicit **Save note**); the green check completes a row and the red X undoes completion. Show Info expands rows; Hide Done only filters the view. Progress always counts all current tracked material types. Counts, Ignore, Refresh, HUD and exports retain upstream behavior.

**Refresh work** reconciles the latest completed upstream observation and preserves work. Definition changes show a notice; removed definitions are kept in read-only Archived work. Storage failure disables editing and retains the last confirmed view. Recovery requires **Recover backup**, then **Confirm recovery**, and may lose the last action. Material controls never create/change a region snapshot or alter `.litematic` files.

The existing `MCMaterialList` footer buttons retain the Phase 0 message on ordinary clicks. **Shift-click** on either screen still runs the temporary Phase 1 diagnostic, explicitly creating/loading/refreshing both local datasets.

Open placement configuration and select **Track regions** to explicitly create region work. Region controls read only the original schematic definitions and remain available independently of material counting. Faces open Claim/Release and explicit note saving; completion preserves Configure, Placement ON/OFF and upstream transforms. Hide Done filters the view while progress includes all tracked regions, including disabled placements. Standard modification and note reasons share one native notice bubble; deleting a note retains the standard reason.

**Refresh work** reconciles original region definitions. Ambiguous changes start blocked default work and keep old work in **Archived work**. Review the exact new definition in row details and deliberately **Accept new definition**; archived state is never transferred. Backup recovery is a separate two-step action. Region controls never initialize or rewrite material snapshots.

## Shared work

On a supported server, open either native screen and select **Share**. **New project...** creates an initially empty work state from a saved, fully counted placement; existing local assignments, completion and notes are preserved separately. **Find projects** and **Join** explicitly select an accessible project for the invoking list. Material and region bindings are independent and may use different projects. **Use local work** returns that list to its original local data.

Shared faces/details allow a Member to claim an unassigned task, complete it with one-step self-assignment, and edit their own completion and note. Administrators can also assign or clear another participant and manage Viewer, Member and Administrator roles using **Manage participants...**. The last current Administrator cannot be removed or demoted. Server confirmation is required before work changes appear. The participant screen offers known online players and an explicit UUID field; no external player lookup occurs. Only one project is actively synchronized per connection, so switching between independently bound projects requires **Join / synchronize** or **Refresh work** before editing.

Total/Missing/Available, multiplier, Ignore, Hide Done and personal enabled-region counts remain local observations. They never change shared task definitions or progress denominators. Creating a project requires a fresh full count with every original region enabled and untransformed; normal refresh of an existing binding may display partial personal counts. A changed schematic source, dimension, placement origin/rotation/mirror or original region definition blocks shared task editing. A current Administrator can explicitly confirm **Update shared definition...** after a fresh full count and unchanged loaded source; this replaces global material and region definitions and the placement baseline, then requires a new join.

Unsupported servers leave local work usable. Unknown sent commands block dependent shared edits and remain attached to their original account/project, even after switching to local mode. After a fresh snapshot of that project, the client resends the exact original with the same operation ID; the server returns its recorded outcome, executes it once if it never arrived, or reports a conflict, and that answer settles it. Reconnecting to the same world and account re-joins the project the previous connection was synchronized to. **Recover metadata**, then **Confirm recovery**, preserves damaged bytes but cannot establish the outcome of a command missing from a backup; shared writes stay blocked, and local work remains available. See [Phase 3A usage, persistence and verification](docs/phase-3-shared-work.md) for these limits.

## Build and verification

Use JDK 25 as `JAVA_HOME`:

```powershell
.\gradlew.bat clean build
.\gradlew.bat :fabric:verifyClientRestart
# Only after accepting https://aka.ms/MinecraftEULA:
.\gradlew.bat :fabric:verifyServerSmoke -PacceptMinecraftEula=true
# Full retained local/restart/server gates plus shared transport and native UI:
.\gradlew.bat clean verifyPhase3 -PacceptMinecraftEula=true
```

`verifyPhase0`, `verifyPhase1` and `verifyPhase2` remain available. Linux/macOS use `./gradlew`; Linux client tests need a display, for example `xvfb-run -a ./gradlew verifyPhase3 -PacceptMinecraftEula=true`.

The distribution is `fabric/build/libs/fabric-0.1.0-phase.3a.jar` and includes the core library. Fabric API is required on both sides; pinned Litematica/MaLiLib are client-only. Unsupported or missing client mods disable the adapter. The dedicated server needs neither optional client mod. `:fabric:runClient` launches the normal development client. Minecraft 26.2 is unobfuscated: Loom uses `jar`, without external mappings or `remapJar`.

## Structure and persistence

- `core`: immutable domain, explicit work commands, progress, reconciliation, strict JSON codec, atomic local store and serial I/O service. No Minecraft/Fabric/Litematica dependency.
- `fabric/src/main`: common/server authority and bounded transport, without client references.
- `fabric/src/client`: optional native adapters, local work and explicitly selected shared backend.
- `fabric/src/gametest`, `serverSmoke`, `transportTest` and `sharedUiTest`: verification code excluded from distribution.

Placement identity wraps upstream's persistent `getHashId()` UUID (`hash_code` in placement JSON). Phase 1 requires a registered saved placement and rejects duplicate identities. Materials use upstream aggregated item registry IDs; regions use durable task UUIDs mapped conservatively by schematic key, relative origin and signed dimensions. Materials and regions have separate dataset UUIDs and snapshot files.

Data lives under `config/mcmateriallist/placements/<placement-uuid>/materials.json` and `regions.json`, with previous-good backups. No work state is written into `.litematic`. Corrupt/future snapshots block writes; backup recovery is explicit, never a silent blank reset. Work UUIDs and notes are stored only as required local data and are not logged by the application.

Test worlds, synthetic fixtures, screenshots and traces remain under ignored build directories. Do not distribute these as user data.

Independent shared bindings, confirmed caches and the original command journal use one schema-2 envelope under `config/mcmateriallist/shared-client/placement-<placement-uuid>.json`. Schema-1 files migrate on the next successful write without changing identities or journal contents. Its previous-good backup omits derived shared snapshots and retains binding/journal metadata; it is not a backup of the server project. Shared authority remains in the server-owned world store. See the [server protocol](docs/SHARED_PROTOCOL.md) and [transport contract](docs/SHARED_TRANSPORT.md).

See the [authoritative v0.1 design](litematica-collaboration-design-v0.1.md), [Phase 2A/2B/2C UI and verification](docs/phase-2-litematica-ui.md), [Phase 1 storage contract](docs/phase-1-local-work-state.md), and [Phase 0 evidence](docs/phase-0-compatibility-spike.md).

## License

MCMaterialList is released under the [MIT License](LICENSE). The repository owner chose it on 2026-10-30 ([decision record](docs/license-decision.md)). The license covers only this project's own code and resources; it does not relicense the dependencies below.

The distributed JAR bundles no third-party code. At runtime the mod requires Minecraft (Mojang EULA), Fabric Loader and Fabric API (Apache-2.0). On the client it optionally integrates with Litematica and MaLiLib, which declare LGPLv3. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists each pinned version and its declared license. The build checks that list against the pinned artifacts.
