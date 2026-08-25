# 交接文档 (HANDOFF)

更新时间：2026-08-22 03:10 (UTC+8)

## 项目背景（零上下文可读）

用户的 Mac 麦克风硬件损坏。本项目实现替代方案：**Android 手机采音，经用户自有的
公网 Linux 服务器中继，写入 Mac 的 BlackHole 虚拟声卡**，使任意 Mac 应用把这条链路
当作麦克风。全部自研、完全可控；不依赖域名（TLS 用自签证书 + SHA-256 指纹固定）。

- 目标原始记录：`GOAL.md`
- 设计定稿：`docs/design/voice-relay-20260822-mvp.md`（方案对比、线路协议 §4、
  TLS 信任模型 §5、组件设计 §6——改代码前必读）
- 计划与结果：`docs/dev/PLAN-001-voice-relay-mvp.md` / `-OUTCOME.md`
- 计划索引：`docs/dev/INDEX.md`

## 当前状态

MVP 已实现并通过本机验证（go test 12 用例全绿 + 回环集成测试四项断言全过，
详见 OUTCOME §2）。三端代码就绪：

| 组件 | 路径 | 状态 |
|---|---|---|
| relay 中转服务器 + fakephone 调试工具 | `relay/` | ✅ 已验证 |
| Mac 接收器 | `mac-receiver/receiver.py` | ✅ 已验证（含 pinning 实测） |
| Android App | `android-app/` | ⚠️ 代码完成，未经编译（环境无 Android SDK） |

## 下一步（按优先级）

1. **真机联调验收**：在有 SDK 的机器编译 android-app 并按 README 部署三端实测听感
   （唯一未闭环的验收项）
2. **服务器部署**：relay 交叉编译后部署到用户公网服务器，配置 systemd 常驻
3. v2 演进（可选）：UDP+Opus+FEC 降延迟降带宽、反向声道、多会话——见设计文档 §9

## 本机环境备忘（开发机）

- Go 工具链：`~/go-sdk/go`（go1.22.10），PATH 已写入 `~/.bashrc`，兜底软链 `~/.local/bin/go`
- Python：miniconda 3.12（`python3`/`pip3` 直用）；pytest 已装
- 预编译产物（易失，重启后需重编）：曾输出至 `/tmp/opencode/relay-linux-amd64-static`（静态）等

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  三端常量必须同步修改（Go: `relay/internal/protocol`，Python: `receiver.py` 头部，
  Kotlin: `RelayClient.kt`）
- 信任模型：整张证书 DER 的 SHA-256 hex = 两端配置的指纹；换证书需重新分发指纹
- 心跳：客户端 10s PING / 任一侧 40s 读超时断开；重连指数退避封顶 30s

---

# 2026-08-26 v2 迁移交接补充（协议注册制 + PTT + Mac GUI）

## 当前状态（v2 已实施）

协议 v2 全量落地（PLAN-005A/B/C 三端已提交推送），**v1 兼容已删除**：

| 组件 | 路径 | 状态 |
|---|---|---|
| relay v2（注册表路由 + 限速防爆破 + REGISTER/EVENT） | `relay/` | ✅ go test -race 全绿（20 用例） |
| Mac 图形客户端（PySide6） | `mac-client/` | ✅ 单测+回环集成全绿（19 例），GUI offscreen 冒烟通过 |
| Mac 轻量接收器（已升 v2） | `mac-receiver/receiver.py` | ✅ 5 项协议单测全绿 |
| Android v2（PTT + 多设备） | `android-app/` | ✅ assembleDebug 通过（0.2.0/versionCode 2） |

## 关键变化（相对 v1）

- 认证模型：Mac 用 **regkey**（`-regkeyfile/RELAY_REGKEY`）认证后 REGISTER 秘密哈希；
  手机 AUTH 只带 `secret`=规范化+SHA-256 hex；relay 零明文。
- 新帧：REGISTER(0x08)/EVENT(0x09)；AUTH_ERR 带原因码；t= 参数从 rv:// 移除。
- 防爆破：按 IP 60s/5 失败 → 1m/5m/30m 递增锁；成功清零。
- 仅凭秘密连入：手机输入 Mac 界面短码即可，无需再知道服务器密码。

## 部署（生产者→用户手动执行）

见 `relay/DEPLOY.md` §2/§4/§5（regkey.txt 先建）；**保持 ./data 目录不动则指纹不变**。
relay-linux 静态产物在 `relay/relay-linux`（git 忽略，已交叉编译 amd64，勿 commit）。
systemd 单元记忆点：`-tokenfile` 已改名 `-regkeyfile`。

## 下一步（v2 余项）

1. 部署新版 relay 上线（用户执行）+ 真机为 Mac/手机装新客户端（v1 客户端已无法认证）
2. mac-client 长跑观察：Keychain CLI 在真实 macOS 的行为、GUI 与黑屏休眠 PTT 切音
3. 演化方向：UDP+Opus+FEC、反向声道、历史落 relay 侧、音频波形 UI 化
