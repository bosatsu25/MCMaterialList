# Phase 3A: explicit shared work integration

This intermediate build connects the accepted [shared authority](SHARED_PROTOCOL.md) and [Fabric transport](SHARED_TRANSPORT.md) to the existing native material and region screens. It does not claim complete multiplayer acceptance or release readiness. Full member management, reconnect/lost-outcome resolution, fault acceptance and external two-client validation remain later work (Issues #6–#8).

## Use local or shared work deliberately

Opening a saved placement reads existing local/shared metadata without creating work or selecting a server project. Local Track/Refresh, notes, archives, recovery, ordinary Phase 0 clicks and Shift-click diagnostics retain their prior behavior.

1. On a compatible server, select **Share** in the native material list or placement configuration.
2. To create, keep the placement and all original regions enabled, use all layers and unmodified original region transforms, then complete a fresh upstream material count. Select **New project...**, enter a name and **Create empty work**. The manifest captures all material types and original region definitions; initial assignments, notes and completion are empty. No local rows are uploaded or silently imported.
3. To select existing work, use **Find projects**, then **Join** for a project the connected account may read. Joining validates the actual schematic digest, dimension, placement origin/rotation/mirror and exact original definitions. A new material binding requires a full native count so its material definition can be checked.
4. Work appears on the invoking native list. Material and region bindings are independent, including different projects. One connection has one live transport subscription; after using another project, explicitly **Join / synchronize** or **Refresh work** before editing the previous one.
5. Use row completion controls or faces/details for Claim, Release and explicit **Save note**. Shared details also provide **Assign...** for a supplied writer UUID and **Clear assignment**. Invalid/nonwriter targets are rejected by server authority. Success appears only after durable server confirmation and the corresponding contiguous state update.
6. **Use local work** restores the invoking list's original local dataset. It preserves unresolved shared commands and the other list's binding. Binding selection is disabled during the bounded source-check/save/admission window.

The creator's **Viewer access...** dialog grants/removes read-only access using a supplied canonical UUID. There is no name-to-UUID service, external skin lookup or automatic public access. A permission change invalidates the active view and requires explicit synchronization. Viewer notes are readable but editing controls are disabled. Access removal promptly hides rows and note drafts and clears derived private cache. Full Member role administration is not included here.

## Counts, identity and refusal reasons

Shared progress counts current server manifest tasks. Total/Missing/Available, multiplier, Ignore, Hide Done, Show Info, layer range and enabled-region observations remain client-owned. A partial personal recount followed by ordinary shared Refresh cannot change the global denominator. Re-enabling regions without a fresh full count cannot make an earlier partial count eligible for project creation.

Creation records native count provenance before counting and checks the placement revision and full scope again when results publish. The application never enables regions or changes count scope on the user's behalf. Actual `.litematic` content is hashed read-only on a background worker under the configured schematic directory; no schematic/NBT transfer or file modification is part of sharing.

| State | Behavior and next action |
| --- | --- |
| Missing/temporary/duplicate placement, missing source or unfinished count | Shared capture is refused; save a valid unique placement/source and finish the required count. |
| Partial or stale full count | Project creation is refused; restore full original counting scope and recount. |
| Source digest, original definitions, dimension or placement baseline changed | Shared editing is blocked; use the matching saved source/baseline before explicitly synchronizing. |
| Another project selected, invalidated snapshot or stale version | Shared writes stay blocked until an authorized matching snapshot is joined. |
| Missing/incompatible server, disconnect or different world/account | No offline shared edits; ordinary local work remains usable. Previous-account private presentation is hidden. |
| Admission/storage/backpressure failure | No optimistic row update. An admitted command with no authoritative result remains unknown. |
| Unknown original command | Dependent writes remain blocked for that account/project. Mode changes do not cancel or remap it. Automatic replay/resolution is not implemented in Phase 3A. |
| Sharing metadata recovery | Recover explicitly in two steps. Local work remains usable, but recovered shared writes stay blocked because the backup may omit a sent command. |

## Client save and command boundary

`SharedWorkBackend` selects a direct shared presentation for each existing native session. Its local `dataset()` remains the original immutable local dataset; shared rows are never passed to `LocalWorkService` save/reconcile/recovery. Region mapping uses the server's stable task UUID plus exact original region descriptor. Local dataset IDs are preserved separately from project/list IDs.

`SharedClientService` owns background serial I/O. A single schema-1 save unit under `config/mcmateriallist/shared-client/placement-<placement-uuid>.json` contains:

- Generation and local placement identity.
- Independent material/region bindings, each preserving the original local dataset ID and optional shared binding ID, account and world/project/list scope.
- Authorized confirmed snapshots with contiguous cursors, as derived cache.
- Original operation IDs, account/connection/binding/scope, explicit intent and expected versions; confirmations store status/revision, never an ACK row as a new binding's state.
- A persisted recovered-journal-uncertainty flag.

The outer strict JSON contains `schema: 1` and bounded Base64 binary payload. The explicit binary codec rejects invalid booleans/counts, truncation and trailing data. Paths are derived only from configured roots and typed IDs; ancestor symlinks/reparse points and unsafe source paths are rejected. Atomic forced temporary-file replacement, generation CAS and previous-good backup protect writes. Missing primaries with a valid backup, corrupt/nonblank state and future schemas never become silent blank work. Opening is read-only; shutdown drains owned queues.

Every backup omits derivative shared snapshots while preserving the exact prior binding/local-ID/journal generation. Thus permission removal cannot leave a previous authorized snapshot in the normal backup. Original journal intents, including required note text and account identity, remain product data until genuinely resolved. No application logging of those fields is added.

Explicit backup recovery retains damaged primary bytes, restores metadata/journal without cached rows and persists `recoveredJournalUncertain`. A fresh snapshot, local switch or account/binding change cannot clear that flag: the backup may predate a sent operation. Recovery is metadata/journal recovery, not a server project rollback. Future confirmed region-copy/detach can extend the same save unit; Unshare/copy is not implemented here.

The producer's durable-admission callback saves the exact original operation before sending. A current-scope ACK never advances the contiguous cache cursor. A legitimate late original-context result confirms only its original journal entry, even after switching to local mode; it cannot alter local rows or a replacement binding. Unconfirmed operations survive mode changes, disconnects and account changes without automatic replay. Known rejected/conflicting outcomes require fresh synchronization where state may be stale.

## Explicit capacities

These are rejection limits, not measured throughput guarantees. Accepted server/wire limits remain in the producer contracts.

| Boundary | Limit |
| --- | --- |
| Client save payload | 65 MiB binary, bounded Base64 JSON envelope |
| Original journal | 64 unknown and 64 retained confirmed operations per placement |
| Serial client save queue | 64 queued operations |
| Lifecycle placement views | First 16 distinct placements; later sharing attempts require a client restart, local work remains usable |
| Source digest | 512 MiB source; 64 KiB streaming buffer; 4 queued background checks |
| Correlated create/join/admin histories | Bounded to 16 requests per controller category, 30-second request timeout |

The source digest checks size, modification time and file identity before/after streaming. It is not an exclusive OS-level lock on a file another program can modify. Native manifest and authority are revalidated around asynchronous work; uncertain/raced observations fail closed where detected.

## Verification and evidence

With configured JDK 25 and accepted Minecraft EULA:

```powershell
.\gradlew.bat :core:test --tests dev.mcmateriallist.core.shared.client.*
.\gradlew.bat :fabric:runSharedUiTest -PacceptMinecraftEula=true
.\gradlew.bat clean verifyPhase3 -PacceptMinecraftEula=true
```

`verifyPhase3` composes `verifyPhase2` (all prior core/build/local UI/fresh-process restoration/isolated server gates), `verifySharedTransport` and `runSharedUiTest`. Application-JAR and bytecode/class-load isolation checks remain required. The native shared fixture has its own test-only source set and mixins, excluded from the application archive; real controls and registered Fabric payloads drive it. Passing Gradle alone is insufficient: current logs require the final assertion marker and reject Fabric test exceptions.

Focused core verification observed 14 tests with zero failures/errors/skips, covering read-only/corrupt/future-safe store behavior, CAS, independent bindings, original-context journal/late-result isolation, cache-free backup and quarantined recovery, capacity, bounded codec and read-only source hashing. Native meaningful RED initially failed at the absent Share action; compile/mixin prerequisite failures are not counted as RED.

The native fixture covers initially empty server work with unchanged local files, independent material/region projects, actual Done/Claim/Release/Assign/SetNote and invalid-target errors, deliberately held admission and late authoritative result, contiguous refresh, creator Viewer changes, full-count provenance, personal quantities/filters, and unchanged source/local IDs. Geometry checks compare real controls at GUI scales 1–4, a narrow window and Japanese resource reload. A synthetic creator grants the actual connected fixture actor Viewer access, then publishes actual server revocation events; this is one in-process offline connection, not external AC-17 two-client proof. Protocol mismatch exercises honest unsupported presentation and local fallback.

The complete focused native run passed with `Shared native UI all assertions PASS` after the owned socket/server closed, including GUI scales 1–4, the narrow window, Japanese controls, actual connected Viewer revocation with cleared draft/cache, and unsupported-protocol local fallback. The focused run completed successfully in 1m8s. Two geometry failures were fixed without weakening assertions: narrow-region Share overlapped the native reset control; Japanese material Share overlapped the localized menu. Only the added Share control now adapts its position.

The configured JDK 25 `clean verifyPhase3 -PacceptMinecraftEula=true --console=plain` run passed with exit 0 in 5m12s (38 tasks, 34 executed). Fresh core XML contained 21 suites and 98 tests with zero failures, errors or skips. Native local work and fresh-process restoration passed; the isolated server emitted both original isolation and asynchronous shared-authority checkpoints, with no forbidden client/optional-mod classes in its actual class-load log. The native shared UI and socket suites emitted their final PASS markers after owned connection/server closure, and the complete saved run contained no gametest exception or assertion-failure marker.

The actual `fabric-0.1.0-phase.3a.jar` audit found the expected application ID/version, 108 outer entries and 154 entries in the nested core library, with no test helpers/resources/logs or optional upstream implementation leakage. Retained compiler warnings and existing OS/Gradle/dependency diagnostics remain visible; this is a passing gate, not a warning-free build. These local results precede independent review and hosted exact-head verification.

Evidence is retained under ignored `core/build/test-results/test`, `fabric/build/run/clientGameTest`, `sharedTransportTest`, `sharedUiTest` and `serverSmoke`. CI uploads selected XML, screenshots and synthetic test logs on success/failure, including server class-load evidence, without uploading runtime config/world/schematic data. Application archives must not contain test helpers/mixins/resources, screenshots/logs or optional upstream implementations.

## Remaining acceptance

Member management and permission matrix expansion, lost-outcome/reconnect recovery, fault/performance acceptance, external authenticated two-client AC-17 evidence and release/signing/license decisions remain unclaimed. The verified basic integration is not a substitute for those later gates. The intermediate `0.1.0-phase.3a` version communicates that scope.
