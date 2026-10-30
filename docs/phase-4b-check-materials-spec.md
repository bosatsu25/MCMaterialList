# Phase 4B Check Materials: implementation spec (Issue #10)

Working spec for the implementation. Once it is done, `docs/phase-4b-check-materials.md` replaces it.

## Owner decisions (2026-10-30)

1. **Authorization: server admins plus a grant registry.**
   - A server admin (an operator, or the owner of a singleplayer or LAN world) may read any position they request. They can already read container contents with `/data get block`, so this exposes nothing new.
   - Everyone else may read only positions that a person who is both a server admin and the project's Administrator has granted to that project.
2. **Minimum project role for granted reads: MEMBER.** A Viewer gets nothing.
3. **What is counted.**
   - Hoppers, droppers and dispensers count separately, as "in machines". They are never added to storage stock.
   - Items inside shulker boxes that are themselves stored as items are not counted (depth 0). The shulker box item itself is counted.
   - A placed shulker box is an ordinary container, and its contents are counted.
4. **Persistence and the client fallback.**
   - Results live in memory only and are lost on restart. Nothing about inventories is written to disk.
   - The client fallback covers two things:
     - (a) The player's own inventory. It is always shown as a separate line, never as warehouse stock.
     - (b) Registered containers the player actually opened. They are shown as OBSERVED with a time, never as CHECKED.

## Architecture

### Separate network channel

Use a new channel, `mcmateriallist:stock_v1`, registered in both directions. Do not add message tags to `SharedMessage` v2:
- an older server would fail with INVALID_INPUT and clear the session;
- bumping `SharedProtocol.VERSION` breaks persisted client blobs.

A server that lacks the channel simply lacks the capability; check this with `ClientPlayNetworking.canSend`. Each message is one bounded frame of at most 61,440 bytes, encoded by its own codec, `core.stock.protocol.StockProtocol`.

### Which path the client uses

The server path is primary everywhere, including singleplayer, where the integrated server answers. The client fallback is used only when one of these holds:
- the server lacks the channel;
- the server refuses the request (cooldown or capacity);
- the server returns positions as UNAUTHORIZED.

## Authorization rules

| Rule | Behaviour |
| --- | --- |
| R1 | A server admin may read any requested position. Isolate the admin check in one method, `StockAuthority.serverAdmin(ServerPlayer)`, because the permission API changed in recent versions. |
| R2 | A non-admin may read only positions granted to a `ProjectScope` in which the requester holds MEMBER or higher. Check the role with `SharedProjectService.committedSnapshot(actor, scope)`. |
| R3 | An unauthorized position returns UNAUTHORIZED *before* any loaded-chunk or block check. The answer must never reveal whether a chunk is loaded or which block is there. |
| R4 | The actor always comes from the connection, never from the payload. |

R4 limits:
- one active scan per player;
- at most 512 positions per request, and duplicate request positions are collapsed;
- a cooldown of about 5 s;
- at most 4 concurrent scans per server.

**Grant registry.** A durable per-world store under the world directory, using the same atomic-write pattern as `LocationProfileStore`.
- Maps a projectId to at most 512 grants of (dimension, canonical x/y/z, approver UUID, time).
- Only an actor who is both a server admin and the project's ADMINISTRATOR may grant or revoke.
- Grant and revoke are messages on the same stock channel.

## Result model (core package `dev.mcmateriallist.core.stock`)

**`LocationStatus` values:**

| Status | Meaning |
| --- | --- |
| `CHECKED` | Read by the server on this scan. |
| `UNLOADED` | The chunk, or the other half of a double chest, is not loaded. |
| `UNAUTHORIZED` | The requester may not read this position. |
| `UNREADABLE` | No block entity, an ungenerated loot table, or more than 1024 distinct items. |
| `CHANGED` | The block is no longer the registered id, or is not a container. |
| `DUPLICATE` | Folded into another index by deduplication. |
| `OTHER_DIMENSION` | Fallback only. |
| `OBSERVED` | Read from a container the client opened. |

**`LocationReport(index, status, kind, counts)`:**
- `kind` is STORAGE or MACHINE.
- `counts` is a map of item id to a long count.
- Hopper, dropper and dispenser are MACHINE; add `ContainerBlocks.kind(blockId)`.

**`StockSnapshot(profileId, profileGeneration, world, startedAt, completedAt, reports)`** with these accessors:
- `confirmed(id)`: CHECKED storage only;
- `machine(id)`;
- `previous(id)`: the earlier CHECKED or OBSERVED value, with its time;
- `coverage()`: checked locations / total distinct locations;
- `unchecked()`.

**Item identity:** the registry id from `BuiltInRegistries.ITEM.getKey(stack.getItem())`. This is the same rule `MaterialTaskId` uses; item components are ignored.

**Never count unknown as zero.** An unread location contributes no count, and the UI states how many locations are unchecked.

**Deduplication runs at scan time on the server, against live block state:**
- Resolve each registered position with the double-chest partner rule from `LookedAtContainer`, then key it by `ContainerBlocks.canonical`.
- A second registration that maps to the same unit becomes DUPLICATE.
- If a registered double chest was split later, only the registered half is read.
- If the partner half is unloaded, the location is UNLOADED. Never count half a chest.

**Staleness.** A report becomes stale when any of these happens:
- it is more than 5 minutes old;
- the profile generation changes;
- a later scan can no longer reach the location.

Discard everything when `LocationWorld.same` fails.

**Core stays Minecraft-free.** Fabric implements a `ContainerProbe` interface that core consumes, so the dedupe, policy, budget and aggregation logic is unit-testable in core.

## Bounded scanning (fabric main, `stock/StockServerScanner`)

**Budget.** It runs on the server tick.
- At most 8 containers **and** at most 1 ms per tick across all scans. Check time between containers with `System.nanoTime()`, and serve scans round-robin.
- Record the maximum containers and nanoseconds per tick.

**Per position, on the server thread:**
1. Find the level whose `level.dimension().identifier().toString()` equals the requested dimension.
2. Call `level.hasChunkAt(pos)`, which does not load chunks. Never call `getChunk` or `getBlockEntity` before this check.
3. Read with `getBlockState` and `getBlockEntity`, using `InventoryProvider` and the default `VanillaContainerProvider` for allowlisted `Container` block entities.
4. **Loot-table guard.** If the block entity is a `RandomizableContainer` whose `getLootTable()` is not null, return UNREADABLE *without* calling `getItem`. Calling `getItem` would generate the loot and change the world.

**Streaming.** Send each location's report as one frame as soon as it is produced, at most 4 stock frames per tick, then send `Done`.

**Cancellation:**
- the player disconnects;
- the server is stopping;
- the same player sends a new request;
- the client sends Cancel.

R2 authority is rechecked before each container.

**The scanner holds no reference to work or done state.** It never changes completion, counts, placement flags or `.litematic` files.

## UI

- **Check Materials button.** Place it right after the Location File button in `MaterialWorkSession.toolbar()`, using `slot()`. Enable it only when `locations.profile() != null`. While a scan runs, the label is "Checking n/N · Cancel".
- **`StockSession`.** Holds scan state like `LocationSession` and is exposed as `stock()`.
- **Never change the Total, Missing or Available columns.**
- **Footer summary line**, above the progress line. For example:
  - "Stock taichi_ms: 31/40 checked 12:03 · 6 unchecked · 3 previous";
  - or, in fallback: "Client observation only (your inventory + opened containers)".
- **Show Info line** (MaterialRowMixin), appended: "Stock 128 (+64 previous, 5 in machines)". The row tooltip shows the per-source breakdown.
- **`StockDetailScreen`.** A paged list of locations with their status and time, following the `LocationProfileEditScreen` row pattern. For admins it also shows Grant/Revoke for the bound project.
- **Stale data** is shown in grey as "previous at hh:mm (n min ago)". An unreadable location is never shown as 0.

## Tests

**Core JUnit:**
- deduplication: join, split and re-registration;
- coverage and staleness, with an injected Clock;
- codec bounds and trailing-byte rejection;
- policy R1 to R3;
- budget accounting with fake nanoseconds;
- aggregation never treats unchecked locations as zero;
- grant store contract: atomic save, recovery, future schema, path safety.

**Client gametest `Phase4StockClientTest`** (singleplayer, using the `Phase4LocationClientTest` patterns):

| Case | Expected |
| --- | --- |
| Chest and barrel | Counted. |
| Double chest with items in both halves | Counted once. |
| Right half also added through the service | DUPLICATE. |
| Hopper | MACHINE. |
| A far position | UNLOADED, and `hasChunkAt` is false both before and after the scan. |
| Loot-table chest | UNREADABLE, and the loot table is still present afterwards. |
| Nether, then re-scan | The overworld locations show "previous". |
| 512 barrels | At most 8 containers per tick. Nanoseconds are logged as a measurement, not a gate. |
| Bytes and columns | `materials.json` and `.litematic` bytes are unchanged, and so are Total, Missing and Available. |

**Multiplayer:** extend `MultiplayerClientTest` and its harness.
- Beta, who is not an admin and has no grant, gets UNAUTHORIZED with no content.
- After alpha, as op and Administrator, grants: beta as MEMBER gets CHECKED. Demoted to VIEWER, beta gets UNAUTHORIZED.
- When beta opens a registered chest, the fallback reports OBSERVED with the limited-scope label.

**Gate:** add `:fabric:verifyStockScan`, which requires the log markers, following the `verifyLocationFiles` pattern. `verifyPhase4` also depends on it. No existing dependency is removed.
