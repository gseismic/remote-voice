# PLAN-030 结果：本机证书恢复链路修复与验收

- 计划：`docs/dev/PLAN-030-cert-recovery-local.md`
- 完成时间：2026-09-19 22:32（Asia/Shanghai）
- 结论：完成。本次问题的业务代码修复已存在于当前源码（上一轮 PLAN-029 的 `83f5602`）；本次完成旧 App 替换、服务端证书目录固定、本机配置恢复及 macOS 实机验收，没有重复修改业务代码。

## 一、实际处置

1. 执行 `scripts/build-mac.sh`，成功构建并安装当前源码到 `/Applications/Remote Voice.app`。
   `/Applications/Remote Voice.app/Contents/MacOS/remote-voice-tauri` 与构建产物的 SHA-256 均为：
   `b64dbf49deea246ae06b8f0bf4e5f0e8c7ca140b5788e05df20bdae7c02d2ebf`。
2. 本机 server 使用固定目录 `server/data/` 启动，没有继续使用 `/tmp/rv-joint-data`。
   两次停止/重启输出的证书指纹均为：
   `1f82448256c58a22d2959525705c210d8ee7ccfb93f29499ee1b9e353efcc596`。
3. 仅针对已确认属于本机的 `127.0.0.1:9432` 做恢复验证；没有清除或重新信任不可达、归属未确认的 `192.168.1.103:9432`。
4. 当前本机配置为 `127.0.0.1:9432`，其 pin 已更新为上述当前指纹；原有的 `.103` 旧记录仍保留，便于后续确认实际服务器后再处理。

## 二、实机验收证据

### 1. 旧 pin 确实触发保护

将 `127.0.0.1:9432` 临时恢复为旧指纹 `2d5d796b…` 后，新安装的 App 显示「服务器证书已更换」弹层。点击「暂不连接」后弹层消失，旧 pin 没有被静默改写，符合安全预期。

### 2. 「重新信任并连接」链路有效

在相同旧 pin、相同本机服务端条件下，通过 macOS 辅助功能实际点击「重新信任并连接」：

- 配置文件中的 `127.0.0.1:9432` pin 从 `2d5d796b…` 更新为 `1f824482…`；
- App 显示「已重新信任服务器，正在连接」并回到「等待手机」/「等待手机连接」；
- server 日志依次出现 TLS 首次标准校验失败、TOFU 重连握手成功、Mac 鉴权成功和 REGISTER 成功：
  `tls handshake ok` → `mac authed` → `authenticated role=mac` → `mac registered`。

这证明弹层不是简单隐藏，而是完成了“清除旧 pin → 显式重连 → 保存当前证书 → 正常注册”的完整恢复流程。

### 3. 代码与根因对应关系

- `mac-app-tauri/web/app.js:557-566` 已绑定两个弹层按钮；重新信任会先调用 `clear_server_trust`，再调用 `connect`，暂不连接会调用 `disconnect`。
- `mac-app-tauri/src-tauri/src/relay.rs:413-439` 在已有 pin 与实际证书不一致时返回 `cert-changed`，不会自动接受新证书。
- `server/internal/selfcert/selfcert.go:39-57` 在证书和私钥同时存在时复用它们；因此必须固定 `-data` 目录，不能让同一地址在不同临时实例之间漂移。

本次现象的根因仍是“同一地址切换了 server 实例/证书”和“运行中的 `/Applications` 旧包未包含按钮绑定”，不是应当关闭证书校验或自动接受未知证书。

## 三、验证结果

- `scripts/build-mac.sh`：通过，成功生成并安装 macOS App。
- `go vet ./...`：通过。
- `go test ./... -count=1 -race`：通过，server 全部测试通过。
- `cargo test`：通过，26 个测试通过，0 失败；包含证书 pin 不匹配、TOFU 回落、strict 模式等回归用例。
- `pnpm run build`：通过，Vite 生产构建成功。
- `git diff --check`：通过。

已有编译告警仍存在，但不影响本次验收：`src/protocol.rs:70` 的未使用字段，以及 `src/relay.rs:1275` 的无效比较提示。

## 四、后续使用边界

- 本次本机验收使用 `127.0.0.1:9432`，不是公网链路；公网地址仍需先确认安全组 TCP 9432 已放行，并在证书确实由用户更换时才执行重新信任。
- `192.168.1.103:9432` 当前不可达且旧 pin 未清除。若要继续使用该地址，应先在该机器上以固定 `server/data` 启动正确实例，再核对实际指纹；不要直接点击重新信任未知端点。
- 本地 server 当前以 `server/data` 运行，App 已使用新安装包连接并完成 Mac 注册。
