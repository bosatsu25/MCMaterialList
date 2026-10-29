# Phase 3D multiplayer verification (Issue #8)

`verifyMultiplayer` runs the shared protocol across three separate Java processes:
- one dedicated server, without Litematica or MaLiLib;
- two real Minecraft clients, alpha and beta, which use distinct offline accounts.

`verifyPhase3` includes this task after the retained local, transport and UI gates. Like the other run tasks, it needs `-PacceptMinecraftEula=true`.

```bash
xvfb-run -a ./gradlew verifyPhase3 -PacceptMinecraftEula=true
```

## How the harness runs

The task takes the exact command lines that Loom builds for the `runMultiplayerServer`, `runMultiplayerAlpha` and `runMultiplayerBeta` run configurations, then starts them concurrently. It never executes those run tasks itself.

1. The server starts first. It reports `server-ready` once the shared authority has loaded.
2. Both clients then connect to `localhost` over a real socket.
3. The two clients meet at named marker files in `build/run/multiplayer/coordination`.
4. When alpha asks for a restart, the harness stops the server and starts a fresh server process on the same world. The new process uses a new port.
5. The task passes only if:
   - each client log contains `Multiplayer <role> all assertions PASS`;
   - neither client log contains a gametest failure;
   - the server log shows the isolation and readiness markers for both boots;
   - neither boot's class-load log contains `fi.dy.masa.`, `net.minecraft.client.` or `dev.mcmateriallist.fabric.client.`.

Console logs and class-load logs are written under `build/run/multiplayer` and uploaded by CI. All accounts and data are synthetic. The test mods, the mixin and the harness are excluded from the application JAR.

Every assertion reads one of two things: the socket-delivered state of the client making it, or a fresh authoritative snapshot fetched with a join. Two test-only hooks exist:
- A mixin can drop one armed decoded command result, which simulates a response lost after the authority saved it.
- The same mixin records every decoded delta before production filtering, so delivery after revocation can be detected.

## Scenario and acceptance criteria

| Step | Asserted on the real processes | Acceptance criteria |
| --- | --- | --- |
| Join | Beta discovers only the project it belongs to. Its join and forged raw command against alpha's other project are refused, and that project stays at revision 0 with empty rows. | AC-14, AC-17 |
| Synchronization | Alpha's completion reaches beta with the assignee, completion actor and revision. Beta's claim and note reach alpha. | AC-17 |
| Simultaneous claim | Both clients send a claim with expected version 0 at a shared barrier. Exactly one is OK. The other is DENIED (Member) or CONFLICT (Administrator). Both clients converge on the same assignee at row version 1. | AC-11 |
| Lost response, same connection | The delta arrives but the ACK is dropped. After a fresh snapshot, the exact replay returns the original outcome and revision, and no new revision appears. A stale command conflicts against the latest row without changing it. | AC-12, AC-13 |
| Lost response and disconnect | Beta loses an ACK, disconnects, and alpha edits meanwhile. On reconnect, beta's snapshot contains the offline edit, and its replay on the new connection returns the original outcome without executing again. | AC-12, AC-13 |
| Definition change | Beta's shared writes stop until it re-fetches. The re-fetched manifest and confirmed work match the authority. | AC-17 |
| Viewer edit | The client refuses to admit the edit. A gate-free resend is DENIED by the authority, and authoritative rows are unchanged. | AC-14 |
| Server restart | A new server process serves the same authoritative state to both clients: revision, manifest, roles, rows and reconfirmation markers. Alpha's replay of an operation from before the restart returns the original outcome and does not execute again. | AC-12, AC-17 |
| Revocation | Beta becomes REVOKED without private rows. No delta for alpha's later edit reaches beta's socket, and beta's join is DENIED. | AC-17 |

## Limits

- **Not externally authenticated.** All three processes run on one machine in offline mode with synthetic accounts. This is not external authenticated two-player evidence and not a deployment on the target server.
- **Network API only.** The clients drive the production `SharedClientNetworking` API directly, not the native Litematica screens. The native screens, the durable client journal and reconnect restoration in the screen controller are covered by `runSharedUiTest` and the core tests.
- **Save failure not covered here.** AC-12's save-failure presentation needs storage fault injection and is covered by the core authority tests.
- **ACK-before-delta ordering not covered here.** Reproducing that ordering across processes is not deterministic; it is covered by `SharedClientStateTest`.
