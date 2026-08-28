# PLAN-010：Rust/Tauri Mac 客户端

日期：2026-08-28
基线提交：`bfb44b3143e7267e8b6696462792c2ff5c16d970`
对应设计：[tauri-mac-client-20260828-overview.md](../design/tauri-mac-client-20260828-overview.md)
预研：[20260828-1715-PRE-bfb44b3-tauri-mac-client.md](20260828-1715-PRE-bfb44b3-tauri-mac-client.md)

## 1. 目标

在保留 `mac-app/` Python 客户端的同时新增 `mac-app-tauri/` Rust/Tauri 客户端，复用 server v3 协议和现有身份/秘密语义，提供 Mac GUI、TOFU、设备自助入网和 BlackHole 音频输出。

## 2. 实施步骤

### 2.1 工程与设计

1. 建立 Tauri 2 + Vite 项目，固定前端/CLI 版本，补 `.gitignore`、README 和构建命令。
2. 将设计中的用户命令、状态事件、错误码和状态机落实为 Rust 类型；保持核心无 Tauri 依赖以便测试。

### 2.2 Rust 核心

1. 实现 v3 帧协议、JSON 编解码、大小边界和错误类型。
2. 实现 server 地址解析、秘密规范化/哈希、Keychain CLI 与 0600 fallback、共享配置/TOFU。
3. 实现 Tokio + rustls TLS 连接、证书 TOFU、AUTH→AUTH_OK→REGISTER 顺序、PING/PONG、PEER_STATE、EVENT、AUDIO、重连和停止清理。
4. 实现 cpal 音频 sink，支持 BlackHole 名称匹配、常见采样格式、有限队列和设备错误状态。
5. 实现 controller、Tauri commands 和 `app-state` 完整快照事件。

### 2.3 前端

1. 实现夜间音频控制台主窗口：状态、临时秘密、永久秘密、连接设置、音频输出和事件日志。
2. 接入 Tauri commands/events，实现保存、连接、断开、秘密重生成/停用、复制和设备刷新。
3. 处理加载、连接中、桥接中、认证拒绝、TOFU 不符、音频设备缺失和无权限等状态。

### 2.4 Review 与测试

1. 逐模块 review：协议边界、TLS verifier、凭据落盘、命令副作用、并发取消、音频队列和 UI 状态一致性。
2. 补 Rust 单元/集成测试和前端 production build；用本地测试 server 验证真实 v3 桥接。
3. 修复 review 发现的问题后重跑格式化、Cargo、npm、Tauri 和既有三端回归测试。

### 2.5 文档与交付

1. 更新 `README.md`、`mac-app-tauri/README.md`、`docs/HANDOFF.md` 和 `docs/dev/INDEX.md`。
2. 生成 `PLAN-010-tauri-mac-client-OUTCOME.md`，记录实际实现、review 修复、测试结果和 macOS 真机缺口。
3. 中文 commit，明确包含计划、结果和设计文档路径；提交后立即 push。

## 3. 实施期间的 review 调整

1. Tauri 客户端启动时恢复 `history.json` 的最近事件，避免重启后 UI 只显示本次进程内容。
2. 增加按当前服务器清除 TOFU 的显式命令和确认按钮，证书轮换可以恢复，同时保留用户确认后的重新信任边界。
3. 为 `AppController` 增加析构清理，关闭 relay 线程和音频输出，避免窗口退出留下后台连接。
4. 为连接生命周期增加串行锁，防止重复点击或并发命令启动多个 relay 线程，也避免热更新秘密与连接切换交叉。

## 4. 验收标准

- Linux 开发机可执行 `npm run build`、`cargo fmt --check`、`cargo test`、`cargo check`，并可完成 Tauri 构建或明确记录平台依赖限制。
- Rust 核心能连接当前无 regkey 的 v3 server：首次设备登记、REGISTER、重连和手机配对秘密路由通过集成测试。
- Mac GUI 不显示或要求 regkey、device key、证书指纹；首次自动生成身份，后续能复用 Python 版身份存储。
- TOFU 按 server 地址隔离，指纹变化进入明确 fatal 错误；认证失败不无限重试。
- BlackHole 可用时音频输出通过 cpal；设备缺失或拔出不会导致网络线程崩溃，UI 有可读状态。
- 现有 `server/`、`mac-app/`、`android-app/` 回归测试不受影响。
- 完成代码 review 文档，且结果文档如实区分自动化验证和 macOS 真机未验证项。

## 5. 计划自查记录

### 第 1 遍：范围与兼容

- Tauri 版是新增并行客户端，不改协议、不删除 Python 版；用户可按环境选择。
- 共享 Keychain/fallback 是为了同一台 Mac 的身份连续性；配置字段兼容失败时必须有明确迁移错误。
- Linux 构建不等于 macOS 可发布，计划把 bundle/Keychain/BlackHole 真机列为独立验收项。

### 第 2 遍：安全与生命周期

- 首次自助登记与 TOFU 的风险不因新 UI 隐藏而消失，设计和 README 都要说明。
- AUTH 成功前禁止 REGISTER；断开必须关闭读任务、写任务、TLS socket 和音频 stream。
- 前端只接收完整状态快照，不接收 device_key 和原始永久秘密。

### 第 3 遍：测试可证性

- 协议、TLS、持久化和状态机均有无 UI 测试入口；不能只用截图或 Cargo 编译证明连接可用。
- 集成测试覆盖真实 v3 server 的 AUTH/REGISTER/桥接；旧 Python/Android 测试作为兼容回归。
- cpal 真机输出不能在 Linux 假装已验证，结果文档必须单独列出缺口。

## 6. 当前状态

- [x] 侦察、设计和计划完成
- [x] Tauri 工程骨架与 Rust 核心
- [x] Tauri UI
- [x] review 与测试
- [x] OUTCOME、文档、commit、push
