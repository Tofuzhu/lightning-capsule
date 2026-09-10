# Lightning Capsule — Phase 1 core API

Edge backend for a "voice capsule" service: record voice or type text → Workers AI Whisper
transcribes → stored in D1 → later exported/synced to Obsidian (pull model).

Stack: Cloudflare Workers + D1 + R2 + Workers AI (`@cf/openai/whisper-large-v3-turbo`) + TypeScript, Wrangler 4.130.0.

Transcription notes:
- Model `@cf/openai/whisper-large-v3-turbo` — markedly better zh accuracy than the base `@cf/openai/whisper`, ~$0.00051/audio-min.
- `audio` **must** be passed as a base64 string (schema is `anyOf[string | {body,contentType}]`); the byte-array form that base `whisper` accepts fails fast with `5006 Type mismatch of '/audio'`.
- `language: "zh"` (ISO 639-1) is set explicitly so the model never mis-detects the language.
- `task: "transcribe"` is passed explicitly (it is the model default) to rule out any accidental `translate`.
- `initial_prompt` is a generic zh sentence written **with full-width punctuation** (，。！？、：), unrelated to any real audio (to avoid hallucinating its wording into the result). It biases output toward Simplified Chinese and pulls **sentence-final** punctuation to full-width 「。！？」.
- **Known limitation — half-width clause comma.** The hosted `whisper-large-v3-turbo` still emits a **half-width `,`** between clauses no matter what `initial_prompt` says (verified against 5 wordings, including a prompt that is nothing but full-width commas). Comma glyph width is not controllable through the exposed params, so `content` may read `今天下午三点,我要…开会。` — full-width period, half-width comma. Left as-is on purpose: no post-hoc string rewriting. Transcription of individual homophones (e.g. 接/借) also varies run-to-run on the hosted model.
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
