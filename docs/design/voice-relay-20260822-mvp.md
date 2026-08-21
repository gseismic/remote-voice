# voice-relay-20260822-mvp

| 项 | 内容 |
|---|---|
| 日期 | 2026-08-22 |
| 状态 | 定稿（已经过 3 轮查漏补缺） |
| 关联目标 | GOAL.md：Mac 麦克风硬件损坏，用 Android 手机替代麦克风输入 |
| 关联计划 | docs/dev/PLAN-001-voice-relay-mvp.md |

## 1. 背景与目标

Mac 麦克风硬件损坏。目标：Android 手机采音，经公网中转服务器实时传至 Mac，
写入 BlackHole 虚拟声卡，使任意 Mac 应用（会议软件等）可将该链路当作麦克风使用。

约束（用户已确认的决策）：

- 全部自研，完全可控，不用现成 App（WO Mic 等）
- 必须脱离 USB/WiFi 直连，走公网中继（用户提供一台有公网 IP 的 Linux 服务器）
- 中转服务器用 Go（用户认可，部署零依赖）；Mac 接收器用 Python（用户主栈）
- 不使用域名：TLS 采用自签证书 + 证书指纹固定（pinning）
- MVP 用 TCP；UDP/Opus 低延迟优化留作 v2

## 2. 方案对比与取舍

### 2.1 端到端连通性

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| P2P 打洞 | 无服务器流量成本 | 双 NAT/对称 NAT 成功率低且不可控；两端 IP 变动需信令 | ❌ 弃 |
| WebRTC (P2P+TURN) | 成熟的打洞/加密/抗丢包 | 引入 SDP/ICE/DTLS/SRTP 全栈，黑盒多，违背"完全可控" | ❌ 弃 |
| **公网中继（自研）** | 两端均为出站连接，穿透任意 NAT；逻辑数百行，全程可控 | 服务器承担流量；延迟多一跳 | ✅ **定稿** |

### 2.2 传输协议

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **TCP + 自定义帧** | 可靠有序、实现极简、背压天然流控 | 丢包时队头阻塞引起延迟抖动 | ✅ **MVP 定稿** |
| UDP + Opus + FEC | 延迟低且稳、带宽省 20 倍 | 需自处理乱序/丢包/抖动缓冲 | ⏳ v2 演进（协议已预留 type 字段扩展） |

依据：境内单程 RTT 通常 20–50ms，TCP 小帧（20ms/帧）+ 关闭 Nagle 后端到端约
100–150ms，满足会议场景；瓶颈是物理距离而非协议。

### 2.3 传输通道加密（不使用域名的前提下）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 明文 TCP + 仅 token 认证 | 最简单 | 语音隐私数据公网明文传输，被动窃听可行 | ❌ 弃 |
| Let's Encrypt + 域名 | 标准 CA 信任链 | 用户不想依赖域名（解析/续期/备案负担） | ❌ 弃 |
| **自签证书 + 证书指纹固定** | 无域名无 CA 无续期；防中间人；两端校验逻辑各 ~20 行 | 指纹需首次手动分发（本场景：本人复制粘贴，信任链成立） | ✅ **定稿** |

> Pinning 细节：对**整张自签证书的 DER 编码做 SHA-256**（证书级 pinning），而非
> SPKI pinning。理由：证书由本项目工具生成、有效期 10 年、不存在轮换密钥不换证书
> 的场景，证书级 pinning 使三端（Go/Python/Kotlin）都能仅用标准库完成校验，
> 免去第三方 crypto 依赖。SPKI pinning 列为可选增强，不在 MVP。

### 2.4 组件语言

| 组件 | 语言 | 理由 |
|---|---|---|
| relay 中转服务器 | Go | 两条连接互拷字节流，goroutine 模型天然匹配；交叉编译单二进制零依赖部署 |
| mac-receiver | Python 3 (+sounddevice) | 用户主栈；sounddevice(PortAudio) 写指定设备成熟稳定 |
| android-app | Kotlin | Android 官方推荐；协程简化长连接管理 |

## 3. 总体架构

```
Android 手机(任意网络)         Linux 服务器(公网IP)          Mac(任意网络)
┌────────────────┐  出站 TLS  ┌────────────────────┐ 出站 TLS ┌────────────────┐
│ android-app     │ ─────────→│ relay (Go)          │←───────── │ mac-receiver    │
│ AudioRecord 48k │  AUTH+流   │ · token 认证         │ AUTH+流   │ TLS+pinning 校验 │
│ mono s16le      │ ←─────────│ · role 配对(phone/mac)│──────────→│ 收流→写入        │
│ 20ms 帧发送     │            │ · 双向桥接转发        │           │ BlackHole 2ch   │
│ 心跳/自动重连    │            │ · 心跳超时/断线通知    │           │ (输出端=环回输入端)│
└────────────────┘            └────────────────────┘           └────────────────┘
                                                                    ↑ 会议软件选中
                                                                      BlackHole 2ch
```

关键不变式（invariant）：

1. relay 是**哑管道**：只认帧头、只做转发，不理解也不修改 AUDIO payload
2. 两端永远主动出站连接 relay；relay 不主动发起任何对外连接
3. 一切媒体参数（采样率/声道/帧长）由两端约定，relay 不感知

## 4. 线路协议规范（v1）

### 4.1 帧格式

所有帧统一为：`[1B type][4B length(BigEndian)][payload(length 字节)]`

| type | 名称 | 方向 | payload |
|---|---|---|---|
| 0x01 | AUTH | C→S | JSON: `{"role":"phone"\|"mac","token":"...","proto":1}` |
| 0x02 | AUTH_OK | S→C | 空（保留） |
| 0x03 | AUTH_ERR | S→C | UTF-8 错误描述，随后服务器关闭连接 |
| 0x04 | AUDIO | 桥接后双向透传 | 原始 PCM（s16le / 48kHz / mono / 20ms = 1920B）|
| 0x05 | PING | 双向 | 空 |
| 0x06 | PONG | 双向 | 空 |
| 0x07 | PEER_STATE | S→C | 1 字节：0x01=对端已上线 0x00=对端已掉线 |

约束：

- `length` 上限 **65536**，超限立即断开（防恶意巨帧）
- 收到未知 type：**丢弃该帧继续运行**（向前兼容，v2 新帧类型不打断 v1 客户端）
- `AUDIO` 在配对完成前到达：服务器直接丢弃

### 4.2 连接生命周期（服务器视角状态机）

```
accepted ──10s内收到AUTH──→ auth_check ──token/proto正确──→ registered(role独占)
    │                           │                              │
    └─超时→ close               └─失败→ AUTH_ERR → close       ├─role已被占用→ AUTH_ERR("role in use") → close
                                                                │
                          两端都 registered ──→ BRIDGED（双向透传 AUDIO，各自维持心跳）
                                                                │
                          任一端断开 ──→ 向在线端发 PEER_STATE(0x00)，回到 registered 等重连
```

- 同一 role 重复接入：拒绝新连接（保守策略，避免抢线导致的音频串流）
- 服务器对每个连接启用 `TCP_NODELAY`

### 4.3 心跳与超时

- 客户端每 **10s** 发 PING，收到 PONG 刷新活性；服务器每收到任意帧刷新活性
- 任一侧 **40s** 未读到任何帧 → 判定半开连接，主动断开
- NAT 保活由 10s 周期的 PING 自然覆盖（蜂窝网 NAT 表项典型超时 ≥60s）

### 4.4 客户端重连策略（两端一致）

- 指数退避：1s → 2s → 4s → … 封顶 30s；连接+认证成功后归零
- 重连成功后重新完整走 AUTH；等待对端期间收 PEER_STATE(0x01) 进入桥接

## 5. TLS 与身份固定

1. relay 首次启动自动生成自签证书（ECDSA P-256，CN=remote-voice-relay，有效期 3650 天），
   存放 `<data-dir>/cert.pem / key.pem`，并在 stdout 打印：
   - 证书 SHA-256 指纹（hex，64 字符）← 三端配置统一使用此值
2. 客户端配置：`host:port` + `token` + `fingerprint(hex)`
3. 校验流程（三端一致语义）：
   - 建立 TLS 连接（跳过系统 CA 校验——因为根信任来自指纹而非 CA）
   - 取对端证书 DER → SHA-256 → 与配置指纹比对（hex 不区分大小写）
   - 不匹配立即断开，报"证书指纹不符"
4. token 在 TLS 建立后才发送，不暴露于公网明文

威胁模型小结：

| 威胁 | 对策 |
|---|---|
| 被动窃听 | TLS（Go 默认策略，TLS1.2+） |
| 主动中间人 | 证书指纹固定 |
| 未授权接入/端口扫描 | AUTH token 校验，失败即断；AUTH 前不泄露任何信息 |
| token 枚举 | 建议 ≥32 随机字符；MVP 不做速率限制（文档标注，见 §9） |
| 恶意巨帧/慢速连接 | 65536 长度上限 + AUTH 10s 超时 |

## 6. 组件设计

### 6.1 relay/（Go）

```
relay/
├── go.mod                      # module github.com/<owner>/remote-voice/relay
├── main.go                     # flag: -addr :9432 -data ./data
│                               # token 来源优先级: -tokenfile > 环境变量 RELAY_TOKEN > -token
│                               # （命令行传 token 会泄露到 ps/history，仅限本地调试）
└── internal/
    ├── protocol/protocol.go    # 帧读写(ReadFrame/WriteFrame)、类型常量、AUTH 结构、长度上限
    ├── selfcert/selfcert.go    # 自签证书生成(存在则复用)、指纹计算与打印；key.pem 落盘 chmod 600
    └── server/
        ├── server.go           # Accept/TLS/会话表(role→conn)/状态机
        ├── session.go          # 单连接：AUTH 处理、读循环、心跳超时、写互斥
        └── bridge.go           # 配对后的双向 AUDIO 转发
└── internal/server/*_test.go   # 见 §7
```

要点：

- 会话表仅两槽（phone/mac），`sync.Mutex` 保护；桥接用两个 goroutine 对拷，
  写操作经每连接一把 `sync.Mutex` 串行化（避免 AUTH_OK/PEER_STATE 与转发的 AUDIO 交错撕裂）
- 优雅退出：SIGTERM 时关闭 listener 并通知在线端
- 日志：连接建立/AUTH 结果/配对/断开，单行结构化文本

### 6.2 mac-receiver/（Python）

```
mac-receiver/
├── receiver.py                 # 主程序（单文件，标准库 + sounddevice/numpy）
├── requirements.txt            # sounddevice numpy
└── config.example.toml         # server/token/fingerprint/device 示例
```

要点：

- 参数优先级：命令行 > config.toml；必填缺省时报错并给出修复指引（用户接口原则：错误信息说清怎么修）
- 设备选择：`--device` 支持名称子串匹配（如 `BlackHole`）；找不到时列出全部输出设备辅助排查
- 读循环：`recv_exact` 按 5B 头 + payload 组装；AUDIO 帧 reshape(-1,1) 后
  `stream.write()`（阻塞写 → 经 TCP 背压自然流控，缓冲总量恒定，延迟不累积）
- 心跳线程独立于读循环；断开后指数退避重连（§4.4）
- 证书校验：`ssl` 建连（CERT_NONE + check_hostname=False）后
  `sock.getpeercert(binary_form=True)` → `hashlib.sha256` → 与配置比对

### 6.3 android-app/（Kotlin）

```
android-app/
├── settings.gradle.kts  build.gradle.kts  gradle.properties
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/remotevoice/app/
        ├── MainActivity.kt        # 配置表单(host/token/指纹) + 启停按钮 + 状态展示
        ├── AudioStreamService.kt  # 前台服务：TLS 连接、AUTH、采音发送、心跳、重连
        ├── RelayClient.kt         # 协议实现(帧编解码/状态机)，纯 Kotlin 无三方依赖
        └── FingerprintTrustManager.kt  # X509TrustManager：DER→SHA-256 比对
    └── res/                       # 极简布局 + strings
```

要点：

- 采音：`AudioRecord`，source 默认 `MIC`；UI 提供"回声消除(通话模式)"开关 →
  `VOICE_COMMUNICATION`（Mac 外放时防止手机拾取对方声音形成回声，硬件 AEC 免费）
- 20ms 定帧读取（960 sample），直接作为 AUDIO payload 发送；发送前不做任何压缩
- **前台服务（microphone 类型）**保后台采音不被杀；通知栏常驻显示连接状态；
  minSdk 26 / targetSdk 35；权限含 `FOREGROUND_SERVICE_MICROPHONE`(API34+)、`POST_NOTIFICATIONS`
- TLS：`javax.net.ssl.SSLSocket` + 自定义 TrustManager（指纹比对），零第三方依赖
- 状态机与服务端对称：connecting → authing → waiting_peer → streaming → (reconnect)

### 6.4 调试工具 relay/cmd/fakephone（Go）

模拟手机端的正弦波发生器（440Hz，同规格 20ms 帧），使 Mac 端可在没有真机的
情况下先行联调"服务器→BlackHole→会议软件"全链路。低成本高价值，随 relay 一起交付。

## 7. 测试策略

| 层 | 手段 | 验证点 |
|---|---|---|
| 协议 | Go 单测（protocol 包） | 帧编解码 roundtrip、超限帧拒收、未知 type 丢弃 |
| 服务器 | Go 单测（server 包，内存 listener） | token 错误拒断、role 独占、配对后双向透传字节相等、对端掉线收到 PEER_STATE(0x00)、重连恢复、心跳超时断开 |
| 本地回环 | fakephone ↔ relay ↔ mac-receiver（CI 无声卡环境跳过实际发声，人工验收听音） | 全链路可出声、指纹错配被拒 |
| Android/真机 | 人工验收 | 无法在本环境编译（无 Android SDK），代码交付 + 自查清单，如实记录于 OUTCOME |
| Python pinning | 实测脚本 | 验证 `verify_mode=CERT_NONE` 下 `getpeercert(binary_form=True)` 可取得 DER（pinning 前提），失败则回退方案：`ssl.SSLContext` + 自定义 verify 回调 |

## 8. 性能与容量预算

| 项 | 数值 | 说明 |
|---|---|---|
| 音频码率 | 96 KB/s ≈ 0.77 Mbps（上行） | 48k×mono×s16le 裸 PCM |
| 服务器转发流量 | ≈1.5 Mbps 双向合计 | 每天 2h 使用 ≈ 1.4 GB/月流量级别，个人额度可忽略 |
| 端到端延迟 | ≈100–150 ms（境内） | 采集 20 + 网络 20–50 + 接收缓冲 ~50 |
| 服务器并发 | 固定 2 连接 | 单实例单会话（MVP 定位：私人专用） |

## 9. 已知限制（MVP 明示不做，均有 v2 对应）

1. 单会话：一组 phone/mac，不支持多设备（v2：多房间）
2. 无速率限制/防爆破锁定：token 足够长即可接受（v2：fail2ban 或 AUTH 失败退避）
3. TCP 队头阻塞：公网丢包时延迟抖动（v2：UDP+Opus+FEC+jitter buffer，延迟预期降至 60–100ms，带宽降至 ~30kbps）
4. 单向音频：仅手机→Mac（v2：反向声道，帧协议 type 字段已预留空间）
5. Android 端未做自动化测试（无 SDK 环境），以代码评审 + 真机人工验收代替

## 10. 用户可见的使用闭环（验收口径）

1. 服务器：`./relay -token <随机串>` → 首次启动打印证书指纹
2. Mac：安装 BlackHole → `pip install -r requirements.txt` →
   `python receiver.py --server ip:9432 --token ... --fingerprint ...` → 显示"等待手机"
3. 手机：App 填 host/token/指纹 → 启动 → 通知栏"推流中"
4. 会议软件麦克风选 **BlackHole 2ch**，对着手机说话，对端可闻 → 验收通过
