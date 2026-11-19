# Runbook

## First production setup (no card needed)

1. **MongoDB Atlas** — create an M0 cluster (AWS Singapore `ap-southeast-1`), a DB user with `readWrite` on `vesper`
   and a long random password, Network Access `0.0.0.0/0`. Copy the `mongodb+srv://…` string → `MONGO_URI`.
2. **Cloudinary** — Dashboard → API Keys: copy Cloud name / API key / API secret → `CLOUDINARY_*`.
   Settings → Backup: keep **off** (otherwise deleted files are retained).
3. **Secrets** — `./gradlew :server:genSecret` → `JWT_SECRET`, `./gradlew :server:genKeyset` → `HALL_DATA_KEYSET`,
   `./gradlew :server:hashPassword --args="hash <your admin password>"` → `ADMIN_PASSWORD_ARGON2`; choose `ADMIN_USERNAME`.
4. **Render** — New → Blueprint → this repo (`render.yaml`, Free, Singapore). Paste the env vars. Set
   `PUBLIC_BASE_URL` to the service URL.
5. **Keep-awake** — cron-job.org (free): GET `https://<service>.onrender.com/health` every 10 minutes. Optionally set the
   GitHub repository variable `VESPER_HEALTH_URL` for the backup workflow. The Console's Server card shows the last ping.
6. **App** — put `vesper.apiUrl.release=https://<service>.onrender.com` in `secrets.properties`, then
   `./gradlew :app:assembleRelease` (signing via `keystore.properties`).

## Operations

| Task | How |
|---|---|
| Rotate admin password | regenerate `ADMIN_PASSWORD_ARGON2`, redeploy (env hash change wins over in-app change) |
| Rotate JWT secret | change `JWT_SECRET`, redeploy — everyone refreshes / signs in again |
| Rotate Hall keyset | add a new primary key to the Tink keyset keeping old keys, redeploy |
| Quotas | Console → Overview: DB vs 512 MB, Cloudinary credits vs 25 (warnings at 70/90 %) |
| Atlas pause | idle M0 clusters can be paused by Atlas; normal traffic + keep-awake prevents it |
| Time-travel test (dev only) | `POST /v1/dev/clock?offsetMs=…` then `POST /v1/dev/sweep` (only with `VESPER_DEV=true`) |

## Later: Oracle Always Free + Cloudflare R2 (needs a card)

Same Docker image on an Ampere A1 VM behind Caddy (`deploy/vm/`), Atlas allowlist = VM IP. Switch media with an
`S3MediaStore` implementation and `MEDIA_PROVIDER=s3`; app needs no update because upload tickets are provider-neutral.
Add an R2 lifecycle rule expiring `vesper/m/` after 2 days as a backstop.
