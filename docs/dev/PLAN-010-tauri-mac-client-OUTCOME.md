# PLAN-010 实施结果：Rust/Tauri Mac 客户端

日期：2026-08-28
计划：[PLAN-010-tauri-mac-client.md](PLAN-010-tauri-mac-client.md)
设计：[../design/tauri-mac-client-20260828-overview.md](../design/tauri-mac-client-20260828-overview.md)
评审：[20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md](20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md)
基线提交：`bfb44b3143e7267e8b6696462792c2ff5c16d970`

## 1. 用户目标与实际结果

用户的真实目标是：新 Mac 打开客户端后只提供服务器地址即可接入，不再手工输入全局 `regkey`、设备密钥或证书指纹；每台 Mac 自动拥有独立身份，并能在同一 relay 上与其它 Mac 并存。该目标已实现为新的 `mac-app-tauri/` Rust/Tauri GUI，同时保留原有 Python `mac-app/` 客户端。

## 2. 已完成实现

### 2.1 Tauri 工程与 GUI

- 新增 Tauri 2 + Vite 工程，前端通过 `app-state` 完整快照事件与 Rust 控制器通信。
- 主窗口提供连接状态、临时配对秘密、永久秘密、服务器/设备名、音频输出、事件日志和连接控制。
- 正常界面不显示 `device_id`、`device_key`、regkey 或证书指纹。
- 提供 Linux 开发构建和 macOS 构建命令；macOS 签名/公证由发布环境负责。

### 2.2 身份、秘密与 TOFU

- 首次连接自动生成 16 字节设备 ID 和 32 字节设备凭据；优先写 macOS Keychain，失败回落 `~/.config/remote-voice/device.json`。
- 与 Python 客户端共用 `remote-voice / device-identity`、`remote-voice / permanent-secret` 和本地文件格式，避免同一台 Mac 切换客户端时重复登记。
- 临时秘密支持 10 分钟、1 小时、8 小时、24 小时；永久秘密支持 Keychain/0600 文件回落。
- 证书按 `host:port` 做内部 TOFU；增加明确确认的 `clear_server_trust` 命令，证书轮换后可恢复连接。

### 2.3 Relay 与音频

- Rust 核心实现 v3 帧协议、AUTH/REGISTER、TLS、AUTH_OK 门闩、PING/PONG、PEER_STATE、EVENT、音频接收和指数退避重连。
- `cpal` sink 支持 48 kHz、单声道 PCM 输入，适配 i16/i32/f32 输出和 1/2 声道设备；队列有界，设备故障只影响音频状态，不杀死网络线程。
- 停止、重连、析构和设置/秘密热更新均通过生命周期同步，避免旧连接事件覆盖新连接或启动多个后台 relay 线程。
- 启动时恢复最近连接历史；历史仅保存设备、类型、IP、时长、字节数和结果等元数据。

### 2.4 文档与兼容

- 新增 `mac-app-tauri/README.md`。
- 更新根 `README.md`、`docs/HANDOFF.md`、Tauri 设计文档和 `docs/dev/INDEX.md`。
- server v3 多 Mac 自助入网、Python Mac、Android 和旧 v2 regkey 兼容路径保持不变。
- 明确记录开放自助入网的首次登记窗口和 TOFU 首次信任窗口；当前设计适合自托管，不宣称是完整多租户 SaaS。

## 3. Review 修复记录

1. 修复 Tauri 重启后历史记录不恢复：启动加载 `history.json`，新增回归测试。
2. 增加按当前服务器清除 TOFU 的显式危险操作，前端确认、后端按地址隔离，活动连接会重新连接。
3. 为连接、断开、设置重连、秘密热更新和信任重置增加生命周期串行锁。
4. 增加 `AppController` 析构清理，确保 relay 线程和音频输出不会在窗口销毁后遗留。

详细评审见 [20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md](20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md)。

## 4. 验证结果

| 范围 | 命令/结果 |
|---|---|
| Rust 格式、lint、测试 | `cargo fmt -- --check`、`cargo clippy --all-targets -- -D warnings`、`cargo test`：17 项通过 |
| 前端 | `npm run build`、`node --check web/app.js`：通过 |
| Tauri Linux bundle | `npm run tauri build -- --no-sign`：`.deb`、`.rpm`、`.AppImage` 生成 |
| Go server | `go build ./...`、`go vet ./...`、`go test ./... -count=1 -race`：通过 |
| Python Mac | `python3 -m pytest tests/ -q`：26 项通过；编译检查通过 |
| Android | `./gradlew assembleDebug`：通过，有 compileSdk/AGP 提示 |
| 工作树 | `git diff --check`：通过 |

另已完成一次进程级回环冒烟：Go server、Tauri release 客户端和 fakephone 完成 v3 `authenticated`、`registered`、`bridged`，fakephone 发送约 199 帧；核心测试同时覆盖真实 TCP/TLS、TOFU 和音频帧接收。

## 5. 未完成验收

- 无实体 macOS 环境，尚未验证 Keychain 弹窗/权限、cpal CoreAudio、BlackHole 实际收音、睡眠/锁屏重连。
- 尚未完成 macOS 签名、公证、DMG/应用升级和多台实体 Mac 的公网联调。
- Android 麦克风权限、前台服务、PTT 手感和长时间推流仍需真实设备验收。
- 若未来服务面向互不信任的多用户，需要新增账号、邀请、配额、管理员审核、撤销和审计控制面。

## 6. 交付结论

PLAN-010 的代码、核心自动化验证和文档已完成；用户当前可以在 macOS 构建并运行 Tauri GUI，首次使用无需输入 regkey 或证书指纹。发布前必须在 macOS 真机执行第 5 节验收，不应把 Linux bundle 视为 macOS 发布认证。
