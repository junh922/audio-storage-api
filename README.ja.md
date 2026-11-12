# Audio Storage API

[English](README.md) | [日本語](README.ja.md)

Kotlin/JVMによる音声保存・取得APIです。Webフレームワーク・ORMを使わず、JDK標準HttpServer、SQLite JDBC、FFmpegで実装しています。

## 必要な環境

- Docker Desktop（Linuxコンテナ）とDocker Compose
- ホストへのJDK・Gradle・FFmpegのインストールは不要

## 起動

プロジェクトフォルダで実行します。初回ビルドはイメージと依存ライブラリを取得するため数分かかります。

```powershell
docker compose up --build -d
docker compose logs -f app
```

APIはlocalhostの80番で公開します。既定ではこのPCからのみアクセスできます。80番が使用中の場合は、PowerShellでポートを変更できます。

起動直後はJVMの準備に数秒かかる場合があります。接続失敗や空の応答になった場合は、少し待ってからリクエストを実行してください。

```powershell
$env:HOST_PORT = '8080'
docker compose up --build -d
```

この場合は以下のcurl例のlocalhostをlocalhost:8080へ置き換えます。変更したHOST_PORTは、そのPowerShellセッションからの後続Compose操作にも使用してください。

初期データはユーザーID・フレーズIDともに1・2・3です。ID以外のマスター属性はありません。

## 正常系の確認コマンド

起動後、プロジェクトフォルダの別ターミナルで実行します。Windows PowerShellではcurl.exeを使用します。macOS/Linuxではcurl.exeをcurlに置き換えます。

### 1. テスト音声を用意

手元のm4aを`test_audio_file_1.m4a`として置くか、次のコマンドで1秒の合成音を生成して取り出します。生成コマンドは同名のテスト音声を上書きします。

```powershell
docker compose exec -T app ffmpeg -v error -y -f lavfi -i 'sine=frequency=440:duration=1' -c:a aac /tmp/test_audio_file_1.m4a
docker compose cp app:/tmp/test_audio_file_1.m4a ./test_audio_file_1.m4a
```

### 2. POSTで新規保存

```powershell
curl.exe --request POST 'http://localhost/audio/user/1/phrase/1' --form 'audio_file=@./test_audio_file_1.m4a' --include
```

未保存のIDの組み合わせならHTTP `201`・`error_code: 0`・`operation: "created"`、保存済みならHTTP `200`・`operation: "replaced"`が返ります。再ビルドしても保存済みデータは残ります。

新規保存を確認したい場合は、初期ID 1〜3の範囲で、まだ録音を保存していない組み合わせにURLを変更してください。

### 3. 同じIDへ再POSTして置換

```powershell
curl.exe --request POST 'http://localhost/audio/user/1/phrase/1' --form 'audio_file=@./test_audio_file_1.m4a' --include
```

HTTP `200`・`error_code: 0`・`operation: "replaced"`が返ります。

### 4. GETで取得して再生

```powershell
curl.exe --fail --show-error --request GET 'http://localhost/audio/user/1/phrase/1/m4a' --output './test_response_file_1_1.m4a' --write-out 'HTTP %{http_code}\n'
```

成功時は`HTTP 200`が表示され、現在のフォルダへm4aファイルを保存します。保存したファイルを音声プレーヤーで開き、音が出ることを確認してください。同名の取得ファイルがある場合は上書きします。

GET成功時は音声、失敗時はJSONを返します。`--fail`はHTTPエラーの本文を音声として保存しないための指定です。

### 5. 結果ログを確認

```powershell
docker compose logs --tail 20 app
```

| 操作 | HTTP status | error_code | operation | level |
|---|---|---|---|---|
| 新規POST | 201 | 0 | created | INFO |
| 置換POST | 200 | 0 | replaced | INFO |
| GET | 200 | 0 | retrieved | INFO |

POSTのレスポンスヘッダー`X-Request-ID`とログの`request_id`を照合すると、同じリクエストの結果を確認できます。リアルタイムで追う場合は`docker compose logs -f app`を使用し、`Ctrl+C`でログ表示だけを終了します。

## 永続化・停止

SQLiteの/data/app.sqliteと録音の/data/audioは、Docker named volumeのaudio-dataで保持します。

```powershell
docker compose down
docker compose up -d
```

この停止・再作成後も録音を取得できます。`docker compose down -v` はDBと録音を含むボリュームを削除するため、データを残す場合は使用しないでください。

## DB・音声・一時ファイルの保存先

以下はComposeの既定設定（`DATA_DIR=/data`）でのコンテナ内のパスです。Windows上のプロジェクトフォルダのパスとは異なります。

| コンテナ内のパス | 用途 | 保持・削除のタイミング |
|---|---|---|
| `/data/app.sqlite` | ユーザー・フレーズ・録音情報のSQLite DB | 永続化。再起動・再作成後も保持 |
| `/data/audio/{UUID}.wav` | 保存音声本体（PCM WAV） | 永続化。置換時は新しいWAVとDBの更新成功後に旧WAVを削除 |
| `/data/uploads/upload-*.m4a` | POSTで受信した音声の一時ファイル | 受信途中の失敗時、またはHTTP処理の終了時に削除 |
| `/data/audio/.tmp/{UUID}.m4a` | GETで返すために変換した一時音声 | 通常の変換失敗時、またはHTTP処理の終了時に削除 |

一時ディレクトリも`audio-data`内にありますが、通常は処理後に削除します。強制終了や削除失敗などで残ったファイルの自動回収は未実装です。

正常系の確認で生成する`/tmp/test_audio_file_1.m4a`は検証用の素材で、APIの保存領域には含まれません。コンテナ再作成時には保持されません。`docker compose cp`やcurlの`--output`で取り出した音声は、コマンドを実行したホスト側フォルダに保存されます。

## ビルドとテスト

```powershell
docker compose run --build --rm test
docker build --target build -t audio-storage-api-build .
```

テストではAPIの正常系・異常系、DBの初期化・保存・置換、音声変換、失敗時の既存録音の保護などを確認します。テスト用の一時DBを使用し、稼働中アプリのDBには触れません。testサービスは上記のrunで実行します。

Dockerビルド時に全テストと実行用ファイルの生成を行います。実行用イメージはJRE・FFmpeg・アプリを含み、非rootユーザーで動きます。

## HTTP API

| メソッド | パス | 成功時 |
|---|---|---|
| POST | /audio/user/{user_id}/phrase/{phrase_id} | 新規201、置換200。JSONとLocationヘッダー |
| GET | /audio/user/{user_id}/phrase/{phrase_id}/m4a | 200、audio/mp4の音声バイナリ |

POSTはmultipart/form-dataのaudio_fileを1ファイルだけ受け付けます。音声上限は10MiBです。元のファイル名は保存先に使いません。

POST成功の例：

```json
{"error_code":0,"message":"Audio created.","data":{"user_id":1,"phrase_id":1,"operation":"created"}}
```

置換はoperationがreplaced、messageがAudio replaced.です。POST・GETの失敗は同じ構造で、dataはnullです。

```json
{"error_code":2001,"message":"User not found.","data":null}
```

| HTTP | error_code | 原因 |
|---|---|---|
| 400 | 1001 | ID形式不正 |
| 400 | 1002 | audio_file未指定 |
| 400 | 1003 | 音声データ不正 |
| 400 | 1004 | 不正multipart・空ファイル・重複ファイル |
| 404 | 2001 / 2002 / 2003 | ユーザー / フレーズ / 録音の不存在 |
| 404 | 2004 | エンドポイント不存在 |
| 405 | 2005 | メソッド非対応。Allowヘッダーを返す |
| 413 | 3001 | サイズ超過 |
| 415 | 3002 | 音声形式非対応 |
| 415 | 3003 | リクエストContent-Type非対応 |
| 500 | 9001 | 音声変換タイムアウト |
| 500 | 9002 | その他の内部障害 |

## リクエストログ

- 各応答にX-Request-IDを付け、同じIDの結果を標準出力へ1行のJSONで記録します。
- timestampはUTC、処理時間はduration_ms。成功はINFO、5xxはERROR、それ以外の失敗はWARNです。
- 例外の種類・メッセージ・スタックトレースは内部障害のログだけに含め、API応答には含めません。音声やリクエスト本文は記録しません。
- 一時ファイルなどの削除失敗は、結果ログとは別の行にWARNログとして記録します。
- 送信失敗はログ専用error_code9003です。応答を送信中のため、エラーJSONを返せるとは限りません。
- PORTはアプリ単体では既定8080、DATA_DIRは既定dataです。ComposeではPORT=8080、DATA_DIR=/dataに設定しています。
- HOST_PORTでPC側のアクセス用ポートを変更できます。コンテナ内のポートは8080のままです。設定例は「起動」を参照してください。
- Dockerのログは1ファイル10MB・最大3ファイルを保持します。

## 実装ファイルの役割

実装は`src/main/kotlin/audio/`にあります。HTTPの受付から読みたい場合は、`Main.kt`の起動処理を確認した後、`AudioHttpHandler.kt`の`handle()`から呼び出し先を追えます。

| ファイル | 役割 |
|---|---|
| [Main.kt](src/main/kotlin/audio/Main.kt) | 設定・DB・各処理を組み立て、HTTPサーバーを起動。終了時の停止処理も登録 |
| [AppConfig.kt](src/main/kotlin/audio/AppConfig.kt) | 環境変数`PORT`・`DATA_DIR`の取得と起動設定の検証 |
| [AudioHttpHandler.kt](src/main/kotlin/audio/AudioHttpHandler.kt) | URL・メソッド・IDの検証、POST/GETの振り分け、応答送信、一時ファイル削除、結果ログ |
| [MultipartAudioReader.kt](src/main/kotlin/audio/MultipartAudioReader.kt) | multipartの解析、項目数・拡張子・サイズの検証、アップロードの一時保存 |
| [AudioService.kt](src/main/kotlin/audio/AudioService.kt) | IDの存在確認と、変換・DB更新・旧ファイル削除を組み合わせた保存・置換・取得の制御 |
| [AudioRepository.kt](src/main/kotlin/audio/AudioRepository.kt) | SQLiteの接続・テーブル初期化・マスター存在確認・録音情報の検索と保存 |
| [AudioConverter.kt](src/main/kotlin/audio/AudioConverter.kt) | ffprobeによる実形式検査と、FFmpegによるm4a/WAV変換、共通の時間制限 |
| [ProcessCommandRunner.kt](src/main/kotlin/audio/ProcessCommandRunner.kt) | 外部コマンドの起動、標準出力・標準エラーの回収、タイムアウトとプロセス停止 |
| [HttpFailure.kt](src/main/kotlin/audio/HttpFailure.kt) | 例外をHTTPステータス・数値エラーコード・公開用メッセージへ変換 |
| [RequestLogger.kt](src/main/kotlin/audio/RequestLogger.kt) | リクエスト結果と独立した削除警告を1行JSONとして標準出力へ記録 |

POST（保存・置換）の流れ：

```text
AudioHttpHandler（URL・メソッド・ID形式の検証）
 → AudioService（ユーザー・フレーズの存在確認）
 → MultipartAudioReader（音声の受信・一時保存）
 → AudioService（保存処理）
    → AudioConverter（m4aからWAVへ変換）
       → ProcessCommandRunner（ffprobe・ffmpegの実行）
    → AudioRepository（録音情報の登録・更新）
    → 古いWAVの削除（置換時）
 → AudioHttpHandler（JSON応答・一時ファイル削除・ログ）
```

GET（取得）の流れ：

```text
AudioHttpHandler（URL・メソッド・ID形式の検証）
 → AudioService（ユーザー・フレーズの存在確認）
    → AudioRepository（録音情報の取得）
    → AudioConverter（WAVからm4aへ変換）
       → ProcessCommandRunner（ffprobe・ffmpegの実行）
 → AudioHttpHandler（音声応答・一時ファイル削除・ログ）
```

## DB構成

- users：idのみ。初期IDは1・2・3。
- phrases：idのみ。初期IDは1・2・3。
- recordings：user_id、phrase_id、file_path、created_at、updated_at。
- recordingsは(user_id, phrase_id)の組み合わせで1件を管理します。置換時は保存先とupdated_atを更新し、created_atを保持します。
- 日時はUTC（末尾Z）の文字列で保存します。取得では更新しません。
- 起動時に不足する初期IDを追加し、既存録音を維持します。

## 保存・置換・取得

- AudioServiceはユーザーとフレーズの存在を確認し、UUID名でwavを作成してからDBを更新します。成功後に旧wavを削除します。
- 変換やDB更新に失敗しても、以前の録音と日時を保持します。DB失敗時には新しいwavを削除します。
- DB更新後の旧ファイル削除に失敗しても更新成功を取り消さず、警告を記録します。
- 保存先のパスを検証し、指定した音声保存領域の外を参照しないようにしています。
- GET用m4aとアップロードの一時ファイルは、処理後に削除します。

## 音声変換

- POSTではm4aを保存用WAVへ、GETではWAVを返却用m4aへ変換します。
- ffprobeで実際の形式と音声の有無を確認し、動画入りのファイルは拒否します。
- 検査と変換を合わせて30秒の制限を設けています。時間切れ後の停止処理には別途時間がかかる場合があります。

## 固定バージョン

| 項目 | バージョン |
|---|---|
| Kotlin | 2.2.21 |
| JDK | 21 |
| Gradle | 8.14.3 |
| SQLite JDBC | 3.50.3.0 |
| Apache Commons FileUpload Core | 2.0.0-M5 |
| kotlinx.serialization JSON | 1.9.0 |
| JUnit | 5.13.4 |

FFmpegはDockerのベースOSのパッケージから導入します。

## 設計判断・簡略化した点

- SQLiteを採用し、外部DBサーバーの準備を不要にしました。
- 同じユーザー・フレーズの録音は履歴を持たず、最新1件だけ保存します。
- 音声変換はリクエスト内で実行します。保存・取得を同時に実行させず、単一コンテナでの利用を想定しています。
- マスターIDは初期データで用意し、マスター管理APIは設けていません。
- 認証・通信の暗号化・ユーザーごとのアクセス制御は実装していません。
- 強制終了などでは、DBに参照されない音声や一時ファイルが残る場合があります。自動回収は未実装です。

## 本番運用で改善する点

- 利用者の認証とアクセス制御、通信の暗号化。
- 大量アクセスや長時間のアップロードへの対策。
- 不要ファイルの回収と、DB・音声のバックアップ。

## 検証結果

- `docker compose run --build --rm test`で全44テスト成功。
- 新規POST201、置換POST200、GET200と、取得音声の再生を確認。
- 全15種類のエラーコードを確認（9003は自動テスト、その他は手動確認）。
- 不正なPOST後も以前の録音を維持し、コンテナ再作成後も録音を取得できることを確認。

## Gitへの提出対象

以下を提出します。Gradle Wrapper一式は、Docker内で指定バージョンのGradleを使ってビルドするため、jarを含めて提出します。

```text
src/main/                実装
src/test/                自動テスト
gradle/wrapper/          Wrapper本体・設定
gradlew / gradlew.bat    Wrapper起動スクリプト
build.gradle.kts         依存関係・ビルド設定
settings.gradle.kts      プロジェクト設定
gradle.properties       Gradle実行設定
Dockerfile              ビルド・実行イメージ
compose.yaml            起動・永続化・ログ設定
.dockerignore           Dockerへの不要ファイル混入防止
.gitignore              Gitへの不要ファイル混入防止
.gitattributes          改行コード指定
README.md               実行方法・仕様・設計判断（英語）
README.ja.md            実行方法・仕様・設計判断（日本語）
```
