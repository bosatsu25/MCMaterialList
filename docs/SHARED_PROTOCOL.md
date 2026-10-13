# Shared authority producer contract (Issue #5, Task 1)

This document describes the Minecraft-free producer in `core.shared`, `core.shared.protocol`, and `core.shared.persistence`. It is not evidence of Fabric integration, two real clients, reconnect acceptance, or complete Member permissions. Task 2 must provide authenticated transport/lifecycle wiring; Task 3 must provide explicit sharing and durable independent client bindings.

## Identity and definitions

`ProjectScope(worldNamespace, projectId, materialListId, regionListId)` is immutable. The store generates and persists the world namespace. `SharedProjectService.create` generates the project and both distinct list UUIDs. Every subsequent scoped operation must match all four fields. Display names, filenames, translated labels, row positions, counts, and digests do not identify work. `localPlacementId` belongs exclusively to the client binding; it is never a server authority identifier.

`SharedManifest.Definition` contains a lowercase 64-character SHA-256 schematic digest, a `Baseline` (dimension registry key, integer origin, rotation, mirror), submitted material registry keys with nonnegative 64-bit definition counts, and exact original `RegionDescriptor` values (original key, integer relative origin, signed size). Zero and minimum-integer size axes are invalid. Material identity rule 1 reuses `MaterialTaskId` and is scoped by the project's material-list ID. A `RegionTaskId` is a generated server UUID mapped to an exact descriptor. Duplicate exact descriptors are rejected; descriptors with a similar name or changed origin/size do not match.

Create initializes every submitted tracked row with empty work. It does not accept local task states. Material and region progress are independent counts of done tracked rows; the percentage is floor(100 × completed / tracked), or zero for an empty list. `ProjectSnapshot` exposes immutable definitions, rows, permissions, revision, and reconfirmation markers. Client Total/Missing/Available, multiplier, enabled regions, Ignore, sorting, and filters have no authoritative mutation API. Definitions change only through deliberate creator administration.

`updateManifest` preserves material rows with the same registry key and region rows with the exact descriptor. Removed rows and their original material count or region mapping are retained in the atomic archive, never guessed onto new rows. An exact descriptor removed and later reintroduced gets a new region UUID. A completed row whose material definition count, digest, or baseline changes remains done and gains `needsReconfirmation`. Explicit successful `SetDone` clears that row's marker, increments its row version even when done already equals the requested value, and publishes the marker in its delta.

A removed and reintroduced material retains its registry-key/list identity but starts with empty assignment, completion and note. Its rowVersion starts one greater than the greatest retired version for that material in the same project archive. The retained rows therefore provide a durable version watermark across repeated removals and restart, without transferring retired progress. A maximum-long watermark rejects reintroduction with CAPACITY rather than wrap or reset. Original retained ACKs remain unchanged on replay and have lower row versions than the fresh row.

## Service lifecycle and methods

Construct `SharedProjectService(new SharedProjectStore(configuredWorldRoot))` once per server-world lifecycle. The constructor queues startup I/O and returns; `ready()` completes with `Ready(StoreStatus, worldNamespace)`. Do not join this future on the Minecraft thread. Startup loads every identified project into a temporary map and publishes only after all are valid. Corruption, missing-primary-with-backup, future schemas, or unsafe paths leave the service unavailable rather than synthesizing empty work. `storageStatus()` is a content-free diagnostic for the latest store action.

All asynchronous methods return `CompletionStage`; their accepted operations run in one bounded background queue. This serializes all projects (a conservative initial implementation). Every scoped command/join/admin action reads the latest durable archive within that queue before checking access and expected versions. State publication occurs only after a confirmed atomic save. Completion callbacks run on the completing background thread; the transport must schedule Minecraft work onto its server thread.

| Producer method | Parameters after trusted actor UUID | Result |
|---|---|---|
| `create` | expected world UUID, display name, submitted `Definition` | `Reply<ProjectSnapshot>`; fresh project/list/region IDs and empty work |
| `discover` | expected world UUID | `Reply<List<ProjectSummary>>`; only permitted projects |
| `join` | full `ProjectScope` | fixed-revision immutable `Reply<ProjectSnapshot>` |
| `command` | `SharedCommand` | `Outcome(CommandResult, List<Event>)` |
| `setViewer` | scope, expected project revision, target UUID, granted boolean | durable grant/removal, invalidation, and content-free removal event |
| `updateManifest` | scope, expected project revision, expected manifest version, submitted definition | durable reconciliation and invalidation |

The actor comes only from the authenticated Minecraft connection, never a request payload. Projects are private initially. The creator is the sole administrator; explicit granted participants are read-only Viewers. Only the creator mutates rows, manifests, and grants. Full Member edits and role reassignment are Issue #6. The creator cannot be removed through viewer administration. Assignment targets must currently be the creator or a granted viewer. Historical assignment/completion metadata is retained when a viewer loses access.

`SharedCommand(scope, taskId, operationId, expectedRowVersion, expectedManifestVersion, intent)` has six typed intents: `Claim`, `Release`, `Assign(target)`, `ClearAssignment`, `SetDone(boolean)`, and `SetNote(text)`. Claim fails if another participant is assigned. Release retains the existing local rule: it cannot clear somebody else's assignment. Creator administration uses the distinct `ClearAssignment` intent. Null Assign is invalid. SetDone(true) assigns the actor only when unassigned, preserves another assignee, and records the actual actor/time; SetDone(false) clears completion metadata without changing assignment/note. There is no ToggleDone or whole-row write API.

Authority is checked before durable operation lookup. Replay is keyed by project + trusted actor + operation ID; identical retained content returns the original result with no new delta. Reusing an ID with different command content fails. Both expected row and manifest versions are checked after lookup; an unrelated row update does not cause a conflict. Authorized outcomes (including conflicts and invalid target/missing-row outcomes) are saved with the state; a storage failure returns SAVE_FAILED with no row/events. Retention is at most 24 hours and 4096 results per project, pruned on the next write; expiry is inclusive at 24 hours. After eviction/expiry the expected-version checks still apply. Ordinary successful no-ops persist their result/generation but do not advance projectRevision or emit a delta.

The three revisions have separate meanings: store generation advances on every saved atomic unit, projectRevision advances for observable state/manifest/membership changes, and rowVersion advances only for that row. Manifest version advances only for a changed submitted definition. Admin calls use expected project revision, so replay after an admin response is lost must re-read a snapshot. Request IDs on create/join/admin wire messages correlate responses and are not durable creation idempotency keys: never automatically resend CreateProject after an uncertain response; discover and ask the user to select existing work first.

`Reply` and `CommandResult` statuses are `OK`, `DENIED`, `MISSING`, `CONFLICT`, `INVALID_INPUT`, `SAVE_FAILED`, `UNAVAILABLE`, `CAPACITY`, and `CLOSED`. Denial and failures do not carry private rows. `Outcome.events` is separate from its acknowledgement: changed row => `ProjectDelta`; definition or membership change => `ProjectInvalidated`; viewer removal also => `AccessRevoked(scope, recipient)` internally. A snapshot reply carries content only for OK. Creator admin responses return the post-save snapshot.

`shutdown()` immediately rejects new work with CLOSED and returns a future that drains accepted work. `close()` waits for that drain and must run off tick/render threads. Pending sends are disabled during shutdown. Do not drop the lifecycle-owned service while accepted writes are still running.

## Publication ordering and consumer obligations

`committedSnapshot(actor, scope)` is a thread-safe, immutable, no-I/O view of currently published authority. It is useful for a local check, but capturing it does **not** authorize a later send.

For every content-bearing send, including every snapshot fragment and delayed ACK/delta/admin response, call `publishIfAuthorized(actor, scope, sendCallback)` **on the server thread immediately around the transport send/enqueue**. It checks the latest committed membership and all scope IDs, then executes the callback while holding the same short monitor used to publish saved authority. If revocation publication won that monitor first, the callback is not invoked; if the callback won, its send is ordered before revocation publication. This is the in-process publication linearization point after disk save. The callback must perform only a short transport send/enqueue; do not do disk I/O, block, await a service future, or call a mutation synchronously inside it. Callback exceptions propagate to the caller and release the monitor; they do not roll back an already saved operation or bypass access checks.

Discovery also contains private names: generate and send it using `publishDiscovery(actor, callback)`, which supplies the current authorized summaries under that monitor. Do not send a captured discovery result later without this gate. Deliver internal AccessRevoked only to its specified recipient; its wire form contains only scope. Stop subscriptions and discard incomplete content transfers on revocation, then use the same gate for any content already scheduled. One live service owns connected-client publication; file locks/CAS protect cooperating maintenance writers, but the in-memory publication monitor does not coordinate independent server processes. External administration should use the owning service or stop it before offline recovery.

Clients advance their contiguous delta cursor only from a fixed-revision snapshot or the next consecutive delta. ACK revisions do not advance it; ACK row adoption requires the same complete scope, a row currently present in the installed manifest, and a strictly newer rowVersion. An ACK never recreates a removed row. The material version watermark also prevents an old ACK from replacing empty work after that material is reintroduced. Still complete pending-operation bookkeeping for an ACK when its row was already received through a delta. Invalidation, a gap, unsupported protocol, missing authority, transfer failure/expiry, or changed baseline/digest disables shared writes pending a fresh snapshot. Transport must bound deltas buffered during transfer and abandon/resnapshot on overflow. These consumer rules are documented contracts, not a claim of implemented client session handling in Task 1.

## Version 1 binary format

`SharedProtocol.encode/decode` uses explicit `DataOutputStream`/`DataInputStream` fields in big-endian order. No reflection or Java object serialization is used. Every message begins with `int32 protocolVersion=1`, then `uint8 tag`. Unknown versions/tags, malformed UTF-8, invalid enum/boolean values, invalid IDs/counts, truncation, and trailing bytes are rejected with content-free exceptions. Strings are `int32 UTF8ByteLength` followed by strictly valid UTF-8. UUIDs are most-significant int64 then least-significant int64. Booleans are exactly 0 or 1. All collections begin with bounded int32 counts unless otherwise specified.

Scope is four UUIDs in world/project/material-list/region-list order. Task ID is uint8 kind (0 material, 1 region) followed by registry string (256-byte maximum) or UUID. Vector3 is three signed int32 values. Instant is signed int64 epoch seconds plus int32 nanos in [0, 999999999]. Optional fields start with a boolean.

| Tag / record | Fields after header, in order |
|---|---|
| 0 Hello | int32 supported protocol version |
| 1 Capabilities | world UUID; int32 protocol, material-row limit, region-row limit, participant limit, message-byte limit, fragment-byte limit |
| 2 ListProjects | request UUID; world UUID |
| 3 Projects | request UUID; uint8 status; count; repeated scope + name |
| 4 JoinProject | request UUID; scope |
| 5 CreateProject | request UUID; world UUID; name; definition |
| 6 UpdateManifest | request UUID; scope; int64 expected project revision; int64 expected manifest version; definition |
| 7 SetViewer | request UUID; scope; int64 expected project revision; target UUID; granted boolean |
| 8 Snapshot | request UUID; uint8 status; full snapshot only when OK |
| 9 TaskCommand | scope; task ID; operation UUID; int64 expected row version; int64 expected manifest version; intent |
| 10 CommandResult | scope; operation UUID; uint8 status; int64 project revision; optional row |
| 11 ProjectDelta | scope; int64 project revision; int64 manifest version; row; needs-reconfirmation boolean |
| 12 ProjectInvalidated | scope; int64 project revision; int64 manifest version |
| 13 AccessRevoked | scope only |

Intent tags: 0 Claim, 1 Release, 2 Assign + target UUID, 3 ClearAssignment, 4 SetDone + boolean, 5 SetNote + UTF-8 note (4096 bytes). Status tags follow the enum order listed above, starting at 0. Rotation tags are NONE=0, CLOCKWISE_90=1, CLOCKWISE_180=2, COUNTERCLOCKWISE_90=3; mirror tags are NONE=0, LEFT_RIGHT=1, FRONT_BACK=2. Changing these orders or fields requires a protocol/schema version change.

Definition: digest string (64 bytes), dimension registry string (256 bytes), origin Vector3, rotation uint8, mirror uint8; bounded material count, each registry string + int64 submitted count in sorted registry-key order; bounded region count, each original-key string (4096 bytes) + relative-origin Vector3 + signed-size Vector3 in submitted order. Manifest: int64 manifest version, definition, then one assigned region UUID per definition region in that same order (no second count).

Row: task ID, optional assignee UUID, done boolean, note string; if done, completed-by UUID + Instant; int64 row version. Snapshot: scope, name (256 bytes), creator UUID, count + sorted viewer UUIDs, int64 project revision, manifest, count + rows (material keys first, then region UUID strings, each sorted), each row followed by its reconfirmation boolean. Definitions and row ID sets must match exactly.

`SharedTransfers` wraps messages into bounded frames for Fabric. Frame fields: int32 protocol version, transfer UUID, int32 zero-based index, int32 fragment count, int32 total message bytes, int32 fragment byte count, bytes. The fixed overhead is 36 bytes. All except the last fragment have exactly 60 KiB payload. Fragment count must equal ceil(total/60 KiB); the last payload must exactly fill the declared total. `Fragment` copies input/output arrays. `decode` bounds frames before allocating payload. Use one receiver instance per authenticated connection; call `accept(fragment, monotonicMillis)` and periodically `expire(monotonicMillis)`, clearing it on disconnect/invalidation. Fragment order may vary. Duplicate or inconsistent fragments discard the transfer. Deadlines are fixed from its first accepted fragment, never refreshed. A fragment arriving after expiry can start another incomplete transfer; it cannot resurrect already discarded bytes or complete the old transfer. Rollback of the supplied clock clears transfers and fails.

The 60 KiB fragment is an application limit. Actual registered Fabric serverbound/clientbound near-boundary roundtrips remain Task 2 verification; this core test does not establish native transport acceptance. Keep these application limits/deadlines even if native large-payload support is used.

## Admission limits

These are enforced limits, not measured server performance guarantees:

| Resource | Limit |
|---|---:|
| Authoritative project identities per owned world | 128, including damaged/unpublished projects and identities retained only by backup |
| Current material rows / current region rows | 2000 each |
| Participants including creator | 20 |
| Notes / original region keys | 4096 strict UTF-8 bytes each |
| Project names / registry keys | 256 UTF-8 bytes each (registry syntax additionally constrained) |
| Serialized message | 32 MiB |
| Fragment payload / frame | 61,440 / 61,476 bytes |
| Fragments per transfer | 547 |
| Concurrent incomplete transfers per receiver | 2 (at most 64 MiB aggregate payload) |
| Reassembly lifetime | 30,000 monotonic milliseconds |
| Queued service work | 256 waiting operations plus the active worker |
| Aggregate admitted service request budget | 32 MiB, including active work; conservative serialized-input reservation, not Java heap size |
| Durable operation results | 4096 and younger than 24 hours |
| Retired rows | 4000; definition updates fail at capacity rather than discard history |
| Binary atomic archive | 96 MiB |
| JSON envelope | 128 MiB + 256 bytes |

The maximal-row encoding fixture uses all 4000 rows, 4096-byte notes, 4096-byte region keys, maximum material registry-key lengths, and completion/assignee metadata. It encoded to **25,992,249 bytes** in the Task 1 test, exceeding the rejected initial 24 MiB proposal and fitting the adopted 32 MiB limit. It proves encoding admission, not network latency, tick cost, memory use of a full server, or throughput. The service reserves 512 + 268 × material rows + 4124 × region rows for create/definition requests, 512 + note UTF-8 bytes for commands, and 256 bytes for other work, releasing reservations when execution finishes or queue admission fails. Reassembly temporarily also holds the completed output buffer; callers must release consumed input/result buffers. Task 2 must additionally limit active authenticated connections, per-connection request rate, outbound transfers, outstanding responses, and snapshot-delta buffers before enqueue/allocation.

## Atomic store and recovery

Only the host-supplied configured world root is accepted as a path. Under it the store uses `mcmateriallist-shared/world.json` and generated `project-<UUID>.json`; `.bak`, `.lock`, generated `.tmp`, and generated `.retained` files never derive from a display name or client path. Every ancestor/target is checked for ordinary directory/file type, symlinks, junction-like/reparse targets, and real-path mismatch. NOFOLLOW_LINKS is used for opens where supported. This is not a hostile-filesystem-race guarantee.

JSON schema 1 is a strict flat envelope (the writer uses this order; readers accept any member order): `{"schema":1,"kind":"world|project","payload":"<base64>"}`. World payload is its 16-byte UUID. Project payload is the deterministic explicit binary atomic archive: int64 generation; snapshot; operation count; each actor UUID + command + original CommandResult + recorded Instant; retired count; each int64 previous manifest version + row + original RegionDescriptor for a region, or int64 submitted definition count for a material. Schema evolution must version this entire durable encoding; future schemas are never read as current or overwritten. The flat streaming JSON parser rejects nested current payloads without recursively constructing a JSON tree. A common bounded header classifier detects integral future numeric versions, including decimal/exponent spellings and values outside integer range, before current payload decoding; it never expands huge exponent notation. Duplicate/malformed/current unknown fields remain refused. Future primary and backup bytes survive load, save refusal and explicit recovery unchanged.

The store takes a cooperating exclusive file lock, reloads the current archive, checks generation and full project/list scope, encodes and round-trips the new immutable unit, flushes a same-directory temporary file with `FileChannel.force(true)`, preserves the previous valid primary as backup by atomic replacement, and atomically replaces the primary. Failure to atomically replace fails closed; no unsafe rename fallback. Existing invalid/future backup files are not silently overwritten. The previous valid primary remains authoritative if replacement fails. Operation results and row changes are never separate writes.

`load` reports typed `StoreStatus` without paths/data. Missing primary plus valid backup is RECOVERY_REQUIRED, not MISSING. Corrupt primary plus valid backup also requires explicit recovery; corrupt/future data never becomes blank work. `recover(world, project)` and `recoverWorld()` are synchronous administrative/offline methods: after an administrator confirms, they retain damaged primary bytes under a generated name, force them, and restore a validated known-schema backup. Reopen the service after recovery. Normal service startup and reads never invoke recovery. A missing world namespace while project files remain is corrupt, preventing a new namespace from relabelling old projects.

The guarantee is save-before-publication under this flush/atomic-replace scheme, plus restart/CAS/backup behavior tested here. It is not an absolute power-loss guarantee across all operating systems/filesystems. No identities, notes, file paths, account contents, or private project contents are logged by the producer.

The service keeps durable project identity reservations separately from publishable snapshots. Startup reserves every identity inventoried from primary or backup files; successfully committed projects also reserve their identity. A later unavailable/corrupt/recovery-required read removes publication, never the reservation. There is no deletion API in this foundation, so a project slot is never automatically released. This prevents admitting a 129th identity while retaining a damaged project's recovery files; explicit offline recovery and restart retain the same admission count.
