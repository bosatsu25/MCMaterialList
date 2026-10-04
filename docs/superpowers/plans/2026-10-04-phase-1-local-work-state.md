# Phase 1 Local Work State Implementation Plan

> **For agentic workers:** Use test-driven implementation task by task; preserve Phase 0 and perform an independent final review.

**Goal:** Persist and reconcile real Litematica material/region work state across client restart.

**Architecture:** Immutable Minecraft-independent domain records and explicit typed transitions in core. Independent material/region datasets use versioned JSON snapshots, controlled UUID paths, atomic replacement and a previous valid backup. A client-owned serial I/O service accepts immutable adapter snapshots; the existing button gains an opt-in diagnostic through Shift-click.

**Tech stack:** Existing pinned Minecraft/Fabric/Litematica/MaLiLib/Java/Gradle/Loom baseline; JUnit and Gson for JSON only.

**Spec:** User's Phase 1 implementation request and supplied collaboration design v0.1, read in full from the original attachment. The design file is not present in the repository.

## Constraints and review focus

- No Phase 2 controls, networking, server project state, or schematic writes.
- Separate dataset UUIDs; placement UUID remains upstream getHashId().
- Region identity uses exact name/relative origin/signed dimensions. Rename/move/ambiguous matches require explicit reevaluation; removed states remain archived.
- GUI filtering never changes the tracked material source (getMaterialsAll).
- Corruption, unknown schemas, symlinks/path escape, stale writes and interrupted replacement must not silently erase work.
- Keep I/O off the render thread and snapshots free of Minecraft objects. Player UUIDs and notes are persisted only as required work state, never logged.

## Tasks

1. [x] Domain (`core/.../work`): IDs, definitions, TaskState, TaskCommand/TransitionResult, Progress, immutable datasets and reconciliation. Write failing tests for transitions, identity/order/count changes, conservative region mapping, removal/restoration and example percentages; implement, run `:core:test`, commit.
2. [x] Persistence (`core/.../persistence`): explicit codec and bounded, contained local store. Write failing tests for Japanese round trips, independent datasets, corrupt/future schema, backup recovery, symlinks, interrupted writes and stale generations; implement, run `:core:test`, commit.
3. [x] Adapter/service (`fabric/src/client/.../work`): registered saved-placement contract, real upstream rows/regions and a lifecycle-owned executor. Shift-click diagnostic uses actual snapshots and typed errors; ordinary Phase 0 click remains. Write real integration test first, compile/run it, implement and verify, commit.
4. [x] Restart verification/docs: three regions and multiple materials, mutate one of each, reload in a fresh process, assert IDs/state/counts/placement/schematic invariants. Preserve verifyPhase0 and add verifyPhase1; run clean full gates, independent review, documentation and final commit.

## Execution evidence

Baseline: master, clean, HEAD 1d0d46bee1d22788de94c84708d03960d942eca7. Feature branch requested explicitly. Unit test output and runtime worlds remain in ignored build directories or temporary logs; execution progress is recorded here rather than creating local tool scratch files.

Task 1: observed missing domain API compilation failure; implemented immutable domain; `:core:test` exit 0 (10 tests). No Minecraft dependency introduced.

Task 2: missing store API compilation failure observed, then passing local persistence and explicit dataset command tests. Strict UTF-8/schema parsing, generation compare-and-set, atomic replacement and explicit backup recovery implemented. Work-state player UUIDs/notes are deliberately persisted as requested, never logged.

External checkout change: another operation added commit fa02e1c, an origin remote and renamed the feature branch to main while implementation was active. Domain commit 0692be2 landed on that main; no external changes were undone. Recreated feature/phase-1-local-work-state from the current head and continued; no push performed.

Task 3: missing adapter API compilation failure observed before implementation. Actual upstream aggregate counts and three region definitions feed immutable snapshots; client-owned service and both real Shift-click diagnostics passed. Queued/uninitialized/canceled lists and unregistered placements are rejected. Initial rendering-disabled fixture gave zero upstream rows; normal enabled rendering produced three rows, without replacing the counter.

Task 4: independent review found archived pending-review loss and readiness acceptance after cancellation. Both were fixed, tested and re-reviewed with no new required fix. `clean verifyPhase1 -PacceptMinecraftEula=true --console=plain` exited 0, `BUILD SUCCESSFUL in 1m 36s`, 24 core tests with zero failures/errors/skips, Phase 0 regressions, both actual client processes, exact state restoration and dedicated-server class-loading isolation all passing. Distribution excludes test code/resources. Full technical evidence and Phase 2 readiness are recorded in `docs/phase-1-local-work-state.md`. No push/PR/merge or Phase 2 work performed.
