# PLAN-029 · 实施结果（OUTCOME）

- 对应计划: [PLAN-029-cert-overlay-fix-and-deploy.md](PLAN-029-cert-overlay-fix-and-deploy.md)
- 依据 review: [20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md](20260919-2110-REVIEW-4db249f-cert-changed-mac-client.md)
- 完成时间: 2026-09-19 21:25 (UTC+8)
- 依赖 commit: 0ea41da（review 文档）

## 结果摘要

review 四项发现（F1–F4）全部处置完毕。

### F1（P0）cert 弹层按钮绑定 ✅

- `mac-app-tauri/web/app.js:559-566`：`#cert-retrust` = `clear_server_trust` →
  `connect`（fatal 不属于后端 active 状态，需显式连接）；`#cert-dismiss` =
  `disconnect`（状态离开 fatal，弹层随 render 消失——不能只隐藏，否则会被
  下一次 render 盖回）。
- 验证：
  - `pnpm build` ✅；
  - **浏览器 mock 冒烟**（node 静态服务 dist/ + 注入 `__TAURI_INTERNALS__`
    mock，脚本已弃置）4 步全过：
    1. 触发 `fatal+cert-changed` → 弹层显示 ✅
    2. 点「重新信任并连接」→ invoke 序列 `[clear_server_trust, connect]`、
       弹层隐藏 ✅
    3. 重新触发 fatal → 弹层再显示 ✅
    4. 点「暂不连接」→ invoke `disconnect`、弹层隐藏、状态回「未连接」✅
  - 说明：冒烟用 DOM 程序化 click 触发——IAB 后端的坐标/role 物理点击在该页
    面不派发事件（elementFromPoint 证明无遮挡、按钮可见有尺寸，属测试环境
    局限）；事件绑定与处理链由程序化点击完整证明。真机复核留给用户
    （见 HANDOFF 遗留 #2，可与 PLAN-025 遗留的证书更换真机场景一并验证）。

### F2（P1）v4 公网部署 ✅（含一次部署事故与处置）

- 部署过程：rsync → 服务器 `go build -o server-linux .` → restart。
- **事故**：rsync 未排除 `server/data/`，把开发机测试证书（`45d1d730…`）覆盖
  到公网 data/（原 `d123ce4d…` 私钥无法恢复）。**处置**：删除被污染密钥对，
  服务器重启时自签全新专属证书——私钥仅存服务器；因公网 TCP 一直被安全组
  拦截，预计无客户端固定过旧指纹，影响面≈0。标准 rsync 命令（含
  `--exclude server/data/`）已固化进 HANDOFF 部署机备忘。
- 部署终态（ssh 实测）：横幅「客户端接入（共两样，别无其他）」= v4 ✅；
  服务 active、监听 :9432 ✅；本机回环 TLS 握手正常（自签 CN=remote-voice-relay）✅；
  新指纹 `4701635ee29ce5cb2c24ae588c137f528d74c5d4119eed9045c3785e20f543c3`；
  v3 二进制已备份 `server-linux.v3.bak`；systemd Description 更新为 v4。
- 未做（用户操作）：云安全组放行 TCP 9432。

### F3（P2）本地测试 data 目录规范 ✅（文档落地）

- HANDOFF「本机环境备忘」：本地测试 server 统一 `-data ./data`（`server/data/`
  持久目录）；禁止 /tmp 等易失目录起 server（每次实例重建=新证书=一次
  「证书已更换」，钝化真实 MITM 告警）；`/tmp/rv-server` 标记废弃。

### F4（P3）HANDOFF 对账 ✅

- 删除「已知 bug 待修：测试污染真实 config.json」过时条目（复核确认全部测试
  已 tempdir 隔离）；更新时间/协议现状/当前状态/遗留问题（V3.2 已完成的过时
  条目清理，遗留 #2 并入 cert 弹层真机复核）。

## 验证汇总

| 项 | 结果 |
| --- | --- |
| `pnpm build`（mac-app-tauri，vite） | ✅ |
| 浏览器 mock 冒烟 4 步（弹层显示/重新信任/再显示/暂不连接） | ✅ 全过 |
| 公网 v4 横幅 + active + 监听 + TLS 握手 | ✅ |
| 公网证书私钥唯一性（仅服务器持有） | ✅ |
| HANDOFF 四处更新 | ✅ |

## 遗留

- 用户操作：云控制台放行 TCP 9432 → 三端公网联测（含 cert 弹层真机复核）。
- Mac 端需重新构建安装（`scripts/build-mac.sh`）才能拿到本次前端修复。
