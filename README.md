# Lightning Capsule — Wear OS App

极简 Wear OS 原生 App：手表上**按住说话** → 松手自动上传到 Lightning Capsule API → 显示 “✓ 已记录”。

- 目标设备：Google Pixel Watch 4，Wear OS 7（Android 17 / API 37）
- 侧载安装，不上架 Play Store
- 后端 API（已存在，本项目不改动）：`https://<your-subdomain>.workers.dev`

---

## 功能（v1）

- 单屏：一个大圆 “按住说话” 按钮
- Press-to-talk：按住开始录音，松手自动停止并上传
- 录音上限 60s（到点自动停止并上传，防手滑）
- 上传中显示转圈；成功显示 “✓ 已记录”（1.8s 后自动回到初始态）；失败显示 “上传失败，请重试”，按住即可重录
- 首次启动若未设置 token，进入 token 输入屏（存 `SharedPreferences`，不写日志）
- 运行时申请 `RECORD_AUDIO` 权限；`INTERNET` 在 manifest 声明

### 录音 / 上传细节

| 项目 | 值 |
| --- | --- |
| 容器 / 编码 | MPEG-4 / AAC，输出 `.m4a`（Whisper 支持） |
| 采样率 / 声道 / 码率 | 16 kHz / 单声道 / 32 kbps |
| 上传 | `POST /api/capture`，`multipart/form-data` |
| 字段 | `audio`（文件，`capsule-<ts>.m4a`，content-type `audio/mp4`）、`source=wear` |
| Header | `Authorization: Bearer <token>` |
| 成功判定 | HTTP 201（新记录）或 200（重复） |
| 失败 | 其他状态码 / 网络错误 / 超时 → “上传失败，请重试” |
| 网络超时 | connect 15s，read/write/call 各 90s |

### 技术栈

Kotlin · Jetpack Compose for Wear OS（`androidx.wear.compose:compose-material3`）· OkHttp ·
单 Activity，无后台服务。

> 不使用 Wear Tiles（Wear OS 7 已 sunset）。从 App 列表启动。

---

## 功能（v2）离线队列

针对 **Pixel Watch 4 Wi-Fi 版**：手机不在身边且无 Wi-Fi 时上传会失败。v2 让失败的录音
**本地暂存**，网络恢复（蓝牙桥接或 Wi-Fi 任一）后**自动补传**，不丢录音。

### 行为

| 场景 | v1 | v2 |
| --- | --- | --- |
| 上传成功 | ✓ 已记录 | 不变，且随后继续补传队列 |
| 网络错误 / 非 2xx（401/403 除外） | “上传失败，请重试” | 存入离线队列 → “已暂存，待网络恢复后上传” |
| 401/403（token 无效） | “上传失败，请重试” | **不入队**（避免死循环）→ “token 无效” |
| 队列已满（50 条或 30MB） | — | 新录音直接丢弃 → “离线队列已满” |

### 补传时机

- App 启动 / 从后台恢复（`onStart`）→ 有队列则后台补传
- 网络恢复 → `ConnectivityManager.registerDefaultNetworkCallback` 监听，`onAvailable` 触发补传
  （需 `ACCESS_NETWORK_STATE`，已加到 manifest；回调在 `onStart` 注册、`onStop` 注销）
- 补传在 `lifecycleScope` + `Dispatchers.IO` 协程里 FIFO 逐个进行，不阻塞 UI；
  同一时刻只跑一个补传循环（`Mutex.tryLock`），遇失败即停，遇 401/403 停止并提示 token 无效

### 存储

- 录音文件：`filesDir/capsule_queue/`（内部存储，无需权限）
- 索引：单个 `capsule_queue/index.json` —— 数组，每项 `{file, createdAtMs, attempts}`（无 Room/SQLite）
- 上传成功 → 删文件 + 移除索引项；失败 → 保留并 `attempts+1`
- 容错：`index.json` 解析失败 → 视为空队列（坏文件重命名为 `index.json.corrupt`）；
  索引项指向的文件缺失 / 为空 → 跳过并从索引剔除，不崩溃
- 线程安全：`CaptureQueue` 所有公有方法 `synchronized`，读取走内存副本，写入落盘后返回

### UI

- 主界面底部状态行：`待上传 N 条`（N>0 时显示），补传中显示 `正在补传 N 条…`
- 非补传状态下状态行带一个 `立即重试` 小按钮，点按触发补传
- 上传转离线时提示 `已暂存，待网络恢复后上传`（1.8s 后自动回初始态，press-to-talk 交互不变）

### 测试

`app/src/test/.../CaptureQueueTest.kt`：入队 / 出队（FIFO）/ 计数与字节上限 / 损坏索引容错 /
索引与文件不一致容错 / `attempts` 持久化。纯本地逻辑，不依赖网络或 Android 框架
（`./gradlew testDebugUnitTest`）。

---

## 环境要求（本机构建）

已在 `~/Android/Sdk` 安装：

- JDK 17（`openjdk-17-jdk`）
- Android SDK：`platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0`
- Gradle 通过 Wrapper 自动获取（9.7.1），不需要系统 Gradle

构建脚本读取 `local.properties` 里的 `sdk.dir`（已生成，指向 `~/Android/Sdk`）。
若在别的机器构建，创建 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

并确保 `JAVA_HOME` 指向 JDK 17。

---

## 构建

```bash
cd ~/projects/lightning-capsule-wear
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk

# Debug APK
./gradlew assembleDebug

# 静态检查（可选）
./gradlew lint
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 侧载到手表（用户自行执行）

前置：手表已开启 **开发者选项 → ADB 调试 / 无线调试**，与电脑同一网络。

### 方式 A：无线 adb（推荐，Pixel Watch 无 USB 数据口）

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

# 手表: 设置 → 开发者选项 → 无线调试 → 使用配对码配对设备
adb pair <手表IP>:<配对端口>        # 输入手表上显示的 6 位配对码
adb connect <手表IP>:<调试端口>     # 通常 5555

adb devices                        # 确认手表在线
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装后在手表 App 列表找到 **Lightning Capsule** 启动。

### 方式 B：通过手机中转（Wear OS ADB over Bluetooth）

若使用 Android 手机的 “调试信息 → ADB 调试” + Wear 端 “通过蓝牙调试”，
`adb forward` 后同样用 `adb install -r ...`。

---

## 设置 AUTH_TOKEN

三选一：

1. **App 内输入**：首次启动进入 token 屏，粘贴 / 输入 Bearer token（不含 `Bearer ` 前缀），点保存。
2. **adb 注入（免手表打字）**：
   ```bash
   adb shell am start -n com.lightningcapsule.wear/.MainActivity -e auth_token '<你的TOKEN>'
   ```
   App 会把该值写入 `SharedPreferences` 并立即清除 Intent extra。
   注意：token 会短暂出现在该命令行 / shell history 里，请自行清理（`history -c` 等）。
3. 之后要更换：清除 App 数据或再次用方式 2 注入。

> token 只存在 `SharedPreferences`（`lightning_capsule_prefs` / key `auth_token`），
> 代码中不硬编码、不打印。

---

## 使用

1. 打开 App，授予麦克风权限。
2. **按住**中央大圆按钮开始说话，圆圈变红。
3. **松手** → 自动停止并上传，显示 “上传中…”。
4. 成功 → “✓ 已记录”；失败 → 提示重试，再次按住即可重录。

---

## 已知风险 / 限制（无法真机验证）

- 构建验收基于 `assembleDebug` 成功 + 代码审查；真机体验以用户侧载后反馈为准。
- **MediaRecorder 在 Wear OS 7 上的行为**（麦克风占用、AAC/MPEG-4 编码器可用性）未在真机实测。
  代码按标准 Android/Wear 实践编写：`MediaRecorder(context)` 构造、`MPEG_4 + AAC`、
  `setMaxDuration`、`OnInfoListener` 处理超时。若真机录音失败，App 会显示 “无法录音”。
- 极短按压（<0.7s）判定为 “录音太短”，不上传。
- 离线队列（v2）在真机上的网络回调时机、蓝牙桥接恢复行为未实测；核心队列逻辑有单元测试覆盖。
- 文本输入依赖 Wear OS 系统输入法；若不便打字，用上面的 adb 注入方式设置 token。
- `compileSdk`/`targetSdk` = 37（Android 17）。若目标 SDK 平台在构建机不可用，
  可在 `app/build.gradle.kts` 回退到 36（对侧载功能无影响）。
- AGP 9.x 使用 built-in Kotlin（2.2.10）；Compose Compiler 插件版本已与之对齐。

---

## 项目结构

```
lightning-capsule-wear/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml         # 版本目录
├── local.properties                  # sdk.dir（不提交）
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/lightningcapsule/wear/
        │   ├── MainActivity.kt        # 单 Activity + UI 状态机 + 补传编排
        │   ├── CaptureScreen.kt       # Compose UI（press-to-talk / token / 权限 / 队列状态行）
        │   ├── AudioRecorder.kt       # MediaRecorder 封装
        │   ├── CapsuleUploader.kt     # OkHttp multipart 上传（Success / AuthError / Failure）
        │   ├── CaptureQueue.kt        # 离线队列：文件 + index.json，线程安全，无 Android 依赖
        │   ├── NetworkMonitor.kt      # ConnectivityManager 默认网络回调
        │   └── TokenStore.kt          # SharedPreferences token 存取
        └── res/…                      # 字符串、启动图标

app/src/test/java/com/lightningcapsule/wear/
└── CaptureQueueTest.kt               # 队列核心逻辑单元测试（JUnit，纯本地）
```

## Git

本地 `git init`，不主动 push GitHub。`.gitignore` 已排除 `local.properties` / `build/` / `*.apk`。
仓库中不含任何真实 token。
