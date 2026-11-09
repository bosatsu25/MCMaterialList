# Development workflow

This page records how changes reach `main`. It was written for #35 on 2026-11-09.

## One Issue, one pull request
- Each Issue gets its own pull request. A pull request may close more than one Issue only when they are documentation or housekeeping changes that depend on each other (for example #33 and #35).
- A pull request is merged only when all of these hold:
  - hosted CI is green on its exact head;
  - the Codex review has finished;
  - every finding is fixed, or answered on its thread with the reason it stays.
- Never merge with a failing check. Never skip, disable or loosen a test to get green.

## Branches
- **One branch per Issue.** Start from the latest `main`, and delete the branch after its pull request is merged.
- **When a branch is reused.** If one long-lived branch has to carry several pull requests one after another, pass an explicit commit message when you squash-merge. Otherwise GitHub fills the squash message with every earlier commit still on the branch, which is what happened in the squash commit of PR #32.
- **Superseded design branches.** These are brought into `main` as records marked "not adopted", then deleted. For example, the Phase 3C design from `docs/issue-7-phase-3c-design` now lives under `docs/superpowers/`.

## Building
- **Core module.** Pure Java, so it can be built and tested anywhere with JDK 25.
- **Fabric module.** It needs the Minecraft and Fabric Maven hosts. In an environment that blocks them, hosted CI is the only compiler.
  - Use only upstream APIs that already have a usage in the repository.
  - Expect the first CI run to be the first compile.

## Version
- `mod_version` in `gradle.properties` stays at the intermediate `0.1.0-phase.3a` until the release preparation in #15. That step sets the release version, and the JAR name, `fabric.mod.json`, README and release notes all follow it.
- Do not change the version in a feature pull request.
