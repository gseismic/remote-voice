# PLAN-020：修复 Mac 客户端电平表动画导致的持续 CPU 占用

- 制定时间：2026-09-18（UTC+8）
- 基线 commit：1e9a589
- 关联需求：用户报告「mac-app 几乎一直占用很大的 CPU」
- 关联设计：docs/ui-design/v3/（V3.2 装饰电平表，PLAN-018 引入）

## 1. 用户意图

用户在 Mac 真机上使用 Tauri 客户端（PLAN-019 后真机验收阶段），观察到应用几乎
一直占用很高的 CPU。真实意图：**查明根因并消除常驻 CPU 消耗**，而不是降低某一次
操作的瞬时开销。

## 2. 侦察结论（证据）

对 mac-app-tauri 全部常驻执行路径逐一排查，按「持续/逐帧」标准筛选：

| 路径 | 证据 | 结论 |
|---|---|---|
| 网络线程（tokio select 事件驱动） | mac-app-tauri/src-tauri/src/relay.rs:373-390 | 空闲时挂起，仅 10s PING（relay.rs:23），可排除 |
| Fn 监听（CGEventTap + CFRunLoop） | mac-app-tauri/src-tauri/src/keyinject.rs:141-181 | 事件驱动无轮询，可排除 |
| 音频输出（cpal 回调） | mac-app-tauri/src-tauri/src/audio.rs:282-310 | 回调驱动，仅桥接时运行，锁+出队开销极小，可排除 |
| 状态事件频率 | mac-app-tauri/src-tauri/src/relay.rs:462-464（每 25 帧→0.5s 一次）；Android TALK 仅在按下/松开沿发送（android-app/.../AudioStreamService.kt:301,321） | app-state 约 2Hz，非高频，可排除为主因 |
| 前端 1s 倒计时 | mac-app-tauri/web/app.js:433 | 每秒一次文本更新，可排除 |
| **装饰电平表 CSS 动画** | mac-app-tauri/web/styles.css:197-201 | **唯一无限循环、逐帧执行的动画**：`animation: mtr 1s ease-in-out infinite alternate`，keyframe 动画的是 `height`（styles.css:201 `@keyframes mtr { to { height: 14%; } }`），12 根柱子（web/index.html:88-89），且延迟错开（styles.css:198-200） |
| 前端全量重渲染 | mac-app-tauri/web/app.js:214-251 | 桥接期间 2Hz 全量重建 DOM，其中 renderConnectButton→renderIcons()（app.js:172,249）每次全文档重建全部 lucide SVG 图标 |

电平表动画的运行条件（web/app.js:194-195）：`off = !bridged || audio_error || !talking`，
即**只要手机按住 PTT 说话就一直运行**。本应用替代用户损坏的麦克风，说话即使用场景，
与「几乎一直占用」的表述吻合。

## 3. 根因分析

- **主因**：`height` 是布局属性（layout-triggering）。WebKit 无法将布局属性动画放到
  合成器线程，每帧都要走 主线程样式重算→布局→绘制 全管线；12 根柱子延迟彼此错开，
  失效几乎连续不断；在 ProMotion 120Hz 屏上帧率翻倍，开销同步翻倍。WebKit 进程
  （WebContent）因此长期保持一核的百分之几十——用户侧表现为「mac-app 占用很大 CPU」。
  该动画是 PLAN-018 的**纯装饰**实现（styles.css:193 注释自认「真振幅数据源为开放问题，
  当前为装饰动画」），却常驻说话全程，收益/成本完全失衡。
- **次因（顺手治理，非主目标）**：桥接期间 2Hz 全量重渲染中，renderConnectButton 无条件
  `replaceChildren`+全文档 `renderIcons()`（app.js:160-173,249）。连接状态未变时这些工作
  全部是重复劳动。量级小于主因，但同属「状态没变也逐帧干活」。
- **明确排除**：Rust 侧各线程空闲均挂起（见 §2）；app-state 2Hz 不高；debug/dev 构建
  （pnpm tauri dev）会导致整体偏慢属使用方式问题，不在本计划范围，但在结果文件中
  给出用户侧复核方法。

## 4. 方案比较

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 改合成器友好动画（选定） | 柱高固定为 `var(--h)`，keyframe 改为 `transform: scaleY()`，`transform-origin: bottom` | transform/opacity 由合成器线程执行，主线程空闲，说话期间 CPU 趋近 0；视觉形态基本不变（柱子仍高低起伏向底部收缩） | 各柱最小高度由固定 14% 变为 `--h×系数`（装饰属性，可接受，见自查 2） |
| B. 去掉动画/大幅降频 | 动画改为 2s+steps 或移除 | 改动最小 | 界面观感明显退化；steps 逐帧跳变仍触发重绘，治标不治本 |
| C. 真实电平数据驱动 | Rust 上报真实振幅，JS rAF 驱动 | 数据真实（本来就是开放问题） | 引入 rAF 逐帧 JS+跨进程事件，又一个常驻开销源；超出「修 CPU」范围 |

选 A：以最小改动消除逐帧主线程工作；观感保持「装饰电平表」意图。
次因治理：renderConnectButton 以 `connected` 布尔为守卫，状态不变时跳过重建与全文档
图标重建（app.js）。图标/文案只依赖该布尔（连接↔断开），守卫充分。
顺带修正：web/index.html:88 元素重复声明了两次 `id="meter"`（无效 HTML，调查中发现），
删除重复项。

## 5. 变更清单

1. `mac-app-tauri/web/styles.css`（197-201, 202 行区域）：
   - `.meter i`：`height` 固定为 `var(--h, 40%)`（off 态仍由 `.meter.off i` 覆写为 6%）；
     增加 `transform-origin: 50% 100%`；
   - `@keyframes mtr`：改为 `from { transform: scaleY(1); } to { transform: scaleY(0.18); }`
     （0.18 ≈ 原 14% 最低点相对柱均高 64% 的比例，观感接近）；
   - `.meter.off i`：补 `transform: none`（停用时强制平铺，防动画残留中间态）；
   - prefers-reduced-motion 规则（styles.css:273-275）语义不变，无需改动。
2. `mac-app-tauri/web/app.js`：
   - 新增模块级 `lastConnectIconState`；renderConnectButton 在 `connected` 未变化时
     跳过 replaceChildren/append/renderIcons（classList/aria-label 仍每次同步，幂等且廉价）。
3. `mac-app-tauri/web/index.html:88`：删除重复的 `id="meter"` 属性。
4. Rust 侧不改（事件频率与空闲路径已核实无问题，见 §3 排除项）。

## 6. 自查（3 遍）

- **第 1 遍（设计缺陷）**：scaleY 后 off 态切换是否残留缩放中间态？→ 已在 `.meter.off i`
  显式 `transform: none`；且 `animation: none` 时 transform 回到无变换，双保险。
  动画从 off→on 重新启动：animation 属性从 none 恢复为 mtr，从头播放，行为与改前一致。
- **第 2 遍（逻辑冲突）**：柱子 `flex: 1; height: var(--h)` 固定后，`align-items: flex-end`
  （styles.css:196）+ `transform-origin` 底部，视觉上仍是「从底部起伏」。
  原 keyframe 只有 `to`（from 隐含为无变换/计算值）；显式写 from/to 保证 alternate 往返
  端点确定。reduced-motion 下 animation:none → 柱子静态显示 var(--h) 全高——与改前
  行为一致（改前 animation:none 时 height 同样停在 var(--h)）。
- **第 3 遍（遗漏）**：
  - `renderIcons()` 还有其他调用点吗？→ 仅 boot（app.js:432）与 renderConnectButton
    （app.js:172）；boot 在守卫变量初值 null 下必然执行首次构建，无遗漏。
  - 2Hz 重渲染其余部分（事件列表/音频选项/文本）是否也要守卫？→ 量级小、
    且「不抢先重构」：本计划只治 CPU 主路径，保持最小 diff。
  - Linux 预览模式（非 Tauri）动画行为一致，无需平台分支。
  - 是否需要真机验证？→ 需要：Mac 上说话期间看活动监视器 WebContent/Remote Voice
    CPU 是否显著下降（写入结果文件的验证清单）。

## 7. 验证方式

1. `cd mac-app-tauri && pnpm build`（vite 构建通过，无类型/打包错误）。
2. 浏览器打开 dist 产物或 dev 页面，用 DevTools 手动移除 meter 的 off 类，确认：
   柱子起伏动画正常、Performance 面板主线程无逐帧 Layout/Paint（改前有）、
   Rendering→Paint flashing 仅闪烁合成层。
3. `cargo test`（未动 Rust，回归确认 18 例仍绿）、`cargo clippy` 0 警告。
4. 真机（用户）：说话期间活动监视器对比改前改后 CPU。

## 8. 风险与回滚

- 风险：动画观感与原来存在细微差异（柱最低点按比例而非统一 14%）——装饰属性，可接受。
- 回滚：revert 本次 commit 即可，无数据/协议影响。
