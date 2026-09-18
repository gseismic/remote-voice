# PLAN-021 结果文件：Mac 音频输出失败链路优化

- 实施时间：2026-09-19（UTC+8）
- 基线 commit：b736099（PLAN-020 电平表动画修复后）
- 计划文件：docs/dev/PLAN-021-mac-audio-error-storm.md
- 实施分支：main（直接提交）

## 实施内容

按计划三项落地，另发现并修复一个**阻塞 macOS 构建的计划外缺陷**（见下）。

### 1. 后端根治：状态未变不推送（emit 门）

`mac-app-tauri/src-tauri/src/controller.rs`：

- `UiEvent`/`AppState` 增加 `PartialEq` 派生；
- `update_state`（controller.rs:717-733）在更新后与旧值比较，**值未变直接返回**，
  不再 clone + `app.emit`。音频输出失败时同一错误按帧重复上报的 50 次/秒
  WebView 全量重绘被从源头消除（TALK 重复、统计重复同理）。
- 新增 `RelayEvent::AudioReady` 分支：清除 `audio_error`。

### 2. 失败自愈：错误锁存 + 限频重试

`mac-app-tauri/src-tauri/src/relay.rs`：

- 新增 `AudioErrorLatch`（relay.rs:93-125）：同一错误消息只上报一次；
  `retry_due` 以 `AUDIO_RETRY_INTERVAL = 5s`（relay.rs:26）限频；
- `report_audio_failure`/`report_audio_ready`/`clear_audio_error_if_needed`/
  `retry_audio_start`（relay.rs:490-538）：feed 失败 → 去重上报 + 限频重试
  `sink.start()`；成功即发 `AudioReady` 恢复界面；PeerOffline 重置锁存。
- `FRAME_PEER_STATE`/`FRAME_AUDIO` 分支改用上述路径（relay.rs:550-590）。

`mac-app-tauri/src-tauri/src/audio.rs`：

- `CpalSink::start()`（audio.rs:170-201）：**修正 last_error 粘滞**——流存活但
  存在 CoreAudio 瞬时错误时，先丢弃旧流、清错误再重建；否则 `feed` 永远命中
  `last_error`（audio.rs:193），本会话无法恢复。
- 新增 `remember_error`：启动/播放失败时写入同一 `last_error`，使后续 feed
  返回相同消息（去重锁存才能生效）。

### 3. 前端高频事件轻量化

`mac-app-tauri/web/app.js`：

- 新增 `setText`（文本相等守卫）与统计专用 `renderCounters`：最高频的
  frames/bytes/dropped 只改 3 个文本节点；
- `renderEvents` 按 `JSON.stringify(events.slice(0, 50))` 签名、`renderAudioOptions`
  按「当前设备 + 设备列表」签名、`renderAudioAlert` 按错误文本、`renderAudioMeter`
  按状态行 HTML 签名，**不变则跳过重建**；
- `renderRelayLine`/`render()`/`renderCountdown()`/`setValueIfIdle` 全面加相等守卫；
- 左侧导航栏状态点 dataset 仅在值变化时写入。

## 计划外修正：macOS 构建被 keyinject 编译错误阻塞（重要）

排查中发现 **PLAN-019 之后 Mac 端从未构建成功过**——`/Applications/Remote Voice.app`
停留在 9/18 01:53（早于 V3.2 UI 与 Fn 注入），根因是：

- `keyinject.rs:173-181`（修改前）：`std::thread::Builder::spawn` 的闭包捕获
  `*mut c_void` 裸指针（`source`/`tap`），**不是 Send，macOS target 上无法编译**。
  此前验证在 Linux 上跑（macos cfg 分支不参与编译），故未被发现；
- 修复：转 `usize` 传入线程再还原（tap/source 生命周期为进程级，不释放），
  `unsafe` 块随外层继承、去掉嵌套冗余（keyinject.rs:172-189）。
- 连带修复 `keyinject::tests::mode_parse_and_stub_are_safe` 的 macOS 可移植性：
  测试进程通常未获辅助功能权限，原 `.unwrap()` 必失败；现接受「成功或权限提示」
  两种结果，并避免在已授权环境下真的敲击系统按键（keyinject.rs:290-307）。

另：`cargo fmt` 对 keyinject.rs/relay.rs/controller.rs 的少量既有未格式化行做了
规范化（HEAD 上 `cargo fmt --check` 原本不通过）。

## 验证结果

| 项 | 结果 |
|---|---|
| `cargo fmt -- --check` | ✅ 通过（此前不通过） |
| `cargo clippy --all-targets -- -D warnings` | ✅ 0 警告（首次在 macOS 上全量编译通过） |
| `cargo test --lib` | ✅ 21 passed（原 18 + 新增 3：锁存单测、失败去重集成、状态相等性） |
| `pnpm build` + `node --check web/app.js` | ✅ 通过（dist 3 产物） |
| `cargo build --release --features custom-protocol` | ✅ 3m34s 成功——**macOS 构建路径恢复可用** |
| 浏览器行为验证（playwright-cli + Tauri IPC stub，见下） | ✅ 全部断言通过 |

浏览器验证方法：将 dist 复制到 /tmp 并注入 `__TAURI_INTERNALS__` stub（invoke /
transformCallback），捕获 app-state 回调后模拟事件序列，断言：
相同 payload 重复到达时事件列表/设备下拉/告警文本节点**引用不变**（守卫生效）；
仅统计变化时列表仍不重建且计数文本 100→150；事件数组变化时列表重建（2 条）；
audio_error 出现→告警显示、重复→不重建、清除→隐藏。

## 待真机复核（用户在 Mac 上）

1. 重新构建并替换客户端：`cd mac-app-tauri && pnpm tauri build`（本次 release
   编译已验证通过；PLAN-020 的电平表复核项也一并在此步完成）。
2. **错误风暴复现实验**：设置 → 输出设备改为不存在的名字（如 `NoSuchDevice`）→
   连接 → 手机按住 PTT：CPU 不应明显上升，界面只出现一次「音频输出异常」；
   恢复实验：改回 `BlackHole 2ch` 保存（重连）或让手机保持推流等待 ≤5s，
   错误提示应自动消失、音频恢复（自愈重试）。
3. 若输出设备正常但仍有高 CPU：按 PLAN-020 OUTCOME「待真机复核」的排查顺序
   取 `sample` 热栈，再把结果带回。

## 追加记录

无（计划内三项与计划外 keyinject 修正均已在上文记录）。
