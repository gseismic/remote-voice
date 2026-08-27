# PLAN-007 · 傻瓜式一键连接（对齐 v2 UI 设计稿）

- 日期: 2026-08-28
- 状态: 待实施
- 目标: 用户重申"傻瓜式一键连接，不要多个密码/复杂 token"，即文档
  docs/ui-design/v2/index.html 与 android-ptt.html 所示形态：
  Android 打开即已连接、无连接按钮；Mac GUI 启动即已连接注册。
- 依赖现状: mac-app（src/macapp）+ android-app 已完成包化/合并（PLAN-006）

## 一、现状差距（对照 v2 设计稿）

| # | 设计稿期望 | 当前实现 | 证据 |
|---|---|---|---|
| 1 | Android 主界面无连接/断开按钮，状态条为已连接常态 | 有「连接/断开」两按钮，每次手点 | android-app/.../res/layout/activity_main.xml:82-104；MainActivity.kt:99-112 |
| 2 | 设备芯片切换即重连 | 切换仅改激活态，正在跑的服务不重连 | MainActivity.kt:135-139；AudioStreamService.kt:102-117（startStreaming 固化 secretHex） |
| 3 | PTT 贴底，其下无控制行 | PTT 下方有 tip + 连接/断开行 | activity_main.xml:73-104 |
| 4 | Mac GUI 启动即"已连接中继" | 启动需手动填配置点连接 | mac-app/src/macapp/ui_main.py:339-359（_connect 手动触发） |
| 5 | 按住时波形/计时反馈 | 无（仅帧计数文本） | MainActivity.kt:38-46 poll 段 |

用户核心诉求 = #1/#2/#4（一键化），#3 顺手，#5 低优先级（波形类视觉可延后，本次做简易计时文本）。

## 二、改动方案

### Phase A · Android（一键连接）

- A1 主界面删除「连接/断开」按钮（activity_main.xml 该段删除 + MainActivity.kt 对应按钮逻辑删除）；
  PTT 下保留 tip 文本；PTT 仍为底部锚定。
- A2 自动连接：MainActivity.onResume → 若配置齐（有 active 设备）且非用户手动停止
  （prefs flag `user_stopped`）→ 请求权限后 `doStartService()`（已幂等）。权限拒绝→
  状态文本显示引导。`onRequestPermissionsResult` 成功后 doStartService（已有）。
- A3 状态条即连接开关：点按状态条 toggle（未运行→启动服务；运行中→ACTION_STOP 并写
  `user_stopped=true` 记录手动停止，onResume 不再自动拉起；再点一次启动并清 flag）。
  Toast 提示结果。
- A4 芯片切换即重连：chip click → activate → 若服务运行中：先 stopService（ACTION_STOP +
  延时 300ms）再 doStartService()；Toast"已切换到 X · 正在重连"。同时清 user_stopped（切换表示想用）。
- A5 简易反馈：传输中状态文本追加计时（Activity 侧由 poll 帧计数改为 按住时长秒数——
  用 Status.talking + setTalking 时间戳实现；波形后续另计划）。
- A6 strings.xml：新增/调整按钮文案（删除 start/stop 引用）、ptt_tip 更新。

### Phase B · Mac GUI（启动自动连接）

- B1 ui_main.py：`_connect()` 拆为 `_validate()/ _start_client()`；
  MainWindow 构造尾部：配置齐（server/fingerprint(64)/regkey 非空）时
  `QTimer.singleShot(0, self._connect_if_ready)` 自动连接并 state 自然推进。
  不打断已有「连接/断开」按钮（保留手动重连）。
- B2 ~~状态条点击重试~~：不做（保留手动「连接」按钮即满足重启场景，避免 QLabel 事件过滤器复杂度）。

### Phase C · 验证与文档

- C1 Android：`./gradlew assembleDebug`；无 adb 设备则交用户真机验证
  （本机无 adb 的设备时，验证以编译通过+代码 review 为界——沿用 PLAN-005 惯例）。
- C2 mac-app：pytest 全绿（无 GUI 自动化，offscreen 冒烟：MainWindow 构造后确认
  auto-connect 配置齐全时 state 进入 connecting——用 fake cfg + 指向本地测试 server 的回环测；
  简单方式：手工回归脚本验证 `_connect_if_ready` 分支）。
- C3 文档：README（android/mac-app 操作段更新为"打开即用/自动连接"）、HANDOFF 更新。
- C4 OUTCOME + INDEX 追加；commit+push。

## 三、不做（NON-goal）

- 不引入波形绘制（留待后续"音频可视化"计划；本次以计时文本代替）
- 不改 Android 后台服务架构/协议/secret 模型（一键连接=交互层变化）
- 不做 Mac 端"配置折叠卡片"等视觉重构（自动连接已达目的；配置卡保留供变更配置）

## 四、自查（计划三重检查）

- [ ] 一致性：A2/A3 的自动连接与"手动停止后不自动拉起"（user_stopped）与设计稿"已连接常态"矛盾吗？
      ——不矛盾：设计稿是常态，手动停止是用户显式动作，需记忆；再点恢复。
- [ ] 竞态：A4 切换时 stop→300ms→start 与 onResume 的自动启动可能并发——doStartService
     服务侧 clientThread 存活检查幂等；stop 为异步动作，双触发只多一次无害 restart。
     300ms 延时保证 ACTION_STOP 先 reach。
- [ ] 安全：自动连接不引入新密钥；无 token 类输入新增；权限（录音/通知13+）请求时机提前到
     onResume 首次——系统流程不变。
- [ ] 回归面：删除按钮后 pollTask 的 startBtn.isEnabled 逻辑要适配（A1）；status 文本加
     计时不破坏现有 fill framesSent 显示。
- [ ] 测试面：Android 无单测框架（零依赖现状）以 assembleDebug+review；mac-app 以现有
     21 例 + offscreen 冒烟为界。
