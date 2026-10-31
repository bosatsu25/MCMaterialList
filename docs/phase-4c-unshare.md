# Phase 4C Unshare regions

Issue #11 adds **Unshare regions**: turning one placement's shared region work into a purely local **detached copy**, and **Rejoin shared regions** to go back to the server's work. Only the regions of that one placement are affected. Materials stay shared, other players are unaffected, and Placement ON/OFF, sub-region flags, `regions.json`, `materials.json` and `.litematic` files are never changed. Unshare sends nothing to the server.

## Owner decisions (2026-10-31)

1. **Unshare only online and synchronized.** The regions binding must be shared and synchronized with the server, and every confirmed edit of your own must already be inside the copy.
2. **Rejoin exports the detached edits.** Rejoin writes the copy to a file under `config/mcmateriallist/shared-client/detached/`, which an administrator can re-enter by hand. The app never reads that file back and never merges it.
3. **Controls live in the existing Shared work screen, for REGIONS only.** They do not go on the native region toolbar, and materials have no unshare.

## Strict defaults

These were chosen for safety where the owner did not decide.

- **P3. Any unresolved REGIONS journal entry blocks Unshare,** whichever account or scope it came from. "Resolved" means the entry has a confirmation, which only a definitive server answer gives (#7). A disconnect never resolves one.
- **P4. After a journal recovery, Unshare stays blocked** on that placement (`recoveredJournalUncertain`).
- **Unreadable sharing metadata blocks original local region editing.** When the placement's sharing metadata was loaded but its status is neither OK nor MISSING (corrupt, unsupported schema, recovery required, I/O failure and so on), region Done, notes, Accept and Refresh on the original local data are disabled, because a detached copy might be hidden in that metadata. The footer shows the storage reason.

## Use

1. Open the placement's region screen and select **Share**. Below the existing rows, the REGIONS management screen has one more row:
   - **Unshare regions...** while the regions are shared;
   - **Rejoin shared regions...** while they are detached.

   The hover text explains the scope: only this placement's regions; materials stay shared; other players are unaffected; Placement ON/OFF is unchanged. The project list starts one row lower on this screen.
2. Both actions need two clicks. The first click arms the action and the label becomes **Confirm unshare** or **Confirm rejoin**; the second performs it. Any change of selection, account, shared snapshot, cursor or detached copy cancels the armed confirmation, as for **Update shared definition...**.
3. While detached, the selection line reads `Regions only: Detached local copy`, and so does the region footer. Done, Claim, Release, Save note, Accept new definition and Refresh work all edit the detached copy only. Nothing is sent to the server, and the journal never blocks these local edits. Find projects still works, but New project... and joining another project are disabled until you rejoin, so the copy is never replaced silently.
4. **Rejoin shared regions...** is an explicit, source-verified join of the project the copy came from. When the join succeeds, the copy is first exported, then the server's snapshot replaces it. The region rows afterwards are the server's rows; nothing from the copy is merged.

Unshare is disabled with one of these reasons (shown in the hover text):

| Reason | Meaning |
|---|---|
| Regions are not shared | The regions binding is original local work (or already detached). |
| Waiting for *n* unconfirmed shared region result(s) | P3: *n* REGIONS originals are still unknown. Reconnect and let the replay settle them. |
| Recovered settings may omit sent work | P4. |
| Join / synchronize online before unsharing | P2: offline, hidden, a stale cache, or the live view is not synchronized with a verified source. |
| Your confirmed region edits are not in the view yet | P5: an own confirmed result is newer than the cached cursor. |
| Shared regions belong to another account | P1. |
| Saving or waiting for a request | P6: a save, confirmation, cache write, administration or join request of this placement is in flight. |
| Sharing settings ... | The sharing metadata's storage status. |

Rejoin is disabled while saving, without a compatible sharing server (the usual availability reason), or for a server world other than the copy's.

## Behaviour

- **One view.** `RegionWorkSession.view()` picks the shared server view, then the detached copy, then the original local dataset. Every row state, review marker, progress, ID lookup, Done and Refresh goes through it.
- **Unshare** (`SharedWorkController.unshare`) re-checks P1-P6 on the client thread, then calls `SharedClientService.unshare`, which re-checks P1-P5 against the on-disk archive on the same serial queue as command admission and replaces the regions binding with the copy in one atomic save. If an admission wins the race, its unresolved entry blocks unshare (P3); if unshare wins, the admission's binding check fails and nothing is sent. A refused save re-reads the archive, so the last confirmed state is never left marked as a conflict.
- **Detached edits** (`SharedWorkController.updateDetached`) run `RegionWorkDataset.apply`, `acknowledge` or `reconcile` once, as a pure function on the store queue, and are refused while any save or request of the placement is open.
- **Reconnect never rejoins.** Reconnect restoration, the confirmed-cache loop and the replay loop skip bindings without a shared reference, so a detached copy is left alone. Join and create refuse to select over a detached copy; only Rejoin replaces it.
- **Rejoin** joins `detached.from` with the same source check as Join / synchronize, then calls `SharedClientService.rejoin`, which under the store lock writes `shared-client/detached/placement-<placement>-dataset-<copy>-g<generation>.json` in `DatasetJsonCodec` format and installs a fresh reference with the server snapshot. A failure between the two steps leaves the copy detached, and a retry rewrites the same export.

The storage model, invariants I1-I5 and schema 3 are described in [the implementation spec](phase-4c-unshare-spec.md) and covered by `SharedClientUnshareTest` in core.

## Verification

`./gradlew verifyPhase4 -PacceptMinecraftEula=true` runs `:fabric:verifyUnshare`, which requires these markers:

- **Native shared UI** (`runSharedUiTest`, `SharedUiUnshareChecks`), marker `Shared native UI unshare PASS`. On its own fixture and connections it checks that:
  - a held server result keeps Unshare disabled with the unresolved count, across a real disconnect and reconnect, until the replay settles;
  - a cursor change cancels the armed confirmation;
  - Unshare and a local Done on the copy send no command and leave the server snapshot, the material binding, the journal, Placement ON/OFF, sub-region flags and the `regions.json` bytes unchanged;
  - the footer and selection show "Detached local copy";
  - Rejoin shows the server rows and writes an export equal to the copy;
  - the detached management row fits at GUI scales 1-4, in the narrow layout and in Japanese (the shared row is covered by the existing geometry pass).
- **Multiplayer** (`verifyMultiplayer`, beta with a real `SharedClientService`), markers:
  - `Multiplayer beta unshare blocked until replay PASS`: a dropped region ACK blocks unshare (P3) and so does a disconnect; reconnect, exact replay and resolve allow it;
  - `Multiplayer beta unshare rejoin authority PASS`: no change crosses in either direction while detached, and rejoin installs the server authority while the export holds beta's local edit;
  - `Multiplayer alpha unshare unaffected PASS`: alpha's fingerprint and revision are unchanged by beta's unshare and local edit.
