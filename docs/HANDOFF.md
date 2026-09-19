# 交接文档 (HANDOFF)

更新时间：2026-09-19 21:25 (UTC+8)

## 项目背景（零上下文可读）

用户的 Mac 麦克风硬件损坏。本项目实现替代方案：**Android 手机采音，经用户自有的公网
Linux 服务器中继，写入 Mac 的 BlackHole 虚拟声卡**，使任意 Mac 应用把这条链路当作麦克风。
全部自研、完全可控；不依赖域名（TLS 用自签证书 + 客户端内部 TOFU 固定）。

- 目标原始记录：`GOAL.md`
- 设计文档目录：`docs/design/`（协议、多 Mac 自助入网、局域网发现等）
- UI 设计稿：`docs/ui-design/`（v1/v2 历史，**v3 为当前待评审的可交互原型**）
- 计划与结果：`docs/dev/`（PLAN-XXX / -OUTCOME + INDEX.md；PLAN-014 已撤销，见其文件头）
- 评审文档：`docs/dev/` 下 REVIEW-* 文件
- 协议现状：v4（服务器密码 + Mac 目录 LIST + 多 Mac 连接），v1/v2/v3 已淘汰；
  **v4 已于 2026-09-19 部署公网**（见部署机备忘）

## 运行端状态总表

| 目录 | 运行端 | 内容 | 状态 |
|---|---|---|---|
| `server/` | 公网 Linux | Go 中继（module `remote-voice/server`，零依赖）+ `cmd/fakephone` 调试工具 + `internal/discovery` 局域网发现应答 | ✅ go test -race 全绿；**已部署 118.193.40.160**（见下） |
| `mac-app-tauri/` | macOS | Rust/Tauri GUI（Tokio/rustls + cpal + Keychain），**唯一受支持 Mac 客户端**，pnpm 构建 | ✅ Rust 测试/clippy/前端/Linux bundle 通过；macOS 真机待验收 |
| `android-app/` | Android | Kotlin 采音（PTT、多设备单激活、局域网扫描、本地/远程双地址） | ✅ assembleDebug 通过；真机验收进行中（见联测记录） |
| `backup/mac-app/` | （已归档） | 早期 Python 客户端（GUI+CLI），PLAN-013 归档，仅参考实现 | 📦 协议变更不再同步 |

## 当前状态（截至 2026-09-19）

### 最近完成的重点

1. **PLAN-029（cert 弹层修复 + v4 公网部署）**：Mac 前端「服务器证书已更换」弹层
   两个按钮此前未绑定事件（PLAN-025 实现缺口，review
   `docs/dev/20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md` 发现），
   现已修复：重新信任=清 pin+重连、暂不连接=disconnect；浏览器 mock 冒烟 4 步
   全过。**v4 服务器已部署公网**（见部署机备忘），systemd Description 同步更新。
   部署教训：rsync 曾把本机 `server/data/` 测试证书覆盖到公网（已处理，服务器
   自签了新专属证书），**今后 rsync 必须加 `--exclude server/data/`**。
2. **协议 v4 三端实现**（PLAN-025/026/027，commit 5338f41）：证书概念对用户归零
   （裸地址=Auto：先标准 CA 验证、自签回落 TOFU、证书换代一键恢复）、服务器
   一个密码 + Mac 目录（含离线）+ 手机多 Mac 连接；PLAN-028 一键构建脚本。

1. **PLAN-019（PTT→Fn 语音输入 + 泄流修复）**：协议加 `FRAME_TALK=0x0A`（手机→Mac
   说话状态，server 透传）；Mac 端 keyinject 模块把手机 PTT 映射为 Fn 按键
   （hold 按住保持=默认 / double 双击=兜底，需**辅助功能权限**），桥接断开强制释放；
   Android 移除 handsfree（泄流根因 A：旧 prefs 残留 true 且 V3.2 无处关闭）、
   onPause 兜底复位 pttHeld（根因 B）、"传输中"文案改"已就绪"。设计：
   `docs/design/ptt-dictation-20260918-holdfn.md`。**待真机验证**：Fn 注入实际效果
   （若长按 Fn 弹输入菜单→设置切双击模式）、授权后语音输入闭环、未按 PTT 帧数归零。
2. **PLAN-012（pnpm 统一）**：Tauri 前端包管理器 npm→pnpm，锁文件换 pnpm-lock.yaml；
   顺带修复开发机 cargo PATH 与 crates.io 网络问题（见环境备忘）。
2. **PLAN-013（局域网发现 + 双地址 + 归档）**：
   - server 新增 UDP 局域网发现应答（探测串 `RV-DISCOVER-v1`，同 `-addr` 端口，同源 1s 节流，
     `-discovery/-name` 参数）；
   - Android 设置页手动扫描 + 本地/远程双地址（后被 V3.2 决定移除，见下，实施待做）；
   - Python Mac 客户端归档 `backup/mac-app/`，Tauri 成为唯一 Mac 客户端；
   - **部署到公网 118.193.40.160**（用户指定）：源码 rsync 至 `/home/ubuntu/services/`，
     服务器 Go 1.22.2 构建，systemd 服务 `remote-voice-server` 已启动；
     服务器本机 fakephone 双角色回环全链路通过（注册→TOFU→认证→399 帧推流→发现应答正确）。
3. **三端联合本地测试（部分完成）**：本机起 server（`:9432`，发现开启），Linux 版 Tauri 客户端
   充当 Mac 端 + 真实小米手机（`adb reverse` USB 转发），三端桥接推流 **500 帧/10s（精确 50fps）**
   连续稳定。注意：该联测中 Mac 端是 Linux 临时替身，**真 Mac 客户端联合测试未做**。


### 遗留问题（按优先级）

1. **云安全组放行（用户操作，卡住公网验收）**：118.193.40.160 的云控制台放行入站
   **TCP 9432**（必须）+ **UDP 9432**（仅局域网扫描需要，公网可不放）。实测公网 TCP/UDP 均
   被安全组拦截；服务器侧监听正常、主机无防火墙（ufw inactive、iptables ACCEPT）。
   v4 已部署就绪，放行后即可三端公网联测。
2. **真 Mac 端三端联测（v4）**：Mac 重新构建安装（`scripts/build-mac.sh`）→ 连
   公网 118.193.40.160:9432（v4；注意 9-19 已换新证书，若 Mac 曾固定旧指纹会弹
   「服务器证书已更换」，点「重新信任并连接」即可——**这同时完成 PLAN-025 遗留的
   cert 弹层真机复核**）；Android 装新 APK，扫码或手填，输服务器密码。
3. Tauri macOS 真机验收：Keychain、BlackHole 实际出声、签名/公证（沿用 PLAN-010 遗留）+
   辅助功能权限授权与 Fn→语音输入真机效果（PLAN-019）；两端必须安装新版。
4. 音频演化方向（远期）：UDP+Opus+FEC、反向声道、波形真实数据源。

## 部署机备忘（118.193.40.160，ubuntu@，免密 sudo）

- 源码：`/home/ubuntu/services/`（**该目录即仓库根**，与开发机 rsync 同步；
  **标准同步命令**（红灯⚠：`--exclude server/data/` 防止本地测试证书覆盖公网
  证书——2026-09-19 实际踩过；禁用 `--delete`——曾险些误删 ThinkTime）：
  ```
  rsync -av --exclude ThinkTime --exclude .git --exclude node_modules \
    --exclude target --exclude .gradle --exclude build --exclude dist \
    --exclude local.properties --exclude server/data/ --exclude server/server \
    ./ ubuntu@118.193.40.160:/home/ubuntu/services/
  ```
  然后服务器上 `cd server && go build -o server-linux . && sudo systemctl
  restart remote-voice-server`。
- 服务：`/home/ubuntu/services/server/server-linux` + systemd `remote-voice-server`
  （User=ubuntu，WorkingDirectory=server/，`-addr :9432 -data ./data`，已 enable；
  Description 已改 v4）。**v3 二进制备份**：`server-linux.v3.bak`（回滚用）。
- **v4 已于 2026-09-19 21:16 部署**：启动横幅应为「客户端接入（共两样，别无其他）」。
- 数据目录 `server/data/` 含 TLS 私钥与设备登记，勿删、勿被 rsync 覆盖。
- 服务器 Go：apt 的 golang-go 1.22.2（满足 go.mod）。
- 历史残留：services 根目录有 8 月旧部署文件（旧 server-linux、token.txt 等），未清理，
  用户确认后可删。
- 证书指纹（2026-09-19 起新）：`4701635ee29ce5cb2c24ae588c137f528d74c5d4119eed9045c3785e20f543c3`。
  旧指纹 `d123ce4d…`（9-17 生成）已作废：9-19 rsync 误将开发机测试证书覆盖到
  data/，处置为删除后让服务器自签新证书（私钥仅存服务器）。因公网 TCP 一直被
  安全组拦截，预计没有客户端固定过旧指纹；若有，客户端点一次「重新信任并连接」即可。

## 本机环境备忘（Linux 开发机）

- Go 工具链：`~/go-sdk/go`（go1.22.10）；Python：miniconda 3.12。
- **Rust**：PATH 已在 `~/.bashrc`/`~/.profile` 末尾固定 `~/.cargo/bin` 优先（/usr/bin/cargo
  1.75 过旧，edition2024 编译失败）；cargo 已配 rsproxy 镜像（`~/.cargo/config.toml`，
  用户级不入库）——本机到 crates.io Fastly CDN 直连不通。
- **pnpm**：10.5.2；在线 install 会被 TUN 代理挂起，用 `pnpm install --offline`（store 2.3G）。
- Android 真机：小米 21091116AC（adb id 5LFAJ7TWUGUCCERO）；联测技巧 `adb reverse tcp:9432
  tcp:9432` 可让手机经 USB 连本机 server（手机不在 Wi-Fi 时）。
- **本地测试 server 数据目录规范**：统一 `-data ./data`（即 `server/data/`，持久
  目录，与 gitignore 一致）；**禁止再用 /tmp 等易失目录起 server**——每次实例
  重建=新证书=客户端一次「证书已更换」，钝化真实 MITM 告警（2026-09-19 review
  教训）。`/tmp/rv-server`（数据 /tmp/rv-joint-data）已废弃，残留待系统自清。
- Rust 单元测试已全部 tempdir 隔离，不再污染真实 `~/.config/remote-voice/config.json`
  （2026-09-19 复核确认，此前 HANDOFF 记录的已知 bug 已不存在）。

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  v3.1 增补 `FRAME_TALK=0x0A`（手机→Mac 说话状态 1 字节 0x01/0x00，server 透传不解析，
  Mac 端据此模拟 Fn 触发语音输入）；
  活跃端常量同步（Go: `server/internal/protocol`，Rust: `mac-app-tauri/src-tauri`，
  Kotlin: `android-app/.../RelayClient.kt`；Python 已归档不再同步）
- 局域网发现（PLAN-013）：UDP 与 TCP 同端口；探测串 `RV-DISCOVER-v1`（精确匹配）；
  应答 `{"v":1,"name","port"}`；同源 IP 1s 节流；server `-discovery=false` 可关
- 信任模型：整张证书 DER 的 SHA-256 hex = 内部 TOFU，按 `host:port` 隔离（本地/远程
  两份信任记录互不污染）
- 心跳：客户端 10s PING / 40s 读超时；重连指数退避封顶 30s
- 扫码配对载荷（PLAN-023）：`rv://<host>[:<port>]?s=<密码>&n=<设备名>`（n UTF-8
  percent-encoding）；Mac 端临时密码区「二维码」出码（永久密码 QR 未做，需 Rust 读
  Keychain）；Android「＋ 添加→扫码配对」解析落地（服务器切换+添加+自动重连），
  zxing:core 3.5.3 为唯一 maven 依赖（零依赖约束修订，走腾讯 maven 镜像——开发机直连
  Maven Central 不通）
- TLS 信任双模式（PLAN-024）：`rvs://host:port`=标准 TLS（系统 CA+主机名验证，无 TOFU，
  公网域名部署用）；`rv://`=自签 TOFU 不变。server `-cert/-key` 加载 LE 证书（缺省自签）。
  公网部署：域名 A 记录 → certbot certonly → systemd ExecStart 加 -cert/-key +
  renew deploy-hook 重启 → 客户端填 rvs://域名:9432（端口默认 9432，按 server 实际 -addr）。
  Mac 标准验证根来自 webpki-roots（新 Rust 依赖，Mac 构建时 cargo fetch）

- 认证：v3 Mac 设备身份（server 存 SHA-256(device_key)）→ REGISTER 秘密哈希；
  手机只带规范化秘密的 SHA-256 hex
- UI V3.2 设计决定（实施界面时必须遵守）：仅保留公网连接（单服务器地址）、
  局域网发现已降级为测试工具（详见 `docs/ui-design/v3/index.html` 设计决定区）

## 历史脉络（详见 docs/dev/INDEX.md）

PLAN-001 MVP → 002 review 修复 → 003 可观测性+Android UX → 004/005 协议 v2 →
006 仓库重组 → 007 一键连接 → 008 TOFU 免输指纹 → 009 多 Mac 自助入网(v3) →
010 Rust/Tauri 客户端 → 011 Android 连接修复 → 012 pnpm 统一 → 013 局域网发现+双地址+
归档 Python+公网部署 → 014 连接前自动扫描（**已撤销**：V3.2 只保留公网）。
