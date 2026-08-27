# PLAN-009 实施结果：多 Mac 自助入网与单密码客户端

日期：2026-08-28
计划：[PLAN-009-multi-mac-self-enrollment.md](PLAN-009-multi-mac-self-enrollment.md)
基线提交：`c3e07e141eaf03e54af8f29d0017bd2e30a5e973`

## 1. 实际实现

### Server 与协议

- 协议版本升级为 v3。Mac AUTH 使用本机 `device_id/device_key`，手机仍只发送配对秘密的 SHA-256 哈希。
- Mac 首次连接时由客户端生成长期设备身份；server 在 `data/devices.json` 中保存设备凭据哈希。后续连接校验同一设备凭据，同一设备重复在线会被拒绝。
- 设备登记在 server 状态锁内完成检查、持久化和在线占位；设备文件损坏、权限/读写失败、错误设备凭据均进入明确失败路径，不会把未持久化的身份当作登记成功。
- server 启动不再要求全局 regkey。旧 v2 Mac 的 `-regkeyfile`、`RELAY_REGKEY` 和 `-regkey` 仍作为可选兼容入口保留。
- 保留 TLS。Mac 与 Android 不再要求用户填写证书指纹，客户端首次按 server 地址内部 TOFU，后续固定校验；旧 Android `rv://...?f=` 配置仍可解析。

### Mac 客户端

- GUI 和 CLI 首次使用自动生成设备身份，优先使用 macOS Keychain；不可用时使用权限收紧的本地文件回落。
- GUI/CLI 常规配置删除 regkey 必填要求；CLI 的指纹参数仅保留为高级兼容/诊断入口。
- 修复认证竞态：Mac 只有收到 AUTH_OK 后才发送 REGISTER，避免连接刚建立时秘密注册帧被 server 拒绝。
- 设备身份、配对秘密和按 server 地址隔离的 TOFU 记录均有持久化边界与失败处理。

### Android 客户端

- Android 认证适配 v3；设置页移除证书指纹输入和清除控件，主流程只需服务器地址与 Mac 配对秘密。
- 指纹由客户端按 server 地址内部保存和校验；服务器切换时不会复用另一地址的信任记录。
- 保持原有自动连接、设备添加、PTT 推流和旧配置串兼容解析行为。

## 2. 期间发现并修复的问题

- 证书 pinning 失败时，Mac 连接对象可能尚未进入可清理状态；已调整保存时机，确保失败连接能被正确关闭。
- 设备凭据的大小写 hex 输入统一按小写哈希，避免同一凭据因大小写差异出现异常。
- 读取旧设备文件时同步收紧目录和文件权限。
- 补充 `REGISTER` 认证门闩、设备存储失败、错误凭据、重启恢复、同设备在线拒绝和双 Mac 隔离回归测试。

## 3. 验证结果

- `cd server && go test ./... -count=1 -race`：通过。
- `cd server && go vet ./...`：通过。
- `cd server && go build ./...`：通过。
- `cd mac-app && python3 -m pytest tests/ -q`：`26 passed`。
- `cd mac-app && python3 -m py_compile $(rg --files src -g '*.py')`：通过。
- `cd android-app && ./gradlew assembleDebug`：通过；Gradle 仅报告 compileSdk 35 与当前 Android Gradle Plugin 的兼容性提示。
- `git diff --check`：通过。

## 4. 尚未完成的验收

- 尚未在两台实体 macOS 上验证 Keychain、睡眠/锁屏后的重连及 BlackHole 长时间音频输出。
- 尚未在真实 Android 设备上验证麦克风权限、前台服务、PTT 手感和长时间推流。
- 尚未完成公网部署、防火墙/安全组和跨网络多 Mac 的实测。

## 5. 当前设计边界

当前实现解决的是同一 relay 上多台 Mac 的独立设备登记与路由，不是完整 SaaS 多租户权限系统。首次设备登记仍然是开放自助入网：知道 server 地址即可尝试登记，因此未来面向互不完全信任的多用户时，还需要账号、邀请、配额、撤销和管理员审核等控制面。TLS TOFU 也保留首次连接时的理论中间人窗口。
