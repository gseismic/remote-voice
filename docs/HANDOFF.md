# 交接文档 (HANDOFF)

更新时间：2026-08-28 16:56 (UTC+8)

## 项目背景（零上下文可读）

用户的 Mac 麦克风硬件损坏。本项目实现替代方案：**Android 手机采音，经用户自有的
公网 Linux 服务器中继，写入 Mac 的 BlackHole 虚拟声卡**，使任意 Mac 应用把这条链路
当作麦克风。全部自研、完全可控；不依赖域名（TLS 用自签证书 + 客户端内部 TOFU 固定）。

- 目标原始记录：`GOAL.md`
- 设计文档目录：`docs/design/`（协议、交互、仓库重组、多 Mac 自助入网）
- 计划与结果：`docs/dev/`（PLAN-XXX / -OUTCOME + INDEX.md）
- 评审文档：`docs/dev/20260826-1540-REVIEW-13aadb1-v2-impl.md`
- 当前计划：[docs/dev/PLAN-009-multi-mac-self-enrollment.md](dev/PLAN-009-multi-mac-self-enrollment.md)
- 当前设计：[docs/design/multi-mac-self-enrollment-20260828-overview.md](design/multi-mac-self-enrollment-20260828-overview.md)

## 当前状态（2026-08-28 多 Mac 自助入网后）

三端目录命名即运行端：**server（公网服务器）/ mac-app（Mac）/ android-app（手机）**。
协议 v3 注册制（Mac 设备身份认证 + 秘密哈希注册表 + 1:1 桥接 + IP 限速防爆破），v1 已删除。
新 Mac 首次连接自动生成 `device_id/device_key`，server 首次登记凭据哈希；之后无需输入全局
`regkey`。旧 v2 Mac 仅在 server 显式配置兼容 regkey 时可用。
一键化已落地：Android 打开即自动连接（芯片切换即重连）、Mac GUI 配置一次后启动自动连接
（PLAN-007）。两端证书身份改为按 server 地址保存的内部 TOFU（PLAN-008/009），用户不再输入
证书指纹；Android 重连失败显示中文原因。

| 目录 | 运行端 | 内容 | 状态 |
|---|---|---|---|
| `server/` | 公网 Linux | Go 中继服务器（module `remote-voice/server`，产物 `server`/`server-linux`）+ `cmd/fakephone` | ✅ `go test -race` 28 服务端/协议用例全绿 |
| `mac-app/` | macOS | 可安装 Python 包 `remote-voice-mac`（src/macapp）：`remote-voice-gui`（图形）+ `remote-voice-recv`（CLI） | ✅ 26 测试全绿（含设备自助入网、TOFU、H-1/H-2 回归） |
| `android-app/` | Android | Kotlin：PTT 按住说话、多设备单激活（0.2.0） | ✅ assembleDebug 通过，真机联调待执行 |

## 本阶段完成的重点（PLAN-009：多 Mac 自助入网）

1. **Mac 设备自助入网**：协议 v3 使用随机 `device_id/device_key`；Mac 首次连接自动生成身份，
   优先写 macOS Keychain，失败回落 `~/.config/remote-voice/device.json`（目录 0700、文件 0600）。
2. **server 多 Mac 注册**：`data/devices.json` 持久化每台 Mac 的 `SHA-256(device_key)`，新记录
   原子写入且文件 0600；同设备重复在线拒绝，错误凭据不能冒用。
3. **客户端简化**：GUI/CLI 删除 regkey 输入，Android/Mac 正常流程删除证书指纹输入；首次连接
   仍通过内部 TOFU 固定证书，按 server 地址隔离信任记录。
4. **兼容与回归**：v2 手机继续可用；旧 v2 Mac 仅在 server 配置旧 regkey 时可用；新增多 Mac、
   持久化、重启恢复、错误身份和 TOFU 回归测试。
5. **既有能力保留**：PLAN-006 的目录重组、PLAN-007 的一键连接、PLAN-008 的心跳/诊断修复均保留。

## 下一步（按优先级）

1. **真机与公网验收**（用户执行）：
   - 服务器：交叉编译 `server-linux` → 上传 → systemd 启动 `-addr :9432 -data ./data`，保留 `data/`；
   - 多台 Mac：分别启动 GUI/CLI，确认无需 regkey 即可在线并各自显示配对秘密；
   - 手机：导入 `rv://<服务器IP>:9432`，添加对应 Mac 的临时/永久秘密，确认只桥接到目标 Mac。
2. mac-app 长跑观察：真实 macOS Keychain CLI 行为、黑屏/休眠后的重连和 BlackHole 输出。
3. 后续安全演化：若 server 面向不完全互信的多用户，增加账号/邀请/配额/撤销管理；当前开放自助
   入网仍存在“首次设备身份登记窗口”，不能等同于完整 SaaS 多租户权限模型。
4. 音频演化方向：UDP+Opus+FEC、反向声道、历史落 server 侧、音频波形 UI 化。

## 本机环境备忘（开发机）

- Go 工具链：`~/go-sdk/go`（go1.22.10），PATH 已写入 `~/.bashrc`，兜底软链 `~/.local/bin/go`
- Python：miniconda 3.12（`python3`/`pip3` 直用）；mac-app 已 `pip install -e` 安装（26 测试可跑）
- 预编译产物（易失）：`/tmp/opencode/server-test`、`/tmp/opencode/fakephone-test`（重编命令见根 README 开发验证段）

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  三端常量必须同步修改（Go: `server/internal/protocol`，Python: `mac-app/src/macapp/protocol.py`，
  Kotlin: `android-app/.../RelayClient.kt`）
- 信任模型：整张证书 DER 的 SHA-256 hex = 内部 TOFU 记录；按 `host:port` 隔离，换证书需清理对应
  客户端信任记录（server `data/` 目录勿动可避免证书变化）
- 心跳：客户端 10s PING / 任一侧 40s 读超时断开；重连指数退避封顶 30s
- 认证：v3 Mac 设备身份（server 保存 `SHA-256(device_key)`）→ REGISTER 秘密哈希；手机只带
  规范化秘密的 SHA-256 hex；v2 Mac 的全局 regkey 只作可选兼容
