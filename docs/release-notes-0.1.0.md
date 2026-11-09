# MCMaterialList 0.1.0 リリースノート

最初のリリース。Litematica の素材リストと配置設定の画面に、素材集め・領域ごとの建築の作業状態（担当、完了、メモ、進捗）を追加する。ひとりで使うローカル作業と、MOD を導入したサーバーでの共有作業に対応する。

- 配布ファイル：`fabric-0.1.0.jar`（MOD ID `mcmateriallist`、ライセンス MIT）
- 導入手順・使い方：[README](../README.md)
- 変更の一覧（英語）：[CHANGELOG.md](../CHANGELOG.md)
- 受入条件ごとの記録：[acceptance-0.1.0.md](acceptance-0.1.0.md)

## 確認している組み合わせ

| | 版 |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.0+26.2（クライアント・サーバーの両方） |
| Litematica | 0.28.3（クライアントのみ） |
| MaLiLib | 0.29.2（クライアントのみ） |
| Java | 25（`fabric.mod.json` は `>=25` を下限として宣言。確認は 25 のみ） |

この組み合わせ以外は確認していない。専用サーバーには Litematica / MaLiLib は不要。

## 未確認の項目

**0.1.0 は、次の確認をせずにリリースする（所有者の決定）。これらは「合格」ではなく「未確認」である。**

- **認証済みの実プレイヤーによる受入テスト**：マルチプレイの自動テストは、1 台のマシン上でオフラインの合成アカウント 2 つと、別プロセスの専用サーバーを使ったもの。
- **対象サーバー「たいち鯖」への導入と確認**。
- **macOS**：一度も実行していない（[platform-verification.md](platform-verification.md)）。
- **Windows での 0.1.0**：Windows の記録は開発初期（Phase 0〜2）の手動実行だけ。
- Java 26 以降、他の MOD との組み合わせ、ゲーム内のフレーム・tick の計測。

実際のサーバーで使う前に、クライアントの `config/mcmateriallist/`、サーバーの `<world>/mcmateriallist-shared/` と `<world>/mcmateriallist-stock/` をバックアップすること。

## 機能

| Issue | 機能 |
| --- | --- |
| #2 | **素材の作業**：保存済みの配置の素材リストで **Track materials**。行の顔から担当（Claim / Release）とメモ、緑のチェックで完了、赤い X で取り消し。進捗は絞り込みに関係なく追跡中のすべての行で数える。**Refresh work** は作業を保ち、消えた定義はアーカイブに残す。バックアップからの復旧は 2 段階の明示的な操作 |
| #3 | **領域の作業**：配置設定で **Track regions**。曖昧な定義変更は作業を止め、**Accept new definition** で意図して受け入れる。Placement の ON/OFF・変形は変えない |
| #4 | **画面の再現**：Litematica / MaLiLib の標準のスプライト・通知・配置。GUI 倍率 1〜4、狭い画面、日本語 |
| #5 | **共有作業**：MOD を導入したサーバーで、数え直した配置からプロジェクトを作り、検索・参加する。変更はサーバーの確定後に表示する。素材と領域は別々に結び付けられる |
| #6 | **権限**：Viewer / Member / Administrator。**Manage participants...** で管理。最後の Administrator は外せない |
| #7 | **再接続と応答の喪失**：再接続で直前のプロジェクトに戻る。結果の分からない操作は同じ操作 ID で再送し、1 回だけ確定する |
| #9 | **Location File**：ワールドごとにコンテナ（チェスト、トラップチェスト、樽、シュルカーボックス、ホッパー、ドロッパー、ディスペンサー）の位置を登録する |
| #10 | **Check Materials**：現在の Location File のコンテナをサーバーが数える。サーバー管理者は全位置、他の人は許可された位置だけを Member 以上で読める。確認済み・未確認・前回値・機械を区別して表示し、結果はメモリだけに残す。MOD のないサーバーでは、自分の所持品と開いたコンテナだけを OBSERVED として表示する |
| #11 | **Unshare regions / Rejoin shared regions**：1 つの配置の領域だけをローカルな切り離しコピーにする。Rejoin では切り離し中の変更をファイルに書き出してからサーバーの作業に戻す（統合はしない） |
| #22 | **MCMaterialList 画面**：下のボタンで、リストの状態・理由・次の操作・実行できる操作をまとめた画面を開く。無効なボタンはホバーで理由を説明する。開発用の診断は `-Dmcmateriallist.diagnostics=true` のときだけ動く |

画面の言語は英語と日本語（キーの一致を `verifyTranslations` で検査）。

## 品質と検証

| Issue | 内容 |
| --- | --- |
| #8 | 実クライアント 2 台と、別プロセスの専用サーバー（1 回再起動）での自動検証。サーバーが Litematica / MaLiLib なしで動き、クライアント専用クラスを読み込まないことをクラスロードの記録で確認 |
| #12 | 性能・上限・観測：素材 2,000 行・領域 2,000 件・20 人の想定で core を測定（[phase-5a-performance.md](phase-5a-performance.md)）。上限を超えるデータは CORRUPT ではなく TOO_LARGE で拒否。領域の行を索引で引くよう改善。設計 §13 の操作トレースを、メモ本文などを含めずに記録し、`verifyPerformance` で検査 |
| #14 | 互換性の監査：上流 MOD への接続点、保存データと通信の形式、更新・ダウングレードの注意（[compatibility.md](compatibility.md)）。素材画面が配置の識別を再確認し、使えない理由を表示するよう修正 |
| #34 | 実クライアントのテストを追加：設置したシュルカーボックス・ドロッパー・ディスペンサー、未ロードチャンクの拒否、Bind / Unbind、在庫チャンネルのないサーバーでの代替、Block changed、Grant / Revoke ボタン、壊れた許可ファイルの復旧、途中の Cancel、専用サーバーとその再起動後の Location File、共有画面での読めない共有設定・表示上限・サーバー保存失敗（AC-12）・Unshare の保存拒否 |
| JSTQB | テスト計画・トレーサビリティ・テスト設計（[test-plan.md](test-plan.md)、[test-traceability.md](test-traceability.md)、[test-design.md](test-design.md)）。境界値分析・同値分割、デシジョンテーブル、状態遷移で導いた core のテスト。JaCoCo によるカバレッジの記録（しきい値なし） |
| #23 | MIT ライセンス。JAR に `LICENSE` と `THIRD_PARTY_NOTICES.md` を同梱し、依存のライセンスを `verifyDependencyLicenses` で検査 |
| #13 | CI がリリースゲート `verifyRelease`（Phase 0〜4 の全ゲート、配布 JAR、依存ライセンス、翻訳）を実行し、成功時に配布 JAR を保存。OS ごとの確認範囲を [platform-verification.md](platform-verification.md) に記録 |

受入条件 AC-01〜AC-18 のうち、15 件は自動のゲートで条件全体を検査し、3 件（AC-01、AC-15、AC-17）は一部だけを自動で検査している。認証済みの実プレイヤーと「たいち鯖」での手動受入は、18 件すべてで未実施（[acceptance-0.1.0.md](acceptance-0.1.0.md)）。

## 制限と既知の問題

- 1 回の接続で同期できる共有プロジェクトは 1 つ。素材と領域を別のプロジェクトに結び付けた場合、切り替えには **Join / synchronize** か **Refresh work** が必要。
- クライアントが共有の情報を保持できる配置は 16 まで。すべて使用中なら新しい配置は「Sharing view limit reached」となり、元の領域の編集が止まる（クライアントの再起動で解消）。
- 領域のアーカイブ済み定義は自動で削除されない。1 データセットは、アーカイブを含めて 4,000 タスクまで。
- Check Materials はサーバー全体で同時 4 件、1 人 5 秒のクールダウン、1 回 512 位置まで。20 人が同時に 512 位置を確認すると、最後の人の結果まで約 1 分かかる。
- Check Materials の許可ファイルが壊れると安全側（許可なし）で止まる。ゲーム内の復旧操作はない。
- Unshare は、未確定の領域操作が 1 件でもある間と、送信記録の復旧後は実行できない（#33 で所有者が確定）。
- 共有の情報を保存したあとで古い開発版に戻すことはサポートしない。戻す前に `config/mcmateriallist/` をバックアップから戻すこと。
- Litematica / MaLiLib のないクライアント、LAN の主による Check Materials、MCMaterialList のない実サーバーへの接続は、自動テストしていない。
- 開発・テストのログに出る上流の警告（OSHI、HTTP 401 など）は MCMaterialList の不具合ではない（[compatibility.md](compatibility.md#ci-log-noise)）。
