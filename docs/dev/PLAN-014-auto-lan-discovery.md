# PLAN-014：两端客户端连接前自动扫描局域网 server

日期：2026-09-18
基线提交：`f755b8b`
对应设计：[../design/lan-discovery-dual-address-20260917-overview.md](../design/lan-discovery-dual-address-20260917-overview.md)（本计划为其行为补充：扫描从"设置页手动"升级为"连接前自动"）
用户期望：无论 Mac 还是 Android，连接时都应自动扫描出局域网可用的 server，扫到且已有
秘密就直接连（本地优先）；扫不到才回落配置/远程地址。此前实现只在 Android 设置页放了
手动扫描按钮，启动流程不会自动扫——手机总是直连远程。

## 1. 行为定义（两端一致）

1. 发起连接时先做一次局域网扫描（UDP 探测，窗口约 1.3s，2 轮广播），状态提示"扫描局域网…"。
2. 扫到 server：优先使用（状态/详情标注「局域网发现」），配对逻辑不变（秘密决定接哪台 Mac）。
3. 扫描无结果：Android 依次回落"本地记忆地址 → 远程"；Mac 回落"配置地址"。
4. 自动重连（RelayClient 内部退避）不重新扫描，沿用连接时选定的地址；重开应用/重启服务才重扫。
5. Android 在无 wlan/eth 网卡（纯移动数据）时跳过扫描，省去 1.3s 空等。
6. 扫描发现的地址与配置地址相同则去重，不重复拨号。

## 2. Android（AudioStreamService）

- `startStreaming` 的网络装配段移入后台"准备线程"：先 `LanDiscovery.scan(1200ms)`（有
  wlan/eth 才扫），按「扫描结果 → KEY_SERVER_LOCAL 记忆 → 远程（空值回落 DEFAULT_SERVER）」
  组装 endpoints；全程校验 connectionGeneration，期间服务重启则丢弃结果。
- 状态文案：扫描中/发现本地服务器 host/未发现走远程。
- 手动扫描按钮与 `KEY_SERVER_LOCAL` 语义保留（记忆地址成为回落项）。

## 3. Mac（mac-app-tauri）

- 新增 `src/discover.rs`：std UdpSocket 广播探测 + JSON 解析；广播目标 = 255.255.255.255 +
  由默认路由源 IP 推导的 /24 广播；`scan/scan_on`（后者可测）。
- `controller.rs connect_inner`：连接前扫描，选定地址注入 `RelayClientOptions.server`，
  TOFU 指纹按选定地址取（`trusted_fingerprint`）；state.detail 标注「局域网发现/配置地址」。
- connect 命令改为后台线程执行（扫描阻塞约 1.3s，不冻结 UI 线程）。

## 4. 验证

- Rust：`cargo test`（新增 parse_reply 单测 + 127.0.0.1 应答器集成测试）、`cargo clippy`；
  本机实测：config.server 故意填错误地址，启动客户端应自动发现 `192.168.1.103:9432` 并注册
  （验证后停掉本机客户端，恢复"本机只跑 server"）。
- Android：`clean assembleDebug`；真机无 Wi-Fi 场景验证跳过扫描直连远程的状态文案；
  Wi-Fi 自动发现路径由用户联测验证。
- 文档：设计文档补 PLAN-014 行为说明；HANDOFF/INDEX 同步。
