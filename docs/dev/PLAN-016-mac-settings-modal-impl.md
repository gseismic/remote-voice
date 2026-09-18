# PLAN-016: Mac Tauri 客户端实施「低频连接设置移出首页」（按 PLAN-015 定稿原型）

- 日期: 2026-09-18 11:26
- 基线 commit: feedd23
- 状态: 待实施
- 上游设计: [PLAN-015-mac-settings-modal.md](PLAN-015-mac-settings-modal.md)（原型已由用户确认："看到了，为我实施代码"）

## 1. 现状勘察（真实客户端 mac-app-tauri/web）

- 首页常驻「RECEIVER PROFILE · 连接设置」面板（`web/index.html:75-124`）：服务器地址、设备名、
  音频输出、临时秘密有效期四个字段 + 「重启后保持临时秘密」开关 + 「保存设置」按钮，
  面板头还有 清除服务器信任 / 刷新音频设备 两个图标按钮——与原型改版前同病：低频配置占据首页。
- 后端（本次**不改**）：
  - `controller.rs:276-350` `save_settings`：`config::parse_server` 校验地址（config.rs:145），
    **已连接时保存自动 `connect_inner()` 换线重连**（L346-348）——与原型行为一致，前端只需触发。
  - 命令签名 `controller.rs:724-735`（server/name/audio_device/temp_duration/keep）与前端
    `readSettings()`（app.js:278-286）字段对应，元素 ID 不搬名字即不破坏。
- 前端现状要点：
  - `toggleConnection`（app.js:266-276）连接前自动 `save_settings(readSettings())`——首次使用
    "填地址→点连接"流依赖表单元素存在；元素移入弹窗后 ID 不变即兼容。
  - `render()`（app.js:190-195）用 `setValueIfIdle` 回填 #server/#device-name 等表单值。

## 2. 信息分层（对齐 PLAN-015 定稿）

| 层 | 内容 | 去处 |
|---|---|---|
| P1 | 配对信号带 | 首页（不动） |
| 中频 | 中继服务器地址/连接状态（一眼可见） | 首页新增 RELAY SERVER 单行只读卡，整卡可点 |
| 中频 | 音频输出设备 + 音频错误条 | 首页新增 AUDIO OUTPUT 卡（开会前确认/切换） |
| 低频 | 服务器地址、设备名、临时秘密有效期、重启保持临时秘密、清除服务器信任 | 「连接设置」模态弹窗 |
| 不做 | RTT 显示 | 真实客户端状态无 RTT 字段，不为展示扩后端 |

弹窗收录有效期/keep-temp/清除信任的理由：同属低频设置，收进同一入口后首页不再残留任何常驻表单；
清除信任是危险低频操作，从面板头图标降级为弹窗底部文字按钮（仍带 window.confirm）。

## 3. 变更清单（仅 web/ 三件，Rust 零改动）

### web/index.html
1. 删除 `panel-settings` 常驻面板（L75-124）。
2. `content-grid` 左列 → RELAY SERVER 面板：eyebrow `RELAY SERVER`、h2 `中继服务器`、
   单行 = 状态点 + 地址（#relay-summary）+ 说明（#relay-detail）+ 「修改」按钮（#open-settings，
   button-accent）；整卡可点（.panel-relay），空态显示「未配置服务器 / 点击填写服务器地址」。
3. 右列 → AUDIO OUTPUT 面板：h2 `音频输出`，panel-actions 放 刷新音频设备（#refresh-audio），
   字段仅 #audio-device select + #audio-alert（hidden 逻辑不变）。
4. body 末尾新增弹窗 #settings-overlay：标题（eyebrow RECEIVER PROFILE / h2 连接设置）+ ×（#close-settings）；
   字段 #server、#device-name、#temp-expiry-choice、#keep-temp（全部沿用原 ID）；错误行 #settings-error；
   底部 = 清除服务器信任（#clear-trust，danger 文字按钮）+ 取消（#cancel-settings）+ 保存设置（#save-settings，button-primary）。

### web/styles.css
1. `.panel-relay`（cursor:pointer + hover 琥珀边框/背景微亮）、`.relay-row` 布局、状态点颜色随 data-connected。
2. `.modal-overlay`（fixed 遮罩，z-index 10）+ `.modal-dialog`（--panel 背景、8px 圆角、宽 min(440px,92vw)、
   阴影）+ `.modal-head`/`.modal-close`/`.modal-error`（coral）/`.modal-footer`/`.text-danger`。
3. `.toast` z-index 3 → 20（保存成功 toast 需浮于弹窗之上）。
4. 响应式：≤620px 弹窗字段单列（沿用 .settings-fields 网格类即可自动继承现有断点规则）。

### web/app.js
1. `openSettings()/closeSettings()`：open 回填当前 state 值并清错误行、focus #server；
   关闭途径 ×/取消/遮罩点击/Esc（仅弹窗开着时）。
2. 新增 `editingSettings` 标志：弹窗打开期间 `render()` 跳过对 #server/#device-name/
   #temp-expiry-choice/#keep-temp 的回填——修复「连接中频繁 app-state 事件把用户未提交编辑冲掉」
   的真实缺陷（原首页表单同样存在，focus 保护只护住输入框聚焦时）。
3. `saveSettings()`：成功 → 关弹窗 + toast（连接中保存后端已自动重连，toast 文案
   「设置已保存，正在按新设置重连」；未连接「设置已保存」）；失败 → 错误写入 #settings-error
   并保持弹窗打开（不用 runAction 的纯 toast 路径）。
4. `toggleConnection()` 前置：server 为空 → openSettings() + toast「请先填写服务器地址」，不再
   依赖后端 invalid-server 报错兜底（后端校验仍在，双保险）。
5. `render()` 增补：#relay-summary = state.server || 未配置服务器；#relay-detail 三分支
   （未配置 / 未连接「点「连接」开始 · 点击本卡修改」 / 连接中「保存修改将自动换线重连」）；
   data-connected 驱动状态点颜色。
6. 保留：clear-trust 的 window.confirm、readSettings 元素 ID、setValueIfIdle 逻辑。

## 4. 验证

1. `pnpm build`（vite build，ESM 解析可抓语法错误）。
2. `pnpm dev` + headless Chrome 截图：默认态（无常驻表单、两新卡布局）、
   弹窗态（临时给 overlay 加 open 类截图后还原，不提交临时改动）。
3. 人工核对：全 ID 保留清单（server/device-name/audio-device/temp-expiry-choice/keep-temp/
   save-settings/clear-trust/refresh-audio/audio-alert/toast/connect-toggle…）。

## 5. 自查记录（已查 2 遍）

1. ~~弹窗字段移位是否破坏 readSettings/render？~~ 全部 ID 不变，兼容；已列入第 3 节核对。
2. ~~已连接时保存要不要前端自己 disconnect+connect？~~ 不要——后端 save_settings 已内置
   （controller.rs:346-348），前端重复触发会双重换线；前端只关弹窗+提示。
3. 连接中 state 事件高频回填冲掉弹窗内未提交编辑 → editingSettings 标志（第 3 节 2），这是
   对既有缺陷的修复而非新逻辑。
4. audio-alert 移卡后仍在首页可见（render 的 hidden 切换不受影响），符合状态矩阵"错误条紧贴音频卡"。
5. 弹窗打开时音频事件仍会 render 整页——relay 行/日志正常更新，只有弹窗字段受保护，无冲突。
6. ≤420px 断点下 .settings-footer 变纵向、按钮全宽——弹窗 footer 复用 .settings-footer 需确认
   该规则对弹窗布局可接受（按钮全宽纵排在窄屏弹窗内是合理形态，不另造规则）。

## 6. 实施后待办

- 结果文件 `PLAN-016-mac-settings-modal-impl-OUTCOME.md`；更新 INDEX.md；commit + push。
