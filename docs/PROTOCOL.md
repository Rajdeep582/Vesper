# Protocol

All DTOs live in `shared/src/main/kotlin/.../Protocol.kt` (single source of truth). JSON, `t` as the frame discriminator.

## REST `/v1`

| Method | Path | Notes |
|---|---|---|
| GET | `/auth/available?u=` | username check |
| POST | `/auth/signup`, `/auth/signin` | `{username, password, device}` → tokens, user, deviceId |
| POST | `/auth/refresh` | rotate refresh token |
| POST | `/auth/signout` | revoke this device |
| GET | `/me`, `/me/devices` | |
| POST | `/me/password`, `/me/delete` | |
| PUT | `/me/fcm` | push token |
| GET | `/users?query=&cursor=`, `/users/{id}` | People |
| GET | `/keys/{uid}` | bundles for all devices (claims one-time prekeys) |
| GET | `/keys/{uid}/devices` | device ids |
| GET/POST | `/keys/count`, `/keys/prekeys` | replenishment |
| GET | `/hall?afterSeq=&limit=` | sync |
| GET/POST | `/direct/pending`, `/direct/ack` | envelopes for this device |
| POST | `/media/intents`, `/media/{id}/commit` | upload ticket, provider-verified commit |
| GET | `/media/{id}/url` | 5-minute signed URL; private = participants only |
| * | `/admin/*` | role-checked server-side; metadata only for private |
| GET | `/health` | keep-awake target |

## WebSocket `/v1/ws`

Client → server: `hall.send`, `direct.send`, `direct.ack`, `ping`.
Server → client: `ack`, `nack` (codes `muted, invalid, devices, media, rate, unknown_user`), `hall.msg`,
`hall.deleted`, `direct.env`, `direct.delivered`, `hall.muted`, `account.ended`, `pong`, and for admin sockets
`admin.activity`, `admin.presence`.

`direct.send` must carry one envelope per recipient device **and** per sender's other device; otherwise the server
replies `nack devices` with `missing`/`extra` so the client can refresh sessions and retry.
