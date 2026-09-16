# PLAN-013：局域网发现 + Android 本地/远程双地址 + 归档 Python Mac 客户端

日期：2026-09-17
基线提交：`f6d58a7`
对应设计：[../design/lan-discovery-dual-address-20260917-overview.md](../design/lan-discovery-dual-address-20260917-overview.md)
用户指令：①Android 默认扫描局域网，用户直接选，扫不到才手动；服务器地址分本地/远程，
本地优先探测；②Mac 端只支持 Tauri 版、傻瓜式连接；③把 mac-app（Python 版）放进备份文件夹
（用户已二次确认是 `mac-app/` 而非 `mac-app-tauri/`，后者与"只支持 Tauri"冲突）。

## A. 归档 Python Mac 客户端（先做，独立提交）

1. `git mv mac-app backup/mac-app`（保留 git 历史）。
2. 根 `README.md`：目录表删 mac-app 行并加归档说明；快速启动 Mac 节改为 Tauri 唯一路径；
   开发验证删 mac-app pytest 段；删除 `remote-voice-recv` CLI 提法。
3. `docs/HANDOFF.md`：运行端目录表、常量同步说明（活跃端变为 Go/Kotlin/Rust + 备份 Python）、
   下一步验收项改为仅 Tauri。
4. `mac-app-tauri/README.md`：改为"唯一受支持 Mac 客户端"，注明 Python 版归档位置与存储兼容。

## B. server：UDP 局域网发现应答

1. 新增 `server/internal/discovery/discovery.go`：`ProbeMessage = "RV-DISCOVER-v1"`；
   `Serve(addr, name)` 监听 UDP（与 `-addr` 同号端口），精确匹配探测串才回
   `{"v":1,"name":...,"port":<TCP端口>}`；同源 IP 1 秒限答 1 次；`LocalPort()` 供测试。
2. `main.go`：新增 `-discovery`（默认 true）与 `-name`（默认主机名）；TCP 监听成功后启动
   应答器，失败仅记日志不影响 TCP；启动横幅打印发现状态。
3. `server/internal/discovery/discovery_test.go`：回环应答内容校验、错误 payload 不应答、
   同源 1s 节流。

## C. Android：扫描 + 双地址 + 状态标注

1. 新增 `LanDiscovery.kt`：按设计 §3 实现扫描（子网广播 + 255.255.255.255，1.5s 窗口，
   去重），后台线程调用。
2. `RelayClient.kt`：构造参数改为 `endpoints: List<Endpoint>`（host/port/label）+
   `fingerprintFor: (host, port) -> String`；拨号按列表顺序尝试，网络级失败切下一个，
   认证/指纹类仍 fatal；TOFU 信任与回调按 endpoint 的 serverKey 记录；拨号状态带
   「本地/远程」前缀。
3. `AudioStreamService.kt`：新增 `KEY_SERVER_LOCAL`；startStreaming 组装 endpoints
   （本地有效则排首位，无效/为空跳过；远程空值回落 DEFAULT_SERVER，两者都无效才报错）。
4. `SettingsActivity.kt` + `activity_settings.xml`：服务器区改为「本地地址（可空）+
   [扫描局域网] 按钮 + 远程地址」；扫描结果对话框直接选择保存为本地地址并触发服务重启；
   rv:// 导入仍写入远程地址。

## D. 文档

1. `server/DEPLOY.md`：防火墙补 UDP 同端口（局域网发现，可选）；README 补 `-discovery/-name`。
2. `android-app/README.md`：傻瓜式流程更新（扫描→选择→自动连接）。

## 验证

- `cd server && go vet ./... && go test ./... -race -count=1` 全绿（含新发现用例）。
- `cd android-app && ./gradlew clean assembleDebug` 通过。
- 活文档 grep 无 `mac-app/` 活动引用（`backup/`、历史 PLAN/REVIEW/INDEX 条目除外）。
- 真机扫描、双链路切换、公网部署待用户验收（沿用 PLAN-011 遗留清单）。

## 自查（实施前，2 遍）

1. 【通过】RelayClient 构造签名变更只有 AudioStreamService 一个调用方，无其他引用。
2. 【通过】TOFU 按 host:port 隔离，本地/远程各自首次握手建信，互不污染；指纹不符 fatal
   语义保持——拨号切换只针对网络级异常。
3. 【通过】本地地址无效时跳过而非报错，避免用户清空远程后系统不可用；两个地址相同时
   去重（同一 host:port 只试一次）。
4. 【通过】UDP 应答在 `-addr` 指定非 9432 端口时跟随同号；`127.0.0.1` 测试用 `:0` 随机端口，
   需 `LocalPort()` 暴露。
5. 【注意】扫描在设置页前台线程外执行（Thread + runOnUiThread 回填），不阻塞 UI、
   不在主线程发网络包。
