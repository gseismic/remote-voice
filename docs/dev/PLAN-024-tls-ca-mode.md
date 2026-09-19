# PLAN-024: TLS CA 模式（rvs://）——server 加载真证书，客户端标准 HTTPS 验证

- 依赖 commit：f066e82（PLAN-023）
- 设计文档：docs/design/tls-ca-mode-20260919-overview.md（先读，含方案比较与自查）
- 编写时间：2026-09-19
- 背景：用户拥有域名，公网部署走 Let's Encrypt。本计划让 server 能加载真证书、
  客户端对 `rvs://` 配置走标准 CA+主机名验证；`rv://`（TOFU）行为不变，并存。

## 实施步骤

### A. server（Go）

1. `cmd/server/main.go`：新增 flag `-cert PATH -key PATH`；两者齐备 →
   `tls.LoadX509KeyPair` 传入 server 配置（现走 internal/selfcert 自签的分支保持不变）。
2. 验证：本机生成 CA+叶子证书（SAN 含测试主机名），起 server 后
   `openssl s_client -connect 127.0.0.1:9432 -CAfile ca.pem -verify_hostname <测试主机名>`
   返回 verify ok；不带证书链时 verify 失败（负例）；fakephone TOFU 回归。

### B. Android

1. `ConfigParser`：`Parsed` 增加 `strict: Boolean = false`（`rvs://` 前缀识别）；
   `format` 按 strict 输出 `rvs://host:port` 或 `host:port`（IPv6 加括号规则不变）；
   `parsePairing` 识别两种 scheme 并把 strict 带进 `Pairing`。
2. `RelayClient`：构造参数加 `strict`；`connectOne` strict 分支——
   默认信任 SSLContext + 握手后 HostnameVerifier 校验主机名；不读写 TrustStore、
   不回调 onPeerFingerprint；非 strict 分支一行不动。
   `AudioStreamService.startStreaming` 传 `server.strict`。
3. 构建通过后真机验证矩阵（假 relay 19432 + adb reverse）：
   - `rvs://cloudflare.com:443` → logcat `tls connected`（CA+主机名验证通路）；
   - `rvs://127.0.0.1:19432`（自签）→ 握手失败（不退化 trust-all）；
   - `rv://127.0.0.1:19432` → 正常连接（回归，应复现 PLAN-023 的已就绪态）。

### C. Mac

1. `Cargo.toml` 增 `webpki-roots = "0.26"`。
2. `config.rs`：parse_server 识别 `rvs://`（strict 标记透传到连接层）。
3. `relay.rs`：`RelayClient` 增 `strict`；connect_once strict → 标准 rustls 验证
   （RootCertStore + webpki_roots），跳过指纹逻辑；非 strict 一行不动。
4. `PATH=~/.cargo/bin:$PATH cargo check`（本机 Linux 门控可行）+ `cargo test`。

### D. 文档与提交

- HANDOFF：关键技术决策速查补 rvs:// 语义 + 部署 runbook（certbot/systemd）；
- `PLAN-024-tls-ca-mode-OUTCOME.md`、INDEX 追加；commit 中文含文件路径，push。

## 风险与回滚

- Mac 端 webpki-roots 需在 Mac 上 cargo fetch（crates.io 可达，项目此前可正常构建）；
  本机 Linux cargo check 已可拦截编译错误，残余风险低。
- rvs:// 连到自签 server 会握手失败——预期语义，文案描述错误原因（describeError 已覆盖
  SSLHandshakeException 文案）。
- 全部改动非破坏性：rv:// 路径零改动，老配置/老配对码完全兼容。
