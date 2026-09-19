# remote-voice

Mac 麦克风硬件损坏的替代方案：**Android 手机采音 → 中继服务器 → Mac 虚拟麦克风**。
全链路自研、完全可控：TLS 加密，按住说话（PTT），手机按下不仅出声、还联动 Mac 端模拟 Fn
触发系统语音输入。协议 v3 设备自助入网，支持多台 Mac 多设备管理。

两种信任模式（按部署场景选）：

| 配置写法 | 信任方式 | 适用场景 |
|---|---|---|
| `rv://IP:端口` 或裸 `IP:端口` | 自签证书 + 首次连接固定指纹（TOFU） | **无需域名**，本地联测/内网 |
| `rvs://域名:端口` | 标准 TLS：系统 CA 链 + 主机名验证（Let's Encrypt） | **公网部署推荐**，无指纹无 mismatch |

## 架构（三端各自独立交付）

```
Android 手机: 按住说话(PTT)              中继服务器                     Mac
┌──────────────────┐  出站TLS  ┌──────────────────────┐  出站TLS  ┌──────────────────┐
│ android-app       │ ───────→│ server (Go)          │────────→│ mac-app / Tauri   │
│ 扫码/手动配对      │        │ 秘密哈希注册表路由+桥接 │         │ 收流写 BlackHole  │
│ 48k/mono/s16le    │        │ Mac 设备身份登记+桥接   │         │ +模拟 Fn 触发语音输入│
└──────────────────┘        │ 按IP限速防爆破           │        └──────────────────┘
                            └──────────────────────┘            ↑ 会议/输入法选 BlackHole 2ch
```

| 目录 | 运行端 | 语言 | 介绍 |
|---|---|---|---|
| [`server/`](server/README.md) | 公网 Linux 服务器 | Go（标准库零依赖） | 中继服务器：TLS + 秘密注册表路由 + 1:1 桥接 + 按 IP 限速防爆破；`-cert/-key` 加载 Let's Encrypt 证书；含调试工具 `cmd/fakephone` |
| [`mac-app-tauri/`](mac-app-tauri/README.md) | macOS | Rust/Tauri | 唯一受支持 Mac 客户端；首次自动生成设备身份；收流写入 BlackHole，PTT 联动模拟 Fn 触发语音输入；首页出示配对二维码 |
| [`android-app/`](android-app/README.md) | Android 手机 | Kotlin（唯一 maven 依赖 zxing core 用于扫码） | 前台服务 + 按住说话（PTT），多设备单激活，扫码配对（扫 Mac 二维码一步完成配置+连接） |

> Mac 端早期 Python 客户端已归档至 [`backup/mac-app/`](backup/mac-app/README.md)
> （只保留 Tauri 版的决定，PLAN-013）；存储约定兼容，如需恢复 `git mv` 回即可。

- 协议 v3：`[1B type][4B len BE][payload]`；Mac 首次自动登记本机设备身份后注册秘密哈希，
  手机认证只带规范化秘密的 SHA-256 hex（全程无明文）；桥接 1:1；
  IP 滑窗限速（60s/5 失败 → 1m/5m/30m 递增锁）
- `FRAME_TALK=0x0A`（v3.1 增补）：手机→Mac 的说话状态帧，Mac 据此模拟 Fn 触发/放下语音输入，
  帧丢失由重桥时无条件同步纠偏
- 音频：48kHz/mono/s16le/20ms 帧裸 PCM（1920B），server 不解析内容
- 安全模型：`rv://` 自签证书 + 客户端内部 TOFU 固定；`rvs://` 标准 CA 验证（服务器加载
  Let's Encrypt 证书）。配对秘密只传 SHA-256 哈希；设备凭据按 Mac 隔离并持久化

## 快速启动

### 1. 服务器

> 逐命令详解、systemd 托管、日志速查与故障排查见 **server/[DEPLOY.md](server/DEPLOY.md)**。需要 Go ≥ 1.22。

```bash
cd server && go build -o server .
./server -addr :9432 -data ./data
# 打印客户端可导入的 rv://<IP>:9432 地址；证书指纹仅供诊断
# 公网部署（有域名）追加：-cert fullchain.pem -key privkey.pem，客户端改填 rvs://域名:9432
```

### 2. Mac（唯一客户端：Tauri）

```bash
brew install blackhole-2ch                  # 虚拟声卡（2ch 已够）
brew install pnpm                           # 或 corepack enable pnpm
cd mac-app-tauri && pnpm install && pnpm run tauri build   # 开发调试用 pnpm run tauri dev
```

启动后连接设置里填一次服务器地址（`rv://IP:9432` 或 `rvs://域名:9432`，可选设备名），首次
连接自动生成本机设备身份，**不需要输入 regkey 或证书指纹**。首页显示临时配对密码——手机
「＋ 添加 → 扫码配对」对准 Mac 的**二维码**即可一步完成；也可以手动抄密码输入。
会议软件/输入法把麦克风选成 BlackHole 2ch；手机按住说话时 Mac 自动模拟 Fn 触发语音输入。

### 3. Android

```bash
cd android-app && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

主屏「＋ 添加」→ **扫码配对**（扫 Mac 端二维码，自动完成服务器+密码配置并连接），或手动输入
Mac 显示的短码 → 返回后**自动连接** → **按住说话**（状态胶囊点按可停止/恢复）。
设备芯片长按可改名/改密码/删除。

## 开发验证

```bash
# server：构建 + 竞态测试
cd server && go build ./... && go vet ./... && go test ./... -count=1 -race

# android-app：构建
cd android-app && ./gradlew assembleDebug

# Tauri Mac GUI：核心测试与前端构建（包管理器统一用 pnpm）
cd mac-app-tauri/src-tauri && cargo test
cd .. && pnpm run build
```

## 文档索引

- 交接档案（项目背景/状态/演进）：[docs/HANDOFF.md](docs/HANDOFF.md)
- 设计与计划：`docs/design/`（多 Mac 自助入网、扫码配对、TLS CA 模式等）、`docs/dev/`（计划/结果/评审，索引见 [docs/dev/INDEX.md](docs/dev/INDEX.md)）
- 部署：server/[DEPLOY.md](server/DEPLOY.md)（服务器）、android-app/[BUILD.md](android-app/BUILD.md)（Android 构建环境）
- 公网 HTTPS 部署三步（域名 A 记录 → certbot 签发 → systemd 挂 `-cert/-key`）：见 [docs/dev/PLAN-024-tls-ca-mode-OUTCOME.md](docs/dev/PLAN-024-tls-ca-mode-OUTCOME.md)「部署 runbook」

当前开放自助入网适合自托管：知道 server 地址的人可以尝试登记新 Mac。若未来服务面向互不信任的多用户，需要增加账号、邀请、配额和撤销管理。
