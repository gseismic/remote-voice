# PLAN-037 Android 公网连接阶段结果

- 计划 [PLAN-037-public-android-device-validation.md](PLAN-037-public-android-device-validation.md)
- 实施于 2026-11-24（Asia/Shanghai），基线 `9ec0217`

## Android 兼容修复

- 扫码和粘贴配对码共用的解码原先调用 Android API 33 新增的 `URLDecoder.decode(String, Charset)`。将其替换为 API 26 已具备的编码名重载。APK 的最低版本为 API 26。 [ConfigParser.kt](../../android-app/app/src/main/java/com/remotevoice/app/ConfigParser.kt#L76)
- 清单把相机和自动对焦显式声明为可选，符合固定焦距回退和手工输入配对码的行为。 [AndroidManifest.xml](../../android-app/app/src/main/AndroidManifest.xml#L11)

## 已验证

- Android Lint 在原版本检出两个错误，修复后为 0 错误。`lintDebug`、`assembleDebug`、APK 签名验证、Go `vet` 与竞态测试均通过。编译产物调用 `URLDecoder.decode(String, String)`，APK 清单将两项相机特性标为非必需。
- 测试覆盖 HTTPS 与旧格式配对码、转义字符、中文名称以及格式错误。ZXing 3.5.3 从旋转的亮度帧识别配对码，返回正确的 HTTPS 地址和设备 ID。
- Android 35 模拟器装入最终 APK，分别用目录点选和扫码页粘贴配对码连接 `voice.gpt60.com`，两次到达「已就绪 · MacdeMacBook-Pro」。公网中继的 HTTPS 升级、登录和目录读取通过。
- 已恢复局域网 APK 下载服务并更新文件。下载与本地 APK 的 SHA-256 一致，为 `0ebfc996a9bbffa22a087b7a0c2bee8ebb1347aa02a353e0800ab7e00b0fbf9c`。 [下载 APK](http://192.168.1.103:19433/Remote-Voice.apk)

## 待验收

- Redmi 真机 USB 接口通过了短暂的 ADB 握手，随后内核报 `-71` 并失去 USB 设备，尚未读取真机连接日志和系统版本。已请求无线调试配对信息。Android 12 镜像下载超时，API 26 到 32 的变更已通过 SDK API 资料、Lint 和编译结果核实，运行时验证留待设备连接。
- 模拟器摄像头显示的画面未能解码有效的配对图，光学扫码留待实体手机联测。
- Mac 已连接中继。直接发送 TALK 后未观察到 Fn 按下，Mac 系统日志显示辅助功能授权仍绑定旧代码签名。已移除临时副本的应用注册并打开正确的系统面板，实际按下和释放在用户为 `/Applications/Remote Voice.app` 保存授权后复测。
