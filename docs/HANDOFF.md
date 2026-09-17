# 交接文档 (HANDOFF)

更新时间：2026-09-18 04:30 (UTC+8)

## 项目背景（零上下文可读）

用户的 Mac 麦克风硬件损坏。本项目实现替代方案：**Android 手机采音，经用户自有的公网
Linux 服务器中继，写入 Mac 的 BlackHole 虚拟声卡**，使任意 Mac 应用把这条链路当作麦克风。
全部自研、完全可控；不依赖域名（TLS 用自签证书 + 客户端内部 TOFU 固定）。

- 目标原始记录：`GOAL.md`
- 设计文档目录：`docs/design/`（协议、多 Mac 自助入网、局域网发现等）
- UI 设计稿：`docs/ui-design/`（v1/v2 历史，**v3 为当前待评审的可交互原型**）
- 计划与结果：`docs/dev/`（PLAN-XXX / -OUTCOME + INDEX.md，已执行至 PLAN-014 计划）
- 评审文档：`docs/dev/` 下 REVIEW-* 文件
- 协议现状：v3（Mac 设备身份认证 + 秘密哈希注册表 + 多 Mac 1:1 桥接 + IP 限速防爆破），
  v1/v2 已淘汰（v2 regkey 仅作 server 可选兼容）

## 运行端状态总表

| 目录 | 运行端 | 内容 | 状态 |
|---|---|---|---|
| `server/` | 公网 Linux | Go 中继（module `remote-voice/server`，零依赖）+ `cmd/fakephone` 调试工具 + `internal/discovery` 局域网发现应答 | ✅ go test -race 全绿；**已部署 118.193.40.160**（见下） |
| `mac-app-tauri/` | macOS | Rust/Tauri GUI（Tokio/rustls + cpal + Keychain），**唯一受支持 Mac 客户端**，pnpm 构建 | ✅ Rust 测试/clippy/前端/Linux bundle 通过；macOS 真机待验收 |
| `android-app/` | Android | Kotlin 采音（PTT、多设备单激活、局域网扫描、本地/远程双地址） | ✅ assembleDebug 通过；真机验收进行中（见联测记录） |
| `backup/mac-app/` | （已归档） | 早期 Python 客户端（GUI+CLI），PLAN-013 归档，仅参考实现 | 📦 协议变更不再同步 |

## 当前状态（截至 2026-09-18）

### 最近完成的重点

1. **PLAN-012（pnpm 统一）**：Tauri 前端包管理器 npm→pnpm，锁文件换 pnpm-lock.yaml；
   顺带修复开发机 cargo PATH 与 crates.io 网络问题（见环境备忘）。
2. **PLAN-013（局域网发现 + 双地址 + 归档）**：
   - server 新增 UDP 局域网发现应答（探测串 `RV-DISCOVER-v1`，同 `-addr` 端口，同源 1s 节流，
     `-discovery/-name` 参数）；
   - Android 设置页手动扫描 + 本地/远程双地址（当时为"本地优先自动"语义，后被 V3.1 推翻，见下）；
   - Python Mac 客户端归档 `backup/mac-app/`，Tauri 成为唯一 Mac 客户端；
   - **部署到公网 118.193.40.160**（用户指定）：源码 rsync 至 `/home/ubuntu/services/`，
     服务器 Go 1.22.2 构建，systemd 服务 `remote-voice-server` 已启动；
     服务器本机 fakephone 双角色回环全链路通过（注册→TOFU→认证→399 帧推流→发现应答正确）。
3. **三端联合本地测试（部分完成）**：本机起 server（`:9432`，发现开启），Linux 版 Tauri 客户端
   充当 Mac 端 + 真实小米手机（`adb reverse` USB 转发），三端桥接推流 **500 帧/10s（精确 50fps）**
   连续稳定。注意：该联测中 Mac 端是 Linux 临时替身，**真 Mac 客户端联合测试未做**。
4. **UI V3.1 可交互原型（待用户评审确认）**：`docs/ui-design/v3/`（index/android/mac 三个 HTML），
   全部按钮可点。V3.1 设计决定（用户拍板，实施时必须遵守）：
   - **本地/远程 = 两条并行链路、两个分组，用户显式选择、随时切换**；推翻 PLAN-013 的
     "本地优先自动回落"（剥夺选择权，被用户否决）；
   - **只有选「本地」链路才扫描**（扫描结果只填本地组地址）；远程直连配置地址，永不扫描；
   - 局域网多台 server → **蓝牙式选择列表**：在线可选，历史连接但不在线的灰显标注「离线」；
   - 秘密配对决定接哪台 Mac（扫到的是中继 server，不是 Mac 本身）。
5. **PLAN-014（连接前自动扫描，计划已写、未实施）**：
   `docs/dev/PLAN-014-auto-lan-discovery.md` —— 两端按 V3.1 决定实施"连接时自动扫描"，
   含 Rust `discover.rs` 与 Android 服务扫描的行为定义。**UI 评审通过后即可实施**。

### 遗留问题（按优先级）

1. **云安全组放行（用户操作，卡住公网验收）**：118.193.40.160 的云控制台放行入站
   **TCP 9432**（必须）+ **UDP 9432**（仅局域网扫描需要，公网可不放）。实测公网 TCP/UDP 均
   被安全组拦截；服务器侧监听正常、主机无防火墙（ufw inactive、iptables ACCEPT）。
2. **UI V3.1 评审**：用户确认原型后实施两端真实界面 + PLAN-014 行为。
3. **真 Mac 端三端联测**：Mac 上构建 Tauri（pnpm），连 `192.168.1.103:9432`（本机 server）或
   公网 server，手机连家里 Wi-Fi 用扫描选本地 server；替换手机端预置的旧秘密（`2TWU-KF8U`
   属于已弃用的 Linux 临时客户端注册的设备，Mac 新客户端会生成新秘密）。
4. Tauri macOS 真机验收：Keychain、BlackHole 实际出声、签名/公证（沿用 PLAN-010 遗留）。
5. 音频演化方向（远期）：UDP+Opus+FEC、反向声道、波形真实数据源。

## 部署机备忘（118.193.40.160，ubuntu@，免密 sudo）

- 源码：`/home/ubuntu/services/`（**该目录即仓库根**，与开发机 rsync 同步；
  同步命令**必须带 `--exclude ThinkTime`**，那里有用户的其他项目；
  **禁用 `--delete`**——曾险些误删 ThinkTime，靠 root 权限拦下）。
- 服务：`/home/ubuntu/services/server/server-linux` + systemd `remote-voice-server`
  （User=ubuntu，WorkingDirectory=server/，`-addr :9432 -data ./data`，已 enable）。
  数据目录 `server/data/` 含 TLS 私钥与设备登记，勿删。
- 服务器 Go：apt 的 golang-go 1.22.2（满足 go.mod）。
- 历史残留：services 根目录有 8 月旧部署文件（旧 server-linux、token.txt 等），未清理，
  用户确认后可删。
- 证书指纹（部署时生成）：`d123ce4dc4b80a3125306b0e83addb9d0062709812296fc1ffae3daf072aa47f`。

## 本机环境备忘（Linux 开发机）

- Go 工具链：`~/go-sdk/go`（go1.22.10）；Python：miniconda 3.12。
- **Rust**：PATH 已在 `~/.bashrc`/`~/.profile` 末尾固定 `~/.cargo/bin` 优先（/usr/bin/cargo
  1.75 过旧，edition2024 编译失败）；cargo 已配 rsproxy 镜像（`~/.cargo/config.toml`，
  用户级不入库）——本机到 crates.io Fastly CDN 直连不通。
- **pnpm**：10.5.2；在线 install 会被 TUN 代理挂起，用 `pnpm install --offline`（store 2.3G）。
- Android 真机：小米 21091116AC（adb id 5LFAJ7TWUGUCCERO）；联测技巧 `adb reverse tcp:9432
  tcp:9432` 可让手机经 USB 连本机 server（手机不在 Wi-Fi 时）。
- 已知 bug 待修：mac-app-tauri 的 Rust 单元测试会把测试数据写进真实
  `~/.config/remote-voice/config.json`（测试未隔离 HOME），联测时发现。
- 本地联测遗留进程：`/tmp/rv-server`（本地 server，数据 /tmp/rv-joint-data，重启即失）。

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  活跃端常量同步（Go: `server/internal/protocol`，Rust: `mac-app-tauri/src-tauri`，
  Kotlin: `android-app/.../RelayClient.kt`；Python 已归档不再同步）
- 局域网发现（PLAN-013）：UDP 与 TCP 同端口；探测串 `RV-DISCOVER-v1`（精确匹配）；
  应答 `{"v":1,"name","port"}`；同源 IP 1s 节流；server `-discovery=false` 可关
- 信任模型：整张证书 DER 的 SHA-256 hex = 内部 TOFU，按 `host:port` 隔离（本地/远程
  两份信任记录互不污染）
- 心跳：客户端 10s PING / 40s 读超时；重连指数退避封顶 30s
- 认证：v3 Mac 设备身份（server 存 SHA-256(device_key)）→ REGISTER 秘密哈希；
  手机只带规范化秘密的 SHA-256 hex
- UI V3.1 设计决定（实施界面时必须遵守）：双链路并行可选、仅本地扫描、
  蓝牙式多服务器列表 + 历史离线标注（详见 `docs/ui-design/v3/index.html` 设计决定区）

## 历史脉络（详见 docs/dev/INDEX.md）

PLAN-001 MVP → 002 review 修复 → 003 可观测性+Android UX → 004/005 协议 v2 →
006 仓库重组 → 007 一键连接 → 008 TOFU 免输指纹 → 009 多 Mac 自助入网(v3) →
010 Rust/Tauri 客户端 → 011 Android 连接修复 → 012 pnpm 统一 → 013 局域网发现+双地址+
归档 Python+公网部署 → 014 连接前自动扫描（**计划完成待实施**）。
