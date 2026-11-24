# PLAN-043 Mac 菜单栏版本部署结果

- 构建提交 `1980538d1d9832e87ac5e1a360e1e4ef219433ba`
- 日期 2026-11-25（Asia/Shanghai）
- 计划 [PLAN-043-deploy-macos-menu-bar.md](PLAN-043-deploy-macos-menu-bar.md)

目标 Mac 当时安装的是 11 月 24 日的正式包，关闭窗口会结束进程。已从 `1980538` 在 macOS 12.7.6 x86_64 构建并安装新版到 `/Applications/Remote Voice.app`。正式包通过签名校验，内含菜单栏动作，安装后二进制与构建产物一致。

| 核对项 | 结果 |
| --- | --- |
| 正式包标识 | `com.remotevoice.mac`，CDHash `4efa460e0ea8bf631c6d21f6da16261728d5ea74`。 |
| 回滚备份 | `~/Library/Application Support/Remote Voice/Backups/Remote Voice-20261125-014444-a210c327.zip`。压缩包完整性及其中二进制的摘要均已验证。 |
| 关闭及恢复 | 正式版进程 `91507` 成功注册并显示「等待手机」。点按红色关闭按钮后窗口隐藏，进程号和菜单状态保持，点按「显示主窗口」恢复窗口和焦点。 |
| Fn 辅助功能 | 新签名启动后首页显示「请求授权」。已从正式版发起 macOS 授权请求，等待用户在系统中确认。 |
