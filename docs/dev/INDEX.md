# 历史计划执行文档

> 追加式记录：每行包含更新时间（精确到分钟）、计划文件与结果文件的对应关系及摘要。

---

- **2026-08-22 03:05** | [PLAN-001-voice-relay-mvp.md](PLAN-001-voice-relay-mvp.md) → [PLAN-001-voice-relay-mvp-OUTCOME.md](PLAN-001-voice-relay-mvp-OUTCOME.md)
  摘要：完成 MVP 全链路——Go 中转服务器（token 认证/role 配对/双向桥接/心跳超时/自签证书指纹固定）+ Python Mac 接收器（TLS pinning → BlackHole）+ Kotlin Android 前台服务采音 App + fakephone 调试工具。验证：go build/vet/test 全绿（12 个用例）；本地回环集成测试四项断言全过（认证、50 帧/秒桥接、指纹错配拒绝、错误 token 拒绝）。未尽：Android 真机编译联调（环境无 SDK）、git push（无远端）。设计依据：../design/voice-relay-20260822-mvp.md。

- **2026-08-22 04:45** | [PLAN-002-review-fixes.md](PLAN-002-review-fixes.md) → [PLAN-002-review-fixes-OUTCOME.md](PLAN-002-review-fixes-OUTCOME.md)
  摘要：Code Review 修复循环（3 轮收敛）。修复高危×2（role 注册 TOCTOU 竞态→原子占位+虚假离线防御；TCP_NODELAY 经 NetConn 解包失效）、中危×1（Python 并发发送锁）、低危×3，另修复复查中新发现的 Serve/Close 数据竞争（-race 实证）与 Close-before-Serve 阻塞边界。新增回归测试×3，全套 go test -race 15 用例全绿，回环集成四断言重验通过。

- **2026-08-26 02:45** | [PLAN-003-relay-observability-android-ux.md](PLAN-003-relay-observability-android-ux.md) → [PLAN-003-relay-observability-android-ux-OUTCOME.md](PLAN-003-relay-observability-android-ux-OUTCOME.md)
  摘要：双工作流。① relay 可观测性：修复"单次 TLS 握手失败杀死整个进程"致命缺陷（握手移入每连接 goroutine + Accept 暂时错误退避重试），全连接/认证/桥接/断开日志统一带 peer 地址，新增 10s 窗口音频流量统计与启动横幅 rv:// 配置串打印，新增防一击毙命回归测试。② Android 极简体验：主界面仅连接密码（可一键生成强密码），服务器/指纹/回声消除移入新设置页并预置默认服务器 43.139.226.138:9432，支持 rv:// 一行导入，状态五态着色+推流帧计数，主题 DayNight。验证：go -race 全绿、回环冒烟日志完整（454帧/10s）、assembleDebug 通过、真机安装 Success。需求变更：用户追加密码自设/默认服务器要求已并入计划 v2；否决弱秘密透明派生方案（伪安全）。

- **2026-08-26 04:10** | [PLAN-004-android-ptt-hifi.md](PLAN-004-android-ptt-hifi.md) → [PLAN-004-android-ptt-hifi-OUTCOME.md](PLAN-004-android-ptt-hifi-OUTCOME.md)
  摘要：Android PTT 交互优化。① 总览页 v2/index.html：删除装饰 gbar、收紧 pttzone 间距，按钮贴底。② 新增高保真稿 v2/android-ptt.html：单屏仅保留说话相关元素（状态栏/连接状态/波形/PTT 钮/手势条），拟真手机外框，按住即传（绿辉光+扩散环+计时）、松开即停，pointer capture 防滑出失灵。③ 设计文档同步：映射表 #6 底部锚定、新增决策 D6、验收口径补高保真标准。

  摘要：协议 v2 全量实施三段（commit 20c15ee/09f3edf/116db06）。① relay：注册表路由（Mac regkey 认证+REGISTER 秘密哈希热更；手机只带哈希）、限速防爆破（60s/5 次→1m/5m/30m 递增）、0x08/0x09 新帧、AUTH_ERR 原因码；② mac-client（PySide6）：临时/永久秘密卡+倒计时+Keychain+连接历史+EVENT 落档+回环集成（14 例）；receiver.py 同步 v2；③ Android：PTT 底部大按钮（按住即传/松开即停）+多设备单激活芯片+首次连接自动命名+免提开关+rv:// 无 t= 导入。
  备注：OUTCOME 文件为 PLAN-005-v2-protocol-clients-OUTCOME.md（本文件写错占位，实际未创建）——修正：本次结果记录于下方 06:00 追加的正常 OUTCOME。

- **2026-08-26 06:05** | [PLAN-005-v2-protocol-clients.md](PLAN-005-v2-protocol-clients.md) → [PLAN-005-v2-protocol-clients-OUTCOME.md](PLAN-005-v2-protocol-clients-OUTCOME.md)
  摘要：协议 v2 全量实施分三段（relay 20c15ee / mac-client 09f3edf / android 116db06），本次文档交付。
  验证：go -race 20 用例全绿；mac-client 14 例（含真实 relay 回环）全绿；receiver 5 例；assembleDebug 通过；
  relay-linux 静态产物 5.9MB。用户待执行：部署 relay + 两端升级客户端（v1 已不兼容）。

- **2026-08-26 15:42** | review 文档 [20260826-1540-REVIEW-13aadb1-v2-impl.md](20260826-1540-REVIEW-13aadb1-v2-impl.md)
  摘要：v2 实施评审（commit 13aadb1）。实测复现：H-1 mac-client 无心跳+读超时 10s（空闲 10s 断连/静默桥接断链）、
  H-2 指纹不符 FatalError 逃逸（线程死亡粘 CONNECTING）、M-1 server reg.name 数据竞争（-race 实证）、
  M-2 计划偏差 peer-offline 缺失（离线坍缩 invalid-secret 计入限速）。其余 L 级 5 + Info 6。修复计划见下条。

- **2026-08-28 10:32** | [PLAN-006-repo-restructure.md](PLAN-006-repo-restructure.md) → [PLAN-006-repo-restructure-OUTCOME.md](PLAN-006-repo-restructure-OUTCOME.md)
  摘要：三端目录重组 + Mac 侧包化（用户拍板命名：server/mac-app/android-app）。
  A) relay→server：module remote-voice/server、产物 server-linux、DEPLOY/BUILD 文档同步；
  B) mac-client+mac-receiver 合并 mac-app（src/macapp + pyproject 双入口 remote-voice-gui/remote-voice-recv），
  并修复 H-1（心跳线程+读超时恢复 40s）与 H-2（FatalError→ST_FATAL），21 测试全绿（含新增 2 回归）；
  C) 三子 README + 根 README 重写。验证：go -race 全绿、CLI 全链路冒烟（50 帧/s）、GUI offscreen 冒烟。
  遗留：M-2（peer-offline）待用户拍板；真机部署待执行。

- **2026-08-28 12:05** | [PLAN-007-one-click-connect.md](PLAN-007-one-click-connect.md) → [PLAN-007-one-click-connect-OUTCOME.md](PLAN-007-one-click-connect-OUTCOME.md)
  摘要：傻瓜式一键连接（对齐 v2 UI 设计稿）。Android：删除「连接/断开」按钮，打开即自动连接
  （user_stopped 记忆手动停止），状态条点击=连接/停止，设备芯片切换即自动重连，
  传输中显示按住秒数+帧计数；PTT 贴底。mac-app：配置保存后启动自动连接
  （_connect_if_ready，缺配置显示待配置提示）。验证：assembleDebug 通过、
  offscreen 冒烟确认 auto-connect 触发、pytest 21 例全绿。遗留：真机手感验证、
  波形可视化另计划、M-2 未拍板。

- **2026-08-28 15:00** | [PLAN-008-tofu-diagnostics.md](PLAN-008-tofu-diagnostics.md) → [PLAN-008-tofu-diagnostics-OUTCOME.md](PLAN-008-tofu-diagnostics-OUTCOME.md)
  摘要：指纹免输（TOFU 首次信任）与重连诊断可读化。Android：f= 可选、空指纹=TOFU
  （握手后自动记录并持久化、后续固定校验）、异常→中文原因（超时/拒连/DNS/指纹不符/AUTH 码）
  经状态条与通知展示；设置页指纹框改"服务器信任状态+清除信任"。mac-app：fingerprint=""
  同语义 TOFU + on_fingerprint 回调写回 config.json；指纹不符 fatal 文案含期望/实际前 12 位。
  DEPLOY §6.1 增客户端原因↔server 日志对照表。验证：assembleDebug 通过、
  pytest 22 例（新增 TOFU 回环用例）。遗留：M-2 未拍板、真机验证。

- **2026-08-28 16:56** | [PLAN-009-multi-mac-self-enrollment.md](PLAN-009-multi-mac-self-enrollment.md) → [PLAN-009-multi-mac-self-enrollment-OUTCOME.md](PLAN-009-multi-mac-self-enrollment-OUTCOME.md)
  摘要：协议升级 v3；Mac 首次自动生成并持久化设备身份，server 支持多 Mac 独立登记与设备级认证，
  不再要求新流程的全局 regkey；Mac/Android 常规流程不要求用户输入证书指纹，内部按 server 地址 TOFU；
  保留旧 v2 regkey 兼容。验证：Go race/vet/build、Mac pytest 26 例、Python 编译检查、Android
  assembleDebug、git diff --check 全部通过。未覆盖：两台实体 Mac、Android 真机、公网与 BlackHole 长跑。

- **2026-08-28 19:00** | [PLAN-010-tauri-mac-client.md](PLAN-010-tauri-mac-client.md) → [PLAN-010-tauri-mac-client-OUTCOME.md](PLAN-010-tauri-mac-client-OUTCOME.md)
  评审：[20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md](20260828-1900-REVIEW-bfb44b3-tauri-mac-client.md)。摘要：新增 Rust/Tauri Mac GUI，首次自动生成设备身份，复用 Keychain/文件存储，内部按服务器地址 TOFU，完成 v3 AUTH→REGISTER、心跳、重连、cpal 音频和完整状态 UI；修复重启历史恢复、TOFU 重置恢复、连接生命周期并发问题。验证：Rust 17 项、clippy、前端构建、Linux Tauri bundle、Go race/vet/build、Python 26 项、Android assembleDebug 全部通过。未覆盖：macOS 真机 Keychain/BlackHole、签名公证、实体多 Mac 公网长跑。

- **2026-08-28 19:05** | [PLAN-011-android-connection-followup.md](PLAN-011-android-connection-followup.md) → [PLAN-011-android-connection-followup-OUTCOME.md](PLAN-011-android-connection-followup-OUTCOME.md)
  摘要：修复 Android 在当前 v3 设计下添加 Mac 秘密后不触发或看似不响应的问题。新增主页面/设置页自动连接与串行重启、可读错误保留、连接与采音代次隔离、拨号 socket 清理；支持 `rv://` 地址、内部 TOFU、仅以麦克风权限作为启动前置条件，并修正多设备删除后的激活切换。验证：Android `clean assembleDebug`、Go 测试、Mac Python 测试、`git diff --check` 通过；无 Android 实体设备，公网/权限/前台服务未实测。

- **2026-09-17 03:45** | [PLAN-012-npm-to-pnpm.md](PLAN-012-npm-to-pnpm.md) → [PLAN-012-npm-to-pnpm-OUTCOME.md](PLAN-012-npm-to-pnpm-OUTCOME.md)
  摘要：Tauri 前端包管理器统一为 pnpm（用户指令"凡涉及 npm 全部改为 pnpm"）。tauri.conf beforeDev/BuildCommand、两个 README、设计文档命令全部切换；package.json 增加 packageManager 固定 pnpm@10.5.2；package-lock.json 删除、pnpm-lock.yaml 入库。验证：pnpm install（offline）、pnpm build、node --check、`pnpm run tauri build` 产出 deb/rpm/AppImage 全部通过。顺带修复开发机环境并记入 HANDOFF：PATH 中旧 cargo 1.75 挡 rustup 1.98；crates.io Fastly CDN 不通，用户级 ~/.cargo/config.toml 配 rsproxy 镜像（不入仓库）；pnpm 在线安装挂起改用 offline store。历史文档保留 npm 记载不改写。

- **2026-09-17 06:05** | [PLAN-013-lan-discovery-dual-address.md](PLAN-013-lan-discovery-dual-address.md) → [PLAN-013-lan-discovery-dual-address-OUTCOME.md](PLAN-013-lan-discovery-dual-address-OUTCOME.md)
  摘要：①归档 Python Mac 客户端（14590fb，mac-app→backup/mac-app，Tauri 唯一受支持）；②server 新增 UDP 局域网发现应答（internal/discovery，探测串 RV-DISCOVER-v1，同源 1s 节流，-discovery/-name 参数，3 例新测试）；③Android 局域网扫描 + 本地/远程双地址（LanDiscovery.kt、RelayClient 多 Endpoint 拨号本地优先、TOFU 按 host:port 隔离、设置页扫描选择 UI）；④部署公网服务器 118.193.40.160（源码 rsync 至 /home/ubuntu/services，服务器 Go 1.22 构建，systemd remote-voice-server 托管）。验证：go vet/-race 全绿、assembleDebug 通过、服务器本机 fakephone 双角色回环全链路通过（注册→TOFU→认证→399 帧推流→UDP 发现应答正确）。遗留：云安全组未放行 TCP/UDP 9432，公网与真机验收待用户执行；保护了 services 目录下非本项目 ThinkTime/，未清理 8 月旧部署残留。

- **2026-09-18 05:20** | UI V3.2 设计决定（用户拍板）+ PLAN-014 撤销
  摘要：经三轮评审迭代（V3.1 双链路可选→仅本地扫描→蓝牙式列表），用户最终拍板：**只保留公网连接，
  本地局域网仅用于测试**（测试时把 server 地址填成局域网 IP 即可，无需独立链路概念）。
  UI 原型 docs/ui-design/v3/（android/mac/index）已全部改为纯公网单链路版本；
  PLAN-014（连接前自动扫描）标记撤销、不再实施；server `-discovery` 默认改为 false
  （降级为本地测试工具）。待办：UI V3.2 评审确认后实施两端界面简化（Android 移除
  LanDiscovery/双地址代码，恢复单一服务器地址）。

- **2026-09-18 11:19** | [PLAN-015-mac-settings-modal.md](PLAN-015-mac-settings-modal.md) → [PLAN-015-mac-settings-modal-OUTCOME.md](PLAN-015-mac-settings-modal-OUTCOME.md)
  摘要：Mac 原型按用户反馈调整——服务器地址/设备名为低频设置，不应占据首页。首页第二卡拆为
  RELAY SERVER 单行只读卡（整卡可点弹窗）+ AUDIO OUTPUT 卡；连接设置表单移入模态弹窗
  （校验失败弹窗内提示、已连接保存自动换线重连，保持 V3.2 行为；×/取消/遮罩/Esc 可关闭）；
  同步 connect/disconnect 状态行文案与页首 tip；顺带修复 index.html 中 V3.2 重写残留的
  孤立文本与指向已删除文件的死链接卡。验证：HTML 标签配平、node --check、headless Chrome
  截图（默认态/弹窗态/总览页）全部通过。仅改设计原型未动客户端代码；Tauri 真端实现待
  UI V3.2 评审确认后统一实施。

- **2026-09-18 11:41** | [PLAN-016-mac-settings-modal-impl.md](PLAN-016-mac-settings-modal-impl.md) → [PLAN-016-mac-settings-modal-impl-OUTCOME.md](PLAN-016-mac-settings-modal-impl-OUTCOME.md)
  摘要：将 PLAN-015 定稿原型落地到 Mac Tauri 客户端（仅 web/ 三件，Rust 零改动）。删除首页常驻
  「连接设置」面板，改为 RELAY SERVER 单行只读卡（整卡可点）+ AUDIO OUTPUT 卡 + 永久秘密通栏；
  服务器地址/设备名/有效期/keep-temp/清除信任收进「连接设置」模态弹窗；全部沿用原元素 ID，
  readSettings/render 无缝兼容。新增 editingSettings 标志修复连接中高频 app-state 事件冲掉
  弹窗内未提交编辑的既有缺陷；保存重连复用后端 save_settings 内置换线（controller.rs:346-348），
  连接前地址为空改为前置打开弹窗引导。验证：pnpm build 通过；vite dev + headless Chrome 截图
  默认态/弹窗态布局与样式正确；临时截图改动已还原复核。未覆盖：macOS 真机行为（Rust 无改动，
  不影响 PLAN-010 结论）、Android V3.2 简化另计。

- **2026-09-18 13:03** | [PLAN-017-android-v32-impl.md](PLAN-017-android-v32-impl.md) → [PLAN-017-android-v32-impl-OUTCOME.md](PLAN-017-android-v32-impl-OUTCOME.md)
  摘要：Android 端完整实施 V3.2 新设计（用户拍板"先完全实施，再测试"）。功能简化：删除
  LanDiscovery/扫描 UI/本地-远程双地址多端点拨号，RelayClient 收敛为单地址，默认服务器切换为
  118.193.40.160:9432；新增清除服务器信任（TrustStore.clear）。UI 重构：两端切暗色 V3.2 tokens
  （RVTheme 固定暗色，放弃 DayNight）；主界面按原型重排（brand 行+齿轮、状态胶囊 LED+双行、
  中央态区大字/转圈/装饰波形+计时+帧数/错误面板+重试、PTT 大钮）；设置页三卡
  SERVER/DEVICES/ADVANCED，rv:// 直接粘贴归一化。协议/采音/心跳/退避重连零改动。
  验证：assembleDebug 通过、残留零命中；真机联测后置（用户指示）。

- **2026-09-18 13:17** | [PLAN-018-mac-v32-final-align.md](PLAN-018-mac-v32-final-align.md) → [PLAN-018-mac-v32-final-align-OUTCOME.md](PLAN-018-mac-v32-final-align-OUTCOME.md)
  摘要：Mac 端 V3.2 剩余对齐（收尾 PLAN-016）。右上状态胶囊可点（连接/断开/重试，与底部
  按钮同语义）；永久秘密改折叠卡（掩码常驻、管理行默认收起）；音频卡新增装饰电平表
  （bridged 时动画）与状态行三分支文案，audio_error 时提示异常。真振幅数据源仍为开放问题。
  验证：pnpm build 通过；headless Chrome 截图默认态/展开态正常；临时截图改动已还原。
  至此 V3.2 设计完整落地两端代码（Mac=PLAN-016+018，Android=PLAN-017），联测按用户指示后置。

- **2026-09-18 15:40** | [PLAN-019-ptt-fn-dictation-and-ptt-leak.md](PLAN-019-ptt-fn-dictation-and-ptt-leak.md) → [PLAN-019-ptt-fn-dictation-and-ptt-leak-OUTCOME.md](PLAN-019-ptt-fn-dictation-and-ptt-leak-OUTCOME.md)
  摘要：①协议增补 FRAME_TALK=0x0A（手机→Mac 说话状态 1 字节，server 桥接透传，三端同步），
  Mac 端新模块 keyinject 把 PTT 映射为 Fn 按键（hold 按住保持=默认，double 双击=兜底，
  裸 FFI CoreGraphics 零新依赖，AXIsProcessTrusted 权限检测，PeerOffline/断线/Drop 强制
  释放防 Fn 悬空，桥接重连时手机重发当前状态），Mac 设置弹窗新增语音输入模拟开关+模式；
  ②泄流修复：确认根因 handsfree 残留（V3.2 已无此概念但代码残留，用户无处关闭）+
  pttHeld 卡死缺陷（ACTION_UP 丢失后永久推流）——移除 handsfree 全部代码、onPause 兜底
  复位、startStreaming 重置、"传输中"文案改"已就绪"（含 review 中发现并修复的 applyState
  状态映射同步）。验证：go vet/-race 全绿（新增 TALK 透传+未桥接丢弃用例）、cargo test 18 例
  （TLS 集成断言 PeerTalking 事件）、clippy 0 警告、pnpm build、assembleDebug 全过。
  待真机验证：Fn 注入实际触发语音输入效果（若长按 Fn 弹输入菜单则切 double 模式）、
  辅助功能权限授权、泄流归零。

- **2026-09-18 16:10** | PLAN-019 追加修正（同日，无新计划文件，记录见 OUTCOME"追加修正"节）
  摘要：用户提出"手机按住录音后断网，Mac 端 Fn 悬空"场景。推演确认原有保障：server 40s
  读超时（或 TCP 显式断开立即）→ 桥接溶解 → Mac PeerOffline 强制释放。修正两处遗漏：
  ①Mac↔server 断线/server 重启时 Reconnecting/Stopped 分支现均强制 release()；
  ②手机重桥时无条件重发当前 PTT 状态（原来松手状态不重发）。至此 Fn 悬空防护覆盖
  对端掉线/Mac 断线/会话停止/应用退出四路径。验证：clippy 0 警告、cargo test 18 例、
  assembleDebug 全过。

- **2026-09-18 17:05** | PLAN-019 追加修正 2（Fn 手动解除）+ 修正 3（秘密→密码）
  摘要：①应"断线后 Mac 主动解除 Fn"需求，新增两条手动路径：物理 Fn 抬起监听
  （CGEventTap listenOnly 只订阅 keyUp，防止合成事件自触发；tap 专用线程 CFRunLoop，
  创建失败仅提示不致命）+ Mac 界面"放下 Fn"按钮（新 command release_fn/manual_release，
  talking 时显示）；解除后保持释放至手机新一轮 TALK(true)。②界面用语"秘密"全部改
  "密码"（Android strings/四 Activity + Mac web/两文件 + controller/secrets 错误文案），
  协议字段/配置键/注释术语/历史文档不动。验证：clippy 0 警告、cargo test 18 例、
  pnpm build、assembleDebug 全过。

- **2026-09-18 23:58** | PLAN-020 修复 Mac 客户端持续高 CPU
  摘要：用户报告"mac-app 几乎一直占用很大的 CPU"。排查全部常驻路径后定位主因：
  PLAN-018 装饰电平表的 CSS 动画作用在 height（布局属性）上，说话（PTT 按住）期间
  12 根柱子逐帧触发 WebView 主线程布局+重绘；次因：桥接期 2Hz 全量 render 中
  renderConnectButton 无条件全文档重建 SVG 图标。修复：①keyframe 改 transform
  scaleY（合成器线程执行，柱高固定 var(--h)，off 态补 transform:none）；
  ②app.js 新增 lastConnectIconState 守卫，连接状态不变时跳过重建；③顺带修
  index.html 重复 id="meter"。Rust 零改动。验证：pnpm build、浏览器实测采样
  （height 恒定、transform 变化）、cargo test 18 例、clippy 0 警告。待真机复核
  说话期间 WebView 进程 CPU（见 OUTCOME 待真机复核节，含非本因时的排查顺序）。
  文件：PLAN-020-mac-meter-cpu.md / PLAN-020-mac-meter-cpu-OUTCOME.md。

- **2026-09-19 00:32** | PLAN-021 Mac 音频输出失败链路优化（错误风暴去重 + 自愈重试 + 前端轻量化）
  摘要：承接 PLAN-020 与 review 核对结论，处理「音频输出失败时每帧报错 → 每错误全量
  UI 推送（约 50 次/秒）」放大链。①后端根治：AppState/UiEvent 加 PartialEq，
  update_state 值未变不 emit（controller.rs:717-733）；②失败自愈：relay 新增
  AudioErrorLatch（同错只报一次）+ 5s 限频重试 start + AudioReady 恢复事件，
  PeerOffline 重置锁存；audio.rs 修正 last_error 粘滞（流存活但出错时重建，
  原来一次瞬时 CoreAudio 错误=整场静音）；③前端：统计只更新 3 个文本节点，
  事件列表/设备下拉/告警/状态行按签名守卫跳过重建。计划外修正（重要）：
  keyinject.rs 裸指针跨线程非 Send，macOS 上根本编译不过（PLAN-019 后所有 Mac
  构建实际失败、/Applications 仍是 01:53 旧包），转 usize 修复并修正该模块测试
  的 macOS 可移植性；cargo fmt 规范化既有未格式化行。验证：cargo fmt/clippy
  首次在 macOS 全量通过、cargo test 21 例（+3）、pnpm build、release 编译成功、
  playwright + Tauri IPC stub 断言全部前端守卫生效。待真机复核错误风暴复现
  实验（设备名填错 → CPU 不升高、≤5s 自愈恢复）。
  文件：PLAN-021-mac-audio-error-storm.md / PLAN-021-mac-audio-error-storm-OUTCOME.md。

- **2026-09-19 01:05** | PLAN-022 Android「添加连接」必闪退修复（主线程 TLS 写）
  摘要：用户报「android 添加连接总闪退」。真机 crash buffer 三次同栈确证：
  NetworkOnMainThreadException @ RelayClient.sendTalk ← AudioStreamService.startCapture:321
  ——PLAN-019 引入的「桥接建立无条件同步 PTT 状态」把 TLS 写留在了主线程（relay 回调经
  postIfCurrent 落主线程），Mac 在线时添加连接 100% 必崩；setTalking（PTT 回调）为同因
  第二处。修复：RelayClient 内新增单线程 daemon 执行器 relay-talk，sendTalk 调用线程只做
  无 I/O 前置检查、TLS 写 FIFO 提交执行器（保 TALK on/off 顺序，防 Fn 悬空回归）；任务内
  自捕获 IOException；stop() shutdownNow（幂等），closeQuietly 不关执行器（重连后 TALK
  仍可用）；sendAudio 保持采音线程直写。验证：Python 假 relay（自签 TLS，AUTH_OK+
  PEER_STATE online，端口 19432 以避开用户自起的 9432 server-linux）+ adb reverse +
  run-as 注入 prefs；修复前 APK 复现同因闪退，修复后 APK 桥接不崩、TALK 0x00 同步帧到达、
  模拟按住 PTT 收 TALK 0x01→50 帧 AUDIO→0x00，全程进程存活。修复版已装机，prefs 还原
  公网默认，harness 已清理。遗留：主线程建 AudioRecord 微卡（方案 C）未做，属可选优化。
  文件：PLAN-022-android-add-connection-crash.md / PLAN-022-android-add-connection-crash-OUTCOME.md。

- **2026-09-19 01:55** | PLAN-023 扫码配对——Mac 出二维码、手机扫码一键添加
  摘要：新功能。载荷 `rv://<host>[:<port>]?s=<密码>&n=<设备名>`（设计文档含方案比较与
  2 轮自查）。Mac 端纯前端：内嵌 qrcode-generator 2.0.4（MIT，web/public/vendor，Vite
  publicDir 才会被拷贝）、临时密码区「二维码」按钮 + 模态白底 SVG、服务器未配/无密码时
  禁用；Rust 零改动。Android 端：唯一 maven 例外 zxing:core 3.5.3（腾讯镜像拉取，零依赖
  约束修订记录于设计文档 §4.1）、ConfigParser.parsePairing（parse 原语义不变）、新增
  ScanActivity（Camera2+YUV→zxing 仅 QR，8fps 节流，手动输入配对码兜底）、添加对话框
  「扫码配对」→ applyPairing（服务器切换+添加设备+自动重连）。验证：zbarimg 离线闭环
  断言、pnpm build、assembleDebug、真机 e2e（手动输入路径全链路：服务器切换→添加→连接
  →已就绪·TestMac 自动命名→无崩溃）。待验收：相机实拍扫码（需真人对准屏幕 10 秒）。
  遗留：永久密码 QR 需 Rust 命令读 Keychain（前端无明文），下次 Mac 构建时做。
  文件：PLAN-023-qr-pairing.md / PLAN-023-qr-pairing-OUTCOME.md，
  设计 docs/design/qr-pairing-20260919-overview.md。

- **2026-09-19 12:00** | PLAN-024 TLS CA 模式（rvs://）——server 加载真证书、客户端标准 HTTPS 验证
  摘要：用户有域名，公网部署走 Let's Encrypt。语义：rvs://=标准 TLS（系统 CA+主机名验证，
  无 TOFU），rv://=现行 TOFU 不变，两者并存（设计文档含方案比较与 2 轮自查）。server 增
  -cert/-key（PEM，LoadX509KeyPair，零新依赖）；Mac 增 webpki-roots 0.26 标准验证分支
  （strict 时跳过指纹逻辑，cargo check+21 测试本机过）；Android strict=默认 SSLContext+
  握手后 HostnameVerifier 补校验（裸 SSLSocket 不自带），parse/format/parsePairing 全链
  保留 rvs:// 前缀。验证：openssl 正负例、fakephone 混连回归、真机「rvs://+公网 TLS 连通 /
  rvs://+自签正确拒绝 / rv://+自签回归」三例、pnpm build、assembleDebug。未真机验证：
  rvs:// 配对码落地（图案锁阻挡，核心链路已被用例 2 覆盖）、LE 实连（部署后自然验收）。
  部署 runbook（certbot+systemd）在 OUTCOME §部署 runbook。
  文件：PLAN-024-tls-ca-mode.md / PLAN-024-tls-ca-mode-OUTCOME.md，
  设计 docs/design/tls-ca-mode-20260919-overview.md。

- **2026-09-19 12:40** | PLAN-025 TLS 证书模式自动探测——消灭 tofu-mismatch
  摘要：裸 `host:port` 成为默认地址形态=Auto 模式（先标准 CA+主机名验证，仅证书验证
  失败回落 TOFU；服务器日后配 LE 证书自动升级，零用户操作）；rvs:///rv:// 保留为逃生门/
  兼容。两端 `tofu-mismatch` 字符串清零：指纹不符改为可恢复流程 Mac=`cert-changed` fatal+
  前端「重新信任并连接」弹层（clear_server_trust+connect）/Android=`onCertChanged` 停连+
  「重新信任并重连」按钮（retrustAndRestart 清 TrustStore 后重连）。Mac relay 拆出
  dial_and_handshake+HandshakeFailure 分类（仅 InvalidCertificate 类回落）。验证：cargo
  test 25 例（+4）、assembleDebug、grep 清零。
  文件：PLAN-025-tls-auto-detect.md / PLAN-025-tls-auto-detect-OUTCOME.md，
  设计 docs/design/connection-simplify-20260919-overview.md。

- **2026-09-19 13:05** | PLAN-026 服务器密码 + Mac 目录（协议 v4，破坏性升级）
  摘要：一个密码模型上线——Mac 端设置服务器密码（Keychain，复用原永久密码槽位），
  REGISTER 推送 SHA-256 到 server 持久化（devices.json v2，last-write-wins）；手机输一次
  密码登录（AUTH target 空=登录会话）后 LIST 查询全部已登记 Mac（含离线，名字持久化），
  target 非空=按 device_id 建桥。per-Mac 秘密/临时秘密/SecretKind/v2 regkey 全删（-regkey
  flag 移除）。Mac 前端重写（配对区+设置弹窗+get_pairing_payload 二维码）；Android
  ServerStore（v3 数据迁移）+RelayClient v4（LIST/authed/文案）+服务=登录连接（5s 轮询）
  +N 桥接。两端自动重连开关（默认开）。验证：go test 全绿（20 例）、cargo test 26、
  pnpm build、assembleDebug、本机 server+fakephone 真实回环（注册→LIST→错密码拒→建桥→
  199/175 帧透传→offline 通知）。部署 runbook 写入 server/DEPLOY.md 顶部（两端同步升级）。
  文件：PLAN-026-server-password-mac-list.md / PLAN-026-server-password-mac-list-OUTCOME.md。

- **2026-09-19 13:06** | PLAN-027 Android 多 Mac 同时连接 + 左右滑动切换
  摘要：与 PLAN-026 Android 改造一次性落地。AudioStreamService=登录连接+
  bridges(deviceId→Bridge) 多桥并发；采音单实例按 activeDeviceId 路由（routed 门控防串流）；
  切换目标=旧桥 TALK-off+新桥同步 PTT（Fn 不悬空）；ACTION_SET_ACTIVE（切换或建桥）/
  ACTION_DISCONNECT_MAC（单桥断开）。MainActivity GestureDetector fling 在已连接 Mac 间
  循环切换；目录 chips ◉/●/○/⧖/· 五态（离线可见、长按断开）。服务器 1:1 桥零改动。
  验证：assembleDebug；双 Mac 双桥并发由 go test TestBridgeFullDuplex 覆盖；真机滑动
  复核待用户。
  文件：PLAN-027-android-multi-mac.md / PLAN-027-android-multi-mac-OUTCOME.md。
