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
