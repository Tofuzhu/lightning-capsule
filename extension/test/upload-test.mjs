/**
 * Simulates the extension's upload logic against a local echo server.
 *
 * It does NOT import the extension files (they are browser-global scripts), but
 * mirrors the exact request shape built by background.js (text) and popup.js
 * (audio), so a mismatch here means a mismatch there. No real token is used.
 *
 * Run: node test/upload-test.mjs
 */
import http from "node:http";
import assert from "node:assert/strict";

const TOKEN = "test-token-not-real";
const SOURCE = "extension";

// --- local server that inspects requests and replays a scripted status --------
let nextStatus = 201;
let lastRequest = null;

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    lastRequest = {
      method: req.method,
      url: req.url,
      auth: req.headers["authorization"],
      contentType: req.headers["content-type"] || "",
      body: Buffer.concat(chunks),
    };
    res.writeHead(nextStatus, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: nextStatus < 400 }));
  });
});

await new Promise((r) => server.listen(0, r));
const API_URL = `http://127.0.0.1:${server.address().port}/api/capture`;

// --- mirrors of the extension request builders -------------------------------
function postText(content) {
  return fetch(API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${TOKEN}`,
    },
    body: JSON.stringify({ content, source: SOURCE }),
  });
}

function postAudio(blob, filename) {
  const form = new FormData();
  form.append("audio", blob, filename);
  form.append("source", SOURCE);
  return fetch(API_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${TOKEN}` },
    body: form,
  });
}

// --- classification, copied from the extension ------------------------------
function classifyText(status) {
  if (status >= 200 && status < 300) return "ok";
  if (status === 401 || status === 403) return "bad-token";
  return "retry";
}
function classifyAudio(status) {
  if (status === 504) return "stored-pending";
  if (status >= 200 && status < 300) return "ok";
  if (status === 401 || status === 403) return "bad-token";
  return "retry";
}

let passed = 0;
const check = (name, cond) => {
  assert.ok(cond, name);
  console.log("  ok -", name);
  passed++;
};

// 1. text capture, 201
nextStatus = 201;
let res = await postText("选中的一段灵感文字");
check("text: POST method", lastRequest.method === "POST");
check("text: bearer header", lastRequest.auth === `Bearer ${TOKEN}`);
check("text: json content-type", lastRequest.contentType.includes("application/json"));
{
  const json = JSON.parse(lastRequest.body.toString("utf8"));
  check("text: content field", json.content === "选中的一段灵感文字");
  check("text: source field", json.source === "extension");
  check("text: no stray fields", Object.keys(json).sort().join() === "content,source");
}
check("text: 201 -> ok", classifyText(res.status) === "ok");

// 2. text capture, 401 -> bad token
nextStatus = 401;
res = await postText("x");
check("text: 401 -> bad-token", classifyText(res.status) === "bad-token");

// 3. text capture, 500 -> retry
nextStatus = 500;
res = await postText("x");
check("text: 500 -> retry", classifyText(res.status) === "retry");

// 4. audio capture, 201
nextStatus = 201;
const fakeAudio = new Blob([new Uint8Array(2048)], { type: "audio/webm" });
res = await postAudio(fakeAudio, "capture.webm");
check("audio: POST method", lastRequest.method === "POST");
check("audio: bearer header", lastRequest.auth === `Bearer ${TOKEN}`);
check(
  "audio: multipart content-type w/ boundary",
  /^multipart\/form-data; boundary=/.test(lastRequest.contentType),
);
{
  const raw = lastRequest.body.toString("latin1");
  check("audio: has 'audio' part", raw.includes('name="audio"'));
  check("audio: has filename", raw.includes('filename="capture.webm"'));
  check("audio: has 'source' part", raw.includes('name="source"'));
  check("audio: source value present", raw.includes("extension"));
}
check("audio: 201 -> ok", classifyAudio(res.status) === "ok");

// 5. audio capture, 504 -> stored, pending retry
nextStatus = 504;
res = await postAudio(fakeAudio, "capture.webm");
check("audio: 504 -> stored-pending", classifyAudio(res.status) === "stored-pending");

// 6. audio capture, 403 -> bad token
nextStatus = 403;
res = await postAudio(fakeAudio, "capture.webm");
check("audio: 403 -> bad-token", classifyAudio(res.status) === "bad-token");

// 7. network failure path
server.close();
let threw = false;
try {
  await postText("x");
} catch {
  threw = true;
}
check("network error is caught (fetch rejects)", threw);

console.log(`\n${passed} checks passed`);
