# PLAN-036 Android 配对目标与桥接恢复结果

- 计划 [PLAN-036-android-bridge-recovery.md](PLAN-036-android-bridge-recovery.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `c4541b9`

## 四条审查问题

| 问题 | 修复 | Android 模拟器联调 |
| --- | --- | --- |
| 失效配对持续重试 | 从当前 `LIST` 确认等待中的 ID 已消失后停止旧桥接，清理原选择并显示目录。已登记的离线设备继续等待。 [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L400) | 保存的旧 ID 被清除，等待超过一个重试周期未再发起旧 ID 认证，点选新的在线 Mac 后建桥。 |
| 忙碌条目导致已有桥接断开 | 点击时优先切换自己已连接的 Mac，其次判断占用。服务把并发占用导致的认证拒绝记录在目标桥接。 [MainActivity.kt](../../android-app/app/src/main/java/com/remotevoice/app/MainActivity.kt#L301) [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L336) | 点击已知忙碌条目未发送认证。目录先显示可用、提交时被占用的分支显示局部错误，原 Mac 的会话标识保持不变并可继续传音。 |
| 关闭自动重连后断网需要点两次 | 客户端显式报告网络断开，状态栏点按启动重试。目录连接和当前桥接按各自状态恢复。 [RelayClient.kt](../../android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt#L138) [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L251) | 仅断开登录时点一次恢复目录，两条桥保持原会话。断开登录和两条桥后点一次恢复目录和当前 Mac，收到音频以及 TALK 按下和松开。 |
| 重试离线目标误断开其他 Mac | 关闭自动重连时，Mac 下线后保留该目标的可重试状态，点按仅重建目标桥接。切换目标同步采音与 TALK。 [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L251) [AudioStreamService.kt](../../android-app/app/src/main/java/com/remotevoice/app/AudioStreamService.kt#L371) | 两台同时在线，退出并重新启动当前 Mac 后点一次恢复。另一台保持同一会话，两台之间切换后的按住说话均通过。 |

## 复审与交付

- 复审检查目录刷新与扫码保存的顺序，按设备 ID 稳定目录顺序，并保护已经保存的新扫码目标。旧登录、桥接和采音的回调校验实例或代次。
- 最终 APK 在 Android 35 模拟器与本地 Go v4 中继复跑以上场景。已登记 Mac 晚启动和重启、等待中手动停止、横滑回到唯一就绪的 Mac、密码错误及证书重新信任均通过。崩溃缓冲区为空。
- `./gradlew --offline assembleDebug`、`apksigner verify`、`go vet ./...`、`go test -race ./... -count=1` 和 `git diff --check` 均通过。
- 下载文件与本地构建的 SHA-256 一致，为 `7db5ee6c8ae1b8b882936f91adae45905b4201756ccb8b089157b0a8ba6435a7`。 [局域网下载 APK](http://192.168.1.103:19433/Remote-Voice.apk)
