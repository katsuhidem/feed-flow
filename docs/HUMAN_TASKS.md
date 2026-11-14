# 人が行う作業の手順(Phase 0)

Phase 0でエージェントに任せられない作業を、実施順に並べた手順書です。各節は「手順」と「確認」に分かれており、確認がすべて通ってから次の節に進みます。

## 全体の流れ

| # | 作業 | 行う時期 | 前提 |
| --- | --- | --- | --- |
| 1 | GitHubの準備 | 最初 | なし |
| 2 | Mac miniの環境 | 最初 | 1 |
| 3 | Tailscale管理画面の設定 | 最初 | 2(Mac miniがtailnetに参加済み) |
| 4 | Galaxy Z Fold8の準備 | 最初 | 2 |
| 5 | FreshRSSの構築と確認 | 最初 | 2、3 |
| 6 | Phase 0aの実機確認 | エージェントがビルドを通した後 | 4、5 |
| 7 | Phase 0bの確認 | チェックポイント6と8に達した時 | 6 |
| 8 | Phase 0cの確認 | 翻訳の検証アプリが動いた後 | 4 |
| 9 | 後片付けと記録 | 0bと0cの終了時 | 7、8 |

1〜5は、エージェントに最初の指示を出す前に済ませます。6以降は、エージェントの作業の途中で人の番が来たときに行います。

手順の中で決めた値は、後の節で繰り返し使います。次の値を手元に控えてください。パスワードとauth keyは、この文書にもリポジトリにも書きません。

| 値 | 決まる節 | 例 |
| --- | --- | --- |
| マシン名 | 3 | `rss` |
| tailnet名 | 3 | `tail1a2b3c` |
| サービス名 | 3 | `freshrss`(`svc:freshrss`) |
| 公開用のURL | 3 | `https://freshrss.tail1a2b3c.ts.net`(マシン名ではなくサービス名を使う) |
| FreshRSSのユーザー名 | 5 |  |
| ページサイズ | 4 | `4096` か `16384` |

## 1. GitHubの準備

**手順**

1. ブラウザで [prof18/feed-flow](https://github.com/prof18/feed-flow) を開き、Fork を押す。作成画面で「Copy the `main` branch only」のチェックを外してから作成する。
2. New repository から、空のリポジトリ `tsbridge` を作る。Private を選び、README、.gitignore、ライセンスは追加しない。

公開リポジトリのフォークは非公開にできません。auth key、APIパスワード、tailnet名を含むURLは、フォークにコミットしないでください。

**確認**

- [x] フォークのページに「forked from prof18/feed-flow」と表示される。
- [x] フォークの Tags に `1.21.0-fdroid` がある。無い場合もそのまま進めてよい(2節でクローンした後、`git fetch upstream tag 1.21.0-fdroid` で取得できる)。
- [x] `tsbridge` が空の状態で作成されている。

## 2. Mac miniの環境

**手順**

1. Homebrewが入っていなければ入れる。続けて `brew install gh go jq` を実行する。
2. `gh auth login` を実行し、GitHub.com、HTTPS、ブラウザでの認証を選ぶ。
3. OrbStackを入れる(`brew install --cask orbstack`)。起動して初期設定を終え、設定で「Start at login」を有効にする。Docker Desktopが入っている場合は終了し、ログイン時の起動も止める。
4. Tailscaleを入れてログインする。未導入なら、公式が推奨するStandalone版を [パッケージサーバー](https://pkgs.tailscale.com/stable/#macos) から入れる。App Store版が導入済みならそのまま使ってよいが、2つを同時には入れない。
5. `tailscale` コマンドを使えるようにする。
   - Standalone版: Tailscaleの Settings → CLI integration → Show me how → Install Now。
   - App Store版: `~/.zshrc` に `alias tailscale="/Applications/Tailscale.app/Contents/MacOS/Tailscale"` を足す。
6. Tailscaleの設定で、ログイン時に起動する項目を有効にする。
7. Android Studioを入れ、SDK Managerで次を入れる: Android SDK Platform(API 37)、SDK Build-Tools、SDK Platform-Tools、NDK (Side by side)、CMake。
8. `~/.zshrc` に次を足し、ターミナルを開き直す。NDKのバージョンは、入れたフォルダ名に合わせる。

   ```sh
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<入れたバージョン>"
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   export PATH="$PATH:$ANDROID_HOME/platform-tools:$HOME/go/bin"
   ```
9. システム設定 → エネルギー で、「ディスプレイがオフのときに自動でスリープさせない」と「停電後に自動的に起動」を有効にする。
10. フォークをクローンする: `gh repo clone <ユーザー名>/<フォーク名> ~/dev/<フォーク名>`。`tsbridge` も同じ場所にクローンする。

gomobileの導入、タグからの `main` の作成、`applicationId` の変更は、エージェントに任せます。

**確認**

次のコマンドを順に実行し、コメントのとおりになることを確かめます。

```bash
docker version              # Client と Server の両方が表示される
docker context ls           # orbstack の行に * が付く
tailscale status            # 一覧にこのMac miniが出る
gh auth status              # Logged in to github.com
go version
adb version
java -version               # 21 と表示される
ls "$ANDROID_NDK_HOME"      # ndk-build などが並ぶ
pmset -g | grep -w sleep    # sleep 0
git -C ~/dev/<フォーク名> remote -v   # origin が自分、upstream が prof18
```

- [x] 上のコマンドがすべて期待どおりになる。
- [x] Mac miniを再起動してログインすると、操作なしでOrbStackとTailscaleが起動する。

## 3. Tailscale管理画面の設定

**手順**

1. [Machines](https://login.tailscale.com/admin/machines) を開く。Mac miniの行の「…」→ Edit machine name を選び、自動生成のチェックを外して、短くて個人情報を含まない名前(例: `rss`)にする。公開用のURLにはサービス名を使うが、マシン名もtailnet内の一覧などに出るため、個人情報を含めない。
2. 同じメニューで Disable key expiry を選ぶ。Mac miniのノードキーが失効すると、FreshRSSに届かなくなるため。手順5でタグを付けると、有効期限は既定で無効になる。
3. [DNS](https://login.tailscale.com/admin/dns) を開く。ページ上部のtailnet名(`tail….ts.net`)を確かめ、「.ts.net」より前の部分をtailnet名として控える。MagicDNSが無効なら Enable MagicDNS を押す。
4. 同じページの HTTPS Certificates で Enable HTTPS を押す。HTTPS証明書を発行すると、サービス名とtailnet名が公開の証明書ログに載る。
5. [Access controls](https://login.tailscale.com/admin/acls) で、サービスのホスト用のタグと、サービスへのアクセスの許可を足す。`src` には、FreshRSSを使う自分の端末(Fold8と、0bでアプリが作るノード)が入るようにする。次は例。

   ```json
   "tagOwners": { "tag:service-host": ["autogroup:admin"] },
   "grants": [
     { "src": ["autogroup:member"], "dst": ["svc:freshrss"], "ip": ["443"] }
   ]
   ```
6. Machinesで、Mac miniの「…」→ Edit ACL tags から `tag:service-host` を付ける。サービスのホストは、タグ付きの端末でなければならない。
7. [Services](https://login.tailscale.com/admin/services) で、サービス `freshrss` を定義する。ポートは443。ホストの承認は、5節でserveを設定した後に行う。
8. Settings → [Keys](https://login.tailscale.com/admin/settings/keys) → Generate auth key を開く。Descriptionに `tsbridge-spike`、ReusableとEphemeralをオン、タグなし、有効期限は30日程度にして生成する。
9. 表示されたキー(`tskey-auth-…`)はこの画面でしか見られない。リポジトリの外に保存する。

   ```sh
   mkdir -p ~/.config/tsbridge && chmod 700 ~/.config/tsbridge
   pbpaste > ~/.config/tsbridge/authkey && chmod 600 ~/.config/tsbridge/authkey
   ```

このauth keyは、0bのチェックポイント1〜5をエージェントが無人で回すためだけに使います。エージェントにはファイルの場所だけを伝えます。

**確認**

```sh
tailscale status --json | jq -r .Self.DNSName   # <マシン名>.<tailnet名>.ts.net. と表示される(公開用のURLではない)
tailscale status --json | jq -c .Self.Tags      # ["tag:service-host"]
ls -l ~/.config/tsbridge/authkey                # -rw------- で始まる
```

- [x] Machinesで、Mac miniが新しいマシン名になり、「Expiry disabled」と表示される。
- [x] Mac miniに `tag:service-host` が付いている(2026-11-14、`Self.Tags` で確認)。
- [x] Servicesに `freshrss` が定義されている。
- [x] DNSページで、MagicDNSとHTTPS Certificatesの両方が有効になっている(ボタンが Disable に変わっている)。
- [x] Keysの一覧に、ReusableとEphemeralの付いた `tsbridge-spike` がある。
- [x] 控えの表に、マシン名、tailnet名、公開用のURLを記入した。

## 4. Galaxy Z Fold8の準備

**手順**

1. 設定 → 端末情報 → ソフトウェア情報 → ビルド番号 を7回タップし、開発者向けオプションを有効にする。
2. 設定 → 開発者向けオプション → USBデバッグ をオンにする。
3. USBケーブルでMac miniにつなぐ。端末に出る「USBデバッグを許可しますか?」で、「このパソコンからの接続を常に許可」にチェックを入れて許可する。
4. Playストアから公式のTailscaleアプリを入れ、Mac miniと同じアカウントでログインして接続する。0aの基準線と、0bを見送った場合の代替経路に使う。

USBデバッグをオンにできない、または `adb install` が拒否される場合は、設定 → セキュリティおよびプライバシー → オートブロッカー の設定を確かめてください。One UIの版によって項目名が違うことがあります。

**確認**

```sh
adb devices                                  # <シリアル>  device と出る(unauthorized ではない)
adb shell getprop ro.product.model           # 端末の型番
adb shell getprop ro.build.version.release   # 17
adb shell getconf PAGE_SIZE                  # 4096 か 16384
```

- [x] `adb devices` に `device` と表示される。
- [x] ページサイズの値を控えの表に記入した。16384の場合は、0bと0cの指示でエージェントに伝える。
- [x] 端末のTailscaleアプリが接続済みで、一覧にMac miniが新しいマシン名で表示される。

## 5. FreshRSSの構築と確認

以下では、公開用のURLを `$BASE` と書きます。ターミナルで先に設定しておきます。

```sh
BASE=https://freshrss.<tailnet名>.ts.net
```

マシン名のURL(`https://<マシン名>.<tailnet名>.ts.net`)ではFreshRSSに届きません。

**手順**

1. 置き場を作る: `mkdir -p ~/services/freshrss && cd ~/services/freshrss`。計画書の `compose.yaml` をこのフォルダに保存する。
2. 起動する: `docker compose up -d`。
3. tailnetに公開する: `tailscale serve --service=svc:freshrss --https=443 127.0.0.1:8080`。サービスへのserveは自動でバックグラウンドの設定になる(`--bg` は不要)。出力に公開用のURLと `proxy http://127.0.0.1:8080` が表示される。
   - 続けて、管理画面の [Services](https://login.tailscale.com/admin/services) で、Mac miniを `freshrss` のホストとして承認する。ポリシーの `autoApprovers` で `tag:service-host` を自動承認にしている場合は不要。
4. Mac miniのブラウザで `$BASE/` を開く。初回は証明書の発行で数十秒かかることがある。`localhost:8080` や `orb.local` のURL、マシン名のURLでは開かない。
5. インストール画面を進める。言語を選び、チェックがすべて通ることを確かめ、データベースはSQLite、認証方法はWebフォームを選び、ユーザー名とパスワードを決める。
6. `base_url` を公開用のURLに合わせる。

   ```sh
   docker exec --user www-data freshrss ./cli/reconfigure.php --base-url "$BASE"
   ```
7. FreshRSSにログインし、設定 → 認証 で「APIアクセスを許可する」にチェックを入れて送信する。
8. 設定 → プロフィール の「API管理」で「APIのパスワード」を入力して送信する。ログイン用のパスワードとは別のものにする。
9. 購読フィードの管理 → フィードを追加する で、英語のフィードを含む3件以上を登録する。

**確認1: 起動と公開**

```sh
docker compose ps                      # freshrss が running
tailscale serve status                 # 公開用のURL (tailnet only) (svc:freshrss) → proxy http://127.0.0.1:8080
lsof -nP -iTCP:8080 -sTCP:LISTEN       # 127.0.0.1:8080 だけが出る(*:8080 が出たらLANに公開されている)
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/i/"   # 200
docker exec freshrss crontab -l        # 行の先頭が 13,43
```

- [x] 上のコマンドがすべて期待どおりになる。
- [x] 同じWi-Fiにいる別の端末でTailscaleを切断し、`http://<Mac miniのLANのIP>:8080/` を開いてもつながらない。
- [x] Fold8のTailscaleを切断すると、ブラウザで `$BASE/` が開けない。接続すると開けて、ログインできる。

**確認2: APIと `base_url`**

ブラウザで `$BASE/api/` を開き、Google Reader互換 APIの欄にあるサーバー設定の確認リンクを押します。続けて、ターミナルでログインと購読一覧の取得を試します。

```sh
FRESH_USER=<FreshRSSのユーザー名>
read -s PW        # APIのパスワードを入力する(画面には出ない)
AUTH=$(curl -s "$BASE/api/greader.php/accounts/ClientLogin" \
  --data-urlencode "Email=$FRESH_USER" --data-urlencode "Passwd=$PW" | sed -n 's/^Auth=//p')
echo "$AUTH"      # <ユーザー名>/<長い英数字> と出る
curl -s -H "Authorization:GoogleLogin auth=$AUTH" \
  "$BASE/api/greader.php/reader/api/0/subscription/list?output=json" \
  | jq -r '.subscriptions[] | "\(.title)\t\(.iconUrl)"'
```

- [x] 確認リンクの結果が PASS になる。
- [x] `echo "$AUTH"` が空でない。空の場合は、手順7と8を見直す。
- [x] 登録したフィードがすべて並ぶ。
- [x] 各行の `iconUrl` が公開用のURLで始まる。`localhost` や `127.0.0.1` で始まる場合は、手順6をやり直す。

**確認3: 自動更新と再起動**

- [ ] 毎時13分か43分を過ぎた後、`docker exec freshrss cat /tmp/FreshRSS.log` にフィード更新の記録が出る。
- [ ] Mac miniを再起動し、ログイン画面のままFold8から `$BASE/` を開く。開けるかどうかを記録する。
- [ ] ログインして2〜3分待ち、操作なしでFold8から `$BASE/` が開ける。`tailscale serve status` に `svc:freshrss` の設定が残っていることも確かめる。開けない場合は、確認1のコマンドで止まっている箇所を調べる。

TailscaleのmacOSアプリは、ログイン前には動きません。停電後に無人で復帰させたい場合は、システム設定 → ユーザとグループ で自動ログインを設定します。自動ログインはFileVaultが有効だと使えず、本体に触れられる人がそのまま操作できる状態になるため、設置場所を踏まえて決めてください。

## 6. Phase 0aの実機確認

エージェントが `applicationId` を変更し、`./gradlew :androidApp:assembleFdroidDebug` を通した後に行います。

**手順**

1. Fold8をUSBでつなぎ、APKを入れる。エージェントに `adb install -r` を実行させてもよい。
2. 端末の公式Tailscaleアプリが接続済みであることを確かめる。
3. アプリを起動し、アカウントの追加でFreshRSSを選ぶ。URLに `https://freshrss.<tailnet名>.ts.net/api/greader.php`、ユーザー名、APIのパスワードを入力する。
4. 同期が終わるのを待つ。

**確認**

- [x] ランチャーに、変更後のアプリ名とアイコンで表示される。
- [x] `adb shell pm list packages | grep <新しいapplicationId>` に1行出る。
- [x] 購読フィードの件数が、FreshRSSの画面と一致する。
- [x] 未読記事が表示され、フィードのアイコンが出る。
- [x] アプリで1件を既読にして同期すると、ブラウザのFreshRSSでその記事が既読になっている。
- [x] ブラウザのFreshRSSで別の1件を既読にしてアプリで同期すると、アプリでも既読になる。
- [x] 公式Tailscaleアプリを切断して同期すると失敗し、再接続すると成功する。

すべて通ったら、この状態をエージェントにコミットさせます。これが以降の基準線になります。

## 7. Phase 0bで人が行う確認

チェックポイント1〜5と7は、エージェントが無人で進めます。人が行うのは、開始前の準備、6のログイン、8の回線切替、撤退の判定です。

### 開始前の準備

1. FreshRSSの接続情報を、リポジトリの外のファイルに置く。

   ```sh
   cat > ~/.config/tsbridge/spike.env <<'EOF'
   FRESHRSS_URL=https://freshrss.<tailnet名>.ts.net/api/greader.php
   FRESHRSS_USER=<ユーザー名>
   FRESHRSS_API_PASSWORD=<APIのパスワード>
   EOF
   chmod 600 ~/.config/tsbridge/spike.env
   ```
2. 端末の公式Tailscaleアプリを切断し、`adb shell am force-stop com.tailscale.ipn` で止める。常時接続VPNに設定している場合は外す。
3. エージェントへの指示に次を含める: `authkey` と `spike.env` の場所、ページサイズの値、試行ごとに `SPIKE_LOG.md` へ追記すること、接続情報をコミットしないこと。

- [x] `adb shell pidof com.tailscale.ipn` が何も出力しない。
- [x] `ls -l ~/.config/tsbridge/` のファイル(`authkey`、`spike.env`、pre-commit が読む `denylist`)が、すべて `-rw-------` で始まる。

チェックポイント1〜5の間は、管理画面のMachinesに検証用のノードがEphemeralの表示付きで現れ、停止後にしばらくして消えます。進み具合は `SPIKE_LOG.md` で確かめます。

### チェックポイント6: 対話ログイン

1. エージェントに6へ進むよう指示する。エージェントは、状態ディレクトリを新しくし、auth keyを外した検証アプリを端末に入れる。
2. 検証アプリを起動する。表示されたログインURLを端末のブラウザで開き、Tailscaleのアカウントでログインして、デバイスの接続を承認する。
3. 検証アプリに戻り、状態が `running` になるのを待つ。
4. 管理画面のMachinesで、新しく現れたノードの「…」→ Disable key expiry を選ぶ。

- [x] Machinesにノードがあり、Ephemeralの表示がなく、「Expiry disabled」と表示される。
- [x] logcatに `running` とtailnet上のIPが出る。
- [x] チェックポイント3(FreshRSSへのログインと購読件数の取得)が通る。
- [x] 公式Tailscaleアプリは止めたままである。

### チェックポイント7: 再起動後の復帰

エージェントが `adb` で検証アプリを再起動して確かめます。人は画面を見て次を確かめます。

- [x] 再起動後にログインURLが表示されず、そのまま `running` になる。
- [x] 端末を再起動した後も、再ログインなしでチェックポイント3が通る。

### チェックポイント8: 回線の切替

logcatを取り続けるため、USBでつないだまま行います。無線デバッグでは、Wi-Fiを切った時点で接続が切れます。

1. 自宅のWi-Fiにつないだ状態で、チェックポイント3を実行して成功を確かめる。
2. クイックパネルでWi-Fiをオフにする。ステータスバーがモバイル回線の表示に変わるのを確かめる。
3. 切替からチェックポイント3の成功がlogcatに出るまでの秒数を記録する。
4. Wi-Fiをオンに戻し、同じように秒数を記録する。
5. 2〜4をもう一度繰り返す。

- [x] モバイル回線だけの状態で、チェックポイント3が通る。
- [x] 4回の切替のすべてで、30秒以内に成功が出る。
- [x] 切替の間、検証アプリを再起動していない。
- [x] 4回分の秒数を `SPIKE_LOG.md` に記録した。

### 撤退の判定

判定は人が行います。`SPIKE_LOG.md` を読み、次のどちらかに当たる場合は埋め込みを見送り、公式Tailscaleアプリ経由の直結に切り替えます。

- tailscale.comのソースにパッチを当てないと先へ進めない。
- 同じチェックポイントで、原因の仮説を変えた試行が3回続けて到達点を進められなかった。

## 8. Phase 0cで人が行う確認

### モデルの入手

1. Hugging Faceで `plamo-3-translate-0.8b-GGUF` のページを開き、PLaMo Community Licenseの条件を読む。同意を求められた場合は同意する。
2. Q4\_K\_Mのファイルを `~/models/` にダウンロードする。
3. 2Bのモデルは、0.8Bの品質が足りないと判断したときにだけ入手する。

- [x] `ls -lh ~/models/` で、ファイルのサイズが約0.57GBである。
- [x] `shasum -a 256 ~/models/<ファイル名>` の値を記録し、エージェントに渡した。

### 評価用の記事を選ぶ

普段読むフィードから、英語の記事を10本選びます。偏りを避けるため、次の内訳を目安にします。

| 種類 | 本数 |
| --- | --- |
| コードブロックを含む技術記事 | 3 |
| ニュース記事 | 3 |
| 長文の記事 | 2 |
| 箇条書きや表が多い記事 | 1 |
| 短い告知 | 1 |

URLを1行ずつ `docs/spike/eval-articles.txt` に書き、エージェントに渡します。

### 訳文を読む

エージェントに、原文と訳文を段落ごとに並べたファイルを出力させます。10本すべてを読み、次の観点で誤りを数えます。

| 観点 | 見るところ | 重さ |
| --- | --- | --- |
| 否定 | not、never、no longer、unless の向きが逆になっていないか | 重大 |
| 数値 | 数、単位、日付、バージョン番号が変わっていないか | 重大 |
| 訳抜け | 文や段落が抜ける、途中で切れる、同じ文が繰り返される | 重大 |
| コードとURL | 変更されずに残っているか | 重大 |
| 固有名詞 | 製品名、人名、社名が別のものになっていないか | 軽微(意味が変わる場合は重大) |
| 専門用語 | 定訳から外れていないか、同じ語の訳が揺れていないか | 軽微 |

結果は、記事ごとに1行で `docs/spike/eval-result.md` に記録します。

| 記事 | 重大 | 軽微 | 意味が取れたか | メモ |
| --- | --- | --- | --- | --- |
| 1 |  |  |  |  |

- [ ] 10本すべての行が埋まっている。
- [ ] 重大な誤りには、原文と訳文の該当箇所をメモに貼ってある。エージェントが、モデルの限界か前後の処理の不具合かを切り分けられるようにするため。
- [ ] 本文を続けて翻訳している間、端末を持ち続けられる熱さかどうかを記録した。
- [ ] 結果を計画書の「判断の目安」に当て、0.8Bを使う、2Bで同じ10本を評価し直す、本文翻訳には使わない、のいずれかを決めた。

## 9. 後片付けと記録

**手順**

1. 管理画面の Settings → Keys で `tsbridge-spike` を Revoke する。続けて `rm ~/.config/tsbridge/authkey` を実行する。
2. Machinesで、検証アプリがチェックポイント6で作ったノードを Remove する。Phase 1では、本番のアプリから改めてログインする。
3. エージェントに、次の項目を `docs/PLAN.md` へ記録させ、内容を読んで確かめる。

| 項目 | 記録する内容 |
| --- | --- |
| 0bの結果 | 埋め込みを採用するか見送るか。根拠にした `SPIKE_LOG.md` の箇所 |
| 0bの計測値 | 起動から疎通までの秒数(初回と2回目以降)、AARのサイズ、回線切替の秒数 |
| 端末 | ページサイズ |
| 0cの結果 | 採用するモデルと量子化、SHA-256、評価結果のファイルの場所 |
| コードの保護 | 採用した方式 |
| Mac mini | 再起動後に使えるようになる条件、自動ログインを設定したかどうか |

**確認**

```sh
cd ~/dev/<フォーク名>
git log --all --oneline -S 'tskey-auth'     # 何も出ない
git grep -n '<tailnet名>'                   # 何も出ない
```

- [ ] Keysの一覧に `tsbridge-spike` がない。
- [ ] 上の2つのコマンドが、フォークと `tsbridge` の両方で何も出力しない。
- [ ] `docs/PLAN.md` に、表の項目がすべて書かれている。
- [ ] 0bを見送った場合は、端末の公式Tailscaleアプリを接続した状態に戻した。
