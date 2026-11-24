# PLAN-039 macOS Fn 最小测试结果

- 计划 [PLAN-039-macos-fn-minimal-test.md](PLAN-039-macos-fn-minimal-test.md)
- 日期 2026-11-24（Asia/Shanghai），基线 `ee793c8`

## 交付

- [fn-send.c](../../labs/fn/fn-send.c) 通过 Quartz 向系统提交 Fn 的 `flagsChanged` 事件，保持 2 秒后松开。发送前检查当前进程的按键权限。 [Apple CGEventPost 文档](https://developer.apple.com/documentation/coregraphics/cgevent/post%28tap%3A%29?changes=__8&language=objc)
- [fn-receive.m](../../labs/fn/fn-receive.m) 在独立的 AppKit 窗口里记录 Fn 状态和来源。 [编译和运行说明](../../labs/fn/README.md)

## 目标 Mac 验证

| 检查 | 结果 |
| --- | --- |
| 独立编译 | 在 macOS 12.7.6 的 Intel Mac 上按 `labs/fn/` 路径用 `clang -Wall -Wextra -Werror` 分别编译通过。 |
| 实际桌面派发 | 前台的独立接收器收到 `Fn=1 source=5256464e`、`Fn=0 source=5256464e`。发送器由 SSH 命令行启动，接收间隔 2.002 秒。 |
| 事件内容 | 进程内验证通过。两条事件均为 `flagsChanged`、键码 63、标记 `5256464e`。Fn 修饰位先设置再清除，Shift 修饰位保留。 |
| 独立发送 App 的权限 | 用同一份发送代码打包的临时 App 在桌面启动时未获按键发送权限，输出明确的权限提示。 |

## 正式应用复测

Redmi 保持通过 `voice.gpt60.com` 连接目标 Mac。复测按住说话 3.47 秒时发送 170 帧。接收窗口在复测前后均在前台，未记录正式应用的 Fn 事件。

另一次真机 PTT 按住 3.48 秒，发送 170 帧。对齐两台机器的时钟后，`BlackHole 2ch` 输入的 50 毫秒峰值 RMS 在按下前、按住时和松开后分别为 `0`、`0.039018656` 和 `0`。探针只计算电平。

macOS 日志仍记录旧授权签名 `0fd936b3…` 与当前安装包 `a2fba748…` 不匹配。本轮另外归档了三个临时构建目录中的旧 Remote Voice 副本，压缩包均通过完整性检查。正式应用的 Fn 验证仍待当前安装包的辅助功能授权。 [正式应用的按键发送检查](../../mac-app-tauri/src-tauri/src/keyinject.rs#L291-L300)
