# PLAN-001-voice-relay-mvp-OUTCOME

| 项 | 内容 |
|---|---|
| 执行时间 | 2026-08-22 02:30 – 03:05 (UTC+8) |
| 对应计划 | docs/dev/PLAN-001-voice-relay-mvp.md |
| 对应设计 | docs/design/voice-relay-20260822-mvp.md |
| 结果 | ✅ 完成（真机联调除外，见 §4） |

## 1. 交付物清单

| 任务 | 状态 | 产物 |
|---|---|---|
| T1 relay 服务器 | ✅ | `relay/main.go`、`relay/internal/protocol/protocol.go`、`relay/internal/selfcert/selfcert.go`、`relay/internal/server/{server.go,session.go}` |
| T1 单元测试 | ✅ | `relay/internal/protocol/protocol_test.go`（4 用例）、`relay/internal/server/server_test.go`（8 用例） |
| T2 fakephone | ✅ | `relay/cmd/fakephone/main.go` |
| T3 Mac 接收器 | ✅ | `mac-receiver/receiver.py`、`requirements.txt`、`config.example.toml` |
| T4 Android App | ✅ | `android-app/` 完整 Gradle 工程（11 个文件，零第三方依赖，中文注释） |
| T5 文档 | ✅ | 根 `README.md`、本文件、`docs/dev/INDEX.md`、`docs/HANDOFF.md` |

## 2. 验证证据

### 2.1 静态检查（全部通过）

```
go build ./...      # 通过
go vet ./...        # 通过（0 告警）
go test ./... -count=1
ok  remote-voice/relay/internal/protocol  0.001s
ok  remote-voice/relay/internal/server    1.959s
python3 -m py_compile mac-receiver/receiver.py   # 通过
```

覆盖点：帧编解码 roundtrip / 超限拒收 / 截断容错；token 错误拒断；AUTH 超时；
role 独占；配对双向透传字节相等；掉线 PEER_STATE(0x00)；重连恢复转发；
PING 保活存活 / 无心跳超时断开；未知帧类型向前兼容。

### 2.2 本地回环集成测试（真实 TLS + 自签证书，四项断言全过）

环境：本机 Linux，relay 监听 127.0.0.1:19432，fakephone 充当 phone 角色，
`receiver.py --sink null` 充当 mac 角色：

1. ✅ 双端认证通过并配对（PEER_STATE(online) 双向送达）
2. ✅ 音频桥接稳定 **49.7–50.0 帧/秒**（20ms 节拍精确，无累积漂移）
3. ✅ 指纹错配被立即拒绝：`致命错误: 证书指纹不符！期望/实际对照 + 修复指引`（exit=1）
4. ✅ 错误 token 被拒：`认证被拒: invalid token`（exit=1）

### 2.3 PLAN-T3 关键实测结论

Python `ssl.SSLContext(verify_mode=CERT_NONE)` 下 `getpeercert(binary_form=True)`
**可以**取得服务端证书 DER——指纹 pinning 方案成立，无需回退到自定义 verify 回调。
证据：上述断言 1 与断言 3 的真实 TLS 连接行为。

## 3. 与计划的偏差

| 偏差 | 原因 | 影响 |
|---|---|---|
| Android App 改由主代理直接实施 | 子代理委派通道故障（模型路由配置损坏，两个类别均报 ProviderModelNotFoundError），重试一次无效 | 无质量影响；已按计划中的自查要点逐项核对 |
| 新增两处实施期修复 | 自查发现：session.go 初版写互斥未实现、ACTION_STOP 私有可见性会导致 MainActivity 编译失败、AudioRecord.read 不响应 interrupt 需释放实例解除阻塞 | 均已在提交前修复 |

## 4. 未尽事项（如实记录）

1. **Android 真机编译与联调未执行**（本环境无 Android SDK/设备）。代码已按
   PLAN §3.T4 自查清单逐项核对（前台服务类型权限、TLS pinning 实现、帧格式
   与 Go/Python 两端逐字节一致、可见性修正）。待用户在装有 SDK 的机器上
   `gradle wrapper && ./gradlew assembleDebug` 后真机验收。
2. **听感验收未执行**（本机无声卡）。链路正确性以 50 帧/秒稳定计数证明；
   最终听感需用户按 README §部署步骤 2–4 验收。
3. git 远端仓库未配置，push 待用户补充 remote 后执行。

## 5. 安全说明

- token 经 TLS 加密传输，AUTH 失败即断开；服务器日志不打印 token
- 私钥文件落盘权限 600；证书指纹打印在 stdout 供手动分发
- 已知限制：AUTH 无速率限制（设计文档 §9.2），建议 token ≥32 随机字符
