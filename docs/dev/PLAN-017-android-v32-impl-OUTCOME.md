# PLAN-017 结果: Android 端完整实施 V3.2 新设计

- 日期: 2026-09-18 13:02
- 对应计划: [PLAN-017-android-v32-impl.md](PLAN-017-android-v32-impl.md)
- 实施 commit: 见 git log（本文件提交所在 commit）
- 状态: 已完成（编译级验证；真机联测按用户指示后置）

## 实施内容

### 功能简化（V3.2 决定落地）
1. 删除 `LanDiscovery.kt`（91 行 UDP 扫描）与 `res/menu/menu_main.xml`。
2. `RelayClient.kt`：构造签名 `(endpoints: List<Endpoint>, …)` → `(host, port, …)`；
   删 `Endpoint` data class、`connectAndServe` 多端点循环、`label` 文案；TOFU 仍按
   `TrustStore.serverKey(host,port)` 隔离，协议/心跳/退避重连/采音代次隔离全部不动。
3. `AudioStreamService.kt`：endpoints 构造段 → 单地址（prefs `server`，ConfigParser 归一化，
   无效 failAndStop）；`DEFAULT_SERVER` 43.139.226.138:9432（旧）→ `118.193.40.160:9432`
   （V3.2 部署机，与原型一致）；删 `KEY_SERVER_LOCAL`。
4. `TrustStore.kt`：新增 `clear(prefs, serverKey)`（设置页清除信任用）。

### 设置页重构（activity_settings.xml 重写 + SettingsActivity.kt）
- SERVER 卡：单一地址输入（接受 `IP:端口` / `rv://…`，保存时 ConfigParser 归一化，格式无效
  toast 拦截）+ 原型 hint（本地测试填局域网 IP）。原"导入配置串"独立卡与扫描按钮删除。
- DEVICES 卡：设备列表（激活态 ● 标注）+ 添加按钮，逻辑保留。
- ADVANCED 卡（新增第三卡）：免提常开、回声消除（迁移）+ **清除服务器信任**（新增，
  AlertDialog 确认后对当前输入地址的 serverKey 执行 TrustStore.clear）。
- 底部琥珀色「保存并返回」。

### 主界面重构（activity_main.xml 重写 + MainActivity.kt + 新 WaveView.kt）
- brand 行：RV 琥珀方块 + 「远程麦克风/REMOTE MIC」+ 齿轮按钮（替代 options menu）。
- 状态胶囊：LED（bg_led 运行时 tint：灰/蓝/琥珀/绿/红）+ 主状态行 + 副行（服务器地址），
  整体可点（连接/停止；ERROR 态语义=重试）。
- 中央态区（新增）：STOPPED/WAIT_PEER 大字、CONNECTING 转圈（ProgressBar indeterminateTint 蓝）、
  STREAMING 波形+计时+「已发送 N 帧 · 48kHz」、ERROR 面板（原因+重试按钮）。
- `WaveView.kt`（新）：10 柱装饰波形，传输时动画、空闲时静默短柱——装饰性，真振幅数据源
  为 index.html 开放问题、未定。
- `AudioStreamService.Status` 新增 `startedAt`（进入 STREAMING 打点 elapsedRealtime，离开清零），
  主界面据此显示 mm:ss 计时。
- 设备芯片/PTT 大钮（172dp、按住绿色描边）保留，样式换 tokens；颜色硬编码全部换 V3.2 tokens。

### 暗色主题
- 新建 `values/colors.xml`（bg/panel/card/line/tx/tx2/amber/green/red/blue 等 tokens，与
  docs/ui-design/v3/index.html 同源）与 `values/themes.xml`（`RVTheme` = Theme.Material.NoActionBar
  固定暗色，windowBackground/statusBar 同色）。
- AndroidManifest theme：`Theme.DeviceDefault.DayNight` → `@style/RVTheme`（放弃 DayNight，
  index.html 已评审假设"暗色为两端唯一主题"）。
- drawable 全部换 tokens：bg_card/bg_capsule/bg_field/bg_iconbtn/bg_errpanel/bg_led/bg_brand/
  bg_ptt（selector：按住绿描边）/chip 系。

## 验证
- `./gradlew assembleDebug` BUILD SUCCESSFUL（APK app/build/outputs/apk/debug/app-debug.apk，
  2026-09-18 13:00）。
- 残留检查：LanDiscovery / KEY_SERVER_LOCAL / 43.139.226.138 / DayNight / menu_main /
  edit_import / btn_scan 在 app/src 下零残留（仅 colors.xml 注释提及 DayNight 为说明文字）。
- 真机安装/联测按用户指示后置（构建产物已就绪，`adb install -r` 即可）。

## 与计划的偏差
无。计划第 3 节有意偏差三条（自动重连保留、装饰波形、rv:// 无独立按钮）均已按计划执行。

## 未覆盖 / 后续
- 真机行为验证（用户明确后置）：六态渲染、TOFU、PTT、免提/回声、清除信任、双设备切换。
- PLAN-018：Mac 端剩余 V3.2 对齐（topbar 状态胶囊可点连接、永久秘密折叠、装饰电平表）。
