# PLAN-044 Mac 窗口收起时隐藏 Dock 图标结果

- 基线 `bd39d81`
- 日期 2026-11-25（Asia/Shanghai）
- 计划 [PLAN-044-macos-tray-hide-dock.md](PLAN-044-macos-tray-hide-dock.md)

旧版在目标 Mac 收起窗口后仍有一个 Dock 运行图标。已在关闭成功时切换到 `Accessory`，恢复窗口时切回 `Regular`。该切换随现有窗口动作执行。 [关闭和恢复窗口](../../mac-app-tauri/src-tauri/src/tray.rs#L39) · [菜单栏用法](../../mac-app-tauri/README.md#菜单栏)

| 验证 | 结果 |
| --- | --- |
| 正式版安装 | macOS 12.7.6 x86_64 发布包已安装至 `/Applications/Remote Voice.app`。签名校验通过，CDHash 为 `8d94f92c93a7bec5777e108e484811ad5254c25d`。 |
| 关闭与恢复 | 主窗口和 Dock 项数从 `1, 1` 变为 `0, 0`。选择「显示主窗口」后恢复到 `1, 1`，窗口取得焦点。紧接着再次关闭也回到 `0, 0`。 |
| 系统重新打开 | 系统打开同一安装包可恢复窗口和 Dock。测试结束时已再次收起，正式版进程 `95735` 继续在线，菜单显示「等待手机」。 |
| 构建与回归 | Linux 上 29 项 Rust 测试通过。改动文件的 Rust 格式检查、前端构建和 `git diff --check` 通过。 |
| 原包备份和 Fn 权限 | 原包归档到 `~/Library/Application Support/Remote Voice/Backups/Remote Voice-20261125-015705-4efa460e.zip`，完整性已校验。新签名的首页显示 Fn 需要重新确认辅助功能授权。 |
