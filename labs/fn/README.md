# macOS Fn 最小测试

两份源码可以各自运行。用一台已解锁的 Mac，在仓库根目录执行相应命令。需要 Apple 命令行开发工具中的 `clang`。

## 接收 Fn

```sh
mkdir -p "$HOME/.cache/remote-voice/fn-test"
clang -fobjc-arc labs/fn/fn-receive.m -framework Cocoa -framework CoreGraphics \
  -o "$HOME/.cache/remote-voice/fn-test/fn-receive"
"$HOME/.cache/remote-voice/fn-test/fn-receive"
```

保持「Fn 接收测试」窗口在前台，按住并松开 Mac 的实体 Fn。终端先输出 `Fn=1`，松开时输出 `Fn=0`。关闭窗口即可退出。接收器只观察自己的窗口，无需开启「输入监控」。 [Apple 事件监视指南](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/MonitoringEvents/MonitoringEvents.html)

## 发送 Fn

保留接收窗口，在另一个终端运行以下命令。倒计时开始后点回接收窗口。发送器按下 Fn，保持约 2 秒后松开。

```sh
mkdir -p "$HOME/.cache/remote-voice/fn-test"
clang labs/fn/fn-send.c -framework ApplicationServices \
  -o "$HOME/.cache/remote-voice/fn-test/fn-send"
"$HOME/.cache/remote-voice/fn-test/fn-send"
```

接收器会依次输出按下和松开的时间、状态和来源。

```text
1795509955.412 Fn=1 source=5256464e
1795509957.414 Fn=0 source=5256464e
```

`source=5256464e` 是本发送器和 Remote Voice 使用的模拟事件标记。如果发送器提示缺少权限，到 macOS「辅助功能」授权系统显示的启动程序，然后重新运行。也可以只开接收器，保持窗口在前台，用手机按住并松开说话按钮，检查正式应用的 Fn 输出。
