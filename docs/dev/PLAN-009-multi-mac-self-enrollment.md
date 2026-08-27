# PLAN-009：多 Mac 自助入网与单密码客户端

日期：2026-08-28
基线提交：`c3e07e141eaf03e54af8f29d0017bd2e30a5e973`
对应设计：[multi-mac-self-enrollment-20260828-overview.md](../design/multi-mac-self-enrollment-20260828-overview.md)

## 1. 目标

把现有“server 全局 regkey + 客户端手工指纹”的 v2 使用方式改为：

- Mac 首次使用自动生成并保存独立设备身份；
- server 支持多台 Mac 自助入网，并持久化设备身份；
- Android 只输入 Mac 配对秘密；
- Mac/Android 常规流程不再输入 regkey 或证书指纹；
- 保留旧 v2 的可选兼容路径，支持分阶段升级。

## 2. 实施步骤

### 2.1 协议与 server

1. 将新协议版本设为 3，扩展 `AuthRequest` 为 `device_id/device_key`，保留 v2 `key` 兼容字段。
2. 新增 server 设备身份仓库：加载 `data/devices.json`、哈希校验、首次原子登记、0600 权限、读取/写入错误保护。
3. 调整 Mac 认证：v3 首次自助登记，后续常量时间校验，同设备在线拒绝；v2 仅在旧 `RegKey` 存在时继续工作。
4. 取消 server 启动时强制 `regkey`，旧 `-regkeyfile/-regkey/RELAY_REGKEY` 降级为兼容参数；更新启动横幅和部署文档。
5. 增加并发、重启持久化、错误凭据和多 Mac 桥接测试。

### 2.2 Mac 客户端

1. 在 `secretsgen` 中实现设备 ID/设备密钥生成、Keychain 存储和 0600 文件回落。
2. 修改 `core.RelayClient` 与 Python 协议编码，发送 v3 设备身份；维持内部 TOFU。
3. GUI 删除指纹/regkey 输入和必填校验，首次连接自动准备设备身份。
4. CLI 删除 regkey 必填项，自动准备设备身份；指纹只保留可选高级参数/内部配置。
5. 清理新配置中的旧 regkey 字段，按 server 地址持久化 TOFU 记录，补测试。

### 2.3 Android 客户端

1. `RelayClient` 改用 v3；手机认证字段仍为配对秘密哈希。
2. 设置页删除指纹状态输入/清除按钮；`rv://host:port` 为主格式，旧 `f=` 仅兼容解析。
3. 按 server 地址保存内部 TOFU 记录，切换服务器时自动重新信任，避免用户手工清理。
4. 更新提示文字和使用文档，保持添加设备/自动连接/PTT 行为不变。

### 2.4 验证与文档

1. 代码实施后逐文件 review，重点检查身份竞态、持久化失败、线程安全、旧配置迁移和 UI 入口。
2. 运行 `go test ./... -race`、`go vet ./...`、Mac pytest 和 Android `assembleDebug`。
3. 更新根 README、三端 README、server DEPLOY、HANDOFF 与 `docs/dev/INDEX.md`。
4. 生成本计划的 OUTCOME，记录实际变更、测试结果和未覆盖的真机/公网验收项。

## 3. 验收标准

- 新 server 无 regkey 参数可正常启动；
- 两台不同 Mac 可各自自动入网、注册秘密并同时在线；
- 同一设备 ID 的错误密钥不能冒用；
- server 重启后设备身份文件可恢复；
- Android/Mac 常规用户界面不要求输入指纹或 regkey；
- 旧 v2 客户端只有在配置兼容 regkey 时才可继续工作；
- 自动化测试全部通过，且没有引入未解释的构建产物或临时文件。

## 4. 计划自查记录

### 第 1 遍：范围与安全

- 已区分“隐藏用户输入”和“删除服务器认证”；服务器仍保留设备级认证。
- 已明确开放自助入网的首次信任窗口及未来账号/邀请系统不在本次范围。
- 已保留 TLS TOFU，避免为了简化输入而退化为明文或无身份 TLS。

### 第 2 遍：兼容与迁移

- 新协议使用 v3；服务端接受 v2 手机和可选旧 Mac，避免同一时间强制升级所有客户端。
- 旧配置中的 `regkey` 不参与新流程；旧 server `data/` 与证书目录不删除。
- Android 旧 `rv://...?f=` 仍可解析，指纹变为内部状态。

### 第 3 遍：失败与并发

- 设备首次登记必须在 server 状态锁内完成“检查-持久化-占位”，避免同 ID 并发抢占。
- 持久化失败不应把未落盘身份报告为成功。
- 同设备在线、错误凭据、损坏设备文件均有明确错误路径和测试。

## 5. 当前状态

- [x] 设计与计划完成
- [x] server/protocol 实施
- [x] Mac 实施
- [x] Android 实施
- [x] review 与测试
- [x] 文档、OUTCOME、commit、push
