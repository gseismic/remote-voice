# PLAN-027 · Android 多 Mac 同时连接 + 左右滑动切换

- 设计：[../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.4
- 依赖 commit：PLAN-026 落地 commit
- 范围：仅 android-app（服务器/Mac 零改动：每 Mac 仍 1:1 桥）

## 目标

手机同时与多台 Mac 保持桥接（每台一条 TLS 连接），左右滑动切换「当前控制目标」：
PTT 语音与 TALK 帧只发给当前页 Mac；切页时旧目标发 TALK-off、新目标同步当前 PTT 状态。

## 方案

- 零依赖保持：不引 androidx.viewpager2；MainActivity 手势（GestureDetector fling）
  切换 active Mac，页面内容随 active 渲染（顶部 Mac 名 + 页点指示器）。
- AudioStreamService 多连接改造：
  - `login: RelayClient?`（target=""：服务器状态 + 5s LIST 轮询）。
  - `bridges: MutableMap<String(deviceId), BridgeHandle>`（RelayClient + 状态字段）。
  - 采音线程单实例常开（≥1 桥在线即跑）；帧路由 `activeClient?.sendAudio(...)`。
  - `setActive(deviceId)`：旧 active 发 TALK(off 若 pttHeld 先置 false) → 新 active 发当前
    PTT 状态；`connectionSerial` 隔离逻辑按 handle 各自维护。
  - 连接/断开单个 Mac 不影响其他桥；全部断开才停前台服务。
- MainActivity：
  - 列表区点「连接」→ startService(ACTION_BRIDGE, device_id)；点「断开」→ ACTION_UNBRIDGE。
  - 控制区顶部横滑手势切 active（仅对已连接 Mac）；页点指示器。
  - 状态渲染取 active handle 的 Status（每 handle 维护 state/text/peerName）。
- 通知：显示 active Mac 名与状态。

## 测试

- assembleDebug；单设备回归（= 026 行为不回退）。
- 双 Mac 本地 e2e（两台 fake/真实 Mac 或双服务器实例）写入 OUTCOME 手测清单：
  同时桥接、滑动切换、PTT 只达当前页 Mac、断开其一另一存活。

## 自查

1. 采音 AudioRecord 只能有一个：路由而非多份采音——已按单采音线程设计。
2. pttHeld 期间滑动：切页立即 TALK-off 旧、TALK-on 新（Mac Fn 语义保持不悬空）。
3. bridges 并发：Service 主线程统一变更（现有 mainHandler 模式延续）。
4. 服务器侧同一手机多桥：PLAN-026 §自查5 已确认合法。

## 实施说明（2026-09-19）

- 与 PLAN-026 的 Android 改造一次性落地（避免 MainActivity/Service 两轮重构）：
  `AudioStreamService` = 登录连接 + `bridges: LinkedHashMap<deviceId, Bridge>`；
  采音单实例，帧只路由到 `activeDeviceId`；切换目标=旧桥 TALK-off + 新桥同步 PTT；
  服务器侧 1:1 桥模型零改动。
- 主界面：`GestureDetector` fling（≥80px/≥600）在已连接 Mac 间循环切换；
  chips 标记 ◉当前/●已连接/○在线/⧖忙碌/·离线；长按已连接 chip 断开。
- 验证：assembleDebug；多 Mac 实机滑动切换待用户双 Mac 场景复核（服务器多桥
  并发已被 go test `TestBridgeFullDuplex` 同密码双 Mac 双桥用例覆盖）。
