# PLAN-033 公网 HTTPS 中继与扫码直连结果

- 计划 [PLAN-033-public-https-scan-to-connect.md](PLAN-033-public-https-scan-to-connect.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `d08e38d`

## 完成事项

- 在 `118.193.40.160` 的现有 Nginx 接入 `https://voice.gpt60.com`，以 Let's Encrypt 签发域名证书。Certbot 定时器运行，模拟续期与 Nginx 重载钩子通过。浏览器入口和 `/healthz` 返回 HTTP 200，OpenSSL 的证书链与主机名校验通过。
- 新 Go 中继二进制已安装。Nginx 将 `/relay` 升级连接转到 `127.0.0.1:19432`，两端继续使用 v4 的认证与音频帧。公网 Mac 身份需要预登记，管理用原生端口监听 `127.0.0.1:9432`。部署和新增设备步骤见 [deploy/voice.gpt60.com/README.md](../../deploy/voice.gpt60.com/README.md)。
- 从目标 Mac 的既有局域网登记迁入已登记设备的凭据摘要。目标 Mac 已安装新版 `/Applications/Remote Voice.app`，中继地址已切换为公网 HTTPS，Go 日志确认该设备鉴权及密码登记成功。原配置没有服务器密码，本次生成新密码并保存于客户端支持的本机凭据文件，权限为 `0600`。
- Mac 配对码携带 HTTPS 地址、服务器密码和设备 ID。Android 读取后保存中继配置并启动目标桥接。旧版地址与目录选择路径继续可用。测试中发现 Android 首次页面「＋ 添加」可能被长空态推到屏外，目录轮询与 TLS 关闭也会触发主线程网络异常，均已修复。

## 验证

| 项目 | 结果 |
| --- | --- |
| 源码与构建 | Go `vet` 与 `go test -race` 通过，Rust 27 个测试通过，Vite 构建与 macOS 发布包装通过，Android `assembleDebug` 与 APK 签名验证通过 |
| 公网 TLS 与认证 | OpenSSL 验证 `voice.gpt60.com` 的 Let's Encrypt 证书和主机名。Android 模拟器日志报告标准验证，成功建立目录会话与点名桥接 |
| Android 配对与停止 | 在扫描页输入同一格式的配对载荷后无需再输入地址或选择目标，首页显示「已就绪」。新版本跨过两轮目录轮询后手动停止，正常回到「未启动」，崩溃日志为空 |
| Android PTT | 模拟器按住 6.5 秒发送 325 帧，松开后恢复「已就绪」 |
| Mac 虚拟麦克风 | 独立公网模拟音源发送 250 帧 440 Hz 测试音，`BlackHole 2ch` 输入峰值为 `0.244140`，停止后归零 |
| 二维码 | 缓存测试图可由 Android 使用的 ZXing 版本识别。模拟器相机预览的图像未能识别，实体手机当时未接入 ADB，现场对屏扫码待验收 |
| 交付 | 新 APK 已替换同网段下载文件。下载内容与构建产物的 SHA-256 均为 `f325ebccca832fd0a88608f789ed566bc51c65ebc453c744f7e60930aea0b243` |

## 复核入口

- `server/internal/server/http_relay.go:24` 接入升级与可信代理来源检查。生产旧二进制备份为 `/home/ubuntu/services/server/server-linux.pre-https`。
- `mac-app-tauri/src-tauri/src/controller.rs:408` 生成配对载荷。Mac 的更新前 App 和配置保存在目标机的 `/tmp/remote-voice-033-mac.n5vd4m/`。
- `android-app/app/src/main/java/com/remotevoice/app/MainActivity.kt:418` 将扫码结果保存并启动配对。`RelayClient.kt:184` 负责停止握手与连接，`RelayClient.kt:298` 在认证后从网络线程刷新目录。
