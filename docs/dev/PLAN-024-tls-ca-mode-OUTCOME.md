# PLAN-024 结果：TLS CA 模式（rvs://）——标准 HTTPS 验证上线

- 对应计划：[PLAN-024-tls-ca-mode.md](PLAN-024-tls-ca-mode.md)
- 设计文档：[../design/tls-ca-mode-20260919-overview.md](../design/tls-ca-mode-20260919-overview.md)
- 实施时间：2026-09-19
- 依赖 commit：f066e82（PLAN-023）

## 结果摘要

**server（Go，零新依赖）**：`main.go` 新增 `-cert PATH -key PATH`——齐备时
`tls.LoadX509KeyPair` 加载外部证书（Let's Encrypt），否则维持 selfcert 自签；启动横幅
打印所载证书指纹并提示 rvs:// 模式。协议/端口/认证零改动。

**Mac（Rust）**：`config.rs` 新增 `is_strict_tls`、`parse_server` 兼容 rvs:// 前缀；
`RelayClientOptions`/`RelayClient` 增加 `strict`；`connect_once` strict 时跳过全部指纹
逻辑；`tls_config(strict)` strict 分支 = `RootCertStore`（新依赖 `webpki-roots` 0.26，
Mozilla 根 bundle 含 Let's Encrypt）+ rustls 内建主机名验证。`controller` 连接装配处
strict 时指纹置空。`cargo check` + `cargo test`（21 例，含真实 TLS 的 relay 集成测试）
本机 Linux 全过（keyinject 已 cfg 门控）。

**Android（Kotlin，零新依赖）**：`ConfigParser.Parsed/Pairing` 增加 `strict`，
parse/format/parsePairing 全链路识别并保留 `rvs://` 前缀；`RelayClient` strict 分支 =
系统默认信任 `SSLContext`（init(null,null,null)）+ 握手后
`HttpsURLConnection.getDefaultHostnameVerifier()` 主机名校验（裸 SSLSocket 不自带）；
不读写 TrustStore。设置页 hint 更新引导两种模式。

## 验证

| 用例 | 结果 |
| --- | --- |
| server `-cert/-key` 加载自建 CA 叶子证书，openssl s_client `-CAfile -verify_hostname` | ✅ Verify return code 0 |
| 同上，不提供 CA 信任（负例） | ✅ code 19 self-signed in chain（正确拒绝） |
| fakephone（rv:// TOFU）连 CA 证书 server（混连回归） | ✅ authed + registered |
| 真机 rvs:// + 公网 TLS（www.baidu.com:443） | ✅ logcat `tls connected`（CA+主机名验证通路） |
| 真机 rvs:// + 本地自签 fake relay | ✅ `Trust anchor not found` 握手失败，0 次 tls connected（不退化 trust-all） |
| 真机 rv:// + 本地自签（回归） | ✅ AUTH + 桥接同步 TALK 到达，连接稳定存活 |
| Mac cargo check / cargo test | ✅ 21 passed |
| pnpm build / assembleDebug | ✅ |

**未真机验证项（如实记录）**：
- rvs:// 配对码（扫码/手动输入）落地流程：被手机图案锁挡住（无人值守无法解锁），但其中
  `parse(rvs://)→strict→RelayClient` 链路已被用例 2 真机证明，parsePairing/format 的
  rvs:// 增量为同构纯字符串代码（编译验证）。用户首次扫码 rvs:// 时即完成实验证收。
- LE 真证书实连：代码路径与「公网 TLS 正例」相同，部署后自然验收。

## 部署 runbook（用户持域名后）

1. 域名 A 记录 → VPS IP；
2. VPS 签发：`certbot certonly --standalone -d <域名>`（80 端口瞬时可用；有 web 服务时用
   `--nginx`/`--webroot`）；
3. systemd `remote-voice-server` 的 ExecStart 追加
   `-cert /etc/letsencrypt/live/<域名>/fullchain.pem -key /etc/letsencrypt/live/<域名>/privkey.pem`，
   并加续期钩子（`certbot renew --deploy-hook 'systemctl restart remote-voice-server'`）；
4. 客户端：Mac 设置填 `rvs://<域名>:9432`（手机扫 Mac 二维码会自动带 scheme）。

## 遗留

- 无功能性遗留；Mac 端 webpki-roots 需用户下次在 Mac 构建时 cargo fetch（crates.io 可达）。
