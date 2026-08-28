# Tauri Mac 客户端代码评审

评审对象：基于 `bfb44b3143e7267e8b6696462792c2ff5c16d970` 的 PLAN-010 未提交实现。
评审时间：2026-08-28 19:00 (UTC+8)
评审范围：`mac-app-tauri/`、与其共享协议/身份语义的 `server/`、`mac-app/`、`android-app/`。
依据：`docs/design/tauri-mac-client-20260828-overview.md`、`docs/design/multi-mac-self-enrollment-20260828-overview.md`、`docs/dev/PLAN-010-tauri-mac-client.md`。

## 1. 评审发现

### H-1：Tauri 重启后丢失连接历史（已修复）

- 原因：原 `AppController::new()` 只从配置构造空的 `AppState.events`，没有读取已有 `history.json`；历史虽写入成功，重启后 UI 却显示为空。
- 证据：`mac-app-tauri/src-tauri/src/controller.rs:134-135` 现在调用 `load_history_events()`；读取实现位于 `mac-app-tauri/src-tauri/src/controller.rs:654-686`。
- 修复：启动时读取最多 50 条历史，映射为 UI 事件；损坏历史只降级为空列表，不阻断客户端连接。新增 `history_is_restored_as_recent_ui_events` 回归测试。

### H-2：TOFU 指纹变化后缺少可用的恢复路径（已修复）

- 原因：证书变化正确进入 fatal，但旧 UI 没有清除当前信任记录的操作，用户只能手工删除配置文件。
- 证据：校验仍在 `mac-app-tauri/src-tauri/src/relay.rs:279-287`；当前服务器记录删除实现位于 `mac-app-tauri/src-tauri/src/config.rs:242-253`，Tauri 命令位于 `mac-app-tauri/src-tauri/src/controller.rs:434-464`，UI 确认按钮位于 `mac-app-tauri/web/index.html:81-88` 和 `mac-app-tauri/web/app.js:298-306`。
- 修复：增加 `clear_server_trust`。前端先要求用户确认；后端只删除当前 `host:port` 的记录，不影响其它服务器。连接中执行时先停止旧连接，再重新建立信任。

### H-3：连接生命周期命令可能并发启动多条 relay 线程（已修复）

- 原因：重复点击连接、保存设置触发重连、修改秘密和信任重置可以同时操作 `client/thread`，存在旧连接事件覆盖新状态或后台线程残留的风险。
- 证据：当前 `AppController` 使用 `lifecycle_lock`（`mac-app-tauri/src-tauri/src/controller.rs:87-97`）；连接路径在 `:162-170`，保存设置/热更新路径在 `:280-290`、`:340-370`，信任重置路径在 `:434-464`。
- 修复：连接、断开、保存设置后的重连、临时秘密重生成、永久秘密设置/停用、TOFU 重置统一经过生命周期锁；内部 `connect_inner()` 避免重复加锁。控制器析构时也会关闭连接线程（`mac-app-tauri/src-tauri/src/controller.rs:648-652`）。

### M-1：开放自助入网仍有首次登记窗口（已接受，属于设计边界）

- 行为：任意知道 relay 地址的客户端都可以尝试用新随机 `device_id/device_key` 首次登记，server 将凭据哈希写入 `data/devices.json`。
- 证据：`server/internal/server/server.go:306-344` 在首次见到设备 ID 时登记；设计取舍记录于 `docs/design/multi-mac-self-enrollment-20260828-overview.md:20-38`。
- 判断：这是用户要求“任何 Mac 可连接、Mac 不输入 regkey、首次自动生成”的直接代价，不是本次实现遗漏。当前适合自托管和小规模使用；面向互不信任的多用户时必须增加邀请、账号、配额和撤销/审核控制面。

### M-2：TOFU 首次连接仍存在理论中间人窗口（已接受，需向用户说明）

- 行为：首次连接使用自签证书完成 TLS，随后按服务器地址保存叶证书 SHA-256；后续证书变化会拒绝。
- 证据：`mac-app-tauri/src-tauri/src/relay.rs:279-301`；Python/Android 也采用同一语义，分别见 `mac-app/src/macapp/core.py:105-127`、`android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt:136-155`。
- 判断：去掉手工指纹输入不会自动消除首次信任问题。当前以简化用户操作为优先，并在 README/交接文档中明确；有正式域名和 CA 时可切换系统 CA。

## 2. 接口与安全检查

- 用户接口只暴露服务器地址、设备名、音频设备和配对秘密；`device_id`、`device_key`、regkey 和指纹不进入正常表单，符合用户目标。
- Rust relay 严格执行 `AUTH -> AUTH_OK -> REGISTER`，见 `mac-app-tauri/src-tauri/src/relay.rs:324-368`；设备凭据只在核心内部使用，完整 UI 状态不包含设备密钥。
- 设备身份优先 Keychain、失败回落 0700/0600 文件；配置、永久秘密和历史均采用原子写或显式失败路径，见 `mac-app-tauri/src-tauri/src/identity.rs`、`config.rs`、`secrets.rs`、`history.rs`。
- relay 读任务、写路径、停止信号、心跳和有界音频队列均有明确生命周期；网络断线重连，认证/TOFU 错误 fatal，用户断开会关闭 socket、音频和线程。
- `clear_server_trust` 是破坏本地信任状态的操作，已在 UI 层确认，并且后端按服务器地址隔离，避免误清其它 relay。

## 3. 剩余风险与验收缺口

- 当前开发环境不是 macOS，无法验证真实 Keychain CLI、CoreAudio/cpal、BlackHole 输出、睡眠/锁屏重连、签名、公证和升级安装。
- Linux Tauri bundle 只能证明工程可构建，不能证明 macOS bundle 可发布。
- 开放自助入网和 TOFU 首次信任风险属于当前产品边界；公网部署仍应限制防火墙来源、保护 `data/` 目录并保留服务端证书与设备文件。
- 连接历史只保存连接元数据，不保存音频和秘密原文；历史损坏时启动降级，但新写入会提示保存失败。

## 4. 验证结果

- `mac-app-tauri/src-tauri`：`cargo fmt -- --check`、`cargo clippy --all-targets -- -D warnings`、`cargo test`，17 项通过。
- `mac-app-tauri`：`npm run build`、`node --check web/app.js`，通过。
- Tauri Linux：`npm run tauri build -- --no-sign`，`.deb`、`.rpm`、`.AppImage` 均生成。
- `server`：`go build ./...`、`go vet ./...`、`go test ./... -count=1 -race`，通过。
- `mac-app`：`python3 -m pytest tests/ -q`，26 项通过；Python 编译检查通过。
- `android-app`：`./gradlew assembleDebug`，通过；仅有 compileSdk/AGP 兼容性提示。
- 仓库：`git diff --check`，通过。

## 5. 结论

发现的三个实现问题均已修复。当前代码满足“多台 Mac、首次自动生成身份、Mac 正常流程不输入 regkey、指纹内部 TOFU、Android 只输入配对秘密”的目标；剩余事项主要是 macOS 真机/公网验收和未来多租户控制面，不应在本次通过增加隐式密码输入来解决。
