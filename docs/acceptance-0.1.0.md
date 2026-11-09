# 受入記録 0.1.0（Issue #15、最終ゲート）

設計書 [§14.2 の受入条件 AC-01〜AC-18](../litematica-collaboration-design-v0.1.md) を、0.1.0 で何によって確認したかを 1 行ずつ記録する。対応の詳細は [test-traceability.md](test-traceability.md)、テスト計画は [test-plan.md](test-plan.md)、OS の確認範囲は [platform-verification.md](platform-verification.md) にある。

作成：2026-11-09。

## 0.1.0 の前提（所有者の決定）

- **0.1.0 は、認証済みの実プレイヤーによる受入テストと、対象サーバー「たいち鯖」への導入を実施せずにリリースする。** この 2 つは**未確認**であり、どの AC についても「確認済み」とは書かない。
- ライセンスは MIT（#23）。Unshare の厳格な既定値は維持する。同時担当取得の敗者には DENIED を返す（#33）。
- 確認している組み合わせ：Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.154.0+26.2、Litematica 0.28.3、MaLiLib 0.29.2、Java 25（`fabric.mod.json` は `java >=25` を下限として宣言。確認は 25 のみ）。

## 結果の区分

| 区分 | 意味 |
| --- | --- |
| **自動 PASS（CI）** | 条件の全体を、`verifyRelease` に含まれるゲートのマーカーまたは core の JUnit で検査している。リリース対象のコミットで CI が成功したときにだけ PASS とする |
| **一部自動** | 条件の一部だけを自動で検査している。残りは下の「残り」に書く |
| **手動未実施** | 自動の検査がなく、手動の受入でしか確認できない。0.1.0 では実施していない |

すべての AC に共通して、**認証済みの実プレイヤーと「たいち鯖」での手動受入（チャーター CH-01〜CH-07、[test-design.md](test-design.md) §5）は未実施**。下の表の「結果」は自動テストの範囲だけを表す。

## CI の実行記録

| 項目 | 値 |
| --- | --- |
| ワークフロー | `.github/workflows/phase-0.yml`、ジョブ `compatibility`（ubuntu-24.04、Microsoft OpenJDK 25、`xvfb-run`） |
| 実行するゲート | `clean build`、続いて `verifyRelease -PacceptMinecraftEula=true`（`verifyPhase4` ＋ `verifyApplicationJar`、`verifyDependencyLicenses`、`verifyTranslations`） |
| リリース対象のコミット | `d47082bd41179c65738d80869a168196e1ceede9`（main、#42 のスカッシュ。v0.1.0 のタグを付けるコミット） |
| そのコミットの CI 実行 | [42695901338](https://github.com/bosatsu25/MCMaterialList/actions/runs/42695901338)：成功（ジョブ 1 件、Ubuntu 24.04、JDK 25。`clean build` と `verifyRelease` の両ステップが成功）。成果物 `application-jar`（ID 13709029194、zip の sha256 `ae871c1c9518726a8b78853e79c14fd951a5ac04b5c9ec8de5935450673803d5`）、`phase-4a-test-evidence`（ID 13709518802）。保持期限 2027-02-07 |
| 証跡 | 成果物 `phase-4a-test-evidence`（core の JUnit XML、JaCoCo、スクリーンショット、各ゲートのログ）、`application-jar`（`fabric-0.1.0.jar`） |
| ローカルの core テスト | 2026-11-09、JDK 25、JUnit Platform を直接実行：286 件、失敗 0、スキップ 0（fabric はこの環境ではビルドできない） |

**#34 のクライアントテスト**（`Phase5GCoverageClientTest`、`SharedUiCoverageChecks`、`MultiplayerClientTest` の Location File）は、PR #41 の最終コミット 1641605 の CI（実行 42689396105 と 42689404128）で初めてコンパイル・実行され、すべてのマーカーが合格した。最初の実行では未ロードチャンクの拒否が失敗し、本番の不具合（`Level.hasChunkAt` がアンロード後も true）を見つけた。修正は同じ PR に含まれる。リリース対象のコミットの CI は上の表に記録した。

## AC-01〜AC-18

| AC | 条件（要約） | Issue | テスト・マーカー・ゲート | 期待結果 | 結果 | 根拠・残り |
| --- | --- | --- | --- | --- | --- | --- |
| AC-01 | 2 画面の追加 UI が添付と同じ順序・配置・色・完了印 | #2, #3, #4 | `verifyClientRestart`：`Phase2ClientTest`, `Phase2RegionClientTest`（1920×1080・GUI 倍率 3 の基準配置、列順、ネイティブの通知スプライト、非重複）、`UiParityEvidence` のスクリーンショット。core `MaterialRowLayoutTest`, `WorkProgressPresentationTest` | 列順・配置関係・色・完了印が設計どおり | **一部自動** | 配置関係は自動。添付画像とのピクセル単位の一致と目視比較は**手動未実施**（フォント・スキン・リソースパックが不明）。スクリーンショットは成果物にある |
| AC-02 | 素材 3・4・10・11 行の完了で 4 / 19 (21%) | #2 | `Phase2ClientTest`（実クリック後に `Progress(4, 19, 21)`）。core `WorkDomainTest#progressUsesTrackedTasksAndFloorsPercentages`, `TaskStateTransitionTest` | 4 / 19 (21%)、顔のチェックと赤 X が対応 | **自動 PASS（CI）** | `verifyClientRestart`、core JUnit |
| AC-03 | Region 2・6・8・9・11 で 5 / 62 (8%)、Placement は ON のまま | #3 | `Phase2RegionClientTest`（`Progress(5, 62, 8)`、Placement と完了の独立、All ON/OFF でも分母不変） | 5 / 62 (8%)、Placement ON | **自動 PASS（CI）** | `verifyClientRestart` |
| AC-04 | 完了・取り消しで数量・メモ・Placement が変わらない | #2, #3 | `Phase2ClientTest`, `Phase2RegionClientTest`。core `WorkDomainTest#explicitTransitionsPreserveIndependentFields`, `TaskStateTransitionTest#stT_allTransitionsOfTheTaskTable` | 他の項目が不変 | **自動 PASS（CI）** | `verifyClientRestart`、core JUnit |
| AC-05 | 完了素材でも Missing=Total・Available=0、Refresh で完了が消えない | #2 | `Phase2ClientTest`（固定データ、上流の Refresh 後も状態が同一で total 19）。core `WorkDomainTest#materialsReconcileByRegistryIdentityAndRetainArchivedState` | 表示どおり、完了が残る | **自動 PASS（CI）** | `verifyClientRestart`、core JUnit |
| AC-06 | 絞り込みで分母が変わらない、0 件でも例外なし | #2, #3 | `Phase2ClientTest`（Hide Done、検索、ソート、Ignore / Clear ignored、Hide available の後も 4/19）、`Phase2RegionClientTest`（Hide Done で 57 行表示・5/62）。core `WorkProgressPresentationTest#emptyDatasetAndOtherNumbersKeepTheirOriginalStyling`、0/0 → 0% | 分母不変、例外なし | **自動 PASS（CI）** | `verifyClientRestart`、core JUnit |
| AC-07 | 領域変更・メモ・完了が共存、メモ削除後も「!」と標準の説明 | #3, #4 | `Phase2RegionClientTest`（`saveReferenceNote` で空のメモを保存、`verifyReferenceNotices`） | 「!」と標準の理由が残る | **自動 PASS（CI）** | `verifyClientRestart` |
| AC-08 | Show Info OFF でも情報印、拡張行の描画とクリック領域が一致 | #2, #3, #4 | `Phase2ClientTest`, `Phase2RegionClientTest`（Show Info OFF の通知、拡張行のクリック判定） | 印が出る、クリック領域が一致 | **自動 PASS（CI）** | `verifyClientRestart`、[phase-2-litematica-ui.md](phase-2-litematica-ui.md) |
| AC-09 | スキン未取得・長い日本語名・GUI 倍率変更で重ならない | #2, #3, #4, #22 | `Phase2ClientTest`（GUI 倍率 1〜4、スキン代替、長い日本語名、日本語 UI）、`Phase2RegionClientTest#verifyScaledNames`、`runSharedUiTest` の倍率・狭い画面・日本語の検査、`Phase 5E footer opens MCMaterialList screen PASS`（倍率 1〜4 と日本語） | 操作不能な重なりがない | **自動 PASS（CI）** | 実際のプレイヤースキン・リソースパックとの組み合わせは**手動未実施** |
| AC-10 | 同じ設計図の別プロジェクトで進捗が混ざらない、ソート・改名・再起動で行がずれない | Phase 1, #2, #3, #5 | `Phase1ClientTest`、`Phase2ClientTest` / `Phase2RegionClientTest`（別プロセスでの復元）、`runSharedUiTest`（素材・領域で別プロジェクト）。core `SharedBoundaryTest`, `WorkDomainTest#regionMatchingNeverGuessesOnRenamesOrMoves`, `LocalDatasetStoreTest` | 混ざらない、ずれない | **自動 PASS（CI）** | `verifyClientRestart`、`runSharedUiTest`、core JUnit |
| AC-11 | 同時担当取得で 1 人だけ成功、全員が確定した担当者を表示 | #5, #6, #8, #33 | `verifyMultiplayer`（`MultiplayerClientTest` の Simultaneous claim：同時送信、片方 OK・もう片方 DENIED、両者が同じ担当者に収束）。core `SharedReconnectTest#concurrentClaimsHaveOneWinnerAndTheLoserLearnsTheAssigneeFromAuthority`, `SharedBoundaryTest#sameRowConcurrentRequestsUseLatestStateInsideTheQueue` | 勝者 1 人、敗者（Member）は DENIED、表示が収束 | **自動 PASS（CI）** | 合成アカウント 2 つ・同一マシン。認証済みの実プレイヤーでは**手動未実施** |
| AC-12 | 応答喪失・再送で二重実行しない、保存失敗で成功と表示しない | #5, #7, #8, #34 | `verifyMultiplayer`（Lost response、Lost response and disconnect、Server restart）、`Phase2ClientTest`（ローカル保存失敗）、`runSharedUiTest` の `Shared native UI server save failure PASS`（#34）。core `SharedAuthorityTest#saveFailureDoesNotPublishAndRestartReplaysConfirmedResult`, `SharedReconnectTest#duplicateExecutesOnceAcrossRestartAndExpiredReplayOnlyConflicts`, `JournalStateTransitionTest` | 1 回だけ実行、保存失敗は失敗と表示 | **自動 PASS（CI）** | 共有の保存失敗は同一プロセス内の専用サーバーでの障害注入。#34 のマーカーは PR #41 の CI で合格 |
| AC-13 | 再接続・通知欠落からスナップショットで復旧、ACK 先着で差分を落とさず、古い ACK で戻らない | #5, #7, #8 | `verifyMultiplayer`（オフライン中の変更、再接続）、`verifySharedTransport`。core `SharedClientStateTest`, `SharedReconnectTest#completeUndoThenDelayedOldCompleteCannotRestoreStaleCompletion`, `#onlyFreshlyInstalledSnapshotsMakeReplayDue` | 復旧する、巻き戻らない | **自動 PASS（CI）** | ACK 先着の順序は core のみ（別プロセスでは決定的に再現できない。[phase-3-multiplayer.md](phase-3-multiplayer.md)） |
| AC-14 | 権限外の編集・過大な入力を拒否、他プロジェクトを書き換えない | #5, #6 | `verifyMultiplayer`（Join、Viewer の編集、偽装した生コマンド）。core `SharedPermissionsTest`, `PermissionDecisionTableTest`（DT-P）, `SharedProtocolTest#decoderRejectsOversizedCountsLengthsMalformedUtf8AndUnknownVersionsBeforeAllocation`, `BoundaryValueTest`（BV-01〜BV-07） | 拒否され、他は不変 | **自動 PASS（CI）** | `verifyMultiplayer`、core JUnit |
| AC-15 | Location File を選んで Check Materials、既知の数・未確認範囲・取得時刻を区別 | #9, #10, #34 | `verifyLocationFiles`（`Phase4LocationClientTest`、#34 の 3 マーカーと専用サーバーの 2 マーカー）、`verifyStockScan`（`Phase4StockClientTest`、マルチの 4 マーカー、#34 の Phase 5G stock マーカー）。core `StockScanTest`, `StockLedgerTest`, `StockPolicyTest`, `StockDecisionTableTest`, `StockSchedulerTest`, `BoundaryValueTest`（BV-08〜BV-13） | 確認済み・未確認・前回値・機械が区別され、未確認を 0 と数えない | **一部自動** | 取得時刻の表示はクライアントテストで検査していない（core `StockLedgerTest#observedIsNeverCheckedAndCarriesItsOwnTime` のみ）。LAN の主、MCMaterialList のない実サーバーは未自動化。実倉庫での確認（CH-04）は**手動未実施** |
| AC-16 | Unshare で自分の領域関連付けだけ外れ記録はローカルに残る、未確定操作・保存失敗では解除しない、素材共有を壊さない | #11, #33, #34 | `verifyUnshare`（`Shared native UI unshare PASS`、マルチの 3 マーカー、#34 の `Shared native UI refused unshare save PASS`, `Shared native UI unreadable sharing metadata PASS`, `Shared native UI placement capacity PASS`）。core `SharedClientUnshareTest`, `UnshareDecisionTableTest`（DT-U）, `BindingStateTransitionTest` | 自分の領域だけ切り離し、条件を満たさないときは不変 | **自動 PASS（CI）** | 32 MiB を超える切り離しコピーはクライアントでは再現しない（core で確認。[phase-4c-unshare.md](phase-4c-unshare.md)） |
| AC-17 | Litematica / MaLiLib なしの専用サーバーで起動しクライアント 2 台と同期、定義変更は再取得、権限失効後は配信停止 | Phase 0, #5, #8 | `verifyServerSmoke`, `verifyIsolation`（クラスロード監査）、`verifyMultiplayer`（Synchronization、Definition change、Revocation、2 回の起動での分離マーカー） | 起動・同期・再取得・配信停止 | **一部自動** | 合成アカウント・同一マシンでは自動で確認。**認証済みの実プレイヤーと「たいち鯖」での確認は手動未実施**（0.1.0 では未確認） |
| AC-18 | Refresh、Ignore / Clear ignored、Raw Materials、Configure、All ON/OFF、座標・回転等が使える | #2, #3 | `Phase2ClientTest`（Refresh、Ignore / Clear ignored、Raw / テキスト書き出し）、`Phase2RegionClientTest`（Configure、Placement / All ON/OFF、原点・回転・反転） | 既存操作が使える | **自動 PASS（CI）** | `verifyClientRestart` |

## 集計

| 結果 | 件数 | AC |
| --- | --- | --- |
| 自動 PASS（CI） | 15 | AC-02〜AC-14、AC-16、AC-18 |
| 一部自動 | 3 | AC-01、AC-15、AC-17 |
| 手動未実施（自動の検査なし） | 0 | - |
| 認証済みの実プレイヤー・「たいち鯖」での手動受入 | 18 件すべて未実施 | AC-01〜AC-18 |

「自動 PASS（CI）」の 15 件も、リリース対象のコミットの CI 成功を記入するまでは合格の記録ではない。

## 未確認の項目

| 項目 | 状態 | 関係する AC |
| --- | --- | --- |
| 認証済みの実プレイヤー 2〜3 人による受入（CH-01〜CH-07） | 未確認 | すべて（特に AC-11、AC-17） |
| 対象サーバー「たいち鯖」への導入と確認 | 未確認 | すべて（特に AC-17） |
| macOS | 未確認（[platform-verification.md](platform-verification.md)） | すべて |
| Windows での 0.1.0 | 未確認（Phase 0〜2 の手動記録のみ） | すべて |
| Java 26 以降 | 未確認 | すべて |
| 添付画像とのピクセル単位の一致 | 未確認 | AC-01 |
| ゲーム内のフレーム・tick の計測 | 未計測（core の測定のみ。[phase-5a-performance.md](phase-5a-performance.md)） | - |
| 他の MOD との組み合わせ（CH-07） | 未確認（[compatibility.md](compatibility.md)） | AC-18 |

## 配布 JAR

- ファイル：`fabric/build/libs/fabric-0.1.0.jar`（`mod_version=0.1.0`）。CI の成功時に成果物 `application-jar` として保存される。
- `verifyApplicationJar` が確認すること：
  - MIT の `LICENSE` と `THIRD_PARTY_NOTICES.md` がある。
  - 入れ子の JAR は自分の `core` だけで、それにも MIT の `LICENSE` がある。
  - Gson、Litematica / MaLiLib のクラス、テストコード、ログ、スクリーンショットがない。
  - `fabric.mod.json` が id `mcmateriallist`、license `MIT`、version `0.1.0`（`project.version`）。
- JAR は `tasks.jar.archiveFile` から読むので、版が変わってもゲートはその名前の JAR を検査する。

## 判定

0.1.0 は、上の自動テストの範囲で、リリース対象のコミットの CI 成功を条件にリリースする。手動受入を実施していないことは、所有者の決定として README、[CHANGELOG.md](../CHANGELOG.md)、[release-notes-0.1.0.md](release-notes-0.1.0.md) に明記している。手動受入を実施した場合は、この文書に結果を追記する。
