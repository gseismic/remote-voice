# PLAN-021：Mac 音频输出失败链路优化（错误风暴去重 + 自愈重试 + 前端高频事件轻量化）

- 制定时间：2026-09-19（UTC+8）
- 基线 commit：b736099（PLAN-020 已修复电平表布局动画）
- 关联需求：用户报告「mac-app 占用 cpu 很大」，在 PLAN-020 修复电平表动画后，
  要求「根据当前分析继续优化、修正」——即处理 review 指出的 AudioError 风暴链路
  与残余高频开销。
- 关联分析：本会话对 review 的核对结论（`relay.rs:442-461` → `controller.rs:658-660`
  → `controller.rs:715-726` → `web/app.js` 全量 render 的错误放大链），以及新发现的
  `CpalSink::start()` 早退导致 `last_error` 粘滞（`audio.rs:161-178`）。

## 1. 用户意图

消除「音频输出失败时 CPU 偏高」的放大链，并让失败状态可自愈（不再一次瞬时错误 =
整场静音），同时把桥接期 2Hz 全量前端重绘降为增量更新。目标不是改协议或网络核心，
而是把已确认的错误路径与高频路径的常数因子降下来。

## 2. 问题证据链（当前代码）

| 环节 | 证据 | 问题 |
|---|---|---|
| 输出失败只报一次 | `relay.rs:442-455`：`sink.start()` 失败发一次 AudioError，仍发 PeerOnline | 失败态会话继续 |
| 每帧再次失败 | `relay.rs:456-461`：每帧 `sink.feed()`；`audio.rs:193`（last_error 非空即失败）、`audio.rs:196-203`（流未启动即失败） | 推流期间 50 次/秒 AudioError |
| 无条件全量推送 | `controller.rs:658-660`（AudioError → update_state）、`controller.rs:715-726`（clone 后无条件 emit） | 50 次/秒 AppState 序列化 + IPC |
| 前端全量重绘 | `web/app.js:139-161`（重建 50 条事件 DOM）、`:121-137`（重建下拉框）、`render()` 全字段赋值 | WebView 主线程 50 次/秒重排 |
| 错误不可清除（粘滞） | `audio.rs:161-178`：`start()` 在 stream 已存在时早退（166-168），清 `last_error` 在早退之后（169-171）；`feed` 优先检查 `last_error`（193） | 一次瞬时 CoreAudio 错误 → 整个会话每帧失败，只能靠对端重连恢复 |

触发前提（已在 review 核对中确认）：音频输出处于失败态 **且** 手机在推流
（PTT 按住；旧 APK 泄流时为常驻）。两种持续失败路径：
(a) PeerOnline 时 `start()` 失败且会话内不再重试；
(b) 流存活期间发生 CoreAudio 瞬时错误，`last_error` 粘滞。

## 3. 方案（职责分层）

1. **错误去重放后端（根治）**：`update_state` 值未变不 emit（`AppState`/`UiEvent`
   派生 `PartialEq`）。重复的相同 AudioError、重复的 TALK 状态、重复的统计事件
   都自然被吸收；前端不再需要处理风暴。
2. **限频自愈重试**：`relay.rs` 增加 `AudioErrorLatch`（消息锁存 + 重试时间戳）：
   - 同一错误消息只上报一次（变化才上报）；
   - 失败时每 `AUDIO_RETRY_INTERVAL=5s` 至多重试一次 `sink.start()`；
   - 重试成功发新事件 `RelayEvent::AudioReady`，恢复流程复用现有 `PeerOnline` 路径；
   - PeerOffline 重置锁存。
3. **修正粘滞错误**：`CpalSink::start()` 在「流存在但 `last_error` 非空」时先丢弃旧
   流、清错误、重建——否则第 2 条的重试永远无法成功。
4. **前端高频事件轻量化**：统计字段（frames/bytes/dropped）每次事件低成本更新
   （带相等守卫）；事件列表 / 音频设备下拉 / 告警区 / 电平状态行只在相关字段变化时
   重建（签名缓存）；文本写入统一带相等守卫，避免无谓 DOM 变更。
5. **测试**：`AudioErrorLatch` 纯逻辑单测；relay 集成测试验证「3 帧同错只报 1 次、
   重试受 5s 门槛约束（start 调用 2 次）」；controller 侧 `AppState` 相等性单测。

## 4. 决策与取舍

1. 不做「输出不可用时跳过 feed」：`feed` 失败路径在任何分配前早退（`audio.rs:190-203`），
   50 次/秒的锁开销可忽略；风暴的代价在 UI 侧，已由第 1 条消除。跳过 feed 反而会让
   音频统计失真，并需要额外的恢复入口，收益低。
2. 不做「重试间隔自适应/无限退避」：5s 固定门槛足够覆盖瞬时错误，避免引入状态机。
3. 不在前端做 `JSON.stringify(state)` 整体比较：状态本来就更小，后端已做 emit 门，
   前端只做局部签名，避免双份逻辑漂移。
4. 不改协议与 Android 侧：本次问题全部在 Mac 端（输出设备失败与 UI 刷新频率）。
5. 不引入新依赖：仅用 `std::time::Instant` 与已有 tokio。

## 5. 验证方案

1. `cargo test`（新增 3 个用例：锁存单测、relay 去重集成、AppState 相等性）。
2. `cargo clippy --all-targets -- -D warnings`、`cargo fmt -- --check`。
3. `pnpm build` + `node --check web/app.js`。
4. 前端行为回归（浏览器打开 dist 产物）：连续相同 app-state 事件不重复重建事件列表 /
   下拉框（签名守卫生效）；统计数字仍逐事件更新。
5. 真机复核（用户在 Mac 上，列入 OUTCOME 待办）：
   - 复现实验：设置里把输出设备改成不存在的名字 → 桥接 → 手机推流：CPU 应无风暴，
     界面只出现一次「音频输出异常」；
   - 恢复实验：改回 BlackHole 2ch 并保存（触发重连）或等待 5s 自愈 → 错误提示消失、
     音频恢复。

## 6. 自查记录（1/3）

- `update_state` 改为「值未变不 emit」后，是否有调用方依赖「必达事件」？逐点核对：
  前端仅用 app-state 渲染，初始状态由 `get_state` 拉取（`web/app.js:441-442`）；
  状态变化必然改变至少一个字段 → 必 emit。无遗漏。
- `AppState` 加 `PartialEq` 对 Tauri `Serialize` 无影响（纯派生）。
- `AudioReady` 新变体：`controller.rs` 的 match 是穷尽的，必须补分支（已列入任务）。
- 重试门槛用 `Instant`（单调时钟），不受系统时间调整影响。
- 锁序核对：`feed` 先 `last_error` 后 `stream` 再 `queue`，且均在释放后进入下一步
  （临时 guard 随语句结束释放，`audio.rs:196-207`）；`stop` 先 `queue` 后 `stream`
  （`audio.rs:180-187`）——不存在持锁嵌套，无死锁风险。
- 新重试逻辑只在 relay tokio 线程调用（`handle_frame` 内），`Mutex` 无跨线程竞争。

## 7. 自查记录（2/3）

- 去重锁存的生命周期：PEER_ONLINE 成功时无论如何都发 `AudioReady`（覆盖上一次会话
  残留的 `audio_error`），避免「上轮错误未清、下轮正常也被前端显示为异常」。
- PEER_OFFLINE 时 `sink.stop()` 后清锁存，保证下一会话的同类错误能再次上报。
- 重试失败返回新消息（如换错误文案）时仍按「变化才上报」处理，不产生 5s 一次的
  低频刷屏。
- 前端签名缓存需在 `clear_history` 后正确失效：事件签名包含数组内容，清空即变化。
- 事件列表签名用 `JSON.stringify(events.slice(0, 50))`，50 条 × 短字段在 2Hz 下
  开销可忽略；比引用比较可靠（每次事件都是新数组）。
- `renderAudioOptions` 加守卫后，`refreshAudioDevices` 显式调用仍能生效（签名含
  `audioDevices` 列表与当前设备名）。

## 8. 自查记录（3/3）

- 回归风险：`start()` 重建分支会先丢弃旧流再建新流；旧流错误回调写入的 `last_error`
  与新建共享同一 Arc，重建时已清空，顺序为先清后建（无竞态：同线程串行）。
- `start()` 在 stream 存在且无错误时仍保持早退（幂等），不改变既有语义。
- 集成测试注入的失败 sink 需实现 `AudioSink` 全量方法（含 `stats`），且 `start` 计数
  用原子量，断言确定性不受调度影响。
- `cargo fmt`/`clippy -D warnings` 在 macOS 上编译 `keyinject.rs` 的 macos cfg 分支，
  与 Linux 验证环境略有差异，以本机结果为准。
- 文档：INDEX 追加计划/结果映射；OUTCOME 记录真机复核项（含 PLAN-020 遗留的真机
  电平表复核一并跟踪）。
