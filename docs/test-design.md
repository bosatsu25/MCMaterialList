# テスト設計（テスト技法の適用記録）

JSTQB/ISTQB Foundation Level シラバス v4.0（日本語版）第4章のテスト技法を、MCMaterialList の具体的な仕様に適用した記録である。各節でテストベース、導出したテスト条件（ID）、カバレッジアイテム、実装したテスト（またはすでに同じ条件を満たしている既存テスト）、達成したカバレッジを示す。テスト計画は [test-plan.md](test-plan.md)、受入条件との対応は [test-traceability.md](test-traceability.md) にある。

- 対象バージョン：コミット `06abb98`（Phase 0-4C マージ済み、#33 / #35 反映）に本設計のテストを追加した状態。
- 新規テストはすべて core の JUnit（`core/src/test/java`）で、メソッド名かコメントに本書の条件IDを持つ。fabric モジュールのテストは変更していない。
- 「既存」と書いたテストは本設計の前からあり、同じ条件を満たしているので重複させていない。
- 実行結果（このブランチ、JDK 25、core のみ）：262 テスト、失敗 0、エラー 0、スキップ 0（設計前 225、追加 37）。CI では `verifyPhase4` の一部として同じ core テストが実行される。

## 1. 技法の選択

| 対象 | 主な技法（シラバス節） | 理由 |
|---|---|---|
| 入力・容量の上限（メモ、参加者、メッセージ、ジャーナル、タスク数、在庫照会、Location File） | 同値分割（4.2.1）、3値境界値分析（4.2.2） | 上限は定数で明示され、境界のすぐ外で拒否されることがセキュリティと永続化の前提になっている |
| コンテナ許可リスト | 同値分割 | 入力が列挙された集合で、順序がない |
| Unshare の可否（P1-P5）、行の権限、在庫照会の認可・受付 | デシジョンテーブル（4.2.3） | 複数条件の組み合わせで結果が決まり、理由に優先順位がある |
| 行の状態、領域バインディング、送信済み操作ジャーナル | 状態遷移テスト（4.2.4） | 仕様が状態と遷移で書かれている（設計 §6.1、Phase 4C の I1、#7） |
| 手動の受入（#15）、UI、実サーバー | エラー推測、探索的テスト（4.4） | 自動化できない操作感・環境差を経験ベースで補う |
| 構造カバレッジ | ステートメント／ブランチカバレッジの計測（4.3） | JaCoCo で core の未実行コードを見える化する（しきい値はまだ設定しない） |

WorkStatePresentation（#22 の変更）はこのブランチに存在しないため、そのデシジョンテーブルは作成していない（#22 のマージ後に追加する）。

## 2. 同値分割と境界値分析

3値境界値分析では、各境界について境界値とその両隣の値をカバレッジアイテムとする。存在し得ない値（負の個数など）は「実行不能」として分母から除く。

### 2.1 条件とカバレッジ

| ID | テストベース | 境界・同値クラス | カバレッジアイテムと結果 | テスト | 達成 |
|---|---|---|---|---|---|
| BV-01 | `TextLimits.NOTE_BYTES` = 4096 UTF-8 バイト（設計 §11） | 上限：4095 / 4096 有効、4097 無効。下限：0 有効、1 有効（-1 は実行不能） | ASCII で 4095・4096・4097、3バイト文字（4095=1365文字、4096、4097、4098）、4バイト文字（4096=1024文字、4097）。ローカル `TaskTransitions`、共有 `SharedCommand.SetNote`、`TaskState` の3経路 | 新規 `BoundaryValueTest#bv01aNoteBytesAsciiBoundary`, `#bv01bNoteBytesMultibyteBoundary`。既存 `SharedProtocolTest`（4096 バイトの行を送受信）、`WorkDomainTest`（6000 バイトを拒否）、`SharedPermissionsTest`（1 バイトのメモ） | 上限 3/3、下限 2/2 |
| EP-01 | 同上 | 有効：空文字列。無効：null、UTF-8 に符号化できない文字列（孤立サロゲート） | 3 クラス | 新規 `#ep01NoteValidityPartitions`。既存 `SharedAuthorityTest#notesStrictUtf8AndBinaryRoundTrip` | 3/3 |
| BV-02 | `SharedLimits.PARTICIPANTS` = 20（役割を問わず全参加者） | 19 / 20 有効、21 無効。`SharedLimits.count` の下限 -1 無効、0 / 1 有効 | スナップショット生成で 19・20・21、count で -1・0・1 | 新規 `#bv02ParticipantsBoundary`。既存 `SharedBoundaryTest#participantsAndSubmittedDefinitionCountsHaveExplicitAdmissionLimits`（権限サービス経由で 20 人目まで OK、21 人目 CAPACITY） | 6/6 |
| BV-03 | `SharedLimits.ROWS_PER_LIST` = 2000 | 1999 / 2000 有効、2001 無効 | 素材定義で 3 値 | 新規 `#bv03RowsPerListBoundary`。既存 `SharedBoundaryTest`（2001 を拒否）、`SharedProtocolTest#allFourThousandRowsAtLegalStringLimitsFitSnapshotAndFragmentBounds` | 3/3 |
| BV-04 | `SharedLimits.MESSAGE_BYTES` = 32 MiB、`FRAGMENTS` = 547 | 下限 0 無効 / 1 有効、上限 MAX-1 / MAX 有効、MAX+1 無効 | 分割転送で 5 値。MAX がちょうど 547 断片に収まること | 新規 `#bv04MessageBytesBoundary`。既存 `SharedProtocolTest`（MAX+1 の復号を確保前に拒否） | 5/5（下限の -1 は実行不能） |
| BV-05 | `SharedClientArchive.MAX_OPERATIONS` = 128 | 127 / 128 有効、129 無効 | 3 値 | 新規 `#bv05ClientJournalCapacityBoundary` | 3/3 |
| BV-06 | `SharedClientService.UNKNOWN_OPERATIONS` = 64（未確定操作） | 既存の未確定数 63 → 受理（64 件目）、64 → BUSY、65 → BUSY | 3 値 | 新規 `#bv06UnknownOperationsBoundary`（63）。既存 `SharedClientServiceTest#unknownJournalCapacityRefusesAdditionalIntentWithoutDiscardingOriginalContexts`（64） | 2/3（65 は手で編集したファイルでしか作れないため未実施） |
| BV-07 | `DatasetJsonCodec.MAX_TASKS` = 4000（有効＋アーカイブ） | 3999 / 4000 有効、4001 無効（保存・読み込みの両方） | 3999、4000（3000+1000）、4001（4001+0 と 4000+1）、読み込み時 4001 | 新規 `#bv07DatasetTaskLimitBoundary` | 3/3 |
| BV-08 | `StockLimits.COOLDOWN` = 5 秒 | 経過 4.999 秒は COOLDOWN、5.000 / 5.001 秒は ADMITTED | 3 値 | 新規 `#bv08StockCooldownBoundary`。既存 `StockPolicyTest#admissionCooldownAndCapacity`（5 秒ちょうど） | 3/3 |
| BV-09 | `StockLimits.CONCURRENT_SCANS` = 4（本番コンストラクタ） | 実行中 3 → 4 件目受理、実行中 4 → 5 件目 CAPACITY、1 件終了 → 受理 | 3 値 | 新規 `#bv09ConcurrentScansBoundary`（既存テストは容量 2 の別インスタンスで確認） | 3/3 |
| BV-10 | `StockLimits.CONTAINERS_PER_TICK` = 8 | 7 / 8 個は 1 tick、9 個は 2 tick（2 tick 目は 1 個） | 3 値 | 新規 `#bv10ContainersPerTickBoundary`。既存 `StockSchedulerTest#stockScansNeverExceedEightContainersPerTick`（512 個で毎 tick 8 以下）、Phase 4B の実サーバー測定（`Phase4StockClientTest`） | 3/3 |
| BV-11 | `LocationLimits.MAX_CONTAINERS` = 512 | 511 / 512 有効、513 無効 | `withContainer` で 3 値 | 新規 `#bv11LocationContainerBoundary`。既存 `LocationProfileCodecTest#enforcesContainerCapacityUniquenessAndNamespacedIds`（512 / 513、ファイル読み込み） | 3/3 |
| BV-12 | 表示名 1〜64 コードポイント（前後の空白を除いた後）、256 バイト | 下限 0 無効 / 1 / 2 有効、上限 63 / 64 有効 / 65 無効 | 6 値 | 新規 `#bv12LocationNameBoundary`。既存 `LocationProfileCodecTest#rejectsUnsafeOrOutOfRangeDisplayNames`（0、64、65、制御文字・双方向制御文字） | 6/6。256 バイトの境界は実行不能（後述） |
| BV-13 | 座標 \|x\|,\|z\| ≤ 30,000,000、-2048 ≤ y ≤ 2047 | 6 つの端それぞれ 3 値 | x・z 各 6 値、y 6 値 | 新規 `#bv13ContainerCoordinateBoundaries`。既存 `StockProtocolTest`（-30,000,000 / -2048 を送受信） | 18/18 |
| EP-02 | `ContainerBlocks` 許可リスト（Phase 4A、#33 確定） | 有効：保管容器 20 種（チェスト、トラップチェスト、樽、無色と16色のシュルカーボックス）、機械 3 種。無効：他のバニラ容器、他の名前空間、書式違い（大文字、名前空間なし、末尾空白）、存在しない色、空、null | 8 クラス。有効クラスは全 23 要素を個別に確認し、種別（STORAGE / MACHINE）も確認 | 新規 `#ep02ContainerAllowlistPartitions`。既存 `ContainerBlocksTest`（代表値、二重チェスト） | 8/8 |

すでに 3 値（または実行可能な値すべて）で確認済みのため追加しなかった境界：`StockLimits.MAX_POSITIONS` 512/513（`StockScanTest`）、`MAX_ITEMS` 1024/1025（`StockScanTest`, `StockProtocolTest`）、`MAX_GRANTS` 512/513（`StockPolicyTest#grantsAreCanonicalUniqueAndBounded`）、`StockLimits.FRESH` 5 分ちょうど／5 分+1 ミリ秒（`StockLedgerTest#staleAfterFiveMinutesOrAGenerationChange`、2 値。境界の手前側は同じ同値クラス）、サーバー側の操作保持数 4096（`SharedBoundaryTest#durableOperationCountIsBoundedAndOldestResultIsEvicted`）。

一部の値だけ確認している境界：`LocationLimits.MAX_PROFILES` 256（`LocationProfileServiceTest#creationStopsAtTheListingCap` は 256 件ある状態での作成拒否のみ。255 件での作成は未確認）。

### 2.2 気づいた点

- **表示名の 256 バイト上限は単独では効かない。** UTF-8 の 1 コードポイントは最大 4 バイトなので、64 コードポイント以下の名前は必ず 256 バイト以下になる。4 バイト文字 64 個がちょうど 256 バイトで、両方の上限に同時に一致する（`#bv12` で確認）。256 バイトの外側の値は作れないため、カバレッジアイテムから除いた。欠陥ではない（多重の安全策）。
- `ProjectSnapshot` は 20 人を超えるメンバーを生成時点で拒否するため、ファイルやメッセージから 21 人目が入ることはない（`#bv02`）。

## 3. デシジョンテーブル

### 3.1 DT-U：Unshare の可否（`SharedClientService.unshareCheck`）

テストベース：[phase-4c-unshare.md](phase-4c-unshare.md) の所有者決定と P1-P5（#33 で P3・P4 を正式仕様として確定）、`unshareCheck` の Javadoc。P6（保存中・要求中）とストレージ状態はクライアントのコントローラーが追加するもので、fabric 側にあり core では扱わない。

条件（T＝満たす）：

| 記号 | 条件 |
|---|---|
| C1 | 領域バインディングが SHARED（P1 前半） |
| C2 | 参照のアカウントが操作者と同じ（P1 後半） |
| C3 | ジャーナル復旧後の不確実性がない（P4） |
| C4 | 未確定の REGIONS 操作が 0 件（アカウント・範囲を問わない。P3） |
| C5 | キャッシュのカーソルがライブのカーソルと一致（P2） |
| C6 | 同じ範囲の自分の OK 結果で、キャッシュより新しいものがない（P5） |

縮約したテーブル（「-」は結果に影響しない）：

| ルール | C1 | C2 | C3 | C4 | C5 | C6 | 結果（理由） |
|---|---|---|---|---|---|---|---|
| R1 | F | - | - | - | - | - | NOT_SHARED |
| R2 | T | F | - | - | - | - | ACCOUNT_MISMATCH |
| R3 | T | T | F | - | - | - | RECOVERED_UNCERTAIN |
| R4 | T | T | T | F | - | - | UNRESOLVED_OPERATIONS（件数を表示） |
| R5 | T | T | T | T | F | - | JOIN_REQUIRED |
| R6 | T | T | T | T | T | F | REFRESH_REQUIRED |
| R7 | T | T | T | T | T | T | 許可 |

| ID | テスト条件 | テスト | カバレッジ |
|---|---|---|---|
| DT-U-1 | 各ルールの単一条件違反（他はすべて T） | 既存 `SharedClientUnshareTest#eachPreconditionRefusesWithoutChangingDisk`（R1-R6、ディスク不変も確認）、`#unshareReplacesOnlyRegionsBindingAndLeavesMaterialsJournalAndOriginalRegionsBytes`（R7） | 7/7 ルール |
| DT-U-2 | 複数条件が同時に F のときの優先順位、件数は常に報告 | 新規 `UnshareDecisionTableTest#dtU_allCombinationsFollowTheSpecifiedPriority`（展開した 2^6 = 64 列すべて） | 64/64 列 |
| DT-U-3 | C5 の境界：ライブカーソル 4 / 5 / 6（キャッシュ 5） | 新規 `#dtU_c5CursorMustMatchExactly` | 3/3 |
| DT-U-4 | C6 の境界：確定リビジョン 4 / 5 / 6（キャッシュ 5） | 新規 `#dtU_c6OwnConfirmationRevisionBoundary` | 3/3 |
| DT-U-5 | C6 の同値クラス：CONFLICT・DENIED の新しい結果、MATERIALS の結果、別範囲の結果は数えない | 新規 `#dtU_c6OnlyOwnScopeOkRegionResultsCount` | 4/4 |
| DT-U-6 | C4 の同値クラス：未確定の MATERIALS は数えない、REGIONS は他アカウント分も数える | 新規 `#dtU_c4OnlyUnknownRegionOriginalsBlock` | 2/2 |

補足：phase-4c-unshare.md の理由一覧の表は優先順位順ではない（表示文言の一覧）。優先順位の正は `UnshareReason` の宣言順と上表であり、テストはこの順序を固定する。

### 3.2 DT-P：行の権限（`ProjectPermissions.allows`）

テストベース：設計 §11 の権限表、[phase-3-shared-work.md](phase-3-shared-work.md) の Issue #6。条件は役割 4 種（非参加者、VIEWER、MEMBER、ADMINISTRATOR）× 意図 7 種（Claim、Release、SetDone(true)、SetDone(false)、SetNote、Assign、ClearAssignment）× 行 5 種（未担当・未完了、未担当・完了、自分・未完了、自分・完了、他人）。期待値は設計表からテスト内に文字で書き起こしており、実装から導いていない。

MEMBER の行（Y＝許可）：

| 意図 ＼ 行 | 未担当・未完了 | 未担当・完了 | 自分・未完了 | 自分・完了 | 他人 |
|---|---|---|---|---|---|
| Claim | Y | Y | - | - | - |
| Release | - | - | Y | Y | - |
| SetDone(true) | Y（自分への割り当てと一括） | - | Y | Y | - |
| SetDone(false) | - | - | Y | Y | - |
| SetNote | - | - | Y | Y | - |
| Assign / ClearAssignment | - | - | - | - | - |

ADMINISTRATOR はすべて許可、VIEWER と非参加者はすべて拒否。

| ID | テスト条件 | テスト | カバレッジ |
|---|---|---|---|
| DT-P-1 | 全 140 ルール | 新規 `PermissionDecisionTableTest#dtP_allRulesMatchTheDesignTable` | 140/140 |
| DT-P-2 | 再送の判定に使う役割非依存の行チェック（`memberAllows`）が MEMBER 列と一致 | 新規 `#dtP_replayRowEvidenceEqualsTheMemberColumn` | 35/35 |
| DT-P-3 | 入力欠落（スナップショット、行、意図、操作者が null）は拒否 | 新規 `#dtP_missingInputsAreDenied` | 5/5 |
| DT-P-4 | 権限サーバーでの結果（DENIED、行を返さない、バイト不変） | 既存 `SharedPermissionsTest`（`#viewerCannotMutate`, `#memberOwnOtherAndUnassignedMatrix`, `#unassignedMemberDoneClaimsAndCompletesOnce`, `#clientAdmissionUsesSameRowPolicyBeforeJournaling` ほか） | 代表ルール |

気づいた点：MEMBER が自分の担当行に Claim すると、無変更ではなく拒否になる（`memberAllows` は Claim を未担当行に限る）。設計表の「未担当行を自分に割り当てる」と一致するので欠陥ではない。UI は自分の行に Release を出すため、通常の操作では起きない。

### 3.3 DT-S / DT-A：Check Materials の認可と受付

テストベース：[phase-4b-check-materials.md](phase-4b-check-materials.md) の所有者決定 1・2 と「Admission」。

| ID | 条件 | ルール | テスト | カバレッジ |
|---|---|---|---|---|
| DT-S-1 | サーバー管理者か × 役割（なし、VIEWER、MEMBER、ADMINISTRATOR）× 付与済みか × 同じディメンションか | 管理者なら常に可。それ以外は MEMBER 以上かつ付与済みかつ同じディメンション | 新規 `StockDecisionTableTest#dtS1_readDecisionAllCombinations`。既存 `StockPolicyTest#r1AdminReadsAnythingR2NeedsMemberAndAGrant`, `#liveAccessFollowsDeopDemotionAndRevocation` | 32/32 |
| DT-S-2 | サーバー管理者か × 役割 | 付与・取り消しはサーバー管理者かつ ADMINISTRATOR のときだけ | 新規 `#dtS2_grantDecisionAllCombinations` | 8/8 |
| DT-A | C1 クールダウン中 × C2 自分のスキャンが実行中 × C3 枠がすべて使用中 | C1 → COOLDOWN、¬C1∧C2 → ADMITTED（自分の枠を再利用）、¬C1∧¬C2∧C3 → CAPACITY、それ以外 → ADMITTED | 新規 `#dtA_admissionAllCombinations`（8 組み合わせすべて、枠数の変化も確認） | 4/4 ルール、8/8 列 |

## 4. 状態遷移テスト

### 4.1 ST-T：行の状態（`TaskTransitions.apply`、設計 §6.1）

状態は操作者から見た（担当者、完了）の組：S0 未担当・未完了、S1 自分・未完了、S2 自分・完了、S3 未担当・完了、S4 他人・未完了、S5 他人・完了。イベント：Claim、Release、SetDone(true)、SetDone(false)（取り消し）、SetNote、Assign(自分)、Assign(他人)。権限は DT-P で扱い、この関数は役割を見ない。

| 状態 ＼ イベント | Claim | Release | SetDone(true) | SetDone(false) | SetNote | Assign(自分) | Assign(他人) |
|---|---|---|---|---|---|---|---|
| S0 | S1 | = | S2（自動割り当て） | = | S0 | S1 | S4 |
| S1 | = | S0 | S2 | = | S1 | = | S4 |
| S2 | = | S3 | = | S1 | S2 | = | S5 |
| S3 | S2 | = | = | S0 | S3 | S2 | S5 |
| S4 | 拒否 ALREADY_ASSIGNED | 拒否 NOT_ASSIGNEE | S5 | = | S4 | S1 | = |
| S5 | 拒否 ALREADY_ASSIGNED | 拒否 NOT_ASSIGNEE | = | S4 | S5 | S2 | = |

「=」は Unchanged（行バージョンも変わらない）。有効な遷移 24、無変更 14、拒否 4。

| ID | テスト条件 | テスト | カバレッジ |
|---|---|---|---|
| ST-T-1〜42 | 表の全セル。変化した遷移では行バージョン +1、メモ保持、完了者・完了時刻の設定／消去／保持 | 新規 `TaskStateTransitionTest#stT_allTransitionsOfTheTaskTable` | 全遷移カバレッジ 42/42（有効遷移 24/24） |
| ST-T-43 | 完了→取り消し→再度完了：SetDone は反転ではなく冪等 | 新規 `#stT_setDoneIsIdempotentAcrossUndo`。既存 `WorkDomainTest#explicitTransitionsPreserveIndependentFields`。遅れて届いた古い完了がサーバーで状態を戻さないことは既存 `SharedReconnectTest#completeUndoThenDelayedOldCompleteCannotRestoreStaleCompletion` | - |
| ST-T-44 | 無効なイベント（操作者・時刻・コマンドの欠落、Assign(null)）は全状態で拒否、同じメモは無変更 | 新規 `#stT_invalidEventsNeverTransition` | 6 状態 × 5 |
| ST-T-45 | 行バージョンの枯渇 | 既存 `WorkDomainTest#versionExhaustionAndImmutableCollectionsAreExplicit` | - |

### 4.2 ST-B：領域バインディング（Phase 4C の I1）

状態：ORIGINAL（共有なし）、SHARED（共有参照あり、キャッシュは任意）、DETACHED（ローカルの切り離しコピーのみ）。素材バインディングが DETACHED になることはない（生成時に拒否。既存 `SharedClientUnshareTest#bindingInvariantsRejectMixedStatesReusedIdentitiesAndMaterialsOrForeignPlacement`）。

| 状態 ＼ イベント | select | local | unshare | updateDetached | rejoin | cache | clearCaches |
|---|---|---|---|---|---|---|---|
| ORIGINAL | → SHARED [A] | → ORIGINAL [N1] | 拒否 NOT_SHARED [B] | 拒否 [N1] | 拒否 [N1] | 拒否 [N1] | 変化なし・書き込みなし [N1] |
| SHARED | → SHARED（新しい参照）[C] | → ORIGINAL [A] | → DETACHED [D] | 拒否 [N2] | 拒否 [N2] | → SHARED [E] | → SHARED（キャッシュなし）[F] |
| DETACHED | 拒否 [G] | 拒否 [G] | 同じ ID は冪等、別 ID は拒否 [D] | → DETACHED [H] | → SHARED（先に書き出し）[I] | 拒否 [G] | 変化なし [G] |

- [A] 既存 `SharedClientServiceTest#independentSelectionIsDurableWithoutImportingLocalRowsAndOnlyOneSideSwitches`
- [B] 既存 `SharedClientUnshareTest#eachPreconditionRefusesWithoutChangingDisk`
- [C] 既存 `SharedClientServiceTest#presendRecordsExactOriginalContextAndLateConfirmationCannotFollowNewBindingOrActor`
- [D] 既存 `SharedClientUnshareTest#unshareReplacesOnlyRegionsBindingAndLeavesMaterialsJournalAndOriginalRegionsBytes`
- [E] 既存 `SharedClientUnshareTest#unknownAndNonDefinitiveReplayBlockAndOkResolutionStillWaitsForTheCacheCursor`
- [F] 既存 `SharedClientServiceTest#admissionRejectsChangedAccountBindingScopeVersionsAndMissingCacheWithoutJournalMutation`
- [G] 既存 `SharedClientUnshareTest#selectLocalCacheAndAdmitCannotDropADetachedCopy`
- [H] 既存 `SharedClientUnshareTest#localEditsChangeOnlyTheCopyAndIgnoreTheJournal`
- [I] 既存 `SharedClientUnshareTest#rejoinExportsTheLocalEditAndInstallsTheServerSnapshot`, `#rejoinFailureAtEachStageLeavesTheCopyDetachedAndRetryRewritesTheSameExport`
- [N1] 新規 `BindingStateTransitionTest#stB_originalStateEvents`（ST-B-02〜07。拒否はファイルのバイト不変も確認）
- [N2] 新規 `BindingStateTransitionTest#stB_sharedStateRejectsDetachedOnlyEvents`（ST-B-11、12）

| ID | テスト条件 | テスト | カバレッジ |
|---|---|---|---|
| ST-B-01〜21 | 表の全セル | 上記の既存・新規テストの組み合わせ | 全遷移カバレッジ 21/21（有効 12、無効 9） |
| ST-B-22 | ORIGINAL → SHARED → SHARED → DETACHED → DETACHED → SHARED → SHARED → ORIGINAL の経路。各段階で I1（ちょうど 1 状態）、I3（素材バインディング不変）、I5（再参加でコピーを統合しない）、元のローカル ID の保持 | 新規 `#stB_lifecyclePathThroughEveryState` | 全状態 3/3、経路上の遷移 7 |

### 4.3 ST-J：送信済み操作のジャーナル（#7）

状態：ABSENT（記録なし）、UNKNOWN（送信済み・確認なし）、CONFIRMED(状態)、EVICTED（確認済みの古い記録の削除）。確定的な状態は OK、CONFLICT、DENIED、MISSING、INVALID_INPUT、非確定的な状態は SAVE_FAILED、UNAVAILABLE、CAPACITY、CLOSED（`SharedClientService.definitive`）。

| 遷移 | 条件 | テスト |
|---|---|---|
| ABSENT → UNKNOWN（admit） | 送信前に記録 | 既存 `SharedClientServiceTest#presendRecordsExactOriginalContextAndLateConfirmationCannotFollowNewBindingOrActor` |
| UNKNOWN → CONFIRMED（confirm：元の接続の ACK） | 9 状態すべてで確定する（その送信への直接の応答だから） | 新規 `JournalStateTransitionTest#stJ_confirmOnTheOriginalConnectionSettlesWithAnyStatus`（ST-J-1、2） |
| UNKNOWN → CONFIRMED（resolve：同じ操作の再送への応答） | 確定的な 5 状態だけ | 新規 `#stJ_onlyDefinitiveReplayAnswersResolve`（ST-J-3）。既存 `SharedReconnectTest#lostAckSettlesThroughExactReplayOnReplacementConnection`, `#onlyAuthoritativeReplayAnswersAreDefinitive` |
| UNKNOWN → UNKNOWN（無効：非確定的な resolve） | 4 状態は拒否され、ファイルに書かない | 新規 `#stJ_onlyDefinitiveReplayAnswersResolve`（ST-J-4）。既存 `SharedClientUnshareTest`（UNAVAILABLE） |
| UNKNOWN（無効：別アカウント・別接続の応答、依存する 2 件目の admit） | 拒否 | 既存 `SharedClientServiceTest#presend…`、`SharedReconnectTest#lostAck…` |
| CONFIRMED → CONFIRMED（同じ応答の繰り返し） | 書き込みなし | 新規 `#stJ_confirmedIsFinalAndAbsentAnswersAreRefused`（ST-J-5） |
| CONFIRMED（無効：別の応答） | 拒否、バイト不変 | 新規 同上（ST-J-6） |
| ABSENT（無効：記録のない操作への confirm / resolve） | 拒否 | 新規 同上（ST-J-7） |
| CONFIRMED → EVICTED（admit 時、確認済みが 64 件） | 最も古い確認済みから削除。UNKNOWN は削除しない。3 値 BVA（確認済み 63 / 64 件） | 新規 `#stJ_evictionOfConfirmedEntriesBoundary`（ST-J-8） |

カバレッジ：全状態 4/4、上表の有効遷移 5/5、無効遷移 5/5。

確認したこと：admit は確認済みを 63 件まで減らしてから新しい記録を追加し、未確定は 64 件で BUSY になるため、ジャーナルの件数は `MAX_OPERATIONS`（128）を超えない（最大 127）。

## 5. 経験ベースのテスト

### 5.1 エラー推測チェックリスト

自動テストの有無を右列に示す。「手動」は #15 の受入実施時に確認する。

| # | 推測した誤り | 確認方法 | 現状 |
|---|---|---|---|
| E1 | 同じ操作の二重送信で二重に完了・反転する | 連打、ラグのある接続でのクリック | 自動（`SharedReconnectTest`、ST-T-43）。実サーバーの体感は手動 |
| E2 | 保存失敗なのに成功表示 | ディスクを読み取り専用にして完了操作 | 自動（`SharedAuthorityTest#saveFailureDoesNotPublishAndRestartReplaysConfirmedResult`、`LocalDatasetStoreTest`）。クライアント表示は手動 |
| E3 | 切断・再接続で未確定操作が消える／勝手に確定する | 操作直後に回線を切る、サーバーを再起動 | 自動（`verifyMultiplayer` の lost ACK・再起動シナリオ）。実プレイヤー環境は手動 |
| E4 | 権限変更（降格・削除）の直後に古い権限で書ける | 管理者が降格した直後に相手が操作 | 自動（`SharedPermissionsTest#currentRoleGuardsHistoricalPrivilegedReplay`、`verifyMultiplayer` Revocation） |
| E5 | 長い日本語名・絵文字・結合文字でレイアウトが崩れる | 64 コードポイントの名前、4 KiB のメモ | 一部自動（GUI 倍率 1〜4、日本語、長い名前）。絵文字・結合文字の表示は手動 |
| E6 | 二重チェストの片側だけ登録・二重計上 | 片側を壊す、別のチェストと連結 | 自動（`StockScanTest`、`Phase4StockClientTest`） |
| E7 | 未ロードのチャンクを読み込んでしまう | 遠方のコンテナを登録して Check Materials | 自動（`Phase4StockClientTest`、4000 ブロック先） |
| E8 | ルートテーブル付きチェストを開封扱いにする | 未生成の宝箱を登録 | 自動（同上） |
| E9 | Unshare 中に送信中の操作が残る／素材共有が壊れる | 完了操作の直後に Unshare | 自動（DT-U、`verifyUnshare`） |
| E10 | ファイル名にプロジェクト名や `../` が入る | 名前 `../evil` の Location File | 自動（`Phase4LocationClientTest`） |
| E11 | 将来版・破損ファイルを空で上書き | ファイルを手で壊す、将来の schema 番号に書き換え | 自動（各ストアのテスト） |
| E12 | 時計の巻き戻り・大きな時差でクールダウンや鮮度が狂う | OS の時刻を変更 | 未実施（クールダウンは `Clock` 注入で境界のみ確認） |
| E13 | Litematica の既存操作（Refresh、Ignore、Configure 等）が効かなくなる | 標準操作を一通り実施 | 自動（`Phase2ClientTest`、`Phase2RegionClientTest`）。リソースパック併用は手動 |
| E14 | 別ワールド・別サーバーのデータが混ざる | 2 つのサーバーを行き来 | 一部自動（ワールド識別の core テスト、`Phase4LocationClientTest`）。実サーバー間は手動 |

### 5.2 探索的テストのチャーター（#15 手動受入用）

各チャーターは 60〜90 分のセッションを想定し、メモ・スクリーンショット・ログを残す。実施結果は test-plan.md のテスト完了レポートに記録する。**本書の作成時点ではどれも実施していない。**

| ID | チャーター（探索する対象／使うもの／見つけたい情報） |
|---|---|
| CH-01 | 認証済みの実プレイヤー 2〜3 人で、対象サーバー上の同じプロジェクトを、同時の Claim・完了・メモ編集で探索し、表示の食い違い・待機表示の残り・競合時の分かりにくさを見つける |
| CH-02 | 回線切断・クライアント強制終了・サーバー再起動を、操作の直後に起こして探索し、未確定表示、再送後の結果、Unshare の可否表示が利用者に理解できるかを確かめる |
| CH-03 | 実際の建築（素材 数百行、領域 数十件）を、Hide Done・検索・並べ替え・GUI 倍率変更を組み合わせて探索し、操作不能な重なり、進捗の分母のずれ、描画の重さを見つける |
| CH-04 | Location File と Check Materials を、本物の倉庫（大型チェスト群、シュルカーボックス、ホッパー）で探索し、確認済み・未確認・前回値・機械の区別が誤読されないかを確かめる |
| CH-05 | 管理者による参加者の追加・降格・削除と、在庫位置の付与・取り消しを、操作中の相手がいる状態で探索し、権限変更の反映の遅れや残留表示を見つける |
| CH-06 | Unshare regions → ローカル編集 → Rejoin を探索し、書き出しファイルの場所と内容、ホバー説明の分かりやすさ、素材共有と Placement への影響がないことを確かめる |
| CH-07 | 他の MOD（ミニマップ、最適化 MOD、リソースパック）と同時に導入した状態で主要画面を探索し、互換性の問題を見つける（#14 の互換性方針と合わせる） |

## 6. ホワイトボックス：ステートメントとブランチカバレッジ

- core に Gradle 標準の `jacoco` プラグイン（JaCoCo 0.8.14、Java 25 を正式サポートする最初の版）を適用した。`:core:test` の後に `jacocoTestReport` が必ず実行され、`core/build/reports/jacoco/test/jacocoTestReport.xml` と `html/index.html` を出力する。`build` と `verifyPhase4` は `:core:test` を含むので、CI でもレポートが作られ、テスト証跡と一緒にアップロードされる。
- **しきい値はまだ設定していない。** 正式な数値は CI の成果物から記録する。
- 参考値（このブランチ、core のみを抜き出したローカルの Gradle 実行、262 テスト）：命令 92.5%（25,480/27,541）、ブランチ 77.7%（2,609/3,357）、行 96.7%（2,959/3,060）。core のコードは 1 行に複数の文を書く箇所が多いため、行カバレッジは実際より高く見える。ステートメントの指標には命令カバレッジ、判定の指標にはブランチカバレッジを使う。
- パッケージ別のブランチカバレッジ（参考値）：`ui` 71.4%、`persistence` 73.5%、`shared.client` 74.3%、`work` 76.0%、`shared.transport` 76.6%、`stock` 77.8%、`shared` 77.9%、`location` 79.6%、`shared.persistence` 80.6%、`shared.protocol` 81.2%、`stock.protocol` 88.7%。未実行ブランチの多くは I/O 失敗・防御的な null 検査である。CI の数値で優先順位を決め、リスクの高い `shared.client` と `persistence` から確認する。
- fabric モジュールはゲーム内テストで動くため、計測していない。
