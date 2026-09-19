# PLAN-027 · 实施结果（OUTCOME）

- 对应计划: [PLAN-027-android-multi-mac.md](PLAN-027-android-multi-mac.md)
- 设计: [../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.4
- 完成时间: 2026-09-19
- 依赖: PLAN-026（Android 部分与 026 一次性落地，见其 OUTCOME 实施说明）

## 结果摘要

Android 可同时与多台 Mac 保持桥接（每台一条 TLS 连接，服务器 1:1 桥模型零改动），
左右滑动切换「当前控制目标」；PTT 语音与 TALK 帧只发给当前目标。

- **AudioStreamService 多连接**：`bridges: LinkedHashMap<deviceId, Bridge>`（每桥独立
  RelayClient + 线程 + online/peerName）；登录连接负责服务器状态与 LIST 轮询；采音单实例，
  帧只路由到 `activeDeviceId` 的桥（`routed` 门控防串流）；切换目标 = 旧桥发 TALK-off、
  新桥同步当前 PTT 状态（Mac 端 Fn 不悬空）；`ACTION_SET_ACTIVE`（存在则切换、不存在则
  建桥）/`ACTION_DISCONNECT_MAC`（断开单台，其余桥不受影响）；全部桥断开或服务停止才释放
  采音与前台通知。
- **MainActivity**：中央控制区/列表区挂 `GestureDetector` fling（|dx|≥80 且 |vx|≥600）在
  已连接 Mac 间循环切换并 Toast 提示；chips 从 `ServerStore.macCache()` 渲染（目录缓存，
  离线也可见），点按=连接/切换、长按已连接=断开；状态胶囊=服务器连通（登录会话 authed），
  副行区分服务器状态与当前 Mac 状态；PTT 按住即向当前目标推流。
- **通知**：显示当前目标状态（`已就绪 · <Mac名>`）。

## 验证

| 用例 | 结果 |
| --- | --- |
| `./gradlew assembleDebug` | ✅ |
| 同服务器双 Mac 双桥并发（服务端行为） | ✅ go test `TestBridgeFullDuplex`（同密码双 Mac、双 phone 各建桥、帧隔离正确） |
| 桥独立断开不影响其他桥 | ✅ `TestBridgeDissolutionOnDisconnect` + 单桥 disconnect 语义（代码路径） |
| 真机滑动切换/双 Mac PTT 路由 | ⏳ 待用户双 Mac 实机复核（PLAN-026 冒烟已覆盖单桥全链路） |

## 遗留

- 双 Mac 实机滑动复核（需两台 Mac 设备）；PTT 持续按住时滑动切换的目标切换路径
  （TALK-off/on 时序）建议在该场景一并验证。
