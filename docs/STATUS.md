# STATUS

最終更新: 2026-11-14(エージェント。公開URLをサービス名に合わせて計画を更新)

## 現在地

- フェーズ: 0a(未着手。ブランチ phase/0a で計画の文書だけを更新)
- 環境: docs/HUMAN_TASKS.md の1〜4節は完了。5節は確認2まで完了。確認3は一部だけ確認済み(下記)。
- 端末のページサイズ: 4096
- FreshRSSの公開: Tailscale Services の `svc:freshrss`。URLは `https://freshrss.<tailnet名>.ts.net`。Mac miniは `tag:service-host` 付きのホスト。`base_url` はこのURLと一致(2026-11-14 に確認)。

## 次の作業

1. 上流のエージェント設定の残り(`.github/workflows`、`renovate.json`)の整理。対処の基準は下の「未解決の点」の1を参照
2. docs/PLAN.md の Phase 0a のチェックリスト(google-services.json のコピー、ビルド、applicationId などの差し替え、:desktopApp と :benchmarks の除外)
3. ビルドが通ったら、docs/HUMAN_TASKS.md 6節を人に依頼する

## 人への依頼

- phase/0a の計画の更新(公開URLをサービス名に変更)を確認し、main へマージしてください。

## 人からの連絡

- 2026-11-14: URLは端末名ではなく freshrss のサービスにした。それが正になるように計画を書き直す。

## 判断の記録

- 2026-11-14: .githooks/pre-commit の auth key の検出を、引き継ぎ文書の `tskey-[A-Za-z0-9]+` から `tskey-[a-z]+-[A-Za-z0-9]+` に変えた。元の式は docs/HUMAN_TASKS.md の説明文(`tskey-auth-…` と `'tskey-auth'`)に一致し、引き継ぎのコミット自体が止まるため。引き継ぎの確認に使う試験用の文字列と、実際のキーの形には引き続き一致する。
- 2026-11-14: 人の指示により、FreshRSSのURLを、Mac miniのマシン名からサービス名(`svc:freshrss`)に変えた。docs/PLAN.md(前提と方針、FreshRSSの到達URL、MacでのFreshRSS構築、0bのtailnet側の準備、確かめる点、参照)と docs/HUMAN_TASKS.md(控えの表、3節、5節、6節、7節)を合わせて更新した。0bで確かめる点に「tsnetからサービスの仮想IPに届くか」を足した。

## 確認の記録

- 2026-11-14 18:26: HUMAN_TASKS 2節と5節確認1の読み取りコマンドはすべて期待どおり。curl はサービスのURLで 200、マシン名のURLでは接続できない。
- 2026-11-14 18:26: 5節確認3の1項目目に当たる確認。`/tmp/FreshRSS.log` は 18:13 に更新され、ユーザーの更新が OK で終わっている。再起動の2項目は未実施。

## 未解決の点

1. docs/PLAN.md の0aが参照する「エージェントへの引き継ぎ」タブが、リポジトリにない。`.github/workflows` と `renovate.json` をどう扱うかの基準がない。
2. フォークの GitHub Actions が有効(実行は0件)。`release.yml` は `-all` で終わるタグの push で動き、ローカルには `1.21.0-all` タグがある。`git push --tags` はしない。
3. PLAN の「上流の AGENTS.md と CLAUDE.md に追記する」に対し、実際は置き換えて上流の内容を docs/upstream/AGENTS.upstream.md に移している。「コードの規約」の節は空のまま。
4. PLAN 0a のチェックボックスのうち、済んでいるもの(フォークと main の作成、upstream の追加、FreshRSS の構築)が未チェックのまま。
