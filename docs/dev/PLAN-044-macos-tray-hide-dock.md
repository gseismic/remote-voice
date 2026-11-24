# PLAN-044 Mac 窗口收起时隐藏 Dock 图标

- 基线 `bd39d81`
- 时间 2026-11-25（Asia/Shanghai）
- 设计 [macos-menu-bar-20261125-overview.md](../design/macos-menu-bar-20261125-overview.md)

在目标 Mac 复现关闭后主窗口数为 `0`、Dock 中 Remote Voice 项数为 `1`。Dock 里未固定 Remote Voice，最近使用的应用已关闭。当前关闭处理隐藏窗口，应用仍处于在 Dock 显示的状态。

1. 在现有的关闭和恢复入口切换 macOS 的应用激活策略。窗口隐藏成功后使用 `Accessory`，恢复界面时使用 `Regular`。切换失败时仍按现有窗口流程处理。
2. 更新 Mac 使用说明，说明左上角关闭按钮和菜单栏恢复的行为。
3. 验证 Rust 测试、改动文件格式和差异检查。构建正式 macOS 包并备份安装。检查启动显示 Dock、关闭隐藏 Dock、菜单恢复窗口及 Dock、快速恢复后再关闭，以及后台保持中继在线。
4. 记录验证结果，更新计划索引，提交并推送。
