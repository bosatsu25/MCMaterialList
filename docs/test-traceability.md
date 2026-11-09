# トレーサビリティ（受入条件 ↔ テスト）

設計書 [§14.2 の受入条件 AC-01〜AC-18](../litematica-collaboration-design-v0.1.md) と、#33 で確定した所有者の決定を、実在するテストクラス・メソッド・ログマーカー・Gradle ゲートに対応付ける（JSTQB FL v4.0 1.4.4）。テスト計画は [test-plan.md](test-plan.md)、技法の適用は [test-design.md](test-design.md)。0.1.0 の受入記録は [acceptance-0.1.0.md](acceptance-0.1.0.md)。

2026-11-09（#15）：取り込み済みの #12（Phase 5A）・#14（Phase 5C）・#22（Phase 5E）と、#34（Phase 5G）のテストを反映した。CI のゲートは `verifyRelease`（#13）になった。

## 読み方

**状態の区分**

| 区分 | 意味 |
|---|---|
| CI自動 | `verifyRelease`（`verifyPhase4` を含む）に含まれる fabric のゲート（実クライアント、ソケット、3 プロセスのマルチプレイ、専用サーバー）と core JUnit で確認している。CI（`.github/workflows/phase-0.yml`）が push / PR ごとに実行する |
| coreのみ | core の JUnit だけで確認している。Minecraft 上の画面・通信では確認していない |
| 一部 | 条件の一部だけ自動で確認し、残りは手動または未確認 |
| 手動未実施 | #15 の受入（認証済みの実プレイヤー、対象サーバー）で確認する予定。**まだ実施していない** |

**ゲートとマーカー**（`fabric/build.gradle` がログの文字列を検査する）

| ゲート | 実行内容 | 必須マーカー |
|---|---|---|
| `:fabric:verifyClientRestart` | `Phase0ClientTest`, `Phase1ClientTest`, `Phase2ClientTest`, `Phase2RegionClientTest`, `Phase4LocationClientTest`, `Phase4StockClientTest`, `Phase5GCoverageClientTest`（#34）を実クライアントで実行し、別プロセスで復元を確認 | gametest の失敗で Gradle が失敗。1 回目のプロセスで `Phase 5E footer opens MCMaterialList screen PASS`, `Phase 5E diagnostic requires explicit flag PASS`（#22）と Phase 5G の 9 マーカー（`Phase 5G location shulker dropper dispenser PASS`, `Phase 5G location unloaded refusal PASS`, `Phase 5G location bind unbind PASS`, `Phase 5G stock fallback without channel PASS`, `Phase 5G stock containers and CHANGED PASS`, `Phase 5G stock grant revoke buttons PASS`, `Phase 5G stock grant file recovery PASS`, `Phase 5G stock cancel midway PASS`, `Phase 5G coverage assertions PASS`） |
| `:fabric:verifyServerSmoke`, `verifyIsolation` | Litematica / MaLiLib なしの専用サーバー起動、クラスロード監査 | `Phase 0 dedicated server isolation PASS`, `Shared authority isolated asynchronous readiness PASS` |
| `:fabric:verifySharedTransport` | 同一プロセス内の専用サーバーとのソケット通信（`SharedTransportClientTest`） | `Shared transport all assertions PASS` |
| `:fabric:runSharedUiTest` | 共有 UI の実操作（`SharedUiClientTest` ほか、#34 の `SharedUiCoverageChecks`） | `Shared native UI all assertions PASS`, `Shared native UI server save failure PASS`（#34） |
| `:fabric:verifyMultiplayer` | クライアント 2 台＋別プロセスの専用サーバー、再起動 2 回（`MultiplayerClientTest`, `MultiplayerServerHarness`） | `Multiplayer alpha/beta all assertions PASS`, `Multiplayer dedicated server isolation PASS boot=1/2`, `Multiplayer authority ready boot=1/2` |
| `:fabric:verifyLocationFiles` | Location File の登録と別プロセス復元、専用サーバーでの Location File（#34） | `Phase 4A Location File restore PASS`, `Phase 5G coverage restore PASS`, `Multiplayer alpha location file dedicated server PASS`, `Multiplayer alpha location file restart PASS` |
| `:fabric:verifyStockScan` | Check Materials（シングル、マルチ） | `Phase 4B Check Materials restore PASS`, `Phase 5G coverage restore PASS`（#34）, `Multiplayer alpha stock operator read and grant PASS`, `Multiplayer beta stock member UNAUTHORIZED then granted CHECKED PASS`, `Multiplayer beta stock viewer UNAUTHORIZED PASS`, `Multiplayer beta stock fallback OBSERVED PASS` |
| `:fabric:verifyUnshare` | Unshare regions（共有 UI、マルチ） | `Shared native UI unshare PASS`, `Multiplayer beta unshare blocked until replay PASS`, `Multiplayer beta unshare rejoin authority PASS`, `Multiplayer alpha unshare unaffected PASS`、#34 の `Shared native UI unreadable sharing metadata PASS`, `Shared native UI placement capacity PASS`, `Shared native UI refused unshare save PASS` |
| `:fabric:verifyPerformance` | 専用サーバーのログに設計 §13 の操作トレースがあり、非公開の内容がない（#12） | `MCMaterialList trace op=join … result=OK`、メモ本文・アイテム ID がログにないこと |
| `:fabric:verifyApplicationJar`, `verifyDependencyLicenses`, `verifyTranslations` | 配布 JAR の内容（MIT ライセンス、通知、入れ子は core だけ、テスト・任意 MOD のコードなし、`fabric.mod.json` の版 = `mod_version`）、依存のライセンス、`en_us` / `ja_jp` のキー一致 | 検査失敗で Gradle が失敗 |
| `verifyRelease`（ルート、#13） | `verifyPhase4` と上の 3 つ。CI が実行するリリースゲート | 上のすべて |

マルチプレイの検証は 1 台のマシン上で、オフラインの合成アカウントを使う（[phase-3-multiplayer.md](phase-3-multiplayer.md)）。認証済みプレイヤーと対象サーバーでの確認ではない。

## AC-01〜AC-18

| AC | 合格条件（要約） | 自動テスト（クラス・メソッド・マーカー） | 状態 | 残り・ギャップ |
|---|---|---|---|---|
| AC-01 | 2 画面の追加 UI の順序・配置・色・完了印 | `Phase2ClientTest`, `Phase2RegionClientTest`（1920×1080・GUI 倍率 3 の基準配置、列順、ネイティブの通知スプライト、ツールバーとリストの非重複、スクリーンショット `UiParityEvidence`）。core `MaterialRowLayoutTest`, `WorkProgressPresentationTest` | 一部 | 添付画像とのピクセル単位の一致は判定しない（フォント・スキン・リソースパックが不明）。目視比較は手動未実施 |
| AC-02 | 素材 3・4・10・11 行の完了で 4 / 19 (21%) | `Phase2ClientTest`（実クリック後 `Progress(4, 19, 21)` を検査）。core `WorkDomainTest#progressUsesTrackedTasksAndFloorsPercentages`, `TaskStateTransitionTest`（ST-T） | CI自動 | - |
| AC-03 | Region 2・6・8・9・11 で 5 / 62 (8%)、Placement は ON のまま | `Phase2RegionClientTest`（`Progress(5, 62, 8)`、Placement と完了の独立、All ON/OFF でも分母不変） | CI自動 | - |
| AC-04 | 完了・取り消しで数量・メモ・Placement が変わらない | `Phase2ClientTest`, `Phase2RegionClientTest`。core `WorkDomainTest#explicitTransitionsPreserveIndependentFields`, `TaskStateTransitionTest#stT_allTransitionsOfTheTaskTable`（メモと完了情報の保持） | CI自動 | - |
| AC-05 | 完了素材でも Missing=Total・Available=0 を表示、Refresh で完了が消えない | `Phase2ClientTest`（Missing=Total・Available=0 の固定データ、上流の Refresh 後も状態が同一で total 19）。core `WorkDomainTest#materialsReconcileByRegistryIdentityAndRetainArchivedState` | CI自動 | - |
| AC-06 | 絞り込みで分母が変わらない、0 件でも例外なし | `Phase2ClientTest`（Hide Done、検索、ソート、Ignore / Clear ignored、Hide available の後も 4/19）、`Phase2RegionClientTest`（Hide Done で 57 行表示・5/62）。core `WorkDomainTest#progressUsesTrackedTasksAndFloorsPercentages`（0/0 → 0%）、`WorkProgressPresentationTest#emptyDatasetAndOtherNumbersKeepTheirOriginalStyling` | CI自動 | - |
| AC-07 | 領域変更・メモ・完了の共存、メモ削除後も「!」と標準の説明 | `Phase2RegionClientTest`（`saveReferenceNote` で空のメモを保存、`verifyReferenceNotices` で通知と理由を検査） | CI自動 | - |
| AC-08 | Show Info OFF でも必要な情報印、拡張行の描画とクリック領域の一致 | `Phase2ClientTest`, `Phase2RegionClientTest`（Show Info OFF の通知、拡張行のクリック判定。[phase-2-litematica-ui.md](phase-2-litematica-ui.md) の AC-07/08 行） | CI自動 | - |
| AC-09 | スキン未取得・長い日本語名・GUI 倍率変更でも重ならない | `Phase2ClientTest`（GUI 倍率 1〜4、スキン代替、長い日本語名、日本語 UI）、`Phase2RegionClientTest#verifyScaledNames`、共有 UI の倍率・日本語検査（`runSharedUiTest`） | CI自動 | 実際のプレイヤースキン・リソースパックとの組み合わせは手動未実施 |
| AC-10 | 同じ設計図の別プロジェクトで進捗が混ざらない、ソート・改名・再起動で行がずれない | `Phase1ClientTest`、`Phase2ClientTest` / `Phase2RegionClientTest`（別プロセスでの復元、独立した配置）、`runSharedUiTest`（素材・領域で別プロジェクト）。core `SharedBoundaryTest#observationsFiltersAndCountsNeverEnterTheAuthorityAndIdsSurviveRestart`, `#wrongWorldListProjectAndManifestCannotCrossScopesOrLeakRows`, `WorkDomainTest#regionMatchingNeverGuessesOnRenamesOrMoves`, `LocalDatasetStoreTest` | CI自動 | - |
| AC-11 | 同時担当取得で 1 人だけ成功、全員が確定した担当者を表示 | `verifyMultiplayer`（`MultiplayerClientTest` の Simultaneous claim：共有バリアで同時送信、片方 OK・もう片方 DENIED、両者が同じ担当者に収束）。core `SharedReconnectTest#concurrentClaimsHaveOneWinnerAndTheLoserLearnsTheAssigneeFromAuthority`, `SharedBoundaryTest#sameRowConcurrentRequestsUseLatestStateInsideTheQueue` | CI自動 | 実プレイヤーの体感は手動未実施 |
| AC-12 | 応答喪失・再送で二重実行しない、保存失敗で成功と表示しない | `verifyMultiplayer`（Lost response、Lost response and disconnect、Server restart）。core `SharedAuthorityTest#saveFailureDoesNotPublishAndRestartReplaysConfirmedResult`, `SharedReconnectTest#duplicateExecutesOnceAcrossRestartAndExpiredReplayOnlyConflicts`, `#lostAckSettlesThroughExactReplayOnReplacementConnection`, `JournalStateTransitionTest`（ST-J）。ローカル作業の保存失敗表示は `Phase2ClientTest`（書き込み失敗時に完了を公開しない、RECOVERY_REQUIRED）。共有作業のサーバー保存失敗の表示は `runSharedUiTest` の `SharedUiCoverageChecks`（#34、マーカー `Shared native UI server save failure PASS`：SAVE_FAILED で「Server save failed」、行は未完了、権威は不変、障害除去後の再試行は保存） | CI自動 | 同一プロセス内の専用サーバーでの障害注入。別プロセスのマルチプレイでは確認していない |
| AC-13 | 再接続・通知欠落からスナップショットで復旧、ACK 先着で差分を落とさず、古い ACK で戻らない | `verifyMultiplayer`（オフライン中の変更、再接続）、`verifySharedTransport`。core `SharedClientStateTest`（5 件すべて）、`SharedReconnectTest#completeUndoThenDelayedOldCompleteCannotRestoreStaleCompletion`, `#onlyFreshlyInstalledSnapshotsMakeReplayDue` | CI自動 | - |
| AC-14 | 権限外の編集・過大な入力を拒否、他プロジェクトを書き換えない | `verifyMultiplayer`（Join、Viewer edit、偽装した生コマンド）。core `SharedPermissionsTest`（16 件）、`PermissionDecisionTableTest`（DT-P、140 ルール）、`SharedBoundaryTest`, `SharedProtocolTest#decoderRejectsOversizedCountsLengthsMalformedUtf8AndUnknownVersionsBeforeAllocation`, `BoundaryValueTest`（BV-01〜BV-07） | CI自動 | - |
| AC-15 | Location File を選んで Check Materials、既知の数・未確認範囲・取得時刻を区別 | `verifyLocationFiles`（`Phase4LocationClientTest`、#34 の Location File マーカー）、`verifyStockScan`（`Phase4StockClientTest` の状態別フィクスチャ、マルチの 4 マーカー、#34 の Phase 5G stock マーカー）。core `StockScanTest`, `StockLedgerTest`, `StockPolicyTest`, `StockDecisionTableTest`（DT-S、DT-A）、`StockSchedulerTest`, `LocationProfile*Test`, `ContainerBlocksTest`, `BoundaryValueTest`（BV-08〜BV-13、EP-02） | 一部 | #34（`Phase5GCoverageClientTest`, `MultiplayerClientTest`）で、詳細画面の Grant / Revoke / Grant all ボタン、破損した付与ファイルの安全側停止と `StockGrantStore.recover` による復旧、Block changed、ディスペンサー・ドロッパー・設置シュルカーボックス、チャンネルのないサーバーでの代替、途中の Cancel、共有プロジェクトへの関連付け、未ロードチャンクの拒否、専用サーバーでの Location File を自動化した。残り：取得時刻の表示はクライアントテストで検査していない（core `StockLedgerTest#observedIsNeverCheckedAndCarriesItsOwnTime` のみ）、LAN オーナー、MCMaterialList のない実サーバー、付与ファイル復旧のゲーム内操作（存在しない）。実倉庫での確認は手動未実施 |
| AC-16 | Unshare で自分の領域関連付けだけ外れ記録はローカルに残る、未確定操作・保存失敗時は解除しない、素材共有を壊さない | `verifyUnshare`（4 マーカー、#34 の `Shared native UI refused unshare save PASS`：保存できないとき解除せずファイル不変、再試行で成功）。core `SharedClientUnshareTest`（14 件、保存失敗の注入を含む）、`UnshareDecisionTableTest`（DT-U、64 列）、`BindingStateTransitionTest`（ST-B） | CI自動 | - |
| AC-17 | Litematica / MaLiLib なしの専用サーバーで起動しクライアント 2 台と同期、定義変更は再取得、権限失効後は配信停止 | `verifyServerSmoke`, `verifyIsolation`（クラスロード監査）、`verifyMultiplayer`（Synchronization、Definition change、Revocation、2 回の起動での分離マーカー） | 一部 | 合成アカウント・同一マシンでの確認。認証済みプレイヤーと対象サーバー（#15）は手動未実施 |
| AC-18 | Refresh、Ignore / Clear ignored、Raw Materials、Configure、All ON/OFF、座標・回転等が使える | `Phase2ClientTest`（Refresh、Ignore / Clear ignored、Raw / テキスト書き出し）、`Phase2RegionClientTest`（Configure、Placement / All ON/OFF、原点・回転・反転） | CI自動 | - |

AC-01〜AC-10、AC-18 の画面上の根拠の詳細は [phase-2-litematica-ui.md](phase-2-litematica-ui.md) の検証表、AC-11〜AC-14・AC-17 は [phase-3-multiplayer.md](phase-3-multiplayer.md) のシナリオ表にある。

## 所有者の決定（docs/phase-4b、docs/phase-4c、#33）

| 決定 | 出典 | テスト | 状態 |
|---|---|---|---|
| 読み取りはサーバー管理者、または管理者が付与した位置に限る | phase-4b 決定 1 | `StockDecisionTableTest#dtS1_readDecisionAllCombinations`, `StockPolicyTest#r1AdminReadsAnythingR2NeedsMemberAndAGrant`, `#liveAccessFollowsDeopDemotionAndRevocation`、マーカー `Multiplayer alpha stock operator read and grant PASS` | CI自動 |
| 付与できるのはサーバー管理者かつプロジェクトの ADMINISTRATOR | phase-4b 決定 1 | `StockDecisionTableTest#dtS2_grantDecisionAllCombinations`（core）。プロトコル経由の付与はマルチで確認、詳細画面のボタンは #34 のマーカー `Phase 5G stock grant revoke buttons PASS` | CI自動 |
| 付与された読み取りは MEMBER 以上、Viewer は不可 | phase-4b 決定 2 | DT-S-1、マーカー `Multiplayer beta stock viewer UNAUTHORIZED PASS` | CI自動 |
| 機械は別集計、アイテムのシュルカーボックスは中身を数えない、設置したものは数える | phase-4b 決定 3 | `StockLedgerTest#aggregationSeparatesMachinesAndNeverCountsUncheckedAsZero`, `#aCheckedMachineAloneLeavesStorageUncheckedAndMachineReadingsStaySeparate`、`Phase4StockClientTest`（シュルカーボックスのアイテム、ホッパー）、#34 の `Phase 5G stock containers and CHANGED PASS`（設置シュルカーボックスは保管、ドロッパー・ディスペンサーは機械） | CI自動 |
| 結果はメモリのみ、代替は自分の所持品と開いた単体コンテナ | phase-4b 決定 4、2026-10-31 の決定 | `StockLedgerTest#observedIsNeverCheckedAndCarriesItsOwnTime`, `#observationsOnlyFillLocationsWithoutOtherEvidence`、`Phase4StockClientTest`（ディスクに在庫が残らない）、マーカー `Multiplayer beta stock fallback OBSERVED PASS` | CI自動 |
| Unshare はオンラインかつ同期済みのときだけ | phase-4c 決定 1 | DT-U（C5）、`SharedClientUnshareTest#eachPreconditionRefusesWithoutChangingDisk`、マーカー `Shared native UI unshare PASS` | CI自動 |
| 再参加では切り離し中の編集を書き出し、統合しない | phase-4c 決定 2 | `SharedClientUnshareTest#rejoinExportsTheLocalEditAndInstallsTheServerSnapshot`、`BindingStateTransitionTest#stB_lifecyclePathThroughEveryState`、マーカー `Multiplayer beta unshare rejoin authority PASS` | CI自動 |
| 操作は共有画面の REGIONS 行だけ、素材には Unshare がない | phase-4c 決定 3 | `SharedUiUnshareChecks`（共有 UI）。core では API が領域専用（`unshare` に種類の引数がない）、素材の DETACHED は生成時に拒否 | CI自動 |
| P3：未確定の REGIONS 操作が 1 件でもあれば解除しない（アカウント・範囲を問わない） | phase-4c 既定値、#33 で確定 | DT-U-1/2/6、`SharedClientUnshareTest#unknownAndNonDefinitiveReplayBlockAndOkResolutionStillWaitsForTheCacheCursor`、マーカー `Multiplayer beta unshare blocked until replay PASS` | CI自動 |
| P4：ジャーナル復旧後は解除しない | phase-4c 既定値、#33 で確定 | DT-U（R3 と優先順位）、`SharedClientUnshareTest#eachPreconditionRefusesWithoutChangingDisk` | coreのみ |
| 共有の設定情報が読めないときは元の領域の編集を止める | phase-4c 既定値、#33 で確定 | #34 の `SharedUiCoverageChecks`、マーカー `Shared native UI unreadable sharing metadata PASS`（壊れた共有設定で元の領域の Done と Refresh work が止まり、理由 `metadata_blocked` を表示、クリックしても `regions.json` と壊れたバイトは不変） | CI自動 |
| 同時取得の敗者には DENIED（行の内容を返さない） | 設計 §10.3、#33 | `SharedReconnectTest#concurrentClaimsHaveOneWinnerAndTheLoserLearnsTheAssigneeFromAuthority`、`verifyMultiplayer` Simultaneous claim（Member は DENIED、Administrator は CONFLICT） | CI自動 |
| 専用サーバーは Litematica / MaLiLib を必要としない | 設計 §16、#33 | `verifyServerSmoke`, `verifyIsolation`, `verifyMultiplayer` の分離マーカー | CI自動 |

## ギャップ一覧

0.1.0 の時点（2026-11-09、#15）。解消した項目は取り消し線ではなく「解消」と書く。

1. **認証済みプレイヤーと対象サーバー「たいち鯖」での受入（#15）**：AC-11・AC-17 を含め、全 AC について**手動未実施**。0.1.0 はこれを実施せずにリリースする（所有者の決定）。自動の根拠は合成アカウント・同一マシンに限られる。README・リリースノート・[acceptance-0.1.0.md](acceptance-0.1.0.md) に「未確認」と書いている。
2. **AC-01 の画像一致**：配置関係は自動で確認するが、添付画像との目視比較は未実施。
3. **AC-12 の共有保存失敗の画面表示**：解消（#34、`Shared native UI server save failure PASS`）。同一プロセス内の専用サーバーでの確認で、別プロセスのマルチプレイでは確認していない。
4. **AC-15 の未自動化項目**：#34 で詳細画面の Grant / Revoke / Grant all、付与ファイルの破損と復旧、Block changed、ディスペンサー・ドロッパー・設置シュルカーボックス、共有プロジェクトへの関連付け、未ロードチャンクの拒否、専用サーバーでの Location File が解消。残り：取得時刻の表示（core のみ）、LAN オーナー、MCMaterialList のない実サーバー（チャンネルの欠如はテスト用 mixin で再現）、付与ファイル復旧のゲーム内操作（存在しない。サーバー管理者のファイル操作）。
5. **共有の設定情報が読めないときの元の領域の編集停止（phase-4c、#33）**：解消（#34、`Shared native UI unreadable sharing metadata PASS`）。
6. **性能（設計 §13）**：#12 で core の測定と専用サーバーのトレース検査（`verifyPerformance`）を追加（[phase-5a-performance.md](phase-5a-performance.md)）。ゲーム内のフレーム・tick の計測、実際の 2,000 領域の設計図での画面計測は未実施。
7. **互換性（他の MOD、別バージョン、OS）**：#14 で監査（[compatibility.md](compatibility.md)）、#13 で OS の確認範囲を記録（[platform-verification.md](platform-verification.md)）。CI は Ubuntu のみ。Windows は Phase 0〜2 の手動記録だけ、**macOS は未確認**。Litematica / MaLiLib のないクライアントの無効化経路は自動テストなし。
8. **境界値の未実施項目**：BV-06 の 3 値目（未確定 65 件のファイル）、`StockLimits.FRESH` の境界手前側（[test-design.md](test-design.md) §2.1）。
9. **WorkStatePresentation（#22）**：取り込み済み。core `WorkStatePresentationTest`（3 件）と、クライアントの `Phase 5E footer opens MCMaterialList screen PASS` / `Phase 5E diagnostic requires explicit flag PASS` で確認している。JSTQB 文書のデシジョンテーブルはまだ作成していない。
10. **#34 と JSTQB のテストの初回 CI**：JSTQB のテストは core のみで、ローカルで合格している（core 全体で 286 件）。#34 のテストは fabric のクライアントテストで、fabric をビルドできない環境で書かれたため、リリース対象のコミットの CI が最初のコンパイル・実行になる。CI が成功するまで、#34 のマーカーに依存する行（AC-12 ほか）は PASS と扱わない。
