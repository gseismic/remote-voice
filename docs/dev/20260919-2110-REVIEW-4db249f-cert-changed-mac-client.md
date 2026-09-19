# REVIEW · Mac 客户端「服务器证书已更换」现象系统排查

- 依赖 commit: `4db249f`（HEAD，main）
- 编写时间: 2026-09-19 21:10 (UTC+8)
- 模型/版本: ZCode（GLM-5.3）
- 研究的问题: 用户报告"Mac 客户端显示服务器证书已更换"，要求系统 review 代码
- 排查手段: 代码走查（server / mac-app-tauri / android-app）+ 部署机 118.193.40.160
  实测（ssh 只读检查）+ 本机进程与数据目录实测

## 一、结论摘要（TL;DR）

1. **提示本身不是误报，是 TOFU 机制按设计工作**：Mac 连的服务器实例换过证书
   （本机测试 server 的 data 目录漂移），客户端检测到指纹与上次记录不一致，
   弹出 PLAN-025 设计的恢复弹层。
2. **但弹层是坏的（P0）**：`web/app.js` 从未给「重新信任并连接」「暂不连接」两个
   按钮绑定事件——用户点按钮无任何反应，被困在弹层里。PLAN-025 OUTCOME 声称的
   "`重新信任并连接` = clear_server_trust + connect" 前端侧没有实现完成。
3. **公网服务器（118.193.40.160）证书从未变化**（实测指纹仍为 `d123ce4d…`，
   与 HANDOFF 记录一致，有效期至 2036 年），且 v4 代码尚未部署（横幅仍为 v3 旧
   文案、日志无任何客户端连接记录）。
4. 连带风险：v4 客户端（proto=4）连 v3 服务器会被 `unsupported proto version`
   拒绝——v4 上线前必须同步部署服务器（PLAN-026 已写明，尚未执行）。

## 二、触发链路（代码证据）

提示的产生路径（全部有代码依据）：

1. Auto 模式（裸地址默认）先标准 CA+主机名验证，自签证书必失败后回落 TOFU：
   `mac-app-tauri/src-tauri/src/relay.rs:392-409`（`TlsMode::Auto` 分支）。
2. TOFU 路径计算实际指纹，与本地 pin 比对，不符即 `cert-changed` Fatal：
   `relay.rs:413-424`。
3. pin 来自 `~/.config/remote-voice/config.json` 的 `fingerprints` 表，按
   `host:port` 隔离：`src-tauri/src/config.rs:232-252`（`trusted_fingerprint`）。
4. controller 把 `cert-changed` 标为 fatal 但不进错误事件流（前端有专用恢复
   入口）：`src-tauri/src/controller.rs:612-623`。
5. 前端在 `fatal + fatal_code==="cert-changed"` 时显示弹层：
   `web/app.js:243-245`、`web/index.html:207-227`。

**服务器侧证书何时会变**：`server/internal/selfcert/selfcert.go:39-57`——只在
`dataDir/cert.pem`+`key.pem` 任一缺失时重新生成；两者存在即复用。即证书变化
⇔ data 目录被删/换路径/换实例。

## 三、根因分析（环境实测证据）

### 3.1 公网服务器排除

ssh 实测（2026-09-19 21:00 前后）：

- 当前指纹 `D1:23:CE:4D:…:A4:7F` = `d123ce4dc4b8…7aa47f`，与 HANDOFF 记录
  一致；`notAfter=2036-09-13`；`data/` 下 cert.pem/key.pem 完好（Sep 17 生成）。
- systemd 单元：`ExecStart=…server-linux -addr :9432 -data ./data`（无 -cert/-key）。
- **日志显示最后一次重启是 Sep 18 10:49（本地时区），启动横幅是 v3 旧文案**
  （"服务端证书指纹（…客户端首次连接自动信任）"），与 v4 的
  `server/main.go:67-76` 新横幅不符 ⇒ **部署机跑的还是 9-18 rsync 的 v3 二进制**。
- 该次重启之后**没有任何 accepted/tls handshake 日志** ⇒ 近期没有任何客户端
  （包括该 Mac）连上过公网服务器；结合 HANDOFF 遗留问题 #1（安全组未放行
  TCP 9432，公网实测被拦），Mac 报 cert-changed 时**不可能**是在连公网服务器
  （被安全组拦截只会报连接超时，走不到 TLS 指纹比对）。

### 3.2 本机测试服务器证书漂移（真正根因）

本机（192.168.1.103，局域网内 Mac 可达）实测：

| data 目录 | 证书指纹（SHA-256 DER） | 证书生成时间 | 说明 |
| --- | --- | --- | --- |
| `/tmp/rv-joint-data/` | `67b2051d…9b9ec8` | Sep 18 01:37 | 9-18 三端联测用的 `/tmp/rv-server`，**重启即失**（HANDOFF 自己记录）；devices.json 更新于 Sep 18 12:29 |
| `server/data/` | `45d1d730…ce4e45` | Sep 18 01:56 | 当前进程在用 |
| （公网 `…160:server/data`） | `d123ce4d…a47f` | Sep 17 | 未变 |

- 当前进程 PID 730783：`dist/server-linux-amd64 -addr :9432 -data ./data`，
  cwd=`server/`，二进制 mtime **Sep 19 20:56**（今天刚构建的 v4），监听 `*:9432`。
- 同一地址 `192.168.1.103:9432` 在 9-18 由 `/tmp/rv-server`（数据在 `/tmp`，
  指纹 `67b2051d…`）服务，今天由 `server/data` 实例（指纹 `45d1d730…`）服务。
  **Mac 按地址固定了旧实例的指纹，换实例即报"证书已更换"**——与现象完全吻合。

结论：**提示是正确行为；真正的问题是本地测试 server 实例/data 目录不稳定**。
`/tmp` 易失目录 + 多实例并存，每换一次实例客户端就要重新信任一次。

## 四、发现清单

### F1（P0·功能缺陷）cert 弹层按钮未绑定事件，用户被困

- 证据：`web/app.js:524-560` `bindEvents()` 绑定了 15 个元素，**不含**
  `#cert-retrust` / `#cert-dismiss`；全文件仅 `app.js:243-245` 三行涉及 cert
  （只做弹层显隐）。`grep -n "cert-retrust" web/app.js` 为 0。
- 后端命令已就绪：`controller.rs:837-839`（`clear_server_trust` 命令）、
  `controller.rs:452-481`（清 pin + active 时自动重连；fatal 不算 active，需显式
  `connect`）。
- 对照：Android 端同功能实现完整（`MainActivity.kt:90-93` 按钮→
  `AudioStreamService.retrustAndRestart`；`MainActivity.kt:223-226` 错误面板
  按钮切换）。PLAN-025 OUTCOME 声称的前端行为只在 Android 兑现。
- 影响：证书更换（合法场景：重装服务器/换证书/换测试实例）时 Mac 用户无出口；
  唯一绕行是 设置弹窗 →「清除信任」（`app.js:548-556`）再手动连接，但弹层
  本身仍然盖在界面上。
- 修复建议（待立 PLAN 实施）：
  ```js
  $("cert-retrust").addEventListener("click", () =>
    runAction(async () => {
      await call("clear_server_trust");
      return call("connect");
    }, "已重新信任并重新连接"));
  $("cert-dismiss").addEventListener("click", () =>
    runAction(() => call("disconnect"), null));  // 状态回 stopped，弹层随之消失
  ```
  注意 dismiss 不能只 `hidden=true`：下一次 `render` 会按
  `fatal+cert-changed` 重新显示（`app.js:245`）；走 `disconnect` 让状态离开
  fatal 才符合「暂不连接」语义。若不想断开底层（fatal 时本来就已停），可先
  验证 fatal 状态下 `disconnect` 的实际效果再定稿。

### F2（P1·部署缺口）公网服务器仍是 v3，v4 客户端无法使用

- 证据：见 §3.1（横幅 v3 旧文案、无连接日志）；`server/internal/protocol/protocol.go:47`
  `ProtoVersion = 4`，`server.go:238-241` 对 proto≠4 一律 `unsupported-ver` 拒绝；
  Mac 端 `src-tauri/src/protocol.rs` 发 `proto:4`（测试断言 `protocol.rs:146`）。
- 后果：Mac/Android 装了 v4 App 后连公网会被拒（错误码 unsupported-ver），
  与 cert-changed 无关但会在同一联测窗口撞上。
- 处置：按 HANDOFF 部署机备忘 rsync（**不带 --delete、排除 ThinkTime**）→
  服务器上 `go build` → `systemctl restart remote-voice-server`，确认新横幅
  出现"客户端接入（共两样，别无其他）"。

### F3（P2·工程习惯）本地测试 server 的 data 目录漂移

- 证据：见 §3.2 表格，历史上至少 3 个 data 目录；HANDOFF:101 自己记录
  "/tmp/rv-server（数据 /tmp/rv-joint-data，重启即失）"。
- 后果：每次实例切换 = 证书更换 = 客户端要重新信任，制造"狼来了"式提示，
  削弱用户对真 MITM 告警的敏感度（这正是 TOFU 的安全价值所在）。
- 建议：本地测试固定一个持久 data 目录（如 `server/data/`，即当前实例的做法）
  并停用 `/tmp` 易失实例；或测试脚本统一 `-data` 参数。当前 `server/data/` 无
  `devices.json`（今天 20:56 起的实例尚无 Mac 注册）属正常。

### F4（P3·文档过时）HANDOFF「已知 bug 待修」条目已过时

- HANDOFF:99-100 称"Rust 单元测试会把测试数据写进真实 config.json（测试未
  隔离 HOME）"。本次 grep 复查：`config.rs` / `identity.rs` / `secrets.rs` /
  `relay.rs` / `controller.rs` 测试全部使用 `tempdir()` 或内存回调，无一处调用
  无参 `config::save()/load()/config_path()`。该 bug 已在后续提交中修复，
  HANDOFF 下次更新时应删除此条。

### 复核记录：本次检查过且未发现问题的部分

- 服务器端 `selfcert.go`：生成/复用/指纹计算逻辑正确；私钥 0600 落盘、先写 key
  后写 cert（并发读到半文件的概率窗口极小，且 Load 失败会 Fatal 而非静默重签）。
- `relay.rs` Auto 回落只对 `HandshakeFailure::Cert` 回落、网络错误不二次拨号
  （`relay.rs:402-408`）——避免双倍超时，正确。
- 指纹口径两端一致：Go `sha256(cert.Certificate[0])` 与 Rust
  `Sha256::digest(peer_certificates().first())` 均为叶子 DER。
- Android 端 cert-changed 全链路（`FingerprintTrustManager.kt:35,43` →
  `RelayClient.kt:384,448` → `AudioStreamService.kt:218-224,359-365` →
  `MainActivity.kt:90-93,223-226`）实现完整。

## 五、用户当下处置（无需改代码）

1. 在 Mac 上打开 设置 →「清除信任」→ 关闭设置 → 重新连接：将重新 TOFU 固定
   当前实例（`45d1d730…`）的证书，弹层消失（状态离开 fatal 后
   `app.js:244-245` 会自动隐藏弹层）。
2. 想连公网：先完成 F2 的 v4 服务器部署 + 云安全组放行 TCP 9432（HANDOFF
   遗留 #1），Mac 会按新地址首次 TOFU，不会出现 cert-changed。

## 六、后续建议（按优先级）

1. 立项修复 F1（前端两行绑定 + 验证 dismiss 语义），随修复补一条"弹层出现 →
   点击重新信任 → 连接成功"的手工验证步骤进 PLAN 验证清单（本次事故的直接
   教训：PLAN-025 验证表只有 cargo test/grep/构建，没有 UI 交互冒烟）。
2. 执行 F2 部署（用户操作为主）。
3. F3/F4 顺手项，可在下次 HANDOFF/工作流整理时处理。
