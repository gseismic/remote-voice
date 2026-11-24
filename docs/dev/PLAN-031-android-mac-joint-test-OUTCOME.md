# PLAN-031 Android 与 Mac 真机联测结果

- 计划 `docs/dev/PLAN-031-android-mac-joint-test.md`
- 源码基线 `6f78ef7f`
- 记录时间 2026-11-24 11:54（Asia/Shanghai）
- 结论是目标 Mac 的注册、桥接和 BlackHole 收音已通过模拟音源验证。Android 真机等待可用的调试连接或手机端完成局域网配对。

## 目标 Mac 和中继

1. 通过 SSH 登录 `mac@192.168.1.102`。已安装的 `remote-voice-tauri` 与 `docs/dev/PLAN-030-cert-recovery-local-OUTCOME.md:9` 记录的构建相同。客户端配置为 `127.0.0.1:9432`，输出设备为 `BlackHole 2ch`。
2. 在 Mac 上从相同提交构建中继，使用 `server/data/plan-031/` 存放联测登记、凭据和日志，并复用 `server/data/` 中的现有证书。当前 Mac 对回环端点的固定指纹与实际证书一致。中继监听 `127.0.0.1:9432`，启动客户端后服务端依次记录 `tls handshake ok`、`mac authed` 和 `mac registered`。
3. 本机 SSH 将 `127.0.0.1:19432` 转给目标 Mac 的 `127.0.0.1:9432`。从该转发口运行 `server/cmd/fakephone`，登录后目录显示一台在线且空闲的目标 Mac。指定该 Mac 后桥接成功。
4. 模拟手机在约 13 秒内发送 649 个 20 毫秒音频帧，稳定阶段约每秒 50 帧。中继一个 10 秒窗口记录 499 帧，共 958,080 字节。Core Audio 在 `BlackHole 2ch` 的 48 kHz 输入上测得 440 Hz 测试音期间峰值约 `0.244140`，播放前和断开后峰值回到 `0.000000`。探针仅保存统计值。

## Android 和公网可达性

- 使用 `./gradlew --offline --rerun-tasks assembleDebug` 从当前源码重新构建，34 个任务执行通过。Android SDK 的 `apksigner verify` 确认 debug APK 的 v2 签名有效。
- Linux USB 枚举识别到 Redmi Note 11 5G（`2717:ff40`）。设备接口为 MTP，系统 ADB 和 SDK 的 ADB 37.0.1 均未列出设备。用户确认已开启 USB 调试，执行手机 USB 总线重置、重启 ADB 和重插后，设备依然只有 MTP 接口，手机也未弹出授权提示。ADB 安装、`adb reverse` 和手机日志采集因此受阻。
- 已配置并从 Linux 实测 `192.168.1.102:9432` 局域网入口。该入口将同网段 TCP 连接转发给同一回环中继，模拟手机通过 TLS、服务器密码登录和目录查询，看到目标 Mac 在线。该入口可用于 Android 的局域网联测。
- MTP 挂载未提供内部存储列表。Linux 上最初的 APK 下载进程退出，浏览器报连接被拒绝。改用 systemd 用户级临时服务后，从 Linux 和目标 Mac 均收到 HTTP 200。Mac 下载内容的 SHA-256 与构建产物一致。
- 用仓库的二维码库生成局域网配对码，使用 Android 同版本的 ZXing 验证了其可解码性。核对中继日志和 BlackHole 电平时，还没有观测到真机鉴权、桥接和音频帧。联测入口和 APK 下载服务保持运行，等待手机完成配对。
- 从目标 Mac 发起 TCP 连接时，项目默认公网中继 `118.193.40.160:9432` 超时。Android 当前使用的地址和失败原因待读取真机日志确认。
- 本机中继代码的 `go vet ./...` 和 `go test -race -count=1 ./...` 均通过。
