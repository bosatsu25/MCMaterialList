# Issue 3: Phase 2B region collaborative UI

## Goal
Implement Issue #3 only, preserving Phase 0/1/2A. The authoritative product specification is `litematica-collaboration-design-v0.1.md`; read it completely together with `docs/phase-0-compatibility-spike.md`, `docs/phase-1-local-work-state.md`, and `docs/phase-2-litematica-ui.md`.

## Global Constraints
- One Issue per PR. No networking, Unshare regions, inventory scanning or Phase 3 work.
- Material and region datasets remain separate. No region operation writes or initializes materials.
- Completion does not alter material counts, placement flags, sub-region enable/render flags or `.litematic` bytes.
- Keep existing Configure, Placement ON/OFF, All ON/OFF, modification notices, origin, rotation and mirror behavior.
- Region identity uses persisted RegionTaskId and exact original schematic key/origin/signed size. Display order, translation and mutable placement transforms are not identity.
- Ambiguous definition changes do not inherit old work. Present archived state and deliberately acknowledge only the new default definition. Repeated unchanged observations retain identity.
- Local storage remains disk-authoritative, serialized, CAS/atomic/backup protected. Only confirmed results publish on queued client callbacks. Errors never present a backup as current or blank corrupt data.
- No skin lookup service, new network calls, telemetry, private data logging or local machine paths in tracked files. Missing player data uses fixed-size fallback. Synthetic test identities are test-only.
- Java 25 without preview, reflection, unchecked casts or warning suppression. Run configured Gradle/JDK. Preserve all prior tests, with meaningful public behavior coverage.
- No ignored/generated/cache/tool files staged. No history rewrite, unrelated change removal, branch deletion or remote changes.

### Task 1: Complete durable region UI and its acceptance verification

**Integration decisions:** Extend the actual `GuiPlacementConfiguration`, `WidgetListPlacementSubRegions` and `WidgetPlacementSubRegion` through pinned version-gated mixins. A region-owned session may reuse small material presentation helpers; do not broadly rewrite working material code. Add a region-only capture and region-only persistence refresh, independent of material readiness. Add a serialized deliberate acknowledge operation over the existing pure region dataset acknowledgement, with typed invalid/recovery/closed outcomes and no old-state transfer. Use existing exact identity/domain transitions rather than parallel models.

**Expected behavior:** Opening reads only. Track regions explicitly creates missing region work; Refresh work reconciles current original schematic definitions. Rows have fixed heads with separate done overlay, upstream L icon, clamped names, notices, complete/undo, Configure and Placement controls. Claim/Release and explicit UTF-8 multiline note save follow existing local transition rules and expose completion actor/time. Show Info updates draw and hit geometry; Hide Done only filters the view. Progress counts all current tracked regions, including disabled upstream placements, with floor percentage. Errors/pending/readiness/review block writes. Parent/detail lifecycle and draft preservation must match material behavior. Explicit archive view and review acceptance must never guess identity. Backup recovery remains deliberate and separate from accepting a changed definition.

**Implementation steps:**
1. Write failing unit tests for region-only refresh preserving material bytes/missing status, strict failures and closure, and acknowledgement persisting across restart without transferring archived state; run RED, implement minimal service/capture changes, run GREEN.
2. Add failing real client assertions for region Track/complete/undo/head/detail/Hide Done/Show Info before implementing the UI. Use upstream source at pinned versions to confirm hooks. Keep existing diagnostic footer and upstream selection hit areas unobstructed. Geometry must preserve standard modification notice, Configure and Placement actions; narrow screens must not overlap controls.
3. Build an upstream 62-region fixture with names Region 1 through Region 62 and distinct original descriptors. Complete Region 2, 6, 8, 9 and 11 by real clicks and assert 5/62 (8%), red undo actions and overlays. Assert all placements stay enabled. This is explicit synthetic test data; do not infer reference screenshot players.
4. Test real Claim/Release and Japanese multiline notes, undo, Hide Done, Show Info row height/hits, upstream search/order/Configure/toggle/All OFF/ON/transforms and notices without denominator/identity drift. Test an unavailable player, long Japanese names and GUI scales 1-4. Test region definition mismatch -> default blocked work plus archived old work -> explicit accept new definition, with no automatic transfer. Preserve material snapshot bytes during these operations.
5. Use the established FixtureWorlds helper to isolate upstream per-world configuration. Save only the owned fixture. Fresh-process restore compares exact region dataset identity, states and metadata, 5/62, read-only opening, material bytes and schematic bytes. No timeout extension or weakening prior Phase 0/1/2A assertions.
6. Update verifyPhase2 to compose the new region suite, keeping prior gates; update CI artifact evidence, README usage/version and `docs/phase-2-litematica-ui.md` with actual observed results and limitations. Full screenshot parity remains Issue #4; do not claim it here.
7. Run targeted core and client restart verification, then `clean verifyPhase2 -PacceptMinecraftEula=true --console=plain` with JDK 25. Inspect actual XML counts, screenshots, application JAR contents, git diff/check and docs. Self-review and commit only tracked source/docs/tests/config files.

**Verification expected:** A clean configured Gradle build exits 0; all existing and new core tests, both real client processes, supported upstream controls and isolated dedicated server pass. No client/optional mod dependency leaks into core/common/server. Report the real commands/counts/elapsed results and screenshots, never predicted or inherited results.

**Handoff:** The implementation agent must not create PRs, push, merge, delete branches or spawn subagents. It may commit reviewed task changes on the supplied Issue #3 feature branch, as authorized by the user's Issue -> branch -> PR workflow. Return DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED with commits, one-line test evidence and concerns; write a full report to the supplied report file. Root coordinates independent task/final review and hosted PR/CI/merge. The Issue is not done before that gate.

## Execution ledger

- Base: 24e6053be0ded04a3e92ecc3f2eef0fd9167d7b9. PR #19 merged after both checks succeeded; main was clean before the Issue #3 branch was created.
- Preflight: one coherent integration task; no inter-task pair exists and no conflicting contracts were found.

| Task 1 producer | Consumer | Inspected contract |
| --- | --- | --- |
| Region-only capture and serial refresh/acknowledge results | Region-owned screen session | Exact original descriptors and persisted task IDs; successful durable results publish on queued client callbacks; materials remain independent. |
| Pinned screen/list/row geometry hooks | Real mouse-hit assertions | Draw, height, spacing and hit geometry agree; Configure, Placement, selection and diagnostic footer retain their handlers. |
| Isolated 62-region fixture and confirmed snapshots | Fresh-process restore and byte checks | Restore the owned placement only; exact IDs/definitions/state/metadata, 5/62, material bytes and schematic bytes agree; opening is read-only. |

- Ruling: task scratch/reports/review packages live outside the repository; the execution ledger stays in this tracked plan. This follows the user's restriction against modifying ignored local tool files. The skill's workspace scripts are replaced by equivalent extraction/diff capture. Cost if wrong: manual recovery needs this ledger and report references.
- Task 1: implementation verified and ready for independent review. Root owns independent review and hosted CI/merge; Issue completion still requires those controller gates.
- Observed RED: core compilation rejected missing region-only refresh/acknowledge APIs; first real region test failed with `Missing button: Track regions` after prior client assertions. Minimal core implementation passed the complete core suite.
- The first basic real region process passed Track, five reference completions, claim/release, Japanese multiline notes, undo, Hide Done and expanded task hits. The extended covering process subsequently passed combined upstream modification/note notices and deletion, upstream controls, archive and explicit definition acceptance. Lifecycle/recovery/scale/restart checks are part of the final gate.
- Lifecycle RED exposed an existing shared pattern bug: pinned MaLiLib `GuiTextFieldMultiLine.Builder.build(font, string)` passes the string as the message/hint and never initializes the editable value. A repeated Claim discarded the region note draft (observed value length zero). Use explicit `setValueWrapper(draft)` in the region detail and the same minimal material prerequisite, with both regressions retained. No broad material refactor is required.
- A separate material draft regression failed before that prerequisite fix. Both detail screens now initialize editable values explicitly; subsequent covering runs passed both draft-preservation assertions.
- AC07 diagnosis separated editable note deletion, durable empty note, one notice and retained upstream explanation. The Fabric test helper emits key events with zero modifier flags even when Control is held, so its Ctrl+A did not select the note. Actual End/Backspace input clears the note and all three saved-state assertions pass. This is a test-input limitation, not a production notice failure.
- Scale verification initially included upstream Ignore entities at the toolbar's y-coordinate. Identify region controls by their full hover labels, assert the expected count and retain sidebar bounds checks; upstream geometry is unchanged.
- Final covering GREEN: `:core:test :fabric:verifyClientRestart --console=plain`, exit 0 in 1m 45s. Both real clients retain all Phase 0/1/2A assertions and pass complete region interactions and exact restart/byte checks.
- Final clean GREEN on Windows, 2026-10-05: `clean verifyPhase2 -PacceptMinecraftEula=true --console=plain`, exit 0 in 2m 4s. XML totals 31 tests, zero failure/error/skip; both clients, dedicated-server isolation, common/core bytecode isolation and build pass. Application JAR inspection confirms nested core and excludes test code/mixins/screenshots/logs. No compiler warnings were suppressed.
- Self-review checked pinned hook signatures, exact original descriptor mapping, queued confirmed publication, material independence, preserved upstream handlers, draft lifecycle, AC07, archive/review/recovery separation, read-only reopen and fresh-process metadata equality. Full screenshot parity remains Issue #4; other mod/version/OS combinations and extreme resolutions are unverified.

## Fix round 1/5: repeated recovery confirmation

- Independent review found that successful recovery never consumed `recoveryConfirmed`; a later corruption in the same session could skip the fresh warning and restore on the first click. The analogous material session path is also confirmed.
- Ruling: repair the same confirmation lifecycle in both material and region sessions because deliberate recovery is a shared preservation requirement and the analogous material defect was confirmed. Use minimal changes and real same-session second-cycle tests. Cost if wrong: this PR includes a small material prerequisite and additional regression work beyond the new region UI.
- Coverage retains all earlier assertions and adds real second-incident refresh/recovery clicks, unchanged damaged bytes after the first step and exact confirmed-state equality after explicit confirmation in both suites.
- Material RED: `:fabric:runClientGameTest --console=plain`, exit 1 in 1m 7s; the second recovery's first click overwrote the damaged primary. After only the material fix, region RED independently failed the equivalent assertion, exit 1 in 1m 19s; the material second-cycle assertions passed in that run.
- Fix: consume confirmation before submitting each recovery attempt and clear it when published status leaves `RECOVERY_REQUIRED`, identically in both sessions. Serial persistence, queued publication, pending/readiness guards and backups are unchanged.
- GREEN on 2026-10-05: `build :fabric:verifyClientRestart --console=plain`, exit 0 in 1m 48s. Both real processes and all existing assertions pass; build/isolation and updated JAR audit pass. Unchanged core tests reuse the prior 31-test result. A second region confirmation is followed by the existing explicit refresh contract because a previous-good backup can precede current original-definition reconciliation. Scoped re-review remains required.
- Deferred minor: repeated full region captures in per-row render checks; #12 owns measured performance work.
- Deferred minor: disclosed upstream/harness OSHI/option/Realms/Gradle noise; #14 owns compatibility/harness audit. No warnings suppressed.
