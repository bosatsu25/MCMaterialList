# Phase 4B Check Materials

Issue #10 implements **Check Materials**: reading the registered containers of the current Location File (Phase 4A) and showing how much of each material is in stock. Check Materials only presents a separate stock view. It never changes Total, Missing or Available, progress, completion, assignments, notes, `materials.json`, `regions.json` or `.litematic` files.

## Owner decisions (2026-10-30)

1. **Authorization is server admins plus a grant registry.**
   - A server admin may read any position they request. A server admin is an operator, or the owner of a singleplayer or LAN world. They can already read containers with `/data get block`, so Check Materials exposes nothing new to them.
   - Everyone else may read only positions that were granted to the project. Only a person who is both a server admin and the project's Administrator can grant a position.
2. **Granted reads need the MEMBER role or higher.** A Viewer gets nothing.
3. **What is counted:**
   - Hoppers, droppers and dispensers count separately, as "in machines". Their contents are never added to storage stock.
   - When a shulker box is stored as an item, only the shulker box item itself is counted. The items inside it are not counted (depth 0).
   - A placed shulker box is an ordinary container, and its contents are counted.
4. **Persistence:**
   - Results live in memory only. Nothing about inventories is written to disk, on the client or on the server.
   - The client fallback shows two things:
     - the player's own inventory, always on a separate line and never as warehouse stock;
     - registered containers the player actually opened, shown as OBSERVED with the time they were seen.

## Use

1. Open a placement's material list and select a current Location File (Phase 4A). **Check Materials** sits right after the Location File control. It is disabled until there is a current Location File.
2. Select **Check Materials**. While the check runs, the button reads **Checking n/N · Cancel**, and selecting it cancels the check. Cancelled or unreported locations stay unchecked.
3. When the check finishes, the footer shows a summary line above the progress line, for example:
   - `Stock taichi_ms: 31/40 checked 12:03 · 6 unchecked · 3 previous`;
   - in fallback: `Client observation only (your inventory + opened containers)`.

   On large screens the summary follows the progress line on the same line.
4. With **Show Info** on, each row gains a stock segment, for example `Stock 128 (+64 previous, 5 in machines) · Inventory 10`. Hovering the Show Info line shows the per-source breakdown: checked storage, previous or observed values, machines, your inventory, and unchecked locations.

   If no location was checked, the row says `Stock unchecked`. An unread location is never treated as zero.
5. **Stock details...** appears after a check. It lists every registered location with its status and time. A stale value is shown in grey as "previous at hh:mm (n min ago)".

   For a Location File bound to a shared project, it also asks the server whether you may grant reads. If the server answers OK (you are a server admin and the project's Administrator), the screen offers **Grant all to the project** and a Grant or Revoke button on each row.

Statuses:

| Status | Meaning |
|---|---|
| Checked | Read by the server on this check. |
| Not loaded | The chunk, or the other half of a double chest, is not loaded. Nothing is loaded to find out. |
| Not permitted | You may not read this position. This answer is decided before any world access, so it reveals nothing about the position. |
| Unreadable | No block entity, a loot table that has not been generated yet, or more than 1024 distinct items. |
| Block changed | The block is no longer the registered container. |
| Same container as another row | The row reads the same double chest as an earlier row. |
| In another dimension | Fallback only. |
| Observed | Fallback: you opened this registered container yourself, at the time shown. |

A check becomes stale after 5 minutes, or when the Location File changes generation. Every value is then shown as previous. A later check that cannot reach a location keeps the location's last value as previous. Everything is discarded when the world or connection changes.

## Server path

The server path is primary everywhere, including singleplayer, where the integrated server answers. The messages use their own channel, `mcmateriallist:stock_v1`, in both directions. Each message is one frame of at most 61,440 bytes (`core.stock.protocol.StockProtocol`). `SharedMessage` and `SharedProtocol.VERSION` are unchanged. A server without the channel simply lacks the capability; the client tests this with `ClientPlayNetworking.canSend`.

The server (`fabric/src/main/.../stock`) works like this:

- **Actor.** The actor always comes from the connection. `StockAuthority.serverAdmin` is the only admin check, so a change to the permission API stays isolated in one place.
- **Admission.** Each player may run one scan at a time. A request may contain at most 512 positions, and repeated positions are collapsed. A player must wait 5 s between starts. At most 4 scans run on the server at once. A refusal (cooldown, capacity or busy) answers `Refused`.
- **Budget.** Scanning runs on the server tick. A tick reads at most 8 containers and spends at most 1 ms between containers, serving scans round-robin. The maximum containers and nanoseconds per tick are recorded; only the container count is a gate.
- **Streaming.** Reports stream at most 4 frames per player per tick, and then `Done` is sent.
- **Cancellation.** A scan is cancelled when the player disconnects, the server stops, the same player sends a new request, or the client sends `Cancel`.
- **World access.** `ServerContainerProbe` follows these rules:
  - It finds the level whose dimension id matches the request.
  - It calls `hasChunkAt` before any block or block-entity access, and never loads a chunk.
  - It applies the double-chest partner rule of `LookedAtContainer`, and never counts half a chest.
  - It returns UNREADABLE for a `RandomizableContainer` that still has a loot table, without calling `getItem`. Calling `getItem` would generate the loot.
  - It identifies items by registry id and ignores components.
- **Live authorization.** Admin status, role and grants are checked again before every container, so a de-op, a demotion or a revoke takes effect mid-scan.
- **Grant registry.** Grants are stored in `<world>/mcmateriallist-stock/grants/<project-uuid>.json`, with a backup and the atomic-write and recovery contract of `LocationProfileStore`. A project holds at most 512 grants.
  - Grant files are read and written on one owned background thread.
  - Live checks consult only the in-memory cache.
  - A scan by a non-admin waits for that project's grant file to be read.
  - Granting and revoking require a server admin who is the project's ADMINISTRATOR. The role is read with `SharedProjectService.committedSnapshot`.

## Client fallback

The client fallback is used when:

- the server lacks the channel;
- the server refuses the request;
- or the server answers a location UNAUTHORIZED.

In those cases a registered container you opened is shown as OBSERVED with its time, never as CHECKED, and the footer names the limited scope. An observation is recorded only if both of these hold when a container screen opens:

- the looked-at block is a registered position of the last Location File shown in that world;
- the menu's container slot count matches the block (27, or 54 for a double chest; 5 for a hopper; 9 for a dropper or dispenser).

The contents are then updated for as long as the screen stays open. Observations and the inventory line are kept in memory only.

## Verification

`verifyPhase4` adds `:fabric:verifyStockScan`. It depends on `verifyClientRestart` and `verifyMultiplayer`, and requires these log markers:

- the restore marker of the client restart pair;
- the multiplayer stock markers of both clients;
- a completed scan on the dedicated server.

No existing dependency was removed. The application JAR still excludes all test code.

- **Core JUnit** covers:
  - deduplication (join, split and re-registration);
  - coverage and staleness with an injected clock;
  - codec bounds and trailing bytes;
  - policy R1 to R3;
  - budget accounting with fake nanoseconds;
  - aggregation that never treats unchecked locations as zero;
  - the grant store contract.
- **`Phase4StockClientTest`** (singleplayer, through the real button). The singleplayer owner is an admin.

  | Fixture | Expected |
  |---|---|
  | Chest and barrel | Counted. A shulker box item counts as itself. |
  | Double chest with items in both halves | Counted once. |
  | Right half also added through the service | Same container as another row (DUPLICATE). |
  | Hopper | Machine. |
  | Position 4000 blocks away | Not loaded, and the chunk is still not loaded afterwards. |
  | Loot-table chest | Unreadable, and its loot table is still there afterwards. |
  | Re-check after the overworld unloads (from the Nether) | Not loaded, with the earlier values shown as previous. |
  | 512 barrels | At most 8 containers per tick. Nanoseconds are logged, not gated. |

  It also checks that `materials.json`, `.litematic` bytes and the Total/Missing/Available columns are unchanged. The fresh restore process confirms that no client file mentions stock.
- **`MultiplayerClientTest`.** The harness makes alpha an operator through `ops.json`. Each client checks:
  - alpha reads the chest without a grant and grants it to the project;
  - beta, as a Member, is refused (Not permitted) before the grant and gets Checked after it;
  - after demotion to Viewer, beta is refused again;
  - an immediate second request is refused by the cooldown and falls back to the client;
  - after beta opens the chest, it is Observed with the limited-scope label.

**Not covered by automated tests:**

- the Grant and Revoke buttons of the details screen; the grant path is covered through the protocol;
- grant-file recovery on a server;
- the LAN-owner case;
- Block changed;
- dispensers, droppers and placed shulker boxes in a client test.
