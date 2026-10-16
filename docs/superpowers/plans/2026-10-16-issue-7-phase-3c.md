# Phase 3C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同時更新を安全に拒否し、保存済み操作結果の照会とサーバー正本の再取得で共有作業を復元する。

**Architecture:** サーバーの単一worker・atomic archiveを維持し、読み取り専用lookupを追加する。純粋な復旧schedulerが接続世代と限定リトライを管理し、Fabric adapterと既存controllerが元journalの確定保存・正本再取得を接続する。ACKで表示行を更新しない。

**Tech Stack:** Java 25、Gradle 9.7.1、JUnit 5.14.4、Minecraft 26.2、Fabric、Litematica 0.28.3、MaLiLib 0.29.2。

**Spec:** `docs/superpowers/specs/2026-10-16-issue-7-phase-3c-design.md`（2026-10-16ユーザー承認済み）。

## Global Constraints

- 対象はMinecraft 26.2 / Litematica 0.28.3 / MaLiLib 0.29.2。
- actorは認証済みMinecraft接続から取得する。
- 材料と領域は別dataset。元bindingとローカルdatasetを勝手に切り替えない。
- 完了操作で材料数、配置有効状態、Ignore、倍率、ソート、翻訳名、`.litematic`の内容を書き換えない。
- 履歴上限4096件。24時間失効・先頭削除を停止し、上限では新規操作をCAPACITYで拒否する。
- wire protocolは3、client保存schemaは3、server project/world保存schemaは2/1を維持する。
- join/lookup応答期限30秒。失敗後の再試行間隔は1秒、2秒、4秒で最大3回。
- CreateProject、membership/manifest変更、TaskCommandは自動再送しない。
- recoveredJournalUncertainはlookup成功だけで解除しない。
- 全品質ゲート・hosted CI・レビューを確認できなければマージしない。
- Issue #8の専用サーバー + 実2クライアントの受入検証は別工程としてopenのまま残す。

## Review Focus

- 一つのscopeを材料・領域の両bindingが使う場合、同じoperationの二重照会・二重確定をしない（Task 6）。
- schema 2 client内のSnapshot/TaskCommandを新wire decoderで拒否して既存作業を壊さない（Task 2）。
- 壁時計の逆行・24時間経過でoperationIdの記憶を失わない（Task 1）。
- 結果確定保存中にactor/接続/選択が変わっても別画面やbindingへ結果を適用しない（Tasks 4/6）。
- 再joinで大きいsnapshotを転送中にdeltaと失効callbackが交差しても、旧試行から現状態へ戻さない（Tasks 5/7）。

---

## 実行準備とファイル境界

開始基準mainは`ad525c3186695660de7a291d084ffdfa7bfe86fb`。設計ブランチは`docs/issue-7-phase-3c-design`。
実行開始時にorigin/mainと進行中PRを再確認し、後続変更があれば差分を読んでから計画を適用する。
この調査用cloneはユーザーの既存checkoutとは独立している。実装は専用branchで行い、無関係な変更を巻き戻さない。

既知の環境制約: shell Javaは17、Gradle取得はネットワーク到達不可で停止した。これはテストの失敗結果ではなく、テスト未開始である。
Java 25と依存取得可能な環境を用意できるか実行開始時に確認する。取得不能の場合も変更・静的レビューは進められるが、RED/GREENやbuild成功を主張せず、未確認のままmergeしない。

新規クラスは`OperationLookup`（照会domain）、`SharedRecovery`（純粋scheduler）、`SharedRecoveryNetworking`（Fabric復旧adapter）の3責務に分ける。
既存のcodec/store/service/controllerはその境界だけを変更し、無関係なUI再設計をしない。

### Task 1: サーバーの永続重複防止と結果照会

**Files:** Create `core/src/main/java/dev/mcmateriallist/core/shared/OperationLookup.java`。
Modify 同packageの`SharedProjectService.java`。
Test 同test packageの`SharedRecoveryAuthorityTest.java`（新規）、`SharedAuthorityTest.java`、`SharedPermissionsTest.java`、`SharedStoreTest.java`。

**Interfaces:** `OperationLookup`は`record OperationLookup(Status status, SharedEvents.CommandResult result)`。
専用enumは`FOUND, NOT_RECORDED, DENIED, INVALID_INPUT, UNAVAILABLE, CAPACITY, CLOSED`。
FOUNDと非null resultを同値とする。serviceは`CompletionStage<OperationLookup> lookupOperation(UUID actor, SharedCommand original)`を公開する。
Task 3のserver adapterがこのメソッドを呼ぶ。

- [ ] **Step 1: 失敗するテストを追加。** 次を独立した`@Test`として書く。
  - `concurrentClaimsCommitExactlyOneWinner`: 2 actorをMemberにしてbarrierからClaim、`assertEquals(1, OKの数)`、所有者は勝者、rowVersion=1。
  - `lookupNeverMutatesAndSurvivesRestart`: ACKを捨て再起動し、`assertEquals(originalResult, lookup.result())`、generation不変、delta再発行0。
  - `retainedResultsSurviveClockChangeAndCapacity`: 24時間超過と時計逆行後もFOUND。4096件の有効archiveをfixtureとして保存し、既存IDは再生可、新規操作はCAPACITY、archive bytes不変。
  - `lookupDeniesRevokedAndDemotedPrivilegedActors`: 現在権限・memberAllowed・legacy falseを検査し、`assertNull(denied.result())`。
  - `changedCommandCannotReuseOperationId`: 同actorの異内容はINVALID_INPUT、別actorに元結果を開示しない。
- [ ] **Step 2: REDを確認。** `bash gradlew :core:test --tests '*SharedRecoveryAuthorityTest' --console=plain`。期待: 新API不存在など対象の失敗。環境エラーはREDと数えない。
- [ ] **Step 3: 実装。** lookupを既存のbounded workerに載せ、current authority→既存operationのpermission proof→command一致の順で検査。通常commandも同じreplay権限判定を共有する。履歴は`new ArrayList<>(archive.operations())`で全件保持。新規操作の容量判定は既存ID再生後・遷移前に置く。membership/manifest保存でも全履歴保持。
- [ ] **Step 4: GREENと既存回帰。** 上のselectorに`*SharedAuthorityTest`、`*SharedPermissionsTest`、`*SharedStoreTest`を加えて実行。全件成功、skipなしを確認。
- [ ] **Step 5: commit。** 関連ファイルのみ追加し、`feat: retain durable operations and expose authorized lookup`。

### Task 2: 旧保存を読めるprotocol 3とclient schema 3

**Files:** Modify `core/src/main/java/dev/mcmateriallist/core/shared/protocol/{SharedMessage,SharedProtocol,SharedTransfers}.java`、`core/src/main/java/dev/mcmateriallist/core/shared/client/{SharedClientCodec,SharedClientStore}.java`。
Test `SharedProtocolTest.java`、`SharedLegacyMigrationTest.java`、`SharedSchemaHeaderTest.java`、`client/{SharedClientCodecTest,SharedClientStoreTest}.java`。
Create `core/src/test/resources/shared-v2/client.json`（旧mainのcodecから採取した合成データ）。

**Interfaces:** `SharedMessage.LookupOperation(UUID requestId, SharedCommand original)`（tag 15）。
`SharedMessage.OperationResult(UUID requestId, ProjectScope scope, UUID operationId, OperationLookup lookup)`（tag 16）。
`SharedProtocol.decodePersistedV2(byte[] bytes)`は永続Snapshot/TaskCommandの旧v2だけを受理する。既存`decodeLegacyPersisted`はv1用として保持する。
schema 3 clientは現在のwire 3を埋め込み、schema 2/1は専用decoderへ振り分ける。

- [ ] **Step 1: 旧mainから固定fixtureを採取。** 新VERSIONのencoderで旧fixtureを作らない。材料・領域cache、未知/確定command、元connection、recovered flagを含め、採取元commitとSHA-256をtest resourceの説明へ記録する。
- [ ] **Step 2: REDテスト。** `v2ClientMigratesWithoutIdentityLoss`で元ID/command/confirmation全項目一致、読み取りだけではbytes不変をassert。`lookupRoundTripAndRejectsInconsistentPayload`で全status往復、FOUND内外scope/operationId不一致、非FOUND payload、末尾byte、無効tagをreject。`oldNetworkPeersFailClosedButLocalArchivesLoad`でv1/2アプリwire拒否・historical Hello許可をassert。
- [ ] **Step 3: RED確認。** `bash gradlew :core:test --tests '*SharedProtocolTest' --tests '*SharedLegacyMigrationTest' --tests '*SharedClientCodecTest' --tests '*SharedClientStoreTest' --console=plain`。
- [ ] **Step 4: 実装。** protocol/frame定数3、lookupタグと専用statusの明示的コード、条件付きpayload validation、client schema3とv1/2 decoderを実装。project/world schemaは変更しない。既存の未来schema fixtureは新しいsupported ceilingより大きい値へ明示的に調整し、拒否条件を維持する。
- [ ] **Step 5: GREEN確認。** 上記selectorと`*SharedSchemaHeaderTest`を実行。最大message fixtureサイズ、過去のlegacy permission proof、future primary/backup bytes不変も成功を確認。
- [ ] **Step 6: commit。** `feat: add lookup protocol with backward compatible client persistence`。

### Task 3: 認証済みFabric serverへのlookup配線

**Files:** Modify `fabric/src/main/java/dev/mcmateriallist/fabric/shared/SharedServerNetworking.java`。
Test `fabric/src/transportTest/java/dev/mcmateriallist/fabric/test/SharedTransportClientTest.java`と既存のobservation/mixin fixture。

**Interfaces:** Task 2のLookupOperationは既存subscription scopeでのみdispatchする。新server completion recordは`LookupReply(UUID token, UUID requestId, SharedCommand original, OperationLookup lookup)`。
clientへOperationResultを返すときにoriginalからscope/operationIdを取得する。

- [ ] **Step 1: REDテスト。** `lookupUsesAuthenticatedActorAndCurrentSubscription`でpayloadに本人確認を委ねないこと、別scope拒否、queue満杯で上限維持をassert。`revocationBetweenLookupAndSendLeaksNoResult`でlookup完了後・fragment送信前の剥奪時に私的rowが送られないことをassert。
- [ ] **Step 2: RED確認。** `xvfb-run -a bash gradlew :fabric:verifySharedTransport -PacceptMinecraftEula=true --console=plain`。旧peer fixtureはprotocol2も含めUNSUPPORTEDを検証する。
- [ ] **Step 3: 実装。** dispatch→Task 1 lookup→LookupReply→既存outgoing budgetを使う。request reserveのreleaseを成功/例外双方で一回だけ実行する。row付きresponseは各fragmentで現在権限を再検査し、旧tokenへの送信を止める。方向違反のserverbound responseは拒否。
- [ ] **Step 4: GREEN確認。** 同gateでlookup往復・権限剥奪・legacy・既存transport全件成功を確認。
- [ ] **Step 5: commit。** `feat: route operation lookups through authenticated server sessions`。

### Task 4: ACKから行状態を分離し、元journalを安全に確定

**Files:** Modify `core/src/main/java/dev/mcmateriallist/core/shared/transport/SharedClientState.java`、`core/src/main/java/dev/mcmateriallist/core/shared/client/SharedClientService.java`。
Test `SharedClientStateTest.java`、`client/SharedClientServiceTest.java`。

**Interfaces:** stateへ`void requireAuthority(ProjectScope scope, long revision, long manifestVersion)`を追加。fenceを単調増加させ、cursor未達ならRESYNC_REQUIRED。`joining`前に保存cacheの下限を復元できること。
serviceへ`CompletableFuture<SharedClientStore.Result> confirmLookup(LocalPlacementId placement, SharedClientArchive.Operation original, SharedEvents.CommandResult result)`を追加する。
現在接続の照会相関はFabricが検証し、serviceは元journalの完全一致とCASを検証する。通常`confirm`の元接続検査は維持する。

- [ ] **Step 1: REDテスト。** `completeUndoDelayedAckDoesNotReapplyRow`でrow.done=false、cursor/marker不変。`futureAckRequiresSnapshotInsteadOfFillingDeltaGap`でRESYNC_REQUIRED。`lookupConfirmsOnlyExactOriginalJournal`で元connection不変、別actor/scope/command拒否。`confirmationCannotOverwriteAndRecoveryFlagPersists`で矛盾結果拒否、同結果冪等、recovered flag維持。
- [ ] **Step 2: RED確認。** `bash gradlew :core:test --tests '*SharedClientStateTest' --tests '*SharedClientServiceTest' --console=plain`。
- [ ] **Step 3: 実装。** CommandResult分岐からrow adoptを除去。選択scopeの既知revisionをfenceへ反映する。confirmLookupはloaded journalの同一Operationだけを更新し、cache/bindingは変更しない。FOUND以外と非永続statusを確定しない。
- [ ] **Step 4: GREEN確認。** selectorと`*SharedLifecycleRegressionTest`を実行。確定保存失敗後もhasUnknown=true、再照会後の保存成功でだけfalseをassert。
- [ ] **Step 5: commit。** `fix: isolate operation confirmation from authoritative client rows`。

### Task 5: 純粋な復旧schedulerと限定リトライ

**Files:** Create `core/src/main/java/dev/mcmateriallist/core/shared/client/SharedRecovery.java`、対応`SharedRecoveryTest.java`。

**Interfaces:** schedulerは送信・disk I/Oをしない。`Context(UUID connection, UUID actor, ProjectScope scope)`、`Action(UUID requestId, Kind kind, SharedCommand original)`をnested record、KindはJOIN/LOOKUPとする。
`void start(Context context, List<SharedCommand> originals, long nowMillis)`、`Optional<Action> poll(long nowMillis)`、`boolean complete(Context context, UUID requestId, Completion outcome, long nowMillis)`、`void cancel()`を公開する。
CompletionはSUCCESS / UNRESOLVED / RETRY / STOP。original=nullはJOINのみ。start時にJOIN、そのSUCCESS後に重複排除済みLOOKUPを順次発行。LOOKUPのFOUNDはSUCCESS、NOT_RECORDEDはUNRESOLVEDで次の照会へ進むが、journalの編集許可は別に判断する。JOINのUNRESOLVEDはSTOPと同様に扱う。

- [ ] **Step 1: REDテスト。** 仮想時計で`initialAttemptPlusThreeRetries`の30秒期限と1000/2000/4000ms待機、4回目失敗後poll空をassert。`staleAttemptCannotCompleteNewRequest`、`newConnectionCancelsOldCallbacks`、`oneLookupAtATimeAndNoTaskCommandAction`を追加。clock逆行時はcancelして明示再開を要求し、オーバーフローで即時再試行を繰り返さない。
- [ ] **Step 2: RED確認。** `bash gradlew :core:test --tests '*SharedRecoveryTest' --console=plain`。
- [ ] **Step 3: 実装。** 一回のstartを一つの予算とし、自動再joinで予算を更新しない。responseはContext/requestId一致だけ受理。NOT_RECORDEDはUNRESOLVED、DENIED/INVALID_INPUT/UNSUPPORTEDはSTOP、transport timeout/UNAVAILABLE等はRETRY、正常応答はSUCCESSとする。STOPでは後続lookupも発行しない。
- [ ] **Step 4: GREEN確認。** 同selectorで実時間sleepなし・接続差替え・snapshot転送中の古い完了通知を検証。
- [ ] **Step 5: commit。** `feat: add bounded deterministic shared recovery scheduler`。

### Task 6: クライアント再join・lookup・元journal保存・未同期表示

**Files:** Create `fabric/src/client/java/dev/mcmateriallist/fabric/client/shared/SharedRecoveryNetworking.java`。
Modify 同packageの`SharedClientNetworking.java`、`SharedWorkController.java`、必要箇所の`SharedWorkScreen.java`。
Modify `fabric/src/client/resources/assets/mcmateriallist/lang/{ja_jp,en_us}.json`。
Test 既存transport/UI testとTask 5の純粋scheduler。

**Interfaces:** Networkingへ`UUID lookup(UUID requestId, SharedCommand original)`、`UUID join(UUID requestId, ProjectScope scope)`（既存joinの内部オーバーロード）、`SharedMessage.OperationResult lookupReply(UUID requestId)`、`UUID connectionId()`を追加する（未接続はnull）。
join/lookupはschedulerのrequestIdをそのまま送信・相関し、内部で別UUIDを生成しない。新adapterは`SharedRecovery`を所有し、controllerからcurrent actor、scope、元Operation一覧を受け、lookup相関成功時にだけTask 4のconfirmLookupへ渡す。response受付時・保存完了時のlistener/token/actor/選択検査を別々に行う。

- [ ] **Step 1: REDテスト。** `lostAckResolvedAfterNewConnectionWithoutCommandReplay`でTaskCommand送信総数1、保存confirmation一致。`sharedScopeAcrossBothDatasetsConfirmsOnce`でlookup・確定各1。`selectionOrActorChangesDuringConfirmation`で元journalだけ変更、現binding/cache変更0。`notRecordedRemainsUnknownWithoutSilentUpload`で未知のまま、TaskCommand追加0。`restoredRevisionFenceRejectsOlderServerSnapshot`で旧snapshotによる編集再開なし。
- [ ] **Step 2: RED確認。** `xvfb-run -a bash gradlew :fabric:verifySharedTransport :fabric:runSharedUiTest -PacceptMinecraftEula=true --console=plain`。
- [ ] **Step 3: 実装。** 新接続の交渉完了後に選択bindingのactor/world照合→join→native source再検証→lookup。通常ACK期限超過時も保存元操作のlookupを開始する。未ロードplacementは既存attach/loadで取得後に復旧し、恣意的な全ファイル走査は追加しない。
- [ ] **Step 4: 状態復元を接続。** 結果の確定保存前は編集遮断。FOUND revision > cursorなら再join。NOT_RECORDED・recovered flag・別actor/world・剥奪時は未確定/非表示を維持。別scope bindingの選択・cache・元ローカルを触らない。disconnectで一時response/queueを破棄しjournalを保持。
- [ ] **Step 5: 表示を追加。** `shared.recovery.unsynchronized`=「未同期」/「Unsynchronized」、`unknown`=「操作結果が不明です」/「Operation outcome unknown」、`retry_exhausted`=「再同期を完了できませんでした」/「Resynchronization could not complete」、`capacity`=「操作履歴の上限に達しました」/「Operation history limit reached」。既存キーと整合し、UIに内部IDを露出しない。
- [ ] **Step 6: GREEN確認。** Step 2のgateを実行。ローカル切替で保持された材料数、領域descriptor、`.litematic` digest、倍率、Ignore、ソート状態を前後比較する。
- [ ] **Step 7: commit。** `feat: recover shared authority and durable outcomes after reconnect`。

### Task 7: 障害注入、全回帰、証跡、PR・レビュー・統合

**Files:** Modify `SharedRecoveryAuthorityTest.java`、`SharedStoreTest.java`、transport/UI test fixtures、`.github/workflows/phase-0.yml`、`docs/{SHARED_PROTOCOL,SHARED_TRANSPORT,phase-3-shared-work}.md`。

**Interfaces:** 既存`SharedProjectStore.WriteHook`のBEFORE_BACKUP_REPLACE/BEFORE_PRIMARY_REPLACEを使用する。新しい本番向け障害APIは作らない。test-only gateでdrop/reorder/duplicateを実際のadapter経路に挿入する。

- [ ] **Step 1: 失敗・復帰の試験を追加。** `stateAndOutcomeRemainAtomicAcrossEveryWriteFailure`で保存前後のprimary/backupと再起動をassert。`snapshotDeltaAndLateResponseInterleavingsConverge`でsnapshot中のdelta、欠落delta、古いlookup responseを交差させ、最終revisionと全rowをauthorityに比較する。
- [ ] **Step 2: RED→GREEN確認。** 修正が必要な失敗には最小修正を加え、該当selector/transport gateを再実行。既に満たす契約は新しい回帰試験として記録し、無理に本番を壊してREDを作らない。
- [ ] **Step 3: 全gate。** Java 25で`bash gradlew --no-daemon clean build`、`xvfb-run -a bash gradlew --no-daemon verifyPhase3 -PacceptMinecraftEula=true`。XMLのtests/failures/errors/skipped、プロセス終了値、server client-class非混入を確認。ネットワークやgraphicsの障害は未実施として残す。
- [ ] **Step 4: CIと文書。** workflow表示名とartifact名をPhase 3Cへ更新し、既存gateを維持。新規XML/log/screenshotも収集。docsにschema3、wire3、永続結果上限、復旧状態、旧版履歴の限界、実行commit・検証結果・未実施を記録する。
- [ ] **Step 5: commit・PR。** `test: verify phase 3c failure recovery and retained regressions`として関連差分だけcommit。設計branchの文書を保持して実装branchをpushし、Issue #7用の一つのPRを作成する。PR本文に具体的な変更、実行コマンド・結果、4096件制限、#8未実施を記載する。
- [ ] **Step 6: hosted CIとレビュー。** PRの最新head SHAの必須checksとworkflow jobs/artifactsを確認。採用した実行方式に応じて独立レビューを行い、指摘は再現/根拠を確認して修正・再検証する。自分の確認を他者approvalとして扱わない。
- [ ] **Step 7: 条件付きmerge。** 最新headがレビュー対象と一致、必須checks成功、blocking reviewなし、未解決の安全性懸念なしの場合だけ通常のmerge APIで統合する。保護を回避しない。merge後mainのCIを確認し、Issue #7の状態と#8依存解除を報告する。#8を閉じない。

## 自己レビューと引継ぎ

設計の競合・結果照会・履歴寿命はTask 1、wire/保存移行はTask 2、server認証境界はTask 3、ACKとjournal分離はTask 4、時刻/予算はTask 5、再接続と未同期表示はTask 6、障害・全回帰・統合はTask 7に対応する。
Review Focusの5条件はそれぞれのtest stepへ反映した。全taskの境界で成果と未実施を記録し、未検証チェックボックスを埋めない。

レビュー待ち: この計画の内容と実行方式。推奨は同一セッションで順次実装する方式。wire移行とjournal/接続相関の依存が強いため、一人が文脈を保持し、最後に独立レビューを行う。
この文書を保存した段階では製品コード、回帰テスト、実2クライアント検証は未実施。
