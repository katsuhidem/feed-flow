# STATUS

最終更新: 2026-11-14(エージェント。0b を開始し、チェックポイント4まで到達)

## 現在地

- フェーズ: 0b(作業中。feed-flow と tsbridge の両方でブランチ phase/0b)。詳細は ../tsbridge/SPIKE_LOG.md
  - 到達: CP1〜CP4(CP2 は起動から running まで約 4.1〜4.3 秒、CP3 は購読 3 件)。AAR は 8.2MB(arm64)
  - 未: CP5(同じプロセスでの2回目の `Start` が `needs-login` のまま止まる。調査中)。CP6〜CP8 は未着手
  - これまでの CP2〜CP4 は、端末の公式 Tailscale アプリが動いたままの状態で確かめた。完了条件の確認は、公式アプリを止めてやり直す
- 0a: 完了(ブランチ phase/0a。main へのマージ待ち)。完了条件(実機で購読一覧と未読記事が同期され、既読がFreshRSSに反映される)を満たし、チェックリストもすべて済んだ
- 0aの基準線: phase/0a の先頭(fdroid debug、`io.github.katsuhidem.feedflow.debug`)
- 0aのチェックリストの進み具合(PLAN のチェックボックスは未更新):
  - 済: フォークと main の作成、upstream の追加(0a以前)
  - 済: 上流のエージェント設定の整理。`.mcp.json`、`.ai/skills` などは引き継ぎ時に削除済み。`.github/workflows`(12ファイル)と `renovate.json` を人の決定で削除(2026-11-14)
  - 済: `config/dummy-google-services.json` を debug と release に `google-services.json` としてコピー(gitignore の対象で、コミットされない)
  - 済: `:desktopApp` と `:benchmarks` を settings から外し、`baselineProfile` の依存を外した
  - 済: applicationId を `io.github.katsuhidem.feedflow`(debug は `.debug` 付き)、アプリ名を `Tailfeed`、アイコンを色替え(release は緑系、debug は紫系)
  - 済: `./gradlew --quiet --console=plain :androidApp:assembleFdroidDebug` が通る(2026-11-14 18:45)。aapt2 で、package `io.github.katsuhidem.feedflow.debug`、label `Tailfeed`、targetSdk 36 を確認
  - 済: FreshRSS の構築(人)
  - 済: 実機への導入(2026-11-14 18:50)。`adb install -r` が Success。`pm list packages` に `io.github.katsuhidem.feedflow.debug` が1行出る。起動して MainActivity が表示され、crash バッファは空
  - 済: 同期の確認(HUMAN_TASKS 6節)。人が7項目を確認(下の「人からの連絡」)。エージェントの照合でも、アプリとFreshRSSの数が一致
- 環境: docs/HUMAN_TASKS.md の1〜4節と6節は完了。5節は確認2まで完了。確認3は一部だけ確認済み(下記)。
- 端末のページサイズ: 4096
- FreshRSSの公開: Tailscale Services の `svc:freshrss`。URLは `https://freshrss.<tailnet名>.ts.net`。Mac miniは `tag:service-host` 付きのホスト。`base_url` はこのURLと一致(2026-11-14 に確認)。

## 次の作業

1. 端末がつながったら、試行3の logcat から CP5 の2回目の `Start` の backend ログを読み、原因の仮説を立てて試行4を行う
2. CP5 が通ったら、公式 Tailscale アプリを止めた状態で CP1〜CP5 をやり直す
3. CP6(対話ログイン)の手前で、HUMAN_TASKS 7節のチェックポイント6を人に依頼する

## 人への依頼

- Fold8 が試行3の途中(19:16 頃)で adb から外れました。USB 機器としては見えていますが、ADB インターフェースが出ていません。前回と同じく、端末で USB デバッグを入れ直してください。続けて USB で使うので、オートブロッカーと「USB デバッグの自動無効化」に当たる設定があれば確かめてください。報告してほしい結果: `adb devices` に `device` と出るか。
- docs/HUMAN_TASKS.md 7節「開始前の準備」の手順2(端末の公式 Tailscale アプリを切断し、`adb shell am force-stop com.tailscale.ipn` で止める)を行ってください。報告してほしい結果: `adb shell pidof com.tailscale.ipn` が何も出さないこと。手順1の `spike.env` は作成済みと確認しました(CP3 が通ったため)。
- phase/0a を確認し、main へマージしてください。0aの基準線です。main にマージされるまで、main には上流の workflows が残ります。

## 人からの連絡

- 2026-11-14: 0b から進める。

- 2026-11-14: `.github/workflows` と `renovate.json` は両方削除する。
- 2026-11-14: HUMAN_TASKS 6節を一通り確認し、問題なさそう。
- 2026-11-14: URLは端末名ではなく freshrss のサービスにした。それが正になるように計画を書き直す。

## 判断の記録

- 2026-11-14: .githooks/pre-commit の auth key の検出を、引き継ぎ文書の `tskey-[A-Za-z0-9]+` から `tskey-[a-z]+-[A-Za-z0-9]+` に変えた。元の式は docs/HUMAN_TASKS.md の説明文(`tskey-auth-…` と `'tskey-auth'`)に一致し、引き継ぎのコミット自体が止まるため。引き継ぎの確認に使う試験用の文字列と、実際のキーの形には引き続き一致する。
- 2026-11-14: 人の選択により、applicationId を `io.github.katsuhidem.feedflow`、アプリ名を `Tailfeed`、アイコンを元のアイコンの色替えにした。`namespace` と Kotlin のパッケージ名は変えていない。変更した既存ファイルは FORK_CHANGES.md にある。
- 2026-11-14: 人の指示により、FreshRSSのURLを、Mac miniのマシン名からサービス名(`svc:freshrss`)に変えた。docs/PLAN.md(前提と方針、FreshRSSの到達URL、MacでのFreshRSS構築、0bのtailnet側の準備、確かめる点、参照)と docs/HUMAN_TASKS.md(控えの表、3節、5節、6節、7節)を合わせて更新した。0bで確かめる点に「tsnetからサービスの仮想IPに届くか」を足した。
- 2026-11-14: 0b の検証アプリは tsbridge リポジトリの `spikeapp/` に置いた。PLAN に置き場所の指定はなく、フォーク本体にスパイクのコードを入れないため。tsbridge のリモートには main が無かったので、手元の main(引き継ぎ時のコミット)をそのままプッシュし、作業は phase/0b で行っている。
- 2026-11-14: tsbridge の公開 API に、PLAN にない `TailnetIP()` と `SetDebugLogs(bool)` を足した。CP2 の IP の表示と、伏せ字付きの backend ログの切り替えのため。Phase 1 で残すかは 0b の終わりに決める。
- 2026-11-14: 人の決定により、`.github/workflows` と `renovate.json` を削除した。`.github` に残るもの(`actions/`、`scripts/`、`msix-*`、`FUNDING.yml`、AGENTS.md への symlink の `copilot-instructions.md`)は、workflows がなければ動かないので残した。

## 確認の記録

- 2026-11-14 19:01: 人の6節の確認の後、アプリのDB(run-as で複製)と FreshRSS の `cli/user-info.php` を照合。購読フィード 3 と 3、既読 37 と 37、未読 13 と 13 で一致。3件のフィードのアイコンURLは、すべてサービスのURLで始まる。アプリの既読の送信待ちは 0 件。Tailscale を切断したときの失敗は、人の確認だけで、エージェントは確かめていない。
- 2026-11-14 18:50: 端末は adb で `device`、Android 17、ページサイズ 4096。最初は USB デバッグの口(ADB インターフェース)が出ておらず、人が端末で USB デバッグを入れ直して解消した。
- 2026-11-14 18:26: HUMAN_TASKS 2節と5節確認1の読み取りコマンドはすべて期待どおり。curl はサービスのURLで 200、マシン名のURLでは接続できない。
- 2026-11-14 18:26: 5節確認3の1項目目に当たる確認。`/tmp/FreshRSS.log` は 18:13 に更新され、ユーザーの更新が OK で終わっている。再起動の2項目は未実施。

## 未解決の点

1. docs/PLAN.md の0aが参照する「エージェントへの引き継ぎ」タブが、リポジトリにない。workflows と renovate.json は人の決定で片付いたが、タブ自体は欠けたまま。
2. フォークの GitHub Actions の設定は有効のまま。main へのマージ後は workflows がなくなるので動かない。設定を無効にするかは人の判断。
3. PLAN の「上流の AGENTS.md と CLAUDE.md に追記する」に対し、実際は置き換えて上流の内容を docs/upstream/AGENTS.upstream.md に移している。「コードの規約」の節は空のまま。
4. PLAN 0a のチェックボックスが未チェックのまま。チェックの更新が「PLAN の決定の書き換え」に当たるか不明なため、進み具合はこの STATUS の「現在地」に書いている。
5. ダミーの google-services.json のパッケージ名は `com.prof18.feedflow` のままだが、applicationId を変えた後も fdroid の debug ビルドは通る。googlePlay フレーバーと release はビルドしていない。
6. HUMAN_TASKS 5節確認3の再起動の2項目が未実施。
