# PLAN-017: Android 端完整实施 V3.2 新设计（纯公网单链路 + 暗色控制台）

- 日期: 2026-09-18 12:40
- 基线 commit: b080afc
- 状态: 待实施
- 上游: docs/ui-design/v3/android.html（V3.2 评审版原型，用户拍板"把新设计完全实施，然后再考虑测试"）
- 关联: bee7135（V3.2 决定：Android 移除双地址/扫描是既有待办）；PLAN-018 将补齐 Mac 端剩余差距

## 1. 现状勘察（android-app @ b080afc）

| 维度 | 现状 | V3.2 目标（原型） |
|---|---|---|
| 连接链路 | 本地+远程双地址、本地优先多端点拨号（AudioStreamService.kt:113-128、RelayClient.kt:162-180）、UDP 扫描（LanDiscovery.kt 91 行、SettingsActivity btn_scan） | 单一服务器地址；本地测试=直接填局域网 IP |
| 默认服务器 | 43.139.226.138:9432（旧服务器，AudioStreamService.kt:480） | 118.193.40.160:9432（V3.2 部署机，原型同款） |
| rv:// 导入 | 独立"导入配置串"卡（activity_settings.xml:12-49） | 无独立卡；地址框直接接受 rv:// 格式（原型 hint） |
| 清除信任 | **无 UI**（仅 TrustStore 有存储，无 clear） | ADVANCED 卡一行 + 确认 |
| 主界面 | 单行 TextView 状态 + 菜单进设置；无中央态区 | brand 行+齿轮、状态胶囊（LED+主/副行）、中央态区（大字/转圈/波形+计时+帧数/错误面板+重试）、设备芯片、PTT 大圆钮 |
| 主题 | DayNight 浅色 Material（AndroidManifest.xml:13）、散落的 0xFF2E7D32 等硬编码色 | 暗色唯一（index.html 假设[已评审]）：bg #0e1116/panel #161b22/card #1a212b/line #2a313c/amber #f5b83d/green #3fb950/red #f85149/blue #58a6ff |
| 状态机 | STOPPED/CONNECTING/WAIT_PEER/STREAMING/ERROR（AudioStreamService.kt:22）+ PTT talking | 原型 idle→connecting→wait→live⇄hold、err；一一对应，**保留真实现的自动重连退避**（原型 err=停服是模拟简化，实机自动重连更合理——有意偏差） |

## 2. 变更清单

### 2.1 功能简化（V3.2 决定）
1. 删除 `LanDiscovery.kt`。
2. `RelayClient.kt`：构造参数 `endpoints: List<Endpoint>` → `host: String, port: Int`；删 `Endpoint` data class、`connectAndServe` 多端点循环（:162-180）、label 状态文案。
3. `AudioStreamService.kt`：删 KEY_SERVER_LOCAL 与 endpoints 构造段（:113-128），单地址 = prefs `server`（ConfigParser.parse 归一化，支持 rv://），无效即 failAndStop；`DEFAULT_SERVER` → `118.193.40.160:9432`；删 `KEY_SERVER_LOCAL` 常量。
4. `SettingsActivity.kt`：删 btn_scan/edit_local/showScanResults/导入配置串卡逻辑。

### 2.2 设置页重构（activity_settings.xml 重写 + SettingsActivity.kt 调整）
- 卡1 `SERVER`：标题「中继服务器地址」+ 单输入框 + hint「格式 IP:端口 或 rv://IP:端口；本地测试可填局域网 IP」；保存时 `ConfigParser.parse→format` 归一化（rv:// 直接可粘贴）。
- 卡2 `DEVICES`：设备列表 + 添加按钮（逻辑保留，样式对齐暗色卡片）。
- 卡3 `ADVANCED`：免提常开、回声消除（保留）+ **清除服务器信任**（新增：行按钮 + AlertDialog 确认；`TrustStore` 新增 `clear(prefs, serverKey)`，语义=清除当前输入地址归一化后的 serverKey 指纹）。
- 底部「保存并返回」。

### 2.3 主界面重构（activity_main.xml 重写 + MainActivity.kt 调整）
- brand 行：RV 方块 + 「远程麦克风/REMOTE MIC」+ 齿轮按钮（替代 options menu，menu_main 移除引用）。
- 状态胶囊：LED 圆点 + 主状态行 + 副行（非传输时显示 `服务器 addr`），点击 = 连接/停止/错误重试（沿用现有 toggleService 语义分发）。
- 中央态区（新增）：
  - STOPPED → 大字「准备就绪」+ 副文案「点状态胶囊连接服务器 / 秘密配对决定接哪台 Mac」
  - CONNECTING → 转圈
  - WAIT_PEER → 大字「已连中继」+「等待 Mac 上线」
  - STREAMING/hold → 波形（新 `WaveView` 自绘 10 柱动画，装饰性）+ 计时 + 「已发送 N 帧 · 48kHz」
  - ERROR → 错误面板（原因 = Status.text + 「重试」按钮）
- 计时：`AudioStreamService.Status` 新增 `startedAt`（进入 STREAMING 置 elapsedRealtime，离线/停止清零），pollTask 据此显示 mm:ss。
- 设备芯片、PTT 圆钮保留，样式换 tokens。

### 2.4 暗色主题 tokens
- 新建 `values/colors.xml`（上表 tokens）与 `values/themes.xml`：`Theme.Material.NoActionBar` 为父（固定暗色、无系统标题栏），windowBackground=bg、statusBarColor=bg；manifest theme 由 `Theme.DeviceDefault.DayNight` 切换（AndroidManifest.xml:13）。
- drawable 换 tokens：bg_card/chip/chip_active/chip_add/bg_ptt 更新；新增 bg_capsule（胶囊 999 圆角）、bg_errpanel（红系描边）。
- MainActivity 颜色常量（:389-392）换 tokens 值。

## 3. 有意偏差（原型 → 实机）
1. 错误后自动重连退避保留（原型 err 为停服示意）；仅 fatal（认证/指纹）停服等待用户重试——维持现有行为。
2. 波形/电平为装饰动画（index.html 开放问题"数据源"未定，真振幅暂不可得），与原型同形。
3. rv:// 导入不设独立按钮，保存时归一化（原型 hint 的格式约定）。

## 4. 验证
- `./gradlew assembleDebug` 通过（编译级验证）。
- grep 确认无 LanDiscovery/KEY_SERVER_LOCAL/43.139.226.138 残留。
- 人工核对主界面六态渲染分支与设置页三卡。
- 真机联测按用户指示后置；构建后不主动安装。

## 5. 自查记录（已查 2 遍）
1. RelayClient 构造签名变更只影响 AudioStreamService 一处（:181）；fakephone/server 协议不变。
2. KEY_SERVER 在两个 companion 重复定义（同名同值 "server"），保持现状不动（清理超范围）。
3. 清除信任用的 serverKey 必须与连接时一致（host 小写:port，TrustStore.serverKey 归一化），对"当前输入地址"清除——与保存后的地址一致（同一 ConfigParser 归一化）。
4. 移除 DayNight 后 AlertDialog 跟随 Activity 主题（Material 暗色），不会再出浅色对话框。
5. StatusState 枚举不变，通知/前台服务逻辑不动——网络与采音生命周期零改动，只动 UI 与配置读取。
6. pollTask 500ms 轮询结构保留，新增计时显示不引入新线程。

## 6. 实施后待办
- OUTCOME 文件 + INDEX.md + commit/push。
- 随后 PLAN-018：Mac 端剩余对齐（topbar 胶囊可点连接、永久秘密折叠、装饰电平表）。
