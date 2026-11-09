# Changelog

All notable changes to MCMaterialList are recorded here. Japanese release notes: [docs/release-notes-0.1.0.md](docs/release-notes-0.1.0.md).

## [0.1.0] - prepared 2026-11-09

First release. Distribution: `fabric-0.1.0.jar` (mod id `mcmateriallist`, MIT).

Tested only with Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.0+26.2, Litematica 0.28.3, MaLiLib 0.29.2 and Java 25. `fabric.mod.json` declares `java >=25` as the loader minimum.

### Not verified

This release was published without these checks. They are open, not passed:

- Acceptance with authenticated real players. All multiplayer tests use two offline synthetic accounts and a dedicated server on one machine.
- Deployment and testing on the target server "たいち鯖".
- macOS (no run at all). Windows has evidence only from the Phase 0-2 local runs, not for this build.
- Java newer than 25, other mod combinations, and in-game frame and tick timing.

### Added

- **Material work** (#2): **Track materials** on a saved placement's material list. Each row gets a face for Claim/Release and a multiline note, plus a green check to complete and a red X to undo. Progress counts all tracked rows whatever the filter. **Refresh work** keeps work and archives removed definitions. Backup recovery takes two explicit steps.
- **Region work** (#3): **Track regions** on placement configuration, with the same controls. Ambiguous definition changes start blocked work, and **Accept new definition** is a deliberate step. Placement ON/OFF and transforms are never changed.
- **UI parity** (#4): native Litematica/MaLiLib sprites, notices and layout at GUI scales 1-4, narrow windows and Japanese.
- **Shared work** (#5): explicit server projects on a server that runs the mod. They are created from a fresh full count, and players find and join them. Every change waits for the server's confirmation, and snapshots and deltas travel over a bounded transport. Material and region bindings are independent.
- **Roles** (#6): Viewer, Member and Administrator, managed with **Manage participants...**. The last Administrator cannot be removed.
- **Reconnect and lost outcomes** (#7): reconnecting rejoins the last synchronized project. An unknown command is resent with its original operation id and settled exactly once.
- **Location Files** (#9): per-world profiles of registered containers (chest, trapped chest, barrel, shulker box, hopper, dropper, dispenser). Files are named by UUID.
- **Check Materials** (#10): the server reads the current Location File's containers. Server admins read any position; Members read only positions that were granted. The client tells checked, unchecked, previous and machine counts apart. Results stay in memory. Without server support it falls back to OBSERVED counts.
- **Unshare regions / Rejoin shared regions** (#11): one placement's regions become a local detached copy. Rejoin exports the copy and installs the server's work; nothing is merged.
- **MCMaterialList screen** (#22): the footer button opens a screen with the list's state, the reason, the next step and the actions. Disabled controls explain why in their hover text. The Phase 1 diagnostic runs only with `-Dmcmateriallist.diagnostics=true`.
- English and Japanese translations with the same keys (`verifyTranslations`).

### Quality and verification

- **Two-client verification** (#8): two real clients run against a separate dedicated-server process that restarts once. The server runs without Litematica/MaLiLib, and a class-load audit checks that.
- **Performance, limits and observability** (#12): core measurements for 2,000 material rows, 2,000 regions and 20 participants. Over-limit datasets are refused as `TOO_LARGE` instead of `CORRUPT`. Region rows resolve through an index. Design section 13 operation traces leave out private content, which `verifyPerformance` checks.
- **Compatibility audit** (#14): upstream hook ownership, stored data and wire formats, and upgrade/downgrade notes ([docs/compatibility.md](docs/compatibility.md)). The material screen now re-checks the placement identity and says why it is unavailable.
- **Phase 5G coverage** (#34): real-client tests for Location File and Check Materials paths that had none:
  - placed shulker box, dropper and dispenser; the unloaded-chunk refusal; Bind/Unbind;
  - fallback without the stock channel; Block changed; the Grant/Revoke buttons; recovery of a damaged grant file; Cancel midway;
  - Location Files on the dedicated server and after its restart;
  - in the native shared UI: unreadable sharing metadata, the sharing view limit, the server save failure (AC-12) and a refused unshare save.
- **JSTQB test basis**: a test plan, acceptance traceability and test design ([docs/test-plan.md](docs/test-plan.md), [docs/test-traceability.md](docs/test-traceability.md), [docs/test-design.md](docs/test-design.md)). Black-box core tests use boundary values, decision tables and state transitions. JaCoCo coverage reports have no threshold.
- **License** (#23): MIT. `THIRD_PARTY_NOTICES.md` ships in the JAR, and `verifyDependencyLicenses` checks the pinned dependencies' declared licenses.
- **Release gate** (#13): hosted CI runs `verifyRelease` (every Phase 0-4 gate, `verifyApplicationJar`, `verifyDependencyLicenses` and `verifyTranslations`) and uploads the application JAR. Platform evidence is in [docs/platform-verification.md](docs/platform-verification.md).
- Acceptance record: [docs/acceptance-0.1.0.md](docs/acceptance-0.1.0.md). 15 of AC-01 to AC-18 are fully covered by automated gates and 3 partly (AC-01, AC-15, AC-17). Manual acceptance was not run.

### Fixed before release

- **Unloaded-chunk refusal** (#34): the client's `Level.hasChunkAt` stayed true for a chunk the client no longer had, so registering a container whose chunk had unloaded was never refused. The guard now asks the client chunk cache. The hosted run logged `cache=false levelHasChunkAt=true` 4000 blocks away.

### Known limitations

- One shared project is actively synchronized per connection.
- At most 16 placements hold sharing entries per client. Beyond that a placement reports the sharing view limit until entries are freed or the client restarts.
- Archived region history is not pruned. Each dataset holds at most 4,000 tasks, archived definitions included.
- Check Materials allows 4 scans per server, a 5 s cooldown and 512 positions per scan. With 20 players scanning at once the last result takes about a minute.
- A damaged Check Materials grant file fails closed. There is no in-game recovery.
- Unshare is blocked while any region operation is unconfirmed and after a journal recovery (owner decision, #33).
- Downgrading to an older development build after saving shared state is not supported. Back up `config/mcmateriallist/` first.
