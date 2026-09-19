# PLAN-030：本机证书恢复链路修复与验收

- 编写时间：2026-09-19
- 目标：修复本机 Mac 客户端持续出现「服务器证书已更换」的问题，并用本机运行结果证明修复有效。
- 相关问题：本机配置仍保存 `192.168.1.103:9432` 的 `/tmp/rv-joint-data` 旧 pin；当前 Mac 实际局域网地址为 `192.168.1.101`，`.103:9432` 当前不可达。当前 `/Applications/Remote Voice.app` 早于证书弹层按钮修复，且本机 server 曾使用易失的 `/tmp` 数据目录。
- 依据：
  - `docs/dev/20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md` §3.2、F1、F3；
  - `docs/dev/PLAN-029-cert-overlay-fix-and-deploy-OUTCOME.md`；
  - `mac-app-tauri/web/app.js:557-566`；
  - `mac-app-tauri/src-tauri/src/relay.rs:413-439`；
  - `server/internal/selfcert/selfcert.go:39-57`。

## 一、验收标准

1. `/Applications/Remote Voice.app` 使用包含 cert 弹层两个按钮绑定的当前源码构建产物；旧 App 不再继续运行。
2. 本机 server 固定使用 `server/data/`，同一数据目录停止并重启后，对外提供的证书指纹保持不变。
3. 仅在确认端点属于用户自己的实例后，清除本机测试端点的旧 pin，并重新连接；本次本机验证使用 `127.0.0.1:9432`，保留不可达的 `192.168.1.103:9432` 记录，不误清除或信任未知端点。
4. 通过 Rust 单元测试、前端构建和 Go 测试；补充本机实际证书重启稳定性与客户端恢复路径的证据。
5. 不降低 TLS/TOFU 校验强度，不自动接受未知服务器证书，不修改公网服务器状态。

## 二、实施步骤

1. 只读复核当前进程、安装包、配置 pin、`server/data` 证书和工作区状态。
2. 自查计划：确认本次不需要改变协议/API；只使用已有的「清除信任→重新连接」恢复语义，避免把证书变化静默接受为普通网络错误。
3. 退出旧 Mac App，执行 `scripts/build-mac.sh`，让当前源码构建并安装到 `/Applications`。
4. 使用固定的 `server/data/` 启动本机 server；记录启动指纹，停止后用同一数据目录重启并再次记录指纹。
5. 在确认服务端指纹与本机测试实例一致后，清除 `127.0.0.1:9432` 旧 pin，再连接新版 App；检查配置文件中该地址只保存当前证书指纹。
6. 执行验证：`go vet ./...`、`go test ./... -count=1 -race`、`cargo test`、`pnpm run build`，并检查 Git 差异只包含本计划要求的文档/代码变更。
7. 写入 `PLAN-030-cert-recovery-local-OUTCOME.md`，更新 `docs/dev/INDEX.md`，提交中文 commit 并 push。

## 三、风险与回滚

- 清除 pin 是有意的安全操作，只针对已确认的本机 `127.0.0.1:9432`；若实际端点或证书无法确认，停止，不清除。`192.168.1.103:9432` 仍需用户确认实际服务器地址后再处理。
- 不删除 `server/data/`，不使用 `rm -rf` 清理用户数据；若新版构建失败，保留旧 App 但不宣称修复完成。
- 若发现 server 仍在使用其他 `-data` 目录，先停止该实例并改用 `server/data/`，不覆盖现有证书文件。
