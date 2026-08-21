# PLAN-001-voice-relay-mvp

| 项 | 内容 |
|---|---|
| 创建时间 | 2026-08-22 |
| 依据设计 | docs/design/voice-relay-20260822-mvp.md（已定稿） |
| 状态 | 待实施 |

## 1. 目标

交付"Android 手机 → 公网中继 → Mac BlackHole 虚拟麦克风"全链路 MVP：
relay 服务器（Go）、Mac 接收器（Python）、Android App（Kotlin）、调试工具与文档。

## 2. 范围

**做**：设计文档 §3–§6 全部内容；本地回环验证（TLS+认证+桥接）。
**不做**：§9 已知限制所列项（多会话、UDP、反向声道、防爆破锁定）；真机联调（无设备环境）。

## 3. 任务分解

### T1 relay 中转服务器（Go，标准库零依赖）
- [ ] `internal/protocol`：帧编解码（`[1B type][4B len BE][payload]`）、65536 长度上限、未知 type 丢弃语义、AUTH JSON 结构
- [ ] `internal/selfcert`：ECDSA P-256 自签（存在复用），cert/key.pem 落盘，key 权限 600，stdout 打印 DER SHA-256 hex 指纹
- [ ] `internal/server`：TLS accept、token 认证（AUTH 10s 超时）、role 独占注册、配对桥接双向透传 AUDIO、PEER_STATE 通知、心跳（客户端 10s PING / 任一侧 40s 读超时断开）、TCP_NODELAY、每连接写互斥、SIGTERM 优雅退出
- [ ] `main.go`：flag `-addr -data`；token 来源优先级 `-tokenfile > $RELAY_TOKEN > -token`；日志不打印 token
- [ ] 单测：帧 roundtrip / 超限拒收；token 错误断开；role 占用拒绝；配对透传字节相等；对端掉线收 PEER_STATE(0x00)；重连恢复；40s 心跳超时（测试中缩短超时参数注入）

### T2 调试工具 `relay/cmd/fakephone`
- [ ] 以 phone 角色接入 relay，持续发送 440Hz 正弦波（48k/mono/s16le/20ms 帧），参数：`-addr -token -fingerprint`

### T3 Mac 接收器 `mac-receiver/`
- [ ] `receiver.py`：TLS 连接（CERT_NONE + 取对端 DER 做 SHA-256 与配置指纹比对）、AUTH、收 AUDIO 帧写 sounddevice OutputStream（设备名子串匹配 BlackHole）、心跳线程、指数退避重连
- [ ] `--sink null` 计数模式：不依赖声卡，仅统计帧率——本机回环验证钩子（对应设计 §7）
- [ ] `requirements.txt`、`config.example.toml`；错误信息含修复指引
- [ ] 验证点（实测）：`verify_mode=CERT_NONE` 下 `getpeercert(binary_form=True)` 能取到 DER；若不能，改用自定义 verify 回调实现 pinning

### T4 Android App `android-app/`
- [ ] Gradle 工程（Kotlin DSL，minSdk 26 / targetSdk 35），零第三方依赖
- [ ] `RelayClient.kt`：协议帧编解码 + 状态机（connecting→authing→waiting_peer→streaming→reconnect），与服务器对称
- [ ] `FingerprintTrustManager.kt`：X509TrustManager 指纹比对
- [ ] `AudioStreamService.kt`：前台服务(microphone)，AudioRecord 48k/mono/s16le 定帧发送，10s PING、40s 超时重连；"回声消除"开关映射 VOICE_COMMUNICATION/MIC
- [ ] `MainActivity.kt`：host/token/指纹表单（SharedPreferences 持久化）+ 启停按钮 + 状态展示
- [ ] 权限：RECORD_AUDIO、INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_MICROPHONE、POST_NOTIFICATIONS

### T5 文档与交付物
- [ ] 根 `README.md`：三端部署使用指南（对应设计 §10 验收闭环）
- [ ] `docs/dev/PLAN-001-voice-relay-mvp-OUTCOME.md` 结果文档
- [ ] `docs/HANDOFF.md` 交接文档、`docs/dev/INDEX.md` 计划索引

## 4. 实施顺序与并行策略

1. T1 → T2（同仓，先服务器后工具）
2. T3（依赖 T1 协议定稿即可并行开发，最终联调在 T1 完成后）
3. T4 委派子代理后台实施（规格=设计文档 §6.3 + §4），与 T1/T3 并行
4. 本地回环集成测试：relay(真实自签证书) ↔ fakephone(phone 角色) ↔ `receiver.py --sink null`(mac 角色)，
   断言：认证通过、帧流量稳定 ≈50 帧/s、错误 token 被拒、指纹错配被拒
5. T5 收尾

## 5. 验证清单（完成定义）

- [ ] `go build ./...` 通过；`go vet ./...` 干净；`go test ./...` 全绿
- [ ] 回环集成测试四项断言全部通过（见 §4.4）
- [ ] `python3 -m py_compile receiver.py` 通过；pinning 行为实测结论记录于 OUTCOME
- [ ] Android 无法本机编译（无 SDK）：以代码走查自查清单代替，如实写入 OUTCOME
- [ ] 设计文档 §10 使用闭环各步骤在 README 中可逐步执行（真机部分标注待人工验收）

## 6. 风险与预案

| 风险 | 预案 |
|---|---|
| Python CERT_NONE 下取不到对端证书 | 改用 `SSLContext.verify_callback` 或包装 socket do_handshake 后取 `sock.getpeercert(True)`（实测决定，见 T3） |
| 子代理产出的 Android 代码不可编译 | 代码走查清单核对关键点（前台服务类型权限、TLS 实现、帧格式一致性）；OUTCOME 如实标注 |
| 本机无声卡无法端到端出声 | `--sink null` 计数模式验证链路，听感验收留给用户真机执行 |
| git 远端未配置 | 提交本地 commit；push 若无远端则在 OUTCOME 注明待用户补配 |

## 7. 自查记录（3 遍）

1. **第 1 遍**：T1 心跳超时需可注入（否则测试要等 40s）→ 已加"缩短超时参数注入"；token 来源优先级与设计 §6.1 对齐。
2. **第 2 遍**：T3 缺少对设计 §4.4 重连退避的验证点 → 并入回环测试（杀 fakephone 观察 PEER_STATE(0x00)；重启恢复）。T4 委派提示词必须显式给帧格式与指纹算法（hex SHA-256 of cert DER），防止子代理自行发明。
3. **第 3 遍**：范围与设计 §9 一致，无越界承诺；验证清单覆盖 AGENTS.md 的证据要求（build/test/diagnostics）；commit 信息将按仓库规范列出全部文档路径。
