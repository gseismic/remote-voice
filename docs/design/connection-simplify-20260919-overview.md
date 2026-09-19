# 连接体验简化：概念归零 + 服务器密码目录 + 多 Mac 连接

- 日期：2026-09-19
- 依赖 commit：f89efcc（PLAN-024 之后）
- 关联计划：PLAN-025 / PLAN-026 / PLAN-027
- 需求来源：用户拍板「任何时候不要出现 tofu-mismatch；有域名（暂未配置证书）；实现全部」，
  完整表述见 2026-09-19 会话（要点：用户只碰地址/密码/二维码三样东西）。

## 1. 问题定义

当前用户必须理解的概念：`rv://` vs `rvs://`、TOFU、证书指纹、永久/临时秘密、
tofu-mismatch 错误码。任一概念泄露到 UI 都违背目标；其中 tofu-mismatch
（mac-app relay.rs:358，服务器证书更换即触发，自签证书每次重签必换）是实际发生过的故障。

目标状态：

1. 证书机制对用户完全不可见；服务器证书任何变化都不产生「tofu-mismatch」错误。
2. 服务器一个密码；Mac 连上即注册；Android 登录后可查 Mac 列表、一键连接。
3. Android 可同时连接多台 Mac，左右滑动切换当前控制目标（PTT/语音发给当前页 Mac）。
4. 用户交互面收敛为：服务器地址、密码、二维码。

## 2. 方案比较与取舍

### 2.1 证书信任（PLAN-025）

| 方案 | 用户概念 | 缺点 |
| --- | --- | --- |
| A. 保留 rv/rvs 双 scheme，UI 写文档教用户 | 全部保留 | 违背目标 |
| B. 全面改 rvs://（只支持真证书） | 零概念 | 域名证书未配置期间完全不可用；局域网自签不可用 |
| C. 地址自动探测：先标准 CA 验证，证书错误回落 TOFU | 零概念 | 降级攻击敞口=TOFU 首连敞口（PLAN-008 已记录并接受） |

选 C。要点：

- 地址三形态：裸 `host:port`=auto（新默认）；`rvs://`=强制标准（兼容+逃生门）；
  `rv://`=强制 TOFU（兼容旧配置，行为不变但错误友好化）。
- auto 每次连接先标准验证 → 服务器日后配置 Let's Encrypt 证书后**自动升级**，无需任何操作。
- 已固定指纹（pin）且标准验证失败且指纹不符 → 不再 fatal `tofu-mismatch`，
  改为可恢复错误「服务器证书已更换」+ UI「重新信任并连接」按钮（清除 pin 重连，重新 TOFU）。
- 代码中 `tofu-mismatch` 字符串全仓清除（两端）。

### 2.2 密码归属（PLAN-026）

| 方案 | 密码设置处 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A. 服务器 flag/文件 | 服务器 | 单一事实源 | headless 改密码要 ssh；二维码无法携带密码 |
| B. Mac 设置后经 REGISTER 推送，服务器持久化 | Mac App | 用户原话「Mac 设置秘密+二维码」；扫码即含密码 | 多台 Mac 密码不一致时 last-write-wins（记日志） |

选 B。哈希（SHA-256 hex）持久化在 devices.json 顶层（不存原文）；手机 AUTH secret 即该哈希。
per-Mac 秘密注册表（regs）、临时秘密、SecretKind 全部删除——多 Mac 靠 target 寻址，
不再靠秘密区分。

### 2.3 Mac 列表获取（PLAN-026）

推送（服务器在注册/上下线时推 LIST）vs 轮询（手机定时请求）。选**轮询 5s**：
实现最小、断线自愈天然成立；目录规模=个人 Mac 台数（个位数），轮询成本可忽略。

### 2.4 多 Mac 并发（PLAN-027）

| 方案 | 改动面 | 缺点 |
| --- | --- | --- |
| A. 单连接多路复用（连接内 SELECT 切换桥） | 服务器协议大改 | 切换延迟；Mac 反复 PeerState 抖动；「同时连接」名不副实 |
| B. 每 Mac 一条 TLS 连接（1 login + N bridge） | 仅 Android | 手机同时维持 N 条连接（N=个位数，可接受） |

选 B。服务器 1:1 桥模型（server.go bridge）零改动；每台 Mac 仍独占一座桥。

## 3. 协议 v4 变更（PLAN-026 落地）

- `ProtoVersion` 3→4；v3 客户端连 v4 服务器将被拒绝（两端需同步升级，部署 runbook 记录）。
- AUTH（phone）：`secret` 语义改为服务器密码哈希；新增 `target`（目标 Mac device_id；
  空=登录会话，仅状态+LIST，不建桥）。
- REGISTER（Mac）：`{name, password_hash}`；空 password_hash=只改名；重复发送=热更。
- 新帧 `LIST=0x0B`：手机→服务器空 payload；服务器→手机
  `{"macs":[{"device_id","name","online","busy"}]}`（含离线已登记 Mac）。
- 删除：`perm/temp/temp_exp` 注册字段、`secret-expired` 原因码、SecretKind。
- 二维码：`rv://<host>:<port>?s=<服务器密码>&n=<Mac名>`（s 语义变更，格式不变）。

## 4. 用户体验目标形态

- Mac：服务器地址、设备名、服务器密码（存 Keychain）、二维码按钮。启动即连、断线自动重连（可关）。
- Android：启动连服务器 → 服务器状态指示 → Mac 列表（在线/离线、忙碌标记）→ 点连接 →
  控制页可左右滑动切换已连接的 Mac；添加服务器=手填或扫码（一次）。
- 错误文案全部人性化（无协议码、无 scheme、无指纹术语）。

## 5. 自查记录（2 轮）

1. auto 回落会不会把「服务器配置错误」静默降级？会降级到 TOFU，但行为与现状（rv://）一致，
   且 pin 换代时给出可恢复提示而非断连；strict 可用时永远优先，安全等级只升不降。
2. 密码 last-write-wins 的最坏情形：两台 Mac 密码不一致时手机端「密码错误」，
   用户在任一 Mac 重新输入正确密码即自愈——记录于 runbook。
3. LIST 含离线 Mac：deviceRecord 新增 Name 字段（REGISTER 时持久化），否则离线后名字丢失。
4. 旧 APK + 新服务器：AUTH secret=旧 Mac 秘密哈希 ≠ 服务器密码哈希 → invalid-secret，
   文案「密码错误」；引导升级两端。
5. login 会话不建桥：服务器必须允许 phone 认证后无桥长期在线（心跳照常），
   detached 时无桥可解（现 detach 已容忍 bridge==nil）。
6. 多连接 + 同一手机多桥：服务器 authPhone busy 判定按 Mac 维度（macSess.bridge!=nil），
   同一手机对不同 Mac 开多条连接天然合法；同一 Mac 重复连接仍被拒（peer-busy）。
