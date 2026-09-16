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
