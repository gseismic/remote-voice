# PLAN-038 Android 未配对时的连接入口结果

- 计划 [PLAN-038-android-unpaired-connection-entry.md](PLAN-038-android-unpaired-connection-entry.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `6d920ee`

## 修复

- Redmi 当时仅保存 HTTPS 地址，密码和目标为空。原来的启动检查返回时，手动按钮仍提示「连接中」。现在显示「未配对 · 点按扫码」，状态栏直接打开扫码页。「＋ 添加」保留手动方式。未配对时隐藏陈旧设备条目，按住说话会提示先配对。 [MainActivity.kt](../../android-app/app/src/main/java/com/remotevoice/app/MainActivity.kt)
- 使用 Mac 的配对载荷完成 Redmi 配对。新版覆盖安装保留该配置，App 和中继均显示已经建桥。

## 验证

| 范围 | 结果 |
| --- | --- |
| Android 35 模拟器 | 首次使用、取消扫码、仅有地址且留有旧目录缓存均通过。状态栏直达相机，识别配对码后保存目标并连接本地 Go 中继。 |
| Android 12 运行时 | 执行旧 APK 里的解析类时复现 `NoSuchMethodError`。执行新 APK 里的同一类时通过 HTTPS 与旧格式、转义字符、中文名称和无效输入用例。 |
| Redmi Android 13 | 真机从扫码页粘贴 Mac 格式的配对码并接入 `voice.gpt60.com`。覆盖安装最终 APK 后保留配置，状态栏停止后点一次即可重连。 |
| Mac 晚启动 | 在 Redmi 保持连接时退出目标 Mac 应用。手机显示「等待 Mac 上线 · 自动重连」，Mac 重启后自动恢复「已就绪」。 |
| 按住说话 | 最终 APK 在 Redmi 上分两轮分别发送 170 帧和 171 帧，中继统计与发送计数一致。 |
| 构建 | `lintDebug`、`assembleDebug`、APK 签名验证和 `git diff --check` 通过。 |

## Mac Fn 验收状态

Mac 解锁后测试仍未观察到 Fn 按下和松开。系统日志显示授权保存到了旧备份的签名 `0fd936b3…`。正式安装包使用 `a2fba748…`。已将旧备份保存为压缩包，通过完整性检查后移出系统可解析的 App 路径。正式安装包可正常启动并连接公网，Fn 仍待系统保存正式安装包的授权后复测。

## APK

本地构建、Redmi 安装包和局域网下载文件的 SHA-256 相同，为 `e917ce50f7192326ed14196ae25ae21b401585a44e75aa03668d30579720535b`。 [下载新版 APK](http://192.168.1.103:19433/Remote-Voice.apk)
