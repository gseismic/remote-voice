# PLAN-008 · TOFU 指纹免输 + 重连诊断可读化

- 日期: 2026-08-28
- 状态: 待实施
- 用户诉求: ① 省略指纹输入、按体验最简连接；② 重连时知道原因（Android/server 打日志、界面可见）

## 〇、设计决策（含查漏补缺）

- **D1 指纹不可省略，改为 TOFU 自动信任**：首次连接（信任指纹为空）→ 握手后从
  peer 证书算 SHA-256 并自动保存本机；此后所有连接用指纹固定校验；
  指纹变化 → 报"证书已变化"（带期望/实际前 12 位），需用户在设置里清除信任后重新信任。
  与 SSH known_hosts 同语义。安全敞口：仅在"第一次且指纹为空"的连接瞬间存在，个人自用可接受。
- **D2 若用户坚持可手动输**：rv:// `f=` 与 GUI 指纹框保留（可选）；手输指纹=固定校验（今日行为）。
- **D3 重连原因可读化**：Android RelayClient 把网络/握手/协议异常分类映射中文
  （拒连/超时/DNS/指纹不符/认证原因码），随 onState 输出"重连中(第N次)：原因"；
  保留最近一条错误于 Service.Status.errMsg，状态条与通知可见。
- **D4 server 不新增日志**：现有记录已覆盖（auth failed 带原因码/ip、tls handshake failed、
  accept 错误）；本次仅文档化"如何看 server 日志"（journalctl 示例）并确保客户端文本与
  server 日志行一一对应（排障时两处对照）。
- **D5 信任指纹存放**：Android prefs（沿用 KEY_FINGERPRINT）；Mac config.json 的
  fingerprint 字段；清除=置空再连（下次 TOFU）。

## 一、Phase A · Android（TOFU + 诊断）

- A1 ConfigParser：`f=` 可选（pattern 改 `(?:\?f=...)?$`）；parse 返回 fingerprint 可为空。
- A2 RelayClient.kt：
  - 构造新增 `trustedFingerprint: String?`（null=TOFU）；FingerprintTrustManager 保持；
    null 时用 TrustAll 并握手后取 `session.peerCertificates[0]` 的 DER SHA-256 → 回调
    `onFingerprint(fp)`；取到后更新内部 trustedFp 供后续校验（同对象重连生效）。
  - 异常分类：SocketTimeout→"连接超时（服务器未响应/防火墙拦截）"；
    UnknownHost→"域名解析失败"；ConnectException→"连接被拒绝（服务器未启动？）"；
    handshake 时对指纹比对失败→"证书指纹不符（期望xx..实际yy..）"；TLS 异常→"TLS 握手失败"。
    经 onState("重连中(第N次)")与 onFatal 输出。
- A3 AudioStreamService：fp 为空→client trusted=null；`onFingerprint` 回调存 prefs
  （KEY_FINGERPRINT）并在状态文本提示"已信任服务器证书"。Status.errMsg 记录最近错误，
  onState 文本含错误时同步更新。
- A4 SettingsActivity：指纹输入框改"服务器信任"展示：信任为空→"未信任（首次连接自动记）"；
  有值→显示"已信任 xxxxxxxx…（证书指纹）"，提供「清除信任」按钮（清空 KEY_FINGERPRINT）。
  hint_import 文案说明 f= 可省略。
- A5 MainActivity/strings：状态文本展示 errMsg（若存在），strings 增补文案。

## 二、Phase B · mac-app（TOFU）

- B1 core.py：`RelayClient(...fingerprint="")`=TOFU：connect 时 verify_mode=CERT_NONE，
  握手后算 sha256 → 首次保存进 self.fingerprint（内存），并通过回调
  `on_trusted_fingerprint(fp)` 通知上层落 config.json；已有指纹→维持校验（错=fatal）。
- B2 ui_main.py：指纹框 hint 改"留空=首次连接自动信任（推荐）"；_connect 内
  cfg["fingerprint"] 空时开始 TOFU；on_trusted_fingerprint → save_config+状态条提示；
  指纹不符 fatal 消息含期望/实际前 12 位并提示"设置中清空指纹可重新信任"。
- B3 测试：test_loopback 增 foo 用例：空指纹连接（服务器正确）→ 自动记录且 state registered；
  再断开重连（非空）check 同一 fp 校验通过；错误 fp → fatal（已有）。空指纹 + wrong server cert
  → 信任成功再断（TOFU 记录的是实际证书）——单测难做多 server，列表里仅做"空指纹成功连接+记录"。

## 三、Phase C · 文档

- C1 根/子 README：指纹描述改 "可省略（首次连接自动信任）；需要严格校验可手填 f= 指纹"。
- C2 HANDOFF 更新；server 侧文档仅补 journalctl 排障一句（DEPLOY §6.1 已有，检查即可）。
- C3 OUTCOME + INDEX。

## 四、验证口径

1. `./gradlew assembleDebug` 通过；代码 review 对照异常分类表。
2. mac-app pytest 全绿（+新 TOFU 回环用例最小集）；
   offscreen 冒烟：fp="" 时连接本地 server→自动记录→状态 registered。
3. 排障对应性：Android 界面文本 ↔ server 日志行（列对照表写入 OUTCOME）。
4. 不做：Android 侧无单测框架（以编译+review 为界，真机验证移交用户）。

## 五、自查（三重检查）

- [ ] D1 vs 安全审查：TOFU 把"指纹校验"从安装期挪到首次连接——失败模式是首次 MITM；
      已接受并记录；重信任路径（清除指纹按钮）需在"指纹不符"提示中显式引导，防用户无助。
- [ ] D3 兼容：RelayClient 现有 onState 文本契约（MainActivity/Service 按文本子串分支"推流中/
      传输中/等待"）——修改不得破坏这两类关键词（错误文本只出现于重连/连接中段落）。
- [ ] 竞态：TOFU 首次连接后立即断线重连——同实例内部 trustedFp 已更新，重连即校验，无竞争。
- [ ] ConfigParser 变更只放宽（f= 可选），旧串（含 f=）仍解析成功；port 默认行为保持 9432。
- [ ] mac-app 回环空指纹用例须以"连同一 server 两次"形式避免 TOFU 记录旁路——实现确认后写。
