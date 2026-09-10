# Lightning Capsule（闪电胶囊）

无压力的跨端灵感闪电捕获：录一段话 / 打一行字 → 自动转写并归档 → 定期同步回 Obsidian。

## 三端组成（monorepo）

| 目录 | 说明 | 技术栈 |
| --- | --- | --- |
| [`worker/`](worker/) | 云端后端 API + 网页端 PWA（录音/文字录入、Whisper 转写、D1 存储、R2 音频归档） | Cloudflare Workers + D1 + R2 + Workers AI + TypeScript |
| [`wear/`](wear/) | Wear OS 手表 App（按住说话、离线队列、表盘 Complication + Wear Widget） | Kotlin + Compose for Wear OS |
| [`extension/`](extension/) | 浏览器扩展（右键选中文字一键存、弹窗录音） | Manifest V3 + 原生 JS |

## 快速体验

本项目没有对外开放的公共实例——作者自己的部署仅为个人 / 演示用途，不接受他人写入
（唯一鉴权是一个 Bearer token）。要用，请按下面「自建部署」自己搭一套（Cloudflare 免费额度基本够用）。

各端详细说明见对应目录 README。

## 自建部署

完整教程（注册账号、开通 Workers AI / R2、建 D1 与 R2、生成 token、部署、三端指向自建实例、常见坑）：

👉 [`docs/SELF-HOSTING.md`](docs/SELF-HOSTING.md)

教程里所有域名 / 邮箱都是占位符，替换成你自己的即可。

## 架构

```
[ 多端采集层 ]
  ├── PWA 网页端（手机/桌面浏览器，含离线暂存）
  ├── Wear OS 手表 App（按住说话 + 离线队列 + 表盘快捷入口）
  └── 浏览器扩展（选中文字 / 录音）
          │ HTTPS POST（音频或纯文本）
          ▼
[ Cloudflare Edge ]
  Worker API：鉴权 → 音频流式归档 R2 → Whisper 大模型转写（中文锁定）→ 元数据存 D1
          │
          ▼ 定期差分拉取（Pull 模式）
[ Obsidian 个人知识库 ]
```

## 各端开发 / 部署

```bash
# 后端 + PWA（Cloudflare Workers）
cd worker && npm run dev       # 本地开发
cd worker && npm run deploy    # 部署生产

# 手表 App（Android Studio 或命令行 gradle）
cd wear && ./gradlew assembleDebug

# 浏览器扩展（Chrome 加载已解压目录即可）
cd extension   # chrome://extensions → 开发者模式 → 加载已解压的扩展程序
```

## License

MIT（见各目录或根目录 LICENSE 文件，如存在）。
