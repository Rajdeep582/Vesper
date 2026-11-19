# ADR 0001 — Free-tier hosting (Render Free + Cloudinary Free)

**Status:** accepted (2026-11). **Context:** the owner has no credit card; Oracle Always Free and Cloudflare R2 both
require one. **Decision:** Render Free (Docker) kept awake by an external pinger, MongoDB Atlas M0, Cloudinary Free
(raw, authenticated, signed uploads, server-driven deletion). **Consequences:** occasional socket drops on Render
restarts (handled by reconnect/outbox), Cloudinary bandwidth counts against 25 credits (download-once cache),
Atlas open to `0.0.0.0/0` (strong credentials). Migration path documented in RUNBOOK.
