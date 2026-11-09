# Platform verification and hosted CI (Issue #13, Phase 5B)

This page records which operating systems MCMaterialList 0.1.0 has evidence for, what hosted CI runs, and what is not verified. It was written on 2026-11-09 from the repository's build files, workflow, source and earlier phase records. Nothing below claims support that no run has shown.

## Summary

| Platform | Status for 0.1.0 | Evidence |
| --- | --- | --- |
| Linux (hosted GitHub Actions `ubuntu-24.04`, x86-64, Microsoft OpenJDK 25, `xvfb-run`) | **Verified.** Every gate in `verifyRelease` runs on every push and pull request. | `.github/workflows/phase-0.yml`; per-run artifacts `phase-4a-test-evidence` and `application-jar` |
| Windows | **Partly verified, older builds only.** The owner ran the Phase 0-2 gates locally on Windows (Phase 0: Windows, AMD Radeon RX 7700 XT, OpenGL). No Windows run of the Phase 3 multiplayer harness, Phase 4 gates or this release build is recorded. | [phase-0-compatibility-spike.md](phase-0-compatibility-spike.md) (environment table), [phase-1-local-work-state.md](phase-1-local-work-state.md) ("Observed on Windows, 2026-10-04"), [phase-2-litematica-ui.md](phase-2-litematica-ui.md) (Windows `clean verifyPhase2` runs on 2026-10-04 and 2026-10-05). [phase-3-shared-work.md](phase-3-shared-work.md) records a local `clean verifyPhase3` with PowerShell commands but does not name the OS, so it is not counted here. |
| macOS | **Not verified.** No run on macOS has been made, locally or hosted. | None. See [macOS feasibility](#macos-feasibility) for what is expected to work and what is unknown. |

Supported baseline (only this combination is tested): Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.0+26.2, Litematica 0.28.3, MaLiLib 0.29.2, Java 25. `fabric.mod.json` declares `java >=25` as the loader minimum; only Java 25 has been tested. See [compatibility.md](compatibility.md).

## What hosted CI runs

The workflow `.github/workflows/phase-0.yml` has one job, `compatibility`, on `ubuntu-24.04` with a 45-minute limit:

1. `./gradlew --no-daemon --warning-mode all clean build`. This compiles `core` and the Fabric `main`/`client` source sets, runs the core JUnit suite with the JaCoCo report, and runs `check` (`verifyIsolation`, `verifyTranslations`).
2. `xvfb-run -a ./gradlew --no-daemon --warning-mode all verifyRelease -PacceptMinecraftEula=true`. `verifyRelease` (root `build.gradle`) depends on:
   - `verifyPhase4`, which keeps every Phase 0-4 gate:
     - `build`;
     - `:fabric:verifyClientRestart` (two real client processes, including the Issue #22 product-entry markers and the Issue #34 Phase 5G singleplayer markers);
     - `:fabric:verifyServerSmoke` (dedicated server without Litematica/MaLiLib, class-load audit);
     - `:fabric:verifySharedTransport` (socket transport; also depends on `verifyIsolation` and `verifyApplicationJar`);
     - `:fabric:runSharedUiTest` (native shared UI, including the Issue #34 server-save-failure marker);
     - `:fabric:verifyMultiplayer` (two real clients against a separate dedicated-server process, restarted once);
     - `:fabric:verifyLocationFiles`, `:fabric:verifyStockScan`, `:fabric:verifyUnshare` and `:fabric:verifyPerformance`;
   - `:fabric:verifyApplicationJar`: the distribution JAR carries the MIT `LICENSE` and `THIRD_PARTY_NOTICES.md`, nests only this project's core (with its own MIT license), has no Gson, no test or optional-mod classes, no logs or screenshots, and its `fabric.mod.json` has id `mcmateriallist`, license `MIT` and version equal to `mod_version`. It reads the archive from `tasks.jar.archiveFile`, so it follows the JAR name when the version changes.
   - `:fabric:verifyDependencyLicenses`: the license of each pinned dependency, read from that artifact's own `fabric.mod.json`, matches `THIRD_PARTY_NOTICES.md`.
   - `:fabric:verifyTranslations`: `en_us.json` and `ja_jp.json` have the same keys and the same number of `%s` arguments per key.

   `verifyApplicationJar` and `verifyTranslations` already ran through `verifySharedTransport` and `check`. `verifyRelease` names them anyway, so the release gate still holds if a phase gate changes its dependencies later. No gate was removed or relaxed.
3. Upload `phase-4a-test-evidence` (always): core JUnit XML, the JaCoCo report, screenshots and the logs of every gate.
4. Upload `application-jar` (only when every step passed): `fabric/build/libs/fabric-<version>.jar`, without the sources JAR. For 0.1.0 this is `fabric-0.1.0.jar`, the file that passed `verifyApplicationJar` on that exact commit.

A release is cut only from a commit whose run of this workflow is green.

## macOS feasibility

No macOS run has been made, so this is a reading of the setup, not a test result. It explains what would have to be shown before macOS could be listed as verified.

### What is expected to be portable

- **Core library and its tests.** `core` is plain Java 25 with Gson. Durable writes use a temporary file, `FileChannel.force(true)` and `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` in the same directory (`LocalDatasetStore`, `SharedClientStore`, `SharedProjectStore`, `LocationFiles`); locks use `FileChannel.tryLock()`. On APFS and HFS+ the JDK implements these with `rename(2)` and `fcntl` locks, which are atomic within one volume. An `AtomicMoveNotSupportedException` is not silently ignored: every store maps it to `ATOMIC_REPLACE_UNAVAILABLE` and does not write. The symbolic-link tests skip themselves with a JUnit assumption where links cannot be created. No core test uses `/proc`, POSIX permission APIs or an OS check.
- **Gradle harness code.** `verifyMultiplayer` picks free ports with `ServerSocket(0)` on the loopback address, starts processes with `ProcessBuilder` and writes coordination files with an atomic move in one directory. None of this is Linux-specific.

### What is unknown or would need changes

- **Display for client gametests.** Every client gate (`runClientGameTest`, `runClientGameTestRestore`, `sharedTransportTest`, `sharedUiTest`, `multiplayerAlpha`/`Beta`) opens a real Minecraft window. On Linux CI that window comes from `xvfb-run`: the workflow wraps the Gradle call, and `verifyMultiplayer` adds `xvfb-run --auto-servernum` only when `DISPLAY` is unset and `/usr/bin/xvfb-run` exists. macOS has no Xvfb. The clients would need a logged-in GUI session and an OpenGL context. Whether a hosted macOS runner provides a usable OpenGL context for Minecraft 26.2 has not been tried.
- **GLFW main thread.** On macOS, GLFW must run on the first thread (`-XstartOnFirstThread`). Fabric Loom's client run configurations are expected to add this flag on macOS. `verifyMultiplayer` builds its client command lines from the run tasks' own JVM arguments, so it would inherit the flag. This has not been observed in this project.
- **Workflow.** The current job is Linux-only: it calls `xvfb-run` directly. A macOS job would need its own step without `xvfb-run`, and the 45-minute limit would have to be re-measured. For reference, the whole Linux job (`clean build` plus `verifyPhase4`) took about 11.5 minutes in run 42673078194, attempt 2.
- **Durability semantics.** On macOS, `FileChannel.force(true)` issues `fsync`, which does not force the drive's write cache the way `F_FULLFSYNC` does. Crash consistency (never a torn file, thanks to the atomic rename) is expected to hold. Durability of the last write after a power loss is weaker than on Linux. This is a property of the JDK on macOS and is not tested.
- **Case-insensitive file systems.** APFS is case-insensitive by default. MCMaterialList names its own files by lowercase UUIDs and fixed names (`materials.json`, `regions.json`, `current.json`), so no collision is expected. This is a reading of the code only.
- **Apple Silicon natives.** Hosted macOS runners are mostly arm64. Whether Loom resolves working macOS arm64 LWJGL natives for this project's run tasks has not been tried.

### Conclusion

macOS is **not verified** for 0.1.0. The pure-Java parts give no specific reason to expect failure, but the client and multiplayer gates have never run there, and their display requirement is unproven on a hosted macOS runner. README and the release notes list macOS under "not verified". Adding a macOS job is follow-up work and is not part of this release.

## Infrastructure note: zero-job workflow failures on 2026-11-09

On 2026-11-09, two runs of this workflow for the same head (`22d17ac`, branch `claude/dreamy-johnson-ltxuxo`, Issue #12) ended as **failure with no job started**:

| Run | Event | Attempt | Created (UTC) | Jobs | Conclusion |
| --- | --- | --- | --- | --- | --- |
| [42673023551](https://github.com/bosatsu25/MCMaterialList/actions/runs/42673023551) | push | 1 | 18:19:26 | 0 | failure |
| [42673078194](https://github.com/bosatsu25/MCMaterialList/actions/runs/42673078194) | pull_request | 1 | 18:19:49 | 0 | failure |
| [42673078194](https://github.com/bosatsu25/MCMaterialList/actions/runs/42673078194) | pull_request | 2 (re-run) | - | 1 (`compatibility`) | **success**; job 19:11:48-19:23:20 |

- **Cause.** No job, step or log existed, so no project code ran. A YAML or workflow error would also fail with no job, but the same unchanged workflow passed on the re-run of the same commit. The failure is therefore attributed to GitHub Actions infrastructure, not to the project.
- **Handling.** Re-run the workflow. Do not merge or release on a run without jobs. Do not count it as a pass or as a test failure.
- **Telling it apart from a real failure.** A real failure always has the `compatibility` job with a failed step and logs. A zero-job run has an empty job list (`gh run view <id> --json jobs`).
