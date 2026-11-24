# remote-voice

Mac 麦克风硬件损坏的替代方案：**Android 手机采音 → 中继服务器 → Mac 虚拟麦克风**。
全链路自研、完全可控：TLS 加密，按住说话（PTT），手机按下不仅出声、还联动 Mac 端模拟 Fn
触发系统语音输入。协议 v4 服务器密码 + Mac 目录：一个密码控制服务器上全部 Mac。

**只在 Mac 设置一次服务器地址和密码。** Mac 连接 [`https://voice.gpt60.com`](https://voice.gpt60.com) 后出示二维码，手机扫码会自动连接这台 Mac，显示「已就绪」后即可按住说话。手动添加中继可查看目录、连接多台 Mac 并左右滑动切换。

## 使用前

1. 在 Mac 打开 `/Applications/Remote Voice.app`，确认状态为「等待手机」并保持应用运行。已配对的 Android 会在 Mac 上线后自动连接。目标 Mac 暂时被占用时，Android 会显示「等待 Mac 空闲」。关闭「断线自动重连」后，在 Mac 或网络恢复时点按状态栏重试。
2. Fn 联动默认开启。手机按住说话时 Mac 按下 Fn，松开时释放。首页提示缺少权限时，点「请求授权」，按 macOS 的提示允许 **Remote Voice**。「打开辅助功能」会打开对应的系统设置，并在 Finder 定位当前安装包。手动打开时，macOS 12 的路径为「系统偏好设置 → 安全性与隐私 → 隐私 → 辅助功能」，较新版本为「系统设置 → 隐私与安全性 → 辅助功能」。更新后已勾选但仍有提示时，用「－」移除旧条目，再用「＋」添加并勾选当前安装包。建议将旧版备份存成压缩包，避免系统把授权写给旧副本。
3. 在 Android 看到「已就绪」后按住说话。需要调整 Fn 联动方式时，打开 Mac 的「连接设置」。使用虚拟麦克风的应用应将输入选为 BlackHole 2ch。

## 构建（scripts/）

```bash
scripts/build.sh all        # 一键：当前机器上能构建的全部（Linux=server+android；Mac=全部）
scripts/build-server.sh     # 服务器二进制 → server/dist/（含 server-linux 兼容名、arm64、fakephone）
scripts/build-android.sh    # Android debug APK（--release 出未签名 release）
scripts/build-mac.sh        # ⚠️ 仅 Mac 上运行：Tauri .app/.dmg（默认构建后装入 /Applications；--debug 快速包；--no-install 只构建）
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

启动后在连接设置填写 `https://voice.gpt60.com` 并设置**服务器密码**（≥12 位）。公网中继使用已登记的 Mac 身份，新增 Mac 可参考[登记流程](deploy/voice.gpt60.com/README.md#登记-mac)。连接成功后在首页点「二维码」。
会议软件/输入法把麦克风选成 BlackHole 2ch；手机按住说话时 Mac 自动模拟 Fn 触发语音输入。

### 3. Android

```bash
cd android-app && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

主屏显示「未配对」时点状态栏，对准 Mac 上的二维码。看到「已就绪」后**按住说话**，松开即停止传输。也可在「＋ 添加」手动输入地址和密码，再从目录选择 Mac。已连接多台时可左右滑动切换，长按设备可断开。

## 开发验证

在 Mac 上独立验证 Fn 发送、接收和正式应用联动，见 [Fn 最小测试](labs/fn/README.md)。

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
- 公网域名、HTTPS、证书续期和 Mac 登记见 [voice.gpt60.com 部署](deploy/voice.gpt60.com/README.md)。

公网 HTTPS 入口仅接受已登记的 Mac。原生 TLS 入口支持自助登记，生产配置将该入口绑定到服务器回环地址。
