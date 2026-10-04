# Phase 2A: Material List collaborative UI

Issue #2 extends the supported Litematica material screen with durable **local** work. The authoritative root design was restored by PR #18, closing #17. Region UI belongs to #3; full two-screen screenshot parity belongs to #4. Shared project/protocol/permission work remains Phase 3.

## User behavior

Opening a material screen reads existing work without writing. **Track materials** explicitly creates a missing material dataset. **Refresh work** reconciles a completed upstream material observation. Unregistered, temporary, duplicate-identity, canceled or still-counting placements cannot submit commands.

Rows retain this order: assignee face, upstream item, material name, Total, Missing, Available, notice, completion action, upstream Ignore. A green check means mark completed; a red X means undo. The face gets a separate completion overlay. Missing/Available, notes, assignment and placement enable/render flags remain independent. Unknown players use a fixed 16px fallback without an added lookup service.

Click the face/notice for Claim/Release, a multiline note and completion actor/time. Claim/Release respect the existing local ownership transition rules and explain disabled actions; the local model does not provide server permissions. Notes have a 4096-byte UTF-8 limit and save only through **Save note**. Show Info changes both row drawing and list hit/space geometry. Hide Done, upstream search/sort/Ignore/Hide Available affect the view, not the denominator. Empty work uses 0/0 (0%).

Progress counts all current tracked material types and floors the percentage. Upstream Refresh reconciles work after counting completes. Changed quantities retain ownership/completion/notes and show a session notice, even with Show Info OFF. The notice remains across repeated refreshes in that screen; this is not a persisted notification history or acknowledgement system. Removed definitions remain in read-only **Archived work** and restore by the same registry identity.

Storage work uses the existing serial I/O service and strict atomic snapshot store. Buttons cannot publish optimistic completion. A failure retains the last confirmed view, disables editing and displays a typed status. A newly opened corrupt dataset never presents its backup as current. **Recover backup** first explains possible loss of the latest action; **Confirm recovery** explicitly restores the previous-good snapshot while preserving damaged data. Refresh work reloads a conflict; future-schema and corrupt data never initialize blank work.

Only the material snapshot is used by these controls. The Phase 0 footer and Phase 1 Shift-click diagnostic remain available; that separate diagnostic intentionally refreshes both datasets. No work is written into `.litematic`. No product networking, telemetry, stock scanning or region controls were added.

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

`verifyPhase2` includes the unchanged Phase 0/1 regression gates, real material UI tests in two Minecraft processes, build/core/isolation checks and the dedicated-server smoke test. The second process uses the first process's configuration directory and reads the actual durable snapshot; no snapshot is copied into a substitute store. Test fixtures/screenshots/exports remain ignored build outputs, excluded from the distribution.

The real fixture uses 19 distinct upstream-counted materials. Its first eleven item types match the reference order; the other eight and small counts 19 through 1 are explicit synthetic test data. Rows 3/4/10/11 complete to **4/19 (21%)**, with Missing=Total and Available=0. Tests exercise real clicks and Japanese multiline keyboard input, claim/release, undo, Hide Done, sort/Ignore/Hide Available, Refresh, text/raw exports, definition-change/archive restoration, four GUI scales, a long synthetic Japanese name, unavailable-player skins and explicit backup recovery. Definition-change tests deliberately republish upstream material observations; they do not rewrite a schematic file or introduce a replacement counter.

The failure test damages a test-only primary snapshot immediately before a completion click: the last confirmed state must remain, the damaged bytes must survive, and recovery must require two explicit actions. A deterministic already-completed-future test covers non-reentrant initialization. The fresh process compares exact dataset identity, definitions, states and metadata, verifies 4/19 and checks read-only reopening and unchanged schematic bytes.

Screenshots are under `fabric/build/run/clientGameTest/screenshots/`, including `phase2-materials-4-of-19`, `phase2-materials-show-info`, `phase2-japanese-name-scale-1` through `-4`, `phase2-explicit-recovery` and `phase2-materials-restart` (numeric prefixes are assigned by the test runner).

On Windows, 2026-10-04, the final `clean verifyPhase2` exited **0**, `BUILD SUCCESSFUL in 1m 47s`. All **28 core tests** passed with no failures/errors/skips; two real client processes and the isolated dedicated server passed. The final UI tests also exercised actual header sorting and keyboard search without changing 4/19. The application JAR includes the nested core library and excludes gametest/server-smoke code, test mixins, screenshots and logs.

Independent review found reentrant initialization, header-arrow clearance and hidden detail rejection feedback issues. All three were corrected with meaningful regression coverage and re-reviewed; no further required correction remained. No compiler warnings were suppressed. Upstream OSHI/Realms/test-account messages and Gradle deprecation notices appeared during the harness.

Hosted CI uses the same `verifyPhase2` gate and uploads evidence; its result is verified on the PR before merge. Other versions remain disabled; other mod combinations and macOS are not verified by this change.
