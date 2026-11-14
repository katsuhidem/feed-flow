# AGENTS.md

このリポジトリは prof18/feed-flow のタグ 1.21.0-fdroid から分岐した個人用のフォーク。上流には追随しない。
計画と決定の正本は docs/PLAN.md。計画と実際の状態が食い違う場合は、実装を進めずに docs/STATUS.md に書いて止まる。

## セッションの進め方

1. 最初に docs/STATUS.md を読む。次に docs/PLAN.md のうち、現在のフェーズの節だけを読む。
2. 作業はフェーズごとのブランチ(phase/0a など)で行う。main へのマージは人が行う。
3. 合否は自分で確かめる。ビルドの成否と、logcatに出す決まった文字列で判定する。確かめていないことを完了と書かない。
4. セッションの最後に docs/STATUS.md を更新し、コミットしてプッシュする。
5. 0bでは、試行のたびに、仮説、変更内容、到達したチェックポイントを ../tsbridge/SPIKE_LOG.md に追記する。tsbridge のコミットとプッシュも、このファイルの規則に従う。

## このフォークの前提

- 対象はAndroidだけ。iOSは Phase 5 まで触らない。:desktopApp と :benchmarks はビルドしない。
- ビルドは fdroid フレーバーを使う: ./gradlew --quiet --console=plain :androidApp:assembleFdroidDebug
- targetSdk は 36 のまま上げない。理由は docs/PLAN.md にある。
- 動作確認は、adb devices に出る Galaxy Z Fold8 の実機で行う。
- 上流の変更は、人が指定したコミットの cherry-pick でだけ取り込む。merge と rebase はしない。
- 上流(prof18/feed-flow)に、プルリクエスト、Issue、コメントを作らない。

## 人に戻す場面

次の場合は、docs/STATUS.md の「人への依頼」に、依頼の内容、docs/HUMAN_TASKS.md の該当する節、報告してほしい結果を書いて止まる。

- docs/HUMAN_TASKS.md に手順がある確認(実機での同期確認、対話ログイン、回線の切替、訳文の評価)に達した。
- docs/PLAN.md の決定を変えたい。
- Tailscaleの管理画面、端末の設定、秘密情報の変更が要る。
- 下の「してはいけないこと」に当たる操作が要る。
- 0bで、tailscale.com のソースへのパッチが必要になった。または、同じチェックポイントで、原因の仮説を変えた試行が3回続けて到達点を進められなかった。この場合は SPIKE_LOG.md に根拠をまとめる。

## してはいけないこと

- このMac miniは稼働中のFreshRSSサーバーを兼ねる。~/services/freshrss、FreshRSSのコンテナとボリューム、tailscale の設定(up、down、logout、set、serve、funnel)を変更しない。tailscale status と docker compose ps のような読み取りはよい。
- ~/.config/tsbridge/ の中身を、表示、複製、コミットしない。値が要る処理は、ファイルを読み込むスクリプトを通す。
- tailnet名、マシン名を含むURL、auth key、パスワードを、コード、テスト、ログ、文書、コミットメッセージに書かない。
- docs/PLAN.md の決定を、承認なしに書き換えない。
- git push --force と --no-verify を使わない。

## コードの規約

(docs/upstream/AGENTS.upstream.md から、このフォークに当てはまる節だけを移す)
