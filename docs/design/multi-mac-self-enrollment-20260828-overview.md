# 多 Mac 自助入网设计

日期：2026-08-28
依据提交：`c3e07e141eaf03e54af8f29d0017bd2e30a5e973`
适用范围：server、mac-app、android-app
设计依据：`api-design-zh` skill

## 1. 目标与边界

目标是让同一台中转服务器承载多台 Mac，同时把用户操作简化为：

- Mac 首次运行自动生成本机设备身份，不再输入 `regkey`；
- Android 只输入 Mac 显示的配对秘密；
- 两端不再要求输入服务器证书指纹，证书身份由内部 TOFU 自动管理；
- 多台 Mac 的配对秘密在服务器上隔离路由，互不覆盖；
- server 重启后已知 Mac 的设备身份仍然有效。

本次不实现账号、租户、计费、邀请后台或管理员 Web 控制台。所谓“多系统”在本次实现中定义为同一 relay 上的多台独立 Mac；真正的多租户权限模型属于后续产品层。

## 2. 当前问题

当前 v2 的 Mac `AUTH` 使用 server 进程级唯一 `RegKey`，来源是启动参数或环境变量；server 启动时还强制要求该值，Mac GUI/CLI 也必须手工输入它。其结果是：

1. 所有 Mac 共用一个管理员级秘密；
2. 新 Mac 无法自助接入；
3. 注册表只存在内存，server 重启后必须依赖 Mac 重新注册；
4. 自签证书使客户端需要处理指纹，但这属于服务器身份而非用户凭据。

证据：`server/main.go:30-41`、`server/internal/server/server.go:243-263`、`mac-app/src/macapp/ui_main.py:148-160`。

## 3. 方案比较

### 方案 A：Mac 不带任何身份，server 接受所有 `role=mac`

优点：协议和界面最简单。

缺点：任何人都能冒充 Mac 注册同一个配对秘密，覆盖或窃听目标设备；无法判断同一 Mac 的重连；开放端口会成为无限注册入口。

结论：不采用。

### 方案 B：首次自助入网，之后使用每台 Mac 独立设备凭据

Mac 首次运行随机生成 `device_id` 与 `device_key`，保存到本机 Keychain。server 首次看到该 ID 时登记 `device_key` 哈希，后续只接受相同凭据。配对秘密仍由 Mac 生成并通过 `REGISTER` 热更新，Android 只提交配对秘密哈希。

优点：不需要管理员逐台发 `regkey`；每台 Mac 身份隔离；可持久化、可撤销；适合当前自托管 relay 的规模。

缺点：首次入网是“谁先拿到该设备身份谁被登记”的信任窗口；开放注册仍需在未来增加配额、邀请或账号控制。

结论：本次采用。

### 方案 C：账号/邀请服务下发设备凭据

用户先登录平台或使用一次性邀请，后台为 Mac 签发凭据，server 按用户/租户管理设备。

优点：首次入网控制最强，适合 SaaS 多租户。

缺点：需要账号服务、邀请生命周期、设备撤销和管理界面，超出当前三端 relay 的实现边界。

结论：作为后续演化方向，不在本次实现。

## 4. 定稿方案

### 4.1 用户接口

Mac GUI/CLI：

- 必须提供 server 地址；设备名可使用主机名；
- 首次运行自动生成身份，写入 macOS Keychain；Keychain 不可用时回落到
  `~/.config/remote-voice/device.json`（目录 0700、文件 0600）；
- 不展示、不要求 `regkey`；旧配置中的 `regkey` 忽略；
- 不展示、不要求证书指纹；首次连接自动 TOFU，后续按 server 地址固定证书；
- 继续显示临时/永久配对秘密，供 Android 输入。

Android：

- 配置串支持 `rv://host:port`，旧的 `?f=` 仍可解析但不再需要；
- 不展示证书指纹输入和清除按钮；切换 server 时只使用新地址对应的信任记录，不复用旧 server 的记录；
- 添加设备时只输入 Mac 配对秘密。

### 4.2 内部协议

协议版本提升为 v3。`AUTH` payload：

```json
{"role":"mac","proto":3,"device_id":"<随机设备ID>","device_key":"<64位随机hex>"}
```

手机仍使用同一配对秘密哈希语义：

```json
{"role":"phone","proto":3,"secret":"<配对秘密SHA-256 hex>"}
```

server 对 v3 Mac 请求：

1. 校验 `device_id` 长度/字符集和 `device_key` 的 64 位 hex 格式；
2. 若 ID 不存在，在同一把锁内持久化 `SHA-256(device_key)` 并登记；
3. 若 ID 已存在，用常量时间比较校验哈希；不匹配拒绝；
4. 同一 ID 已有在线会话时拒绝新的会话；
5. 认证成功后沿用现有 `AUTH_OK` → `REGISTER` → 1:1 bridge 流程。

server 继续接受 v2：仅当显式提供旧 `RegKey` 时允许旧 Mac 连接；v2 手机可以继续认证。该兼容路径用于滚动升级，不属于新用户流程。

### 4.3 持久化

`data/devices.json` 保存设备身份，不保存配对秘密原文或哈希；设备凭据只保存
`SHA-256(device_key)`：

```json
{
  "version": 1,
  "devices": {
    "<device_id>": {
      "credential_hash": "<sha256 hex>",
      "created_at": 1787
    }
  }
}
```

文件权限为 0600；新增设备采用临时文件写入、关闭后原子 rename。server 无法读取已有设备文件时拒绝新的 Mac 认证，避免在存储损坏时意外接受并覆盖身份。

### 4.4 TLS 身份

本次不关闭 TLS 校验。自签证书仍使用当前 TOFU：空信任记录时接受握手证书并保存其叶证书 SHA-256；后续连接按 server 地址比较。证书指纹是内部安全状态，不再是用户输入项。未来有域名时可切换系统 CA，不改变配对协议。

## 5. 查漏补缺

1. **设备 ID 被复制**：复制配置文件不会复制 macOS Keychain；若设备密钥也被复制，server 会以“同一台 Mac”处理，这是凭据复制的正常结果，后续可增加撤销 API。
2. **设备密钥丢失**：Mac 生成新 ID/密钥重新入网，旧记录保留但不再在线；后续管理 API 可删除旧记录。
3. **首次 MITM**：TOFU 仍有首次连接被冒充的窗口；连接密码在 TLS 中保护，文档必须明确该风险，不能把“免输入指纹”描述成“无需服务器身份校验”。
4. **秘密碰撞**：配对秘密仍是全局哈希索引；随机永久秘密和临时秘密应保持足够熵，后续多租户版本应把租户 ID 纳入命名空间。
5. **旧部署升级**：不删除现有 `data/`；新 server 可不带 regkey 启动。旧 Mac 仅在 server 仍配置旧 regkey 时兼容，新 Mac 走 v3 自助入网。

## 6. 验收标准

- server 不传 `-regkeyfile` 也能启动；
- 两台全新 Mac 不输入任何注册密钥即可同时认证并注册不同配对秘密；
- 手机凭各自配对秘密只能桥接到对应 Mac；
- 同一 `device_id` 使用错误 `device_key` 会被拒绝；
- server 重启并重新加载 `devices.json` 后，原 Mac 凭据仍可认证；
- GUI/CLI/Android 正常流程不要求指纹或 regkey；
- Go `test -race`、Mac pytest、Android `assembleDebug` 全部通过。
