# PLAN-005 · 协议 v2 全量实施（relay 注册制 + Mac 图形客户端 + Android PTT）

- 日期: 2026-08-26
- 状态: 待实施
- 前置: 设计文档 docs/design/client-ui-20260826-v2.md（§二 D1/D2/D3/D4/D5/D6）、高保真稿 docs/ui-design/v2/*.html

## 〇、用户拍板（2026-08-26 问询结论）

| 决策点 | 结论 |
|---|---|
| Mac 客户端栈 | PySide6 图形窗口（与现 Python 接收器同语言；当前环境可安装可测） |
| v1 兼容性 | 直接切 v2，不留兼容；relay 与两端 App 同步升级 |
| 上线方式 | 本机产出 linux/amd64 静态二进制 + 逐条部署命令，用户自行部署 |

## 一、实现层补充决策（写计划前补洞）

| # | 决策 | 理由 |
|---|---|---|
| I1 | relay 新增 `-regkey`（长随机，仅 Mac 持有）：Mac 认证用 regkey，手机认证用秘密 | 否则任何人可伪装 Mac 注册秘密，诱导手机连入（钓鱼/音频窃听）。设计文档 D2 未覆盖此洞，必须补。手机侧 UX 不变（仍只输秘密） |
| I2 | 秘密全程只传规范化的 SHA-256 hex（两端本地计算，relay 零原文）；规范化=去空格/连字符+大写 | "relay 只存哈希"的严格实现，连内存驻留原文都避免 |
| I3 | 支持多 Mac 并发注册 + 多手机并发在线；桥接 1:1——Mac 已有桥接时新手机 auth 得 `peer-busy` | 连接历史图中有 iPhone 15 / Pixel 8 两台手机；音频混音不做，故对单台 Mac 维持 1:1 |
| I4 | 新帧：0x08 REGISTER（Mac→relay 注册/热更秘密）、0x09 EVENT（relay→Mac 认证失败事件通知） | Mac 需实时得知"谁拿我的秘密试探被拒"，连接历史"被拒绝"行才有数据源 |
| I5 | 指纹 pinning 不变：rv:// 导入保留 `f=`；升级前后 data 目录不动则证书指纹不变 | 防 MITM 基础；server/fingerprint 属"安装期配置"，不在 UI 化简范围 |
| I6 | 防爆破限速只对 `invalid-secret`/`secret-expired` 计数；`peer-offline`/`peer-busy` 不计数 | Mac 关机时手机连不上若也算失败，5 次就锁 → 显然误伤 |

## 二、协议 v2 规格（线协议，帧格式 `[1B type][4B len BE][payload]` 不变）

消息类型（新增用 0x08/0x09，其余复用 v1）：

| type | 方向 | payload | 说明 |
|---|---|---|---|
| 0x01 AUTH | C→S | JSON `{"role":"mac\|phone","proto":2,"key":"<regkey-hex>"}` 或 `{"role":"phone","proto":2,"secret":"<sha256-hex>"}` | 手机只带秘密；proto=2 |
| 0x02 AUTH_OK | S→C | 手机：`{"mac":"<设备名>"}`；Mac：`{}` | 设备名回传供手机自动命名 |
| 0x03 AUTH_ERR | S→C | UTF-8 原因，随后关连接 | 原因枚举见下 |
| 0x04 AUDIO | 双向 | 不变（1920B PCM） | |
| 0x05/0x06 | Ping/Pong | 不变 | |
| 0x07 PEER_STATE | S→C | 1B 0x01/0x00 | 语义不变，按桥接对通知 |
| 0x08 REGISTER | Mac→S | JSON `{"name":"...","perm":"<hex>"\|null,"temp":"<hex>"\|null,"temp_exp":unix\|0}` | Mac 每次连接后必须发；可随时重发实现"重新生成即热更"。每 Mac 最多 1 perm + 1 temp |
| 0x09 EVENT | S→Mac | JSON `{"event":"auth-fail","ip":"...","reason":"..."}` | 仅发给"该 secret 归属的 Mac"（桥接空闲时）；被拒绝历史数据源 |

AUTH_ERR 原因枚举：`invalid-key`（Mac 的 regkey 错）/ `invalid-secret` / `secret-expired` / `peer-offline` / `peer-busy` / `rate-limited` / `bad-request` / `unsupported-proto`。

认证与桥接规则：
1. Mac：regkey 常量时间比较 → AUTH_OK → 等 REGISTER（10s 超时，不注册无害）。
2. 注册表 key = secretHash → {macSession, name, kind(perm/temp), temp_exp}；同 hash 多 Mac 注册=后者覆盖。
3. 手机 AUTH：查表 → miss=`invalid-secret`（限速计数）；temp 且过期=`secret-expired`（计数）；Mac 离线=`peer-offline`（不计数）；Mac 已被桥接=`peer-busy`（不计数）；全过→挂桥+双方 PEER_STATE(online)+AUTH_OK`{mac:name}`。
4. 过期只在 AUTH 时判定；已建立桥接到期不断开（D2 安全细则）。
5. 任一端断开→解桥、另一端 PEER_STATE(offline)、Mac 侧回 EVENT 源通道。
6. 防爆破：以来源 IP 为键，60s 滑窗失败≥5 → 锁定 1m；锁定期内再失败→递增 5m→30m 封顶；锁定期直接 `rate-limited`；成功认证清零。全部失败/锁定落日志（含 IP）。

relay 配置变更：`-token/-tokenfile/RELAY_TOKEN` 删除，换 `-regkeyfile` + `RELAY_REGKEY`；启动横幅打印 `rv://<host>:<port>?f=<fingerprint>`（手机不再需要 token）。

## 三、分阶段任务

### Phase A relay v2
- A1 internal/protocol：AuthRequest 改为 v2 字段；新增 RegisterEvent/RegisterRequest/EventNotify 结构；常量补齐 type 0x08/0x09。
- A2 internal/server/server.go + session.go 重构：role 两槽位占位模型 → 注册表模型；限速器（IP 键、滑窗+递增锁）；桥接 1:1；Mac 注册/热更/dead 清理。
- A3 main.go：-regkeyfile/RELAY_REGKEY；横幅 v2。
- A4 cmd/fakephone：升级为 v2 手机侧（secret 参数）+ 可选 fakemac 模式（注册 + 收流统计）供冒烟。
- A5 internal/server/server_test.go 重写/扩充：坏 regkey 拒、错/过期/有效秘密三路、限速锁定（同 IP 6 连败→锁）、成功清计数、多 Mac 并存、peer-busy、离线即失效、REGISTER 热更、到期不断已建桥、Mac 断开后手机 auth 得 peer-offline、EVENT 送达归属 Mac、旧协议测试保留项（ping/超时/未知帧/TLS）。
- A6 验证：`go test ./... -count=1 -race` 全绿；循环冒烟。

### Phase B Mac 客户端（新目录 mac-client/）
- B1 protocol.py + secrets.py + history.py（纯逻辑、可 pytest）：帧编解码；秘密生成（8 位 base32 剔除 0O1lI，20 年字母表与原型一致）、规范化+SHA-256；历史 JSON（上限 200、清空、过滤器）；regkey/永久秘密经 macOS Keychain（`security` CLI，零新依赖）、失败回落文件 0600。
- B2 core.py：QThread RelaySession——TLS pinning（沿用现有算法）、AUTH/REGISTER、收流→sounddevice 写 BlackHole、重连退避、桥接状态、会话结束记历史；Qt 信号驱动 UI。
- B3 ui_main.py + ui_history.py：对接高保真稿——临时秘密卡片（短码/倒计时/4 档时长/复制/重生成/保持复选）、永久秘密卡片（设置/修改/停用/掩码）、状态条、历史窗口（全部/今日/被拒、清空）；QSS 暗色主题（琥珀+绿，对齐 v2 原型色板）。
- B4 自测：QT_QPA_PLATFORM=offscreen 实例化冒烟 + 对本地测试 relay 的回环集成（Core 以 mac 身份连上→注册→假手机 socket 桥上收 1920B 帧）。
- B5 mac-receiver/receiver.py 同步升级为 v2（它也要能跑）：auth 改 {role:mac,key:regkey,proto:2}+REGISTER 由 test_demo 或简单固定 secret。

### Phase C Android v2
- C1 RelayClient.kt：AUTH 载荷改 secret=sha256(规范化秘密)，发帧类型 0x08? 不——Android 无 REGISTER；解析 AUTH_OK mac 名、AUTH_ERR 新原因。
- C2 多设备存储：prefs JSON `[{id,name,secret,type}]` + `active_id`；首次连接成功以回传 mac 名自动命名（开放问题②的落地）；设置页增删改。
- C3 MainActivity + 新布局：状态条（LED+文本+切换）、设备芯片单激活、底部锚定 PTT 大按钮（按住=startTalking 松开=stopTalking，边沿抖动防护）；「免提常开」保留开关（默认关=仅 PTT）。
- C4 AudioStreamService：需支持外部即时启停采音（加 companion 实例引用 + startTalking/stopTalking）；流仅在 peerOnline 且 (talking 或 handsfree) 时发送。
- C5 验证：assembleDebug 通过；真机联调留给用户（本机无 adb 设备）。
- C6 移除 v1 token 痕迹（token key 删除、rv:// 另文案）。

### Phase D 文档与交付
- D1 README.md / android-app/BUILD.md / relay/DEPLOY.md 更新为 v2 流程（regkey、rv:// 无 token、PTT 交互）。
- D2 产出 relay-linux（静态、linux/amd64）并给出部署命令清单（保留 data 目录防指纹漂移、新 regkey.txt、systemd 单元改 -regkeyfile、重启、日志速查）。
- D3 OUTCOME + INDEX + HANDOFF 更新；分阶段 commit + push。

## 四、自查（计划三重检查）

- [x] 与设计文档一致性：D2 注册制 ✓、D4 限速+递增锁 ✓、D6 PTT 底部 ✓、"到期不断已建桥" ✓；唯一偏离（I1 regkey 引入）已注明理由且不改手机侧 UX。
- [x] 安全审查：纯哈希传输+I2；常量时间比较延续；限速只在错误秘密计数（I6 防误伤）；regkey 由文件/环境注入防 ps 泄露（沿 v1 token 设计）。
- [x] 破坏性变更面：relay 与 fakephone/receiver/android 需同步换新——用户已同意不留兼容；指纹不变（data 目录不动）。
- [x] 测试面：单测覆盖表驱动+回环集成含 TLS；PySide6 GUI 逻辑与 Qt 分离（core 可无 GUI 测试）；Android 以 assembleDebug+现有五态断言为界，真机联调移交用户。
- [x] 交付物：linux/amd64 二进制、部署命令、三端代码、文档四件套（README/BUILD/DEPLOY/交接）。
- [x] 风险：PySide6 安装体积大（近 200MB）——只开发机/使用机安装，不提交依赖实体即不入库；本机 headless 用 offscreen 渲染验证。

## 五、验收口径

1. `cd relay && go test ./... -count=1 -race` 全绿（新增 ≥10 个 v2 用例）。
2. mac-client 回环集成脚本：本地起 relay → Core 以 mac 连接注册 → 假手机认证桥接 → 1920B 帧双向贯通 → 断开后手机再连得 peer-offline。
3. 高保真稿关键交互均在真实客户端复现：PTT 按住即传/松开即停且按钮贴底；临时短码+倒计时；历史含被拒行。
4. `go build` 产出 relay-linux 静态二进制；部署命令逐条可执行。
5. Android `./gradlew assembleDebug` 通过；App 内无 v1 token 残留文案。
