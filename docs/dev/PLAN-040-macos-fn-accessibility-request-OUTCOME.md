# PLAN-040 macOS Fn 辅助功能授权入口结果

- 计划 [PLAN-040-macos-fn-accessibility-request.md](PLAN-040-macos-fn-accessibility-request.md)
- 日期 2026-11-24（Asia/Shanghai），基线 `6e5f1c2`

首页的 Fn 权限提示提供「请求授权」和「打开辅助功能」。前者调用系统的授权接口。后者打开辅助功能面板，同时在 Finder 定位运行中的 App。窗口重新获得焦点时刷新权限状态。 [后端命令](../../mac-app-tauri/src-tauri/src/controller.rs#L845) · [首页入口](../../mac-app-tauri/web/index.html#L99)

| 复测 | 结果 |
| --- | --- |
| 前端 | Playwright 验证了缺少权限时显示入口、点击后等待系统确认、打开系统设置失败的提示、授权后隐藏，以及关闭 Fn 联动后隐藏。Vite 构建通过。 |
| Rust 与发布包 | Linux 上 29 项 Rust 测试通过。目标 Mac 的系统盘构建曾因空间不足中止，归档生成缓存后改用另一卷上的临时 APFS 映像构建。macOS 发布包本机签名及完整性检查通过。 |
| 安装 | `/Applications/Remote Voice.app` 已更新并运行，CDHash `a210c327dd06c97eaa4f5b59754beedf4fad5cb5`。上一版已压缩备份并验证完整性，构建映像已卸载。 |
| 目标 Mac 授权 | 两个入口均从正式应用界面触发，系统偏好设置打开到辅助功能。系统日志在 19:34:37 记录该安装包签名获准，回到应用后授权入口消失。 |
| Redmi 到正式 Mac | Redmi 在公网连接中按住说话 3.50 秒，发送 170 帧。Mac 前台接收窗口收到正式应用产生的 `Fn=1` 和 `Fn=0`，来源 `5256464e`，间隔 3.398 秒。 |
