# Audio Storage API

[English](README.md) | [日本語](README.ja.md)

An audio storage and retrieval API built with Kotlin/JVM. It uses the JDK HttpServer, SQLite JDBC, and FFmpeg without a web framework or ORM.

## Requirements

- Docker Desktop with Linux containers and Docker Compose
- No separate installation of JDK, Gradle, or FFmpeg on the host is required

## Start

Run these commands from the project directory. The first build may take a few minutes to download images and dependencies.

```powershell
docker compose up --build -d
docker compose logs -f app
```

The API is exposed on localhost port 80 and is accessible only from this PC by default. If port 80 is already in use, choose another host port in PowerShell:

The JVM may take a few seconds to become ready after startup. If a request fails to connect or receives an empty reply, wait briefly and try again.

```powershell
$env:HOST_PORT = '8080'
docker compose up --build -d
```

In this case, replace localhost with localhost:8080 in the curl examples below. Keep HOST_PORT set in that PowerShell session for subsequent Compose commands.

Both user and phrase master tables are seeded with IDs 1, 2, and 3. They contain no attributes other than the ID.

## Verify normal behavior

After startup, open another terminal in the project directory. Use curl.exe in Windows PowerShell, or replace it with curl on macOS/Linux.

### 1. Prepare test audio

Place a local m4a file named `test_audio_file_1.m4a` in the project directory, or generate a one-second tone with these commands. Generation overwrites test audio with the same name.

```powershell
docker compose exec -T app ffmpeg -v error -y -f lavfi -i 'sine=frequency=440:duration=1' -c:a aac /tmp/test_audio_file_1.m4a
docker compose cp app:/tmp/test_audio_file_1.m4a ./test_audio_file_1.m4a
```

### 2. Create a recording with POST

```powershell
curl.exe --request POST 'http://localhost/audio/user/1/phrase/1' --form 'audio_file=@./test_audio_file_1.m4a' --include
```

An unused ID pair returns HTTP `201`, `error_code: 0`, and `operation: "created"`. An existing pair returns HTTP `200` and `operation: "replaced"`. Stored data survives image rebuilds.

To verify creation, choose a user/phrase pair that has no recording yet, using the seeded IDs 1 through 3.

### 3. Replace the recording with another POST

```powershell
curl.exe --request POST 'http://localhost/audio/user/1/phrase/1' --form 'audio_file=@./test_audio_file_1.m4a' --include
```

The response is HTTP `200`, `error_code: 0`, and `operation: "replaced"`.

### 4. Retrieve and play the recording

```powershell
curl.exe --fail --show-error --request GET 'http://localhost/audio/user/1/phrase/1/m4a' --output './test_response_file_1_1.m4a' --write-out 'HTTP %{http_code}\n'
```

On success, curl prints `HTTP 200` and saves the m4a in the current directory. Open it in an audio player and check that it plays. An existing output file with the same name is overwritten.

GET returns audio on success and JSON on failure. The `--fail` option prevents HTTP error bodies from being saved as audio.

### 5. Check result logs

```powershell
docker compose logs --tail 20 app
```

| Action | HTTP status | error_code | operation | level |
|---|---|---|---|---|
| Create with POST | 201 | 0 | created | INFO |
| Replace with POST | 200 | 0 | replaced | INFO |
| GET | 200 | 0 | retrieved | INFO |

Match the POST response's `X-Request-ID` header with `request_id` in the log. To follow logs in real time, use `docker compose logs -f app`; `Ctrl+C` stops log viewing without stopping the app.

## Persistence and shutdown

The SQLite database at `/data/app.sqlite` and recordings under `/data/audio` are stored in the Docker named volume `audio-data`.

```powershell
docker compose down
docker compose up -d
```

Recordings remain available after this stop and recreation. Do not use `docker compose down -v` if you want to retain data: it deletes the volume containing the database and recordings.

## Database, audio, and temporary paths

These paths are inside the container with the default Compose setting `DATA_DIR=/data`. They are not paths under the Windows project directory.

| Container path | Purpose | Retention and cleanup |
|---|---|---|
| `/data/app.sqlite` | SQLite user, phrase, and recording metadata | Persistent across restarts and container recreation |
| `/data/audio/{UUID}.wav` | Stored audio in PCM WAV format | Persistent; the old WAV is removed after the new WAV and database update succeed |
| `/data/uploads/upload-*.m4a` | Temporary POST upload | Removed on a failed read or when HTTP handling finishes |
| `/data/audio/.tmp/{UUID}.m4a` | Temporary audio converted for GET | Removed on normal conversion failure or when HTTP handling finishes |

Temporary directories are also within `audio-data`, but their files are normally deleted after processing. Automatic recovery of files left by forced termination or cleanup failures is not implemented.

The generated `/tmp/test_audio_file_1.m4a` is a test asset outside the API storage area and does not survive container recreation. Files retrieved with `docker compose cp` or curl's `--output` are saved on the host.

## Build and test

```powershell
docker compose run --build --rm test
docker build --target build -t audio-storage-api-build .
```

Tests cover API success and error cases, database initialization and replacement, audio conversion, and preservation of existing recordings on failure. They use temporary databases and do not modify the running app's database. Run the test service with the command above.

The Docker build runs all tests and creates the application distribution. The runtime image contains the JRE, FFmpeg, and the app, and runs as a non-root user.

## HTTP API

| Method | Path | Success response |
|---|---|---|
| POST | /audio/user/{user_id}/phrase/{phrase_id} | 201 for creation, 200 for replacement; JSON and a Location header |
| GET | /audio/user/{user_id}/phrase/{phrase_id}/m4a | 200 with audio/mp4 bytes |

POST accepts exactly one multipart/form-data file field named audio_file, with a 10 MiB audio limit. Client-provided filenames are not used as storage paths.

Example successful POST:

```json
{"error_code":0,"message":"Audio created.","data":{"user_id":1,"phrase_id":1,"operation":"created"}}
```

For replacement, operation is replaced and message is Audio replaced. POST and GET errors share the following structure, with data set to null:

```json
{"error_code":2001,"message":"User not found.","data":null}
```

| HTTP | error_code | Cause |
|---|---|---|
| 400 | 1001 | Invalid ID format |
| 400 | 1002 | Missing audio_file |
| 400 | 1003 | Invalid audio data |
| 400 | 1004 | Malformed multipart, empty file, or duplicate files |
| 404 | 2001 / 2002 / 2003 | User / phrase / recording not found |
| 404 | 2004 | Endpoint not found |
| 405 | 2005 | Unsupported method; includes an Allow header |
| 413 | 3001 | Size limit exceeded |
| 415 | 3002 | Unsupported audio format |
| 415 | 3003 | Unsupported request Content-Type |
| 500 | 9001 | Audio conversion timeout |
| 500 | 9002 | Other internal failure |

## Request logs

- Responses include X-Request-ID, and the matching result is written to stdout as one JSON line.
- timestamp is UTC; duration_ms is elapsed time. Success is INFO, HTTP 5xx is ERROR, and other failures are WARN.
- Exception types, messages, and stack traces are included only in internal-error logs, not API responses. Audio and request bodies are not logged.
- File cleanup failures are recorded as separate WARN lines in the same output stream.
- Transmission failures use log-only error_code 9003. An error JSON response cannot always be sent because transmission has already started.
- Outside Compose, PORT defaults to 8080 and DATA_DIR to data. Compose sets PORT=8080 and DATA_DIR=/data.
- HOST_PORT changes the host-side access port; the container still listens on 8080. See the example under Start.
- Docker retains up to three log files of 10 MB each.

## Implementation files

Application code is under `src/main/kotlin/audio/`. To follow request handling, start with startup wiring in `Main.kt`, then read `handle()` in `AudioHttpHandler.kt` and its callees.

| File | Responsibility |
|---|---|
| [Main.kt](src/main/kotlin/audio/Main.kt) | Wires configuration, database, and services; starts the HTTP server and registers shutdown handling |
| [AppConfig.kt](src/main/kotlin/audio/AppConfig.kt) | Reads PORT and DATA_DIR and validates startup settings |
| [AudioHttpHandler.kt](src/main/kotlin/audio/AudioHttpHandler.kt) | Validates paths, methods, and IDs; dispatches POST/GET, sends responses, cleans up temporary files, and logs results |
| [MultipartAudioReader.kt](src/main/kotlin/audio/MultipartAudioReader.kt) | Parses multipart data, validates fields, extension, and size, and saves temporary uploads |
| [AudioService.kt](src/main/kotlin/audio/AudioService.kt) | Coordinates ID checks, conversion, database updates, and file cleanup for storage, replacement, and retrieval |
| [AudioRepository.kt](src/main/kotlin/audio/AudioRepository.kt) | Connects to SQLite, initializes tables, checks master IDs, and reads and writes recording metadata |
| [AudioConverter.kt](src/main/kotlin/audio/AudioConverter.kt) | Inspects formats with ffprobe and converts m4a/WAV with FFmpeg under a shared time limit |
| [ProcessCommandRunner.kt](src/main/kotlin/audio/ProcessCommandRunner.kt) | Runs commands, collects stdout/stderr, and handles timeouts and termination |
| [HttpFailure.kt](src/main/kotlin/audio/HttpFailure.kt) | Maps exceptions to HTTP statuses, numeric error codes, and public messages |
| [RequestLogger.kt](src/main/kotlin/audio/RequestLogger.kt) | Writes request results and separate cleanup warnings as one-line JSON to stdout |

POST flow (create or replace):

```text
AudioHttpHandler (validate URL, method, and ID format)
 -> AudioService (check user and phrase existence)
 -> MultipartAudioReader (receive and temporarily store audio)
 -> AudioService (store the recording)
    -> AudioConverter (convert m4a to WAV)
       -> ProcessCommandRunner (run ffprobe and ffmpeg)
    -> AudioRepository (insert or update recording metadata)
    -> Delete the old WAV (on replacement)
 -> AudioHttpHandler (JSON response, temporary file cleanup, and logging)
```

GET flow (retrieve):

```text
AudioHttpHandler (validate URL, method, and ID format)
 -> AudioService (check user and phrase existence)
    -> AudioRepository (find recording metadata)
    -> AudioConverter (convert WAV to m4a)
       -> ProcessCommandRunner (run ffprobe and ffmpeg)
 -> AudioHttpHandler (audio response, temporary file cleanup, and logging)
```

## Database schema

- users: id only; seeded IDs are 1, 2, and 3.
- phrases: id only; seeded IDs are 1, 2, and 3.
- recordings: user_id, phrase_id, file_path, created_at, and updated_at.
- Each (user_id, phrase_id) pair has one recording. Replacement updates its path and updated_at while preserving created_at.
- Timestamps are stored as UTC strings ending in Z. Retrieval does not update them.
- Startup adds missing seed IDs while preserving existing recordings.

## Storage, replacement, and retrieval

- AudioService checks user and phrase existence, creates a UUID-named WAV, updates the database, then removes the old WAV.
- Conversion or database update failures preserve the previous recording and timestamps. On database failure, the new WAV is deleted.
- Old-file deletion failures after commit are logged as warnings without undoing the successful update.
- Storage paths are validated to prevent access outside the audio storage directory.
- Temporary upload and download m4a files are deleted after processing.

## Audio conversion

- POST converts m4a to WAV for storage; GET converts WAV to m4a for download.
- ffprobe checks the actual format and presence of audio; files containing video are rejected.
- Inspection and conversion share a 30-second limit. Process shutdown after timeout may take additional time.

## Pinned versions

| Component | Version |
|---|---|
| Kotlin | 2.2.21 |
| JDK | 21 |
| Gradle | 8.14.3 |
| SQLite JDBC | 3.50.3.0 |
| Apache Commons FileUpload Core | 2.0.0-M5 |
| kotlinx.serialization JSON | 1.9.0 |
| JUnit | 5.13.4 |

FFmpeg is installed from the Docker base OS package repository.

## Design decisions and scope

- SQLite avoids the need to prepare a separate database server.
- Only the latest recording for each user/phrase pair is stored; history is not retained.
- Conversion runs within the request. Storage and retrieval are serialized, and the app is intended for a single container.
- Master IDs are seeded; no master-data management API is provided.
- Authentication, encrypted transport, and per-user access control are not implemented.
- Forced termination may leave unreferenced audio or temporary files. Automatic cleanup of these files is not implemented.

## Production improvements

- User authentication, access control, and encrypted transport.
- Protection against high request volumes and slow uploads.
- Recovery of unused files and backups of the database and audio.

## Validation results

- All 44 tests passed with `docker compose run --build --rm test`.
- Verified POST creation (201), POST replacement (200), GET (200), and playback of downloaded audio.
- Checked all 15 error codes: 9003 through an automated test and the others manually.
- Confirmed that invalid POSTs preserve existing audio and recordings survive container recreation.

## Files to submit to Git

Include the following files. Submit the complete Gradle Wrapper, including its JAR, so Docker builds use the specified Gradle version.

```text
src/main/                Application code
src/test/                Automated tests
gradle/wrapper/          Gradle Wrapper and settings
gradlew / gradlew.bat    Wrapper launch scripts
build.gradle.kts         Dependencies and build settings
settings.gradle.kts      Project settings
gradle.properties       Gradle execution settings
Dockerfile              Build and runtime images
compose.yaml            Startup, persistence, and logging
.dockerignore           Docker build exclusions
.gitignore              Git exclusions
.gitattributes          Line-ending settings
README.md               Usage, API, and design notes (English)
README.ja.md            Usage, API, and design notes (Japanese)
```
