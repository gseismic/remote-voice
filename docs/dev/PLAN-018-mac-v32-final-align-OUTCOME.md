# PLAN-018 结果: Mac 端剩余 V3.2 对齐

- 日期: 2026-09-18 13:16
- 对应计划: [PLAN-018-mac-v32-final-align.md](PLAN-018-mac-v32-final-align.md)
- 实施 commit: 见 git log（本文件提交所在 commit）
- 状态: 已完成

## 实施内容（mac-app-tauri/web 三件）

1. **状态胶囊可点**（index.html 无改动，app.js + styles.css）：`#status-cluster` 点击 =
   `toggleConnection`（连接/断开/错误重试，与底部按钮同语义），`cursor:pointer` + 按下琥珀描边
   反馈；底部大按钮保留（桌面窗口主 CTA + 设备状态行落点）——计划中已声明的有意偏差。
2. **永久秘密折叠**（index.html + styles.css + app.js）：标题行加「展开管理 ▾ / 收起 ▴」
   （#btn-fold）；掩码行 `••••-••••-••••` 常驻；`.permanent-row` 包进 `#perm-body` 默认收起，
   `.panel-permanent.open` 展开。#permanent-input/#set-permanent/#clear-permanent ID 不变。
3. **装饰电平表 + 音频状态行**（index.html + styles.css + app.js）：
   - `#meter` 12 柱（各带 `--h` 目标高度，交错 delay CSS 动画），仅 `bridged` 且无音频错误时
     动画，其余静默 6% 灰柱（`.off`）；`prefers-reduced-motion` 下禁用动画。
   - `#audiost` 三分支文案：未连接 / 等待手机上线 / 桥接「实时接收中 · 0 丢帧」；
     有 `audio_error` 时显示「音频输出异常（详见下方提示）」（错误条仍是 audio-alert）。
   - `renderAudioMeter(status)` 在 render() 内调用；真振幅数据源为 index.html 开放问题（未定），
     当前为装饰动画，与原型同形。

## 验证
- `pnpm build` 通过（vite，含全部新 JS）。
- headless Chrome 截图（dist + 本地静态服务，820×1000）：
  - 默认态：音频卡含静默电平表 + 「未连接」；永久秘密折叠（掩码 + 未设置 + 展开管理）；
  - 展开态：永久秘密展开显示新秘密输入行与保存/停用按钮。
- 截图所用临时改动（perm-body display:block）已从 dist 与 web/ 源文件还原并 grep 复核（0 命中）。
- 静态服务与临时进程已清理。

## 未覆盖 / 后续
- 桥接态电平表动画、音频错误态、胶囊点击连接的实机验证需真实连接（用户指示联测后置）。
- 至此 V3.2 设计（android.html/mac.html/index.html）已完整落地两端代码：
  Mac=PLAN-016+018，Android=PLAN-017。
