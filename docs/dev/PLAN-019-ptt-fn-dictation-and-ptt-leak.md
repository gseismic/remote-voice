# PLAN-019：PTT 联动 Mac 语音输入（Fn 模拟）+ PTT 泄流修复

- 日期：2026-09-18
- 设计依据：docs/design/ptt-dictation-20260918-holdfn.md（定稿，用户拍板 hold-Fn）
- 背景：用户报两点——①手机按住 PTT 时 Mac 端模拟按住 Fn（macOS 语音输入）；
  ②未按 PTT 手机仍持续推流（手机端帧数实证）。侦查结论与方案对比见设计文档。

## 任务 1：协议加 FRAME_TALK=0x0A（server）

1. `server/internal/protocol/protocol.go`：帧常量表加 `FrameTalk byte = 0x0A`（注释：
   手机→Mac 说话状态，payload 1 字节 0x01/0x00，桥接透传不解析）。
2. `server/internal/server/session.go` readLoop：
   - `case protocol.FrameTalk:` 与 AUDIO 同路径经桥接透传到对端（不计数）；
   - `audioTarget` 改名 `peerTarget`（语义不再只音频）。
3. `server/internal/server/server_test.go`：新增用例——手机桥接后发 TALK(0x01)，
   Mac 收到同字节；未桥接时手机发 TALK 不崩溃不转发。

## 任务 2：Android 发送 TALK + 泄流修复

1. `RelayClient.kt`：
   - 常量 `frameTalk = 0x0A`、`talkOn = 0x01`、`talkOff = 0x00`；
   - 公共方法 `sendTalk(on: Boolean, expectedConnectionSerial: Long): Boolean`——
     参照 sendAudio（:144-154）固定 socket + serial 校验，best-effort 发 1 字节帧；
   - handlePeerState 文案："传输中/推流中" → "已就绪 · peer"风格（桥接≠推流）。
2. `AudioStreamService.kt`：
   - 移除 `handsfree` 字段、`KEY_HANDSFREE` 读取（:42,:114）与 `shouldSend` 的
     handsfree 分支（:300 → `pttHeld`）；`KEY_HANDSFREE` 常量从 companion 删除；
   - `setTalking(on)`（:293）内：置位后 `client?.sendTalk(on, client?.currentConnectionSerial() ?: return)`；
   - `startCapture` 内 `onPeerOnline` 路径：桥接建立时若 `pttHeld` 为真，重发
     `sendTalk(true)`（重连状态重同步）；
   - `startStreaming` 重置 `pttHeld = false`（防重启残留）。
   - 注意：sendTalk 调用点在 Activity 主线程，RelayClient 内部 outLock 已串行化。
3. `MainActivity.kt`：
   - `onPause()` 追加 `AudioStreamService.instance?.setTalking(false)`（ACTION_UP
     丢失兜底，设计 §3.2）；
   - render() STREAMING 非 talking 文案"传输中 · X" → "已就绪 · X"（:127-128）。
4. `SettingsActivity.kt` + `activity_settings.xml` + `strings.xml`：
   - 删除免提开关行（布局 :134-167 第一行 row）、`handsfreeSwitch` 字段/读写、
     `label_handsfree` 系列字符串；`saveAll` 不再写 KEY_HANDSFREE。

## 任务 3：Mac 端接收 TALK + Fn 注入

1. `protocol.rs`：`FRAME_TALK: u8 = 0x0A`。
2. `relay.rs`：`RelayEvent::PeerTalking { on: bool }`；`handle_frame` 新增
   `FRAME_TALK` 分支：payload 首字节 0x01→on / 0x00→off，其他忽略。
3. 新文件 `keyinject.rs`：
   - `#[cfg(target_os="macos")]`：裸 FFI（CGEventCreateKeyboardEvent/CGEventSetFlags/
     CGEventPost/CFRelease/AXIsProcessTrusted），keycode 63 + fn flag(0x20)，
     post kCGHIDEventTap(0)；
   - API：`set_talking(on: bool, mode: InjectMode) -> Result<(), String>` 与
     `release()`；hold 用 AtomicBool 防重复 down；double 在独立线程 down+up×2
     （间隔 120ms）；未授权返回 Err 文案（含系统设置路径）；
   - 非 macOS：全部空实现返回 Ok（保证 Linux 构建/测试）；
   - 单测：非 macOS stub 可测 set_talking/release 不 panic（macos 逻辑真机验证）。
4. `config.rs`：`AppConfig` 加 `dictation_enabled: bool`（默认 true）、
   `dictation_mode: String`（默认 "hold"，仅接受 hold|double，apply_defaults 纠偏）。
5. `controller.rs`：
   - `AppState` 加 `dictation_enabled`、`dictation_mode`、`talking`（含 from_config）；
   - `handle_relay_event`：`PeerTalking{on}` → 状态更新 + 若 enabled 调
     `keyinject::set_talking`，Err 时 add_event warning（AtomicBool 节流一次性）；
     `PeerOffline` → `keyinject::release()` + talking=false；`Drop` → release；
   - `save_settings` 扩展两参数（校验 mode ∈ {hold,double}），即时生效不重连。

## 任务 4：Mac 前端设置

1. `web/index.html` 设置弹窗：toggle 后追加"语音输入"两行——开关（启用）+ 模式
   select（按住 Fn / 双击 Fn）+ 一行说明"模拟按键需要辅助功能权限"。
2. `web/app.js`：readSettings/save 透传新字段；state 渲染 talking 徽标
   （桥接+按住时状态行加"对方说话中"）。

## 任务 5：验证

1. `go vet ./... && go test -race ./...`（server 目录）
2. `cargo test && cargo clippy`（mac-app-tauri/src-tauri，Linux 上 macos cfg 编译通过）
3. `pnpm build`（mac-app-tauri）
4. `./gradlew assembleDebug`（android-app）
5. 全绿后 review 一遍 diff，写 OUTCOME。

## 自查记录（1/3）

- TALK 帧方向只有 phone→mac，但 server 转发按桥接对端通用实现：Mac 若发 TALK
  会被转发给手机，手机 `frameAudio -> {}` 分支外未知类型"忽略（向前兼容）"
  （RelayClient.kt:286-291）→ 安全。
- `sendTalk` 需要 expectedConnectionSerial：与 sendAudio 一致防旧线程写入新连接；
  setTalking 时 serial 由 service 侧从 `currentConnectionSerial()` 现取，存在
  取值与发送间重连的窄窗口——失败即 false，下条状态覆盖，可接受（best-effort）。
- handsfree 删除后 prefs 残留 true 无人读 → 无害；旧 APK 若回装会恢复泄流，
  在 OUTCOME 注明"两端必须用新 APK"。
- onPause 兜底与正常 ACTION_UP 时序：按下期间 onPause 只会在切走/息屏时发生，
  此时本来就必须停（用户无法同时按住）；不会误伤正常按住。
- Mac 收 TALK(0x01) 时若尚未 bridged？server 只在桥接后转发 → 不会发生。
- Fn hold 与 dictation_enabled=false：set_talking 不调用，Fn 状态由 release()
  保证；开关从 true→false 切换时若 Fn 正按着，save_settings 需调 release()。
  （已列入任务 3.5 实现要点）
- cargo test 在 Linux 跑，keyinject macos 分支不参与编译：真机验证项独立列出
  （设计 §5.2），不阻塞本计划验收。

## 自查记录（2/3）

- session.go 透传 TALK 时对端可能是 phone（假设 Mac 发 TALK）：手机 handlePeerState
  只认 PEER_STATE，未知帧忽略 → 安全。
- `peerTarget` 改名涉及 audioTarget 唯一调用点（session.go:74）与注释同步。
- RelayClient.sendTalk 放 `synchronized(outLock)` 已有；heartbeat/音频/控制三写方
  并发安全不变。
- MainActivity.onPause 在服务未启动时 instance 为 null，空安全。
- strings.xml 删除 label_handsfree* 两行前确认无其他引用（grep 复核）。
- config serde default：旧 config.json 无新字段时 Default::default 生效（#[serde(default)]
  已在结构体级），无需迁移代码。

## 自查记录（3/3）

- double 模式线程：set_talking(on) 与 (off) 连续触发两次线程并发——double 只是
  重复动作，幂等无状态竞争（每次独立 down/up×2），可接受。
- PeerTalking 事件从 relay tokio 线程回调 controller（events handler 与既有
  RelayEvent 同路径），update_state 线程安全同现状。
- save_settings 前端 readSettings 必须同步新参数，否则 Rust 端缺参报错——任务 4
  与任务 3.5 必须同 commit 落地。
