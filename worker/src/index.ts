/**
 * Lightning Capsule — Phase 1 core API (Cloudflare Workers + D1 + R2 + Workers AI Whisper)
 *
 * AUTH (极简鉴权)
 *   Every /api/* route requires `Authorization: Bearer <AUTH_TOKEN>`.
 *   AUTH_TOKEN comes from env (local: .dev.vars, prod: `wrangler secret put AUTH_TOKEN`).
 *   Missing / malformed / wrong token -> 401 `{ "error": "unauthorized" }`.
 *   Comparison is a length-checked constant-ish string compare; no token value is ever echoed back.
 *
 * ERROR HANDLING
 *   Every handler runs inside a try/catch. Clients only ever receive a JSON body
 *   `{ "error": "<short message>" }` with an appropriate status code. Internal errors
 *   (stack traces, D1/R2/AI messages) are written to `console.error` for `wrangler tail`
 *   and NEVER placed in the HTTP response. Unknown routes -> 404 JSON.
 *
 * AUDIO PIPELINE (硬红线 #1 — 防超时 / 零丢失)
 *   1. Stream the uploaded Blob straight into R2 (never buffered fully before persistence).
 *   2. Only after R2 confirms the write do we call Whisper, guarded by AbortSignal.timeout(60000)
 *      AND a Promise.race backstop timer.
 *   3. Success -> row with status='pending'.
 *   4. Whisper timeout / failure -> row with status='pending_retry' (audio_url points at the
 *      already-safe R2 object) and HTTP 504. The Worker never throws past this point, so the
 *      audio is never lost and the capture can be retried later.
 *
 * IDEMPOTENCY (硬红线 #2)
 *   Text uploads: checksum = SHA256(content). If a row with that checksum already exists we
 *   return HTTP 200 with `{ ..., "existing": true }` (NOT 409).
 *   Rationale: the Obsidian pull-sync / PWA client re-POSTs captures after flaky networks;
 *   modelling a duplicate as a successful no-op (200 + flag) lets the client take a single
 *   code path and still receive the canonical stored record for id reconciliation, instead of
 *   having to special-case an error status.
 *
 * SYNC = PULL (硬红线 #4)
 *   GET  /api/export -> rows with status='pending' (each carries a stable `export_id`).
 *   POST /api/ack    -> look up by export_id, set status='synced' + synced_at=now.
 */

export interface Env {
  DB: D1Database;
  AUDIO_BUCKET: R2Bucket;
  AI: Ai;
  AUTH_TOKEN: string;
}

// large-v3-turbo: 显著优于默认 whisper (base/small 级)，$0.00051/min (仅贵 13%)。
// language 显式指定 "zh" 避免自动语言检测失误——中文准确率提升最大的一招。
// 关键：该模型的 `audio` 入参 schema 是 anyOf[ base64 string | {body,contentType} ]，
// 不接受默认 whisper 那种 number[] 字节数组 —— 传数组会立即 5006 Type mismatch 报错。
const WHISPER_MODEL = "@cf/openai/whisper-large-v3-turbo";
const WHISPER_LANGUAGE = "zh";
// initial_prompt 是一段“上下文前缀”，Whisper 会模仿其书写风格（简繁体 + 标点形态）。
// 放一句与测试音频无关的通用示例，并显式使用全角中文标点，诱导模型输出简体 + 标点。
// 实测（large-v3-turbo，Workers AI 托管）：该前缀能把**句末标点**稳定带成全角「。！？」，
// 但**子句之间的逗号**始终输出半角「,」—— 换过 5 种措辞（含“通篇只有全角逗号”的极端前缀）
// 均无效，说明托管版模型的逗号形态无法经 initial_prompt 控制。详见 README“Transcription notes”。
// 注意：示例内容必须与真实音频无关，否则会诱发幻觉（把示例词句写进转录结果）。
const WHISPER_INITIAL_PROMPT =
  "以下是一段普通话录音的转录文本，使用规范的全角中文标点符号，例如：逗号、句号、问号和感叹号。他说：这个方案不错！你觉得呢？我们明天再讨论。";
// task 显式设为 "transcribe"（该模型默认值），杜绝被误当成 translate。
const WHISPER_TASK = "transcribe";
// large-v3-turbo 推理明显慢于默认 whisper（冷加载 + 大模型），旧的 25s 上限实测会整体超时。
// Workers 按 CPU time 计费，等待 AI 推理不消耗 CPU → 放宽到 60s 不会增加成本。
const WHISPER_TIMEOUT_MS = 60_000;

/* ------------------------------- helpers ---------------------------------- */

function json(body: unknown, status = 200, extraHeaders?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...(extraHeaders ?? {}) },
  });
}

function errorResponse(message: string, status: number): Response {
  return json({ error: message }, status);
}

/** ArrayBuffer -> base64 string. Chunked so a multi-hundred-KB buffer can't blow the
 *  argument limit of String.fromCharCode(...). */
function arrayBufferToBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  const chunk = 0x8000; // 32 KiB per fromCharCode call
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** SHA256 -> lowercase hex. */
async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** decodeURIComponent that never throws — returns "" on malformed percent-escapes. */
function safeDecode(raw: string): string {
  try {
    return decodeURIComponent(raw).trim();
  } catch {
    return "";
  }
}

/** Bearer check. Returns true only on an exact match with env.AUTH_TOKEN. */
function isAuthorized(request: Request, env: Env): boolean {
  const header = request.headers.get("authorization") ?? "";
  const prefix = "Bearer ";
  if (!header.startsWith(prefix)) return false;
  const token = header.slice(prefix.length).trim();
  if (!token || !env.AUTH_TOKEN) return false;
  // length guard first so we don't compare against an empty/placeholder secret
  return token.length === env.AUTH_TOKEN.length && token === env.AUTH_TOKEN;
}

/** Pick a file extension for the R2 key from the upload's filename / MIME type. */
function audioExtension(file: File): string {
  const name = (file.name || "").toLowerCase();
  const dot = name.lastIndexOf(".");
  if (dot > -1 && dot < name.length - 1) return name.slice(dot + 1).replace(/[^a-z0-9]/g, "") || "bin";
  const map: Record<string, string> = {
    "audio/webm": "webm",
    "audio/ogg": "ogg",
    "audio/wav": "wav",
    "audio/x-wav": "wav",
    "audio/wave": "wav",
    "audio/mpeg": "mp3",
    "audio/mp3": "mp3",
    "audio/mp4": "m4a",
    "audio/x-m4a": "m4a",
    "audio/aac": "aac",
    "audio/flac": "flac",
  };
  return map[file.type?.toLowerCase() ?? ""] ?? "bin";
}

/**
 * Run Whisper with a hard 60s ceiling.
 * Belt-and-braces: pass an AbortSignal.timeout AND race a backstop timer, so a binding
 * that ignores the signal still cannot hang the request.
 */
async function transcribeWithTimeout(env: Env, audio: ArrayBuffer): Promise<string> {
  // whisper-large-v3-turbo 只吃 base64 字符串（或 {body,contentType}）—— 传字节数组会 5006 报错。
  const base64 = arrayBufferToBase64(audio);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const backstop = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error("whisper_timeout")), WHISPER_TIMEOUT_MS);
  });
  try {
    // run 签名按已知模型列表收窄，large-v3-turbo 不在其中 → 用显式函数签名绕过类型收窄
    const run = (
      env.AI.run as (
        model: string,
        inputs: Record<string, unknown>,
        options?: Record<string, unknown>,
      ) => Promise<{ text?: string }>
    )(
      WHISPER_MODEL,
      {
        audio: base64,
        task: WHISPER_TASK,
        language: WHISPER_LANGUAGE,
        initial_prompt: WHISPER_INITIAL_PROMPT,
      },
      { signal: AbortSignal.timeout(WHISPER_TIMEOUT_MS) },
    );
    const result = await Promise.race([run, backstop]);
    return (result?.text ?? "").trim();
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** Columns returned to the pull-sync client. Internal fields (audio_url, checksum,
 *  raw_transcript, version) are intentionally omitted. */
const EXPORT_COLUMNS = "id, export_id, content, source, tags, status, created_at, updated_at, synced_at";

/** Columns returned to the reading UI (GET /api/capsules). `audio_url` keeps its existing
 *  meaning — the raw R2 object key, not a fetchable URL. checksum / export_id / version /
 *  updated_at stay internal. */
const CAPSULE_LIST_COLUMNS =
  "id, content, raw_transcript, audio_url, source, tags, status, created_at, synced_at";

/* ------------------------------ handlers --------------------------------- */

async function handleCapture(request: Request, env: Env): Promise<Response> {
  const contentType = request.headers.get("content-type") ?? "";

  /* ---- audio upload: multipart/form-data, field name "audio" ---- */
  if (contentType.includes("multipart/form-data")) {
    let form: FormData;
    try {
      form = await request.formData();
    } catch {
      return errorResponse("invalid multipart body", 400);
    }
    const audio = form.get("audio");
    if (!(audio instanceof File) || audio.size === 0) {
      return errorResponse("missing audio field", 400);
    }
    const source = typeof form.get("source") === "string" ? (form.get("source") as string) : "web";
    const tags = typeof form.get("tags") === "string" ? (form.get("tags") as string) : null;

    const id = crypto.randomUUID();
    const exportId = crypto.randomUUID();
    const key = `capsules/${id}.${audioExtension(audio)}`;

    // (1) stream straight into R2 — audio is durable before we touch Whisper
    try {
      await env.AUDIO_BUCKET.put(key, audio.stream(), {
        httpMetadata: { contentType: audio.type || "application/octet-stream" },
      });
    } catch (err) {
      console.error("R2 put failed", err);
      return errorResponse("audio storage failed", 502);
    }

    // (2) transcribe with 60s protection; re-read the bytes from R2 (source of truth)
    let transcript: string;
    try {
      const obj = await env.AUDIO_BUCKET.get(key);
      if (!obj) throw new Error("r2 object missing after put");
      transcript = await transcribeWithTimeout(env, await obj.arrayBuffer());
    } catch (err) {
      // (4) timeout / failure -> pending_retry, 504, audio still safe in R2
      console.error("whisper failed, marking pending_retry", err);
      try {
        await env.DB.prepare(
          `INSERT INTO capsules (id, content, raw_transcript, audio_url, source, tags, status, checksum, export_id)
           VALUES (?, ?, NULL, ?, ?, ?, 'pending_retry', NULL, ?)`,
        )
          .bind(id, "[audio pending retry]", key, source, tags, exportId)
          .run();
      } catch (dbErr) {
        console.error("D1 insert (pending_retry) failed", dbErr);
        return errorResponse("audio stored but bookkeeping failed; safe to retry", 504);
      }
      return json(
        { id, status: "pending_retry", message: "transcription timeout, audio stored" },
        504,
      );
    }

    // (3) success -> pending
    const checksum = await sha256Hex(transcript);
    try {
      await env.DB.prepare(
        `INSERT INTO capsules (id, content, raw_transcript, audio_url, source, tags, status, checksum, export_id)
         VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
      )
        .bind(id, transcript, transcript, key, source, tags, checksum, exportId)
        .run();
    } catch (dbErr) {
      console.error("D1 insert (audio pending) failed", dbErr);
      return errorResponse("transcription ok but storage failed; audio safe to retry", 500);
    }
    return json(
      {
        id,
        status: "pending",
        checksum,
        audio_url: key,
        content: transcript,
        created_at: new Date().toISOString(),
      },
      201,
    );
  }

  /* ---- text upload: application/json ---- */
  if (contentType.includes("application/json")) {
    let payload: { content?: unknown; source?: unknown; tags?: unknown };
    try {
      payload = await request.json();
    } catch {
      return errorResponse("invalid JSON body", 400);
    }
    const content = typeof payload.content === "string" ? payload.content.trim() : "";
    if (!content) return errorResponse("missing content", 400);
    const source = typeof payload.source === "string" ? payload.source : "web";
    const tags = typeof payload.tags === "string" ? payload.tags : null;

    const checksum = await sha256Hex(content);

    // idempotency: same checksum -> return existing row, 200 + existing:true
    const existing = await env.DB.prepare(
      `SELECT id, status, checksum, export_id, created_at FROM capsules WHERE checksum = ? LIMIT 1`,
    )
      .bind(checksum)
      .first<{ id: string; status: string; checksum: string; export_id: string; created_at: string }>();
    if (existing) {
      return json({ ...existing, existing: true }, 200);
    }

    const id = crypto.randomUUID();
    const exportId = crypto.randomUUID();
    try {
      await env.DB.prepare(
        `INSERT INTO capsules (id, content, raw_transcript, audio_url, source, tags, status, checksum, export_id)
         VALUES (?, ?, NULL, NULL, ?, ?, 'pending', ?, ?)`,
      )
        .bind(id, content, source, tags, checksum, exportId)
        .run();
    } catch (dbErr) {
      // UNIQUE-ish race: another request inserted the same checksum between our SELECT and INSERT
      console.error("D1 insert (text) failed", dbErr);
      const raced = await env.DB.prepare(
        `SELECT id, status, checksum, export_id, created_at FROM capsules WHERE checksum = ? LIMIT 1`,
      )
        .bind(checksum)
        .first<{ id: string; status: string; checksum: string; export_id: string; created_at: string }>();
      if (raced) return json({ ...raced, existing: true }, 200);
      return errorResponse("storage failed", 500);
    }

    const row = await env.DB.prepare(
      `SELECT id, status, checksum, export_id, created_at FROM capsules WHERE id = ?`,
    )
      .bind(id)
      .first();
    return json(row ?? { id, status: "pending", checksum }, 201);
  }

  return errorResponse("unsupported content-type; use application/json or multipart/form-data", 400);
}

async function handleExport(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const status = url.searchParams.get("status") ?? "pending";
  let limit = Number.parseInt(url.searchParams.get("limit") ?? "50", 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = 50;
  if (limit > 500) limit = 500;

  const { results } = await env.DB.prepare(
    `SELECT ${EXPORT_COLUMNS} FROM capsules WHERE status = ? ORDER BY created_at ASC LIMIT ?`,
  )
    .bind(status, limit)
    .all();

  return json({ items: results ?? [], count: (results ?? []).length });
}

async function handleAck(request: Request, env: Env): Promise<Response> {
  let payload: { export_id?: unknown };
  try {
    payload = await request.json();
  } catch {
    return errorResponse("invalid JSON body", 400);
  }
  const exportId = typeof payload.export_id === "string" ? payload.export_id.trim() : "";
  if (!exportId) return errorResponse("missing export_id", 400);

  const target = await env.DB.prepare(`SELECT id FROM capsules WHERE export_id = ? LIMIT 1`)
    .bind(exportId)
    .first<{ id: string }>();
  if (!target) return errorResponse("export_id not found", 404);

  await env.DB.prepare(
    `UPDATE capsules SET status = 'synced', synced_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
     WHERE export_id = ?`,
  )
    .bind(exportId)
    .run();

  const updated = await env.DB.prepare(
    `SELECT ${EXPORT_COLUMNS} FROM capsules WHERE export_id = ?`,
  )
    .bind(exportId)
    .first();
  return json(updated, 200);
}

/**
 * GET /api/capsules — paginated list for the reading UI.
 *   limit  : default 30, hard cap 100, bad value -> default
 *   offset : default 0, bad / negative value -> 0
 *   q      : optional `content LIKE '%q%'` filter, always passed as a bound parameter
 * Sort is `created_at DESC, id DESC` for a stable order within the same second.
 */
async function handleCapsulesList(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);

  let limit = Number.parseInt(url.searchParams.get("limit") ?? "", 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = 30;
  if (limit > 100) limit = 100;

  let offset = Number.parseInt(url.searchParams.get("offset") ?? "", 10);
  if (!Number.isFinite(offset) || offset < 0) offset = 0;

  const q = (url.searchParams.get("q") ?? "").trim();
  const filter = q ? "WHERE content LIKE ?" : "";
  const like = `%${q}%`;

  const totalStmt = env.DB.prepare(`SELECT COUNT(*) AS total FROM capsules ${filter}`);
  const totalRow = await (q ? totalStmt.bind(like) : totalStmt).first<{ total: number }>();
  const total = totalRow?.total ?? 0;

  const listStmt = env.DB.prepare(
    `SELECT ${CAPSULE_LIST_COLUMNS} FROM capsules ${filter} ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`,
  );
  const { results } = await (q
    ? listStmt.bind(like, limit, offset)
    : listStmt.bind(limit, offset)
  ).all();
  const items = results ?? [];

  return json({ items, total, has_more: offset + items.length < total });
}

/**
 * DELETE /api/capsules/:id — remove one capsule row and its R2 audio object.
 * A failing R2 delete must NOT block the row delete: it is logged and the response
 * carries `audio_removed: false`.
 */
async function handleCapsuleDelete(id: string, env: Env): Promise<Response> {
  const row = await env.DB.prepare(`SELECT id, audio_url FROM capsules WHERE id = ? LIMIT 1`)
    .bind(id)
    .first<{ id: string; audio_url: string | null }>();
  if (!row) return errorResponse("not found", 404);

  let audioRemoved = false;
  if (row.audio_url) {
    try {
      await env.AUDIO_BUCKET.delete(row.audio_url);
      audioRemoved = true;
    } catch (err) {
      console.error("R2 delete failed; continuing with D1 row delete", err);
    }
  }

  try {
    await env.DB.prepare(`DELETE FROM capsules WHERE id = ?`).bind(id).run();
  } catch (dbErr) {
    console.error("D1 delete failed", dbErr);
    return errorResponse("delete failed", 500);
  }

  return json({ deleted: true, id, audio_removed: audioRemoved });
}

/**
 * GET /api/capsules/:id/audio — stream the capsule's stored audio back to an
 * authenticated client (the reading UI fetches this with the Bearer header and
 * wraps it in an object URL, since a bare <audio src> cannot send the header).
 * `audio_url` is the R2 key; its meaning is unchanged.
 */
async function handleCapsuleAudio(id: string, env: Env): Promise<Response> {
  const row = await env.DB.prepare(`SELECT audio_url FROM capsules WHERE id = ? LIMIT 1`)
    .bind(id)
    .first<{ audio_url: string | null }>();
  if (!row || !row.audio_url) return errorResponse("not found", 404);

  const obj = await env.AUDIO_BUCKET.get(row.audio_url);
  if (!obj) return errorResponse("not found", 404);

  return new Response(obj.body, {
    status: 200,
    headers: {
      "content-type": obj.httpMetadata?.contentType || "application/octet-stream",
      "cache-control": "private, max-age=3600",
    },
  });
}

/* ------------------------------- router --------------------------------- */

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    try {
      if (path.startsWith("/api/")) {
        if (!isAuthorized(request, env)) return errorResponse("unauthorized", 401);

        if (path === "/api/capture" && request.method === "POST") {
          return await handleCapture(request, env);
        }
        if (path === "/api/export" && request.method === "GET") {
          return await handleExport(request, env);
        }
        if (path === "/api/ack" && request.method === "POST") {
          return await handleAck(request, env);
        }
        if (path === "/api/capsules" && request.method === "GET") {
          return await handleCapsulesList(request, env);
        }
        if (path.startsWith("/api/capsules/")) {
          const rest = path.slice("/api/capsules/".length);
          const audioSuffix = "/audio";
          if (request.method === "GET" && rest.endsWith(audioSuffix)) {
            const audioId = safeDecode(rest.slice(0, -audioSuffix.length));
            if (!audioId) return errorResponse("bad request", 400);
            return await handleCapsuleAudio(audioId, env);
          }
          if (request.method === "DELETE" && !rest.includes("/")) {
            const delId = safeDecode(rest);
            if (!delId) return errorResponse("bad request", 400);
            return await handleCapsuleDelete(delId, env);
          }
          return errorResponse("not found", 404);
        }
        return errorResponse("not found", 404);
      }

      return errorResponse("not found", 404);
    } catch (err) {
      // last-resort guard: never leak internals, never crash the isolate
      console.error("unhandled error", err);
      return errorResponse("internal error", 500);
    }
  },
} satisfies ExportedHandler<Env>;
