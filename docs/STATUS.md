# STATUS

最終更新: 2026-11-14(エージェント。0aのビルド設定と、ID、名前、アイコンの差し替え)

## 現在地

- フェーズ: 0a(作業中。ブランチ phase/0a)
- 0aのチェックリストの進み具合(PLAN のチェックボックスは未更新):
  - 済: フォークと main の作成、upstream の追加(0a以前)
  - 済: `config/dummy-google-services.json` を debug と release に `google-services.json` としてコピー(gitignore の対象で、コミットされない)
  - 済: `:desktopApp` と `:benchmarks` を settings から外し、`baselineProfile` の依存を外した
  - 済: applicationId を `io.github.katsuhidem.feedflow`(debug は `.debug` 付き)、アプリ名を `Tailfeed`、アイコンを色替え(release は緑系、debug は紫系)
  - 済: `./gradlew --quiet --console=plain :androidApp:assembleFdroidDebug` が通る(2026-11-14 18:45)。aapt2 で、package `io.github.katsuhidem.feedflow.debug`、label `Tailfeed`、targetSdk 36 を確認
  - 済: FreshRSS の構築(人)
  - 未: 上流のエージェント設定の残り(`.github/workflows`、`renovate.json`)。下の「未解決の点」の1で止めている
  - 済: 実機への導入(2026-11-14 18:50)。`adb install -r` が Success。`pm list packages` に `io.github.katsuhidem.feedflow.debug` が1行出る。起動して MainActivity が表示され、crash バッファは空
  - 未: 同期の確認(HUMAN_TASKS 6節の手順2〜4と確認)。人に依頼中
- 環境: docs/HUMAN_TASKS.md の1〜4節は完了。5節は確認2まで完了。確認3は一部だけ確認済み(下記)。
- 端末のページサイズ: 4096
- FreshRSSの公開: Tailscale Services の `svc:freshrss`。URLは `https://freshrss.<tailnet名>.ts.net`。Mac miniは `tag:service-host` 付きのホスト。`base_url` はこのURLと一致(2026-11-14 に確認)。

## 次の作業

1. 6節の結果を受けて、0aの基準線をコミットする
2. 上流のエージェント設定の残りの整理(「未解決の点」の1が決まってから)

## 人への依頼

- APK は導入済みです。docs/HUMAN_TASKS.md 6節「Phase 0aの実機確認」の手順2〜4と確認を行ってください。報告してほしい結果は、確認の7項目それぞれの可否と、購読フィードの件数です。アプリに入れるURLは `https://freshrss.<tailnet名>.ts.net/api/greader.php` です。
- 「未解決の点」の1(`.github/workflows` と `renovate.json` の扱い)を決めてください。

## 人からの連絡

- 2026-11-14: URLは端末名ではなく freshrss のサービスにした。それが正になるように計画を書き直す。

## 判断の記録

- 2026-11-14: .githooks/pre-commit の auth key の検出を、引き継ぎ文書の `tskey-[A-Za-z0-9]+` から `tskey-[a-z]+-[A-Za-z0-9]+` に変えた。元の式は docs/HUMAN_TASKS.md の説明文(`tskey-auth-…` と `'tskey-auth'`)に一致し、引き継ぎのコミット自体が止まるため。引き継ぎの確認に使う試験用の文字列と、実際のキーの形には引き続き一致する。
- 2026-11-14: 人の選択により、applicationId を `io.github.katsuhidem.feedflow`、アプリ名を `Tailfeed`、アイコンを元のアイコンの色替えにした。`namespace` と Kotlin のパッケージ名は変えていない。変更した既存ファイルは FORK_CHANGES.md にある。
- 2026-11-14: 人の指示により、FreshRSSのURLを、Mac miniのマシン名からサービス名(`svc:freshrss`)に変えた。docs/PLAN.md(前提と方針、FreshRSSの到達URL、MacでのFreshRSS構築、0bのtailnet側の準備、確かめる点、参照)と docs/HUMAN_TASKS.md(控えの表、3節、5節、6節、7節)を合わせて更新した。0bで確かめる点に「tsnetからサービスの仮想IPに届くか」を足した。

## 確認の記録

- 2026-11-14 18:50: 端末は adb で `device`、Android 17、ページサイズ 4096。最初は USB デバッグの口(ADB インターフェース)が出ておらず、人が端末で USB デバッグを入れ直して解消した。

- 2026-11-14 18:26: HUMAN_TASKS 2節と5節確認1の読み取りコマンドはすべて期待どおり。curl はサービスのURLで 200、マシン名のURLでは接続できない。
- 2026-11-14 18:26: 5節確認3の1項目目に当たる確認。`/tmp/FreshRSS.log` は 18:13 に更新され、ユーザーの更新が OK で終わっている。再起動の2項目は未実施。

## 未解決の点

1. docs/PLAN.md の0aが参照する「エージェントへの引き継ぎ」タブが、リポジトリにない。`.github/workflows` と `renovate.json` をどう扱うかの基準がない。
2. フォークの GitHub Actions が有効(実行は0件)。`release.yml` は `-all` で終わるタグの push で動き、ローカルには `1.21.0-all` タグがある。`git push --tags` はしない。
3. PLAN の「上流の AGENTS.md と CLAUDE.md に追記する」に対し、実際は置き換えて上流の内容を docs/upstream/AGENTS.upstream.md に移している。「コードの規約」の節は空のまま。
4. PLAN 0a のチェックボックスのうち、済んでいるものが未チェックのまま。チェックの更新が「PLAN の決定の書き換え」に当たるか不明なため、進み具合はこの STATUS の「現在地」に書いている。
5. ダミーの google-services.json のパッケージ名は `com.prof18.feedflow` のままだが、applicationId を変えた後も fdroid の debug ビルドは通る。googlePlay フレーバーと release はビルドしていない。
