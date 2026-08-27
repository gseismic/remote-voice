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
