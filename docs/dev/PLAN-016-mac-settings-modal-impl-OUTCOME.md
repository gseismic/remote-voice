# PLAN-016 结果: Mac Tauri 客户端实施「低频连接设置移出首页」

- 日期: 2026-09-18 11:41
- 对应计划: [PLAN-016-mac-settings-modal-impl.md](PLAN-016-mac-settings-modal-impl.md)
- 上游设计: PLAN-015（原型已获用户确认）
- 实施 commit: 见 git log（本文件提交所在 commit）
- 状态: 已完成

## 实施内容（Rust 零改动，仅 web/ 三件）

### mac-app-tauri/web/index.html

1. 删除首页常驻「RECEIVER PROFILE · 连接设置」面板（原 L75-124：服务器地址/设备名/音频输出/
   有效期四字段 + keep-temp 开关 + 保存按钮 + 面板头清除信任/刷新音频图标按钮）。
2. `content-grid` 改为三卡：
   - `RELAY SERVER · 中继服务器`（#relay-panel，整卡可点 + hover 琥珀高亮）：「修改」按钮
     （#open-settings）+ 状态行（.relay-dot 状态点 + #relay-summary 地址 + #relay-detail 说明），
     空态显示「未配置服务器 / 点击填写服务器地址」。
   - `AUDIO OUTPUT · 音频输出`：输出设备下拉（#audio-device）+ 刷新设备按钮（#refresh-audio）
     + 音频错误条（#audio-alert，仍留在首页，符合"错误条紧贴音频卡"）。
   - `LONG-LIVED ACCESS` 通栏（.panel-span），内容不变。
3. body 末尾新增「连接设置」模态弹窗（#settings-overlay，role=dialog + aria-modal）：
   服务器地址 / 设备名 / 临时秘密有效期 / 重启后保持临时秘密 / 错误行（#settings-error）/
   清除服务器信任（danger 文字按钮，保留 window.confirm）/ 取消 / 保存设置。
   **全部沿用原元素 ID**（server、device-name、temp-expiry-choice、keep-temp、save-settings、
   clear-trust、audio-alert 等），readSettings/render/bindEvents 零适配成本。

### mac-app-tauri/web/styles.css

1. 新增 .panel-relay（cursor+hover）、.panel-span、.relay-line/.relay-dot/.relay-copy/
   .relay-address/.relay-detail，状态点颜色由 data-connected 驱动（false 灰 / progress 琥珀 /
   true 绿 / fatal 珊瑚），色值全部复用现有 tokens。
2. 新增 .modal-overlay（fixed 遮罩，[hidden] 显式 display:none 防止 display:grid 覆盖）/
   .modal-dialog（宽 min(440px,100%)、--panel 背景、8px 圆角）/ .modal-head/.modal-close/
   .modal-error/.modal-footer/.modal-footer-actions/.text-danger。
3. .toast z-index 3→20，保证保存成功 toast 浮于弹窗之上；删除失效的 .settings-footer 规则，
   ≤420px 断点中 .settings-footer 改为 .modal-footer + .modal-footer-actions（窄屏弹窗按钮
   纵向全宽，与 bottom-bar 行为一致）。

### mac-app-tauri/web/app.js

1. `openSettings()/closeSettings()`：打开时回填当前生效值并清错误行、focus 服务器地址输入框；
   关闭途径 ×/取消/点击遮罩/Esc（仅弹窗打开时）。
2. 新增 `editingSettings` 标志：弹窗打开期间 render() 跳过对连接设置四项的回填——修复既有缺陷：
   连接中高频 app-state 事件会把用户未提交的编辑冲掉（原首页表单仅有 activeElement 保护，
   焦点在按钮上时仍会被冲掉）。
3. `renderRelayLine(status)`：状态点四态 + 地址/说明三分支文案（未配置 / 连接失败 /
   连接中「保存修改将自动换线重连」/ 未连接「点「连接」开始 · 点击本卡修改」）。
4. `saveSettings()`：成功 → render + 关弹窗 + toast（保存前已连接时文案「设置已保存，正在按新
   设置重连」——重连由后端 controller.rs:346-348 内置，前端不重复触发）；失败 → 错误写入弹窗内
   #settings-error 并保持弹窗打开。
5. `toggleConnection()` 前置：地址为空 → 打开弹窗 + toast「请先填写服务器地址」（后端
   invalid-server 校验仍在，双保险）；「连接动作同时提交当前设置」的首次使用流保留。
6. bindEvents 注册弹窗全套事件；clear-trust 的 window.confirm 保留。

## 验证

- `pnpm build`（vite）通过：1855 modules transformed，产物 dist/ 正常。
- `pnpm dev` + headless Chrome 截图（820×1100）：
  - 默认态：信号带 → 中继服务器卡 + 音频输出卡并排 → 永久秘密通栏 → 会话日志，首页无常驻表单，
    整页一屏可见；
  - 弹窗态：遮罩变暗、对话框居中，字段/开关/清除信任/取消/保存布局正确，风格与全应用一致。
  - 截图用的临时改动（去掉 overlay 的 hidden）已精确还原并复核（`git diff` 无残留）。
- 残留检查：web/ 与 dist/ 中无 settings-footer/panel-settings 残留；dev server 已停止、端口释放。

## 未覆盖 / 后续

- 真机 macOS 上的 Keychain、BlackHole 枚举、换线重连的实机行为未测（本机 Linux 仅验证前端构建
  与渲染；Rust 侧本次无改动，不影响 PLAN-010 已有验证结论）。
- 「保存修改将自动换线重连」的完整过程需真实连接才能观察（逻辑由后端既有实现保证）。
- Android 端 V3.2 简化（移除 LanDiscovery/双地址）仍为独立待办，不在本计划范围。
