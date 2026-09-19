# remote-voice

Mac 麦克风硬件损坏的替代方案：**Android 手机采音 → 中继服务器 → Mac 虚拟麦克风**。
全链路自研、完全可控：TLS 加密，按住说话（PTT），手机按下不仅出声、还联动 Mac 端模拟 Fn
触发系统语音输入。协议 v4 服务器密码 + Mac 目录：一个密码控制服务器上全部 Mac。

**用户只需三样东西：服务器地址、密码、二维码。** Mac 端设置服务器密码并出示二维码；
手机扫码（或手填）后即见服务器上的 Mac 列表（离线也可见），点连接即可控制，可同时
连接多台、左右滑动切换。证书全自动：裸地址先标准验证、自签自动回落首连信任，
服务器日后配置 Let's Encrypt 证书自动升级，永不出现证书换代死局错误；
`rvs://` 显式前缀保留为强制标准验证的逃生门。

## 构建（scripts/）

```bash
scripts/build.sh all        # 一键：当前机器上能构建的全部（Linux=server+android；Mac=全部）
scripts/build-server.sh     # 服务器二进制 → server/dist/（含 server-linux 兼容名、arm64、fakephone）
scripts/build-android.sh    # Android debug APK（--release 出未签名 release）
scripts/build-mac.sh        # ⚠️ 仅 Mac 上运行：Tauri .app/.dmg（--debug 快速包，--install 装入 /Applications）
```

## 架构（三端各自独立交付）

```
Android 手机: 按住说话(PTT)              中继服务器                     Mac
┌──────────────────┐  出站TLS  ┌──────────────────────┐  出站TLS  ┌──────────────────┐
│ android-app       │ ───────→│ server (Go)          │────────→│ mac-app / Tauri   │
│ 扫码/手动配对      │        │ 服务器密码+Mac目录路由 │         │ 收流写 BlackHole  │
│ 48k/mono/s16le    │        │ Mac 设备身份登记+桥接   │         │ +模拟 Fn 触发语音输入│
└──────────────────┘        │ 按IP限速防爆破           │        └──────────────────┘
                            └──────────────────────┘            ↑ 会议/输入法选 BlackHole 2ch
```

| 目录 | 运行端 | 语言 | 介绍 |
|---|---|---|---|
| [`server/`](server/README.md) | 公网 Linux 服务器 | Go（标准库零依赖） | 中继服务器：TLS + 服务器密码认证 + Mac 目录（LIST）+ 1:1 桥接 + 按 IP 限速防爆破；`-cert/-key` 加载 Let's Encrypt 证书；含调试工具 `cmd/fakephone` |
| [`mac-app-tauri/`](mac-app-tauri/README.md) | macOS | Rust/Tauri | 唯一受支持 Mac 客户端；首次自动生成设备身份；收流写入 BlackHole，PTT 联动模拟 Fn 触发语音输入；首页出示配对二维码 |
| [`android-app/`](android-app/README.md) | Android 手机 | Kotlin（唯一 maven 依赖 zxing core 用于扫码） | 前台服务 + 按住说话（PTT），Mac 目录一键连接、多 Mac 同时连接 + 左右滑动切换，扫码配对（扫 Mac 二维码一步完成配置+连接） |

> Mac 端早期 Python 客户端已归档至 [`backup/mac-app/`](backup/mac-app/README.md)
> （只保留 Tauri 版的决定，PLAN-013）；存储约定兼容，如需恢复 `git mv` 回即可。

- 协议 v4：`[1B type][4B len BE][payload]`；Mac 首次自动登记本机设备身份，REGISTER 上报
  设备名与**服务器密码**哈希（server 持久化）；手机认证只带服务器密码的 SHA-256 hex
  （全程无明文），AUTH 带 target（空=登录会话可查目录 LIST，非空=与指定 Mac 建 1:1 桥）；
  IP 滑窗限速（60s/5 失败 → 1m/5m/30m 递增锁）
- `FRAME_TALK=0x0A`：手机→Mac 的说话状态帧，Mac 据此模拟 Fn 触发/放下语音输入，
  帧丢失由重桥/切页时无条件同步纠偏
- 音频：48kHz/mono/s16le/20ms 帧裸 PCM（1920B），server 不解析内容
- 证书模型：客户端对裸地址**自动探测**——先标准 CA+主机名验证，自签自动回落首连信任并固定，
  服务器日后配置 Let's Encrypt 证书自动升级；`rvs://` 显式前缀=强制标准验证。
  手机同服务器只需一个密码；多台 Mac 请设相同密码（后注册覆盖，server 日志留痕）

## 快速启动

### 1. 服务器

> 逐命令详解、systemd 托管、日志速查与故障排查见 **server/[DEPLOY.md](server/DEPLOY.md)**。需要 Go ≥ 1.22。

```bash
cd server && go build -o server .
./server -addr :9432 -data ./data
# 公网部署（有域名）追加：-cert fullchain.pem -key privkey.pem
# 客户端始终只填 IP/域名:端口，证书模式自动探测，无需区分
```

### 2. Mac（唯一客户端：Tauri）

```bash
brew install blackhole-2ch                  # 虚拟声卡（2ch 已够）
brew install pnpm                           # 或 corepack enable pnpm
cd mac-app-tauri && pnpm install && pnpm run tauri build   # 开发调试用 pnpm run tauri dev
```

启动后连接设置里填一次服务器地址并设置**服务器密码**（≥12 位），首次连接自动生成本机设备
身份，不需要输入 regkey、指纹或任何 scheme 前缀。首页点「二维码」出示配对码——手机
「＋ 添加 → 扫码配对」对准即可一步完成（或手动抄密码输入）。
会议软件/输入法把麦克风选成 BlackHole 2ch；手机按住说话时 Mac 自动模拟 Fn 触发语音输入。

### 3. Android

```bash
cd android-app && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

主屏「＋ 添加」→ **扫码配对**（扫 Mac 端二维码，自动完成服务器+密码配置），或手动输入
地址+密码 → 登录后显示服务器上的 **Mac 列表**（离线也可见）→ 点一台**连接** → **按住说话**
（状态胶囊点按可停止/恢复）。可同时连接多台 Mac，**左右滑动切换**控制目标；长按已连接的
chip 可断开。

## 开发验证

```bash
scripts/build.sh all            # 三端构建（按当前 OS 自动取舍）

# 测试
cd server && go vet ./... && go test ./... -count=1 -race
cd mac-app-tauri/src-tauri && cargo test && cd .. && pnpm run build
cd android-app && ./gradlew assembleDebug
```

## 文档索引

- 交接档案（项目背景/状态/演进）：[docs/HANDOFF.md](docs/HANDOFF.md)
- 设计与计划：`docs/design/`（多 Mac 自助入网、扫码配对、TLS CA 模式等）、`docs/dev/`（计划/结果/评审，索引见 [docs/dev/INDEX.md](docs/dev/INDEX.md)）
- 部署：server/[DEPLOY.md](server/DEPLOY.md)（服务器）、android-app/[BUILD.md](android-app/BUILD.md)（Android 构建环境）
- 公网 HTTPS 部署三步（域名 A 记录 → certbot 签发 → systemd 挂 `-cert/-key`）：见 [docs/dev/PLAN-024-tls-ca-mode-OUTCOME.md](docs/dev/PLAN-024-tls-ca-mode-OUTCOME.md)「部署 runbook」

当前开放自助入网适合自托管：知道 server 地址的人可以尝试登记新 Mac。若未来服务面向互不信任的多用户，需要增加账号、邀请、配额和撤销管理。
