# Phase 1: Local Work State

## Goal

Maintain material gathering and region building work independently for a saved Litematica placement. Assignment, completion, notes and completion metadata survive client restart. This phase provides the local model, storage, adapters and a diagnostic; final task controls and multiplayer remain future work.

The pinned Phase 0 baseline is unchanged: Minecraft 26.2, Litematica 0.28.3, MaLiLib 0.29.2, Fabric Loader 0.19.3, Fabric API 0.154.0+26.2, JDK 25, Gradle 9.7.1 and Loom 1.17.20. The supplied collaboration design v0.1 was read from its original attachment; it is not tracked in this repository. Phase 0 evidence remains in `phase-0-compatibility-spike.md`.

## Architecture

`core.work` contains immutable IDs, definitions, states, explicit commands, progress and reconciliation. It imports no Minecraft, Fabric or Litematica classes. `core.persistence` contains an explicit Gson codec, local snapshot store and a lifecycle-owned serial I/O service. Gson is a JSON dependency, not a game dependency; Minecraft supplies it at runtime.

Client-only `PlacementWorkAdapter` captures upstream observations on the client thread into a `WorkSnapshot`, which contains no Minecraft objects. `LocalWorkService` performs disk reads, reconciliation and writes on its owned background executor. Disk is authoritative: operations reload before mutation, and successful changes are returned only after a successful save. Shutdown drains the queue rather than canceling an atomic replacement.

Each placement has two independently saved datasets with separate `DatasetId` UUIDs: `MaterialWorkDataset` and `RegionWorkDataset`. A corrupt region snapshot does not reset or block valid material work. There is no combined transaction across the two files: refresh reports a result for each side. No second placement UUID, database, server work state, synchronization payload or global state cache is introduced.

## Placement identity contract

The Phase 0 `PlacementIdentity` boundary wraps `SchematicPlacement#getHashId()` as the Minecraft-independent `LocalPlacementId`. Upstream stores this random UUID as `hash_code` in its placement JSON. It is not Java `hashCode()` and does not depend on schematic filename, placement name, origin or row index.

Persistent capture additionally requires the exact placement instance to be registered with the current placement manager, `shouldBeSaved()` to be true, a saved schematic file, and no other registered placement with the same UUID. Temporary, unregistered and duplicated placements are rejected with typed adapter results. Distinct placements of the same schematic remain distinct. User replacement/copying of upstream JSON outside this contract cannot establish reliable identity automatically.

## Material identity contract

Read `MaterialListPlacement#getMaterialsAll()`, never the filtered/ignored view. The supported upstream version aggregates by `ItemType` with component comparison disabled; use the actual aggregated row's item registry ID (for example `minecraft:blackstone`) as `MaterialTaskId`. Translated text, counts, list order and stack object identity are irrelevant to task identity. This adapter never counts blocks independently.

An accessor verifies that the material list belongs to the requested placement; schematic/area material lists cannot silently use the selected placement's ID. Version-gated readiness mixins record successful `setMaterialListEntries` publication and invalidate it when placement counting restarts. Pending counting, uninitialized lists and canceled refreshes are rejected. An unrelated pending placement count conservatively returns `MATERIALS_BUSY`; retry when counting finishes. A successfully counted empty schematic is valid. Count definitions are observations of upstream behavior and selected counting scope, not a second source of material truth.

`identityRule: 1` versions the registry-only identity rule. A future component grouping change requires a reviewed migration and new rule; it must not silently reuse current IDs.

## Region identity contract

Each region task has its own durable `RegionTaskId` UUID. Its mapping stores the schematic's region key, original relative origin and signed size from `getAreaPositions()` / `getAreaSizes()`. World placement origin, rotation, mirror and placement enabled flags are not task identity inputs. This describes the schematic definition, not mutable sub-region placement transforms.

An exact current definition keeps its UUID/state. An exact unique archived definition can reappear with its prior UUID/state. Same-name geometry changes, same-geometry renames, duplicate descriptors and multiple historical candidates cannot transfer old state to a new region. They create default work with a pending review flag and archive old work. Repeated unchanged observations retain current IDs instead of growing history on every refresh.

Pending review survives removal and restoration. Persisted `reviewRequired` may refer to active or archived IDs; `activeReviewRequired()` and reconciliation results expose current mismatches. Work commands on a blocked active region return `REVIEW_REQUIRED`. `acknowledge(id)` accepts only that active new definition and never transfers another region's work. Phase 1 has no review-resolution UI; future UI must expose the mismatch and deliberate acknowledgement.

## State transition rules

`TaskState` stores task ID, nullable assignee UUID, done, UTF-8 note, nullable completed-by UUID, nullable completion instant and nonnegative row version. Completed-by and time must both be present exactly when done is true. Note defaults to empty and is limited to 4096 UTF-8 bytes with well-formed Unicode.

| Operation | Effect |
| --- | --- |
| Claim | Assign actor if unassigned; reject another owner's claim. |
| Release | Remove actor's assignment; preserve completion and note. Reject release by another actor. |
| Assign | Explicitly assign a non-null UUID in local mode. No multiplayer permission policy is implied. |
| SetDone(true) | On a change, set completion actor/time and assign actor if empty. |
| SetDone(false) | Clear completion actor/time; preserve assignment and note. |
| SetNote | Change only note. |

Actual mutations increment `rowVersion`; repeated identical commands return `Unchanged` and do not rewrite time or advance versions. Invalid input, unknown task, ownership conflict, pending review and exhausted version return typed failures. There is no authoritative `ToggleDone` operation. Completion never changes material counts or upstream placement/region enabled/rendering flags.

`Progress` counts done among all current tracked tasks and floors the percentage: 4/19 = 21%, 5/62 = 8%, 0/0 = 0%, all complete = 100%. Archived tasks are excluded; search, sorting, Ignore and Hide Available are view concerns.

## Persistence format and location

Client storage is relative to Fabric's client config directory:

```text
config/mcmateriallist/placements/<canonical-placement-uuid>/
    materials.json
    materials.json.bak
    materials.json.lock
    regions.json
    regions.json.bak
    regions.json.lock
```

No metadata sidecar is needed: each snapshot carries its own dataset ID, placement ID, kind, schema, identity rule and generation. Nothing is added to `.litematic`.

Illustrative material snapshot (all UUIDs here are synthetic):

```json
{
  "schema": 1,
  "identityRule": 1,
  "kind": "MATERIALS",
  "datasetId": "11111111-1111-4111-8111-111111111111",
  "placementId": "22222222-2222-4222-8222-222222222222",
  "generation": 3,
  "tasks": [{
    "id": "minecraft:blackstone",
    "total": 1200,
    "missing": 1200,
    "available": 0,
    "state": {
      "assignee": "33333333-3333-4333-8333-333333333333",
      "done": true,
      "note": "資材収集完了",
      "completedBy": "33333333-3333-4333-8333-333333333333",
      "completedAt": "2026-10-04T00:00:00Z",
      "rowVersion": 2
    }
  }],
  "archived": []
}
```

Region snapshots use `kind: "REGIONS"`, another dataset UUID, `reviewRequired: []` and task rows shaped as follows; the state has the same fields:

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "key": "Region 2",
  "origin": [10, 0, 0],
  "size": [2, 2, -2],
  "state": {
    "assignee": null, "done": false, "note": "",
    "completedBy": null, "completedAt": null, "rowVersion": 0
  }
}
```

Strict parsing rejects duplicate/unknown fields, malformed UTF-8/Unicode, invalid identities/types/invariants, integer overflow, excessive depth/nodes and more than 4000 active plus archived tasks. Files are bounded to 32 MiB. Serialization sorts IDs deterministically. Snapshots deliberately contain work-state UUIDs and notes as required by this feature; application feedback/logging never includes these contents or file paths. Backups and damaged copies also contain private work state and should be handled as user data.

### Safe writes and recovery

Paths are generated only from validated canonical UUIDs and fixed filenames. Existing path ancestors and targets must be ordinary directories/files; symlinks, junction escapes and special files are rejected. This does not claim resistance to a malicious local process swapping directories concurrently between filesystem checks; the client data directory must be under the user's control.

A per-dataset file lock plus expected generation/dataset ID prevents cooperating writers from overwriting stale state. Create is allowed only if neither primary nor backup exists. Save writes an owned temporary file, forces it to storage, reads/validates it and requires atomic replacement. Before replacing a valid primary, it atomically saves that primary as the previous-good backup. If atomic moves are unsupported, the operation returns `ATOMIC_REPLACE_UNAVAILABLE`; it does not fall back to a weaker write. The store does not force directory metadata, so power-loss durability remains filesystem-dependent; tests simulate pre-replacement I/O failures, not power removal.

Corrupt snapshots never become a new blank dataset. A valid backup produces `RECOVERY_REQUIRED` with a candidate that is not writable until explicit `recover` is called. Recovery preserves damaged primary bytes under an owned `*-damaged-*.json` name, validates the backup and atomically restores it. It may lose the most recent operation because the backup is the previous generation. There is no automatic recovery on launch. Future schema/identity rules return `UNSUPPORTED_SCHEMA` and are never overwritten; a malformed or future backup also blocks ordinary save. Service results carry each storage status explicitly.

## Reconciliation

Material matches use registry identity and retain all work fields across sorting and changed counts. Changed definitions are reported; done is not automatically reset when Total changes. New tasks start empty, removed tasks are archived, and reappearing IDs recover their archived state. Regions use the conservative mapping above. Reconciliation returns matched, added, removed, definition-changed and active review sets. Definition updates advance dataset generation; work row versions advance only through commands.

## Minimal diagnostic

Ordinary clicks retain the Phase 0 message. **Shift-click** the existing `MCMaterialList` footer button on either placement configuration or its material list to explicitly create/load/reconcile local datasets and show material/region counts and progress. This action is opt-in and can write the two local snapshots. Its tooltip labels it as a Phase 1 diagnostic. The button disables while saving; errors show typed statuses without private details. No final heads, check/X controls, Hide Done, storage scanning or sharing are implemented.

## Verification evidence

Observed on Windows, 2026-10-04, with the pinned JDK/Gradle baseline:

```powershell
.\gradlew.bat :core:test --console=plain
.\gradlew.bat :fabric:compileGametestJava --console=plain
.\gradlew.bat :fabric:verifyClientRestart --console=plain
.\gradlew.bat clean verifyPhase1 -PacceptMinecraftEula=true --console=plain
```

The final clean gate exited **0**, `BUILD SUCCESSFUL in 1m 36s`; it includes build, core tests, bytecode/classpath isolation, two real Minecraft client processes and the isolated dedicated server. `verifyPhase0` remains in the dependency chain and passed. Its original client test source and compatibility report were not modified.

- **24 core tests**, zero failures/errors/skips: 4 placement identity, 9 domain/transition/reconciliation, 9 codec/store/recovery, 2 service lifecycle/independent failure tests. Progress examples, Unicode notes, ordering/count changes, archival/reappearance, pending review, version exhaustion and immutable collections pass.
- The filesystem suite exercised a real symlink on this Windows host. Corrupt/future files and backups, invalid UTF-8, mismatched placement, duplicate fields, overflow/huge numbers, stale generations and injected pre-replacement failure were rejected or recovered as specified.
- The real client fixture contains stone/glass/dirt aggregated by upstream and three schematic regions. It marks only stone and Region 2 done, writes Japanese notes, and verifies assignee, completion actor/time and row version 2. Other rows remain empty. Ignore/Hide Available/sorting retain all three tracked tasks, and work changes preserve count definitions and placement/region enabled/rendering flags.
- Both real Shift-click footer diagnostics were exercised with mouse/key input. The original Phase 0 GUI regression suite still covers scales 1–4 and existing actions. Diagnostic screenshots are under `fabric/build/run/clientGameTest/screenshots/`.
- A fresh Minecraft process restores the saved placement by its expected UUID among the saved manager entries, re-counts real materials and verifies exact dataset identities/definitions/states against the first process. Both datasets show 1/3 completed; only Region 2 is done. `.litematic` bytes remain equal before/after work and restart.
- Uninitialized, queued and canceled material calculations cannot be captured as ready. An unregistered saved-file placement is rejected. The initial rendering-disabled fixture produced zero upstream rows; the final live-count fixture uses normal rendering-enabled placement, without adding a replacement counter or modifying upstream counting behavior.
- Dedicated-server initialization reached `Phase 0 dedicated server isolation PASS` without Litematica/MaLiLib installed. Its actual class-loading trace contains no optional-mod, Minecraft client or application client classes. Core/common bytecode and common runtime dependency isolation passed.
- Distribution `fabric-0.1.0-phase.1.jar` contains the nested core jar and client/common implementation; archive inspection found no gametest, server-smoke or test-mixin code/resources.
- An independent read-only review found two issues: archived pending review could be lost on reappearance, and a canceled/uninitialized material list could be accepted. Both were fixed with regression coverage, re-reviewed, and included in the final clean gate. Final review reported no new required fix.

The CI workflow now calls `verifyPhase1`, but hosted CI was **not run** because this task does not push. Linux/macOS and other mod combinations remain unverified. Upstream OSHI/Realms/test-account and Gradle deprecation messages appeared; no application compiler warnings were suppressed. The test-only fixtures, worlds, screenshots and traces remain under ignored build directories, contain synthetic identities and are excluded from distribution.

## Risks and Phase 2 readiness

Optional mixins/accessors and readiness publication are tied to the exact supported upstream versions. New versions remain disabled until reviewed. Temporary/non-persisted placements cannot use persistent work. Region renames, geometry changes and ambiguous history require review; exact geometry/key reuse cannot establish provenance across arbitrary external edits. Region placement transforms do not redefine the schematic mapping. A future material component grouping rule requires migration.

Phase 2 must surface per-dataset storage/recovery/conflict statuses, active region review, archived work and definition change notices. It should submit explicit commands to the I/O service and display durable results instead of mutating records or toggling state optimistically. Local UUID assignment is not server permission enforcement. Networking and a shared authoritative project remain Phase 3 work.

**Phase 1 is complete; Phase 2 can begin on this pinned baseline.** There is no unresolved Phase 1 blocker. This is readiness to implement local UI, not evidence that final screenshot UI or multiplayer behavior has been implemented. This task stops at Phase 1.
