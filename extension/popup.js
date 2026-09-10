// Popup UI: token setup, quick text capture, push-to-talk audio capture.
// The auth token is stored only in chrome.storage.local and is never logged.

// 自建部署：把下面的域名换成你自己的 Worker 域名（wrangler deploy 后拿到），
// 并同步修改 manifest.json 的 host_permissions。见 docs/SELF-HOSTING.md 第 E 节。
const API_URL = "https://<your-subdomain>.workers.dev/api/capture";
const TOKEN_KEY = "auth_token";
const SOURCE = "extension";
const MAX_RECORD_MS = 60000;

const $ = (id) => document.getElementById(id);

const tokenView = $("token-view");
const mainView = $("main-view");
const tokenInput = $("token-input");
const tokenSave = $("token-save");
const textInput = $("text-input");
const textSave = $("text-save");
const recBtn = $("rec-btn");
const recTimer = $("rec-timer");
const changeToken = $("change-token");
const statusEl = $("status");

let recorder = null;
let recStream = null;
let recChunks = [];
let recStartedAt = 0;
let recStopTimer = null;
let recTickTimer = null;

init();

async function init() {
  const token = await getToken();
  showView(token ? "main" : "token");

  tokenSave.addEventListener("click", onSaveToken);
  tokenInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") onSaveToken();
  });

  textSave.addEventListener("click", onSaveText);
  textInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) onSaveText();
  });

  changeToken.addEventListener("click", () => {
    tokenInput.value = "";
    showView("token");
    setStatus("");
  });

  // Push-to-talk: hold to record, release to upload. Pointer capture keeps the
  // release event on the button even if the cursor drifts off while held.
  recBtn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    try {
      recBtn.setPointerCapture(e.pointerId);
    } catch (_) {}
    startRecording();
  });
  recBtn.addEventListener("pointerup", stopRecording);
  recBtn.addEventListener("pointercancel", stopRecording);
  // Fallback: if the pointer left without capture (older engines), still stop.
  recBtn.addEventListener("pointerleave", (e) => {
    if (!recBtn.hasPointerCapture || !recBtn.hasPointerCapture(e.pointerId)) {
      stopRecording();
    }
  });
}

function showView(name) {
  tokenView.hidden = name !== "token";
  mainView.hidden = name !== "main";
}

function getToken() {
  return chrome.storage.local.get(TOKEN_KEY).then((r) => r[TOKEN_KEY] || "");
}

async function onSaveToken() {
  const token = tokenInput.value.trim();
  if (!token) {
    setStatus("请粘贴访问 Token", "err");
    return;
  }
  await chrome.storage.local.set({ [TOKEN_KEY]: token });
  tokenInput.value = "";
  showView("main");
  setStatus("Token 已保存", "ok");
}

async function onSaveText() {
  const content = textInput.value.trim();
  if (!content) {
    setStatus("请输入内容", "err");
    return;
  }
  const token = await getToken();
  if (!token) {
    showView("token");
    return;
  }

  setBusy(true);
  setStatus("保存中…");
  try {
    const res = await fetch(API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ content, source: SOURCE }),
    });
    if (res.ok) {
      textInput.value = "";
      setStatus("已存入闪念胶囊", "ok");
    } else if (res.status === 401 || res.status === 403) {
      handleBadToken();
    } else {
      setStatus(`保存失败（${res.status}），请重试`, "err");
    }
  } catch (e) {
    setStatus("保存失败，请重试", "err");
  } finally {
    setBusy(false);
  }
}

async function startRecording() {
  if (recorder) return;

  const token = await getToken();
  if (!token) {
    showView("token");
    return;
  }

  try {
    recStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    setStatus("无法访问麦克风，请检查系统权限", "err");
    return;
  }

  recChunks = [];
  const mimeType = pickMimeType();
  recorder = new MediaRecorder(
    recStream,
    mimeType ? { mimeType } : undefined,
  );
  recorder.addEventListener("dataavailable", (e) => {
    if (e.data && e.data.size > 0) recChunks.push(e.data);
  });
  recorder.addEventListener("stop", onRecorderStop);
  recorder.start();

  recStartedAt = Date.now();
  recBtn.classList.add("recording");
  recBtn.textContent = "松开上传";
  setStatus("录音中…保持弹窗打开");
  updateTimer();
  recTickTimer = setInterval(updateTimer, 100);
  recStopTimer = setTimeout(stopRecording, MAX_RECORD_MS);
}

function stopRecording() {
  if (!recorder) return;
  clearTimeout(recStopTimer);
  clearInterval(recTickTimer);
  recStopTimer = null;
  recTickTimer = null;
  if (recorder.state !== "inactive") recorder.stop();
  recBtn.classList.remove("recording");
  recBtn.textContent = "按住说话";
}

async function onRecorderStop() {
  const type = (recorder && recorder.mimeType) || "audio/webm";
  const durationMs = Date.now() - recStartedAt;
  const blob = new Blob(recChunks, { type });

  if (recStream) recStream.getTracks().forEach((t) => t.stop());
  recorder = null;
  recStream = null;
  recChunks = [];

  if (durationMs < 400 || blob.size === 0) {
    setStatus("录音太短，未上传", "err");
    return;
  }
  await uploadAudio(blob, type);
}

async function uploadAudio(blob, type) {
  const token = await getToken();
  if (!token) {
    showView("token");
    return;
  }

  const form = new FormData();
  form.append("audio", blob, `capture.${extFor(type)}`);
  form.append("source", SOURCE);

  setBusy(true);
  setStatus("上传中…");
  try {
    const res = await fetch(API_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    if (res.status === 504) {
      setStatus("已保存到后端，转录待重试", "ok");
    } else if (res.ok) {
      setStatus("已存入闪念胶囊（转录完成）", "ok");
    } else if (res.status === 401 || res.status === 403) {
      handleBadToken();
    } else {
      setStatus(`上传失败（${res.status}），请重试`, "err");
    }
  } catch (e) {
    setStatus("上传失败，请重试", "err");
  } finally {
    setBusy(false);
  }
}

function pickMimeType() {
  const candidates = [
    "audio/webm;codecs=opus",
    "audio/webm",
    "audio/mp4",
    "audio/ogg;codecs=opus",
  ];
  for (const c of candidates) {
    if (
      typeof MediaRecorder !== "undefined" &&
      MediaRecorder.isTypeSupported &&
      MediaRecorder.isTypeSupported(c)
    ) {
      return c;
    }
  }
  return "";
}

function extFor(type) {
  if (type.includes("mp4")) return "mp4";
  if (type.includes("ogg")) return "ogg";
  return "webm";
}

function updateTimer() {
  const s = (Date.now() - recStartedAt) / 1000;
  recTimer.textContent = `${s.toFixed(1)}s`;
}

function handleBadToken() {
  chrome.storage.local.remove(TOKEN_KEY);
  tokenInput.value = "";
  showView("token");
  setStatus("Token 无效，请重新输入", "err");
}

function setBusy(busy) {
  textSave.disabled = busy;
  tokenSave.disabled = busy;
}

function setStatus(msg, kind) {
  statusEl.textContent = msg;
  statusEl.className = `status${kind ? ` ${kind}` : ""}`;
}
