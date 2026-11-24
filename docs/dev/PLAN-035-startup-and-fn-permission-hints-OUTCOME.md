# PLAN-035 启动与 Fn 权限提示结果

- 计划 [PLAN-035-startup-and-fn-permission-hints.md](PLAN-035-startup-and-fn-permission-hints.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `944a95d`

## 使用提示

- `README.md` 开头新增「使用前」，说明 Mac 启动、Android 自动重连、Fn 的权限位置和 App 更新后重新授权的方法。
- `android-app/app/src/main/java/com/remotevoice/app/MainActivity.kt` 在目标掉线或目录没有在线 Mac 时提示启动 Remote Voice。点选离线 Mac、关闭自动重连时重试仍离线，也显示同一操作提示。
- `android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt` 对已配对目标的 `invalid-target` 和现有桥接的离线通知重新发起桥接。默认开启，每次重试等待最多五秒。`SettingsActivity.kt` 保存现有自动重连开关，关闭后状态栏提供手动重试。
- `android-app/app/src/main/java/com/remotevoice/app/RelayClient.kt` 修正真实中继 v4 `LIST` 应答解析，客户端可使用 `{ "macs": [...] }` 的目录数据，同时保留对裸数组的解析。
- `mac-app-tauri/web/index.html` 的音频输出区在 Fn 开启且缺少权限时显示授权提示。Mac 返回窗口时重新检查实际授权，页面依据结果更新提示及 Fn 状态。

## 实机排查

- 用户在系统辅助功能界面勾选后，Mac 的 `tccd` 仍记录 `Failed to match existing code requirement`。授权记录要求旧签名，已安装包的当前签名与它不同。该拒绝与当时正常的手机音频转发同时存在。
- 已在目标 Mac 更新 `/Applications/Remote Voice.app` 并完成本机签名校验。重新添加该 App 后，macOS 对当前进程返回辅助功能允许。保存的 Fn 同步按住模式继续开启。
- Mac 桌面采样记录到 Fn 按下和释放。实体手机的传输和 Fn 变化尚未在同一次按住说话中同步确认。

## 验证

- Android 35 模拟器通过真实本地 Go 中继验证目录离线提示与刷新、已配对 Android 先启动时自动建桥、已连接的 Mac 重启后自动恢复，以及设置页保存关闭开关后仅由点按触发重试。崩溃缓冲区为空。
- Mac 前端验证未授权显示、已授权隐藏及关闭 Fn 联动时隐藏，Rust 29 项测试、Vite 构建与 macOS 发布包构建通过。
- Android `assembleDebug` 与 APK 签名验证通过。构建产物与[局域网下载文件](http://192.168.1.103:19433/Remote-Voice.apk)的 SHA-256 均为 `81bda8cbe239addd779ba5bb98462ecde3754600602b6c960a69e31190345b77`。
