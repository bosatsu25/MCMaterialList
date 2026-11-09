# Phase 5A — Performance, limits and observability (Issue #12)

This document records **measured observations** for the design §13 initial load profile: one project with **2000 material rows, 2000 regions and 20 participants**. It also covers the limits that bound excessive work and the operation traces added for diagnosis.

The numbers are observations from one environment. They are not targets and not capacity guarantees. They depend heavily on CPU, JIT warm-up, GC and, above all, disk `fsync` latency. Hosted CI runners will produce different values. Tests assert only hard bounds: protocol and storage limits, consistency, no corruption, and bounded refusal. Timings are checked only against generous ceilings, such as an ordinary command finishing in under 60 s.

## How to reproduce

- `./gradlew :core:test` runs the measurement tests. Every measurement line starts with `PERF5A `. The core test task echoes these lines to the console, and they also stay in `core/build/test-results/test/*.xml`.
  - `core/src/test/java/dev/mcmateriallist/core/shared/Phase5ASharedLoadTest.java`: payloads, authority queue, overload, full retention.
  - `core/src/test/java/dev/mcmateriallist/core/shared/client/Phase5ALocalStoreLoadTest.java`: `regions.json`, detached copy, task/byte headroom.
  - `core/src/test/java/dev/mcmateriallist/core/stock/Phase5AStockBudgetTest.java`: scan tick budget, admission for 20 players.
  - `core/src/test/java/dev/mcmateriallist/core/ui/RegionRowIndexTest.java`: region row resolution at 2000 regions.
  - `core/src/test/java/dev/mcmateriallist/core/shared/Phase5AProfile.java`: the profile. It uses registry-id-like material ids, `Region NNNN` names and 20 members (1 administrator, 17 members, 2 viewers). Rows are about 30% done, 20% assigned and 10% carry a short Japanese/English note. The legal maxima are covered separately by `SharedProtocolTest`.
- `./gradlew verifyPhase4 -PacceptMinecraftEula=true` now includes `:fabric:verifyPerformance`. After the two-client dedicated-server run, this task prints `PERF5A hosted-server-traces …` and `PERF5A hosted-server-stock-scans …` from the server console log (see "Observability" below).

## Measurement environment

All core values below come from the following environment:

| | |
|---|---|
| CPU | Intel Xeon @ 2.10 GHz, 4 vCPUs (shared cloud VM) |
| Memory | 15 GiB host, JVM max heap 3420 MiB |
| JVM | OpenJDK 25.0.4.1, HotSpot 64-Bit Server VM, Linux amd64 |
| Disk | Container temp directory; every durable replace calls `FileChannel.force(true)` |
| Runner | JUnit Platform launcher directly (Gradle could not run in this sandbox), all 242 core tests in one JVM |

The tests ran three times. Where values differed noticeably between runs, the table gives the range. The Fabric/Minecraft side could not be built or run here, so no in-game timing is claimed. Hosted CI records only the server-log observations listed under "Observability".

## Results

### Shared protocol payloads (initial profile)

| Message | Size | Fragments (60 KiB) | Encode / decode |
|---|---|---|---|
| Full snapshot (2000 + 2000 rows, 20 members) | 410,703 B (401 KiB) | 7 | p50 6.4–7.2 ms / 7.3–10.7 ms |
| CreateProject request (definitions only) | 155,425 B | 3 | – |
| TaskCommand SetDone | 120 B | 1 | round trip p50 0.002 ms |
| TaskCommand SetNote (4096-byte note) | 4,219 B | 1 | round trip p50 0.04 ms |
| CommandResult / ProjectDelta with a 4096-byte note | 4,266 / 4,257 B | 1 | round trip p50 0.04 ms |
| ProjectInvalidated | 85 B | 1 | – |

- **Hard bounds asserted.** The snapshot is at most `MESSAGE_BYTES` (32 MiB), which leaves about 82× headroom. It stays within `FRAGMENTS`. Every ordinary operation message fits a single fragment, even with a maximal note: a click sends one row, never the list. The legal-maximum snapshot remains the existing 25,992,266 B fixture.
- **Join storm model (not a measurement).** The server sends 4 frames per tick in total. If all 20 participants join at once, that is 20 × 7 = 140 frames, at least 35 ticks (1.75 s at 20 TPS), and 7.8 MiB outbound. A legal-maximum 26 MB snapshot needs 434 frames, about 5.4 s for a single client. Because the outbound budget reserves twice the message bytes, at most two such snapshots can be queued at once. Further joins get a bounded `CAPACITY` failure and retry, so no unbounded queue builds up.

### Shared authority (disk-backed, one serialized worker)

| Scenario | Observation |
|---|---|
| Create project (profile) | 228–308 ms; project file 449 KiB |
| 19 membership changes | p50 109–161 ms each |
| Average: 120 sequential commands (SetDone / Claim / note) | p50 **75–123 ms**, p95 112–167 ms; file grows to 502 KiB |
| Burst: 18 writers × 10 completions + 18-way claim race (198 commands at once) | drain **14–23 s**; max queue wait 14–23 s; execution per command p50 73–121 ms; 181 OK, 17 lost claim race (members get `DENIED` once the row is assigned); no refusal |
| Join storm: 20 joins at once | p50 149–261 ms, max 280–496 ms |
| Overload: worker held, 300 more commands | exactly 256 (`QUEUED_WORK`) queued, 44 refused with `CAPACITY`, returning in p50 0.011–0.021 ms; queue drained in 1.5–2.8 s after release |
| Full retention (4096 operations) | archive 1,621 KiB (400 KiB without operations, limit 96 MiB); file 2,161 KiB; store load p50 29–59 ms; **command p50 210–330 ms** |

Hard bounds asserted:

- After the burst, every accepted completion is stored exactly once (`rowVersion == 1`), and exactly one claim wins.
- After overload, the store loads `OK`, holds exactly 257 operations, and the first note was applied.
- The full-retention archive stays within `ARCHIVE_BYTES`.

**Where the time goes.** Every command reloads and validates the project from disk. It then saves through `SharedProjectStore`, which:

1. re-reads the primary file and the backup;
2. encodes the new archive and verifies the encoding by decoding it;
3. writes the backup and the primary, each fsynced and then re-parsed to verify.

JFR showed archive decoding and JSON header classification of the base64 envelope as the dominant costs (`SharedSchemaHeader` takes about 1.5–4 ms per 600 KB envelope, plus Base64 and decoding).

**Fix made.** `SharedProjectStore` now reuses the archive it already decoded while validating a payload, instead of decoding the same bytes again in `readProject`. This removes 3 of the 9 archive decodes per command, without any format or validation change. The average command p50 dropped from about 123 ms to about 100 ms on the same run conditions.

**Deferred.** Further cuts would change the durability design. Options include keeping the committed archive in memory (with a file-stamp check instead of a reload per command), dropping a verification pass, or moving to an incremental operation log. At about 100 ms per command, the queue serves about 10 commands/s at the initial profile. At full retention it serves about 3–5 commands/s. Sustained activity by 20 participants at one click every 3 s (6.7/s) fits below full retention and builds a queue at full retention.

A queue does not hang the game: the server tick never waits, clients show their pending (saving) state, and the queue is bounded at 256 operations and 32 MiB with immediate `CAPACITY` refusal beyond that. This is recorded as a follow-up, not fixed here.

### Operation retention: 24 h / 4096 results (PR #26)

- The retention window in operations shrinks as activity grows. The time window is 4096 ÷ (operations per second). For 20 participants:

  | One operation per participant every | 4096 results cover |
  |---|---|
  | 1 s | 3.4 minutes |
  | 3 s | 10.2 minutes |
  | 10 s | 34 minutes |
  | 30 s | 102 minutes |

  The 24 h cap applies only in quieter projects.
- Retention matters for exact replay. A client re-sends a journaled command whose outcome it never received, for example after a disconnect. Within the window, the client gets the original result.
- **Hard bound asserted.** After eviction (by count here; the 24 h expiry is already covered in `SharedBoundaryTest`), an exact replay is answered with the definitive `CONFLICT`, because the row version has moved on. The command is never applied a second time, and the probe row is unchanged.
- **Assessment.** Retention is sufficient for correctness at 20 participants: state never rolls back or double-applies. The only effect of a long disconnect during heavy activity, roughly more than 10 minutes at one click every 3 s per person, is that the returning client sees `CONFLICT` for its own already-applied command. The client then settles and resynchronizes.
- **Deferred.** Whether to raise 4096 depends on the archive cost above: each retained operation adds about 0.3 KiB to every save.

### Local region store and detached copy (PR #32, `DatasetJsonCodec.MAX_TASKS`)

| Dataset | JSON | Encode / decode | Store save / load |
|---|---|---|---|
| `regions.json`, 2000 active (representative states) | 467 KiB | p50 4.6–7.1 / 18.6 ms | p50 111–159 ms / 16–22 ms |
| `regions.json` at the limit (2000 active + 2000 archived) | 945 KiB | p50 10.8–17.0 / 29.6–41.5 ms | p50 246–254 ms / 30–35 ms |
| Detached copy of the profile (2000 regions) via `SharedClientStore` | 457–459 KiB (about 71× below `MESSAGE_BYTES`) | copy p50 0.7–0.9 ms | p50 127–165 ms / 20–24 ms |

- **History headroom.** With 2000 active regions, 2000 archived definitions remain under `MAX_TASKS`. Every Refresh that changes the definition of *k* regions archives *k* old definitions. That allows about 2000 refreshes at 1 change each, 200 at 10, 20 at 100, or 4 at 500. A detached copy starts with no history but grows the same way through local Refresh.
- **Byte headroom.** A legal worst-case task (4096-byte region key and 4096-byte note) costs 8,384 JSON bytes as plain text. At 4000 tasks that is 33,536,000 B, which just fits the 32 MiB file limit. Fully escaped control characters cost 49,319 B per task, so only 680 such tasks fit.
- **Fix made.** Exceeding the task limit, or the detached-copy and client-state byte limits, used to surface as a generic `IllegalArgumentException` that both stores reported as **`CORRUPT`**. The encoders now throw `CapacityExceededException`, a subtype of `IllegalArgumentException`. `LocalDatasetStore` and `SharedClientStore` map it to the existing **`TOO_LARGE`** status before writing anything:
  - The previous primary file and backup stay byte-identical, and work on the active rows continues.
  - The on-disk format and decode-side validation are unchanged.
  - The UI already shows the status name (region work) or the existing `shared.storage.TOO_LARGE` text (sharing settings).
  - Regression tests: `CapacityRefusalTest`, `DetachedCapacityTest`. Both were RED (`CORRUPT`) before the fix.
- **Deferred.** There is no automatic pruning of archived region history. Pruning changes what the archive screen promises, so it needs a product decision. The encoder also still builds the whole JSON string before the byte check, which is a transient memory spike for pathological escaped data.

### Region rows at 2000 regions (Phase 2 follow-up)

Before this phase, `RegionWorkSession` recaptured all original regions in per-row render paths:

- `id(key)` rebuilt the region descriptor list and rescanned every definition for each row. It runs at row construction and on every Hide Done filter pass.
- Done-button enablement and its hover feedback captured again on every frame through `ready()`.

| Path | Before | After |
|---|---|---|
| Resolve all 2000 row names (core part only) | p50 **1,145–1,352 ms** | p50 **1.9–3.5 ms** (index build 0.8–1.4 ms) |
| Per-frame model: 11 visible rows × 2 captures of 2000 descriptors | about 9–15 ms of core work per frame | 1 capture per client tick (0.4–0.7 ms) |

These are core-side costs only. Litematica's own capture cost (`getAreaPositions`, placement identity) was not measurable here and multiplied by the same factors before the fix.

**Fix made.**

- `core.ui.RegionRowIndex` resolves a row name to its task in O(1) after one pass. It keeps exactly the old rules: a name resolves only when it is unique in the observation, and its exact descriptor maps to exactly one task. Shared manifests use the same rules. A randomized test compares it with verbatim copies of both old rules.
- `RegionWorkSession` reuses one immutable observation for at most one client tick (50 ms) in render paths only. It drops the observation at every screen rebuild (`initGui` HEAD and `toolbar()`), and rebuilds the index when the dataset or shared snapshot changes.
- Fresh command-time validation is unchanged. `ready()`, Refresh, Done/Claim/notes (`currentDefinitions()`), acknowledgement and the detached Refresh still capture fresh definitions. The detached Refresh now refuses an invalid fresh capture instead of dereferencing it.
- `SharedWorkBackend.regionIndex()` gives the shared path the same one-pass index.

### Check Materials tick budget (PR #31)

The default budget is 8 containers per tick, with 1 ms checked *between* containers. The first container of a tick always runs. The probe reads a realistic slot array, so these timings cover the core scan/report path only, not chunk or block-entity access.

| Scenario | Ticks | Per-tick time | Longest single container step | Ticks over 1 ms |
|---|---|---|---|---|
| 4 concurrent scans × 512 barrels (27 slots), cold | 257 | p50 0.09–0.13 ms, max 1.0–2.7 ms | 0.7–2.6 ms | 1–3 |
| Same, warm | 256 | p50 0.06–0.08 ms, max 0.4–0.7 ms | 0.2–0.6 ms | 0 |
| 256 double chests, 54 distinct items each | 128 (scan done in 64–65; report streaming at 4 frames per tick is the limit) | p50 0.12–0.15 ms | 0.2–2.1 ms | 0–1 |

The container count is asserted to be at most 8 in every tick.

**Decision on per-container handling.** Keep a container read atomic, and keep the time check between containers:

- Splitting one container across ticks would allow an inconsistent reading, in particular half a double chest.
- A warm container step costs about 10–70 µs. The overshoots above, and the 4.4 ms seen in PR #31's integrated-server run, come from single cold or GC-affected steps, not from the loop. A tick can exceed the budget by at most about one step.
- To make that attributable on real servers, `StockScheduler.stepStats()` now records the longest single step, the ticks over budget and the busy ticks. The server's `Stock scan …` log line reports them, still without item ids or quantities, and `verifyPerformance` prints their maxima from the hosted dedicated-server run.

**Admission for 20 players** (1 scan per player, 5 s cooldown, 4 concurrent scans per server). The model has all 20 players start at once and click again every second while refused:

| Positions per scan | Refusals | First finished | Last admitted | Last finished |
|---|---|---|---|---|
| 64 | 80 `CAPACITY` | 1.6 s | 8.0 s | 9.6 s |
| 512 (maximum) | 520 `CAPACITY` | 12.8 s | 52 s | 64.8 s |

A second start inside the cooldown is asserted to be `COOLDOWN`, and at most 4 scans ever run at once. The limits hold and bound server work to 8 containers per tick in total. The cost is latency: the 20th player waits about a minute when everyone scans a full 512-container Location File at once. These values are recorded as acceptable for a user-triggered action and are not changed here.

### `OPEN_PLACEMENTS = 16` (`SharedWorkController`)

- The client keeps sharing metadata entries for at most 16 placements.
- When a 17th placement's screen opens, the first *evictable* entry is dropped. An entry is evictable when it has no live screen backend, no save or request in flight, no shared reference, no unresolved journal entry and nothing hidden. The dropped entry reloads from disk, detached copy included, when that placement opens again.
- "No live screen backend" is tracked through weak references, so eviction of recently closed screens depends on garbage collection.
- If no entry is evictable, for example 16 placements that are all shared or still open, the new screen's backend has no entry:
  - It shows `placement_capacity` ("Sharing view limit reached. Restart the client …").
  - Its sharing state is unknown, so local edits of that placement are blocked rather than risk editing an original behind a detached copy.
  - Nothing is lost or overwritten.
- Reopening the screen later retries eviction, and a client restart clears the limit.
- This is a bounded refusal. It is documented here and not changed, because it is Fabric-only and could not be compiled or tested in this environment.

## Observability (design §13)

`SharedProjectService` emits one `SharedDiagnostics.Trace` per executed or refused operation: create, discover, join, command, membership and manifest. The server logs it as one line:

```
MCMaterialList trace op=command project=<uuid> task=<task id> operation=<uuid> intent=SetDone(true) result=OK revision=12 rowVersion=3 manifest=-1 queuedMicros=54 durationMicros=26026
```

- **Privacy by construction.** A trace can only carry these fields. Intents are reduced to their kind (`SetNote`, `Assign`, …), with no note text or length and no assignment target. There are no project or region names, no material quantities and no actor or target player UUIDs. Manifest and create traces carry row counts only. Stock summaries carry counts and timings only.
- **Log levels.** Routine successful commands and discovery are logged at DEBUG. Joins, administration, refusals, failures and slow operations (≥250 ms queue + execution) are logged at INFO. A throwing sink never changes an authoritative result, and the trace is emitted before the reply completes.
- **Privacy assertions on log content.**
  - `SharedDiagnosticsTest` puts a sentinel note body, project name, region key, quantity and every participant UUID through real operations. It asserts that none of them appears in any formatted trace, and that every correlation field is present.
  - `:fabric:verifyPerformance` fails if any multiplayer fixture note body (`beta note`, `while beta offline`, `after revocation`) appears anywhere in the dedicated-server log, or if a `Stock scan` summary contains an item id. It also requires a successful join trace with every correlation field.

## Acceptance status

| Criterion | Status |
|---|---|
| Representative load tests record real response, tick, storage and payload measurements for 2000 + 2000 + 20 | Done for the core paths (above). In-game frame and tick timing on Minecraft is not measured here. Hosted CI adds dedicated-server trace and scan maxima through `verifyPerformance`. |
| Limits reject or bound excessive work without corruption or client hangs | Done. Queue overload is an immediate `CAPACITY` with a consistent store. Over-limit history or detached copies are now `TOO_LARGE`, not `CORRUPT`. Stock scans are bounded per tick and per server. The region list no longer does O(rows × regions) work per rebuild. |
| Diagnostics correlate operations and exclude note bodies and large inventories by default, with privacy assertions | Done (core tests and the hosted log gate). |
| Stock scans respect measured tick and work budgets | Done. At most 8 containers per tick is asserted, the overshoot is attributed to single atomic steps, and step statistics are logged on servers. |

Remaining follow-ups (not regressions):

- Authority save cost per command (in-memory committed archive or incremental log).
- Pruning policy for archived region history.
- Exhaustion of `OPEN_PLACEMENTS` with many shared placements.
- An in-game measurement of region screens with a real 2000-region schematic.
