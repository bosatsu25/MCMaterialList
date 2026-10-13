# Phase 3A: explicit shared work integration

This intermediate build connects the accepted [shared authority](SHARED_PROTOCOL.md) and [Fabric transport](SHARED_TRANSPORT.md) to the existing native material and region screens. It does not claim complete multiplayer acceptance or release readiness. Core membership policy and durable migration are implemented for Issue #6. Native role-management integration, reconnect/lost-outcome resolution, fault acceptance and external two-client validation remain separate work (Issues #6–#8).

## Use local or shared work deliberately

Opening a saved placement reads existing local/shared metadata without creating work or selecting a server project. Local Track/Refresh, notes, archives, recovery, ordinary Phase 0 clicks and Shift-click diagnostics retain their prior behavior.

1. On a compatible server, select **Share** in the native material list or placement configuration.
2. To create, keep the placement and all original regions enabled, use all layers and unmodified original region transforms, then complete a fresh upstream material count. Select **New project...**, enter a name and **Create empty work**. The manifest captures all material types and original region definitions; initial assignments, notes and completion are empty. No local rows are uploaded or silently imported.
3. To select existing work, use **Find projects**, then **Join** for a project the connected account may read. Joining validates the actual schematic digest, dimension, placement origin/rotation/mirror and exact original definitions. A new material binding requires a full native count so its material definition can be checked.
4. Work appears on the invoking native list. Material and region bindings are independent, including different projects. One connection has one live transport subscription; after using another project, explicitly **Join / synchronize** or **Refresh work** before editing the previous one.
5. Use row completion controls or faces/details for Claim, Release and explicit **Save note**. Shared details also provide **Assign...** for a supplied authorized participant UUID (creator or explicitly granted Viewer) and **Clear assignment**. Invalid/nonparticipant targets are rejected by server authority. Success appears only after durable server confirmation and the corresponding contiguous state update.
6. **Use local work** restores the invoking list's original local dataset. It preserves unresolved shared commands and the other list's binding. Binding selection is disabled during the bounded source-check/save/admission window.

The creator's **Viewer access...** dialog grants/removes read-only access using a supplied canonical UUID. There is no name-to-UUID service, external skin lookup or automatic public access. A permission change invalidates the active view and requires explicit synchronization. Viewer notes are readable but editing controls are disabled. Access removal promptly hides rows and note drafts and clears derived private cache. Full Member role administration is not included here.

## Counts, identity and refusal reasons

Shared progress counts current server manifest tasks. Total/Missing/Available, multiplier, Ignore, Hide Done, Show Info, layer range and enabled-region observations remain client-owned. A partial personal recount followed by ordinary shared Refresh cannot change the global denominator. Re-enabling regions without a fresh full count cannot make an earlier partial count eligible for project creation.

Creation records native count provenance before counting and checks the placement revision, loaded source proof and full scope again when results publish. The application never enables regions or changes count scope on the user's behalf. Actual `.litematic` content is hashed read-only on a background worker under the configured schematic directory; no schematic/NBT transfer or file modification is part of sharing.

A bounded local proof is attached to each successfully loaded native schematic. A scoped adapter observes the exact raw stream used by the pinned native NBT reader. It copies consumed bytes into bounded chunks without hashing or waiting for queue space on the native thread. When the native reader closes, the worker drains the unread tail of that same opened stream, hashes all bytes, closes it and releases the buffers. The gzip-to-plain fallback discards the first candidate. A completed proof retains only its digest, file metadata, object/load generation and native edit epoch; no NBT or source bytes are persisted or transmitted.

A separate background hash of the current saved file must equal this loaded-stream digest before Create, join or commands. Commands repeat this check after durable journal persistence and recheck native identity at admission. Refusal preserves the original unknown journal entry. A fresh count is associated with the same completed load proof, so recounting an old loaded object cannot attach it to replacement bytes. Native editing invalidates the proof even when geometry is unchanged. The pinned Reload retains its old modified flag; successful reload establishes a new edit-epoch baseline, and another native edit invalidates it again.

File metadata probes are only an early guard: matching timestamps, sizes or Windows creation-time tunneling do not bypass the digest comparison. Missing, pending, timed-out or capacity-rejected observations fail closed for sharing while normal native reading continues. Once an observation completes, a fresh full count can establish valid provenance. Source hashing stays off the render thread; small file-attribute probes remain at capture boundaries. This is not a hostile-filesystem atomic-snapshot or slow/network-folder performance guarantee.

| State | Behavior and next action |
| --- | --- |
| Missing/temporary/duplicate placement, missing source or unfinished count | Shared capture is refused; save a valid unique placement/source and finish the required count. |
| Unsaved native schematic edits or a replaced saved source | Save or discard edits, reload the saved schematic in Litematica, run a fresh full count, then explicitly synchronize. A recount alone cannot associate an old loaded schematic with a replaced file. |
| Partial or stale full count | Project creation is refused; restore full original counting scope and recount. |
| Source digest, original definitions, dimension or placement baseline changed | Shared editing is blocked; use the matching saved source/baseline before explicitly synchronizing. |
| Another project selected, invalidated snapshot or stale version | Shared writes stay blocked until an authorized matching snapshot is joined. |
| Missing/incompatible server, disconnect or different world/account | No offline shared edits; ordinary local work remains usable. Previous-account private presentation is hidden. |
| Admission/storage/backpressure failure | No optimistic row update. An admitted command with no authoritative result remains unknown. |
| Unknown original command | Dependent writes remain blocked for that account/project. Mode changes do not cancel or remap it. Automatic replay/resolution is not implemented in Phase 3A. |
| Sharing metadata recovery | Recover explicitly in two steps. Local work remains usable, but recovered shared writes stay blocked because the backup may omit a sent command. |

## Client save and command boundary

`SharedWorkBackend` selects a direct shared presentation for each existing native session. Its local `dataset()` remains the original immutable local dataset; shared rows are never passed to `LocalWorkService` save/reconcile/recovery. Region mapping uses the server's stable task UUID plus exact original region descriptor. Local dataset IDs are preserved separately from project/list IDs.

`SharedClientService` owns background serial I/O. A single schema-2 save unit under `config/mcmateriallist/shared-client/placement-<placement-uuid>.json` contains:

- Generation and local placement identity.
- Independent material/region bindings, each preserving the original local dataset ID and optional shared binding ID, account and world/project/list scope.
- Authorized confirmed snapshots with contiguous cursors, as derived cache.
- Original operation IDs, account/connection/binding/scope, explicit intent and expected versions; confirmations store status/revision, never an ACK row as a new binding's state.
- A persisted recovered-journal-uncertainty flag.

The client and project outer strict JSON contains `schema: 2` and bounded Base64 binary payload. The world namespace envelope remains `schema: 1`. Shared client, project and world envelopes classify the top-level numeric schema independently of member order before interpreting current payloads. Integral future versions also include decimal/exponent spellings and huge exponents without expanding them; future primaries/backups cannot be downgraded by save or explicit recovery. Current schema spelling, duplicate fields, malformed numbers and fractional versions remain strict. The explicit binary codec rejects invalid booleans/counts, truncation and trailing data. Paths are derived only from configured roots and typed IDs; ancestor symlinks/reparse points and unsafe source paths are rejected. Atomic forced temporary-file replacement, generation CAS and previous-good backup protect writes. Missing primaries with a valid backup, corrupt/nonblank state and future schemas never become silent blank work. Opening is read-only; shutdown drains owned queues.

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
| Native load proof | 4 active observations; 8 queued 64 KiB chunks per observation (2 MiB total queued bytes), plus bounded producer/tail buffers; 30-second proof deadline; overflow refuses sharing |
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

Independent review then identified three asynchronous boundary errors. Native shared editing now waits for exact confirmed cache publication; a restored shared binding blocks local recount/Refresh while its metadata loads; and a reordered future-schema primary is rejected without overwriting it from a backup. Focused store and native tests reproduce those failures and cover false/closed transport admission while preserving unknown originals. The amended configured JDK 25 default-selector `verifyPhase3` run passed with exit 0 in 4m18s (35 tasks, 18 executed). Fresh core XML contained 21 suites and 101 tests, with zero failures, errors or skips. Native local work, fresh-process restoration, dedicated-server isolation, the default-all shared UI and socket transport emitted their final PASS markers after owned closure; the complete log had no client gametest exception or assertion-failure marker. Re-audit of the application JAR confirmed the expected ID/version, 108 outer and 154 nested-core entries, and no test, log or optional-upstream implementation leakage. The server class-load log contained no forbidden client, Litematica or MaLiLib implementation classes. These are local verification results; fix review, commit and hosted exact-head CI remain separate gates.

After the final schema, loaded-source, admission and Done-initialization fixes, the configured JDK 25 `clean build verifyPhase3` run with the default-all selector passed with exit 0 in 4m27s (38 tasks, 34 executed and 4 up-to-date). Fresh core XML contained 23 suites and 115 tests, with zero failures, errors or skips. Dedicated-server isolation and asynchronous authority readiness passed; the native shared UI and socket suites emitted their final PASS markers after owned shutdown/drain. The complete log contained no explicit assertion or gametest failure. Fresh local/restoration screenshots were visually checked for material progress 4/19, region progress 5/62 and Japanese shared-management controls.

The resulting application JAR had the expected ID/version, 117 outer entries and 158 nested-core entries, with no test helpers, logs or optional upstream implementation leakage. The server class-load audit found no forbidden client, Litematica or MaLiLib implementation classes. These results cover the final local implementation; scoped re-review and hosted CI for the exact committed head remain separate gates.

Evidence is retained under ignored `core/build/test-results/test`, `fabric/build/run/clientGameTest`, `sharedTransportTest`, `sharedUiTest` and `serverSmoke`. CI uploads selected XML, screenshots and synthetic test logs on success/failure, including server class-load evidence, without uploading runtime config/world/schematic data. Application archives must not contain test helpers/mixins/resources, screenshots/logs or optional upstream implementations.

## Remaining acceptance

Native Member management integration, lost-outcome/reconnect recovery, fault/performance acceptance, external authenticated two-client AC-17 evidence and release/signing/license decisions remain unclaimed. The verified basic integration is not a substitute for those later gates. The intermediate `0.1.0-phase.3a` version communicates that scope.

## Issue #6 core membership and migration

Current snapshots carry an immutable map of Viewer, Member and Administrator roles, including every participant. The historical creator is only the initial Administrator: another current Administrator may demote or remove that creator. Removing or demoting the last current Administrator is refused atomically. The 20-participant limit includes all roles.

Viewers can read. Members can Claim an unassigned task, Release their own assignment, and change completion or notes on their own tasks. Completing an unassigned incomplete task assigns it to the authenticated actor and completes it in one row version and one durable transition. After Release, completion, completion actor/time and notes remain intact; a Member must Claim an unassigned completed task before editing it. Administrator-only intents are Assign and ClearAssignment, and only current Administrators can change membership or the manifest. Release retains its explicit own-assignment semantics; clearing another participant uses ClearAssignment. Material and region tasks use the same pure policy, also applied before client journal admission.

Current membership and privileged-intent capability are checked before operation replay. Each schema-2 server operation stores one boolean recording whether the original pre-operation row permitted that actor's intent under Member rules. This preserves original Claim/Release outcomes after their own state changes or an Administrator-to-Member demotion, while refusing replay of former privileged or other-assignee edits. Legacy operations lack this evidence and migrate with the flag false: their original outcomes remain available to current Administrators, but demoted Members fail closed. Removed actors and Viewers cannot retrieve historical outcomes. Invalid targets/rows and denied current operations do not append a durable generation. Valid stale-version conflicts retain the existing bounded durable-outcome policy.

Wire protocol 2 carries the complete role map and explicit SetMembership role/removal intent. Version-1 network messages are unsupported and require the existing local-fallback path; the explicit legacy decoder accepts only stored schema-1 project archives and the schema-1 client's embedded Snapshot/TaskCommand blobs. It is not a network compatibility path. Literal synthetic fixtures captured with the merged version-1 codec pin world/project/list/placement identities, generations, manual rows, retired rows, reconfirmation, independent bindings and original unknown/confirmed journals.

Loading schema 1 does not rewrite primary or backup bytes. A successful compare-and-swap or explicit recovery writes schema 2, preserving identities, versions and journal contents; client recovery still drops derived caches and quarantines an uncertain recovered journal. Future schemas are protected separately for project/client (greater than 2) and world (greater than 1), including reordered fields, decimal/exponent spellings and numeric tokens larger than the JSON reader's lookahead buffer. A legacy payload marked as schema 2 is rejected rather than reinterpreted. Native role controls/receiver dispatch remain the next Issue #6 integration task; no reconnect automation or external authenticated two-client evidence is claimed here.