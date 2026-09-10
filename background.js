// Service worker: right-click "save selection" + text upload to the backend.
// The auth token is read from chrome.storage.local on demand and never logged.

const API_URL = "https://<your-subdomain>.workers.dev/api/capture";
const TOKEN_KEY = "auth_token";
const MENU_ID = "lc-save-selection";

chrome.runtime.onInstalled.addListener(() => {
  // removeAll first so re-running on extension update doesn't hit a duplicate id.
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_ID,
      title: "存入闪念胶囊",
      contexts: ["selection"],
    });
  });
});

chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId !== MENU_ID) return;
  handleSaveSelection(info.selectionText || "");
});

async function handleSaveSelection(rawText) {
  const content = rawText.trim();
  if (!content) {
    notify("请先选中文字", "右键菜单没有拿到选中的文本，请先选中一段文字。");
    return;
  }

  const token = await getToken();
  if (!token) {
    notify("未设置 Token", "点击扩展图标，在弹窗里填入访问 Token 后再试。");
    return;
  }

  try {
    const res = await fetch(API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ content, source: "extension" }),
    });

    if (res.ok) {
      notify("已存入闪念胶囊", truncate(content, 80));
    } else if (res.status === 401 || res.status === 403) {
      notify("Token 无效", "打开弹窗重新输入访问 Token。");
    } else {
      notify("保存失败", `服务器返回 ${res.status}，请稍后重试。`);
    }
  } catch (e) {
    notify("保存失败", "网络错误，请检查连接后重试。");
  }
}

function getToken() {
  return chrome.storage.local.get(TOKEN_KEY).then((r) => r[TOKEN_KEY] || "");
}

function notify(title, message) {
  chrome.notifications.create({
    type: "basic",
    iconUrl: "icons/icon128.png",
    title,
    message,
  });
}

function truncate(s, n) {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}
