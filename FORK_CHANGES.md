# FORK_CHANGES

タグ `1.21.0-fdroid` から変更した既存ファイルの一覧。上流のコミットを cherry-pick するときの、衝突箇所の目安にする。新規に足したファイル(docs/PLAN.md など)は載せない。

| ファイル | 変更 | フェーズ |
| --- | --- | --- |
| `AGENTS.md` | フォーク用の内容に置き換え。上流の内容は `docs/upstream/AGENTS.upstream.md` に移した | 0a |
| `.mcp.json`、`.ai/skills/`、`.claude/skills`、`.codex/skills`、`.junie/guidelines.md` | 削除 | 0a |
| `settings.gradle.kts` | `:benchmarks` と `:desktopApp` の `include` を外した | 0a |
| `androidApp/build.gradle.kts` | `applicationId` を `io.github.katsuhidem.feedflow` に変更。`"baselineProfile"(project(":benchmarks"))` を外した | 0a |
| `androidApp/src/main/AndroidManifest.xml` | アプリ名(`android:label`)を `Tailfeed` に変更 | 0a |
| `androidApp/src/release/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを青から緑系に変更 | 0a |
| `androidApp/src/debug/res/drawable/ic_launcher_background.xml` | 背景のグラデーションを橙から紫系に変更 | 0a |

`namespace`(`com.prof18.feedflow.android`)とKotlinのパッケージ名は変えていない。
