# PLAN-025 · TLS 证书模式自动探测——消灭 tofu-mismatch

- 设计：[../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.1
- 依赖 commit：f89efcc
- 范围：mac-app（Rust+前端）、android-app；服务器零改动

## 目标

1. 裸 `host:port` = auto 模式：先标准 CA+主机名验证，仅当证书验证失败才回落 TOFU；
   服务器日后配置 Let's Encrypt 证书后自动升级为标准验证。
2. 全仓清除 `tofu-mismatch`：指纹不符不再是断连死局，改为可恢复错误
   「服务器证书已更换」+ UI 一键「重新信任并连接」。
3. UI/文案不再出现 rvs://、TOFU、指纹等术语（`rvs://`/`rv://` 前缀仍解析，作为兼容逃生门）。

## 改动清单

### Mac（src-tauri）

- config.rs：`is_strict_tls` 旁新增 `TlsMode { Auto, Strict, Tofu }` 与 `tls_mode(raw)`：
  rvs://→Strict、rv://→Tofu、其余→Auto。
- relay.rs：
  - `RelayClientOptions.strict: bool` → `tls_mode: TlsMode`。
  - `connect_once`：拆出 `dial_and_handshake(strict: bool)`（拨号+握手，auto 回落需重新拨号）。
    - Strict：仅标准验证（现状）。
    - Tofu：仅 TOFU+pin（现状，mismatch 文案改友好）。
    - Auto：先 strict 握手；错误为**证书验证失败**（`TlsError::InvalidCertificate(_)` 或
      包含它的 cause 链）→ 重新拨号走 TOFU 分支；其他错误（超时/拒连）不回落。
  - TOFU pin mismatch → `RelayFailure::Fatal { code: "cert-changed", message: 中文可恢复文案 }`，
    message 不含指纹 hex、不含 "tofu" 字样。
  - Strict 成功且本地有 pin（auto 升级场景）→ 忽略/清除 pin（tofu_store 不再写）。
- controller.rs：按 `tls_mode` 装配；`fingerprint` 仅 Tofu 模式传入。
- 前端（web/app.js + index.html）：
  - fatal 事件 `code == "cert-changed"` 时渲染对话框「服务器证书已更换，可能是你换了证书或
    服务器重装。如果这是你本人的操作，点此重新信任」+ 按钮 → `clear_server_trust` → `connect`。
  - 服务器地址输入 placeholder 改「例如 192.168.1.10 或 voice.example.com」；
    移除提示中的 rv:// 字样（grep 清理）。

### Android

- ConfigParser：`Parsed.strict: Boolean` → `mode: TlsMode`（枚举 Auto/Strict/Tofu），
  裸地址=Auto（现状是 Tofu，语义变更即本计划核心）；rvs://→Strict；rv://→Tofu。
- RelayClient：
  - 构造参数同步；auto 时先默认 SSLContext 握手，`SSLHandshakeException` 且 cause 链为
    证书信任类（Trust anchor / certificate / validator 关键字）→ 关闭后重拨走 TOFU 分支。
  - TOFU 指纹不符：不再 `FatalProtocolError("证书指纹不符…")`，新增
    `Listener.onCertChanged()`；runForever 捕获后 onCertChanged + 停止重连。
- AudioStreamService：实现 onCertChanged → ERROR 态文案「服务器证书已更换，请在设备上选择
  重新信任」；新增公开 `retrustAndRestart()`：清 TrustStore 该服务器 pin + ACTION_RESTART。
- MainActivity：ERROR 态且 lastError 为证书更换时显示「重新信任并重连」按钮。
- 文案清理：SettingsActivity hint 去 scheme 术语。

## 测试

- Mac cargo test：
  - 既有 TOFU 用例改 auto 模式后应全绿（自签 relay → strict 失败回落 TOFU）。
  - 新增：auto + 自签首连 → TOFU 记录 + 注册成功；换证书后 → Fatal code=="cert-changed"
    （断言不含 "tofu"）；rvs:// 显式前缀 → 不回落、直接握手失败（Network 而非 Fatal）。
  - 新增：config tls_mode 解析三形态。
- Android：`./gradlew assembleDebug`；ConfigParser 若无测试基建则编译验证 + 手测项写入 OUTCOME。
- grep 断言：`grep -rn "tofu-mismatch" --include="*.rs" --include="*.kt" --include="*.js"` 为空
  （文档中允许保留历史 PLAN 引用）。

## 自查

1. auto 回落仅限证书错误：网络层失败不重复拨号（避免双倍超时）。
2. Strict 模式永不回落（rvs:// 逃生门语义保持，负例测试锁定）。
3. cert-changed 后 runForever 必须 break（防错误风暴重连循环）。
4. Android TOFU 分支复用既有 TrustAll+FingerprintTrustManager，onPeerFingerprint 回调语义不变。

## 实施说明（2026-09-19）

- Mac：`config::TlsMode`（Auto/Strict/Tofu）+ `relay::dial_and_handshake`（拨号/握手拆分，
  auto 仅对 `InvalidCertificate` 类错误回落，`classify_handshake_error` 类型判定+文本兜底）；
  pin 不符 → `Fatal{code:"cert-changed"}`（文案无技术术语），前端 `cert-overlay` 弹层
  「重新信任并连接」= `clear_server_trust` + `connect`；AppState 新增 `fatal_code`。
- Android：`ConfigParser.TlsMode`；`RelayClient.openTlsConnection/dialTls`（AUTO 先标准验证，
  证书类失败回落 TOFU）；`FingerprintTrustManager` 指纹不符抛专用消息 → `onCertChanged` →
  服务停连，主界面 ERROR 态显示「重新信任并重连」（`AudioStreamService.retrustAndRestart`）。
- 验证：cargo test 25 例（含 auto 回落/cert-changed 文案/strict 负例 3 个新用例）、
  assembleDebug、`grep tofu-mismatch` 代码清零。
