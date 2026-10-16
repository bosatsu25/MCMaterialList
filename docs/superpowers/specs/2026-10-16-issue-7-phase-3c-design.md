# Phase 3C — 競合制御・操作結果照会・再接続の詳細設計

状態: ユーザーレビュー待ち。実装仕様案であり、実装・検証の完了報告ではない。

## 目的と根拠

Issue #7の受入条件を満たし、共有作業の切断・メッセージ欠落後にサーバーの正本へ復帰する。
対象はMinecraft 26.2 / Litematica 0.28.3 / MaLiLib 0.29.2。
確認基準はmain `ad525c3186695660de7a291d084ffdfa7bfe86fb`（PR #25）。

- Issue #7: https://github.com/bosatsu25/MCMaterialList/issues/7
- 前提PR: https://github.com/bosatsu25/MCMaterialList/pull/25
- 別工程の実2クライアント検証: https://github.com/bosatsu25/MCMaterialList/issues/8

ユーザー承認済みの方針は、保存した元操作の結果照会と、再接続時の正本スナップショット取得を分けること。
履歴が存在しない操作は未確定として保持し、勝手に更新再送しない。

## 現状と変更範囲

| 境界 | 現在のコードで確認した動作 | Phase 3Cで追加・修正すること |
| --- | --- | --- |
| `SharedProjectService` | 単一workerで直列化、row/manifestバージョン検査、権限確認後の重複判定 | 読み取り専用の操作結果照会、重複防止履歴の無断破棄を停止 |
| `ProjectArchive` / `SharedProjectStore` | 状態と結果を一つのatomic archiveとして保存 | 保存前後の障害・再起動・上限時の不変性を追加検証 |
| `SharedClientState` | delta連続性検査、低rowVersionの拒否、スナップショット下限revision | ACKを行状態へ直接適用しない。欠落時の自動再同期を接続層に接続 |
| `SharedClientArchive` / `SharedClientService` | 元binding・actor・接続・commandを保存、未知結果が編集を遮断 | 新接続上の照会結果を元操作に限定して確定する入口 |
| `SharedClientNetworking` | 新接続で状態初期化。通常ACKは元接続だけが受理可能 | 照会要求の相関、接続世代、タイムアウト、限定リトライ、再join |
| `SharedWorkController` | 元操作の通常ACK確定、元ローカル参照保持、権限剥奪時のcache遮断 | 保存操作をロードして照会、正本再取得、未同期・未確定表示 |

## 不変条件

1. actorは認証済みMinecraft接続から取得する。保存されたactorを本人確認の代替にしない。
2. world/project/material-list/region-listの4 ID、actor、元commandをすべて照合する。
3. 材料と領域は別dataset。元bindingとローカルdatasetを勝手に切り替えない。
4. 完了操作で材料数、配置有効状態、Ignore、倍率、ソート、翻訳名、`.litematic`の内容を書き換えない。
5. 更新を送る前に元操作を保存。結果確定を保存できるまで従属する編集を許可しない。
6. delta/snapshotだけが表示用正本を進める。ACK/照会結果はjournalを確定するための証拠であり、行の再適用ではない。
7. 権限剥奪・接続変更後の古いコールバックは表示・送信・bindingを変更できない。
8. 容量、通信、保存、未知schemaの失敗を空状態や成功に置換しない。

## 採用方式と代替案

採用: **読み取り専用の結果照会 + 正本の再取得**。
元operationIdを変更せず保存し、接続復帰後は照会だけを自動再送する。
更新の再送は今回の自動復旧から除外する。照会で見つからないときに未実行と断定できないためである。

代替の更新再送方式は、未送信操作を実行できる利点があるが、旧履歴の失効や元データの変更後に意図せず操作を実行する危険がある。
現在値から結果を推測する方式は、他人の更新や「完了→取消」を元操作の成功と誤認するため採用しない。

## サーバー: 競合と重複防止

既存の直列workerと遷移関数を維持する。同時Claimは先に保存が成功した要求だけが勝ち、後続要求は現在の権限・バージョン・所有状態に従って拒否される。
第三者行の変更要求はDENIEDであり、すべての敗者をCONFLICTに統一して権限ポリシーを弱めない。

新規操作ではexpectedRowVersionとexpectedManifestVersionの両方を検査する。
operationIdのキーは現在と同じproject + trusted actor + operationId。
同一内容の再送は元結果だけを返し、generation/revision/rowVersionを進めず、deltaも再発行しない。
同じキーで異なるcommandはINVALID_INPUT。異なるactorは別キーだが、そのactor自身の権限検査を省略しない。

### 履歴の寿命と容量

現行の24時間失効・4096件超過時の先頭削除を停止する。
保存済みの結果は時刻が進んでも残し、上限4096件に達したら新規操作をCAPACITYで拒否する。
既存IDの照会・同一内容の再送・join・権限管理は引き続き動作する。
容量判定は既存IDの判定より後、新規の状態変更より前に行う。
権限・manifest更新も履歴を削除せず引き継ぐ。
CAPACITYや保存失敗をクライアントが「操作成功の確定」として保存してはならない。

これはプロジェクト当たりの生涯保存可能操作数を制限する保守的な方式である。
本Issueでは上限拡大、履歴削除、プロジェクトの自動ローテーションを行わない。
将来の圧縮・epoch切替は、古いepochの操作を実行不能にする別仕様が必要になる。
アップグレード前に既に失効・削除された結果は復元できない。今回の照会では不明として扱い、過去の完全な重複防止を主張しない。

### 結果照会

`lookupOperation(trustedActor, originalCommand)`をserviceの同じ直列queueに追加する。
入力は完全な元command。読み取りだけで、時刻・archive・revisionを更新しない。

| 応答 | 条件 | 内容・クライアント動作 |
| --- | --- | --- |
| FOUND | 現在の権限と既存のreplay permission proofが許可し、元commandが完全一致 | 保存済みCommandResult。journalだけを確定する |
| NOT_RECORDED | 読めるscopeだが元IDの保存記録がない | 行なし。「未送信」「失効」「既存拒否」を区別できない。未確定のまま |
| DENIED | 現在の権限不足、旧Administrator操作の証拠不足、scope不一致 | 行・参加者情報なし。cache非表示、未確定保持 |
| INVALID_INPUT | 自分の既存IDとcommand内容が不一致 | 行なし。自動処理停止 |
| UNAVAILABLE / CAPACITY / CLOSED | 読取不能、queue満杯、停止 | 行なし。限定リトライ後は未確定保持 |

lookupの権限順序は通常のreplayと合わせる。Viewer・剥奪済みactorには過去の私的行を返さない。
FOUNDで返す元結果は保存済みのOKまたはCONFLICTであり、一時的な配送エラーを代入しない。
server enqueue/sendの各fragmentにも既存の`publishIfAuthorized`を適用する。
旧Member証拠がないlegacy操作を、新接続の成立だけで開示しない。

## 通信・永続化バージョン

wire protocolは3に更新し、新しい照会request/responseタグを追加する。
requestIdは一回の照会の相関用、operationIdは永続操作ID。両者を混同しない。
照会responseはrequestId、scope、operationId、専用lookup status、FOUND時だけ元CommandResultを持つ。
既存Status enumのordinalに新しいlookup statusを混ぜず、独立した型で明示的に符号化する。
response内外のscope/operationIdの一致、条件付きpayload、trailing bytes、長さ上限をconstructorとdecoderで検査する。

旧Helloの歴史的negotiation envelopeだけは維持し、旧peerに新規アプリメッセージを送らない。
v2 peerとの共有はUNSUPPORTEDとして停止し、ローカル作業の利用を維持する。
frame version判定、direction検査、sealed switch、fixtureサイズ、transport試験をまとめて更新する。

client保存にはwireのSnapshot/TaskCommand blobが埋め込まれているため、単なるVERSION定数変更では旧保存を壊す。
client envelopeをschema 3に更新し、schema 1/2の読み取りを専用のpersisted decoderへ分離する。
schema 2の内部blobはprotocol 2として解読し、network decoderには流さない。
schema 1の既存migrationも維持する。次の正当な保存時にschema 3へ移行する。
schema 3のjournal fieldsは現行のまま維持でき、復旧リトライの接続contextは永続元connectionとは別の一時情報にする。
server project/worldの保存形式は変わらないためschema 2/1を維持する。
未知のfuture schema/backupの拒否とバイト保持を回帰検証する。

## クライアント: 結果確定と正本の復元

### 接続世代と相関

通常ACKの元接続限定条件を緩めない。
新接続上の照会は、現在のlistener/token/actorと照会requestIdに関連付け、元journal operationを別に保持する。
受理条件は現在の接続世代、trusted actorに対応するローカルplayer、scope、operationId、taskId、元commandの一致。
旧connection IDを書き換えて元journalの出自を消さない。
専用の確定メソッドは元operationがまだ未確定で内容が一致することをCASで確認し、結果だけを書き込む。
既存の確認済み結果と異なる結果が届いた場合は上書きせず異常として止める。
確定保存が失敗した場合、照会を繰り返しても安全である。

### 復帰手順

1. Minecraftへの接続が成立したら既存Helloで交渉する。切れたMinecraft接続そのものを自動で張り直す機能は追加しない。
2. 保存bindingのactor、world namespaceと現接続を照合する。別world/別actorは自動join・照会をしない。
3. 現在の共有選択scopeを再joinし、新しいsnapshotを受け取る。複数の別scopeが保存されている場合は現在のUI選択のみ復元し、他bindingを暗黙に選択しない。
4. 元のlocal placement/native sourceのdigest・baseline・descriptorを既存検証経路で再検証する。元ローカル状態を共有snapshotで上書きしない。
5. 同scope/actorの保存された未知操作を読み、読み取り専用lookupを順に送る。bindingを再選択していても元journalを別bindingの操作へ付け替えない。
6. FOUNDは元journalを確定保存する。NOT_RECORDEDは未確定表示を維持し、更新を再送しない。
7. 確定結果のprojectRevisionが現在のcontiguous cursorを超える場合、snapshotを再取得する。結果に含まれるrowを直接適用しない。
8. 正本の連続性、native sourceの一致、保存の成功、未知操作がないことを確認して編集を再開する。

元操作の結果確定と現在bindingの表示権限は別判定である。元結果が判明しても、表示対象が変わっていたらその画面へ反映しない。
`recoveredJournalUncertain`はlookup成功だけで解除しない。backup回復によって失われた別操作がないと証明できないため、編集停止と不確実性表示を維持する。

### ACK・delta・snapshot

- ACKを受けてもrowをadoptせず、journal確定とrevision fenceに使う。欠落deltaをACKで穴埋めしない。
- delta <= cursorは無視。delta > cursor+1、manifest不一致、未知rowはRESYNC_REQUIREDとして再joinする。
- 古いACKで完了状態やneedsReconfirmationを戻さない。完了→取消の後の元完了ACKはjournalの元結果だけを確定する。
- snapshotは現在のjoin requestIdとscopeに限定し、既存minimum revision/manifest fence以下へ戻さない。
- 接続が変わると過去の受信queueと一時照会を破棄。保存journalは維持する。
- 同一worldへ再接続する場合のrevision下限は、選択bindingの保存cache cursorと既知の確定結果revisionから復元する。
- 正本が下限を満たさない場合は自動でローカルcacheを巻き戻さず、復元不整合を表示する。

### 限定リトライ

join/lookupの応答期限は既存transportと同じ30秒。失敗後の再試行間隔は1秒、2秒、4秒で最大3回。
時間はmonotonic clock。tick/render threadでsleepやdisk I/Oを行わない。
各再試行で新requestIdを使い、前回requestIdを失効させる。遅延responseは破棄する。
新しい接続、明示的なjoin、選択変更で試行contextを更新する。自動リトライで予算を無限にリセットしない。
DENIED、UNSUPPORTED、NOT_RECORDED、INVALID_INPUTは自動リトライしない。
既存の要求数・送信byte・transfer寿命・worker queue上限を維持し、lookupは1件ずつ進める。
CreateProject、membership/manifest変更、TaskCommandは自動再送しない。

## オフラインとUI

未同期共有状態、結果待ち、結果不明、権限不足、履歴上限を日本語・英語で区別して表示する。
切断時は共有編集を停止する。元のローカルdatasetに明示的に切り替えた作業はローカルのまま保存し、再joinでアップロードしない。
未確定操作を消して編集制限を解除する機能や、任意のローカル差分を自動マージする機能は追加しない。
再接続はcurrent cacheの権限確認をやり直し、剥奪されたsnapshot/結果/参加者名を見せない。

## 受入条件と検証

| #7の条件 | 決定的な試験 | 必須のassertion |
| --- | --- | --- |
| 同時Claimの勝者が一人 | 2 actorのfutureをbarrierで同時投入、処理順は固定しない | 勝者はどちらか1人、敗者は拒否、rowVersion増分1、状態保存一致 |
| 古いrow/manifest拒否 | 別row更新、manifest更新、対象削除・再追加を交差 | 権限に応じた拒否、材料数・領域mapping・別row不変 |
| 重複と再起動 | 同一ID同一/異内容、異actor、時計を24時間超進める、4096件境界 | 元結果一致、追加deltaなし、generation不変、容量時の履歴保持 |
| 完了→取消→遅延完了 | 元command/ACKを並べ替え、再起動を挟む | 完了はfalseのまま、rowVersionとcursorの逆行なし |
| ACK消失 | command保存後ACKをdrop、接続継続/交換の両方でlookup | FOUNDで元journal確定、再実行なし、最新snapshotへ収束 |
| 不明・遅延照会 | 未送信元操作、旧requestId、旧接続、別actor/world、偽taskId | 未確定維持または破棄、更新送信0、他binding変更0 |
| delta欠落 | deltaのdrop/reorder/duplicate、snapshot途中のdelta | gapで編集停止、snapshot再取得、contiguous cursor復元 |
| 保存障害 | 元journal保存、結果確定保存、server atomic replace各段階の失敗 | 保存前送信0、公開前保存、再起動後の状態/結果の組としての一貫性 |
| オフライン・再接続 | virtual clockでtimeout/backoff、接続世代変更、native source変更 | 限定回数、未同期表示、暗黙mergeなし、旧callback無効 |
| 権限回帰 | lookup処理中/fragment送信中の剥奪、Admin降格、legacy proofなし | 私的row非開示、現在権限で拒否、cache非表示 |
| 保存互換性 | 固定schema 1/2のclient fixtureとfuture primary/backup | ID/元command/確認状態保持、future bytes無変更 |

coreに時計・message schedulerを注入できる純粋な復旧状態機械を置き、上記drop/reorder/duplicateとタイムアウトを実時間待機なしで検証する。
Fabric adapterの配線は既存`transportTest`/`sharedUiTest`へ組み込み、実際の受信・join・lookup・永続確認経路を通す。
既存テストを削除・閾値緩和せず、材料数・領域・元ローカルbindingの回帰を追加する。

実行コマンドはJava 25環境で`bash gradlew --no-daemon clean build`、続いて
`xvfb-run -a bash gradlew --no-daemon verifyPhase3 -PacceptMinecraftEula=true`。
この品質ゲートには専用サーバーのclient class非混入と従来のUI/再起動検証を含める。
hosted CIで同じgateとXML/log/screenshot artifactを確認し、実装差分のレビュー後にのみマージする。

## 検証の現状と統合条件

2026-10-16の調査ではソース照合のみ実施。
`bash gradlew :core:test --console=plain`はGradle 9.7.1の取得で`Network is unreachable`となり、コンパイル・テスト開始前に停止した。
現在のshellのJavaは17、プロジェクト要求は25。実装検証にはJava 25と依存物取得可能な環境が必要。
mainのworkflow runs取得は空の結果であり、この調査から過去CI成功を独立には再確認できていない。
設計文書の追加ではテスト成功・CI成功を主張しない。

設計レビュー後に実装計画を作成し、Issue #7を一つの実装PRとして進める。
全品質ゲート・hosted CI・レビューを確認できなければマージしない。
Issue #8の専用サーバー + 実2クライアントの受入検証は別工程としてopenのまま残す。
core試験、socket試験、単一clientによるUI試験を#8完了の代わりにしない。

## 自己レビュー

- 入出力、権限、相関、容量、未確定操作、schema移行と失敗時の処理を定義した。
- 再送対象は読取専用join/lookupに限定し、「再接続」とMinecraft接続自体の自動接続を区別した。
- 重複防止のための4096件上限の運用制限と、旧版で既に消えた履歴の復元不能を明記した。
- ローカル保存・材料・領域・権限の回帰条件を受入条件へ対応付けた。
- 実装、既存CIの独立再確認、実2クライアント検証を未実施として区別した。
