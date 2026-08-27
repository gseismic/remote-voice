# android-app · Android 手机端

手机端采音程序（Kotlin，零第三方依赖）：前台服务 + TLS 指纹固定连接 server，
认证凭 Mac 显示的秘密（临时/永久），桥接后按住说话（PTT）推 48kHz/mono 音频。

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

1. 打开 App → 设置（右上角齿轮）：
   - 「导入配置串」粘贴 server 启动横幅的 `rv://<IP>:9432?f=<指纹>`（或手动填服务器/指纹）
   - 「添加设备」输入 **Mac 端显示的临时/永久秘密**（新增即激活）
   - （可选）编辑设备名/开关免提常开/回声消除
2. 返回主界面：**App 打开即自动连接**，底部大按钮 **按住说话**（松开即停）；
   状态条显示连接态，**点按状态条 = 连接/停止**；多台设备点芯片切换（切换即自动重连）

> 免提常开：适合会议场景（无需按住）；默认关闭（仅 PTT）。
> 手动停止后 App 不再自动拉起，点状态条恢复。

## 权限与安全说明

- 权限：录音（前台采音必需）、通知（Android 13+ 前台服务通知）
- 秘密仅存本机 SharedPreferences，网络层只传 SHA-256 hex；
  TLS 使用服务端证书指纹固定（`FingerprintTrustManager`），不信任 CA
