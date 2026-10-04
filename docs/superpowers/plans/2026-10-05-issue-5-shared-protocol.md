# Issue 5: Shared protocol and authoritative state

Status: Task 1 implemented, review pending. Issue #4 merged via PR #21 with both exact-head hosted checks successful. Base: 648a9229002e811be47b5b11850926ada299462b. Branch: feature/issue-5-shared-project-protocol. Clean main was verified before branch creation. No Phase 3 acceptance evidence yet.

## Goal
Implement Issue5's server-authoritative project/protocol foundation, preserving all local UI/identity/persistence gates. Read design sections5,9–12 completely and the Phase0/1/2 reports. Subsequent Issues6–8 own full membership UX/policy, fault/reconnect verification and isolated two-client integration; do not claim those later acceptance results here.

## Global Constraints
- Separate immutable Minecraft-free core models, common/server Fabric transport and optional client/Litematica adapter. Dedicated server requires neither optional client mod nor client classes.
- Required identities are worldNamespace, projectId, materialListId, regionListId and client-owned localPlacementId. Names/paths/row index/translations/counts never substitute for identity. Material registry keys are scoped to project/list; regions use server-persisted UUID plus exact original key/origin/signed-size mapping.
- Keep material and region bindings independent. Existing local datasets remain intact and opening is read-only. Shared mode is explicit, never selected solely because the server supports it. No schematic/NBT distribution or schematic writes.
- Total/Missing/Available are per-client Litematica observations. Ordinary Refresh, multiplier, enabled-region selection, Ignore and view filters never rewrite global definitions or denominator. Only deliberate authorized manifest administration changes tracked definitions.
- Commands express Claim, Release, Assign(target), SetDone(boolean), SetNote(text), never whole-row replacement or authoritative ToggleDone. Operation actor comes only from Minecraft connection, not payload. Recheck latest authority/ownership/expected versions inside serial update queue.
- Distinct projectRevision, rowVersion, manifestVersion and operationId. Persist immutable state and required operation result data atomically before publishing success. Failure does not optimistically update UI. ACK does not advance contiguous delta cursor; row adoption requires newer rowVersion. Missing/incompatible/invalidated state blocks shared writes pending snapshot.
- Privacy: required identity/note persistence is product scope and tested. No default private note/inventory/account/UUID/path/address logging, telemetry, external services or skin lookups. Only configured world/config roots plus generated validated IDs produce file names; display names/client paths never do.
- Strict UTF-8 note maximum4096 bytes. Bound decode/reassembly before allocation, total bytes/counts/fragments/deadlines/incomplete transfers/queues. Proposed numerical capacities must be explicit, tested and documented as limits rather than measured performance guarantees.
- Preserve future-schema safety, corrupt/nonblank behavior, CAS, previous-good backup, controlled paths, atomic flush/replacement, background I/O and shutdown drain. Do not duplicate or relabel client localPlacement datasets as server authority.
- Java25 configured Gradle/JDK, no preview/reflection/unsafe/broad unchecked casts/warning suppression. Retain all previous tests and application-JAR/isolation gates. No ignored/local-tool/cache files staged, no public-history rewrite or unrelated discard.
- One Issue/branch/PR. Implementers commit only scoped work after exact root/origin/branch guard; root owns push/PR/hosted CI/merge. No implementer branch mutations, push, PR, merge or nested agents. TEMP scratch/report ruling replaces ignored skill workspace; tracked plan is recovery ledger.

## Integration decisions
Use separate shared models and store/service; reuse pure task transitions where their preconditions are explicitly enforced, without modifying local dataset semantics. Adopted minimal membership foundation: projects private by default, creator-administrator mutations and explicit durable read-only viewer grants/removal. Issue6 adds member editing, role administration and the full matrix. This resolution is recorded below; creator-only readership cannot meet Issue5 distinct-client discovery/join. Expose supported/unsupported/loading/unsynchronized/failure reasons honestly. Wire Hello/Capabilities, ListProjects/JoinProject, ProjectSnapshot, TaskCommand, CommandResult, ProjectDelta, ProjectInvalidated and content-free AccessRevoked through actual Fabric transport. Define how foundation revocation/invalidation messages are emitted and tested without presenting unfinished permissions as complete.

The restored design binds durable operation identity/result storage; create that save-unit boundary now even if Issue7 expands retention/replay/reconnect behavior. Do not leave an unsafe whole-row overwrite or success-before-save API for later work. Choose explicit bounded fragment envelopes if native registerLarge cannot establish the specified fragment/time/concurrency limits. Snapshot revision is fixed; finite deltas buffered during transfer, overflow/gap requires resync.

Client sharing entrypoint and backend/binding boundary must be concrete and usable, rather than test-only networking or fake buttons. Keep existing local work and personal Litematica controls. Capture schematic content digest from actual persistent source through controlled read-only streaming, not a hash of counts/display names; nonpersistent/missing source needs explicit unsupported reason. Match baseline dimension/origin/rotation/mirror and original definitions before shared editing; deliberate admin updates preserve only exact mappings and archive ambiguous old work.

## Task 1: Core project, protocol and durable authority
Producer contract to establish in this task: immutable scoped IDs/manifests/project snapshots; bounded versioned protocol messages/codec; explicit command outcome/events; async lifecycle-owned authoritative service and controlled world/project store. Task2 consumes this contract without game classes in core.

Write meaningful RED tests for separate material/region identity/progress, creator-only mutation and distinct granted-viewer/private-project isolation, expected row/manifest versions, explicit commands, invalid inputs/UTF-8 limits, unrelated-row updates, save failure/no success, restart/current-state equality, corrupt/future-schema/backup/CAS/path cases, and bounded malformed decode/reassembly. Implement minimum clear models/service/store, retaining the shared state+operation-result atomic unit. Acknowledgements and deltas are distinct outcomes. Include deliberately different client observations in boundary tests without making observations authoritative. Test ID stability and conservative exact region reconciliation; no guessed old-state transfer.

Document the actual producer API and binary/message fields for subsequent tasks. Run configured targeted core tests, then covering core/build/isolation. Commit only this coherent core foundation and tests/ledger; do not push. Root task review must approve before Task2.

## Task 2: Actual Fabric server/client transport
Consume Task1's precise API; register actual payload codecs on both directions before handlers, derive actor from context.player and use lifecycle-owned server world service. Server-thread receivers enqueue immutable work; background completion schedules server publication and rechecks current recipients' access, not captured stale lists. Do not block ticks on disk or expose old private rows after removal. Define loading/backpressure/corruption/unsupported outcomes and prevent incompatible-peer sends.

Bind actual client capabilities/discovery/join/snapshot/delta/result/invalidation/access-removal state on render-thread callbacks. Bound transfer/work queues and protect incomplete transfer memory/time. Register only common-safe classes from common entrypoint. Add real Fabric codec/registration and basic packet roundtrip coverage with a legitimate test context, plus isolated dedicated-server boot/class-load/JAR audits. In-process dedicated testing alone cannot prove AC17 isolation; retain external server isolation and defer full two-real-client proof to Issue8 explicitly.

Run covering transport/basic client/server tasks and regressions once. Commit scoped changes/tests/report. Root reviews before Task3.

## Task 3: Explicit shared UI and independent bindings
Consume Task1/2 contracts through a narrow client backend instead of broadly rewriting durable local screens. Add a real shared-work entrypoint for explicit create/discover/join and independent material/region selection. Persist bindings/caches only under controlled client config roots with schema/atomic safety. Keep a future single-save-unit region detach/copy boundary for Issue11; do not implement Unshare here.

Real-client RED/GREEN must verify supported capability, actual discovery/join/snapshot, explicit intent commands, pending/confirmed/errors, delta refresh and invalidation/access-removal presentation, local fallback on unavailable server and preservation of local datasets. Compare stable IDs, unchanged schematic bytes, independent bindings and local quantity observations under personal controls. No invented live Microsoft authentication or external schematic delivery claim. New shared projects must clearly communicate initial work state and preserve existing local work; do not silently import arbitrary whole rows as server authority.

Add Phase3 report/usage and verifyPhase3 composing every prior gate. Scope later permission/fault/two-client results as pending. Run targeted coverage then configured full clean verification, inspect actual XML/results/images/JAR/class-load/diff and document observed limits. Root owns task and final whole-branch review plus hosted Green merge.

## Prepared preflight
| Producer | Consumer | Contract/risk to settle before dispatch |
|---|---|---|
| Task1 immutable service/protocol/store | Task2 thin Fabric handlers | Exact public types/fields, no actor field, save-before-publication, current access check and bounded serialization. |
| Task2 capabilities/snapshot/results | Task3 shared sessions/UI | Render-thread confirmed publication, cursor separate from ACK, explicit status/bindings and no premature permissions/reconnect claim. |
| Task3 per-client observations/placement identity | Task1 shared definitions | Counts and filters remain local; only explicit administration changes shared manifest. Original geometry/digest/baseline mismatch blocks commands. |
| Shared binding save unit | Later Issue11 | Independent region/material refs, confirmed local copy and atomic region detach must be possible without unrelated multiwrite. |


## Execution ledger

Ruling: Use TEMP task briefs, reports and immutable review packages with this tracked plan as the recovery ledger — user instructions prohibit touching ignored local tool workspaces — recovery depends on the ledger and retained TEMP reports rather than a skill workspace.

Ruling: Include explicit durable creator-admin grants and removal of read-only viewers in Issue #5, with creator-only mutations — design sections 9.1, 10.2 and 11 plus Issue #5 discovery/join/delta criteria require a distinct authorized participant; projects remain private by default — this brings minimal membership plumbing into #5; if the interpretation is wrong, #6 may need to revise that foundation. Full member editing and role administration remain #6.

Task 1: implemented, review pending. Task 2: pending. Task 3: pending. Hosted CI, final review, merge and release readiness remain unverified.

Issue #4 completed feature branches were retained because ordinary git branch -d rejected the squash ancestry; no force deletion was used. This does not block the new branch.

Controller-observed Task 1 validation (2026-10-04 UTC): the child escalated Gradle approval waited 889.6s without executing and was interrupted for status recovery. The sandbox getsockopt failure and aborted approval are infrastructure, not TDD RED. Root then used the normal approval gate (no permission bypass): SharedAuthorityTest actual RED exit 1 in 11s, Java compilation passed, 5 tests / 4 expected create-stub failures; implementation GREEN exit 0 in 4s, actual XML 5 tests / 0 failures/errors/skips. A later capacity RED for allFourThousandRowsAtLegalStringLimitsFitSnapshotAndFragmentBounds exited 1 in 2s, actual failure Shared byte capacity exceeded, proving the initial 24 MiB cap insufficient for legal maximum row strings. Coherent capacity adjustment and expanded tests remain worker-owned/in progress. This establishes encoding capacity only, not performance. Root executes requested validation/Git bookkeeping through normal gates while the implementer owns source; no duplicate runs are launched.

Task 1 implementation evidence (2026-10-05 JST): added the immutable scoped shared domain, creator administration with durable Viewer grants, explicit intent/result/event boundaries, lifecycle-owned serialized authority, deterministic versioned binary protocol and bounded reassembly, and controlled JSON-envelope project persistence with atomic state/result storage, CAS, previous-good backups, and explicit recovery. Existing local datasets/transitions and Fabric sources remain unchanged. Producer API, full wire fields, storage schema, limits, and consumer obligations are documented in `docs/SHARED_PROTOCOL.md`.

Capacity GREEN: the legal 4000-row maximal-string fixture encodes to 25,992,249 bytes; adopted message limit is 32 MiB with at most 547 fragments of 60 KiB and two incomplete transfers per receiver. A 32 MiB aggregate admitted-request budget complements the 256-waiting-operation queue. These are admission/encoding limits, not measured throughput or real-client capacity. Expanded shared GREEN passed 28 tests, followed by one new request-budget test included in the covering gate.

Covering command: configured Java 25 `gradlew.bat :core:test build :fabric:verifyIsolation --console=plain` exited 0, BUILD SUCCESSFUL in 13s, 13 tasks (9 executed, 4 up-to-date). Actual core XML: 10 suites, 64 tests (29 new shared tests plus 35 prior regressions), zero failures/errors/skips; the Fabric test task has no sources. Application archive audit found 61 outer entries and 63 nested shared core class entries, with no test/gametest/serverSmoke/test-mixin/screenshot/log/test-language-resource leakage. `git diff --check` passed. Existing Gradle deprecation warnings remain. This core-only gate did not launch game processes; real registered Fabric fragment transport, dedicated-server/two-client synchronization, and UI behavior remain Task 2/3 and later integration evidence.

Implementer self-review completed against own scoped diff and API documentation. Independent controller-supplied review must approve this foundation before Task 2 begins. No push, PR, merge, remote change, or branch mutation was performed by the implementer.
