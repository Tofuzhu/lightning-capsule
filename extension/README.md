# Lightning Capsule 浏览器扩展

桌面浏览器里的灵感快捕（Chrome / Edge，Manifest V3，无构建步骤）：

- **右键菜单存文字** —— 选中网页文字 → 右键 → “存入闪念胶囊”
- **弹窗快捷入口** —— 点扩展图标：快速打字存 / 按住说话录音存（上限 60 秒）

数据发往后端 `POST https://<your-subdomain>.workers.dev/api/capture`
（`source: "extension"`）。

## 安装（加载已解压的扩展）

1. 打开 `chrome://extensions`（Edge 为 `edge://extensions`）
2. 右上角打开 **开发者模式**
3. 点 **加载已解压的扩展程序**，选择本目录 `lightning-capsule-extension/`
4. 首次会提示需要“麦克风”权限（录音功能所需）

图标已随仓库提供。若要重新生成：`node scripts/gen-icons.js`

## 使用

### 设置 Token（首次）

点扩展图标 → 在弹窗里粘贴访问 Token → **保存 Token**。
Token 只存于本机 `chrome.storage.local`（key: `auth_token`），不写日志、不上传到别处。
若后端返回 401/403，弹窗会清掉旧 Token 并要求重新输入。

### 存文字

- **右键菜单**：选中文字 → 右键 → “存入闪念胶囊”，成功/失败以系统通知提示。
  未选中文字或未设置 Token 时也会通知提示。
- **弹窗**：在文本框输入 → **存入文字**（或 `Ctrl/Cmd + Enter`）。

### 录音存

在弹窗里 **按住** “按住说话” 按钮说话，**松开** 即上传。

- 录音在弹窗页面内进行，**关闭弹窗会中断录音**，请保持弹窗打开。
- 上限 60 秒，到时自动停止并上传。
- 后端返回 `201` 表示转录完成；返回 `504` 表示音频已安全存到后端、转录稍后重试
  （弹窗提示“已保存到后端，转录待重试”）。
- 网络错误/其他失败 → 提示“上传失败，请重试”（v1 不做离线队列）。

## 文件结构

```
manifest.json        # MV3 清单：contextMenus / storage / notifications / audioCapture
background.js         # service worker：右键菜单注册 + 文字上传（JSON）
popup.html/css/js     # 弹窗 UI：Token 设置、打字存、按住说话录音存（multipart）
icons/               # 16/32/48/128 png（深色圆角 + 黄色闪电，与 PWA 同款）
scripts/gen-icons.js # 一次性图标生成器（无图像依赖）
test/upload-test.mjs # 用本地 echo server 模拟验证上传请求形状（node test/upload-test.mjs）
```

## 开发自测

```
node scripts/gen-icons.js     # 重新生成图标
node test/upload-test.mjs     # 校验 JSON / multipart 请求形状与状态码分类
```

测试不使用真实 Token（用临时值 `test-token-not-real`）。

## 约束（v1 极简）

- 不改后端 Worker
- 纯原生 JS，无框架 / 无构建工具
- 不做离线队列、不做 badge 计数、不做独立选项页（Token 在弹窗里设）
