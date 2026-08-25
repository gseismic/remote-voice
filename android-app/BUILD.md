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

### 3.4 Gradle Wrapper

> **2026-08-26 起 wrapper 已提交进仓库**：clone 后直接执行 `./gradlew` 即可，
> 本小节仅当仓库中不存在 `gradlew` 时才需要。

wrapper 会把项目锁定的 Gradle 版本写进 `gradle/wrapper/`，此后任何机器上执行
`./gradlew` 都自动下载并使用同一版本。生成它需要一个本地 **Gradle ≥ 8.7**；
⚠️ 实测教训：Ubuntu apt 自带的 4.4.1 跑在 JDK 17 上会直接报出模糊错误
`Could not create service of type ScriptPluginFactory...`（老 Gradle 不认识新 JDK）。
且 `export PATH` **只对当前终端会话生效**，新开终端就会退回系统旧版——
所以推荐移到固定位置、用绝对路径调用：

```bash
# 1. 临时获取 Gradle 8.9
cd $(mktemp -d)
wget https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip gradle-8.9-bin.zip
mkdir -p ~/opt && mv gradle-8.9 ~/opt/     # 固定位置，避免放在 /tmp 重启后被清

# 2. 校验：应显示 Gradle 8.9 且 JVM 为 17
~/opt/gradle-8.9/bin/gradle --version
```

然后在工程目录生成 wrapper（之后日常构建只认 `./gradlew`）：

```bash
cd android-app
~/opt/gradle-8.9/bin/gradle wrapper --gradle-version 8.9
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

**2026-08-26 更新：环境已全部就绪，且完成首次全流程构建验证**
（`./gradlew assembleDebug` 成功，产物 `app/build/outputs/apk/debug/app-debug.apk` ≈ 816 KB）。

| 检查项 | 要求 | 当前状态 | 结论 |
|---|---|---|---|
| JDK | 17 | OpenJDK 17.0.20（2026-08-26 由 11 升级） | ✅ |
| Gradle | ≥ 8.7 | wrapper 已入库（锁定 8.9），日常只需 `./gradlew`；系统仍残留 4.4.1 勿直接调用，另有完整版在 `~/opt/gradle-8.9` | ✅ |
| Android SDK | platform 35 + build-tools 34.0.0 + platform-tools | `~/Android/Sdk` 下已装齐（2026-08-26 经 sdkmanager 补装） | ✅ |
| SDK 路径配置 | local.properties 或 ANDROID_HOME | 已创建 `android-app/local.properties` 指向 `~/Android/Sdk`（不入库） | ✅ |

即：本机现在 clone 后无需任何环境操作，直接执行 §3.5 的
`./gradlew assembleDebug` 即可构建。仅当换机器或重装系统时才需重走 §3.1–§3.4。

> 历史状态存档：2026-08-22 首次盘点时三项均不满足（JDK 11、Gradle 4.4.1、SDK 缺组件），
> 相关补齐步骤保留在 §3 各小节。

---

## 5. 常见问题排查

| 现象 | 原因与解决 |
|---|---|
| `SDK location not found` | 未配置 SDK 路径，见 §3.3 |
| `Could not create service of type ScriptPluginFactory...` | 跑了太老的 Gradle（如系统自带 4.4.1）+ 新 JDK 的组合。`gradle --version` 看实际版本，`which -a gradle` 查 PATH 里混入的旧版；改用绝对路径调用新版（见 §3.4） |
| `Android Gradle plugin requires Java 17 ...` 或 `Unsupported class file major version` | 当前 java 不是 17，`update-alternatives --config java` 切换 |
| `Could not find com.android.application:com.android.application.gradle.plugin:8.5.2` 或依赖下载超时 | 网络：需能访问 `dl.google.com`、`repo.maven.apache.org`；wrapper 下载慢可改 `gradle/wrapper/gradle-wrapper.properties` 中 URL 为腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` |
| `You have not accepted the license agreements` | 执行 `yes | sdkmanager --licenses` |
| `Failed to find target with hash string 'android-35'` | 未装 Platform 35：`sdkmanager "platforms;android-35"` |
| `adb: no devices` | 手机未开 USB 调试或数据线仅充电模式；`lsusb` 确认识别，Linux 下必要时补 udev 规则 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致的历史安装残留：`adb uninstall com.remotevoice.app` 后重装 |
| 手机提示禁止安装 USB 来源应用 | 开发者选项中允许「USB 安装」/「USB 调试（安全设置）」 |

---

## 6. 附录：为什么流程这么复杂——每一步存在的原因

### 6.1 根因：APK 不是单一二进制，而是「打包产物」

对比 relay：`go build` 一条命令就完事，因为 Go 语言的设计目标是把运行时和
所有依赖**静态链接成一个自包含的可执行文件**。

而 Android App 不同：APK 本质是一个 zip 包，里面装着

- **DEX 字节码**（Kotlin/Java 源码编译后的产物，运行在手机系统的 ART 虚拟机上）
- **编译后的资源**（布局/字符串/图标的二进制形式）
- **合并编译过的 AndroidManifest.xml**
- **签名信息**

且 App **不自带** Android 系统 API——它们由手机操作系统的 ART 运行时提供，
App 只携带自己的代码和资源。这意味着构建必须走一条多阶段流水线：

```
Kotlin 源码 ──kotlinc──> JVM 字节码 ──d8──> DEX ─┐
资源/图标   ──aapt2──> 编译后资源 ────────────────┼─> 打包(zipalign) ─> 签名(apksigner) ─> APK
Manifest   ──合并+编译──────────────────────────┘
```

每个环节是独立工具，于是需要「编排者 + 各环节工具集」，这就是下面四层软件的由来。

### 6.2 四层工具各司其职（缺一不可）

| 层 | 它是什么 | 为什么必须有 |
|---|---|---|
| **JDK 17** | Java 运行时 + 编译器 | Gradle 和 Kotlin 编译器本身就是跑在 JVM 上的程序；没有 JDK，构建命令根本无法启动 |
| **Android SDK** | android.jar（框架 API 存根）+ aapt2/d8/apksigner 等打包工具 + adb | JDK 只能把代码编译成普通 JVM 程序；要把代码变成 APK，必须靠 SDK 里这套 Android 专属工具链 |
| **Gradle** | 通用构建编排器（任务调度、增量编译、缓存） | AGP 只是它的插件；没有宿主，插件无处运行 |
| **AGP**（Android Gradle Plugin） | Android 官方 Gradle 插件 | 定义 `assembleDebug` 等任务，把上面那条流水线串起来 |

### 6.3 逐步骤解释（对应 §3 各小节）

| 步骤 | 为什么需要 |
|---|---|
| §3.1 装 **JDK 17**（而非任意版本） | AGP 8.x 自身按 Java 17 的类文件格式编译、要求运行于 ≥17 的 JVM；用旧 JDK 会报 `Unsupported class file major version`。选 17 不选更高：它是 AGP 8.5 的官方基线版本 |
| §3.2 装 `platforms;android-35` | 这是 compileSdk 35（`app/build.gradle.kts:8`）对应的**框架 API 存根**（android.jar）：你的代码能调用哪些 Android API、编译能否通过，由它决定。版本必须 ≥ 代码中用到的 API 级别 |
| §3.2 装 `build-tools;34.0.0` | aapt2/d8/apksigner 所在包；34.0.0 是 AGP 8.5 默认配对的版本 |
| §3.2 装 `platform-tools` | 提供 `adb`，用于安装与调试 |
| §3.2 `yes \| sdkmanager --licenses` | Google 法律条款要求先接受 SDK 使用协议；接受状态记录在 SDK 的 `licenses/` 目录，AGP 构建时会检查该标记，缺失即报错 |
| §3.3 配 `ANDROID_HOME` 或 `local.properties` | 构建系统需要知道 SDK 装在哪。`local.properties` 因路径因机器而异所以不入库（已在 `.gitignore` 中排除） |
| §3.4 用 **≥8.7** 的 Gradle 生成 wrapper | AGP 与 Gradle 版本是硬绑定的（AGP 8.5 ↔ Gradle ≥8.7）：AGP 依赖新版 Gradle 才有的 API。Ubuntu apt 源里的 4.4.1 太老，根本不认识这些接口。另外生成 wrapper 本身需要一个现成的 gradle 可执行文件（先有鸡后有蛋），所以手动下载一次 8.9 |
| §3.4 为什么要 **wrapper**（gradlew） | wrapper 把项目锁定的精确 Gradle 版本写进仓库，任何人执行 `./gradlew` 都会自动下载同一版本——保证所有机器/CI 构建行为一致可复现。代价只在首次生成时付一次 |
| §3.5 `assembleDebug` 可直接安装、`assembleRelease` 不能 | Debug 包自动用本机自动生成的调试密钥库（`~/.android/debug.keystore`）签名；Release 包必须用**你自己的正式密钥**签名，未签名的 APK 会被手机 PackageManager 直接拒绝安装。本项目尚未配置签名（`app/build.gradle.kts:18-22`），所以 release 产物带 `-unsigned` 后缀 |
| §3.5 产物路径那么深 | Gradle 按 variant（构建类型 × 渠道）组织输出目录；本项目没配渠道，所以只有 `debug/`、`release/` 两层 |
| §3.6 手机要开「USB 调试」才能 adb install | Android 安全模型默认禁止 USB 侧载应用；开发者选项 + 授权弹窗（记录电脑 RSA 指纹）是用户显式同意机制。普通用户的安装渠道是应用商店，不走这条路 |

### 6.4 补充：三个 SDK 版本号为什么不一样

| 名称 | 本项目取值 | 含义 | 出处 |
|---|---|---|---|
| compileSdk | 35 | **编译时**能看到的最全 API——决定你能写什么代码 | `app/build.gradle.kts:8` |
| targetSdk | 35 | 向系统声明的「已适配到该行为版本」——决定系统对你启用哪些新行为约束 | `app/build.gradle.kts:13` |
| minSdk | 26 | 可安装的最低系统版本（Android 8.0）——低于它的设备装不上；本项目所用 AudioRecord/前台服务 API 自 26 起可用 | `app/build.gradle.kts:12` |

### 6.5 一句话总结

relay 的复杂度在**部署环境**（要公网 IP、防火墙、证书指纹），构建只需一条命令；
Android 相反——部署极简（装个 APK），复杂度全在**构建流水线**（多阶段、多工具、强版本绑定）。
但环境搭好之后，日常构建同样只是一条 `./gradlew assembleDebug`。

---

*文档编写：2026-08-22；修订：2026-08-26（增补 §6 原理附录、wrapper 入库、§3.4/§4/§5 按实测更新）。
构建参数若后续调整，以 `build.gradle.kts` 为准并同步更新本文档 §1。*
