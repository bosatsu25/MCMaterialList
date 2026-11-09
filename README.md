# MCMaterialList

Litematica の素材リストと配置設定の画面に、素材集め・領域ごとの建築の「作業状態」（担当、完了、メモ、進捗）を追加する Fabric MOD です。ひとりで使うローカル作業と、MOD を導入したサーバーでの共有作業に対応します。

A Fabric mod that adds work state (claim, done, notes, progress) for material gathering and region building to Litematica's material list and placement configuration screens. It works locally on its own and can share work on a server that runs it too.

**Version 0.1.0** (the first release). License: [MIT](LICENSE). Changes: [CHANGELOG.md](CHANGELOG.md), release notes: [docs/release-notes-0.1.0.md](docs/release-notes-0.1.0.md), acceptance record: [docs/acceptance-0.1.0.md](docs/acceptance-0.1.0.md).

> **未確認の項目 / Not verified in 0.1.0**
>
> 0.1.0 は次の確認をせずに公開しています（所有者の判断）。これらは「合格」ではなく「未確認」です。 / 0.1.0 was released without the checks below (owner's decision); they are open, not passed.
>
> - **認証済みの実プレイヤーによる確認は未実施です。** マルチプレイの自動テストは、1 台のマシン上でオフラインの合成アカウント 2 つと専用サーバーを使ったものです。 / Not tested with authenticated real players. Multiplayer tests use two offline synthetic accounts and a dedicated server on one machine.
> - **対象サーバー「たいち鯖」への導入・確認は未実施です。** / Not deployed or tested on the target server "たいち鯖".
> - **macOS は未確認です。** 自動テストは Linux（GitHub Actions）だけで毎回実行しています。Windows は開発初期（Phase 0〜2）の手動実行の記録だけです（[docs/platform-verification.md](docs/platform-verification.md)）。 / macOS is not verified. Every automated gate runs on hosted Linux only; Windows evidence exists only from early local runs.
> - Java は 25 だけで確認しています（`fabric.mod.json` の下限は `>=25`）。 / Only Java 25 is tested (the loader minimum is `>=25`).
>
> 実際のサーバーで使う前に、下の「更新とバックアップ」に従ってデータを保存してください。 / Back up your data before using it on a real server.

## 必要なもの / Requirements

この組み合わせだけを確認しています。ほかの版は動作を保証しません（[docs/compatibility.md](docs/compatibility.md)）。 / Only this exact combination is tested.

| | クライアント / Client | サーバー / Dedicated server |
| --- | --- | --- |
| Minecraft | 26.2 | 26.2 |
| Java | 25 | 25 |
| Fabric Loader | 0.19.3 | 0.19.3 |
| Fabric API | 0.154.0+26.2 | 0.154.0+26.2 |
| Litematica | 0.28.3（必須。ないと MCMaterialList の画面は無効 / required for the screens） | 不要 / not needed |
| MaLiLib | 0.29.2（同上 / same） | 不要 / not needed |
| MCMaterialList | `fabric-0.1.0.jar` | `fabric-0.1.0.jar`（共有作業と Check Materials を使う場合 / for sharing and Check Materials） |

- 配布ファイルの名前は `fabric-0.1.0.jar` です（中の MOD ID は `mcmateriallist`）。 / The distributed file is `fabric-0.1.0.jar` (mod id `mcmateriallist`).
- Minecraft、Fabric Loader、Fabric API の版が違うと、Fabric Loader が起動を止めます。Litematica / MaLiLib の版が違う、または入っていないと、クライアントの MCMaterialList 画面は自動で無効になります（この無効化の経路は自動テストしていません）。 / Other Minecraft/Loader/Fabric API versions are refused by Fabric Loader. With another or no Litematica/MaLiLib, the client screens turn themselves off (this path is not covered by an automated test).

## インストール / Install

1. Minecraft 26.2 に Fabric Loader 0.19.3 を入れます。 / Install Fabric Loader 0.19.3 for Minecraft 26.2.
2. `mods` フォルダーに Fabric API 0.154.0+26.2 を入れます。 / Put Fabric API 0.154.0+26.2 into `mods`.
3. クライアントには Litematica 0.28.3 と MaLiLib 0.29.2 も入れます。サーバーには入れません。 / On the client, also add Litematica 0.28.3 and MaLiLib 0.29.2. Not on the server.
4. GitHub の Release v0.1.0 から `fabric-0.1.0.jar` を取得し、`mods` フォルダーに入れます（自分でビルドする場合は下の「ビルドと検証」）。 / Download `fabric-0.1.0.jar` from the v0.1.0 GitHub release into `mods` (or build it yourself, see "Build and verification").
5. 起動します。素材リストか配置設定の画面の下に `MCMaterialList` ボタンが出れば導入できています。 / Start the game. An `MCMaterialList` button at the bottom of the material list or placement configuration means it is active.

## 更新とバックアップ / Update and backup

更新・削除の前に、次のフォルダーを丸ごとコピーしてください。 / Before updating or removing the mod, copy these folders:

| 場所 / Location | 中身 / Contents |
| --- | --- |
| クライアント `config/mcmateriallist/` | ローカル作業（`placements/<UUID>/materials.json`, `regions.json`）、共有の設定と送信記録（`shared-client/`）、切り離しコピーの書き出し（`shared-client/detached/`）、Location File（`location-files/`） / local work, sharing state and journal, detached-copy exports, Location Files |
| サーバー `<world>/mcmateriallist-shared/` | 共有プロジェクト（サーバーが正本） / shared projects (the server is the authority) |
| サーバー `<world>/mcmateriallist-stock/grants/` | Check Materials の読み取り許可 / Check Materials read grants |

- 更新は JAR を差し替えるだけです。0.1.0 は古い開発版（`0.1.0-phase.*`）のデータを読み、次の保存で新しい形式に書き換えます。 / Updating is replacing the JAR. 0.1.0 reads data from earlier development builds and rewrites it in the current schema on the next save.
- **古い版に戻すことはサポートしていません。** 新しい形式で保存した共有設定は、古い開発版では「新しい版が必要」と表示されて共有の書き込みが止まります（[docs/compatibility.md](docs/compatibility.md#upgrade-and-downgrade-notes)）。戻す場合は、コピーしておいたフォルダーを戻してください。 / Downgrading is not supported; restore your copy instead.
- クライアントとサーバーは同じ版にしてください。通信の版が違うと共有は拒否されます（ローカル作業は使えます）。 / Run the same build on client and server. A protocol mismatch is refused; local work stays usable.
- `.litematic` ファイルには何も書き込みません。 / Nothing is written into `.litematic` files.

## 使い方 / Usage

### ひとりで使う / Local use

1. Litematica で配置（placement）を保存し、素材リストを開きます。 / Save a placement in Litematica and open its material list.
2. **Track materials** で素材の作業を始めます。配置設定の画面では **Track regions** で領域の作業を始めます。開くだけでは何も書き込みません。 / **Track materials** (or **Track regions** on placement configuration) starts work; just opening writes nothing.
3. 行の顔をクリックすると担当（Claim / Release）とメモ、緑のチェックで完了、赤い X で取り消しです。進捗は絞り込みに関係なく、追跡中のすべての行で数えます。 / Click a face for Claim/Release and a note, the green check to complete, the red X to undo. Progress always counts every tracked row.
4. `MCMaterialList` ボタンで状態・理由・次の操作をまとめた画面を開けます。 / The `MCMaterialList` button opens a screen with the state, the reason and the next step.

詳しくは下の [Feature reference](#feature-reference)。 / Details below.

### MOD を導入したサーバーで共有する / Sharing on a server with the mod

1. サーバーとクライアントの両方に同じ `fabric-0.1.0.jar` を入れます。 / Install the same JAR on both sides.
2. 素材リストか配置設定で **Share** → **New project...** でプロジェクトを作ります（配置を保存し、すべての領域を有効・未変形にして数え直してから）。 / **Share** → **New project...** after a fresh full count of a saved placement.
3. 他の人は **Find projects** → **Join** で参加します。権限は Viewer / Member / Administrator で、Administrator が **Manage participants...** で管理します。 / Others use **Find projects** → **Join**. Roles are Viewer, Member and Administrator.
4. 変更はサーバーが確定してから表示されます。通信が切れても、未確定の操作は再接続後に同じ操作 ID で再送され、二重に実行されません。 / Changes appear after the server confirms them; unconfirmed operations are replayed exactly once after reconnecting.

同時に同じ行を担当しようとした場合、成功するのは 1 人だけで、Member の敗者には DENIED が返ります。 / In a simultaneous claim only one player wins; a Member who loses gets DENIED.

### MOD のないサーバー / Servers without the mod

ローカル作業はそのまま使えます。共有はできず、画面にその理由（MOD がない、または版が違う）が出ます。Location File はシングルプレイか対応サーバーのワールド識別が必要なので、ここでは無効になります。Check Materials は自分の所持品と自分で開いたコンテナだけを「OBSERVED」として表示します。 / Local work stays usable. Sharing is unavailable and the screens say why. Location Files are disabled. Check Materials shows only your inventory and containers you opened yourself, as OBSERVED.

### Location File / Check Materials / Unshare

- **Location File**：素材リストのボタンから、ワールドごとにチェスト等の位置を登録します。見ているブロックを **Edit...** で追加します。→ [docs/phase-4a-location-files.md](docs/phase-4a-location-files.md) / Register container positions per world.
- **Check Materials**：現在の Location File のコンテナの中身をサーバーに数えてもらいます。サーバー管理者（OP、シングルプレイ・LAN の主）は全位置、ほかの人は管理者が許可した位置だけ、Member 以上で読めます。結果はメモリだけに残り、Total / Missing / Available は変えません。→ [docs/phase-4b-check-materials.md](docs/phase-4b-check-materials.md) / Ask the server to count the containers; results stay in memory.
- **Unshare regions**：共有画面の REGIONS 行から、その配置の領域だけを自分のローカルな切り離しコピーにします。オンラインで同期済み、未確定の操作がないときだけ実行でき、2 回のクリックで確定します。**Rejoin shared regions...** で戻ると、切り離し中の変更はファイルに書き出され、統合はされません。→ [docs/phase-4c-unshare.md](docs/phase-4c-unshare.md) / Detach one placement's regions into a local copy; Rejoin exports, never merges.

## 制限 / Limits

主な上限（詳細と測定値は [docs/phase-5a-performance.md](docs/phase-5a-performance.md)）。上限を超える操作は、データを壊さずに拒否されます。 / Main limits; over-limit work is refused without corruption.

- 1 データセットのタスク数は 4,000 まで（有効な行とアーカイブ済みの定義の合計）。ローカル作業のファイルと通信メッセージは 32 MiB まで。 / 4,000 tasks per dataset including archived definitions; 32 MiB per local work file and per message.
- Location File：1 ファイル 512 位置、1 ワールド 256 ファイル。Check Materials：1 回 512 位置、1 人 5 秒のクールダウン、サーバー全体で同時 4 件、1 tick あたり 8 コンテナ。 / Location Files: 512 positions, 256 files per world. Check Materials: 512 positions per scan, 5 s cooldown, 4 concurrent scans, 8 containers per tick.
- 共有の表示中エントリーはクライアントあたり 16 配置まで。すべて使用中なら、新しい配置は「Sharing view limit reached」となり、元の領域の編集が止まります（クライアントの再起動で解消）。 / At most 16 placements hold sharing entries per client.
- 測定は素材 2,000 行・領域 2,000 件・20 人の想定で core（Minecraft の外）で行ったものです。ゲーム内のフレーム・tick の計測はしていません。 / Measurements were made in core for 2,000 rows, 2,000 regions and 20 participants; in-game frame and tick times were not measured.

## 互換性 / Compatibility

上の表の組み合わせだけを確認しています。ほかの MOD、ほかの版、上流 MOD のどこに接続しているかは [docs/compatibility.md](docs/compatibility.md)、OS ごとの確認範囲は [docs/platform-verification.md](docs/platform-verification.md) にあります。 / Only the combination above is tested. See the compatibility and platform pages.

## 既知の問題 / Known issues

- 1 回の接続で同期できる共有プロジェクトは 1 つです。素材と領域を別のプロジェクトに結び付けた場合、切り替えるには **Join / synchronize** か **Refresh work** が必要です。 / One project is actively synchronized per connection.
- 領域のアーカイブ済み定義は自動で削除されません。定義変更の多い Refresh を繰り返すと 4,000 タスクの上限に達し、保存が TOO_LARGE で拒否されます。 / Archived region history is never pruned.
- 20 人が同時に 512 位置の Check Materials を実行すると、最後の人の結果まで約 1 分かかります。 / With 20 players scanning 512 positions at once, the last result takes about a minute.
- サーバーの Check Materials 許可ファイルが壊れた場合は安全側（許可なし）で止まります。ゲーム内の復旧操作はなく、サーバー管理者がファイルを戻す必要があります。 / A damaged grant file fails closed; there is no in-game recovery.
- Unshare は厳格な既定値です。未確定の領域操作が 1 件でもある間、また送信記録の復旧後は実行できません（所有者が #33 で確定）。 / Unshare is blocked while any region operation is unconfirmed and after a journal recovery (owner decision #33).
- 開発・テストのログには上流の警告（OSHI、HTTP 401 など）が出ます。MCMaterialList の不具合ではありません（[docs/compatibility.md](docs/compatibility.md#ci-log-noise)）。 / Development logs show harmless upstream warnings.
- LAN の主による Check Materials、MCMaterialList のない実サーバーへの接続、Litematica / MaLiLib のないクライアントは自動テストしていません。 / The LAN owner case, a real unmodded server and a client without Litematica/MaLiLib are not covered by automated tests.
- 画面の言語は英語と日本語です。 / The UI ships in English and Japanese.

## 未確認の項目（まとめ） / Not verified (summary)

| 項目 / Item | 状態 / Status |
| --- | --- |
| 認証済みの実プレイヤー 2〜3 人による受入テスト / Acceptance with authenticated real players | 未確認 / not verified |
| 対象サーバー「たいち鯖」への導入と確認 / Deployment on "たいち鯖" | 未確認 / not verified |
| macOS | 未確認 / not verified |
| Windows での 0.1.0（Phase 3 以降の機能） / Windows with 0.1.0 | 未確認（Phase 0〜2 の手動記録のみ） / not verified (only Phase 0-2 local runs) |
| Java 26 以降 / Java newer than 25 | 未確認 / not verified |
| ゲーム内の性能（フレーム・tick） / In-game frame and tick performance | 未計測 / not measured |
| 参照画像とのピクセル単位の一致（AC-01） / Pixel match with the reference images | 未確認（配置関係は自動テスト） / not verified |

受入条件 AC-01〜AC-18 ごとの状態は [docs/acceptance-0.1.0.md](docs/acceptance-0.1.0.md) にあります。 / Per-criterion status is in the acceptance record.

## Feature reference

Open a saved placement's material list and select **Track materials** to start local tracking. Opening existing work only reads it. Click a face for Claim/Release and a multiline note (explicit **Save note**); the green check completes a row and the red X undoes completion. Show Info expands rows; Hide Done only filters the view. Progress always counts all current tracked material types. Counts, Ignore, Refresh, HUD and exports retain upstream behavior.

**Refresh work** reconciles the latest completed upstream observation and preserves work. Definition changes show a notice; removed definitions are kept in read-only Archived work. Storage failure disables editing and retains the last confirmed view. Recovery requires **Recover backup**, then **Confirm recovery**, and may lose the last action. Material controls never create/change a region snapshot or alter `.litematic` files.

### The MCMaterialList screen

Click the `MCMaterialList` footer button on the material list or on placement configuration to open the **MCMaterialList** screen for that list (Issue #22). It shows:

- the state of the list: local work, detached local copy, shared and synchronized, synchronizing or waiting for an unconfirmed result, waiting for Litematica, disconnected, real-time sharing unavailable on this server, no permission, schematic/placement differs from the shared project, read or save failure, or another action required;
- the reason and the next step, whether this server supports MCMaterialList sharing, the work source (local, a shared project or the detached copy) and progress;
- the actions **Track materials**/**Track regions** or **Refresh work**, **Shared work...** (on the region screen also **Unshare regions...**/**Rejoin shared regions...**), and on the material list **Location File** and **Check Materials**. **Back** returns to the list.

Opening the screen only reads; only an explicitly labelled action writes. A disabled control on these screens, and on Shared work and Location Files, explains in its hover text why it is disabled and what to do next. A network failure or an unconfirmed result is never shown as saved or shared.

The Phase 1 local work diagnostic, which creates and refreshes both local datasets, is a developer tool and not part of normal use. It runs only on **Shift-click** when the client JVM is started with `-Dmcmateriallist.diagnostics=true`; without that flag Shift-click is an ordinary click and writes nothing.

### Region tracking

Open placement configuration and select **Track regions** to explicitly create region work. Region controls read only the original schematic definitions and remain available independently of material counting. Faces open Claim/Release and explicit note saving; completion preserves Configure, Placement ON/OFF and upstream transforms. Hide Done filters the view while progress includes all tracked regions, including disabled placements. Standard modification and note reasons share one native notice bubble; deleting a note retains the standard reason.

**Refresh work** reconciles original region definitions. Ambiguous changes start blocked default work and keep old work in **Archived work**. Review the exact new definition in row details and deliberately **Accept new definition**; archived state is never transferred. Backup recovery is a separate two-step action. Region controls never initialize or rewrite material snapshots.

### Shared work

On a supported server, open either native screen and select **Share**. **New project...** creates an initially empty work state from a saved, fully counted placement; existing local assignments, completion and notes are preserved separately. **Find projects** and **Join** explicitly select an accessible project for the invoking list. Material and region bindings are independent and may use different projects. **Use local work** returns that list to its original local data.

Shared faces/details allow a Member to claim an unassigned task, complete it with one-step self-assignment, and edit their own completion and note. Administrators can also assign or clear another participant and manage Viewer, Member and Administrator roles using **Manage participants...**. The last current Administrator cannot be removed or demoted. Server confirmation is required before work changes appear. The participant screen offers known online players and an explicit UUID field; no external player lookup occurs. Only one project is actively synchronized per connection, so switching between independently bound projects requires **Join / synchronize** or **Refresh work** before editing.

Total/Missing/Available, multiplier, Ignore, Hide Done and personal enabled-region counts remain local observations. They never change shared task definitions or progress denominators. Creating a project requires a fresh full count with every original region enabled and untransformed; normal refresh of an existing binding may display partial personal counts. A changed schematic source, dimension, placement origin/rotation/mirror or original region definition blocks shared task editing. A current Administrator can explicitly confirm **Update shared definition...** after a fresh full count and unchanged loaded source; this replaces global material and region definitions and the placement baseline, then requires a new join.

Unsupported servers leave local work usable; the MCMaterialList and Shared work screens say why real-time sharing is unavailable there (no MCMaterialList, or an incompatible version). Nothing is uploaded to any other service. Unknown sent commands block dependent shared edits and remain attached to their original account/project, even after switching to local mode. After a fresh snapshot of that project, the client resends the exact original with the same operation ID; the server returns its recorded outcome, executes it once if it never arrived, or reports a conflict, and that answer settles it. Reconnecting to the same world and account re-joins the project the previous connection was synchronized to. **Recover metadata**, then **Confirm recovery**, preserves damaged bytes but cannot establish the outcome of a command missing from a backup; shared writes stay blocked, and local work remains available. See [Phase 3A usage, persistence and verification](docs/phase-3-shared-work.md) for these limits.

### Location Files

The material list's **Location File** button opens per-world profiles of registered container positions (Issue #9, Phase 4A). **New...** creates one in the current dimension, a row selects the current profile, and **Edit...** adds the looked-at chest, trapped chest, barrel, shulker box, hopper, dropper or dispenser, removes positions, renames, or records the list's shared project. Double chests are registered once. Location Files need the world identity of singleplayer or a compatible MCMaterialList server; elsewhere the controls are disabled with the reason. Files are named only by UUID under `config/mcmateriallist/location-files/`. See [Phase 4A Location Files](docs/phase-4a-location-files.md).

### Check Materials

**Check Materials** (Issue #10, Phase 4B), next to the Location File control, asks the server to read the current Location File's containers. Server admins (operators, or the singleplayer/LAN owner) may read any position; other players read only positions a server admin who is the project's Administrator granted to the bound project, and only as Member or higher. The footer shows checked/unchecked locations, Show Info adds `Stock n (+previous, in machines) · Inventory n`, and **Stock details...** lists each location's status. Hoppers, droppers and dispensers count separately as machines; unchecked locations are never counted as zero. Without server support, or for refused positions, only your inventory and registered containers you opened yourself are shown, as OBSERVED. Results stay in memory; Total/Missing/Available and work are never changed. See [Phase 4B Check Materials](docs/phase-4b-check-materials.md).

## Build and verification

Use JDK 25 as `JAVA_HOME` (Gradle 9.7.1 wrapper, Loom 1.17.20). The build needs the Minecraft and Fabric Maven hosts.

```bash
./gradlew clean build
# Only after accepting https://aka.ms/MinecraftEULA. Linux needs a display for the client tests, for example xvfb-run:
xvfb-run -a ./gradlew clean verifyRelease -PacceptMinecraftEula=true
```

On Windows use `.\gradlew.bat` with the same tasks.

- `verifyRelease` is the release gate that hosted CI runs on every push and pull request (`.github/workflows/phase-0.yml`). It runs `verifyPhase4` (every Phase 0-4 gate: real client restart, Location Files, Check Materials, shared transport and native UI, isolated dedicated server, two clients against a restarted dedicated server, Unshare, Phase 5A traces) plus `:fabric:verifyApplicationJar`, `:fabric:verifyDependencyLicenses` and `:fabric:verifyTranslations`. `verifyPhase0` to `verifyPhase4` remain available.
- The test plan, the acceptance traceability and the applied test techniques are in [docs/test-plan.md](docs/test-plan.md), [docs/test-traceability.md](docs/test-traceability.md) and [docs/test-design.md](docs/test-design.md). `:core:test` also writes a JaCoCo coverage report to `core/build/reports/jacoco/test/` (no threshold yet). Platform evidence and CI notes are in [docs/platform-verification.md](docs/platform-verification.md).
- The distribution is `fabric/build/libs/fabric-0.1.0.jar` and includes the core library; the version comes from `mod_version` in `gradle.properties`. A green CI run uploads it as the `application-jar` artifact. Fabric API is required on both sides; pinned Litematica/MaLiLib are client-only. Unsupported or missing client mods disable the adapter. The dedicated server needs neither optional client mod. `:fabric:runClient` launches the normal development client. Minecraft 26.2 is unobfuscated: Loom uses `jar`, without external mappings or `remapJar`.

## Structure and persistence

- `core`: immutable domain, explicit work commands, progress, reconciliation, strict JSON codec, atomic local store and serial I/O service. No Minecraft/Fabric/Litematica dependency.
- `fabric/src/main`: common/server authority and bounded transport, without client references.
- `fabric/src/client`: optional native adapters, local work and explicitly selected shared backend.
- `fabric/src/gametest`, `serverSmoke`, `transportTest` and `sharedUiTest`: verification code excluded from distribution.

Placement identity wraps upstream's persistent `getHashId()` UUID (`hash_code` in placement JSON). Phase 1 requires a registered saved placement and rejects duplicate identities. Materials use upstream aggregated item registry IDs; regions use durable task UUIDs mapped conservatively by schematic key, relative origin and signed dimensions. Materials and regions have separate dataset UUIDs and snapshot files.

Data lives under `config/mcmateriallist/placements/<placement-uuid>/materials.json` and `regions.json`, with previous-good backups. No work state is written into `.litematic`. Corrupt/future snapshots block writes; backup recovery is explicit, never a silent blank reset. Work UUIDs and notes are stored only as required local data and are not logged by the application.

Test worlds, synthetic fixtures, screenshots and traces remain under ignored build directories. Do not distribute these as user data.

Independent shared bindings, confirmed caches and the original command journal use one schema-2 envelope under `config/mcmateriallist/shared-client/placement-<placement-uuid>.json`. Schema-1 files migrate on the next successful write without changing identities or journal contents. Its previous-good backup omits derived shared snapshots and retains binding/journal metadata; it is not a backup of the server project. Shared authority remains in the server-owned world store. See the [server protocol](docs/SHARED_PROTOCOL.md) and [transport contract](docs/SHARED_TRANSPORT.md).

See the [authoritative v0.1 design](litematica-collaboration-design-v0.1.md), [Phase 2A/2B/2C UI and verification](docs/phase-2-litematica-ui.md), [Phase 1 storage contract](docs/phase-1-local-work-state.md), and [Phase 0 evidence](docs/phase-0-compatibility-spike.md).

## License

MCMaterialList is released under the [MIT License](LICENSE). The repository owner chose it on 2026-10-30 ([decision record](docs/license-decision.md)). The license covers only this project's own code and resources; it does not relicense the dependencies below.

The distributed JAR bundles no third-party code. At runtime the mod requires Minecraft (Mojang EULA), Fabric Loader and Fabric API (Apache-2.0). On the client it optionally integrates with Litematica and MaLiLib, which declare LGPLv3. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists each pinned version and its declared license. The build checks that list against the pinned artifacts.
