# PLAN-018: Mac 端剩余 V3.2 对齐（胶囊连接、永久秘密折叠、装饰电平表）

- 日期: 2026-09-18 13:06
- 基线 commit: d86a6e5
- 状态: 待实施
- 上游: docs/ui-design/v3/mac.html（V3.2 评审版原型）；PLAN-016 已完成"设置弹窗化"

## 1. 差距清单（mac-app-tauri/web @ d86a6e5 vs mac.html 原型）

| 原型 | 现状（PLAN-016 后） | 处理 |
|---|---|---|
| 点右上状态胶囊连接/断开 | 胶囊仅显示状态，连接在底部按钮 | 胶囊增加点击（同一动作的第二入口，`cursor:pointer`）；底部按钮保留（桌面窗口的大号 CTA，设备状态行也需要落点）——有意偏差 |
| 永久秘密折叠卡（展开管理 ▾ / 掩码行常驻） | 常驻输入行+两图标按钮，无折叠 | 改折叠卡：标题行+折叠钮、掩码+状态 tag 常驻、输入行默认收起 |
| 音频卡电平表 + 状态行（等待/实时接收） | 无 | 新增装饰电平表（12 柱，桥接时动画）+ 状态行文案 |

装饰电平表与波形同理：数据源（真实振幅）是 index.html 开放问题，先装饰对齐视觉。

## 2. 变更清单（仅 web/ 三件）

### index.html
1. `panel-audio`：电平表 `<div class="meter" id="meter">`（12 柱，各带 `--h`）+ 状态行
   `<p class="audiost" id="audiost">`（电平表置于下拉与 audio-alert 之间）。
2. `panel-permanent`：标题行加「展开管理 ▾」（#btn-fold，text-button 样式）；掩码行
   （••••-••••-•••• + #permanent-state tag）常驻；`.permanent-row` 包进 `#perm-body`（默认收起）。

### styles.css
1. `.status-cluster{cursor:pointer}` + `:active` 反馈。
2. `.fold`（text-button 变体）；`.perm-body{display:none}` `.panel-permanent.open .perm-body{display:block}`。
3. `.meter` 柱状动画（`@keyframes mtr` 高度交替 + 交错 delay，绿色渐变）与 `.meter.off`（静默 6% 灰柱）；
   `.audiost` 状态行（11px muted，`b` 绿色强调）。

### app.js
1. `#status-cluster` 点击 → `toggleConnection`（与底部按钮同语义：连接/断开/错误重试）。
2. 折叠开关：切换 `.open`，按钮文案「展开管理 ▾ ↔ 收起 ▴」。
3. `render()` 增补：电平表 `off` 切换（仅 `bridged` 且无 audio_error 时动）；
   `#audiost` 文案三分支（未连接「未连接」/连接中「等待手机上线」/桥接「实时接收中」，
   有 audio_error 时在电平表下保留既有 alert，状态行显示「音频输出异常」）。

## 3. 验证
- `pnpm build` 通过；`node --check` 提取 JS 语法。
- headless Chrome 截图默认态（折叠态+静默电平表）与 JS 注入临时态（展开+电平表）——截图临时改动还原。
- grep 确认 ID 引用齐全（btn-fold/perm-body/meter/audiost/status-cluster）。

## 4. 自查记录（已查 1 遍）
1. 胶囊点击与底部按钮并存：同一动作双入口，主 CTA 仍唯一（底部大按钮），胶囊是原型约定的快捷入口。
2. 折叠仅改布局不改逻辑：#permanent-input/#set-permanent/#clear-permanent ID 不动。
3. 电平表纯 CSS 动画，`prefers-reduced-motion` 全局已有规则覆盖 meter（补充到禁用清单）。
4. render() 每次重设 audiost 文案不与 toast 冲突（toast 独立元素）。

## 5. 实施后待办
- OUTCOME + INDEX.md + commit/push。
