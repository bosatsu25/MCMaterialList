# Phase 2A/2B: Local collaborative UI

Issue #2 extends the supported Litematica material screen with durable **local** work; Issue #3 adds region work to placement configuration. The authoritative root design was restored by PR #18, closing #17. Full two-screen screenshot parity belongs to #4. Shared project/protocol/permission work remains Phase 3.

## User behavior

Opening a material screen reads existing work without writing. **Track materials** explicitly creates a missing material dataset. **Refresh work** reconciles a completed upstream material observation. Unregistered, temporary, duplicate-identity, canceled or still-counting placements cannot submit commands.

Rows retain this order: assignee face, upstream item, material name, Total, Missing, Available, notice, completion action, upstream Ignore. A green check means mark completed; a red X means undo. The face gets a separate completion overlay. Missing/Available, notes, assignment and placement enable/render flags remain independent. Unknown players use a fixed 16px fallback without an added lookup service.

Click the face/notice for Claim/Release, a multiline note and completion actor/time. Claim/Release respect the existing local ownership transition rules and explain disabled actions; the local model does not provide server permissions. Notes have a 4096-byte UTF-8 limit and save only through **Save note**. Show Info changes both row drawing and list hit/space geometry. Hide Done, upstream search/sort/Ignore/Hide Available affect the view, not the denominator. Empty work uses 0/0 (0%).

Progress counts all current tracked material types and floors the percentage. Upstream Refresh reconciles work after counting completes. Changed quantities retain ownership/completion/notes and show a session notice, even with Show Info OFF. The notice remains across repeated refreshes in that screen; this is not a persisted notification history or acknowledgement system. Removed definitions remain in read-only **Archived work** and restore by the same registry identity.

Storage work uses the existing serial I/O service and strict atomic snapshot store. Buttons cannot publish optimistic completion. A failure retains the last confirmed view, disables editing and displays a typed status. A newly opened corrupt dataset never presents its backup as current. **Recover backup** first explains possible loss of the latest action; **Confirm recovery** explicitly restores the previous-good snapshot while preserving damaged data. Refresh work reloads a conflict; future-schema and corrupt data never initialize blank work.

Only the material snapshot is used by these controls. The Phase 0 footer and Phase 1 Shift-click diagnostic remain available; that separate diagnostic intentionally refreshes both datasets. No work is written into `.litematic`. Phase 2A added no product networking, telemetry, stock scanning or region controls; the region extension is documented below.

## Integration

Version-gated optional mixins extend the existing widgets; they do not copy the upstream screen or replace counting. Quantity rendering, item hover, sorting, Ignore and export handlers remain upstream. Row identity uses the aggregated item's registry ID, never its translated name or row position. Names yield space to right-side controls; retained header sort icons have explicit clearance.

Each material screen owns its confirmed immutable view. Worker results are always queued to the client thread, including already-completed futures, and rebuild only the matching current parent/detail screen. A hidden screen cannot replace another placement's UI. Show Info/Hide Done are personal to the screen session. Storage remains lifecycle-owned and drains accepted operations at client shutdown.

The extra toolbar reserves 22 GUI pixels above the list and retains the diagnostic/footer area. The semantic row ordering and material behavior are implemented here. Exact density/fonts/colors and combined region parity against the supplied 1920x1080 images remain the dedicated #4 acceptance work; unknown screenshot players and the eight unseen materials are not inferred.

## Verification

Use the pinned JDK 25 and dependencies documented in README:

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :fabric:verifyClientRestart
# After accepting the Minecraft EULA:
.\gradlew.bat clean verifyPhase2 -PacceptMinecraftEula=true --console=plain
```

`verifyPhase2` includes the unchanged Phase 0/1 regression gates, real material and region UI tests in two Minecraft processes, build/core/isolation checks and the dedicated-server smoke test. The second process uses the first process's configuration directory and reads the actual durable snapshot; no snapshot is copied into a substitute store. Test fixtures/screenshots/exports remain ignored build outputs, excluded from the distribution.

The real fixture uses 19 distinct upstream-counted materials. Its first eleven item types match the reference order; the other eight and small counts 19 through 1 are explicit synthetic test data. Rows 3/4/10/11 complete to **4/19 (21%)**, with Missing=Total and Available=0. Tests exercise real clicks and Japanese multiline keyboard input, claim/release, undo, Hide Done, sort/Ignore/Hide Available, Refresh, text/raw exports, definition-change/archive restoration, four GUI scales, a long synthetic Japanese name, unavailable-player skins and explicit backup recovery. Definition-change tests deliberately republish upstream material observations; they do not rewrite a schematic file or introduce a replacement counter.

The failure test damages a test-only primary snapshot immediately before a completion click: the last confirmed state must remain, the damaged bytes must survive, and recovery must require two explicit actions. A deterministic already-completed-future test covers non-reentrant initialization. The fresh process compares exact dataset identity, definitions, states and metadata, verifies 4/19 and checks read-only reopening and unchanged schematic bytes.

Screenshots are under `fabric/build/run/clientGameTest/screenshots/`, including `phase2-materials-4-of-19`, `phase2-materials-show-info`, `phase2-japanese-name-scale-1` through `-4`, `phase2-explicit-recovery` and `phase2-materials-restart` (numeric prefixes are assigned by the test runner).

On Windows, 2026-10-04, the final `clean verifyPhase2` exited **0**, `BUILD SUCCESSFUL in 1m 54s`. All **28 core tests** passed with no failures/errors/skips; two real client processes and the isolated dedicated server passed. The final UI tests also exercised actual header sorting and keyboard search without changing 4/19. The application JAR includes the nested core library and excludes gametest/server-smoke code, test mixins, screenshots and logs.

Independent review found reentrant initialization, header-arrow clearance and hidden detail rejection feedback issues. All three were corrected with meaningful regression coverage and re-reviewed; no further required correction remained. No compiler warnings were suppressed. Upstream OSHI/Realms/test-account messages and Gradle deprecation notices appeared during the harness.

Hosted CI uses the same `verifyPhase2` gate and uploads evidence; its result is verified on the PR before merge. Other versions remain disabled; other mod combinations and macOS are not verified by this change.

The first PR CI run exposed a Phase 1 restart fixture collision: all tests used the same world name, allowing upstream per-world configuration to import previous fixtures. The pinned Litematica manager rebuilds chunks asynchronously while adding placements; overlapping imported fixtures could leave only the earlier placement in the schematic world. Every test now creates a distinct world name, asserts that no unrelated placements were imported, and saves/restores only its own fixture. Both processes still use the same durable application configuration; dataset equality, schematic bytes and all existing assertions remain checked without extending timeouts.

## Phase 2B region behavior and integration

Opening placement configuration reads region work without writing. **Track regions** explicitly creates missing work; **Refresh work** reconciles exact original schematic keys, relative origins and signed sizes. Capture and refresh are region-only and do not require an initialized/completed material calculation, nor read, initialize or rewrite material work. The separate Phase 1 Shift-click diagnostic still deliberately refreshes both datasets.

Rows retain this order: fixed 16px assignee face, upstream schematic L icon, clamped region name, one notice, complete/undo, Configure, Placement ON/OFF. The face overlay and green/red action are separate. Completion follows the existing local transition rules and never changes counts, placement/render flags or schematic bytes. **Regions built** includes all current tracked definitions, even when placements are disabled; percentages floor. Hide Done and upstream natural ordering/search only change the view. Show Info updates both drawing and the erased upstream height method used for row creation/space/hits.

The single `!` combines upstream modification and note reasons with the standard translated explanation retained. Removing the note leaves a standard modification notice, including with Show Info OFF and completion retained. Heads and notices open details with Claim/Release, explicit 4096-byte UTF-8 multiline note saving and completion actor/time. Existing player data or a fixed fallback is used; no added skin lookup or network call is made.

Ambiguous original-definition changes archive old work and create a new default task with a persisted review flag. The new task cannot receive work commands until **Accept new definition** is selected in details. Its tooltip gives the exact key/origin/signed size and explains that archived state will never transfer. **Archived work** is read-only and exposes exact old descriptors and metadata, with full hover text for clamped entries. Exact unique original definitions can restore their archived state under the existing domain rules. Repeated unchanged observations retain task IDs; mutable placement origin/rotation/mirror are not identity.

The region session publishes only confirmed serial I/O results on queued client callbacks, and rebuilds only its current parent/detail. Errors retain the last confirmed view and stop writes. A fresh corrupt screen never displays a backup as current. **Recover backup**, then **Confirm recovery**, is separate from definition acceptance and may lose the latest action. A subsequent explicit refresh reconciles current definitions. Recovery preserves damaged data.

A lifecycle regression exposed the same narrow defect in both detail screens: MaLiLib's multiline builder string is a message/hint, not the initial editable value. Both screens now explicitly set the field value, so saved notes and unsaved drafts survive ownership-operation refreshes; Claim/Release never implicitly save a note. This is a minimal prerequisite fix, without a material-session rewrite.

The region fixture contains Region 1 through Region 62 with distinct original descriptors. Unseen regions are explicit synthetic test data. The five reference rows are completed through actual mouse input. Definition-change coverage deliberately republishes one test-only in-memory original origin and restores it; it never rewrites the schematic file. A separate long Japanese name fixture uses a synthetic unavailable player and remains independent of the 62-region work. FixtureWorlds isolates upstream per-world configuration, and only the owned 62-region placement is saved for the fresh process.

On Windows, 2026-10-05, the final `clean verifyPhase2 -PacceptMinecraftEula=true --console=plain` exited **0**, `BUILD SUCCESSFUL in 2m 4s`. All **31 core tests** passed with zero failures/errors/skips, both real client processes passed and the isolated dedicated server reported PASS. The preceding covering `:core:test :fabric:verifyClientRestart` passed in 1m 45s. Existing Phase 0/1/2A assertions remain in the gate, including the added material draft regression. No compiler warnings were suppressed; upstream OSHI/Realms/test-account messages and Gradle deprecation notices remain harness output.

Real clicks complete Region 2/6/8/9/11 to **5/62 (8%)**. Screenshot pixel checks verify green head overlays and red undo actions on those five rows. Tests retain separate AC07 assertions for an empty saved note, exactly one notice and the upstream modification explanation. They exercise Claim/Release, unsaved drafts, Japanese multiline notes, undo, Hide Done, expanded row hits, Configure, Placement and All ON/OFF, origin/rotation/mirror, search, archive/explicit acceptance, invalid capture, unavailable-other-assignee actions, long Japanese names at GUI scales 1–4 and 1280x720, corruption and deliberate recovery. Both reopen and fresh-process restore compare exact definitions/IDs/states/metadata and preserve material and schematic bytes. The application JAR contains the nested core library and excludes test code, test mixins, screenshots and logs.

Screenshots under `fabric/build/run/clientGameTest/screenshots/` include `phase2-regions-5-of-62`, `phase2-regions-show-info`, `phase2-regions-notice-coexist`, `phase2-regions-notice-after-note-delete`, `phase2-regions-archive`, `phase2-regions-definition-review`, `phase2-regions-japanese-name-scale-1` through `-4`, `phase2-regions-narrow-japanese-name`, `phase2-regions-explicit-recovery` and `phase2-regions-restart`. Numeric prefixes are runner-assigned. Hosted CI and independent review remain controller gates before merge.

Full font/density/color parity against the supplied screenshots remains Issue #4. Other upstream versions remain disabled. Networking, sharing, Unshare regions, inventory/stock scans, server permission enforcement and simultaneous multiplayer edits remain outside this local phase.
