# TLS CA 模式（rvs://，标准 HTTPS 验证）设计 — 20260919

- 状态：定稿（2 轮自查见文末）
- 关联计划：docs/dev/PLAN-024-tls-ca-mode.md
- 背景：用户拥有域名，计划公网部署时使用 Let's Encrypt 证书。现状 TOFU（自签证书 +
  首连固定指纹）在本地开发期（数据目录重建换证书）反复报 mismatch。

## 1. 目标与非目标

**目标**：
1. server 支持加载外部 PEM 证书（Let's Encrypt），不再只会自签；
2. 客户端新增 `rvs://` 配置：标准 TLS——系统 CA 验证 + 主机名校验，**无指纹、无 TOFU**；
3. 现有 `rv://`（TOFU）行为完全不变，两者并存：公网域名走 rvs://，IP/局域网联测走 rv://。

**非目标**：不做 ACME 自动续期（certbot 手动签发 + 续期即可，runbook 见 PLAN-024）；
不改认证协议；不删除 TOFU。

## 2. 方案比较（如何让客户端进入「标准验证」）

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| A：新 scheme `rvs://`（**选定**） | 显式无歧义；与 rv:// 平行扩展，归一化/format/server_key 各加一个前缀分支；用户可控 | 两端 parser/format 各加少量代码 |
| B：按 host 自动判断（IP→TOFU，域名→CA） | 用户零输入 | 隐式行为切换易踩坑（`quant.local` 是域名但永远无 CA 证书；用户填错即莫名失败） |
| C：query 参数 `?tls=ca` | 不加 scheme | 地址归一化/format/server_key 全链路要处理 query，易漏；复制粘贴易丢失 |

## 3. 语义定义

- `rv://host[:port]`：现状不变——自签兼容，首连 TOFU 记指纹，之后固定校验。
- `rvs://host[:port]`：标准 TLS——系统 CA 信任链 + 主机名（SNI）校验；**不读不写指纹**。
- 两者都支持 `?s=&n=` 配对码 query（parsePairing），语义随 scheme。
- 混连允许：rvs:// 客户端连到自签 server → 握手失败（预期行为，提示去用 rv:// 或换证书）；
  rv:// 客户端连到 LE 证书 server → 正常 TOFU。

## 4. 各端改动

### 4.1 server（Go，零新依赖）

`cmd/server` 增 flag `-cert PATH -key PATH`（PEM）；两者齐备则 `tls.LoadX509KeyPair`
加载，否则维持 `internal/selfcert` 自签。协议/端口/认证不变。

### 4.2 Mac（Rust）

- `config.rs`：parse_server 识别 `rvs://` 前缀（复用现有 strip 逻辑），返回是否 strict；
  server_key 保持 host:port 形态（strict 不落指纹，无碰撞问题）。
- `relay.rs`：`RelayClient` 增加 `strict`；`connect_once` 按 strict 分支：
  - strict → `ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()`
    （roots 来自新依赖 `webpki-roots` 0.26 的 Mozilla 根 bundle；rustls 自带主机名校验，
    ServerName 沿用现有构造）；跳过指纹比对/TOFU 记录/`onPeerFingerprint`；
  - 非 strict → 现行 TofuVerifier 路径，一行不动。
- 依赖修订：新增 `webpki-roots`（静态 Mozilla 根，含 Let's Encrypt；不选
  rustls-native-certs 是因为其引 macOS security-framework 依赖链，对个人部署无增益）。
- 本机 `cargo check` 可编译验证（keyinject 已 cfg 门控，Linux 下可全量检查）。

### 4.3 Android（Kotlin，零新依赖）

- `ConfigParser.Parsed` 增加 `strict: Boolean = false`；parse 识别 `rvs://`；`format` 保留
  scheme（rvs://host:port / host:port）；`parsePairing` 识别两种 scheme 并透传 strict。
- `RelayClient` 构造增加 strict：
  - strict → `SSLContext.getInstance("TLS")` 默认初始化（init(null,null,null)＝系统 CA），
    握手后用 `HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)`
    补主机名校验（裸 SSLSocket 不自带）；不读不写 TrustStore；
  - 非 strict → 现行 TOFU 路径不动。
- UI 不变：设置页/配对码都天然支持（地址里带 rvs:// 前缀即切换）。

## 5. 验证矩阵

| 用例 | 期望 | 环境 |
| --- | --- | --- |
| server `-cert/-key` 加载自建 CA 签的叶子证书 | `openssl s_client -CAfile -verify_hostname` 通过；fakephone 照常 | 本机 |
| rvs:// + 真实公网 TLS（cloudflare.com:443） | logcat `tls connected`（CA+主机名验证路径通） | 真机 |
| rvs:// + 本地自签 fake relay | 握手失败，不退化为信任任意证书 | 真机 |
| rv:// + 本地自签 fake relay | 正常 TOFU 连接（回归） | 真机 |
| Mac cargo check / relay 单测 | 通过 | 本机 Linux |

真机 LE 证书实连留待部署后（代码路径与用例 2 相同，残差仅证书链内容）。

## 6. 部署 runbook（域名在手时）

1. 域名 A 记录 → VPS IP（118.193.40.160）；
2. VPS：certbot 签发（`certbot certonly --standalone -d <域名>`，需 80 端口瞬时可用；
   或 --nginx/webroot 复用现有 80）；
3. systemd 单元 `remote-voice-server` 加 `-cert /etc/letsencrypt/live/<域名>/fullchain.pem
   -key .../privkey.pem`，续期用 `ExecStartPost`/deploy hook 重启服务；
4. 客户端：Mac 设置/手机设置填 `rvs://<域名>:9432`（或扫 Mac 出的二维码，scheme 随 Mac
   配置自动带出）。

## 7. 自查记录（2 轮）

- 第 1 轮：Android 裸 SSLSocket **不做主机名校验**，仅系统 CA 验证不够——strict 分支必须
  补 HostnameVerifier（已入 §4.3）。
- 第 1 轮：strict 模式绝不能落到 TrustAllTrustManager 分支（现逻辑是「无指纹→TrustAll」），
  RelayClient 分支必须在 SSLContext 构造处分开（已入 §4.3）。
- 第 2 轮：`format()` 必须保留 rvs:// 前缀，否则设置页保存一次 strict 地址就退化成 rv://
  （已入 §4.3）。
- 第 2 轮：QR 载荷 scheme 随 Mac 配置走：Mac 配 rvs:// 则二维码自动是 rvs://，手机
  parsePairing 透传 strict，用户无感知（已入 §4.2/§4.3）。
- 第 2 轮：webpki-roots 是静态根 bundle，公共 CA 覆盖 Let's Encrypt 足够；企业自建 CA
  场景不适用（当前无此需求，记为已知限制）。
