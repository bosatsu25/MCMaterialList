# Issue 2 Material List UI Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. The user explicitly authorized continuous Issue -> Branch -> PR -> CI -> Merge execution.

**Goal:** Add durable assignment, completion, notes and progress to real Litematica material rows without changing upstream quantities or operations.

**Architecture:** Each material screen owns its view of confirmed immutable work. The existing lifecycle-owned service performs material-only reconciliation and writes; UI callbacks publish results on the client thread. Narrow version-gated mixins add widgets and adjust columns/list height while retaining upstream rendering, sorting, counting, Ignore and export handlers.

**Tech Stack:** Java 25, Gradle 9.7.1, Fabric/Minecraft 26.2, Litematica 0.28.3, MaLiLib 0.29.2, JUnit and Fabric client GameTest.

**Spec:** `litematica-collaboration-design-v0.1.md`, sections 5–7 and material portions of AC-01/02/04/05/06/08/09/10/18; GitHub Issue #2.

## Global Constraints

- No networking or region UI in Issue #2; no writes to region datasets from material-only refresh.
- Completion, assignee, completion actor, counts and Placement ON/OFF remain independent.
- Registry IDs identify material rows; filtered views do not change progress denominators.
- Preserve Phase 0 diagnostics and Phase 1 verification, strict recovery and schematic byte invariance.
- No private note/UUID/path logging; skins use available player information with a fixed-size fallback and no added lookup service.
- Loading existing work is read-only; creating a missing dataset requires the explicit Track materials action.

## Review Focus

- A failed save or corrupt/future snapshot must not publish completion or silently initialize blank work.
- Reinitialization, sort, scroll and late I/O callbacks must not associate state with another screen/placement or stale row index.
- Upstream calculation in progress/canceled must disable mutations until a valid observation is ready.
- Long names and wide numeric columns must preserve separate head/item/count/action hit areas.
- Notes, assignment release, completed-by metadata and archived/changed definitions must remain independently visible.

### Task 1: Material-only persistence and presentation geometry

**Files:** `core/.../persistence/LocalWorkService.java`, new `MaterialRefreshResult.java`, new `core/.../ui/MaterialRowLayout.java`; corresponding core tests.

**Interfaces:** `refreshMaterials(WorkSnapshot): CompletableFuture<MaterialRefreshResult>`; result carries storage and reconciliation. `MaterialRowLayout.fit(width,totalWidth,missingWidth,availableWidth,ignoreWidth)` returns optional nonoverlapping row geometry.

- [x] Test that material-only refresh leaves regions missing/unchanged and preserves material state and strict failure results; observe failure before implementation.
- [x] Test action/count/name bounds at baseline, narrow widths and oversized counts; observe failure before implementation.
- [x] Implement material-only service without weakening full refresh; implement pure row geometry.
- [x] Run `:core:test` and commit the tested unit.

### Task 2: Durable material screen and row controls

**Files:** new `client/material/` screen session, row/detail/head helpers; material GUI/row/list mixins; client mixin registration; English/Japanese translations; gametest screen accessors/tests.

**Interfaces:** A per-screen session loads/reconciles/recover/updates through TaskCommand; rows resolve state by registry ID. List height changes through the same session's Show Info state; confirmation results update the screen and list together.

- [x] Add real client assertions that Track materials, completion/undo, head/detail Claim/Release/notes, information notice and Show Info have intended durable effects; observe missing-feature failure.
- [x] Add version-gated toolbar/footer and row controls; preserve upstream draw/count/Ignore/sort handlers and original diagnostic.
- [x] Display pending/readiness/storage failure and explicit recovery confirmation; no optimistic completion. Surface archived and definition-changed work.
- [x] Verify client compilation, real mouse events and prior diagnostics; commit the tested unit.

### Task 3: Acceptance fixture, regression and hosted gate

**Files:** new Phase 2A client fixture/restart suite, Gradle `verifyPhase2`, CI composition, `docs/phase-2-litematica-ui.md` and usage README.

- [x] Build a real upstream-counted 19-material fixture; complete rows 3/4/10/11 and assert 4/19 (21%), red undo actions and completion overlays, Missing=Total/Available=0, independent notes/counts and unchanged placement flags.
- [x] Verify sorting/Ignore/Hide Available/Refresh/search retain identity/progress, Show Info height/hit alignment, fixed-size unavailable skins, long names, exports and fresh-process restoration.
- [x] Capture 1920x1080 screenshots and relevant scale evidence; original fixtures remain regression coverage, not replacements.
- [x] Run targeted tests then `clean verifyPhase2 -PacceptMinecraftEula=true`; inspect JAR, diff and docs.
- [x] Obtain a fresh independent branch review as required by the execution skill; fix material issues with regression tests.
- [ ] Commit/push, create PR with `Closes #2`, attach it, wait for hosted green CI and mergeability, then squash merge, update main and parent checklist. Do not mark other Phase 2 Issues complete.

## Execution record

- Source recovery: PR #18 merged and Issue #17 closed; restored source matches the completely read PR blob. The Phase 0/1 reports and all 18 design acceptance criteria were read and mapped to the roadmap. No product-design contradiction was found for Issue #2.
- Task 1: test-first compilation failed on the missing refreshMaterials API and MaterialRowLayout; after implementation `:core:test` exited 0, 28 tests with no failures/errors/skips. This is core evidence; client/hosted gates remain pending.
- Task 2: the real nineteen-material test initially timed out waiting for the missing Track materials control. Durable screen/row/detail controls, queued client publication, session filters/notices and explicit recovery were then implemented. No region control or product networking was added.
- Task 3: final Windows clean verifyPhase2 exited 0 in 1m47s: 28 core tests, original Phase 0/1 suites, Phase 2A real UI and exact fresh-process snapshot restoration, build/isolation and dedicated server all passed. JAR/diff/docs were inspected. Independent review issues were fixed and re-reviewed. Hosted PR/merge remains the final workflow step.
