# STATUS

最終更新: 2026-11-16(エージェント。Phase 2 の完了条件を実機で確認中。8つのうち6つを確認)

## 現在地

- フェーズ: Phase 2(作業中。ブランチ phase/2)。実機をつながずに進められる部分だけを実装した。**実機では一度も動かしていない**
  - 済: 新規モジュール `translation/`(KMP)。`PlamoPromptBuilder`、`TokenBudget`、`LanguageGate`、`TranslationPlanner`(キーを作る唯一の関数)、`TranslationCache`(専用の SQLDelight DB、失敗の記録、フィード別設定、300ms でまとめる変更通知)、`TranslationEngine`、`TranslationQueue`、`TitleTranslator`。JVM の単体テスト 73 件がすべて通る(`./gradlew :translation:jvmTest`)
  - 済: 新規モジュール `llama/`(Android ライブラリ)。submodule の llama.cpp を arm64、CPU、Release でビルドする JNI。`:llama:assembleDebug` が通り、`libllamabridge.so` ができる
  - 済: `translation/` の Android 側: `LlamaCppEngine`、`ModelStore`(Range での再開、SHA-256 検証、0.8B と 2B のカタログ)。`:translation:compileAndroidMain` が通る
  - 済(ビルドのみ): アプリへの組み込み。`androidApp/.../translation/` に `TranslationSetup`(組み立てと Koin)、`TimelineTitleTranslationsImpl`、`TitleTranslationWorker`(充電中のバッチ)、`TranslationPrefs`、`TranslationSettingsSection`(設定)。`assembleFdroidDebug` が通る(debug APK 74.9MB。`libllamabridge.so` 6.5MB)
  - 済(テストあり): タイムラインへの差し込み。shared に小さなインターフェース `TimelineTitleTranslations` を置き、`FeedStateRepository` が一覧を公開する前に訳題を埋め、保存の通知で差し替える。`FeedItem.translatedTitle` を追加。`:shared:jvmTest --tests '*FeedStateRepository*'` は 25 件(既存 21、追加 4)すべて通る
  - 済(ビルドのみ): 行の表示(訳題を主、原題を小さく副)。設定は「読み方」の設定画面の末尾(翻訳のオン/オフ、モデルのダウンロード・中断・削除、常体/敬体、ライセンスの表示)
  - 未: フィード別のオン/オフの画面(保存先 `feed_translation_meta` と判定は実装済み。操作する UI がない)
  - 実機での確認(2026-11-16、人と対話で。クラッシュなし):
    - 済: アプリ内からモデルをダウンロードでき、中断後に再開できる。126MB の時点でアプリを強制終了 → 開き直すと「ダウンロード: 125MB / 573MB」→ 続きから完了。別の回のログで `on_disk=200000000 http=206 continue_from=200000000`、検証後にインストール
    - 済: 英語フィードのタイトルに訳題が表示され、日本語フィードは翻訳されない。日本語フィード 20 件は訳 0 件(`estimated_lang=ja`)。英語は Android Developers' Blog 5 件、TechCrunch 10 件
    - 未(実機): 日本語フィードの、かなを含まないタイトルが翻訳されない。実機のフィードにかなを含まないタイトルが無かった。単体テスト(`LanguageGateTest`、`TitleTranslatorTest`)では確認済み
    - 済: 中国語フィードのタイトルが、日本語と誤判定されずに翻訳される(3 件中 3 件。タイトルが 5 件に満たないので推定は未決定のまま、文字種で判定)
    - 済: 一度訳したタイトルは、モデルをロードせずに表示される(アプリを入れ直した後、PSS 約 250MB のまま訳題が出ていた。ロード時は約 1.2GB)
    - 済: 訳が保存されると、一覧を開き直さずに訳題へ切り替わる(人が中国語のタイトルで確認)
    - 済: 常体と敬体を切り替えると、古い文体の訳が表示されず、訳し直される。常体に変えた直後の 5 秒で 18 件が新しい `config_hash` で保存された(18 件中 8 件は訳文が変化。例「ご利用ください」→「利用できる」)。古い条件の行は残るが参照されない
    - 未: バッチ翻訳が動いている間も、タイムラインのスクロールが引っかからない。訳し直しが 5 秒で終わり、人の目では判断できなかった
  - 実機で分かったこと: バッチは USB 給電中に自動で動いた(5 件を約 2 秒)。中国語の同じ語(「科技爱好者周刊」)が 3 件で別々の訳になった(用語の揺れ。Phase 3 の用語集の対象)
- Phase 1: 完了し main にマージ済み。完了条件9つを実機で確認した(2026-11-14〜15、人と対話で)
  - 新規モジュール `tailnet/`: `TailnetController`(共通)、`TailnetManager`、`TailnetProxySelector`、`TailnetGateInterceptor`(起動待ちと利用中の印)、`TailnetProxyAuthenticator`、`NetworkStateReporter`、`TailnetSettings`。tsbridge は v0.1.0 の AAR を `tailnet/libs/` に置いた
  - 差し込み: GReader 用の OkHttp エンジン、画像ローダー、バックグラウンド同期の Worker(`SyncNetworkGate`)、FreshRSS アカウント画面の Tailscale の欄。変更した既存ファイルは FORK_CHANGES.md
  - release ビルドの ProGuard: 追加の設定は不要と確認した(2026-11-15)。`:androidApp:minifyFdroidReleaseWithR8` が通り、tsbridge の AAR 同梱のルールで `go.*` と tsbridge のクラスが名前を保ったまま残る。release の APK の作成には署名用の `keystore.properties`(秘密情報。人が用意する)が要るため、`assembleFdroidRelease` は試していない
  - 完了条件の確認(2026-11-14、人と対話で):
    - 済: 公式 Tailscale アプリなしで、初回ログインから同期まで完了(ノード名 `tailfeed`、Expiry disabled)
    - 済: フィードのアイコンが表示される(FreshRSS への通信は `route=proxy`)
    - 済: tsnet が停止している状態から同期すると、自動で起動して成功(停止 23:13:53 → 同期で起動 23:14:00 → 接続 23:14:02)
    - 済: 記事本文の取得がプロキシを通っていない(本文と画像の表示中、`TailnetRoute` のログ 0 行、Go のプロキシへの要求 0 件)
    - 済: 同期中にバックグラウンドへ移しても、最後の通信が終わるまで tsnet は止まらない(検証用の「本文を2秒遅らせる」「停止まで10秒」で確認)
    - 済: 本文の受信が印の上限(検証用 0.5 秒)に達すると、通信をキャンセルしてから印を返し、その後で tsnet が止まる
    - 済: タスク一覧から消した状態でのバックグラウンド同期。同期の Worker(`FeedDownloadWorker`)がバックグラウンドのまま tsnet を起動し、5件とも 200 で SUCCESS(23:56:54〜57)。ただし定期実行のジョブそのものは、Android がタスク一覧から消されたアプリの通信を `APP_BACKGROUND` でブロックしているため、ネットワーク条件が満たされず動かなかった(tsnet とは無関係で、上流の直結でも同じ)。確認は、同じ Worker をネットワーク条件なしで 30 秒後に1回動かす検証用ボタンで行った
    - 済: ログイン未完了と接続タイムアウトで、同期がエラーになり直結しない。公式 Tailscale アプリを接続した(直結なら届く)状態で、`Tailscale login required` と `Tailscale did not connect within 1s`(検証用の起動待ち1秒)のエラーになった。「起動の失敗」(tsbridge の Start が例外)は実機で再現していない。コード上は同じ経路(Error 状態 → ensureUp が例外 → I/O エラー、プロキシ選択は到達不能な宛先を返す)を通る
    - 済: 接続モード「システム」で 0a と同じ直結で動く(tsnet は停止し、同期とアイコンは公式アプリ経由で成功。`TailnetRoute` のログなし)
  - 再ログインの後、管理画面のノード名は `tailfeed-1`(古い `tailfeed` は人が削除)。`tailfeed-1` は Expiry disabled(人が確認)
  - 端末のアプリは、確認の最後にシステムモードにしたまま
  - debug ビルドだけの検証用の操作(停止まで10秒、印の上限0.5秒、本文を2秒遅らせる、起動待ち1秒、30秒後のバックグラウンド同期、ログイン状態を消す)を FreshRSS アカウント画面に残している
- Phase 0: 完了。Phase 0(0a、0b、0c、HUMAN_TASKS 9節)は完了し main にマージ済み。結果は docs/PLAN.md「Phase 0 の結果」記録は docs/spike/TRANSLATE_LOG.md
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
- 環境: docs/HUMAN_TASKS.md の1〜9節は完了(8節の「端末の熱さ」の1項目だけ未記録)。
- 端末のページサイズ: 4096
- FreshRSSの公開: Tailscale Services の `svc:freshrss`。URLは `https://freshrss.<tailnet名>.ts.net`。Mac miniは `tag:service-host` 付きのホスト。`base_url` はこのURLと一致(2026-11-14 に確認)。

## 次の作業

1. スクロールの引っかかりを確かめる(`dumpsys gfxinfo` で、訳し直し中とそうでないときのフレームを比べる案。自動スクロールは「スクロールして既読としてマーク」で既読を増やすので、人の了承を得てから)
2. フィード別のオン/オフの画面を足す
3. 完了条件がそろったら、phase/2 の main へのマージを人に依頼する

## 人への依頼

- スクロールの計測を自動で行ってよいか決めてください(一覧を自動でスクロールするため、「スクロールして既読としてマーク」がオンだと既読が増えます)。
- phase/2 は、残りの確認が済むまで main にマージしないでください。

## 人からの連絡

- 2026-11-14: Mac mini の停電後の自動起動(`autorestart`)はオフのままにする。
- 2026-11-14: Mac mini を再起動した。ログイン画面のままでは Fold8 から接続できず、ログインして2〜3分たつと接続できた。phase/0c を main にマージした。
- 2026-11-14: docs/PLAN.md「Phase 0 の結果」を読んで確かめた。問題なし。
- 2026-11-14: HUMAN_TASKS 9節の手順1(`tsbridge-spike` の失効と authkey の削除)と手順2(ノード `tsb-spike` の削除)を行った。
- 2026-11-14: 本文翻訳の既定は「通常 0.8B、高品質な翻訳だけ 2B に切り替え」。
- 2026-11-14: 2B の訳文も別 AI に評価させた結果を渡す(docs/spike/eval-result.md に記録)。
- 2026-11-14: 0c の判断は「2Bで同じ10本を評価し直す」。タイトルは 0.8B の結果で自動翻訳を採用できる見込み。
- 2026-11-14: 訳文の評価は人では難しかったため、別の AI に評価させた結果を渡す(docs/spike/eval-result.md に記録)。
- 2026-11-14: 品質の確認はまず 0.8B で行う。2B は、0.8B の訳文を読んで品質が足りないと判断したときに入手する(HUMAN_TASKS 8節の手順どおり)。
- 2026-11-14: 評価用の英語記事10本を選んだ(docs/spike/eval-articles.txt)。内訳の分類はエージェントに任せる。
- 2026-11-14: 0c 用のモデルを入手した。`pfnet/plamo-3-translate-0.8b-GGUF` の `plamo-3-translate-0.8b-Q4_K_M.gguf`。ライセンスへの同意は求められなかった。
- 2026-11-14: feed-flow と tsbridge の phase/0b を main にマージした。0c に進め、HUMAN_TASKS 8節を1つずつ対話で進める。
- 2026-11-14: 0b の撤退の判定は「採用する」。
- 2026-11-14: `ls -l ~/.config/tsbridge/` を確認した。authkey、denylist、spike.env の3つだけで、すべて `-rw-------`。
- 2026-11-14: 7節は1手ずつ対話で進める。CP6 で、ノード `tsb-spike` に Ephemeral の表示がなく、Expiry disabled と表示された。CP7 で端末を再起動した。CP8 で Wi-Fi のオフとオンを2往復した。
- 2026-11-14: 0b から進める。

- 2026-11-14: `.github/workflows` と `renovate.json` は両方削除する。
- 2026-11-16: Fold8 をつないだ。FreshRSS に日本語のフィードと中国語のフィードを1件ずつ追加した。中国語のタイトルは見ている間に訳題へ切り替わった。アプリを開いた時点で英語の訳題はすでに出ていた。文体の切り替えは速くてよく分からなかった。
- 2026-11-16: 一覧をスクロールすると複数の記事が既読になる挙動が気になる、との質問。上流の設定「スクロールして既読としてマーク」(既定でオン)によるもので、翻訳のコードは既読の状態に触れていない、と回答した。
- 2026-11-15: Fold8 をつないでいないので、実機確認が要らない部分から進める。
- 2026-11-14: HUMAN_TASKS 6節を一通り確認し、問題なさそう。
- 2026-11-14: URLは端末名ではなく freshrss のサービスにした。それが正になるように計画を書き直す。

## 判断の記録

- 2026-11-14: .githooks/pre-commit の auth key の検出を、引き継ぎ文書の `tskey-[A-Za-z0-9]+` から `tskey-[a-z]+-[A-Za-z0-9]+` に変えた。元の式は docs/HUMAN_TASKS.md の説明文(`tskey-auth-…` と `'tskey-auth'`)に一致し、引き継ぎのコミット自体が止まるため。引き継ぎの確認に使う試験用の文字列と、実際のキーの形には引き続き一致する。
- 2026-11-14: 人の選択により、applicationId を `io.github.katsuhidem.feedflow`、アプリ名を `Tailfeed`、アイコンを元のアイコンの色替えにした。`namespace` と Kotlin のパッケージ名は変えていない。変更した既存ファイルは FORK_CHANGES.md にある。
- 2026-11-14: 人の指示により、FreshRSSのURLを、Mac miniのマシン名からサービス名(`svc:freshrss`)に変えた。docs/PLAN.md(前提と方針、FreshRSSの到達URL、MacでのFreshRSS構築、0bのtailnet側の準備、確かめる点、参照)と docs/HUMAN_TASKS.md(控えの表、3節、5節、6節、7節)を合わせて更新した。0bで確かめる点に「tsnetからサービスの仮想IPに届くか」を足した。
- 2026-11-15: タイムラインへの差し込みは、Phase 1 の `SyncNetworkGate` と同じ形にした。shared は `translation/` に依存せず、インターフェース `TimelineTitleTranslations` だけを持つ。実装は androidApp。変更の購読(300ms でまとめる)と翻訳の投入は androidApp 側のスコープで行い、`FeedStateRepository` には結果を渡すだけにした。
- 2026-11-15: 表示時の翻訳の対象は「一覧に読み込んだページ」とした(1ページ 40 件、0.8B で約 8 秒)。PLAN の「画面に出ている未翻訳のタイトルだけ」より広い。行ごとの可視判定は上流の UI への変更が増えるため、実機で必要と分かってから入れる。
- 2026-11-15: バッチの対象は、タイムラインの未読の新しい順 200 件。フィードの言語推定に使う「直近のタイトル」は、DB を引かず、タイムラインとバッチで見たタイトル(フィードごとに最大 30 件)から取る。5 件に満たない間は推定せず、文字種だけで判定する。
- 2026-11-15: 翻訳の設定は、新しい画面を作らず「読み方」の設定画面の末尾に足した(上流の画面遷移への変更を避けるため)。文字列は翻訳リソースに入れず日本語を直接書いた(個人用ビルドのため。Tailscale の欄と同じ)。
- 2026-11-15: androidApp に `ndkVersion` を足した。無いと `libllamabridge.so` のデバッグ情報が取り除かれず、APK に 54MB のまま入る(足した後は 6.5MB)。
- 2026-11-15: ProGuard の確認(Phase 1 の残り)は別ブランチを作らず phase/2 で行った。設定の変更は不要だった。
- 2026-11-15: llama.cpp の JNI は、`translation/` ではなく別の Android ライブラリ `llama/` に置いた。KMP の Android ライブラリのプラグインが CMake(externalNativeBuild)を扱えないため。
- 2026-11-15: キャッシュの `model_rev` は、インストール済みファイルの実測ハッシュではなく、選んだモデルの `ModelSpec.sha256` を使う。ダウンロード時にこの値と照合しているので同じ値になり、モデルを消した後でもキャッシュ済みの訳題を表示できる。
- 2026-11-15: 中国語の言語コードは、モデルカードの一覧に合わせて `zh-hans` と `zh-hant` にした(0c のスパイクでは `zh` を使っていた)。フィードの文字列に含まれる `<|plamo:` は、制御トークンとして解釈されないようゼロ幅スペースで崩す。
- 2026-11-15: SQLite の UPSERT は使わない(minSdk 26 の SQLite は 3.18)。
- 2026-11-15: Phase 1 の完了条件の確認で、本物の定期実行のジョブが Android の通信ブロック(`APP_BACKGROUND`)で動かなかったため、同じ Worker を検証用ボタンで動かして確認した。
- 2026-11-14: 人の判断により、同期の途中でアプリがバックグラウンドへ移ると約5秒後から通信がタイムアウトする件は、Android の一般的な制約(バックグラウンドのアプリの凍結と通信制限)として Phase 1 では対処しない。tsnet に固有ではなく、上流の直結でも同じ制約を受ける。長い同期の取り損ねが問題になったら、手動の同期を WorkManager で実行する案を検討する。
- 2026-11-14: 人の判断により、上流の GReaderClient が debug ビルドで要求の URL(tailnet名を含む)を logcat に出すことは許容する。上流由来で debug ビルドだけのため。tailnet モジュール自身のログは引き続き伏せ字にする。
- 2026-11-14: 人の選択により、tsbridge の AAR はフォークにコミットする(`tailnet/libs/tsbridge-<版>.aar`、SHA-256 は `tailnet/libs/README.md`)。tsbridge は v0.1.0 のタグを打った。
- 2026-11-14: Phase 1 の新規コードのパッケージは `io.github.katsuhidem.tailnet`(モジュール `tailnet/`)と `com.prof18.feedflow.android.tailnet`(androidApp 内の組み立て)。既定の接続モードはシステム(0a と同じ直結)で、人が設定画面で切り替える。
- 2026-11-14: PLAN では `getGReaderModule` に `HttpClientEngine?` を渡すとしている。Koin の起動前にエンジンを作る必要があるため、`initKoin` にも同じ引数を足して渡した。バックグラウンド同期の Worker は、shared から tailnet に依存しないよう、`SyncNetworkGate` という小さなインターフェースを介して起動待ちと利用中の印を行う。
- 2026-11-14: FreshRSS の URL はログインが成功してから保存されるため、最初のログインでは画面に入力された URL を tailnet の対象として使う(`TailnetSetup.pendingLoginUrl`)。
- 2026-11-14: 人の判断により、Mac mini の停電後の自動起動はオフのまま。HUMAN_TASKS 2節の手順9(オンにする)とは異なる。停電後は人が電源を入れてログインするまで FreshRSS は使えない(FileVault のため自動ログインも使えない)。
- 2026-11-14: 人の判定により、0b は埋め込み(tsnet)を採用した。根拠は ../tsbridge/SPIKE_LOG.md の「0b の結果のまとめ」。PLAN.md への記録は、HUMAN_TASKS 9節の手順どおり 0c の後にまとめて行う。
- 2026-11-14: 0b の検証アプリは tsbridge リポジトリの `spikeapp/` に置いた。PLAN に置き場所の指定はなく、フォーク本体にスパイクのコードを入れないため。tsbridge のリモートには main が無かったので、手元の main(引き継ぎ時のコミット)をそのままプッシュし、作業は phase/0b で行っている。
- 2026-11-14: tsbridge の公開 API に、PLAN にない `TailnetIP()` と `SetDebugLogs(bool)` を足した。CP2 の IP の表示と、伏せ字付きの backend ログの切り替えのため。Phase 1 で残すかは 0b の終わりに決める。
- 2026-11-14: 人の決定により、`.github/workflows` と `renovate.json` を削除した。`.github` に残るもの(`actions/`、`scripts/`、`msix-*`、`FUNDING.yml`、AGENTS.md への symlink の `copilot-instructions.md`)は、workflows がなければ動かないので残した。

## 確認の記録

- 2026-11-14: HUMAN_TASKS 9節の確認コマンドをエージェントが両リポジトリで実行した(名前は表示せずに照合)。tailnet名とマシン名は、作業ツリーにも全履歴にも 0 件。`git log --all -S 'tskey-auth'` はフォークの 160975031(引き継ぎのコミット)を1件返すが、これは HUMAN_TASKS.md の説明文(`tskey-auth-…`)によるもので、実際のキーの形(`tskey-<種類>-<英数字8文字以上>`)は全履歴で 0 件。tsbridge は何も出ない。9節の最後の確認(0b を見送った場合)は、採用したので該当しない。

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
