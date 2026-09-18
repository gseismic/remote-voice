# PLAN-019 实施结果：PTT 联动 Mac 语音输入（Fn 模拟）+ PTT 泄流修复

- 日期：2026-09-18
- 计划：PLAN-019-ptt-fn-dictation-and-ptt-leak.md
- 设计：docs/design/ptt-dictation-20260918-holdfn.md
- 结论：**已完成**（自动化验证全绿；Fn 注入真机行为待用户在 Mac 上验证）

## 交付内容

### 1. 协议 v3 增补：FRAME_TALK=0x0A（三端同步）

- server：`server/internal/protocol/protocol.go:25` 新增常量；
  `server/internal/server/session.go:73-77` readLoop 经桥接透传（与 AUDIO 同路径，
  不计数不解析）；`audioTarget` 更名 `peerTarget`（session.go:95）。
- Android：`android-app/.../RelayClient.kt` 新增 `frameTalk/talkOnByte` 常量与
  `sendTalk(on, expectedConnectionSerial)`（best-effort，桥接未就绪/串号丢弃）；
  对端上线文案"推流中/传输中"→"已就绪 · peer"（桥接≠推流）。
- Mac：`mac-app-tauri/src-tauri/src/protocol.rs` 新增 `FRAME_TALK`；
  `relay.rs` handle_frame 解析 → `RelayEvent::PeerTalking{on}`（未知 payload 归 off）。

### 2. Android：PTT 状态上报 + 泄流修复

- `AudioStreamService.setTalking`：置位后即时 `sendTalk`；`startedAt` 改为按住打点
  （计时=按住时长，桥接不再计时）；`startStreaming` 重置 `pttHeld=false`。
- `startCapture`：桥接建立/重连后若仍按住，重发 `sendTalk(true)`（状态重同步）。
- **根因 A 移除**：handsfree 功能整体删除（布局行/SettingsActivity 读写/
  `KEY_HANDSFREE`/strings），`shouldSend()=pttHeld` 纯 PTT；旧 prefs 残留值无人读取。
- **根因 B 兜底**：`MainActivity.onPause()` 强制 `setTalking(false)`（息屏/来电/
  切走时 ACTION_UP 可能不送达）；删除无用的 `pttDownAt`。
- 文案：主界面桥接未按住时显示"已就绪 · 设备名 / 按住下方按钮开始说话"（原"传输中"
  有误导）。review 阶段修复：`applyState` 状态归一同步改为匹配"已就绪"，否则桥接后
  UI 会停留在 CONNECTING。

### 3. Mac：Fn 注入（语音输入模拟）

- 新模块 `mac-app-tauri/src-tauri/src/keyinject.rs`：
  - hold（默认）：Fn(keycode 63) keyDown 保持 / keyUp 释放，AtomicBool 防重复；
  - double（兜底）：独立线程 down+up×2（30/120/30ms），对应系统"双击 Fn"听写快捷键；
  - 裸 FFI CoreGraphics（CGEventCreateKeyboardEvent/SetFlags(fn mask 0x20)/
    CGEventPost(kCGHIDEventTap)/CFRelease），零新依赖；`AXIsProcessTrusted` 未授权
    返回中文指引错误；非 macOS 空实现（Linux 构建测试可用）。
- `config.rs`：`dictation_enabled`（默认 true）/`dictation_mode`（默认 "hold"，
  apply_defaults 纠偏非法值）；serde default 兼容旧 config.json。
- `controller.rs`：PeerTalking → 状态更新 + 调注入器（权限错误经事件提示，仅一次）；
  PeerOffline/disconnect/connect_inner/Drop 均 `injector.release()` 防 Fn 悬空；
  save_settings 扩展两参数（校验 hold|double），保存即释放旧模式残留并重置警告节流。
- 前端 `web/`：设置弹窗新增"模拟 Fn"开关 + 模式选择 + 辅助功能权限提示；
  音频状态行显示"对端说话中 · 模拟 Fn"，电平表按 talking 激活。

### 4. server 测试

- `TestBridgeTalkForwardAndUnbridgedDrop`：桥接透传 TALK(0x01/0x00) 字节级断言；
  未桥接时 TALK 被丢弃且连接存活（PING→PONG 佐证）。

## 验证

| 项 | 结果 |
|---|---|
| `go vet ./... && go test -race ./internal/...` | ✅ 全绿（server 新增 1 用例） |
| `cargo test`（18 用例，含 TLS 集成 TALK 事件断言） | ✅ 全绿 |
| `cargo clippy --all-targets` | ✅ 0 警告 |
| `pnpm build` + `node --check web/app.js` | ✅ 通过 |
| `./gradlew assembleDebug` | ✅ 通过 |

## 待用户真机验证（写入 HANDOFF 遗留）

1. **两端 APK/APP 必须用新版**：旧 Android APK 回装会恢复 handsfree 残留泄流。
2. 手机未按 PTT 时"已发送"帧数应恒 0（泄流修复验收）。
3. Mac 系统设置→隐私与安全性→辅助功能勾选 App 后：按住手机 PTT → Mac 输入框出现
   语音输入激活，松开结束（hold 模式）。若长按 Fn 弹出的是输入菜单/emoji 面板
   （macOS 13+ 长按 Globe 行为，设计 §6 已列风险），到设置切"双击 Fn"模式复测。
4. 桥接中断后 Mac 的 Fn 不悬空（松开手机后按一下实体 Fn 应无异常状态）。

## 计划外修正记录

- review 阶段发现并修复：applyState 状态归一未随 RelayClient 文案变更同步（见上 2）。
- clippy too_many_arguments（save_settings 8 参）：Tauri command 需扁平参数，加
  `#[allow]` 注释说明，未引入参数结构体（避免前端传参改造）。
