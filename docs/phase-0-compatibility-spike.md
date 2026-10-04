# Phase 0 compatibility spike

検証日: 2026-10-04。対象は設計 v0.1 の第15節「0: 互換性の小さな試作」のみ。

## Environment

| 項目 | 固定値 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.0+26.2 |
| Litematica | 0.28.3 / Modrinth version Yxyi6tlv |
| MaLiLib | 0.29.2 / Modrinth version 52cWF5Da |
| Java | JDK 25; 検証は Microsoft OpenJDK 25.0.4+7-LTS |
| Gradle | 9.7.1 (Wrapper) |
| Loom | net.fabricmc.fabric-loom 1.17.20 |
| mappings | 外部 mappings なし。26.2 は非難読化済み |
| OS / graphics | Windows / AMD Radeon RX 7700 XT / OpenGL |

上流タグの Gradle は9.6.0、Loom は1.17.+。本試作は利用可能な固定版9.7.1 / 1.17.20を採用。依存の `+26.2` は固定バージョン文字列の一部であり、Gradleの動的指定ではない。

## Findings

### 2画面への接続

`GuiMaterialList` と `GuiPlacementConfiguration` のそれぞれに小さなMixinを1つ追加。`initGui()V` のTAILでMaLiLibの公開 `addButton` により標準 `ButtonGeneric` を登録する。描画とヒット領域、イベント配送はMaLiLibの既存経路を利用する。

`getBrowserHeight()I` のRETURNでリストを22 GUI単位短くし、素材画面では下から58、配置画面では下から44の位置にボタンを置く。既存下部ボタン・進捗ラベルとリストの間に1行を確保する。上流画面や行Widgetをコピー・置換せず、既存計算・行操作を維持する。

両方のselectorはメソッドdescriptorを含む。`remap=false`、`require=0`、設定全体 `required=false`。Litematica/MaLiLibの版をメタデータで検査し、対象版がない場合はMixinを適用しない。配置画面の `placement` フィールドのみShadowする。ボタンの反応は画面内メッセージで、チャット送信や通信は行わない。

### localPlacementId

設計の想定: 保存・復元フックへの独自UUID追加、またはsidecar。

実際: 指定版の `SchematicPlacement` に `private final UUID hashId` と公開 `getHashId()` がある。新規生成時は `UUID.randomUUID()`、`toJson()` は `hash_code` に保存し、`fromJson()` はそのUUIDをコンストラクターへ渡す。これはJava object hashCodeではない。

採用判断: 既存UUIDをcoreの `LocalPlacementId` へ包む。新しいJSONキー、sidecar、追加の保存ファイル、保存Mixinは不要。独自永続化を増やさず、配置インスタンスの同一性をそのまま利用する。表示名・原点・設計図ファイル名からIDを導かない。

同じschematicから新たに作った配置は新UUID。既存JSONの複製でUUIDをコピーした配置は、アダプターが現在の配置一覧との重複を拒否し、配置の再作成を求める。黙って進捗を混同したり上流IDを変更したりしない。保存対象外・ファイル未保存の配置は永続性を保証しないため拒否する。

追加MODは `.litematic` に書き込まない。テストのみ専用fixtureを作成し、ID操作前後・別プロセスの復元後にバイト一致を検査する。

### Dedicated server class separation

Loomの `splitEnvironmentSourceSets()` を使用。Litematica/MaLiLibは `clientImplementation` のみ。common entrypointは初期化ログだけで、client・Litematica・MaLiLib・Minecraft clientクラスを参照しない。Mixin設定はmetadataでclient環境に限定する。配布JARにはcoreをnested jarとして含める。

`verifyIsolation` はcore/commonのコンパイル済みクラスの参照とcommon runtime classpathを検査する。`verifyServerSmoke` はテスト専用entrypointを追加した実際の専用サーバーを起動し、対象MODの不在とMCMaterialListのロードを確認して正常停止する。JVM class-load traceにもclientクラスがないことを検査する。

テストサーバーはloopbackのみ、ランダムport、認証なし。テスト専用ワールドに限定し、起動チェック後に終了。EULAはユーザーの同意を得て検証。通常ビルドはEULA同意・サーバー起動を行わず、実機検証タスクには明示フラグが必要。

## Evidence

```text
gradlew :core:test
gradlew :fabric:compileGametestJava
gradlew :fabric:verifyClientRestart
gradlew :fabric:verifyServerSmoke -PacceptMinecraftEula=true
gradlew clean verifyPhase0 -PacceptMinecraftEula=true --warning-mode all
```

- core: JUnit 4件。生成の独立性、parse/equals/serialize、null、不正・非canonical UUIDを検査。
- client: 実際のMinecraft/Loader/Mixin環境で既存2画面を開く。1280×720、GUI倍率設定1〜4、英語UI。実効倍率は1・2・3・3（設定4はMinecraftの最小GUIサイズにより3へ制限）。描画・マウスクリック・応答メッセージ、ボタン/label/listとの非重複、再初期化後の追加ボタンが1個だけであることを検査。
- 既存操作: 配置のON/OFFと復帰、All OFF/ON、sub-regionのConfigure、材料のHide available、Ignore、Clear ignored、Refreshによる再集計を実際のマウス入力で検査。テストfixtureはplayer付近の読み込み済みchunkへ置き、Litematicaの非同期schematic更新を待ってから集計する。行ボタンは見出しの非表示widgetを除外して選ぶ。
- placement identity: 新規/同一/別配置、JSON保存復元、改名・移動、コピーJSONの重複拒否、保存できない配置の拒否、schematicバイト不変。
- restart: 第1Minecraftプロセスでplacement manager JSONを検証専用ファイルに保存して終了。第2プロセスがmanagerの `loadFromJson` で復元し、同一IDとschematicバイト一致を確認。
- dedicated server: Litematica/MaLiLibなしで `Done`、`Phase 0 dedicated server isolation PASS` に到達して正常停止。実ロード記録に `fi.dy.masa.*`、`net.minecraft.client.*`、本MODのclient packageがないことを確認。
- スクリーンショット: `fabric/build/run/clientGameTest/screenshots/`。検証用ログ・fixture・ワールドはignored build配下で、追跡・配布しない。
- 通常 `runClient` の対話操作とは別に、Fabric client GameTestで実クライアントの起動・画面・クリックを検証した。
- GitHub Actionsの最小CIを追加。remote未設定・push対象外のため、hosted CIの実行は未確認。

初期検証では未実装ID/ボタンに対する失敗を確認。テスト環境のMixin package分離、26.2のGUI API、空selectionによるnull fixture、マウス座標のGUI/window変換、復元ランチャーのfixture指定、serverテストsource setのcommon classpathを実際のエラーから修正した。Gson/Guavaの注釈不足はcompile-only依存追加で解消し、警告抑制を使っていない。

最終結果: `clean verifyPhase0 -PacceptMinecraftEula=true --console=plain --warning-mode all` は **BUILD SUCCESSFUL**、終了コード0、1分31秒。core 4件は失敗・error・skipなし。両画面と既存操作の全倍率設定、別Minecraftプロセスでの復元、専用サーバーの正常起動・終了、静的/実ロードの分離検査が通過した。スクリーンショット16枚を生成。専用サーバーは `localhost:0` にbindし、`Done (1.829s)` とisolation PASSを確認した。

配布JARのmetadataに展開済み版とnested core指定があること、core内に `LocalPlacementId` とFabric用metadataがあること、配布JARにgametest/serverSmokeクラスが含まれないことも確認。独立した読み取りレビューでは重大・重要指摘なし。登録外の一時配置と実効GUI倍率に関する軽微な指摘を適用範囲として文書化した。

## Risks

- 上流更新で `initGui`、`getBrowserHeight`、`placement`、UUID/JSON仕様が変わるとアダプターを再検証する必要がある。現版に限定し、対応版の拡張は行っていない。
- optional Mixinは起動への影響を小さくするが、別MODとの変換競合や片方のhookだけの失敗を完全に防ぐものではない。実機テストでUIとリスト領域の両方を検査する。
- 日本語・特殊font/resource pack・非常に小さいwindowは今回の英語/倍率試験だけでは保証しない。添付画像全体の再現はPhase 2。
- 既存配置JSONからUUIDを削除した場合、旧形式からの移行、addonを外して上流側がID仕様を変更した場合は同一性を保証しない。
- JSON丸ごとのコピーは「新規配置作成」と区別できないため新IDへ自動変更しない。現在の配置一覧で検出し、ID使用を拒否する。
- 永続化検証はplacement managerに登録済みの配置が対象。アダプター自身は一覧への所属を検査しない。上流の `createTemporary` もファイル保存済みschematicなら保存可能フラグを持つため、全ての一時配置を拒否する保証はない。Phase 1でアダプターの利用箇所を増やす際に、保存対象・manager登録の契約を確定する。
- サーバー共有、権限、IDとprojectのbinding、永続化障害復旧はPhase 0の対象外。
- WindowsのOSHIシステム情報取得、テスト用MinecraftアカウントのRealms認証などで上流の警告が出る。追加MODの通信・テレメトリーは実装していない。

## Decision

**Phase 1へ進める。Phase 0のblockerはなし。** 2画面への接続、既存placement UUIDの永続性、専用サーバーのclass separationを実環境で確認した。Phase 1の進捗保存を実装する前に、manager登録済み・保存対象配置のIDだけを使う契約を確定する。上流版の変更、日本語や異なる描画環境、別MODとの競合は別途検証が必要。

今回の成果物はPhase 0試作であり、共同建築機能の完成版ではない。Phase 1の実装、push、PR作成、mergeは行っていない。

## Primary references

- [固定タグの設定](https://github.com/sakura-ryoko/litematica/blob/26.2-0.28.3/gradle.properties)
- [SchematicPlacement: UUIDと保存・復元](https://github.com/sakura-ryoko/litematica/blob/26.2-0.28.3/src/main/java/fi/dy/masa/litematica/schematic/placement/SchematicPlacement.java)
- [GuiMaterialList](https://github.com/sakura-ryoko/litematica/blob/26.2-0.28.3/src/main/java/fi/dy/masa/litematica/gui/GuiMaterialList.java)
- [GuiPlacementConfiguration](https://github.com/sakura-ryoko/litematica/blob/26.2-0.28.3/src/main/java/fi/dy/masa/litematica/gui/GuiPlacementConfiguration.java)
- [Fabric automated testing](https://docs.fabricmc.net/develop/automatic-testing)

後続状態: この文書の実行結果・段階0の境界は当時の記録。固定版での2画面の日本語・倍率・狭い画面・実操作の追加検証は [Phase 2C](phase-2-litematica-ui.md) に記録する。元画像のフォント・リソースパック・プレイヤーの正体は未確定のまま。
