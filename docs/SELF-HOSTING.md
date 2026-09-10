# 自建部署教程（Self-Hosting）

Lightning Capsule 没有对外开放的公共后端。要用它，你需要在自己的 Cloudflare 账号上
部署一套 Worker，然后把三端客户端（浏览器扩展 / Wear OS App / PWA 网页端）指向你自己的实例。

本教程所有域名、邮箱、资源 id 都是**占位符**，形如 `<your-subdomain>`、`your-email@example.com`、
`<your-d1-database-id>`、`<your-r2-bucket-name>`，请替换成你自己的真实值。

- 后端技术栈：Cloudflare Workers + D1（SQLite）+ R2（对象存储）+ Workers AI（Whisper 转写）
- 预计耗时：30–60 分钟
- 费用：Cloudflare 免费额度基本够个人用；**唯一可能产生费用的是 Workers AI 的 Whisper 转写**
  （约 $0.00051/音频分钟），需要在账号里绑定信用卡才能调用。纯文字捕获不花钱。

---

## A. 前置：注册账号并开通服务

1. **注册 Cloudflare**：<https://dash.cloudflare.com/sign-up>，免费版即可。

2. **安装工具**（本机）：
   ```bash
   node -v          # 需要 Node 18+（建议 20+）
   npm i -g wrangler # 或用项目里的 npx wrangler，无需全局装
   wrangler login    # 浏览器授权，把本机 CLI 关联到你的账号
   ```
   > 本项目锁定 `wrangler ^4.130`。`compatibility_date` 是 `2026-09-01` 且用了
   > `nodejs_compat`，太老的 wrangler 会报错，请用新版本。

3. **开通 Workers**：首次 `wrangler deploy` 会提示你选一个 `*.workers.dev` 子域名
   （例如 `my-capsule`，最终域名就是 `https://my-capsule.<你的账号>.workers.dev`）。
   也可以先去 dashboard → Workers & Pages 里手动设子域名。

4. **开通 Workers AI**：dashboard → AI → Workers AI，按提示启用。
   - 本项目用模型 `@cf/openai/whisper-large-v3-turbo`（中文准确率明显好于基础版 `@cf/openai/whisper`）。
   - 计费约 **$0.00051 / 音频分钟**，是本项目唯一可能扣费的项，**需要账号里已绑卡**否则调用会失败。
   - 只做纯文字捕获、不录音的话，不会触发 AI，不产生费用。

5. **开通 R2**：dashboard → R2，点 “Enable R2” / 按提示开通（需要同意条款，可能要绑卡但有 10GB 免费额度）。
   - **坑**：没先在 dashboard 开通就直接 `wrangler r2 bucket create ...` 会报
     `code 10042 "enable R2"`。先在网页开通再回来跑命令。

---

## B. 创建存储：D1 数据库 + R2 桶

在 `worker/` 目录下操作：

```bash
cd worker
npm install
```

### B.1 复制配置模板

仓库里**不含**真实资源 id。真实配置放在 `worker/wrangler.jsonc`，这个文件已被 `.gitignore` 忽略。

```bash
cp wrangler.jsonc.example wrangler.jsonc
```

### B.2 创建 D1 数据库

```bash
wrangler d1 create lightning_capsule_db
```

输出里会有一段：

```
[[d1_databases]]
binding = "DB"
database_name = "lightning_capsule_db"
database_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"   ← 复制这个
```

把 `database_id` 填进 `worker/wrangler.jsonc` 的 `d1_databases[0].database_id`
（替换掉 `<your-d1-database-id>`）。

### B.3 创建 R2 桶

```bash
wrangler r2 bucket create <your-r2-bucket-name>    # 名字自取，小写字母/数字/连字符，3–63 字符
```

把这个桶名填进 `worker/wrangler.jsonc` 的 `r2_buckets[0].bucket_name`
（替换掉 `<your-r2-bucket-name>`）。

### B.4 建表

```bash
npm run schema:remote    # 把 schema.sql 应用到远程 D1（生产用这条）
npm run schema:local     # 把 schema.sql 应用到本地 D1（本地开发用）
```

---

## C. 鉴权 token

后端**唯一**的鉴权手段是一个 Bearer token（`Authorization: Bearer <AUTH_TOKEN>`）。
没有用户系统、没有别的门禁——**谁拿到这个 token，谁就能往你的库里写数据、上传音频**。
请当成密码对待，不要提交进 git、不要发群里。

### C.1 生成

```bash
openssl rand -hex 24
```

### C.2 本地开发用

在 `worker/.dev.vars` 里写（这个文件已 gitignored，**别提交**）：

```
AUTH_TOKEN=<上一步生成的值>
```

### C.3 生产用

```bash
wrangler secret put AUTH_TOKEN
# 粘贴同一个（或另一个）值
```

`wrangler.jsonc` 里 `vars` 必须保持空对象 `{}`。
**不要**在 `vars` 里放 `AUTH_TOKEN` 占位符——它会盖掉 secret，导致线上所有请求 401。

### C.4 轮换

重新 `openssl rand -hex 24` → `wrangler secret put AUTH_TOKEN` → 重新部署 → 到三端重新填新 token。

---

## D. 部署后端

```bash
cd worker
npm run typecheck                       # 先过一遍类型检查
npx wrangler deploy --dry-run --outdir /tmp/lc-dryrun   # 可选：本地校验配置能解析
npm run deploy
```

成功后 wrangler 会打印你的地址，形如 `https://<worker名>.<你的账号子域>.workers.dev`。
下文统一把这个完整地址记作 `https://<your-subdomain>.workers.dev`——**下一节三端都要用**。

看实时日志：

```bash
wrangler tail
```

自测（把域名和 token 换成你的）：

```bash
# 纯文字
curl -X POST https://<your-subdomain>.workers.dev/api/capture \
  -H "Authorization: Bearer <AUTH_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello capsule","source":"curl"}'
# 期望 201（首次）/ 200（重复）

# 导出待同步
curl https://<your-subdomain>.workers.dev/api/export?status=pending \
  -H "Authorization: Bearer <AUTH_TOKEN>"
```

---

## E. 三端指向你的自建实例

### E.1 浏览器扩展（`extension/`）

1. 改 `extension/background.js` 顶部的 `API_URL`：
   ```js
   const API_URL = "https://<your-subdomain>.workers.dev/api/capture";
   ```
2. 同样改 `extension/popup.js` 顶部的 `API_URL`。
3. 改 `extension/manifest.json` 的 `host_permissions`（MV3 里 JSON 不能写注释，直接替换字符串）：
   ```json
   "host_permissions": ["https://<your-subdomain>.workers.dev/*"]
   ```
4. `chrome://extensions`（Edge 为 `edge://extensions`）→ 开发者模式 → **加载已解压的扩展程序** →
   选 `extension/` 目录。改过之后点该扩展的 **刷新** 按钮重新加载。
5. 点扩展图标 → 在弹窗里粘贴你的 `AUTH_TOKEN` → 保存。token 只存在 `chrome.storage.local`。

### E.2 Wear OS App（`wear/`）

1. 改 `wear/app/src/main/java/com/lightningcapsule/wear/CapsuleUploader.kt` 里的：
   ```kotlin
   const val BASE_URL = "https://<your-subdomain>.workers.dev"
   ```
2. 重新构建并侧载：
   ```bash
   cd wear
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export ANDROID_HOME=$HOME/Android/Sdk
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. 打开 App，首次进入 token 屏，粘贴 `AUTH_TOKEN`（存在 `SharedPreferences`）。
   或用 adb 注入：`adb shell am start -n com.lightningcapsule.wear/.MainActivity -e auth_token '<AUTH_TOKEN>'`
   （注意命令会进 shell history，自行 `history -c` 清理）。

### E.3 PWA 网页端（`worker/public/`）

**不用改**。PWA 前端用 `location.origin` 拼接 API 地址，你部署到哪个域名它就自动打到哪个域名。
直接访问 `https://<your-subdomain>.workers.dev`，在设置里粘贴 `AUTH_TOKEN` 即可，可“添加到主屏幕”当 App 用。

---

## F. 已知坑 / FAQ

| 现象 | 说明 / 解决 |
| --- | --- |
| `wrangler r2 bucket create` 报 `code 10042` | R2 未开通。先去 dashboard → R2 点 Enable，再重试。 |
| 本地 `wrangler dev --local` 下 `env.AI` 报 `"not supported"` | 这个 wrangler 版本不在本地代理 Workers AI。Whisper **成功路径只能连真实基础设施测**；本地能测的是超时/失败回退（音频已进 R2、返回 504 `pending_retry`）。 |
| 转写结果里句子中间是半角逗号 `,`，句末却是全角 `。！？` | 托管版 `whisper-large-v3-turbo` 的已知行为，`initial_prompt` 无法左右子句逗号宽度。项目**故意不做**事后字符串替换，保持原样。 |
| 线上请求全部 401 | 检查 `wrangler.jsonc` 的 `vars` 是不是被塞了 `AUTH_TOKEN` 占位符——它会盖掉 secret。`vars` 必须是 `{}`。然后确认 `wrangler secret put AUTH_TOKEN` 已执行、客户端填的 token 一致。 |
| Whisper 调用失败 / 扣费相关 | Workers AI 需要账号**已绑卡**才能用。没绑卡时录音上传会存进 R2 但转写失败。 |
| `wrangler deploy` 报 compatibility / nodejs_compat 相关错误 | `compatibility_date = 2026-09-01` + `nodejs_compat` 需要较新的 wrangler，升级到 `^4.130`。 |
| Wear 构建报找不到 SDK 平台 37 | `wear/app/build.gradle.kts` 里 `compileSdk` / `targetSdk` 可从 37 回退到 36，对侧载功能无影响。 |
| Wear 构建报 `local.properties` 缺失 | 在 `wear/` 下建 `local.properties`，内容 `sdk.dir=/绝对路径/Android/Sdk`；并让 `JAVA_HOME` 指向 JDK 17。 |
| 换了域名后扩展/手表还是打旧地址 | 扩展要在 `chrome://extensions` 点刷新重新加载；Wear 要重新 `assembleDebug` + `adb install -r`。别忘了扩展的 `manifest.json` `host_permissions` 也要一起改。 |

---

## 数据流回顾

```
[ 采集端：PWA / Wear / 扩展 ]
        │ HTTPS POST /api/capture  (Bearer token)
        ▼
[ 你的 Cloudflare Worker ]
  鉴权 → 音频流式写入 R2 → Whisper 转写（锁定中文）→ 元数据写 D1
        │
        ▼  GET /api/export?status=pending  →  POST /api/ack
[ 你自己的 Obsidian 同步脚本 / 客户端（自行实现，pull 模式）]
```

`/api/export` 返回待同步条目，同步完成后调 `/api/ack` 标记 `synced`。仓库不含同步脚本，
按 `worker/README.md` 的 Endpoints 表自行实现即可。
