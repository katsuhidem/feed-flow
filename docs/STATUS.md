# STATUS

最終更新: 2026-11-14(エージェント。0b 完了。人の判定で tsnet の埋め込みを採用)

## 現在地

- フェーズ: 0c(作業中。ブランチ phase/0c)。手順1(PC での確認)が済み、プロンプトの形と停止条件を確定した。記録は docs/spike/TRANSLATE_LOG.md
- 0b: 完了。CP1〜CP8 がすべて通り(2026-11-14 20:31)、人の判定で tsnet の埋め込みを採用した。feed-flow と tsbridge の両方で main にマージ済み(2026-11-14、人)。詳細と計測値は ../tsbridge/SPIKE_LOG.md の「0b の結果のまとめ」
  - 完了条件(CP1〜CP8、起動から疎通までの秒数、AAR のサイズの記録、公式 Tailscale アプリを止めた状態での確認)は満たした
  - 計測値: 起動から疎通まで、初回 2.9〜3.4 秒、同じプロセスでの再起動 4.3〜5.0 秒、永続ノードのアプリ再起動 2.7〜2.8 秒、端末の再起動後 3.2 秒。回線切替からの回復 4.9〜8.8 秒(4回)。AAR 8.2MB(arm64)
  - `tailscale.com` のソースへのパッチは不要。netmap のディスクキャッシュを envknob(`TS_USE_CACHED_NETMAP=false`)で止めた。キャッシュから起動すると、1回おきにサービスの仮想IPに届かなかったため
  - Phase 1 に持ち越す点: 画面オフやブラウザが前面のときにアプリが凍結され、tsnet も止まる。対話ログインの後にアプリへ戻る導線が要る。端末では AdGuard の VPN(tun0)が常時動いており、tsnet はこの上でも動いた
- 0a: 完了(main にマージ済み)。完了条件(実機で購読一覧と未読記事が同期され、既読がFreshRSSに反映される)を満たし、チェックリストもすべて済んだ
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

1. 0c 手順2〜12: llama.cpp を submodule(タグ `b12360`)で取り込み、JNI(`load`、`countTokens`、`translate`、`cancel`、`unload`)と検証アプリを作る
2. 0c 手順13: 実機で計測表の値を取る
3. 訳文が出せるようになったら、HUMAN_TASKS 8節「訳文を読む」を人に依頼する
4. 0b と 0c の両方が終わったら、HUMAN_TASKS 9節(後片付けと PLAN への記録)を行う

## 人への依頼

- なし(8節「訳文を読む」は、検証アプリが訳文を出せるようになってから依頼する)。

## 人からの連絡

- 2026-11-14: 評価用の英語記事10本を選んだ(docs/spike/eval-articles.txt)。内訳の分類はエージェントに任せる。
- 2026-11-14: 0c 用のモデルを入手した。`pfnet/plamo-3-translate-0.8b-GGUF` の `plamo-3-translate-0.8b-Q4_K_M.gguf`。ライセンスへの同意は求められなかった。
- 2026-11-14: feed-flow と tsbridge の phase/0b を main にマージした。0c に進め、HUMAN_TASKS 8節を1つずつ対話で進める。
- 2026-11-14: 0b の撤退の判定は「採用する」。
- 2026-11-14: `ls -l ~/.config/tsbridge/` を確認した。authkey、denylist、spike.env の3つだけで、すべて `-rw-------`。
- 2026-11-14: 7節は1手ずつ対話で進める。CP6 で、ノード `tsb-spike` に Ephemeral の表示がなく、Expiry disabled と表示された。CP7 で端末を再起動した。CP8 で Wi-Fi のオフとオンを2往復した。
- 2026-11-14: 0b から進める。

- 2026-11-14: `.github/workflows` と `renovate.json` は両方削除する。
- 2026-11-14: HUMAN_TASKS 6節を一通り確認し、問題なさそう。
- 2026-11-14: URLは端末名ではなく freshrss のサービスにした。それが正になるように計画を書き直す。

## 判断の記録

- 2026-11-14: .githooks/pre-commit の auth key の検出を、引き継ぎ文書の `tskey-[A-Za-z0-9]+` から `tskey-[a-z]+-[A-Za-z0-9]+` に変えた。元の式は docs/HUMAN_TASKS.md の説明文(`tskey-auth-…` と `'tskey-auth'`)に一致し、引き継ぎのコミット自体が止まるため。引き継ぎの確認に使う試験用の文字列と、実際のキーの形には引き続き一致する。
- 2026-11-14: 人の選択により、applicationId を `io.github.katsuhidem.feedflow`、アプリ名を `Tailfeed`、アイコンを元のアイコンの色替えにした。`namespace` と Kotlin のパッケージ名は変えていない。変更した既存ファイルは FORK_CHANGES.md にある。
- 2026-11-14: 人の指示により、FreshRSSのURLを、Mac miniのマシン名からサービス名(`svc:freshrss`)に変えた。docs/PLAN.md(前提と方針、FreshRSSの到達URL、MacでのFreshRSS構築、0bのtailnet側の準備、確かめる点、参照)と docs/HUMAN_TASKS.md(控えの表、3節、5節、6節、7節)を合わせて更新した。0bで確かめる点に「tsnetからサービスの仮想IPに届くか」を足した。
- 2026-11-14: 人の判定により、0b は埋め込み(tsnet)を採用した。根拠は ../tsbridge/SPIKE_LOG.md の「0b の結果のまとめ」。PLAN.md への記録は、HUMAN_TASKS 9節の手順どおり 0c の後にまとめて行う。
- 2026-11-14: 0b の検証アプリは tsbridge リポジトリの `spikeapp/` に置いた。PLAN に置き場所の指定はなく、フォーク本体にスパイクのコードを入れないため。tsbridge のリモートには main が無かったので、手元の main(引き継ぎ時のコミット)をそのままプッシュし、作業は phase/0b で行っている。
- 2026-11-14: tsbridge の公開 API に、PLAN にない `TailnetIP()` と `SetDebugLogs(bool)` を足した。CP2 の IP の表示と、伏せ字付きの backend ログの切り替えのため。Phase 1 で残すかは 0b の終わりに決める。
- 2026-11-14: 人の決定により、`.github/workflows` と `renovate.json` を削除した。`.github` に残るもの(`actions/`、`scripts/`、`msix-*`、`FUNDING.yml`、AGENTS.md への symlink の `copilot-instructions.md`)は、workflows がなければ動かないので残した。

## 確認の記録

- 2026-11-14: 評価用記事10本の構成をエージェントが確認した(全件取得可、ログイン不要)。コードブロックを含む技術記事 4(1、2、8、10)、ニュース 2(6、9)、箇条書きや表が多い記事 3(4、5、7)、短い告知 1(3)。1,500語を超える長文は 5本(2、4、6、7、9)。目安の内訳に対してニュースが1本少なく、4と7は同じ書き手の同じ形式(代替製品の比較)。

- 2026-11-14 20:44: `~/models/plamo-3-translate-0.8b-Q4_K_M.gguf` は 573126848 バイト(547MiB、約 0.57GB)、先頭は `GGUF`。SHA-256 は `70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6` で、Hugging Face の API が返す LFS の値と一致。

- 2026-11-14 19:01: 人の6節の確認の後、アプリのDB(run-as で複製)と FreshRSS の `cli/user-info.php` を照合。購読フィード 3 と 3、既読 37 と 37、未読 13 と 13 で一致。3件のフィードのアイコンURLは、すべてサービスのURLで始まる。アプリの既読の送信待ちは 0 件。Tailscale を切断したときの失敗は、人の確認だけで、エージェントは確かめていない。
- 2026-11-14 18:50: 端末は adb で `device`、Android 17、ページサイズ 4096。最初は USB デバッグの口(ADB インターフェース)が出ておらず、人が端末で USB デバッグを入れ直して解消した。
- 2026-11-14 18:26: HUMAN_TASKS 2節と5節確認1の読み取りコマンドはすべて期待どおり。curl はサービスのURLで 200、マシン名のURLでは接続できない。
- 2026-11-14 18:26: 5節確認3の1項目目に当たる確認。`/tmp/FreshRSS.log` は 18:13 に更新され、ユーザーの更新が OK で終わっている。再起動の2項目は未実施。

## 未解決の点

1. docs/PLAN.md の0aが参照する「エージェントへの引き継ぎ」タブが、リポジトリにない。workflows と renovate.json は人の決定で片付いたが、タブ自体は欠けたまま。
2. フォークの GitHub Actions の設定は有効のまま。main にマージ済みで workflows はなくなったので動かない。設定を無効にするかは人の判断。
3. PLAN の「上流の AGENTS.md と CLAUDE.md に追記する」に対し、実際は置き換えて上流の内容を docs/upstream/AGENTS.upstream.md に移している。「コードの規約」の節は空のまま。
4. PLAN 0a のチェックボックスが未チェックのまま。チェックの更新が「PLAN の決定の書き換え」に当たるか不明なため、進み具合はこの STATUS の「現在地」に書いている。
5. ダミーの google-services.json のパッケージ名は `com.prof18.feedflow` のままだが、applicationId を変えた後も fdroid の debug ビルドは通る。googlePlay フレーバーと release はビルドしていない。
6. HUMAN_TASKS 5節確認3の再起動の2項目が未実施。
