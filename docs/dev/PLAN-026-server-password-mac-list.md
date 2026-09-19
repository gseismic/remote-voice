# PLAN-026 · 服务器密码 + Mac 目录（协议 v4）

- 设计：[../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.2/§2.3/§3
- 依赖 commit：PLAN-025 落地 commit
- 范围：server（Go）、mac-app、android-app —— 破坏性协议升级（v3 客户端不兼容，需两端同步发版）

## 目标

1. 一个密码：Mac 设置（存 Keychain），REGISTER 推送哈希到服务器持久化；手机输一次密码即可
   登录并查看服务器上全部 Mac（含离线），点「连接」建桥。
2. 删除 per-Mac 秘密/临时秘密/秘密类型概念；多 Mac 靠 device_id 寻址。
3. 自动重连开关（默认开）两端各加。

## 改动清单

### server（Go）

- protocol.go：`ProtoVersion=4`；`AuthRequest + Target`（json `target,omitempty`）；
  `RegisterRequest → {Name, PasswordHash json:"password_hash"}`；新增 `FrameList byte = 0x0B`、
  `MacInfo{DeviceID,Name,Online,Busy}` 与 `ListPayload`；删除 SecretKind/perm/temp 相关常量与
  `secret-expired`；auth 原因 `invalid-secret` 文案语义=密码错误。
- device_registry.go：文件 version 1→2（读兼容 1）；顶层新增 `password_hash`；
  `deviceRecord + Name`；新增 `SetPasswordHash` / `PasswordHash` / `SetDeviceName`。
- server.go：
  - 删除 `regs map`、`regEntry`、`reapplyRegister` 中秘密逻辑。
  - `authPhone`：rate limit 不变；`req.Secret`（64 hex）与 registry.PasswordHash 常时比较；
    失败 → invalid-secret（计数）。`target==""` → 登录会话（role=phone、无桥，AUTH_OK{}）；
    `target!=""` → `devicesOnline[target]` 查桥目标：离线 → invalid-target；busy（已有桥）→
    peer-busy；成功 → 建桥 + 双向 PeerState + AUTH_OK{mac:name}。
  - `handleRegister`：{name, password_hash}；name 持久化（SetDeviceName）；password_hash 非空时
    SetPasswordHash（last-write-wins，日志记录）。
  - LIST：桥接或登录会话均可请求 → 回 ListPayload（遍历 deviceStore.devices ∪ 在线信息）。
  - `detach` 清理相应简化（无 regs）。
- cmd/fakephone：同步 v4（target/密码/LIST）。
- server_test.go：全量改写受影响用例（注册流程、桥接、busy、限速、LIST、离线 Mac 目录）。

### Mac

- secrets.rs：新增 `server password` 的 Keychain 读写（独立 service 名，与旧 perm 分开）。
- config.rs：AppConfig 保留旧字段但标注废弃（skip 序列化不再必要——直接保留读取兼容，
  保存时写空）；指纹逻辑不变。
- relay.rs：`Registration { name, password_hash }`；register_payload 对应改。
- controller.rs：
  - 删 regenerate_temp/set_permanent/clear_permanent → 新 `set_server_password(password)`（写
    Keychain + 热更 REGISTER）与 `server_password_state()`。
  - 新命令 `get_pairing_payload()` → `rv://host:port?s=<pwd>&n=<name>`（读 Keychain）。
  - AppState：`temp_secret/temp_exp/temp_duration/keep/permanent_enabled` →
    `password_set: bool`。
  - 自动重连开关：`config.auto_reconnect`（默认 true）→ RelayClientOptions；false 时
    Network 失败即 Stopped（事件「已断开」）。
- 前端：设置弹窗=服务器/设备名/服务器密码/自动重连/音频设备/语音输入联动；临时密码卡、
  永久密码折叠区删除；二维码按钮改用 get_pairing_payload；事件文案同步（AuthAccepted 无 kind）。

### Android

- DeviceStore → 重构为 ServerStore 语义：`servers: [{id, host, port, password}]` +
  `macs: [{device_id, name, last_seen}]`（LIST 缓存）。旧 devices 数据迁移：首个设备
  {host,port}=全局 server，secret→password，其余丢弃。
- RelayClient：`secretHex` 语义=服务器密码哈希；构造 + `target: String`；
  新增 `requestList()` 与 `Listener.onMacs(List<MacInfo>)`；AUTH_OK target 会话含 mac 名不变。
- AudioStreamService：启动 = login 连接（状态标记）+（如有已选 Mac）bridge 连接；
  Mac 列表由 login 连接 5s 轮询 requestList → 广播/Status 供 Activity 渲染。
- MainActivity：页面=服务器状态条 + Mac 列表（在线可点「连接」，busy 显示忙碌，离线置灰）
  + 已连接 Mac 的控制卡（现 UI 迁移）；添加连接=扫码/手填（服务器+密码）。
  设置：自动重连开关、AEC 保留。
- TrustStore/指纹逻辑随 PLAN-025 不变。

## 测试

- server：`go test ./...`（改写后全绿，含 LIST/目录/last-write-wins/busy/限速用例）。
- Mac：cargo test（Registration 改造 + 回环用例改 v4；server_password 未设时 Registration
  password_hash 为空 → 服务器仍接受（只注册名字）——断言该行为）。
- Android：assembleDebug + 手测清单（扫码→密码已带→列表→连接→PTT）写入 OUTCOME。
- 回环：fakephone v4 ↔ server（target 建桥 + LIST）。

## 自查

1. 未设密码时手机必然 invalid-secret → UI 文案「服务器尚未设置密码，请在 Mac 端设置」。
2. Mac 未设密码也可注册（只报名字），目录可见但无人能连——符合渐进设置流程。
3. 登录会话也要心跳与读超时管理（复用 readLoop）。
4. 二维码 s= 密码原文；泄露面与旧方案相同（PLAN-023 已接受）。
5. 服务器 devices.json v1→v2 迁移不丢设备；密码字段缺失=未设密码。

## 实施说明（2026-09-19）

- server：`ProtoVersion=4`；AUTH+`target`（空=登录会话）；REGISTER `{name,password_hash}`；
  `LIST=0x0B`；devices.json v2（顶层 `password_hash` + `deviceRecord.Name`，兼容读 v1）；
  删除 regs/临时秘密/v2 regkey（`-regkey` flag 一并移除）；`invalid-target` 新原因码。
- Mac：服务器密码复用原「永久密码」Keychain 槽位（`secrets::save/load/clear_permanent`，
  语义变更为服务器密码）；`Registration{name,password_hash}`；`get_pairing_payload` 命令
  生成二维码载荷；设置弹窗=地址/设备名/密码/自动重连/语音输入联动；临时密码区删除。
- Android：`ServerStore`（服务器+密码，v3 数据一次性迁移）+ `MacInfo` 缓存；RelayClient
  v4（target/LIST/authed 标志/人性化文案）；服务=1 登录连接（5s LIST 轮询）+N 桥接连接
  （与 PLAN-027 一次性落地）；主界面=服务器状态胶囊+Mac 目录 chips（在线可点连接）。
- 自动重连开关：Mac 设置弹窗 + Android 设置页，默认开。
- 验证：go test 全绿（LIST/密码热更/invalid-target/限速等 20 例）、cargo test 26 例、
  assembleDebug；真实回环（本机 server+fakephone）：注册→LIST→错密码拒绝→target 建桥→
  199 帧发送/175 帧到达→上下线通知全通过。
