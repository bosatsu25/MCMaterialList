# テスト計画書

JSTQB/ISTQB Foundation Level シラバス v4.0（日本語版）5.1.1 の構成に沿った、MCMaterialList のテスト計画である。技法の適用は [test-design.md](test-design.md)、受入条件との対応は [test-traceability.md](test-traceability.md) に分けている。本書は計画であり、ここに書く「CI自動」はリポジトリの CI が実行するテスト、「手動」は人が実施するテストを指す。**手動の受入テスト（#15）は本書の作成時点で実施していない。**

## 1. テストの概要

### 1.1 目的

- 設計書 [litematica-collaboration-design-v0.1.md](../litematica-collaboration-design-v0.1.md) §14.2 の受入条件 AC-01〜AC-18 と、#33 で確定した所有者の決定を満たすことを、根拠を示して確認する。
- 共有作業で起こると被害の大きい欠陥（作業状態の消失、二重実行、権限外の書き込み、他プロジェクトへの漏えい）を、リリース前に見つける。
- 既存の Litematica の操作が壊れていないこと（リグレッション）を、変更ごとに自動で確認する。
- リリースの判断に使える情報（残存リスク、未実施の項目）を利害関係者に示す。

### 1.2 スコープ

| 区分 | 含む | 含まない |
|---|---|---|
| 機能 | 素材・領域の作業状態（Claim / Release / 完了 / メモ / 進捗）、永続化と復旧、共有プロジェクト（参加、役割、同期、再接続、再送）、Location File、Check Materials、Unshare regions / Rejoin、既存の Litematica 操作との共存 | Litematica / MaLiLib 本体の機能、`.litematic` ファイルの配布 |
| 環境 | Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.154.0+26.2、Litematica 0.28.3、MaLiLib 0.29.2、JDK 25（固定基準） | 他の Minecraft 版・ローダー（#14 の互換性方針で扱う） |
| 非機能 | 性能（#12）、互換性（#14）、使用性（UI の重なり、日本語、GUI 倍率）、障害時の振る舞い | 電源断に対する絶対的な耐久性、悪意あるローカルのファイル競合（設計 §12.2、既存文書のとおり対象外） |

### 1.3 テスト対象

| テスト対象 | 場所 |
|---|---|
| core ライブラリ（Minecraft 非依存のドメイン、永続化、共有プロトコル、在庫照会の方針） | `core/src/main/java` |
| Fabric 共通・サーバー側（権限サーバー、通信、在庫照会） | `fabric/src/main` |
| Fabric クライアント側（Litematica 画面への組み込み、共有 UI） | `fabric/src/client` |
| 配布 JAR（テストコードを含まないこと、依存ライセンス） | `verifyApplicationJar`, `verifyDependencyLicenses` |

### 1.4 テストベース

- 設計書 v0.1（§5〜§14、§16 の確定値）
- Phase 文書：[phase-1](phase-1-local-work-state.md)、[phase-2](phase-2-litematica-ui.md)、[phase-3-shared-work](phase-3-shared-work.md)、[phase-3-multiplayer](phase-3-multiplayer.md)、[phase-4a](phase-4a-location-files.md)、[phase-4b](phase-4b-check-materials.md)、[phase-4c](phase-4c-unshare.md)
- プロトコルと通信の契約：[SHARED_PROTOCOL.md](SHARED_PROTOCOL.md)、[SHARED_TRANSPORT.md](SHARED_TRANSPORT.md)
- 所有者の決定（phase-4b、phase-4c、#33）
- 性能と互換性の文書（並行作業中）：`docs/phase-5a-performance.md`（#12）、`docs/compatibility.md`（#14）

### 1.5 制約

- Minecraft のクライアントテストは表示環境が必要（CI は `xvfb-run`）。1 回の `verifyPhase4` は数分〜数十分かかる（CI の制限時間 45 分）。
- マルチプレイの自動テストは 1 台のマシン上で、オフラインの合成アカウントを使う。認証済みプレイヤーと対象サーバーの確認は手動でしかできない。
- 一部の開発環境では Minecraft / Fabric の配布元に接続できず、fabric モジュールをビルドできない。その場合は core の JUnit だけをローカルで実行し、fabric のゲートは CI の結果で判断する。
- 添付画像の元 MOD の内部仕様は不明で、画像に写っていない部分は設計書で定義した動作を正とする（設計 §16）。

## 2. 前提

- `verifyPhase4` が CI で成功していることを、コード変更の取り込みの前提とする。
- テストデータはすべて合成データで、ビルドディレクトリ（Git の管理外）に置く。利用者のデータとして配布しない。
- core の JUnit はネットワークや Minecraft なしで実行でき、時刻・ナノ秒・ファイル書き込みの失敗を注入できる。

## 3. ステークホルダー

| 役割 | 責務 | テストとの関わり |
|---|---|---|
| リポジトリ所有者（プロダクトオーナー） | 仕様の決定（#33 など）、リリース判断、手動受入の実施者・承認者 | 受入条件の確定、残存リスクの受容、#15 の実施 |
| 開発担当（人・AI エージェント） | 実装、core / fabric のテスト作成、CI の維持 | コンポーネント〜システムテストの作成と実行、欠陥の修正 |
| レビュー担当 | PR のコードレビュー、静的テスト | テスト設計の妥当性、トレーサビリティの確認 |
| サーバー管理者（利用者） | 対象サーバーの運用、オペレーター権限 | 受入テストの環境提供、Check Materials の付与操作 |
| 建築参加者（利用者） | 実際の共同作業 | 受入テスト・探索的テストの参加者、使用性の評価 |

必要なトレーニング：手動受入の参加者は [test-design.md](test-design.md) §5 のチャーターと README の操作説明を事前に読む。

## 4. コミュニケーション

| 手段 | 頻度 | 内容 |
|---|---|---|
| GitHub Issue / PR | 変更ごと | テスト結果、CI のリンク、欠陥の報告と修正 |
| CI の成果物（`phase-4a-test-evidence`） | push / PR ごと | core の JUnit XML、JaCoCo レポート、スクリーンショット、各ゲートのログ |
| テスト進捗レポート | Phase ごと、またはリリース候補ごと | §11 のメトリクス |
| テスト完了レポート | テストレベルの完了時、リリース前 | §12 のテンプレート |

## 5. リスクレジスター

リスクレベル＝可能性（1 低、2 中、3 高）× 影響（1 小、2 中、3 大）。6 以上を「高」、3〜4 を「中」、2 以下を「低」とする（5.2.1、5.2.3 の定量的アプローチ）。「緩和策（テスト）」はリスクを下げる既存・新規のテストで、リスクレベルはそのテストを実施する前の評価である。

### 5.1 プロダクトリスク

| ID | リスク | 品質特性 | 可能性 | 影響 | レベル | 緩和策（テスト） |
|---|---|---|---|---|---|---|
| PR-01 | 応答喪失・再送で操作が二重に実行される、または未確定のまま消える | 信頼性 | 3 | 3 | 9 高 | `SharedReconnectTest`, `JournalStateTransitionTest`（ST-J）, `verifyMultiplayer`（lost ACK、再起動） |
| PR-02 | 権限外の書き込み、他プロジェクト・他ワールドへの漏えい | セキュリティ | 2 | 3 | 6 高 | `SharedPermissionsTest`, `PermissionDecisionTableTest`（DT-P）, `SharedBoundaryTest`, `verifyMultiplayer`（Viewer edit、偽装コマンド、Revocation） |
| PR-03 | 保存失敗・破損で作業状態が消える、空で上書きされる | 信頼性 | 2 | 3 | 6 高 | `LocalDatasetStoreTest`, `SharedStoreTest`, `SharedClientStoreTest`, `SharedAuthorityTest#saveFailureDoesNotPublishAndRestartReplaysConfirmedResult`, `Phase2ClientTest`（復旧） |
| PR-04 | 同時担当取得で 2 人とも成功、表示が食い違う | 機能適合性 | 2 | 3 | 6 高 | `SharedReconnectTest#concurrentClaims…`, `verifyMultiplayer`（Simultaneous claim） |
| PR-05 | 過大な入力（メモ、メッセージ、件数）で枯渇・クラッシュ | セキュリティ、信頼性 | 2 | 3 | 6 高 | `BoundaryValueTest`（BV-01〜BV-07）, `SharedProtocolTest`, `BoundedTransfersTest`, `BoundedAdmissionsTest` |
| PR-06 | Unshare で他人の記録・素材共有が壊れる、未確定操作を残して解除 | 機能適合性 | 2 | 3 | 6 高 | `UnshareDecisionTableTest`（DT-U）, `BindingStateTransitionTest`（ST-B）, `SharedClientUnshareTest`, `verifyUnshare` |
| PR-07 | Check Materials が未ロードのチャンクを読み込む、ルートを生成する、権限外の位置を読む | セキュリティ、性能効率性 | 2 | 3 | 6 高 | `StockScanTest`, `StockDecisionTableTest`（DT-S）, `Phase4StockClientTest`, マルチの在庫マーカー |
| PR-08 | 進捗の分母が絞り込みで変わる、行の対応がずれる | 機能適合性 | 2 | 2 | 4 中 | `Phase2ClientTest`, `Phase2RegionClientTest`, `WorkDomainTest`, `TaskStateTransitionTest`（ST-T） |
| PR-09 | 既存の Litematica 操作が使えなくなる | 互換性 | 2 | 2 | 4 中 | `Phase2ClientTest`, `Phase2RegionClientTest`（AC-18） |
| PR-10 | 専用サーバーがクライアント専用クラスを読み込み起動しない | 移植性 | 1 | 3 | 3 中 | `verifyServerSmoke`, `verifyIsolation`, `verifyMultiplayer` の分離マーカー |
| PR-11 | 大きな建築・多人数でサーバーの tick や画面が重くなる | 性能効率性 | 2 | 2 | 4 中 | `StockSchedulerTest`, `BoundaryValueTest#bv10ContainersPerTickBoundary`, `Phase4StockClientTest`（512 個）。測定は #12（`docs/phase-5a-performance.md`） |
| PR-12 | UI の重なり・日本語・GUI 倍率で操作できない | 使用性 | 2 | 2 | 4 中 | `Phase2*ClientTest`、`runSharedUiTest` の倍率・日本語検査、探索的テスト CH-03 |
| PR-13 | 他の MOD・別 OS との組み合わせで動かない | 互換性 | 2 | 2 | 4 中 | #14（`docs/compatibility.md`）、探索的テスト CH-07 |
| PR-14 | 在庫の未確認範囲を 0 と誤表示し、利用者が誤った判断をする | 使用性、機能正確性 | 1 | 2 | 2 低 | `StockLedgerTest#aggregationSeparatesMachinesAndNeverCountsUncheckedAsZero`, CH-04 |

### 5.2 プロジェクトリスク

| ID | リスク | 可能性 | 影響 | レベル | 対応 |
|---|---|---|---|---|---|
| PJ-01 | 認証済みの実プレイヤーと対象サーバーを揃えられず、#15 の受入が遅れる | 3 | 3 | 9 高 | 合成アカウントの 3 プロセス検証で代替できる範囲を広げておく。受入の日程を所有者と先に決める。未実施のままリリースする場合は残存リスクとして明記し、所有者が受容する |
| PJ-02 | 一部の開発環境で fabric をビルドできない（配布元への接続制限） | 3 | 2 | 6 高 | core の JUnit をローカルで実行し、fabric のゲートは CI で確認する。fabric のテストの追加は CI で動かせる担当が行う |
| PJ-03 | クライアントテストの時間・不安定さで CI が遅い・落ちる | 2 | 2 | 4 中 | ログのマーカーで判定し、失敗時はスクリーンショットとログを成果物に残す |
| PJ-04 | Minecraft / Litematica の更新で固定基準が古くなる | 2 | 2 | 4 中 | 基準の変更は Phase 0 のスパイクと同じ手順で、全ゲートを再実行する（#14） |
| PJ-05 | 複数のエージェントが並行して同じ文書・テストを変更する | 2 | 1 | 2 低 | Issue ごとの PR、担当ファイルの分離（本計画は #12・#14・#22 のファイルを変更しない） |

## 6. テストアプローチ

### 6.1 テストレベル（2.2.1）

| レベル | テスト対象 | 実装 | 実行 |
|---|---|---|---|
| コンポーネントテスト | core の各クラス（状態遷移、権限、コーデック、ストア、スケジューラー） | core JUnit（44 テストクラス、262 テスト。本計画で 7 クラス・37 テストを追加） | CI自動（`build` の `:core:test`）、ローカル |
| コンポーネント統合テスト | クライアントと Litematica 画面、ストア、通信のつなぎ込み | `verifyClientRestart`（`Phase0/1/2/2Region/4Location/4StockClientTest`、別プロセス復元）、`verifySharedTransport`、`runSharedUiTest` | CI自動 |
| システムテスト | クライアント 2 台＋別プロセスの専用サーバー（再起動 2 回）、Litematica なしの専用サーバー | `verifyMultiplayer`（3 プロセス）、`verifyServerSmoke` / `verifyIsolation`、`verifyStockScan` と `verifyUnshare` のマルチ部分 | CI自動 |
| 受入テスト | 認証済みの実プレイヤーと対象サーバーでの AC-01〜AC-18 | #15。[test-design.md](test-design.md) §5 のチャーターと、トレーサビリティ表の「手動」項目 | **手動。自動化しない。未実施** |

### 6.2 テストタイプ（2.2.2）

| タイプ | 内容 | 主なテスト |
|---|---|---|
| 機能テスト | 受入条件、所有者の決定、状態遷移、権限 | core 全般、Phase 2〜4 のクライアントテスト、マルチプレイ |
| 非機能：性能効率性 | tick 予算（在庫照会 8 個／tick、1 ms）、メッセージ・保存サイズの上限 | `StockSchedulerTest`、`Phase4StockClientTest`（ナノ秒は記録のみ）、#12 の測定（`docs/phase-5a-performance.md`、`RegionWorkSession` の測定） |
| 非機能：互換性・移植性 | 専用サーバーの分離、配布 JAR の内容、他 MOD・OS | `verifyIsolation`、`verifyApplicationJar`、#14（`docs/compatibility.md`） |
| 非機能：使用性 | 重なり、日本語、GUI 倍率、理由の表示 | クライアントテストの幾何検査、探索的テスト CH-03 / CH-06 |
| 非機能：セキュリティ | 権限、入力上限、パスの安全性 | DT-P、BV、ストアのパス検査（シンボリックリンク、`../`） |
| 確認テスト | 修正した欠陥を再現したテストが合格すること | 欠陥ごとに再現テストを追加してから修正する（既存の運用） |
| リグレッションテスト | 既存機能が壊れていないこと | `verifyPhase4` が Phase 0〜4 のすべてのゲートを含む。CI が push / PR ごとに実行 |
| ホワイトボックス | core のステートメント・ブランチカバレッジ | JaCoCo（`jacocoTestReport`、しきい値なし。数値は CI から記録） |

### 6.3 領域ごとの技法

| 領域 | 技法 | 記録 |
|---|---|---|
| 入力・容量の上限 | 同値分割、3 値境界値分析 | test-design §2（BV-01〜13、EP-01〜02） |
| Unshare の可否、権限、在庫照会の認可・受付 | デシジョンテーブル | test-design §3（DT-U、DT-P、DT-S、DT-A） |
| 行の状態、バインディング、ジャーナル | 状態遷移テスト（全遷移カバレッジ） | test-design §4（ST-T、ST-B、ST-J） |
| UI と実サーバー | エラー推測、探索的テスト | test-design §5 |
| core 全体 | ステートメント・ブランチカバレッジの計測 | test-design §6 |
| 通信・永続化の障害 | 障害注入（書き込みフック、応答の破棄、遅延） | 既存の `WriteHook`、`MultiplayerResultDrop`、`SharedUiDelays` など |

### 6.4 テストの独立性

開発担当がコンポーネント〜システムテストを書き、レビュー担当が PR で確認する。受入テストは所有者と利用者が行う。テスト設計の期待値（デシジョンテーブル、状態遷移表）は実装からではなく仕様から書き起こす。

### 6.5 テストデータと環境

- 合成の設計図・ワールド・アカウント。参照画像の行（4/19、5/62）は合成データで再現する。
- CI：GitHub Actions、ubuntu-24.04、Microsoft OpenJDK 25、`xvfb-run`。
- 受入：対象サーバー（Fabric、MOD 導入済み）、Windows / Linux の実クライアント、認証済みアカウント 2〜3。

## 7. 開始基準と終了基準（5.1.3）

| レベル | 開始基準 | 終了基準 |
|---|---|---|
| コンポーネント | 対象の仕様（設計書・Phase 文書・Issue）があり、core がコンパイルできる | core の全テストが合格（失敗 0）。新しい条件・上限には test-design の条件 ID 付きのテストがある。JaCoCo レポートが生成されている |
| コンポーネント統合 | コンポーネントの終了基準を満たす。CI でクライアントを起動できる | `verifyClientRestart`、`verifySharedTransport`、`runSharedUiTest` が合格し、必須マーカーがある。失敗時の証跡（スクリーンショット、ログ）が成果物にある |
| システム | 統合の終了基準を満たす | `verifyMultiplayer`、`verifyServerSmoke` / `verifyIsolation`、`verifyLocationFiles`、`verifyStockScan`、`verifyUnshare` が合格（`verifyPhase4` 全体が成功） |
| 受入 | システムの終了基準を満たすリリース候補の JAR がある。対象サーバーとアカウントが用意され、チャーターが配布済み | トレーサビリティ表の「手動」項目をすべて実施し、重大な欠陥が未解決でない。結果をテスト完了レポートにまとめ、所有者が承認する |

### 7.1 リリースの完了の定義（Definition of Done）

1. `./gradlew clean build` と `verifyPhase4 -PacceptMinecraftEula=true` がリリース対象のコミットで CI 成功している。
2. core のテストが失敗 0、スキップ 0。JaCoCo のレポートが成果物にある（しきい値は未設定。数値を記録する）。
3. [test-traceability.md](test-traceability.md) が最新で、すべての AC に自動または手動の根拠がある。「ギャップ」は解消済みか、残存リスクとして所有者が受容している。
4. 受入テスト（#15）を実施し、テスト完了レポートを所有者が承認している。未実施でリリースする場合は、その旨と理由を README とリリースノートに書く。
5. 優先度「高」のプロダクトリスク（PR-01〜PR-07）に対応するテストがすべて合格している。
6. 性能（#12）と互換性（#14）の文書に、そのリリースで確認した範囲が書かれている。
7. 配布 JAR の検査（`verifyApplicationJar`、`verifyDependencyLicenses`）が合格し、テストコードを含まない。

## 8. 優先順位付け（5.1.5）

- **リスクに基づく優先順位付け**を基本とする。リスクレベルの高い順（§5）に、テストの作成・実行・欠陥修正を行う。
- 自動テストの実行順は依存関係で決まる（`verifyPhase4` は core → クライアント → 共有 UI・通信 → マルチの順）。安く速いテストを先に実行するので、高リスク領域の多くは core の段階で先に検出される。
- 手動受入（#15）は次の順で行う：(1) PR-01・PR-04（CH-01、CH-02）、(2) PR-02・PR-06（CH-05、CH-06）、(3) PR-07・PR-14（CH-04）、(4) PR-08・PR-12（CH-03）、(5) PR-13（CH-07）。時間が足りない場合は後ろから省き、省いた項目を残存リスクとして報告する。

## 9. テストピラミッド（5.1.6）

| 層 | 既存のスイート | 数と速さ |
|---|---|---|
| 最上位：エンドツーエンド（手動） | #15 の受入、探索的テスト | 少数、手動、未実施 |
| システム | `verifyMultiplayer`（3 プロセス）、`verifyServerSmoke`、在庫・Unshare のマルチ部分 | 数シナリオ、分単位 |
| 統合（UI・サービス） | クライアント gametest 6 クラス（2 プロセス）、`sharedUiTest`、`transportTest` | 数十のシナリオ、分単位 |
| 最下位：コンポーネント | core JUnit 262 テスト | 多数、数秒〜十数秒 |

下の層ほど数が多く速い形になっている。境界値・組み合わせ・状態の網羅は core で行い、上の層は画面・通信・プロセス分離など core では確認できないことに絞る。

## 10. テストの四象限（5.1.7）

| 象限 | 内容 | 本プロジェクトのスイート |
|---|---|---|
| Q1 技術指向・チーム支援 | コンポーネント、コンポーネント統合 | core JUnit、`verifyClientRestart`、`verifySharedTransport`（CI で自動） |
| Q2 ビジネス指向・チーム支援 | 機能テスト、受入条件の例 | `Phase2ClientTest` / `Phase2RegionClientTest`（4/19、5/62 の参照例）、`runSharedUiTest`、`verifyMultiplayer` のシナリオ、`verifyStockScan`、`verifyUnshare`（自動） |
| Q3 ビジネス指向・製品批評 | 探索的テスト、使用性、受入 | #15 の受入、チャーター CH-01〜07（手動、未実施） |
| Q4 技術指向・製品批評 | スモーク、非機能 | `verifyServerSmoke` / `verifyIsolation`、`verifyApplicationJar`、在庫照会の tick 予算、#12 の性能測定、#14 の互換性、JaCoCo の構造カバレッジ |

## 11. 収集するメトリクス（5.3.1）

| 種類 | メトリクス | 取得元 |
|---|---|---|
| テスト進捗 | 実行・合格・失敗・スキップ数（レベル別） | core の JUnit XML、各ゲートの成否とマーカー |
| カバレッジ | 要件カバレッジ（AC と所有者の決定のうち自動／手動の根拠がある割合）、技法のカバレッジ（境界値・デシジョンテーブル・遷移の達成率）、コードカバレッジ（core の命令・ブランチ） | test-traceability、test-design、JaCoCo XML |
| 欠陥 | 見つかった欠陥・修正した欠陥の数と重大度、検出したテストレベル | Issue / PR |
| リスク | 残存リスク（未緩和の高リスク、ギャップ一覧の件数） | §5、test-traceability のギャップ |
| プロダクト品質 | 在庫照会の tick あたり最大コンテナ数・最大ナノ秒、性能測定値 | `Phase4StockClientTest` のログ、#12 |
| プロジェクト進捗 | CI の所要時間、手動受入の実施済みチャーター数 | GitHub Actions、完了レポート |

本計画の作成時点の値：core 262 テスト合格（追加 37）、AC 18 件のうち「CI自動」14、「一部」4（AC-01、AC-12、AC-15、AC-17。詳細はトレーサビリティ表）。手動受入の実施 0（「CI自動」の項目にも、実プレイヤー環境での手動確認が残るものがある）。コードカバレッジは CI の成果物から記録する（ローカルの参考値は test-design §6）。

## 12. テスト完了レポートのテンプレート（5.3.2）

```markdown
# テスト完了レポート：<リリース候補 / テストレベル>

## テスト概要
- 期間：YYYY-MM-DD 〜 YYYY-MM-DD
- 対象コミット / JAR：<sha> / fabric-<version>.jar
- 実施したテストレベル：コンポーネント / 統合 / システム / 受入
- CI の実行：<GitHub Actions の URL>

## テストと品質の評価
- 終了基準（test-plan §7）ごとの達成状況：
  | レベル | 基準 | 結果 |
- 合格・失敗・スキップ数（レベル別）
- 要件カバレッジ：AC <n>/18（自動 <a>、手動 <m>、未実施 <u>）
- コードカバレッジ（core、JaCoCo）：命令 <x>%、ブランチ <y>%
- 手動受入：実施したチャーター <ID 一覧>、所見

## 計画からの逸脱
- 日程・範囲・環境の差異と理由

## テストの阻害要因と回避策

## メトリクス
- 欠陥：検出 <n>（重大度別）、修正 <m>、未修正 <k>
- 在庫照会の tick あたり最大コンテナ数 / 最大ナノ秒
- 性能（#12）、互換性（#14）の確認範囲

## 未解決のリスクと未修正の欠陥
- 残存リスク（test-plan §5 の ID）と受容者
- test-traceability のギャップの状況

## 学んだ教訓

## 承認
- 所有者：<名前>、日付、判断（リリース可 / 条件付き / 不可）
```

## 13. 予算とスケジュール

個人プロジェクトのため金銭的な予算はない。自動テストは CI の実行時間（1 回 45 分以内）の範囲で行う。手動受入（#15）の日程は、対象サーバーと参加者が揃う日を所有者が決める。
