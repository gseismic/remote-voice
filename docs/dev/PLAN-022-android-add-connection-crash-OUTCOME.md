# PLAN-022 结果：Android「添加连接」必闪退修复

- 对应计划：[PLAN-022-android-add-connection-crash.md](PLAN-022-android-add-connection-crash.md)
- 实施时间：2026-09-19 01:00 前后
- 实施分支：main（98f9950 基础上）
- 代码变更：`android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt`（唯一代码文件）

## 结果摘要

1. **根因确证**：真机 `adb logcat -b crash` 三次同栈 `NetworkOnMainThreadException`，
   栈顶 `RelayClient.sendFrameTo ← sendTalk ← AudioStreamService.startCapture`。
   PLAN-019（488b291）引入的「桥接建立后无条件同步 PTT 状态」在**主线程**做 TLS 写，
   Mac 在线时添加连接 100% 崩溃。第二处同因调用点 `setTalking()`（PTT 回调主线程）一并修复。
2. **修复方案**（计划方案 B）：`RelayClient` 内新增单线程 daemon 执行器 `relay-talk`；
   `sendTalk` 调用线程只做无 I/O 前置检查，TLS 写提交执行器（FIFO 保序）；
   任务内自捕获 IOException（socket 关闭竞态不产生新的未捕获异常路径）；
   `stop()` 追加 `talkExecutor.shutdownNow()`（幂等），提交竞态走
   `RejectedExecutionException` 分支返回 false；`closeQuietly()`（每轮重连）不关执行器。
   `sendAudio` 保持采音线程直写不变。
3. **真机验证**（小米 21091116AC，假 relay harness：Python TLS 服务器回 AUTH_OK 并发
   PEER_STATE online，`adb reverse tcp:19432`，手机 prefs 指向 `127.0.0.1:19432`）：
   - 修复前 APK：桥接建立即 `NetworkOnMainThreadException`，进程死（harness 与线上崩溃同因）；
   - 修复后 APK：桥接建立**不崩**，假服务器收到桥接同步 TALK `0x00`；
     `input swipe` 模拟按住 PTT 1.5s：TALK `0x01` → 约 50 帧 AUDIO → 松开 TALK `0x00`，
     全程进程存活，`logcat -b crash` 无新增记录。
4. **手机端交付状态**：修复版 debug APK 已装机（`adb install -r`，保留数据）；
   prefs 已还原（设备条目 mac/26S3-YRRW 保留，服务器恢复默认公网 118.193.40.160:9432）；
   测试用假服务器与 `adb reverse` 规则已清理；用户自起的 9432 端口 `server-linux` 未受影响。

## 与计划的偏差

- harness 端口用 19432（计划写 9432）：用户终端里有一个仍在运行的 `server-linux -addr :9432`
  （今日 00:52 起，疑似用户自用），为不干扰改用 19432，其余一致。

## 遗留事项

- 主线程创建 AudioRecord 的微卡（计划方案 C 范畴）未处理，属可选优化，不影响正确性；
- 假 relay harness 脚本在 `/tmp/rv-harness/fake_relay.py`（/tmp 重启即失），
  后续如需回归可重建（自签证书 + `adb reverse` + run-as 注入 prefs 的完整步骤见本文件 §3）。
