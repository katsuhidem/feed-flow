# FORK_CHANGES

タグ `1.21.0-fdroid` から変更した既存ファイルの一覧。上流のコミットを cherry-pick するときの、衝突箇所の目安にする。新規に足したファイル(docs/PLAN.md など)は載せない。

| ファイル | 変更 | フェーズ |
| --- | --- | --- |
| `AGENTS.md` | フォーク用の内容に置き換え。上流の内容は `docs/upstream/AGENTS.upstream.md` に移した | 0a |
| `.mcp.json`、`.ai/skills/`、`.claude/skills`、`.codex/skills`、`.junie/guidelines.md` | 削除 | 0a |
| `.github/workflows/`(12ファイル)、`renovate.json` | 削除。上流のCI、リリース、依存更新の自動化はこのフォークでは使わない | 0a |
| `settings.gradle.kts` | `:benchmarks` と `:desktopApp` の `include` を外した | 0a |
| `androidApp/build.gradle.kts` | `applicationId` を `io.github.katsuhidem.feedflow` に変更。`"baselineProfile"(project(":benchmarks"))` を外した | 0a |
| `androidApp/src/main/AndroidManifest.xml` | アプリ名(`android:label`)を `Tailfeed` に変更。Phase 4d: `queries` に Notion アプリのパッケージ(`notion.id`)を足した | 0a、4d |
| `androidApp/src/release/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを青から緑系に変更 | 0a |
| `androidApp/src/debug/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを橙から紫系に変更 | 0a |
| `.gitignore` | 0c の評価用の訳文(記事本文を含む)の出力先 `docs/spike/eval-out/` を除外 | 0c |
| `settings.gradle.kts` | `:tailnet` を `include` | 1 |
| `settings.gradle.kts` | `:translation` と `:llama` を `include` | 2 |
| `core/src/commonMain/kotlin/com/prof18/feedflow/core/model/FeedItem.kt` | `translatedTitle` を追加(既定値 null) | 2 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/domain/feed/FeedStateRepository.kt` | `titleTranslations` 引数を追加。一覧を公開する前に訳題を埋め(`withTitleTranslations`)、通知で差し替える(`init`) | 2 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/di/Koin.kt` | `FeedStateRepository` に `getOrNull<TimelineTitleTranslations>()` を渡す | 2 |
| `androidApp/build.gradle.kts` | `:translation` と `:database` への依存、`ndkVersion` を追加 | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/FeedFlowApp.kt` | `TranslationSetup` を作り、Koin モジュールを渡し、起動後に `install()` | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/home/components/list/FeedItemCommon.kt` | 訳題があれば主に表示し、原題を下に小さく表示(`OriginalTitle`) | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/settings/readingbehavior/ReadingBehaviorScreenContent.kt` | `footerContent` 引数を追加 | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/settings/readingbehavior/ReadingBehaviorScreen.kt` | 翻訳の設定の欄を `footerContent` に渡す。Phase 4a: 通信量の設定の欄も同じ枠に渡す。Phase 4e: 重複した記事の設定の欄も同じ枠に渡す | 2、4a、4e |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/feed/editfeed/EditFeedComponents.kt` | `EditFeedContent` に `extraSettings` 引数を追加 | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/editfeed/EditFeedScreen.android.kt` | `EditScreen` に `feedId` 引数を追加し、フィード別の翻訳設定を表示 | 2 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/MainActivity.kt` | `EditScreen` に `feedId` を渡す | 2 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/domain/ReaderModeHtmlAndCss.kt` | 翻訳用の CSS と JavaScript を読み込む2か所(`readerTranslationCss()`、`readerTranslationJs()`) | 3 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/readermode/ReaderModeScreen.android.kt` | 翻訳用 ViewModel の取得、ツールバーへのボタン、JS ブリッジのハンドラ `translationBlocks`、ページ読み込み完了の通知と JS コマンドの実行。debug ビルドでは、検証用のスイッチがオンのとき、本文とタイトルを確認用の記事(`TranslationDebug`)に差し替える。Phase 4a: 画像と動画の読み込みの制御(`applyMediaBlock`)、ツールバーの「画像を読み込む」ボタン、画像ビューアで開く画像の許可。Phase 4d: Notion アプリが入っているときに、ツールバーへ Notion への共有の処理を渡す | 3、4a、4d |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/readermode/ReaderModeToolbar.kt` | `actions` 引数を追加 | 3 |
| `androidApp/build.gradle.kts` | `:tailnet`、`:feedSync:networkcore`、tsbridge の AAR(`tailnet/libs/tsbridge-0.1.0.aar`)、`ktor-client-okhttp` への依存を追加 | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/FeedFlowApp.kt` | `TailnetSetup` を作り、GReader 用エンジンと Koin モジュールを `initKoin` に渡す。Koin 起動後に `install()`。画像ローダーに tailnet 用の OkHttp を渡す。Phase 4a: 通信量の制御の組み立て(`DataUseSetup`)を起動時に作る1行、Koin モジュールと画像ローダー用のインターセプタ(`imageInterceptor`)を渡す | 1、4a |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/utils/ImageLoader.kt` | `coilImageLoader` に `okHttpClient` 引数を追加し、`OkHttpNetworkFetcherFactory` で使う。Phase 4a: 追加のインターセプタを受け取る引数(モバイル回線で画像をネットワークから取得しないため) | 1、4a |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/accounts/freshrss/FreshRssSyncScreen.android.kt` | Tailscale の欄を表示し、ログイン時に入力された URL を tailnet の対象にする | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/accounts/freshrss/FreshRssSyncComponents.kt` | `FreshRssSyncContent` に `headerContent` 引数を追加 | 1 |
| `feedSync/greader/src/commonMain/kotlin/com/prof18/feedflow/feedsync/greader/di/GReaderModule.kt` | `getGReaderModule` に `httpClientEngine` 引数を追加し、`GReaderClient` に渡す | 1 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/di/Koin.kt` | `initKoin` と `getAllModulesModules` に `greaderHttpEngine` 引数を追加 | 1 |
| `shared/src/androidMain/kotlin/com/prof18/feedflow/shared/domain/FeedDownloadWorker.kt` | `syncNetworkGate` 引数を追加し、取得を `withNetwork` で包む | 1 |
| `shared/src/androidMain/kotlin/com/prof18/feedflow/shared/di/KoinAndroid.kt` | `FeedDownloadWorker` に `getOrNull<SyncNetworkGate>()` を渡す | 1 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/domain/contentprefetch/ContentPrefetcher.kt` | Phase 4a: 各記事の取得の直前に `ContentPrefetchGate` を確かめる。止められたら取得せず、待ち行列に残したまま `false` を返す(`prefetch` の戻り値を `Boolean` にした) | 4a |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/domain/contentprefetch/ContentPrefetchRepositoryImpl.kt` | Phase 4a: 同期直後の取得で、止められたら残りを試さずに待ち行列へ回す | 4a |
| `shared/src/androidMain/kotlin/com/prof18/feedflow/shared/domain/contentprefetch/WorkManagerBackgroundPrefetchScheduler.kt` | Phase 4a: 「Wi-Fi のときだけ」がオンなら、WorkManager の条件を「従量制でない回線」にする | 4a |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/di/Koin.kt`、`shared/src/androidMain/kotlin/com/prof18/feedflow/shared/di/KoinAndroid.kt` | Phase 4a: 先読みの部品に `ContentPrefetchGate` を渡す(`getOrNull()`、1行ずつ) | 4a |
| `i18n/src/commonMain/resources/locale/values-ja/strings.xml` | Phase 4b: 上流に無い日本語の訳 341 件を、末尾に注釈付きでまとめて足した(英語のファイルの順)。上流の翻訳の更新を cherry-pick して同じ名前が増えたら、どちらか一方を残す | 4b |
| `i18n/build.gradle.kts` | Phase 4b: 文字列を生成する KSP の処理に、言語ファイルのフォルダを入力として登録した(登録しないと、言語ファイルを変えても生成し直されない) | 4b |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/readermode/ReaderModeFloatingToolbar.kt` | Phase 4c: 「記事を読み上げる」と「壊れた記事を報告」のボタンを並べる行を外した(処理そのものは残っている)。Phase 4d: 「Notionに保存」のボタン(引数 `onShareToNotion` を渡したときだけ出す) | 4c、4d |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/domain/feed/FeedFetcherRepository.kt` | Phase 4e: 引数 `duplicateReadMarker` を足し、同期の後(FreshRSS は同期が成功したときだけ)に重複の判定を呼ぶ | 4e |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/di/Koin.kt` | Phase 4e: `DuplicateReadMarker` を登録し、`FeedFetcherRepository` に渡す | 4e |
| `database/src/commonMain/kotlin/com/prof18/feedflow/database/DatabaseHelper.kt` | Phase 4e: 重複の判定に使う記事の一覧を読むメソッド `getDuplicateReadRows` | 4e |

`namespace`(`com.prof18.feedflow.android`)とKotlinのパッケージ名は変えていない。

新規に足したもの(既存ファイルの変更ではない): `.gitmodules` と submodule `third_party/llama.cpp`(タグ `b12360`)、翻訳スパイクの検証アプリ `spike/translate/`(フォーク本体のビルドには含めない)。Phase 1 のモジュール `tailnet/`(tsbridge の AAR を含む)、`shared/src/androidMain/.../domain/SyncNetworkGate.kt`、`androidApp/src/main/kotlin/com/prof18/feedflow/android/tailnet/`。 Phase 2 のモジュール `translation/`(KMP、専用の SQLDelight DB)と `llama/`(llama.cpp の JNI)。 `shared/src/commonMain/.../domain/feed/TimelineTitleTranslations.kt`、`shared/src/commonTest/.../FeedStateRepositoryTitleTranslationTest.kt`、`androidApp/src/main/kotlin/com/prof18/feedflow/android/translation/`。 Phase 3: `shared/src/commonMain/.../domain/ReaderTranslationScript.kt`、`shared/src/jvmTest/.../ReaderTranslationFixtureTest.kt`、`translation/` の `CodeProtection.kt` と `ArticleTranslator.kt`、`androidApp/.../translation/ArticleTranslationViewModel.kt` と `ArticleTranslationButton.kt`。
