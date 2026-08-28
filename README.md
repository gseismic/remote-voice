# remote-voice

Mac 麦克风硬件损坏的替代方案：**Android 手机采音 → 公网 Linux 中继服务器 → Mac 虚拟麦克风**。
全链路自研、完全可控，TLS 加密 + 首次连接自动建立内部信任（TOFU），无需域名。协议 v3 设备自助入网。

## 架构（三端各自独立交付）

```
Android 手机: 按住说话(PTT)              Linux 服务器(公网IP)          Mac
┌──────────────────┐  出站TLS  ┌──────────────────────┐  出站TLS  ┌──────────────────┐
│ android-app       │ ───────→│ server (Go)          │────────→│ mac-app / Tauri   │
│ 秘密SHA-256 hex   │        │ 秘密哈希注册表路由+桥接 │         │ GUI / CLI 收流    │
│ 48k/mono/s16le    │        │ Mac 设备身份登记+桥接   │         │ 写入 BlackHole   │
└──────────────────┘        │ 按IP限速防爆破           │         └──────────────────┘
                            └──────────────────────┘            ↑ 会议软件选 BlackHole 2ch 当麦克风
```

| 目录 | 运行端 | 语言 | 介绍 |
|---|---|---|---|
| [`server/`](server/README.md) | 公网 Linux 服务器 | Go（标准库零依赖） | 中继服务器：TLS + 秘密注册表路由 + 1:1 桥接 + 按 IP 限速防爆破；含调试工具 `cmd/fakephone` |
| [`mac-app/`](mac-app/README.md) | macOS | Python（PySide6） | 可安装包：`remote-voice-gui` 图形客户端（临时/永久秘密、历史、自动注册）与 `remote-voice-recv` CLI 轻接收器 |
| [`mac-app-tauri/`](mac-app-tauri/README.md) | macOS | Rust/Tauri | 独立原生 GUI；首次自动生成设备身份，免输 regkey/指纹，使用 cpal 写入 BlackHole |
| [`android-app/`](android-app/README.md) | Android 手机 | Kotlin（零第三方依赖） | 前台服务 + 按住说话（PTT），多设备单激活，免提常开 |

- 协议 v3：`[1B type][4B len BE][payload]`；Mac 首次自动登记本机设备身份后注册秘密哈希，
  手机认证只带规范化秘密的 SHA-256 hex（全程无明文）；桥接 1:1；
  IP 滑窗限速（60s/5 失败 → 1m/5m/30m 递增锁）
- 音频：48kHz/mono/s16le/20ms 帧裸 PCM（1920B），server 不解析内容
- 安全模型：TLS 自签证书 + 客户端内部 TOFU 固定；设备凭据按 Mac 隔离并持久化；配对秘密只传 SHA-256 哈希

## 快速启动

### 1. 服务器

> 逐命令详解、systemd 托管、日志速查与故障排查见 **server/[DEPLOY.md](server/DEPLOY.md)**。需要 Go ≥ 1.22。

```bash
cd server && go build -o server .
./server -addr :9432 -data ./data
# 打印客户端可导入的 rv://<IP>:9432 地址；证书指纹仅供诊断
```

### 2. Mac

```bash
brew install blackhole-2ch                  # 虚拟声卡（2ch 已够）
pip install -e mac-app/                     # 安装 GUI+CLI（PySide6 ~200MB）
remote-voice-gui                            # 填服务器/设备名；本机身份首次连接自动创建
# 界面显示临时短码；手机 App 输入即可连入；连接记录见「查看连接历史」
```

也可以使用 Rust/Tauri GUI（macOS 上构建，详见 [`mac-app-tauri/README.md`](mac-app-tauri/README.md)）：

```bash
cd mac-app-tauri && npm install && npm run tauri dev
```

它与 Python 客户端共用本机身份和配对秘密存储，首次连接同样不需要输入 `regkey` 或证书指纹。

无桌面/脚本化用法：`remote-voice-recv --server <IP>:9432`（见 [mac-app/README.md](mac-app/README.md)）。

### 3. Android

```bash
cd android-app && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

设置页导入 `rv://…` 配置串 → 添加设备输入 Mac 显示的短码 → 返回后**自动连接** → **按住说话**（状态条点按可停止/恢复）。

## 开发验证

```bash
# server：构建 + 竞态测试
cd server && go build ./... && go vet ./... && go test ./... -count=1 -race

# mac-app：纯逻辑 + 真实 server 回环集成（无需声卡；可复现协议行为）
pip install -e '.[test]' && python3 -m pytest mac-app/tests/ -q

# android-app：构建
cd android-app && ./gradlew assembleDebug

# Tauri Mac GUI：核心测试与前端构建
cd mac-app-tauri/src-tauri && cargo test
cd .. && npm run build
```

## 文档索引

- 交接档案（项目背景/状态/演进）：[docs/HANDOFF.md](docs/HANDOFF.md)
- 设计与计划：`docs/design/`（含多 Mac 自助入网）、`docs/dev/`（计划/结果/评审）
- 部署：server/[DEPLOY.md](server/DEPLOY.md)（服务器）、android-app/[BUILD.md](android-app/BUILD.md)（Android 构建环境）

当前开放自助入网适合自托管：知道 server 地址的人可以尝试登记新 Mac。若未来服务面向互不信任的多用户，需要增加账号、邀请、配额和撤销管理。
