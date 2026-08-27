# 交接文档 (HANDOFF)

更新时间：2026-08-28 10:40 (UTC+8)

## 项目背景（零上下文可读）

用户的 Mac 麦克风硬件损坏。本项目实现替代方案：**Android 手机采音，经用户自有的
公网 Linux 服务器中继，写入 Mac 的 BlackHole 虚拟声卡**，使任意 Mac 应用把这条链路
当作麦克风。全部自研、完全可控；不依赖域名（TLS 用自签证书 + SHA-256 指纹固定）。

- 目标原始记录：`GOAL.md`
- 设计文档目录：`docs/design/`（v1 协议、v2 交互、仓库重组）
- 计划与结果：`docs/dev/`（PLAN-XXX / -OUTCOME + INDEX.md）
- 评审文档：`docs/dev/20260826-1540-REVIEW-13aadb1-v2-impl.md`

## 当前状态（2026-08-28 仓库重组后）

三端目录命名即运行端：**server（公网服务器）/ mac-app（Mac）/ android-app（手机）**。
协议 v2 注册制（regkey 认证 + 秘密哈希注册表 + 1:1 桥接 + IP 限速防爆破），v1 已删除。

| 目录 | 运行端 | 内容 | 状态 |
|---|---|---|---|
| `server/` | 公网 Linux | Go 中继服务器（module `remote-voice/server`，产物 `server`/`server-linux`）+ `cmd/fakephone` | ✅ `go test -race` 20 用例全绿 |
| `mac-app/` | macOS | 可安装 Python 包 `remote-voice-mac`（src/macapp）：`remote-voice-gui`（图形）+ `remote-voice-recv`（CLI） | ✅ 21 测试全绿（含 H-1/H-2 回归） |
| `android-app/` | Android | Kotlin：PTT 按住说话、多设备单激活（0.2.0） | ✅ assembleDebug 通过，真机联调待执行 |

## 本阶段完成的重点（PLAN-006：仓库重组）

1. **relay→server 改名**：module/import/产物（server-linux）/DEPLOY/BUILD 文档全同步；程序
   名改用"server"，"relay"仅作中继职能说明词。
2. **mac-client + mac-receiver 合并为 mac-app**：一个包双入口，消除重复协议实现；
   `pip install -e .` 后任意目录可运行（旧 os.chdir 平级导入 hack 移除）。
3. **缺陷修复（评审 H-1/H-2）**：① 心跳线程（10s PING）+ 读超时与 AUTH 分离（40s）——
   空闲与静默桥接不再断连；② 指纹不符转 ST_FATAL 不再线程死亡。
4. **README 分工**：根 README（总览+快速启动）+ 三子 README；DEPLOY/BUILD 保留细粒度文档。

## 下一步（按优先级）

1. **M-2 拍板**：`peer-offline` 原因码是否补齐（评审文档 §三 M-2）——计划书 I6 动机失守，
   手机在 Mac 离线期反复重试可触发递增锁。三选一：补 peer-offline / 维持记档 / 离线失败不计数。
2. **真机部署验收**（用户执行）：
   - 服务器：`server/` 交叉编译 server-linux → 上传 → systemd 改 `-regkeyfile` 重启（data 目录保留防指纹漂移）
   - Mac：`pip install -e mac-app/` → `remote-voice-gui` 运行；老 v1 客户端已无法认证
   - 手机：装新 APK → 导入 rv://…?f= 配置串 → 添加 Mac 短码设备
3. mac-app 长跑观察：Keychain CLI 在真实 macOS 上行为、黑屏/休眠 PTT 切音。
4. 演化方向：UDP+Opus+FEC、反向声道、历史落 server 侧、音频波形 UI 化。

## 本机环境备忘（开发机）

- Go 工具链：`~/go-sdk/go`（go1.22.10），PATH 已写入 `~/.bashrc`，兜底软链 `~/.local/bin/go`
- Python：miniconda 3.12（`python3`/`pip3` 直用）；mac-app 已 `pip install -e` 安装（21 测试可跑）
- 预编译产物（易失）：`/tmp/opencode/server-test`、`/tmp/opencode/fakephone-test`（重编命令见根 README 开发验证段）

## 关键技术决策速查

- 协议：`[1B type][4B len BE][payload]`，音频 48kHz/mono/s16le/20ms(1920B) 裸 PCM；
  三端常量必须同步修改（Go: `server/internal/protocol`，Python: `mac-app/src/macapp/protocol.py`，
  Kotlin: `android-app/.../RelayClient.kt`）
- 信任模型：整张证书 DER 的 SHA-256 hex = 两端配置的指纹；换证书需重新分发指纹（data 目录勿动）
- 心跳：客户端 10s PING / 任一侧 40s 读超时断开；重连指数退避封顶 30s
- 认证：Mac regkey（`-regkeyfile`/`RELAY_REGKEY`）→ REGISTER 秘密哈希；手机只带规范化秘密的 SHA-256 hex
