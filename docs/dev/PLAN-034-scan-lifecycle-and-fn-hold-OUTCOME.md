# PLAN-034 扫码生命周期与默认 Fn 联动结果

- 计划 [PLAN-034-scan-lifecycle-and-fn-hold.md](PLAN-034-scan-lifecycle-and-fn-hold.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `5794fbf`

## Android 扫码退出

- Android 35 模拟器用旧 APK 两次复现 `CalledFromWrongThreadException`。栈从 `scan-camera` 的 `onOpened` 进入旧版 `ScanActivity.kt:177` 的 `SurfaceHolder.setFixedSize()`，由 View 布局检查抛出。
- 新版 `android-app/app/src/main/java/com/remotevoice/app/ScanActivity.kt:79` 在主线程设置预览尺寸。Camera2 的打开、配置和关闭也在主线程串行处理，每次打开持有自己的相机资源和解码线程。页面暂停时立即撤销当前会话，旧回调只关闭各自的资源。相机打开或配置失败时保留手动输入入口。
- 模拟器完成 32 轮快速进出扫码和切换后台，期间发生 9 次 CameraService 相机连接，崩溃缓冲区为空。另验证拒绝权限仍可手输、重新授权能显示预览、无相机环境显示可手输提示。
- `assembleDebug` 和 APK 签名验证通过。局域网下载文件和本地构建的 SHA-256 均为 `22faaf91928755dcffc435e44ed3cea2042f4a11b660e854cffbf4001c09efe1`。实体 Android 此次未出现在 ADB 中。

## Mac 默认联动 Fn

- `mac-app-tauri/src-tauri/src/keyinject.rs:156` 按 macOS SDK 的 `flagsChanged` 事件和 Fn 修饰位 `0x00800000` 发出按下与松开，保留其他修饰键。保持状态与发送串行更新。退出、断开及关闭设置时释放保持键，物理 Fn 松开监听忽略本应用发出的事件。[Apple 修饰键事件](https://developer.apple.com/documentation/coregraphics/cgeventtype/flagschanged) [Apple Fn 修饰位](https://developer.apple.com/documentation/coregraphics/cgeventflags/masksecondaryfn)
- 缺少设置字段的旧配置默认启用同步按住与松开，已显式保存的关闭和双击模式继续生效。已有 Mac「连接设置」开关与模式选择可修改。
- Mac App 已更新并完成本机 ad hoc 签名校验。当前目标 Mac 设置为 `dictation_enabled=true`、`dictation_mode=hold`。上一个 App 和配置保存在 `/tmp/remote-voice-034-mac.PR7WxY/`。
- Linux Rust 29 项测试和 macOS 原生按键模块 3 项测试通过。原生测试检查按键类型、Fn 位的设置和清除、其他修饰位及事件来源标记。Vite、macOS 发布包构建、Go vet 与 race 测试通过。
- 公网实机探针成功发送 TALK 按下、松开帧。Mac 系统 TCC 日志记录了 `com.remotevoice.mac` 的辅助功能访问被拒绝，桌面观测没有出现 Fn 按下。桌面验收需要在 macOS 辅助功能中同意 Remote Voice 后重试。
