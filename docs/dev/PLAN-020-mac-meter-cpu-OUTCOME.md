# PLAN-020 结果文件：修复 Mac 客户端电平表动画导致的持续 CPU 占用

- 实施时间：2026-09-18（UTC+8）
- 基线 commit：1e9a589
- 计划文件：docs/dev/PLAN-020-mac-meter-cpu.md
- 实施分支：main（直接提交）

## 实施内容

用户报告「mac-app 几乎一直占用很大的 CPU」。根因与证据链见计划文件 §2/§3，要点：

- **主因**：装饰电平表（PLAN-018 引入）的 CSS 动画作用在 `height`（布局属性）上，
  12 根柱子无限循环、延迟错开。WebKit 无法合成布局属性动画，对端说话（PTT 按住）
  期间每帧都走 主线程样式重算→布局→绘制 全管线，WebView 进程常驻高 CPU。
  本应用即用户日常麦克风，说话≈常驻，与「几乎一直」吻合。
- **次因**：桥接期间 app-state 约 2Hz（每 25 音频帧一次，relay.rs:463）触发全量
  render，其中 renderConnectButton 无条件重建按钮并全文档重建 lucide SVG 图标。

### 变更清单（3 文件，+11/−4 行）

1. `mac-app-tauri/web/styles.css:195-202`：柱高固定为 `var(--h, 40%)`，
   `@keyframes mtr` 从 `height` 动画改为 `transform: scaleY(1)→scaleY(0.18)`
   （`transform-origin: 50% 100%`，向底部收缩，观感不变）；`.meter.off i` 补
   `transform: none` 防停用残留中间态；`prefers-reduced-motion` 规则语义不变。
2. `mac-app-tauri/web/app.js:56-58,168-169`：新增 `lastConnectIconState` 守卫，
   连接状态未变化时跳过按钮重建与全文档 `renderIcons()`（class/aria-label 仍每次
   同步，幂等）。
3. `mac-app-tauri/web/index.html:88`：删除调查中发现的重复 `id="meter"` 属性
   （无效 HTML，一并修正）。

Rust 侧零改动（计划 §4 第 4 条：网络/Fn/音频/事件频率路径均已核实无常驻开销）。

## 验证结果

| 项 | 结果 |
|---|---|
| `pnpm build`（vite） | ✅ 通过（dist/index.html 10.87 kB 等 3 产物） |
| 浏览器实测（IAB 打开 dist 产物，移除 off 类模拟说话态） | ✅ 3 次采样（间隔 ~400ms）：柱子 `height` 恒为 17.6719px 不变，`transform` scaleY 在 1→0.71→0.23 间变化——动画已完全由 transform 驱动，不再触发布局 |
| `cargo test` | ✅ 18 passed / 0 failed |
| `cargo clippy --all-targets` | ✅ 0 警告 |
| 截图 | ✅ 柱体绿色渐变、错落形态正常（IAB 预览环境） |

环境备注：本机 shell 非交互加载时 PATH 未含 `~/.cargo/bin`，`cargo` 会找到旧
`/usr/bin/rustc` 1.75 而报 edition2024 错误；需 `PATH="$HOME/.cargo/bin:$PATH"` 前缀
（与 HANDOFF「本机环境备忘」一致，shell 环境下需显式前缀）。

## 待真机复核（用户在 Mac 上）

1. 重新构建安装：`cd mac-app-tauri && pnpm tauri build`，替换正在运行的客户端。
2. 说话（按住手机 PTT）期间打开活动监视器：应看到 WebView 渲染进程
   （Remote Voice 的 WebContent/Helper）CPU 从常驻高位降至接近空闲。
3. 若**未说话/未桥接**时仍高 CPU，与本修复无关，按以下顺序排查：
   - 是否用 `pnpm tauri dev` 长期运行（dev 模式含 Vite/HMR 与调试 WebView，开销显著
     偏高）——日常使用请用 release 构建；
   - 活动监视器区分进程：`Remote Voice` 本体 vs `com.apple.WebKit.WebContent` vs
     `coreaudiod`（BlackHole 虚拟声卡走 coreaudiod）；
   - 必要时 `sample Remote\ Voice 5 -file /tmp/rv.txt` 取热栈定位。

## 观感差异说明

原动画每根柱子最低点统一为 14%；改后按比例缩至 `--h × 0.18`（30%–95% 的柱子
最低点约 5.4%–17%）。装饰属性，无功能影响；`prefers-reduced-motion` 行为不变。

## 追加记录

无（本次实施与计划一致，无计划外变更）。
