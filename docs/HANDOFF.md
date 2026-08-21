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

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  三端常量必须同步修改（Go: `relay/internal/protocol`，Python: `receiver.py` 头部，
  Kotlin: `RelayClient.kt`）
- 信任模型：整张证书 DER 的 SHA-256 hex = 两端配置的指纹；换证书需重新分发指纹
- 心跳：客户端 10s PING / 任一侧 40s 读超时断开；重连指数退避封顶 30s
