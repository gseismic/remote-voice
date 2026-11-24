# PLAN-031 Android 与 Mac 真机联测

- 编写时间 2026-11-24 11:27（Asia/Shanghai）
- 源码基线 `6f78ef7f`
- 目标是在本机 USB 连接的 Android 真机上运行当前客户端，验证配对、选择目标 Mac、按住说话和松手停流，核对 `mac@192.168.1.102` 上的 BlackHole 输入。
- 已确认目标 Mac 安装包与 `docs/dev/PLAN-030-cert-recovery-local-OUTCOME.md:9` 记录的版本一致。其客户端已配置 `127.0.0.1:9432` 和 `BlackHole 2ch`，本地中继当前未启动。

## 联测步骤

1. 构建当前 Android debug APK，运行 Go 中继测试。核对 USB 设备在 ADB 中可用后，使用覆盖安装保留手机现有应用数据。
2. 从目标 Mac 上同一提交构建中继，复用 `server/data/` 的固定证书，在 Mac 回环地址启动。使用独立的联测设备登记数据和随机联测密码，避免改变既有端点的配置和凭据。
3. 打开目标 Mac 的已安装客户端，核对证书、鉴权与 REGISTER。用 SSH 本地转发和 `adb reverse` 将 Android 的回环测试端口接到 Mac 中继。
4. 在 Android 的添加服务器界面登录，确认目标 Mac 在线并建立桥接。检查按住 PTT 时收到音频帧和 BlackHole 输入电平变化，松手后帧数停止增长。以 Mac 客户端、中继或 Core Audio 的实测证据报告覆盖范围。
5. 写入配套结果记录与 `docs/dev/INDEX.md`。测试时出现的设备或系统授权阻塞应记录对应证据和未覆盖的环节。

等待 ADB 授权期间，用仓库的 `server/cmd/fakephone` 登录和查询目录，经 SSH 转发向真 Mac 发送 440 Hz 测试音。用 Core Audio 读取 BlackHole 输入的分时段峰值，保存音量统计以验证 Mac 收音。

Android 报告连不上远程后，从目标 Mac 检查项目默认公网地址的 TCP 可达性。在目标 Mac 的局域网地址增加临时 TCP 转发，收到的 TLS 数据交给同一回环中继。用本机登录和目录查询验证新入口，同时继续等待 USB 调试授权。

如果重插 USB 后仍只有 MTP 接口，尝试通过手机现有的文件传输权限将 debug APK 放入下载目录。MTP 未提供存储目录时，通过本机局域网临时下载地址单独提供 APK。复用仓库的二维码生成代码给出局域网配对码。手机系统的安装和 App 的麦克风授权由用户确认，中继日志和 Mac 输入峰值继续由本机核对。

## 依据与验收

- 客户端正常入口参见 `README.md:75` 和 `README.md:83`。配对需要服务器密码，服务端登录、桥接和帧计数的检查点参见 `server/internal/server/server.go:320`、`server/internal/server/server.go:370` 和 `server/internal/server/server.go:510`。
- 计划自查确认 Android 与 Mac 都连接同一中继，转发无需手机 Wi-Fi，现有 Mac 证书固定记录与中继证书匹配。现有 Mac 禁用了语音输入联动，本次以 PTT 音频链路为验收范围。
