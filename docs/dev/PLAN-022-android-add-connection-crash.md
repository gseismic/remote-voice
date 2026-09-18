# PLAN-022: Android「添加连接」必闪退修复（NetworkOnMainThreadException）

- 依赖 commit：98f9950
- 编写时间：2026-09-19
- 现象（用户原话）：android 添加连接总闪退
- 真机证据：小米 21091116AC（adb 5LFAJ7TWUGUCCERO），`adb logcat -b crash` 连续 3 次同栈崩溃（2026-09-19 00:47~00:48，versionName 0.2.0 / versionCode 2）

## 1. 根因（已由崩溃栈确证）

崩溃栈（节选）：

```
android.os.NetworkOnMainThreadException
    at RelayClient.sendFrameTo(RelayClient.kt:363)
    at RelayClient.sendTalk(RelayClient.kt:169)
    at AudioStreamService.startCapture(AudioStreamService.kt:321)
    at ...relayListener$1.onPeerOnline$lambda$3(AudioStreamService.kt:155)
```

因果链：

1. 手机「＋ 添加」录入 Mac 密码后，`MainActivity.showAddDeviceDialog()`（MainActivity.kt:252）→
   `restartForActiveDevice()` → 自动拉起 `AudioStreamService`；
2. relay 连上服务器、Mac 在线时，`RelayClient.handlePeerState()`（RelayClient.kt:318）在
   **relay 线程**回调 `onPeerOnline`，服务里经 `postIfCurrent { startCapture() }`
   （AudioStreamService.kt:155）**post 到主线程**执行；
3. `startCapture()` 第一件事是桥接建立后无条件同步 PTT 状态：
   `captureClient.sendTalk(pttHeld, ...)`（AudioStreamService.kt:321，PLAN-019 引入）；
4. `sendTalk` → `sendFrameTo` 做 **TLS socket 写** → 主线程网络 I/O →
   `NetworkOnMainThreadException` → 进程被杀（闪退）。

所以只要 Mac 在线（「添加连接」的真实场景必然是 Mac 在线亮着密码等手机），添加即闪退、
重试即再闪退，表现为「总闪退」。Mac 不在线时不会崩（只停在等待 Mac），这也解释了此前
联测若没走到桥接就发现不了。

### 引入时间

`git log -S "captureClient.sendTalk" -- android-app` → 488b291（PLAN-019）。PLAN-020/021
只改 Mac 侧，手机端未重测桥接链路，故延续至今。

### 同根因的第二处（必一并修）

`AudioStreamService.setTalking()`（AudioStreamService.kt:299-302）在 Activity 主线程
（PTT 触摸回调、onPause 兜底）直接调 `relay.sendTalk(...)` —— 同样是主线程 TLS 写。
只修 startCapture 的话，按住 PTT 的瞬间仍会闪退。

## 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A：调用侧后台化 | 服务里两处 sendTalk 各自 `Thread{}.start()` | 改动局部 | 每次按 PTT 起线程；多个发送线程间无顺序保证，TALK on/off 可能乱序 → Mac 端 Fn 悬空风险回归（正是 PLAN-019 追加修复过的问题） |
| B：RelayClient 内部单线程执行器（**选定**） | `sendTalk` 把写帧动作提交到实例内 `Executors.newSingleThreadExecutor`（daemon，命名 relay-talk），`stop()` 时 `shutdownNow()` | 一处修改覆盖两处调用点；FIFO 保序，TALK on/off 顺序稳定；主线程调用立即返回 | 引入一个常驻（懒创建）daemon 线程；需处理执行器已 shutdown 的提交竞态 |
| C：整条采音/发送移出主线程重构 | startCapture 全量后台化 | 顺带解决主线程建 AudioRecord 的微卡 | 超出本 bug 范围、风险大，不做（记为后续可选优化） |

## 3. 实施步骤

1. `RelayClient.kt`：
   - 新增 `talkExecutor = Executors.newSingleThreadExecutor { daemon "relay-talk" }`（懒创建，首任务才起线程）；
   - `sendTalk(on, serial)`：调用线程只做无 I/O 的前置检查（socket 非空、peerOnline、串号一致、running），通过后把 `sendFrameTo` 提交执行器；提交本身包 `try/catch RejectedExecutionException`（stop 后竞态提交，返回 false 即可）；
   - `stop()` 追加 `talkExecutor.shutdownNow()`（幂等）；`closeQuietly()`（每轮重连的 finally 都会走）**不**关执行器，否则重连后 TALK 永久失效；
   - `sendAudio` 保持采音线程直写不变（背压自然、50 帧/秒不引入线程切换；与 TALK 并发写已有 `outLock` 帧级互斥，帧不会撕裂；TALK 与 AUDIO 相对顺序对 Mac 端语义无影响——两者独立消费）。
2. 不改 UI / 服务逻辑；AudioStreamService 两处调用点代码不变（线程语义由 RelayClient 兜住）。

## 4. 验证方案（真机 + 本地假 relay 服务器）

1. harness：Python 脚本起自签 TLS 服务端（帧格式 `[1B type][4B len BE][payload]`）：
   收 AUTH(0x01) → 回 AUTH_OK(0x02) `{"mac":"TestMac"}` → 主动发 PEER_STATE(0x07)+0x01
   （触发 onPeerOnline → startCapture 崩溃路径）→ 回 PONG、打印收到的 TALK(0x0A) 值。
   `adb reverse tcp:9432 tcp:9432` + 手机端服务器填 `127.0.0.1:9432`（TOFU 首连信任任意证书，无需配指纹）。
2. 先用**未修复** APK 复现闪退，证明 harness 真正走到了崩溃路径（与线上崩溃栈同因）；
3. 构建修复版 debug APK 装机，同 harness 重验：
   - 添加设备 → 桥接建立 → **不闪退**，主界面进入「已就绪 · TestMac」；
   - 模拟按住/松开 PTT → 不闪退，harness 收到 TALK 0x01 / 0x00 各一帧；
4. `adb logcat -b crash` 无新增 remotevoice 崩溃记录。

## 5. 风险与自查（已过 2 遍）

- stop 后主线程再提交 → RejectedExecutionException 已捕获（自查 1 新增边界）；
- closeQuietly 不关执行器，重连多次 TALK 仍可用（自查 2 确认）；
- 执行器任务仅可能抛 IOException（getOutputStream/write），sendFrameTo 已捕获，不会形成新的未捕获异常杀进程路径；
- 主线程建 AudioRecord 的微卡属方案 C 范畴，本计划不扩大范围。
