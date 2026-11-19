# ADR 0002 — Three Gradle modules; JCE AES-GCM for local media

**Decision:** `shared` (JVM), `server`, `app` instead of many feature modules — faster builds, same layering by package.
Local media blobs are the same AES-256-GCM ciphertext that was uploaded (key stored in the SQLCipher DB), so Tink
StreamingAead is unnecessary on the device; Tink is used server-side for Hall at-rest encryption. Media dev storage
is a signed local-disk store (`MEDIA_PROVIDER=local`, dev only) instead of MinIO.
