# Vesper

A private Android messaging platform: one live public **Hall**, end-to-end encrypted one-to-one **Direct**
conversations, and an in-app administrator **Console** — with messages that genuinely disappear
(text after 72 h, voice and photos after 24 h, counted from server acceptance).

| Part | Stack |
|---|---|
| Android app (`app/`) | Kotlin, Jetpack Compose, Hilt, Room on SQLCipher, OkHttp WebSocket, libsignal, Media3 |
| Backend (`server/`) | Kotlin, Ktor (Netty), MongoDB Atlas, Cloudinary (raw, authenticated), FCM HTTP v1 (optional) |
| Shared protocol (`shared/`) | kotlinx.serialization DTOs and limits used by both sides |

Docs: [architecture](docs/ARCHITECTURE.md) · [threat model](docs/THREAT_MODEL.md) · [protocol](docs/PROTOCOL.md) ·
[design](docs/DESIGN.md) · [runbook](docs/RUNBOOK.md) · [ADRs](docs/ADR).

## Local development

1. MongoDB running on `localhost:27017`.
2. Create `.dev/server.env` from `deploy/.env.example` (generate values with the tasks below), set `VESPER_DEV=true`
   and `MEDIA_PROVIDER=local` to keep media on disk.
3. Start the server: `powershell -ExecutionPolicy Bypass -File tools/dev/run-server.ps1`
4. Phone over USB: `adb reverse tcp:8080 tcp:8080`, then `./gradlew :app:installDebug`.

Secret helpers (print to stdout only):

```
./gradlew :server:genSecret                       # JWT_SECRET
./gradlew :server:genKeyset                       # HALL_DATA_KEYSET
./gradlew :server:hashPassword --args="hash <pw>" # ADMIN_PASSWORD_ARGON2
```

Server tests: `./gradlew -p server-build :server:test`

## Deployment (current: free tier, no card)

Render Free (Docker, `render.yaml`) + MongoDB Atlas M0 + Cloudinary Free. Render Free sleeps after 15 min idle, so a
cron-job.org job calls `/health` every 10 minutes (backup: `.github/workflows/keep-awake.yml`). See
[RUNBOOK](docs/RUNBOOK.md) for step-by-step setup and the later move to Oracle Always Free + Cloudflare R2.

## Licences

libsignal is AGPL-3.0: fine for private use; if the APK is ever distributed publicly, its source must be offered.
Bundled fonts (Fraunces, Instrument Sans, JetBrains Mono) are SIL Open Font License 1.1. UI sounds are original,
synthesised for this project.
