# TRANSLATE_LOG

Phase 0c(翻訳スパイク)の記録。手順の番号は docs/PLAN.md「Phase 0c 翻訳スパイク」の「手順」に従う。

## 構成(共通)

- モデル: `pfnet/plamo-3-translate-0.8b-GGUF` の `plamo-3-translate-0.8b-Q4_K_M.gguf`(573126848 バイト、SHA-256 `70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6`)。置き場所は Mac mini の `~/models/`
- llama.cpp: タグ `b12360`(コミット `2d8a325`)
- 端末: Galaxy Z Fold8(SM-F971Q)、Android 17、ページサイズ 4096。16KB の整列確認は不要

## 手順1: PC での確認(2026-11-14)

- 環境: Mac mini(Apple Silicon)、llama.cpp `b12360` を Release でビルド(Android SDK 同梱の CMake 4.1.2 と Ninja)。`llama-completion` を CPU のみ(`-ngl 0 -t 4`)、`--temp 0` で実行した。
- プロンプト: モデルカード(README_ja.md の「PFML3プロンプト形式」)の形をそのまま使う。要素の間に改行は入れない。先頭の BOS はプロンプトに書かず、トークナイズで付ける。末尾は `output` 要素の `<|plamo:msg|>` で終え、後ろに改行を付けない。

  ```
  <|plamo:tag|>dataset<|plamo:msg|>translation
  [<|plamo:tag|>context<|plamo:msg|>{参考テキスト}]
  [<|plamo:tag|>glossary(<|plamo:index|>{原語}<|plamo:item|>{訳語})...]
  <|plamo:tag|>input[<|plamo:key|>lang<|plamo:val|>{原文言語}]<|plamo:msg|>{原文}
  <|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja[<|plamo:key|>form<|plamo:val|>plain]<|plamo:msg|>
  ```

  上は読みやすさのために改行しているが、実際は1行につなげる。docs/PLAN.md の「プロンプトの構造」の図も同じで、改行は入れない。`glossary` 要素は `<|plamo:msg|>` を持たない。
- トークン: BOS は ID 1(`<|plamo:bos|>`)。llama.cpp はプロンプトの先頭に1回だけ付けた。特殊トークンは1トークンとして解釈された(`<|plamo:tag|>`=16、`<|plamo:key|>`=17、`<|plamo:val|>`=18、`<|plamo:msg|>`=19)。モデルカードにトークンIDの例はないため、手順5の照合は「BOS が1回だけ」「特殊トークンが分割されない」ことの確認とする。
- 停止条件: GGUF のメタデータでは EOS も ID 1(`<|plamo:bos|>`)。EOG は 1、2(`<|plamo:eos|>`)、5(`<|plamo:file_separator|>`)、16(`<|plamo:tag|>`)。訳文の後は `<|plamo:tag|>`(16)で止まった。JNI では、`<|plamo:tag|>`(16)と `<|plamo:bos|>`(1)の ID、および `llama_vocab_is_eog` で止める。
- 結果(すべて期待どおり):

  | 入力 | 訳文の要点 |
  | --- | --- |
  | モデルカードの例文(会議の日程変更) | 「会議は来週の月曜日に延期されました。」 |
  | 英語の段落(約 45 語、版番号、数値、`experimental_retention`、`false` を含む) | 数値(5、40%)とコードの文字列がそのまま残った |
  | 同じ段落、`form=plain` | 常体で出た |
  | 同じ段落、`glossary` でコードの文字列を「原語と同じ訳語」として指定 | 指定なしと同じ訳文 |
  | 中国語のタイトル、原文言語の指定なし | 正しく日本語のタイトルになった |

- 速度の参考(Mac の CPU、4 スレッド): プロンプト処理 約 620〜690 tokens/秒、生成 約 230 tokens/秒。実機の値は手順13で測る。
- 確定したこと: プロンプトの形、BOS の扱い、停止に使うトークン ID。

## 手順2〜12: JNI と検証アプリ(2026-11-14)

- llama.cpp は submodule `third_party/llama.cpp`(タグ `b12360`)。検証アプリは `spike/translate/`(独立した Gradle プロジェクト。フォーク本体の `settings.gradle.kts` には含めない)。
- ビルド: CMake で llama.cpp を静的リンクし、arm64-v8a の CPU バックエンドだけを作る(`GGML_NATIVE=OFF`、`GGML_BACKEND_DL=OFF`、`GGML_OPENMP=OFF`、`GGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16`)。APK は 7.4MB。
- JNI(`translator_jni.cpp`): `load(path, nCtx, nThreads)`、`countTokens(prompt)`、`translate(prompt, maxTokens, callback)`、`cancel()`、`unload()`。
  - `translate` は途中経過(生成トークン数と、UTF-8 として完結した部分までの訳文)と、終了理由(STOP、MAX_TOKENS、CANCELLED、ERROR)、生成したトークン列を返す。
  - トークナイズは `add_special=true` と `parse_special=true`。サンプリングは greedy。停止は `<|plamo:tag|>`(16)、`<|plamo:bos|>`(1)、`llama_vocab_is_eog`。
  - 呼び出しごとに `llama_memory_clear` で KV キャッシュを消す。キャンセルは `llama_set_abort_callback` と生成ループの両方で見る。
  - 「プロンプトのトークン数 + 出力予算 > nCtx」の入力は ERROR で拒否する。出力予算は max(64, 原文トークン数 × 3)。
- 計測の入力(`app/src/main/assets/inputs.json`)は、スパイク用に書いた文章(英語タイトル 20、英語段落 5、インラインコードを含む段落 20、中国語タイトル 10)。記事からは取っていない。

## 手順13: 実機での計測(2026-11-14 21:02、nCtx 4096、4 スレッド)

| 入力 | 結果 |
| --- | --- |
| モデルのロード | 1.0〜1.1 秒。RSS はロード前 約 140〜150MB、ロード後 約 1.21GB(ロード中のピーク 約 1.31GB) |
| 記事タイトル1件(ロード済み) | 0.20 秒 |
| 記事タイトル20件を連続 | 3.6 秒(1件 0.15〜0.25 秒)。20件すべて STOP で終了し、訳も自然 |
| 段落(英語約 100 語、プロンプト 99 トークン) | 最初のトークンまで 0.22 秒、生成 99.5 tokens/秒 |
| 段落20個を連続 | 合計 33.3 秒。生成速度は 1〜7 個目が 93〜98 tokens/秒、8 個目から低下し 10 個目以降 約 56 tokens/秒で安定。熱の状態は 12 個目から 1(LIGHT)。最初のトークンまで 0.21〜0.38 秒。推論中の RSS は 約 1.22GB で増えない |
| 推論中のキャンセル | 3 回とも 0〜2ms で CANCELLED |
| モデルのアンロード | RSS 約 230MB(ロード前より 約 80MB 多い) |
| 同じプロンプトを2回連続 | 生成トークン列が完全に一致(100 トークン) |
| 長すぎる入力の拒否 | プロンプト 3020 トークン + 予算 9060 > 4096 を拒否 |
| インラインコードを含む段落20個 | 保護なし 20/20、プレースホルダ方式 20/20、glossary 方式 20/20 でコードがそのまま残った |
| 中国語のタイトル10件 | 原文言語の指定なし 10/10、`zh` 指定 10/10 で正しく訳せた(エージェントが確認。2件は語の選び方だけが異なる) |
| 英語と日本語の文字数とトークン数の比 | 英語 6.56 文字/トークン、日本語(訳文)2.42 文字/トークン |
| 実機のページサイズ | 4096 |

- プレースホルダ方式は、インラインコードを `[[1]]` のような記号に置き換えて訳し、後で戻した。glossary 方式は、コードの文字列を「原語と同じ訳語」として渡した。
- 2B(Q4_K_M、1.81GB)の計測は未実施。docs/PLAN.md は「2Bでも同じ計測をする」としているが、docs/HUMAN_TASKS.md 8節は「0.8Bの品質が足りないと判断したときにだけ入手する」としている。人の判断を待つ。

## 記事10本の翻訳(2026-11-14 21:06〜21:19、人の評価用)

- 記事の本文を Mac で取得して段落に分け(`<article>` か `<main>` の p、li、見出し、表のセル。コードブロックは除く。HTML を返さないサイトは Markdown を使った)、実機で段落ごとに訳した。原文と訳文を並べたファイルは docs/spike/eval-out/(コミットしない)。
- 988 段落すべてが STOP で終了。合計 約 13 分(記事ごとに 10〜173 秒)。ロード後の RSS 約 1.21GB、ピーク 約 1.31GB。
- エージェントの機械的な点検: 数値の単位変換(億、万)はいずれも値が正しい。記事2の記事タイトル1件が英語のまま残った。繰り返しや途中切れはなし。人の評価は docs/spike/eval-result.md。
