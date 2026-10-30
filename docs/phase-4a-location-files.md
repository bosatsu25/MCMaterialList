# Phase 4A Location Files

Issue #9 implements the **Location File** control of the design ([§8](../litematica-collaboration-design-v0.1.md), AC-15): a per-world profile that records which container blocks hold the materials for a build. Phase 4A only registers and remembers positions. **Check Materials** (Issue #10) is not implemented; nothing here reads container contents, counts items or changes Total/Missing/Available, progress or completion.

## Use

1. Open a placement's native material list. The toolbar shows **Location File: (none)**, the current profile's name, or **(unavailable)** (see identity rules).
2. Select it. **New...** creates a profile in the current dimension and makes it current. Selecting a row makes that profile current. **Edit...** opens the current profile.
3. In **Edit**, look at a container in reach and select **Add looked-at container**. Each position row has **Remove**. **Rename...** changes only the display name.
4. **Bind to this list's shared project** records the invoking material list's shared project scope as metadata, only when that list is shared and the project belongs to the same world. **Unbind shared project** removes it. Binding shares nothing.

The control follows Show Info/Hide Done on the native header row when that row has room. Otherwise it takes the first free toolbar row; Share keeps its previously verified position. A clamped label keeps the complete localized label as hover text.

Every action resolves the world again on the client thread. Results are applied only after the write is durable, and only while the same world and connection are current; controls are disabled while storage is pending. Registration is validated at click time and refused with a specific reason:

| Reason | Meaning |
|---|---|
| Unavailable / other world | No world identity here, or the profile belongs to another world |
| Other dimension | The player is not in the profile's dimension |
| Not looking at a block / out of reach / not loaded | The client hit result is not a block, the block (or the other chest half) is not loaded, or it is beyond the server's block-interaction tolerance |
| Not a registrable container | The block id is outside the allowlist |
| Already registered | The position, or the other half of the same double chest, is registered |

## World identity (fail-closed)

A Location File belongs to the world namespace UUID negotiated by MCMaterialList's shared transport: singleplayer (integrated server) and servers running a compatible MCMaterialList. While negotiating, and on servers without the mod, with an incompatible protocol, unavailable world identity or disconnected, the screens show the reason and disable create, select and register. There is no server-address, world-name or local fallback identity, so a profile can never be attached to the wrong world by name. Reconnecting to the same world restores the same profiles; another world never lists them.

The current profile is remembered per world. A missing or unreadable current profile is reported as such and never replaced by another profile.

## Registrable containers

Chest, trapped chest, barrel, shulker box (every colour, placed), hopper, dropper and dispenser, by registry id. Ender chests and every other block are refused. A double chest is stored once, at the lexicographically smaller x, y, z of its two halves; adding either half when the other is registered is a duplicate. A half whose partner does not point back is treated as a single chest.

Hoppers, droppers and dispensers usually hold items only in transit or as machine contents. A future check (Issue #10) must report their contents as such, not as reliable storage.

## Storage

Files live under the client configuration, next to local work:

```
config/mcmateriallist/location-files/worlds/<world-uuid>/profiles/<profile-uuid>.json  (+ .bak previous-good)
config/mcmateriallist/location-files/worlds/<world-uuid>/current.json                  (+ .bak previous-good)
```

Only canonical lowercase UUIDs and fixed names become path components; display names are values only (a name such as `../evil` is stored as text). Profiles hold their id, generation, display name, world, dimension, optional shared-project binding and sorted unique positions with block ids. Writes use the Phase 1 contract: lock, generation compare-and-set, validated temporary file, atomic replace and previous-good backup. Damaged or future-schema files are listed with their status and are never overwritten; **Recover backup**, then **Confirm recovery**, restores the validated backup and preserves the damaged bytes. Reading never writes.

Limits: 512 containers per profile, 256 profiles per world, display names of 1-64 code points (256 UTF-8 bytes) without control or bidirectional formatting characters, coordinates within ±30,000,000 horizontally and y -2048..2047.

## Verification

`verifyPhase4` runs every retained Phase 0-3 gate plus `:fabric:verifyLocationFiles`. Core unit tests cover the model, codec, store, service and container allowlist. `Phase4LocationClientTest` runs inside the existing client restart pair:

- First process: the material list shows **Location File: (none)**; profiles `taichi_ms` and `../evil` are created through the real form, and only UUID-named files exist. Real placed blocks are aimed at and added: a chest, one double chest half (stored at its smaller position), a barrel and a hopper are accepted; the other double chest half is a duplicate; stone and an ender chest are refused; looking at the sky is refused. A position is removed and the profile is renamed. In the nether, adding to the overworld profile is refused without a write. `materials.json` and `.litematic` bytes are unchanged, and a reopened list reads the same current profile without rewriting it.
- Fresh restore process: the same profile and `current.json` bytes, current selection, name, generation and positions are read back unchanged; a different world resolves to another identity and lists no profiles.

Not covered: binding through a shared project in the client gametest (core tests cover bind/unbind rules), the out-of-reach and unloaded-chunk refusals in a real client, shulker boxes, droppers and dispensers in the client gametest, multiplayer servers other than the integrated server, and container contents.
