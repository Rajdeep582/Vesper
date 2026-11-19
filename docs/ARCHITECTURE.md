# Architecture

```
Android app (Compose)                          Render Free web service (Docker)
 ├─ ui/        screens, design system           └─ vesper-server (Ktor, JVM 21)   ← TLS by Render
 ├─ data/      Messages engine, repos, expiry       ├─ REST /v1/*
 ├─ core/db    Room on SQLCipher                    ├─ WebSocket /v1/ws
 ├─ core/crypto libsignal stores, media AES-GCM     ├─ Sweeper (30 s) + reconciler (hourly)
 ├─ core/net   OkHttp REST + WebSocket              ├─ FCM sender (content-free wake)
 └─ media/     WebP/blurhash, Opus recorder, player └─ MediaStore → Cloudinary (prod) / local disk (dev)
                                                 MongoDB Atlas M0 · Cloudinary Free · cron-job.org → /health
```

## Modules

The master prompt sketched many Gradle modules; the build uses three (`shared`, `server`, `app`) with package-level
separation inside `app` (see ADR 0002). `server-build/` is a standalone settings file so Docker can build the backend
without the Android SDK.

## Message flow

1. The app writes the message locally as `PENDING` (no countdown).
2. The outbox sends it over the socket with a client id (`cmid`). Media is encrypted, uploaded with a server-signed
   ticket and committed first.
3. The server stamps `acceptedAt` / `expiresAt`, stores it, acks, and fans out. Sends are idempotent on `cmid`.
4. Reconnect → Hall sync by sequence number, pending Direct envelopes fetched, outbox flushed in order.

## MongoDB field dictionary (short keys keep documents small)

| Collection | Fields |
|---|---|
| users | `_id, u` username, `ph` argon2 hash, `seed` env hash (admin), `role, st` status, `mu` mute-until, `at` joined, `la` last active |
| devices | `_id uid:d, uid, d` device id, `reg, ik` identity key, `spk, kyl` last-resort kyber, `label, fcm, at, seen` |
| one_time_prekeys | `uid, d, k` ec/ky, `id, pub, sig` |
| refresh_tokens | `_id` sha256(token), `uid, d, used, at, exp` (TTL) |
| hall_messages | `_id, seq, uid, k` kind, `enc` AES-GCM body, `mid` media id, `cmid, at, exp, ttl` (TTL only for text) |
| private_envelopes | `_id, mid, fromUid, fromD, toUid, toD, type, body` (ciphertext), `k, at, exp` (TTL) |
| private_meta | `_id` mid, `fromUid, toUid, pair, k, cmid, mediaId, bytes, at, exp, del` — metadata only |
| conversations | `_id` pair, `a, b, first, last` |
| media_objects | `_id, prov, key, sc` scope, `k, own, peer, bytes, sha, st` pending/committed/attached, `at, exp, msg` |
| activity_events | `_id, t, uid, un, peer, pn, k, at, exp` (TTL 72 h) |
