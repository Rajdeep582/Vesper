# Threat model

| Asset | Protection |
|---|---|
| Direct text/voice/photo | libsignal (PQXDH + Double Ratchet) per device; media AES-256-GCM with a per-blob key sent inside the encrypted message. Server, DB, Cloudinary, logs and Console only ever hold ciphertext + metadata. |
| Hall content | TLS in transit; text and media keys AES-256-GCM at rest (Tink keyset `HALL_DATA_KEYSET`); media blobs encrypted client-side. **Not E2EE by design** — every member reads it and the admin moderates it. |
| Local data | Room on SQLCipher; 256-bit passphrase wrapped by a non-exportable Android Keystore key (StrongBox when present). Media cache = ciphertext; decrypted bytes only in memory. Backups disabled. |
| Passwords | Argon2id (19 MiB, t=2), constant-time verify with dummy work for unknown users, common-password list, rate limits. |
| Sessions | 15-min JWT + rotating opaque refresh token (stored hashed), reuse detection revokes the device. |

## Admin cannot read private content

There is no endpoint that returns envelope bodies to anyone except the addressed device, and the server never holds
private keys. The admin has **no password reset / impersonation** capability (it would let them register a device and
receive a user's future messages).

## Known limits (honest)

- A modified server could substitute public keys (MITM). Mitigation: per-device safety numbers in Chat info and an
  in-chat notice whenever a known device's identity key changes.
- Shared admin credentials: each sign-in is a new device of the admin identity and receives **new** private messages
  addressed to the admin from then on (old history is never re-encrypted). Shown in the admin's You screen.
- Hall content is readable by whoever holds `HALL_DATA_KEYSET`.
- Render Free has no static outbound IP → Atlas allows `0.0.0.0/0`; the DB relies on a strong random password and a
  least-privilege user (readWrite on `vesper` only).
- Forgotten passwords are unrecoverable (no email; no admin reset).

## Canary check

Send a Direct message containing a unique random string, then search Mongo (`mongosh`), Cloudinary, server logs and
FCM payloads for it — zero matches expected. Repeat for the Hall: the string must not appear in Mongo in plaintext.
