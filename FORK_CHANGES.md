# FORK_CHANGES

タグ `1.21.0-fdroid` から変更した既存ファイルの一覧。上流のコミットを cherry-pick するときの、衝突箇所の目安にする。新規に足したファイル(docs/PLAN.md など)は載せない。

| ファイル | 変更 | フェーズ |
| --- | --- | --- |
| `AGENTS.md` | フォーク用の内容に置き換え。上流の内容は `docs/upstream/AGENTS.upstream.md` に移した | 0a |
| `.mcp.json`、`.ai/skills/`、`.claude/skills`、`.codex/skills`、`.junie/guidelines.md` | 削除 | 0a |
| `.github/workflows/`(12ファイル)、`renovate.json` | 削除。上流のCI、リリース、依存更新の自動化はこのフォークでは使わない | 0a |
| `settings.gradle.kts` | `:benchmarks` と `:desktopApp` の `include` を外した | 0a |
| `androidApp/build.gradle.kts` | `applicationId` を `io.github.katsuhidem.feedflow` に変更。`"baselineProfile"(project(":benchmarks"))` を外した | 0a |
| `androidApp/src/main/AndroidManifest.xml` | アプリ名(`android:label`)を `Tailfeed` に変更 | 0a |
| `androidApp/src/release/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを青から緑系に変更 | 0a |
| `androidApp/src/debug/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを橙から紫系に変更 | 0a |
| `.gitignore` | 0c の評価用の訳文(記事本文を含む)の出力先 `docs/spike/eval-out/` を除外 | 0c |
| `settings.gradle.kts` | `:tailnet` を `include` | 1 |
| `androidApp/build.gradle.kts` | `:tailnet`、`:feedSync:networkcore`、tsbridge の AAR(`tailnet/libs/tsbridge-0.1.0.aar`)、`ktor-client-okhttp` への依存を追加 | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/FeedFlowApp.kt` | `TailnetSetup` を作り、GReader 用エンジンと Koin モジュールを `initKoin` に渡す。Koin 起動後に `install()`。画像ローダーに tailnet 用の OkHttp を渡す | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/utils/ImageLoader.kt` | `coilImageLoader` に `okHttpClient` 引数を追加し、`OkHttpNetworkFetcherFactory` で使う | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/accounts/freshrss/FreshRssSyncScreen.android.kt` | Tailscale の欄を表示し、ログイン時に入力された URL を tailnet の対象にする | 1 |
| `androidApp/src/main/kotlin/com/prof18/feedflow/android/ui/accounts/freshrss/FreshRssSyncComponents.kt` | `FreshRssSyncContent` に `headerContent` 引数を追加 | 1 |
| `feedSync/greader/src/commonMain/kotlin/com/prof18/feedflow/feedsync/greader/di/GReaderModule.kt` | `getGReaderModule` に `httpClientEngine` 引数を追加し、`GReaderClient` に渡す | 1 |
| `shared/src/commonMain/kotlin/com/prof18/feedflow/shared/di/Koin.kt` | `initKoin` と `getAllModulesModules` に `greaderHttpEngine` 引数を追加 | 1 |
| `shared/src/androidMain/kotlin/com/prof18/feedflow/shared/domain/FeedDownloadWorker.kt` | `syncNetworkGate` 引数を追加し、取得を `withNetwork` で包む | 1 |
| `shared/src/androidMain/kotlin/com/prof18/feedflow/shared/di/KoinAndroid.kt` | `FeedDownloadWorker` に `getOrNull<SyncNetworkGate>()` を渡す | 1 |

`namespace`(`com.prof18.feedflow.android`)とKotlinのパッケージ名は変えていない。

新規に足したもの(既存ファイルの変更ではない): `.gitmodules` と submodule `third_party/llama.cpp`(タグ `b12360`)、翻訳スパイクの検証アプリ `spike/translate/`(フォーク本体のビルドには含めない)。Phase 1 のモジュール `tailnet/`(tsbridge の AAR を含む)、`shared/src/androidMain/.../domain/SyncNetworkGate.kt`、`androidApp/src/main/kotlin/com/prof18/feedflow/android/tailnet/`。
