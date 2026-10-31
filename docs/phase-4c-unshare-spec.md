# Phase 4C Unshare regions: implementation spec (Issue #11)

This is the working spec for the implementation. Once the work is done, `docs/phase-4c-unshare.md` replaces it.

## Owner decisions (2026-10-31)
1. **Unshare only online and synchronized.** The regions binding must be shared and synchronized with the server. Every confirmed edit of the user's own must already be inside the copy.
2. **Rejoin exports the detached edits.** It writes them to a file under `config/mcmateriallist/shared-client/detached/`, which an administrator can re-enter by hand. The app never reads that file back and never merges it.
3. **Controls live in the existing Shared work screen, for REGIONS only.** They do not go on the native region toolbar.

The assistant chose the strict defaults below for safety. Each one is listed in the PR body.
- **P3:** any unresolved REGIONS journal entry blocks unshare, whichever account or scope it came from.
- **P4:** after a journal recovery (`recoveredJournalUncertain`), unshare stays blocked on that placement.
- **Unreadable metadata:** if the sharing metadata cannot be read, region editing on the original local data is blocked. A detached copy might be hidden in that metadata.

## Design principle
Use the simplest design whose invariants can be written down: one atomic envelope write, and no merging. Issue #10 showed that clever merging of several state sources produces endless edge cases.

## Model (core)
- **`SharedClientArchive.Binding` gains `detached`,** a `Detached(RegionWorkDataset dataset, ProjectScope from)`. The 3-argument constructor is kept, with `detached = null`.
- **Invariants:**
  - **I1.** A binding is in exactly one state:
    - SHARED: `shared != null`, cache optional;
    - DETACHED: `detached != null`, `shared == null`, `cache == null`;
    - ORIGINAL: both null.

    The materials binding is never DETACHED. The archive constructor enforces I1.
  - **I2.** `detached.dataset.id` differs from `localDataset` and from `from.regionListId()`, and its placement equals the archive's placement.
  - **I3.** Unshare changes only the regions binding. The materials binding and the journal carry over unchanged, and the original `regions.json` is never touched.
  - **I4.** Unshare is purely local. It sends nothing to the server and does not change Placement ON/OFF, sub-region flags or other upstream state.
  - **I5.** Nothing ever merges. No path reads a detached dataset into shared state, or shared state into a detached dataset, after creation.
- **Every rebuild of a `Binding` preserves `detached`.** That includes `cache()`, `clearCaches` and `withoutCaches`, so the backup keeps the detached copy.

## Unshare
`SharedClientService.unshare(placement, expectedRef, expectedCursor, actor, DatasetId newId)` runs on the serial queue. It re-checks these preconditions against the on-disk archive:
- **P1.** The regions binding is SHARED, its reference equals `expectedRef`, and its actor equals `actor`.
- **P2.** `cache != null` and `cache.cursor == expectedCursor`. The UI also requires `synchronizedView`: online, source proof present, and live state equal to the cache.
- **P3.** No journal entry has `kind == REGIONS && confirmation == null`. "Resolved" means `confirmation != null`. Only a definitive status resolves an entry (#7); a disconnect never does.
- **P4.** `!recoveredJournalUncertain`.
- **P5.** Every REGIONS entry in that scope with an OK confirmation has `projectRevision <= cache.cursor`.
- **P6** (controller only). `busy == 0`, nothing is confirming, there is no open request for this entry, and storage is OK.

The write:
1. **Idempotent retry.** If the binding is already DETACHED with `newId`, return the archive unchanged.
2. **Build the copy** with `RegionDetachment.copy(newId, placement, snapshot)` in `core.shared.client`:
   - definitions come from `manifest.regions()`;
   - states are the server region rows, unchanged;
   - `reviewRequired` is `needsReconfirmation` restricted to region tasks;
   - archived is empty and generation is 0.
3. **Save once.** Replace the regions binding with `Binding(localDataset, null, null, Detached(dataset, scope))` in a single `store.save`. The atomic primary move is the only commit point, so any failure before it leaves the binding SHARED.

**Admission race.** `admit` and `unshare` run on the same serial queue.
- Admit first: the new journal entry is unresolved, so P3 refuses.
- Unshare first: admit's binding-equality check fails, so the command is never sent.

**Local edits after unshare.** `updateDetached(placement, datasetId, expectedGeneration, fn)` applies `RegionWorkDataset.apply`, `acknowledge` or `reconcile` to the detached copy. Journal state does not block it, because these edits stay local.

## Rejoin
Rejoin is an explicit two-step action, **Rejoin shared regions** (`join(detached.from, select=true)`). Reconnect never rejoins automatically:
- `restore()`, the cache loop and the replay loop all skip bindings without a reference;
- `select()` refuses to replace a DETACHED binding.

On a successful join, `rejoin(placement, expectedDetachedId, expectedGeneration, local, actor, snapshot, cursor)` runs under the store lock:
1. It atomically writes the export `shared-client/detached/placement-<p>-dataset-<id>-g<gen>.json` in `DatasetJsonCodec` format.
2. It replaces the binding with `Binding(localDataset, newRef, freshCache, null)`.

A failure between the two steps leaves the binding DETACHED; a retry overwrites the export with the same content. The shared view afterwards is the fresh server snapshot.

## Storage
- **Schema.** `SharedClientStore.SCHEMA` becomes 3. Version 3 stores a detached flag plus a blob holding the `DatasetJsonCodec` JSON and the four scope UUIDs.
- **Upgrade and downgrade.** Versions 1 and 2 are still read. A future schema is refused and never overwritten; older builds see UNSUPPORTED_SCHEMA.
- **Size.** Because of I1, at most one large blob sits in each binding slot, so `MAX_BYTES` is unchanged.
- **Fault injection.** A store `WriteHook`, mirroring `LocalDatasetStore`, injects failures before the backup replace and before the primary replace.

## UI (`SharedWorkScreen`, REGIONS only)
- **Controls.** The row offers **Unshare regions...**, or **Rejoin shared regions...** when detached. Both use two-step confirmation, cancelled by any context or cursor change, like `DefinitionConfirmation`.
- **Hover text** explains the scope: only this placement's regions; materials stay shared; other players are unaffected; Placement ON/OFF is unchanged.
- **Disabled reasons:** `not_shared`, `unresolved_operations` (with count), `recovered_uncertain`, `join_required` (offline, hidden or stale), `refresh_required` (P5), `account_mismatch`, `saving`, `storage.X`.
- **Status.** The footer and selection status show "Detached local copy".
- **Session.** `RegionWorkSession` uses one `view()` helper that picks shared, then detached, then the original dataset.
- **Language files.** Keys are added to both en_us and ja_jp.

## Tests and gate
**Core.**
- The copy equals the cache rows and manifest and has a fresh ID.
- The materials binding and the journal are equal before and after, and the original `regions.json` bytes are unchanged.
- Each of P1–P5 refuses with no change on disk.
- An unknown entry blocks, and so does a non-definitive replay result. `resolve(OK)` unblocks, but P5 still requires the cache cursor to catch up.
- Both admission orders are covered, and a retry with the same ID is a no-op.
- Failure injection runs at both stages for unshare and for rejoin.
- The backup keeps the detached copy.
- Version 2 is read, and a future schema is refused.
- `select` refuses to drop a detached copy.
- After rejoin, the export exists and the cache is the server snapshot.

**sharedUiTest.**
- A held result keeps Unshare disabled, even across a disconnect and reconnect, until the replay settles.
- Unshare leaves the server unchanged.
- Materials, placement and sub-region flags, and `regions.json` bytes are unchanged.
- A local Done changes only the copy.
- Rejoin shows the server rows and writes the export.
- Geometry is checked at GUI scales 1–4, in the narrow layout and in Japanese.
- Marker: `Shared native UI unshare PASS`.

**Multiplayer** (beta uses a real `SharedClientService`).
- A dropped ACK blocks unshare, and so does a disconnect.
- Reconnect, replay and resolve then allow unshare.
- Alpha's fingerprint and revision are unchanged.
- No changes cross in either direction while detached.
- Rejoin brings back the server authority, and the export holds beta's local edit.
- Markers:
  - `Multiplayer beta unshare blocked until replay PASS`
  - `Multiplayer beta unshare rejoin authority PASS`
  - `Multiplayer alpha unshare unaffected PASS`

**Gate.** `:fabric:verifyUnshare` checks these markers, and `verifyPhase4` depends on it. No existing dependency is removed.
