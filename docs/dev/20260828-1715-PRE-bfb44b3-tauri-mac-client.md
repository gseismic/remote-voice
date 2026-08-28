# Tauri Mac 客户端预研

依赖提交：`bfb44b3143e7267e8b6696462792c2ff5c16d970`
编写时间：2026-08-28 17:15 (UTC+8)
研究问题：在保留 Python 客户端的前提下，新增 Rust/Tauri Mac 客户端是否能完整复用 v3 自助入网协议，并在当前开发机完成可重复构建与核心测试。
模型：Codex GPT-5

## 已查明

### 1. 现有协议契约

- 帧格式是 `[1B type][4B length BigEndian][payload]`，单帧上限 65536 字节；常量和读写约束见 `server/internal/protocol/protocol.go:1-140`。
- v3 Mac 首帧 AUTH 携带 `role=mac`、`proto=3`、`device_id` 和 64 位 hex `device_key`，认证成功后必须发送 REGISTER；协议结构见 `server/internal/protocol/protocol.go:70-112`。
- server 首次登记设备凭据哈希并持久化到 `data/devices.json`，在线设备 ID 不能重复占用；认证和清理逻辑见 `server/internal/server/server.go:305-344`、`server/internal/server/server.go:441-482`。
- Mac 收到手机桥接音频后要处理 AUDIO、PING/PONG、PEER_STATE 和 EVENT；Python 无 UI 会话顺序见 `mac-app/src/macapp/core.py:172-333`。

### 2. 现有 Mac 用户行为

- 首次使用自动生成设备 ID/密钥，优先 macOS Keychain，失败回落到 `~/.config/remote-voice/device.json`；实现见 `mac-app/src/macapp/secretsgen.py:152-249`。
- 临时秘密、永久秘密、设备名和 server 地址是用户可见配置；证书指纹已经是按 server 地址内部 TOFU，不应重新暴露为必填项；实现见 `mac-app/src/macapp/ui_main.py:200-373`、`mac-app/src/macapp/config_store.py:51-93`。
- 当前音频输出通过 `sounddevice` 找到名称包含 `BlackHole` 的输出设备，接收 PCM 后写入；实现见 `mac-app/src/macapp/audio.py:86-220`。
- Python GUI 的高频工作流是“查看临时秘密 → 手机输入 → 看桥接状态”，设置与历史属于次级功能；交互依据见 `docs/design/client-ui-20260826-v2.md:8-72`。

### 3. 构建环境

- 当前开发机有 Rust 1.98、Cargo 1.98、Node 22、npm 11。
- 已安装 Linux Tauri 运行时依赖 `webkit2gtk-4.1`、`gtk+-3.0`，并安装 ALSA 开发库；可以在 Linux 做 Tauri/Cargo 构建和无声卡核心测试。
- 当前没有全局 Tauri CLI；应将 `@tauri-apps/cli` 固定为项目开发依赖，避免依赖开发机全局状态。
- 当前没有实体 macOS、Keychain 或 BlackHole 环境；macOS 安装包、Keychain 实际行为和音频设备输出必须作为未覆盖验收项记录。

## 方案结论

新增 `mac-app-tauri/` 并行交付，不修改 Python 客户端目录结构。Rust 核心与 Tauri UI 分层：核心不依赖 Tauri，使用 Tokio + tokio-rustls 处理网络，`cpal` 提供音频 sink；Tauri 只负责命令、状态事件和窗口生命周期。前端使用 Vite 打包的 HTML/CSS/JavaScript，运行时只暴露用户概念，不展示 `regkey` 或证书指纹输入。

Rust 版与 Python 版共享以下存储约定以避免同一台 Mac 重复登记：

- Keychain service `remote-voice`、account `device-identity` 保存设备身份 JSON；
- Keychain service `remote-voice`、account `permanent-secret` 保存永久秘密；
- Keychain 不可用时使用 `~/.config/remote-voice/device.json`、`perm.secret` 和 `config.json`，权限分别为目录 0700、敏感文件 0600。

## 待解决问题

1. rustls 0.23 自定义证书 verifier 的 API 需要在实际编译中确认，首连接受证书、后续按叶证书 SHA-256 固定。
2. `cpal` 输出设备的采样格式可能是 f32/i16/i32，必须统一转成 48 kHz、mono PCM，并让设备找不到/设备拔出变成可恢复状态。
3. Tauri 2 Linux 构建可以验证，但 macOS bundle、签名、公证和 Keychain 只能在 macOS 验证。
4. 当前不扩展 server 协议、不引入账号/租户控制面；首次设备自助登记的开放信任窗口仍然存在。

## 证据不足项

- 尚未有 Rust/Tauri 源码，因此不能声称 Tauri 版已能连接 server。
- 当前 Python 的 26 项测试只能证明既有协议服务端行为，不能替代 Rust 帧、TLS、持久化和 UI IPC 测试。
