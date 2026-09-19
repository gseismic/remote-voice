# PLAN-025 · 实施结果（OUTCOME）

- 对应计划: [PLAN-025-tls-auto-detect.md](PLAN-025-tls-auto-detect.md)
- 设计: [../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.1
- 完成时间: 2026-09-19
- 依赖 commit: f89efcc

## 结果摘要

**目标达成**：`tofu-mismatch` 从两端代码中永久消失（`grep` 为 0）；裸 `host:port`
成为默认地址形态 = Auto 模式（先标准 CA+主机名验证，仅证书验证失败回落 TOFU）；
服务器日后配置 Let's Encrypt 证书后客户端**自动升级**，无需任何用户操作。

- Mac（Rust）：`config.rs` 新增 `TlsMode{Auto,Strict,Tofu}` 与 `tls_mode()`；
  `relay.rs` 拆出 `dial_and_handshake` + `HandshakeFailure{Cert,Network,Stopped}`，
  `classify_handshake_error` 优先按 `rustls::Error::InvalidCertificate` 类型判定（文本兜底）；
  Auto 只对证书验证失败回落（网络错误不二次拨号，避免双倍超时）；pin 不符改为
  `Fatal{code:"cert-changed"}` 中文可恢复文案；Auto 下标准验证通过即放弃陈旧 pin（升级路径）；
  `controller.rs` AppState 新增 `fatal_code`（cert-changed 不进错误事件流）。
- Mac 前端：新增「服务器证书已更换」弹层，`重新信任并连接` = `clear_server_trust` + `connect`；
  地址输入 placeholder 与「清除信任」提示语全部去技术术语。
- Android：`ConfigParser.TlsMode`（裸地址语义从 TOFU 变更为 AUTO，即本计划核心）；
  `RelayClient.openTlsConnection/dialTls`（AUTO 先标准验证+主机名校验，证书类失败回落
  TOFU；握手失败路径补 `closeSslQuietly` 防泄漏）；`FingerprintTrustManager` 指纹不符抛
  专用消息（`CERT_CHANGED_MESSAGE`）→ `Listener.onCertChanged()` → 停止重连；
  主界面错误面板在证书更换态显示「重新信任并重连」（`retrustAndRestart`，companion 静态
  方法，服务销毁后仍可调用）。

## 验证

| 用例 | 结果 |
| --- | --- |
| cargo test（25 例，新增 `tls_mode_parses_three_address_forms`、`tls_auto_falls_back_to_tofu_on_self_signed`、`tls_auto_pin_mismatch_reports_cert_changed_without_tofu_word`、`tls_strict_mode_never_falls_back_to_tofu`） | ✅ |
| cert-changed 文案断言：不含 "tofu"、不含指纹 hex | ✅ |
| Android assembleDebug | ✅ |
| `grep -rn "tofu-mismatch" --include="*.rs/kt/js/go"` | ✅ 0 处 |

## 遗留

- 真机验证：用户服务器证书更换场景（自签→LE）实连复核（逻辑已被单测覆盖）。
