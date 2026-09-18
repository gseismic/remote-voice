# 设计：PTT 联动 Mac 语音输入（模拟 Fn）+ PTT 泄流修复

- 日期：2026-09-18
- 状态：定稿（用户已拍板关键决策）
- 关联：GOAL.md（Mac 麦克风损坏替代方案）；docs/ui-design/v3/（V3.2 纯 PTT 交互）
- 背景侦查：见 PLAN-019 同日记录（本设计两大部分：①手机按住 PTT → Mac 模拟按住 Fn，
  用 macOS 语音输入把链路音频转成文字；②修复"未按 PTT 仍持续推流"）

## 0. 用户意图

1. Mac 麦克风损坏，本系统把手机音频写入 BlackHole。现有闭环只到"系统有声音"，要在
   **任意输入框**把声音变成文字，必须激活 macOS 语音输入（听写）。用户拍板：手机按住
   PTT = Mac 端模拟**按住 Fn 不松开**，手机松开 = Fn 释放。
2. 用户观察到手机端"已发送 N 帧"未按 PTT 也在增长（持续泄流），要求根因修复。

## 1. PTT 状态如何到达 Mac（协议设计）

### 方案对比

| 方案 | 说明 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A1 显式控制帧 | 新增 `FRAME_TALK=0x0A`，payload 1 字节（0x01 按下/0x00 松开），phone→mac，server 桥接透传 | 语义明确、即时、与 PEER_STATE 风格一致 | 三端+server 同步改 | **采用** |
| A2 音频推断 | Mac 依"收到音频帧/超时无帧"推断按下/松开 | 零协议改动 | 抖动误判、收尾延迟、语义歧义 | 否决 |
| A3 扩展 PEER_STATE | 复用 0x07 帧加字段 | 少一个帧类型 | 语义过载、payload 分支复杂 | 否决 |

### A1 定稿

- 帧类型：`FRAME_TALK = 0x0A`，方向 phone→mac（server 对该帧与 AUDIO 同路径透传，
  不计数、不解析）。payload 固定 1 字节：`0x01`=按下，`0x00`=松开。
- 常量三端同步（既有约定）：Go `server/internal/protocol/protocol.go`、Rust
  `mac-app-tauri/src-tauri/src/protocol.rs`、Kotlin `android-app/.../RelayClient.kt`。
- 兼容性：
  - 旧 server 收 0x0A → default 丢弃（session.go:82，向前兼容），但**必须先部署新 server**
    才能透传；
  - 旧 Mac 收 0x0A → `handle_frame` default 分支 Ok 忽略（relay.rs:483）；
  - 手机不接收该帧（无影响）。
- 时序保证：
  - `setTalking` 变化即时发送（best-effort，失败不重试——下一条状态会覆盖）；
  - **重连/重桥后状态重同步**：手机 `onPeerOnline` 时若仍按住，重发当前状态
    （否则 Mac 重连后 Fn 状态未知）；
  - **Fn 悬空防御**：Mac 收到 `PEER_OFFLINE` 或断线时强制释放 Fn（防手机崩溃/掉线后
    Fn 永久按住）；Mac 退出（controller Drop）时同样释放。

## 2. Mac 端模拟什么按键

用户拍板：**按住 Fn（keycode 63，kVK_Function）不松开**，直到手机松开。

| 方案 | 说明 | 风险 | 结论 |
|---|---|---|---|
| B1 按住 Fn | Fn down 保持，up 释放；对应"按住即说"心智 | macOS 13+ 长按 Globe/Fn 可能弹出输入菜单/emoji 面板（**真机验证项**） | **默认模式** |
| B2 双击 Fn | 模拟"按两下 Fn"两次（开始/结束各一次），对应系统听写快捷键 | 双击判定窗口未知 | **备选模式**（设置可切） |
| B3 双击 Control | 合成事件最可靠 | 需改系统设置 | 不做，仅记录为 B1/B2 均失败时的最终兜底 |

- 实现：新模块 `keyinject`（`cfg(target_os="macos")`，非 macOS 空 stub）：
  - 裸 FFI 调 CoreGraphics：`CGEventCreateKeyboardEvent` + `CGEventSetFlags(fn mask 0x20)`
    + `CGEventPost(kCGHIDEventTap)` + `CFRelease`；不引入新依赖。
  - hold 模式：down/up 各一次，模块内 `AtomicBool` 防重复 down；
  - double 模式：down+up ×2（间隔 120ms），在独立线程执行避免阻塞 async；
  - 权限：`AXIsProcessTrusted()` 检查，未授权时 Post 无效 → 一次性 UI 事件提示
    "系统设置→隐私与安全性→辅助功能 中勾选本应用"。
- 配置（`AppConfig` 新字段，serde default 平滑迁移）：
  - `dictation_enabled: bool = true`（总开关）
  - `dictation_mode: String = "hold"`（"hold" | "double"）

## 3. 泄流 bug 根因与修复

### 3.1 根因（两个候选，均处理）

发送唯一出口 `AudioStreamService` 采音循环，门控 `shouldSend() = handsfree || pttHeld`
（AudioStreamService.kt:300），`framesSent` 仅在门控通过后递增（:369-376）。用户证实
帧数在涨且**设置页找不到"免提常开"**：

- **根因 A（确定存在）**：`KEY_HANDSFREE` 是 v1/PTT 时代的"免提常开"功能，V3.2 原型
  （docs/ui-design/v3/android.html）已无此概念，但代码残留（布局
  activity_settings.xml:150-166、SettingsActivity.kt:48,236、AudioStreamService.kt:114）。
  旧 APK 若开过免提，prefs 残留 true → 新版 UI 无处可见、无处可关 → 永久泄流。
- **根因 B（确定存在的缺陷）**：`pttHeld` 只靠 MainActivity touch 事件复位
  （MainActivity.kt:87-111）。按住期间息屏/来电/Activity 重建导致 ACTION_UP 丢失 →
  `pttHeld` 永久 true → 持续泄流。服务层无任何兜底。

### 3.2 修复定稿

1. **移除 handsfree 功能**（V3.2 语义=纯 PTT）：删布局行、开关读写、`shouldSend`
   简化为 `pttHeld`；prefs 残留不再被读取（无害）。
2. **pttHeld 兜底**：`MainActivity.onPause()` 强制 `setTalking(false)`（息屏/切走/
   弹窗覆盖均触发 onPause，覆盖真实卡死路径）；`AudioStreamService.startStreaming()`
   重置 `pttHeld=false`。
3. **文案纠偏**：桥接已建但未按住时，手机状态显示"已就绪 · 设备名"（原"传输中"误导
   用户以为在推流）；通知文案同步（RelayClient.kt:302 / MainActivity.kt:127-128）。

## 4. 各端改动清单

| 端 | 文件 | 改动 |
|---|---|---|
| server | internal/protocol/protocol.go | `FrameTalk=0x0A` 常量 |
| server | internal/server/session.go | readLoop 对 FrameTalk 经桥接透传（`audioTarget` 改名 `peerTarget`） |
| server | internal/server/server_test.go | 新增 TALK 透传 + 未桥接丢弃测试 |
| Android | RelayClient.kt | 常量 + `sendTalk(on)`；对端上线文案 |
| Android | AudioStreamService.kt | setTalking 发帧；onPeerOnline 重发；handsfree 移除；pttHeld 重置 |
| Android | MainActivity.kt | onPause 兜底；状态文案 |
| Android | SettingsActivity.kt / activity_settings.xml / strings.xml | 免提开关移除 |
| Mac | protocol.rs | `FRAME_TALK` 常量 |
| Mac | relay.rs | handle_frame 处理 TALK → `RelayEvent::PeerTalking{on}`；PeerOffline 时释放（controller 层做） |
| Mac | keyinject.rs（新） | Fn 注入（macos FFI / 非 macos stub） |
| Mac | config.rs | `dictation_enabled` / `dictation_mode` 字段 |
| Mac | controller.rs | 事件处理、配置扩展 save_settings、Drop 释放、未授权提示 |
| Mac | web/index.html + app.js | 设置弹窗"语音输入"节（开关+模式） |

## 5. 验收标准

1. `go test -race`（含新 TALK 用例）、`cargo test`/`clippy`、`pnpm build`、
   `assembleDebug` 全绿。
2. 真机/macOS 待验证项（用户执行，列出供对照）：
   - 手机升级新 APK 后：未按 PTT 时"已发送"帧数恒 0；
   - Mac 授予辅助功能权限后：按住手机 PTT → Mac 输入框出现听写激活、松开结束
     （hold 模式）；若长按 Fn 弹出的是输入菜单而非听写 → 设置切"双击 Fn"模式复测；
   - 桥接中断时 Mac 的 Fn 不悬空（断开后手动按一次 Fn 能恢复常态即无残留）。

## 6. 开放问题

- Fn 合成事件能否触发 macOS 15"语音输入"的按住行为：**只能在真 Mac 上验证**；
  B1 失败走 B2，B2 失败再评估 B3（本设计不含实现）。
- 模拟 Fn 是否会被"输入源切换"（单击 Globe）干扰：hold 模式按下即保持，理论不触发
  单击语义；真机验证。
