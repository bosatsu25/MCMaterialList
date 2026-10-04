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
2. [ ] Persistence (`core/.../persistence`): explicit codec and bounded, contained local store. Write failing tests for Japanese round trips, independent datasets, corrupt/future schema, backup recovery, symlinks, interrupted writes and stale generations; implement, run `:core:test`, commit.
3. [ ] Adapter/service (`fabric/src/client/.../work`): registered saved-placement contract, real upstream rows/regions and a lifecycle-owned executor. Shift-click diagnostic uses actual snapshots and typed errors; ordinary Phase 0 click remains. Write real integration test first, compile/run it, implement and verify, commit.
4. [ ] Restart verification/docs: three regions and multiple materials, mutate one of each, reload in a fresh process, assert IDs/state/counts/placement/schematic invariants. Preserve verifyPhase0 and add verifyPhase1; run clean full gates, independent review, documentation and final commit.

## Execution evidence

Baseline: master, clean, HEAD 1d0d46bee1d22788de94c84708d03960d942eca7. Feature branch requested explicitly. Unit test output and runtime worlds remain in ignored build directories or temporary logs; execution progress is recorded here rather than creating local tool scratch files.

Task 1: observed missing domain API compilation failure; implemented immutable domain; `:core:test` exit 0 (10 tests). No Minecraft dependency introduced.
