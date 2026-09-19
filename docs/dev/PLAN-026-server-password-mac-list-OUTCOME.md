# PLAN-026 · 实施结果（OUTCOME）

- 对应计划: [PLAN-026-server-password-mac-list.md](PLAN-026-server-password-mac-list.md)
- 设计: [../design/connection-simplify-20260919-overview.md](../design/connection-simplify-20260919-overview.md) §2.2/§2.3/§3
- 完成时间: 2026-09-19
- 依赖: PLAN-025

## 结果摘要

**协议 v4 上线**：服务器一个密码（Mac 设置、服务器持久化哈希），手机输一次密码即可
登录并查看服务器上全部 Mac（含离线）目录，点连接建桥；per-Mac 秘密/临时秘密/秘密类型
概念全部删除。

- **server（Go）**：`protocol.go` v4（`AuthRequest.Target`、`RegisterRequest{name,password_hash}`、
  `FrameList=0x0B`+`ListPayload`、`ReasonInvalidTarget`，删除 SecretKind/perm/temp）；
  `device_registry.go` 文件 v2（顶层 `password_hash` + `deviceRecord.Name`，兼容读 v1，
  `SetPasswordHash`/`SetDeviceName`/`Devices()`）；`server.go` 删除 regs 注册表与 v2 regkey
  路径（`-regkey` flag 一并删除）；`authPhone` = 密码常时比较（未设密码一律拒绝）→
  target 空=登录会话（无桥，可 LIST）→ target 非空=按 `devicesOnline[device_id]` 建桥
  （busy 同旧）；`handleRegister` 持久化名字与密码（last-write-wins，mu 内防并发覆盖）；
  `handleList` 目录含离线已登记设备。
- **Mac**：服务器密码复用原永久密码 Keychain 槽位（`secrets` 函数语义重命名，
  老用户永久密码无缝变为服务器密码）；`Registration{name,password_hash}`；新命令
  `set_server_password`/`clear_server_password`/`get_pairing_payload`（rv:// 载荷，QR 用）；
  `AppConfig` 删临时密码字段、新增 `auto_reconnect`（默认 true，关则网络错误即停）；
  前端重写：配对区=显示密码/复制/二维码，设置弹窗=地址/设备名/服务器密码/自动重连/语音联动。
- **Android**：`ServerStore`（服务器+密码列表，v3 全局地址+激活设备密码一次性迁移）+
  `MacInfo` 目录缓存；`RelayClient` v4（`target` 参数、`requestList`/`onMacs`、`isAuthed`
  连通标志、`invalid-secret`→「服务器密码错误」等文案）；`AudioStreamService` 重构为
  1 登录连接（5s LIST 轮询、服务器状态标记）+ N 桥接连接（与 PLAN-027 一次性落地）；
  `MainActivity` 重写：状态胶囊=服务器连通，Mac 目录 chips（◉当前/●已连接/○在线可连/
  ⧖忙碌/·离线），添加服务器=手填或扫码；`SettingsActivity` 精简为地址+密码+AEC+自动重连+清信任；
  DeviceStore.kt 删除。**自动重连开关**两端落地（默认开）。

## 验证

| 用例 | 结果 |
| --- | --- |
| `go vet ./... && go test ./...`（server 20 例，含 TestLoginSessionAndList、TestListShowsOfflineDeviceWithPersistedName、TestPhoneTargetOfflineRejected、TestRegisterLiveUpdatePassword、TestBridgeFullDuplex 同密码双 Mac、TLS 集成改写） | ✅ 全绿 |
| `cargo test`（Mac 26 例：v4 注册 payload/proto=4 断言、回环用例改密码哈希） | ✅ |
| `pnpm build`（Mac 前端） | ✅ |
| `./gradlew assembleDebug` | ✅ |
| **真实回环冒烟**（本机 server + fakephone v4）：mac 注册名字+密码 → phone 登录 LIST（device_id/name/online/busy 正确）→ 错密码 invalid-secret 拒绝 → target 建桥（auth-ok 事件到达 mac）→ 4s 推流 199 帧 / mac 收 175 帧（其余为终止时在途）→ 断开双端 offline 通知；mac 掉线后 target 建桥 invalid-target 拒绝 | ✅ 全通过 |

## 部署注意事项（已写入 server/DEPLOY.md 顶部）

- v4 破坏性升级：server 与两端 App 必须同步更新；systemd 单元若引用 `-regkey*` 需去掉。
- 多台 Mac 请设置相同的服务器密码（后注册覆盖先注册，日志留痕）。
- 域名证书签发与挂载 runbook 见 PLAN-024-OUTCOME；客户端裸地址自动探测，配证书零操作。

## 遗留

- rvs:// 显式前缀与自动探测并存（逃生门）；真机双 Mac 场景复核移交用户。
