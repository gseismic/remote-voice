# Android App 编译与安装指南

本文档说明如何在本地把 `android-app/` 编译为 APK 并安装到手机。
适用代码基线：`app/build.gradle.kts`（v0.1.0）。

> 快速回顾：本 App 为零第三方依赖的 Kotlin 前台服务采音推流端，
> 架构与部署全貌见根目录 `../README.md`。

---

## 1. 环境要求

| 要求 | 版本 | 出处 |
|---|---|---|
| JDK | **17** | `app/build.gradle.kts:25`（Java 17 编译目标），且 AGP 8.5 强制要求 |
| Android Gradle Plugin | 8.5.2 | `build.gradle.kts:2` |
| Kotlin | 2.0.20 | `build.gradle.kts:3` |
| Gradle | **≥ 8.7**（推荐 8.9） | AGP 8.5 的最低兼容线 |
| Android SDK Platform | **35** | `app/build.gradle.kts:8`（`compileSdk = 35`） |
| Build-Tools | 34.0.0（AGP 8.5 默认） | 未显式指定时由 AGP 决定 |
| 目标手机系统 | Android 8.0+ | `app/build.gradle.kts:12`（`minSdk = 26`） |

---

## 2. 方案 A：Android Studio（最省事，推荐新手）

1. 安装 [Android Studio](https://developer.android.com/studio)（自带匹配的 JDK 与 SDK 管理器）。
2. 打开工程：`File → Open` 选择 `android-app/` 目录（注意是子目录，不是仓库根目录）。
3. 首次打开按提示点击同步（Sync），Studio 会自动下载缺失的 SDK Platform 35 与 Build-Tools。
4. 菜单 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`。
5. 产物位置：`android-app/app/build/outputs/apk/debug/app-debug.apk`。

连接手机后也可直接点工具栏 ▶ 运行按钮自动安装启动。

---

## 3. 方案 B：纯命令行编译（Linux / macOS）

### 3.1 安装 JDK 17

```bash
# Ubuntu/Debian
sudo apt update && sudo apt install -y openjdk-17-jdk
sudo update-alternatives --config java   # 若存在多版本，选 17

# macOS (Homebrew)
brew install openjdk@17

# 验证 —— 必须显示 17.x
java -version
```

### 3.2 安装 Android SDK（cmdline-tools 方式，无需装 Studio）

```bash
# 目录约定：~/Android/Sdk，下文均以此为例
mkdir -p ~/Android/Sdk/cmdline-tools && cd $(mktemp -d)

# 下载命令行工具（版本号可能更新，以官方页面为准：
# https://developer.android.com/studio#command-line-tools-only ）
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip \
    -d ~/Android/Sdk/cmdline-tools && \
    mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export ANDROID_HOME=~/Android/Sdk          # 建议写入 ~/.bashrc 或 ~/.zshrc
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 接受全部许可
yes | sdkmanager --licenses

# 安装编译所需组件
sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
```

### 3.3 让 Gradle 找到 SDK（二选一）

```bash
# 方式一：环境变量（同上 export ANDROID_HOME）
# 方式二：在 android-app/ 下创建 local.properties（不要提交该文件）
echo "sdk.dir=$HOME/Android/Sdk" > android-app/local.properties
```

### 3.4 生成 Gradle Wrapper

仓库未提交 `gradlew`，首次需要用本地 Gradle 生成。**注意：系统的 Gradle 必须 ≥ 8.7**
（可用 `gradle --version` 确认；Ubuntu apt 装的 4.x 不行）。若没有新版 Gradle：

```bash
cd $(mktemp -d)
wget https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip gradle-8.9-bin.zip
export PATH=$PATH:$(pwd)/gradle-8.9/bin
gradle --version    # 应显示 8.9
```

然后在工程目录生成 wrapper（之后不再依赖这份手动下载的 Gradle）：

```bash
cd android-app
gradle wrapper --gradle-version 8.9
```

### 3.5 编译

```bash
./gradlew assembleDebug        # Debug 包（可直接安装）
# ./gradlew assembleRelease    # Release 包（未签名，见 §3.6 说明）
```

产物路径：

| 变体 | 路径 |
|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release-unsigned.apk` |

### 3.6 安装到手机

```bash
# 手机开启「开发者选项 → USB 调试」后插线，确认设备可见
adb devices

adb install app/build/outputs/apk/debug/app-debug.apk
# 覆盖升级加 -r：adb install -r app/build/outputs/apk/debug/app-debug.apk
```

无线调试（Android 11+，可选）：开发者选项 → 无线调试 → 配对后
`adb pair <ip:port>` 再 `adb connect <ip:port>`。

> **Release 包为何不能直接装**：`app/build.gradle.kts:18-22` 未配置签名
> （`isMinifyEnabled = false`，无 signingConfig），产出的是 unsigned APK，
> 无法直接安装。日常自用请装 Debug 包；正式签名属后续工作。

### 3.7 首次启动配置

App 内依次填入（三项均来自服务器/Mac 侧部署，详见 `../README.md` §1–§2）：

1. 服务器地址：`<服务器IP>:9432`
2. Token：relay 启动所用 token 文件内容
3. 证书指纹：relay 首次启动打印的 64 位 hex

然后点开始推流。若 Mac 端外放，建议打开「回声消除」开关
（映射 `VOICE_COMMUNICATION` 音源）。

---

## 4. 本开发机（Linux）现状备忘

2026-08-22 在本机实测的环境状态，供在此机器上编译前对照：

| 检查项 | 要求 | 实测值 | 结论 |
|---|---|---|---|
| JDK | 17 | OpenJDK 11.0.29 (`java -version`) | ❌ 需 `apt install openjdk-17-jdk` |
| 系统 Gradle | ≥ 8.7 | 4.4.1 | ❌ 不能用它生成 wrapper，需按 §3.4 手动获取 8.9 |
| Android SDK | platform 35 + build-tools | `/usr/lib/android-sdk` 仅 platform-tools | ❌ 需按 §3.2 补装 |

即：在本机走方案 B 前，需先完成 §3.1–§3.4 三步补齐。

---

## 5. 常见问题排查

| 现象 | 原因与解决 |
|---|---|
| `SDK location not found` | 未配置 SDK 路径，见 §3.3 |
| `Android Gradle plugin requires Java 17 ...` 或 `Unsupported class file major version` | 当前 java 不是 17，`update-alternatives --config java` 切换 |
| `Could not find com.android.application:com.android.application.gradle.plugin:8.5.2` 或依赖下载超时 | 网络：需能访问 `dl.google.com`、`repo.maven.apache.org`；wrapper 下载慢可改 `gradle/wrapper/gradle-wrapper.properties` 中 URL 为腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` |
| `You have not accepted the license agreements` | 执行 `yes | sdkmanager --licenses` |
| `Failed to find target with hash string 'android-35'` | 未装 Platform 35：`sdkmanager "platforms;android-35"` |
| `adb: no devices` | 手机未开 USB 调试或数据线仅充电模式；`lsusb` 确认识别，Linux 下必要时补 udev 规则 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致的历史安装残留：`adb uninstall com.remotevoice.app` 后重装 |
| 手机提示禁止安装 USB 来源应用 | 开发者选项中允许「USB 安装」/「USB 调试（安全设置）」 |

---

*文档编写：2026-08-22；环境实测基于当日代码库状态。构建参数若后续调整，
以 `build.gradle.kts` 为准并同步更新本文档 §1。*
