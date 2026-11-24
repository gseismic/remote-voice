# PLAN-042 Mac 菜单栏支持结果

- 基线 `4d0e823`
- 日期 2026-11-25（Asia/Shanghai）
- 计划 [PLAN-042-macos-menu-bar.md](PLAN-042-macos-menu-bar.md)

Mac 菜单栏新增适配系统外观的麦克风图标。菜单显示连接、说话和音频输出状态，并提供「显示主窗口」与「退出 Remote Voice」。主窗口启动时可见，关闭后会收起并继续处理手机音频和 Fn 联动。菜单和 Dock 都能恢复同一个窗口。 [托盘与窗口动作](../../mac-app-tauri/src-tauri/src/tray.rs#L17) · [使用说明](../../mac-app-tauri/README.md#菜单栏)

退出事件先解除界面关联，再停止中继并等待网络线程退出，最后兜底释放 Fn。菜单刷新只在状态文案变化时调度，主线程执行时读取最新快照。已复核菜单栏初始化失败时窗口按原流程关闭。 [退出清理](../../mac-app-tauri/src-tauri/src/controller.rs#L282) · [状态更新](../../mac-app-tauri/src-tauri/src/controller.rs#L696)

| 验证 | 结果 |
| --- | --- |
| macOS 发布包 | macOS 12.7.6 x86_64 使用锁定依赖构建 `.app`，隔离实例的本机签名校验通过。 |
| 窗口生命周期 | 点按关闭后窗口隐藏，进程继续运行。菜单可恢复窗口并获得焦点，也可恢复已最小化的窗口。通过系统重新打开应用时恢复原有进程的主窗口。 |
| 后台传音 | 用本地 TLS 中继及模拟手机发送 PTT 和 125 帧测试音频。菜单状态经历「等待手机」「手机已连接」「正在说话」「手机已连接」。窗口持续隐藏时，BlackHole 2ch 输入峰值 RMS 为 `0` → `0.044364661` → `0`。 |
| 退出 | 在模拟手机按住说话时从托盘退出，进程结束，中继目录显示 Mac 离线且桥接关闭。再次启动后，从 macOS 应用菜单退出也通过。隔离实例关闭 Fn 注入，退出时的 Fn 释放路径经代码复核。 |
| Rust 与前端 | Linux 上 29 项 Rust 测试通过。前端构建、JavaScript 语法检查、改动文件的 Rust 格式检查和 `git diff --check` 通过。 |

全量 `cargo fmt -- --check` 在现有 `config.rs` 和 `relay.rs` 上报告格式差异。严格 Clippy 命中现有的未使用字段及恒真比较，放行这些诊断后通过。 [格式差异示例](../../mac-app-tauri/src-tauri/src/config.rs#L178) · [未使用字段](../../mac-app-tauri/src-tauri/src/protocol.rs#L70) · [恒真比较](../../mac-app-tauri/src-tauri/src/relay.rs#L1357)
