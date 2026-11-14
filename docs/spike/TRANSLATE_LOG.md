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
