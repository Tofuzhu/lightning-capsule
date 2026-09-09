# Lightning Capsule — Phase 1 core API

Edge backend for a "voice capsule" service: record voice or type text → Workers AI Whisper
transcribes → stored in D1 → later exported/synced to Obsidian (pull model).

Stack: Cloudflare Workers + D1 + R2 + Workers AI (`@cf/openai/whisper-large-v3-turbo`) + TypeScript, Wrangler 4.130.0.

Transcription notes:
- Model `@cf/openai/whisper-large-v3-turbo` — markedly better zh accuracy than the base `@cf/openai/whisper`, ~$0.00051/audio-min.
- `audio` **must** be passed as a base64 string (schema is `anyOf[string | {body,contentType}]`); the byte-array form that base `whisper` accepts fails fast with `5006 Type mismatch of '/audio'`.
- `language: "zh"` (ISO 639-1) is set explicitly so the model never mis-detects the language.
- `initial_prompt` is a short zh sentence to bias output toward Simplified Chinese + punctuation.
- Whisper guard raised 25s → 60s: turbo's cold-load + larger model needs the headroom, and waiting on AI inference burns no CPU time so there is no cost impact.

## Endpoints (all require `Authorization: Bearer <AUTH_TOKEN>`)

| Method | Path | Body | Notes |
| --- | --- | --- | --- |
| POST | `/api/capture` | `application/json` `{content, source?, tags?}` | text; SHA256 idempotency → 201 new / 200 `existing:true` |
| POST | `/api/capture` | `multipart/form-data` field `audio` | stream→R2, then Whisper (60s guard); 201 ok / 504 `pending_retry` |
| GET  | `/api/export?status=pending&limit=50` | – | `{items, count}`, no `audio_url`/`checksum` in items |
| POST | `/api/ack` | `{export_id}` | sets `status='synced'`, `synced_at=now`; 404 if unknown |

Unknown routes / bad token → JSON `{error}` with 404 / 401. Errors never leak internals.

## Local dev

```sh
npm install
npm run schema:local          # apply schema.sql to the local D1
echo "AUTH_TOKEN=$(openssl rand -hex 24)" > .dev.vars   # already present in this checkout
npm run dev                    # wrangler dev on :8787 (local D1 + local R2)
```

Note: in `wrangler dev --local`, `env.AI` reports **"not supported"** (this Wrangler version does not
proxy Workers AI locally), so the Whisper *success* path only runs against real infra. The
timeout/failure fallback (`pending_retry` + 504, audio already in R2) is fully exercised locally.

## Production deploy (acceptance party)

1. **Enable R2** in the Cloudflare dashboard for your own Cloudflare account
   (`wrangler r2 bucket create <your-r2-bucket-name>` currently returns code 10042 "enable R2").
2. `npm run schema:remote` (already applied once to DB `<your-d1-database-id>`).
3. `wrangler secret put AUTH_TOKEN` — overrides the `dev-placeholder` var in `wrangler.jsonc`.
4. `npm run deploy`.

`.dev.vars` holds the local dev token and is gitignored — never commit it.
