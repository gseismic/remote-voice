# PLAN-041 Android 在 Mac 暂时被占用后恢复连接的结果

- 计划 [PLAN-041-android-busy-reconnect.md](PLAN-041-android-busy-reconnect.md)
- 日期 2026-11-24（Asia/Shanghai）

## 修复

17:03:39 公网中继拒绝新桥接，原因是目标 Mac 已有会话。旧桥接于 17:03:51 关闭，Redmi 此后仍显示失败。`peer-busy` 现在进入「等待 Mac 空闲」。打开自动重连时按现有网络退避重试，关闭时点按状态栏只重建当前桥接。 [RelayClient.kt](../../android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt#L163) · [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L252)

## 验证

| 场景 | 结果 |
| --- | --- |
| 旧 APK 复现 | Android 35 模拟器从扫码页配对，真实 Go 中继返回 `peer-busy`。旧会话释放且目录显示可用后，仍停留在失败状态。 |
| 新 APK 自动重连 | 同一中继与设备在旧会话释放后自动建桥，无需点按。 |
| 新 APK 手动回退 | 关闭自动重连，空闲后一次点按重建当前桥接。另一台 Mac 保持桥接且收到按住说话的音频帧。 |
| APK | `lintDebug`、`assembleDebug` 与 APK v2 签名验证通过。覆盖安装保留 Redmi 的配对信息。首次公网建桥发生 TCP 超时，应用继续尝试后自动恢复「已就绪」。 |
| Redmi 与正式 Mac | 重启 Mac 应用时，手机显示「等待 Mac 上线 · 自动重连」，随后自行恢复「已就绪」。正式 Mac 在下一次真机 PTT 收到 `Fn=1` 和 `Fn=0`，间隔 3.382 秒。 |
| BlackHole 音频 | 真机另一次 PTT 发送 170 帧。`BlackHole 2ch` 的 50 毫秒峰值 RMS 为 `0` → `0.016222560` → `0`，分别对应按下前、按住及释放后。 |
| Redmi 扫码 | 正常入口进入扫描页并退出，连续三轮通过。应用进程与原公网连接保持。 |

局域网下载包与 Redmi 安装包的 SHA-256 均为 `907defb0093aa9bd8a778bdd86829eb5131e780b2805269a0dce6e29f1e0b812`。 [下载 APK](http://192.168.1.103:19433/Remote-Voice.apk)
