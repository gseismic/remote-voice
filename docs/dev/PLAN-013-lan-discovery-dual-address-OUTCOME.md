# PLAN-013 实施结果：局域网发现 + Android 双地址 + 归档 Python 客户端 + 公网部署

实施日期：2026-09-17
基线提交：`f6d58a7`（步骤 A 归档提交：`14590fb`）
对应计划：[PLAN-013-lan-discovery-dual-address.md](PLAN-013-lan-discovery-dual-address.md)
对应设计：[../design/lan-discovery-dual-address-20260917-overview.md](../design/lan-discovery-dual-address-20260917-overview.md)

## 1. 用户意图

①Android 傻瓜式连接：默认扫描局域网发现 server 供用户直接选，扫不到才手动；服务器地址
分「本地/远程」两个，本地优先探测。②Mac 端只支持 Tauri 版（Python 版归档 backup/）。
③部署到用户公网服务器 `ubuntu@118.193.40.160`（源码同步至 `/home/ubuntu/services`）并测试。

## 2. 实施内容

### 步骤 A：归档（提交 14590fb）

- `git mv mac-app backup/mac-app`（保留历史）；根 README/HANDOFF/mac-app-tauri README
  同步为「Tauri 唯一受支持 Mac 客户端」。

### 步骤 B：server 局域网发现

- 新增 `server/internal/discovery/discovery.go`：UDP 与 `-addr` 同号端口监听；payload 精确
  等于 `RV-DISCOVER-v1` 才应答 `{"v":1,"name","port"}`；同源 IP 1s 限答 1 次（表上限 4096
  防膨胀）；`Serve/LocalPort/DisplayName/Close`。
- `main.go`：新增 `-discovery`（默认开）、`-name`（默认主机名）；UDP 启动失败仅降级日志，
  不影响 TCP；横幅打印发现状态。
- 测试 `discovery_test.go`：应答内容、陌生 payload 不应答、同源节流 3 例。

### 步骤 C：Android 扫描 + 双地址

- 新增 `LanDiscovery.kt`：全局广播 255.255.255.255 + 各 IPv4 网卡子网广播，2 轮探测、
  1.5s 收集窗、按 IP 去重；JSON 宽松解析（v=1 校验）。
- `RelayClient.kt`：构造改为 `List<Endpoint>`（host/port/label）+ `fingerprintFor` 回调；
  拨号按序尝试（按 serverKey 去重），网络级失败切下一个，认证/指纹类仍 fatal；
  TOFU 指纹按 endpoint 隔离（HashMap by serverKey）；状态文案带「本地/远程」前缀。
- `AudioStreamService.kt`：新增 `KEY_SERVER_LOCAL`；组装 endpoints 本地优先，本地无效跳过，
  远程空值回落 `DEFAULT_SERVER`，两者皆无效才报错。
- `SettingsActivity.kt` + 布局 + strings：服务器区改为「本地地址 + 扫描按钮 + 远程地址」；
  扫描后台线程执行、结果对话框直接选择保存并重连；rv:// 导入明确写入远程地址。

### 步骤 D：部署与公网测试（用户指定的 118.193.40.160）

- 源码 rsync 至 `/home/ubuntu/services/`（仓库根即该目录；详见 §4 环境注意事项）。
- 服务器 apt 安装 Go 1.22.2，`cd /home/ubuntu/services/server && go build` 出 server-linux。
- systemd 单元 `remote-voice-server.service`（User=ubuntu，WorkingDirectory=server/，
  `ExecStart=server-linux -addr :9432 -data ./data`），enable --now 成功，服务 active。
- 启动日志：TLS :9432 监听 + 「局域网发现已开启（UDP :9432，显示名 10-7-16-31）」；
  证书指纹 `d123ce4d…aa47f`（新 data 目录，全新身份）。

## 3. 验证结果

- `go vet ./...` 通过；`go test ./... -race -count=1` 全绿（discovery 3 例新用例 +
  原有 28 例服务端/协议用例）。
- Android `./gradlew clean assembleDebug` 通过（新 APK 876KB）。
- 服务器本机回环全链路通过：fakephone mac 注册（LoopMac，临时秘密签发）→ phone
  TOFU→认证→桥接→399 帧/50 帧秒推流，mac 侧正常收帧；UDP 发现本机探测应答
  `{"v":1,"name":"LoopTest","port":19432}` 正确。
- **公网不可达（遗留，需用户操作）**：云安全组未放行 9432 入站。实测 TCP 9432 握手超时、
  UDP 9432 无应答；服务器侧监听正常、主机 iptables 全放行、ufw inactive。
  **需在云控制台为 118.193.40.160 放行入站 TCP 9432 + UDP 9432**（UDP 仅局域网发现需要，
  公网可不放）。

## 4. 环境注意事项（部署机）

- `/home/ubuntu/services/` 是既有目录：8 月曾直接同步过 server 源码（残留 go.mod/
  main.go/旧 server-linux/token.txt 于根目录），另有其他项目 `ThinkTime/`。
  本次 rsync **未使用 --delete** 保护 ThinkTime 与历史文件，未清理 8 月残留（token.txt 为
  旧凭据文件，建议用户自行确认后处理）。
- 旧 systemd 服务不存在、9432 端口部署前空闲；新服务数据目录
  `/home/ubuntu/services/server/data/`（含 TLS 私钥与设备登记，请勿删除）。

## 5. 遗留

- 用户在云控制台放行安全组后，即可从手机/公网验收；Android 真机扫描（同 Wi-Fi）、
  本地→远程切换、公网推流待真机验收。
- Mac Tauri 真机验收仍沿用 PLAN-011 遗留清单（Keychain/BlackHole/签名公证）。
- 本地/远程地址相同时连接去重已实现（按 serverKey）；公网部署无本地地址场景不受影响。
