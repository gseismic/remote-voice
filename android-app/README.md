# android-app · Android 手机端

手机端采音程序（Kotlin，使用 ZXing 扫码）。从 Mac 扫码连接中继与目标设备，桥接后按住说话（PTT）推送 48 kHz 单声道音频。

## 构建与安装

```bash
cd android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

- 构建环境（JDK 17 / Android SDK / Gradle wrapper）搭建与踩坑详见
  **[BUILD.md](BUILD.md)**
- 手机开 **USB 调试**；安装被拦按 BUILD.md 排查表处理

## 首次使用流程

1. 打开 App，点击「＋ 添加 → 扫码配对」，对准 Mac 上的配对二维码。首次扫码和连接分别需要相机与麦克风权限。
2. App 自动保存中继信息并连接二维码对应的 Mac。看到「已就绪」后按住说话，松开即停止传输。
3. 手动输入中继地址和服务器密码，或扫描旧配对码后，可在列表里选一台 Mac 连接。多台 Mac 已连接时可左右滑动切换。

点按状态条可以停止或恢复连接。再次扫码会启动配对连接。

## 权限与安全说明

- 服务器密码保存在本机应用数据中，认证上传规范化密码的 SHA-256。HTTPS 中继使用系统 CA 和主机名验证。
