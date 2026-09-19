# PLAN-029 · cert 弹层修复 + v4 公网部署 + 文档对账

- 拟定时间: 2026-09-19 21:20 (UTC+8)
- 依赖 commit: 0ea41da（review 文档入库）
- 依据 review: [20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md](20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md)
- 目标: 修复该 review 的全部发现（F1–F4）

## F1（P0）Mac 前端 cert 弹层按钮绑定

文件: `mac-app-tauri/web/app.js`（唯一代码改动）

1. `bindEvents()` 追加两个绑定：
   - `#cert-retrust`「重新信任并连接」→ `clear_server_trust` 成功后显式 `connect`
     （fatal 不属于后端 active 状态，`clear_server_trust` 不会自动重连，见
     controller.rs:472-479）。复用现有 `runAction` helper（app.js:319）。
   - `#cert-dismiss`「暂不连接」→ 调用 `disconnect`。fatal 状态下底层网络线程
     已因 Fatal 退出，disconnect 是幂等收尾；状态回 `stopped` 且 `fatal_code`
     清空（controller.rs:250-255），弹层随 render 自然消失。**不能**只设
     `hidden=true`——下一次 render 会按 `fatal+cert-changed` 重新盖回
     （app.js:244-245）。
2. 顺序与失败语义：`clear_server_trust` 失败即停（toast 错误，不 connect）；
   clear 成功而 connect 失败则 toast 错误（信任已清，用户可手动连接，可接受）。

### F1 验证（补上 review 指出的"UI 交互冒烟"缺口）

- `pnpm build`（vite）通过。
- 浏览器冒烟（vite dev + 注入 mock `window.__TAURI_INTERNALS__`）：
  1. mock invoke 记录调用序列；listen 回调注入
     `{status:"fatal", fatal_code:"cert-changed", …}` → 断言弹层可见；
  2. 点「重新信任并连接」→ 断言 invoke 序列 =
     `["clear_server_trust","connect"]`，返回 stopped/connecting 态后弹层隐藏；
  3. 重新触发 fatal → 点「暂不连接」→ 断言 invoke 含 `disconnect` 且弹层隐藏。

## F2（P1）公网服务器部署 v4

目标机: ubuntu@118.193.40.160（HANDOFF 部署机备忘）

1. rsync 源码 → `/home/ubuntu/services/`：**排除 ThinkTime、禁用 `--delete`**
   （HANDOFF:80-82 红线）。
2. 服务器上构建：`cd /home/ubuntu/services/server && go build -o server-linux .`
   （服务器 Go 1.22.2；先核对 go.mod 要求）。
3. `sudo systemctl restart remote-voice-server`。
4. 验证：
   - 新横幅含「客户端接入（共两样，别无其他）」（v4 文案，main.go:67-76）；
   - `data/cert.pem` 指纹仍为 `d123ce4d…`（data 目录不动，证书必须不变）；
   - 服务 active、监听 :9432；
   - `data/devices.json` 暂不存在属正常（v4 首个注册者写入）。
5. 不做：云安全组放行（用户控制台操作，提醒即可）。

## F3（P2）本地测试 data 目录规范（文档落地）

- HANDOFF「本机环境备忘」更新：本地测试 server 统一 `-data ./data`（即
  `server/data/`，持久目录，当前实例已是该形态）；`/tmp/rv-server` +
  `/tmp/rv-joint-data` 易失实例标记为**已废弃勿再用**（残留文件留待系统重启
  自清，不做删除动作）。
- 理由（review §F3）：易失目录每换一次实例 = 证书更换 = 一次"狼来了"提示，
  钝化对真实 MITM 告警的敏感度。

## F4（P3）HANDOFF 对账

- 删除「已知 bug 待修：测试污染真实 config.json」条目（review §F4 复核：全部
  测试已 tempdir 隔离）。
- 更新"当前状态"：补 review 结论（cert 弹层修复、v4 已部署公网）、更新时间。

## 收尾

1. 写 `PLAN-029-cert-overlay-fix-and-deploy-OUTCOME.md`。
2. `docs/dev/INDEX.md` 追加本条目（只追加，含更新时间精确到分钟）。
3. commit（消息含计划/结果文件路径与逐条变更）+ push。

## 自查记录

- 第 1 遍（设计缺陷）: dismiss 最初方案是"隐藏弹层+标志位"，与 render 的
  显隐逻辑冲突（每次 render 重置 hidden），改为走 disconnect 让状态离开
  fatal——语义正合「暂不连接」，且复用后端既有行为，无新增状态。✓
- 第 2 遍（后端契约）: 核对 controller.rs `clear_server_trust`(452-481)/
  `disconnect`(246-256)/`connect`(766-769) 三命令签名与返回（均返回 AppState），
  `runAction` 对 AppState 返回值会 render。connect 返回的新状态（connecting）
  会让弹层消失。✓
- 第 3 遍（部署风险）: rsync 不带 --delete、排除 ThinkTime；构建产物名保持
  `server-linux`（systemd ExecStart 引用名不变）；重启前确认 data 目录路径
  不变（-data ./data + WorkingDirectory 不动）→ 证书不变。✓
