# PLAN-006 · 实施结果（OUTCOME）

- 对应计划: [PLAN-006-repo-restructure.md](PLAN-006-repo-restructure.md)
- 设计定稿: [../design/repo-restructure-20260828-v3.md](../design/repo-restructure-20260828-v3.md)
- 完成时间: 2026-08-28
- 状态: 全部完成（分段提交并推送）

## 提交链

| commit | 范围 | 说明 |
|---|---|---|
| `db49423` | 设计+计划 | 仓库重组设计定稿 + PLAN-006 |
| `7750c51` | Phase A | relay→server：module remote-voice/server、import/产物/gitignore/DEPLOY/BUILD 同步 |
| `5db74e7` | Phase B | mac-app 包化：src/macapp + pyproject 双入口 + H-1/H-2 修复 + 21 测试 |
| `c6c71cb` | Phase B 补 | 旧 mac-client/mac-receiver 目录删除（补记 rename） |
| 本次 | Phase C/D | 三子 README + 根 README 重写 + HANDOFF/INDEX 更新 |

## 验证汇总

- `cd server && go vet ./... && go test ./... -count=1 -race`：全绿（protocol 1.0s + server 8.6s）
- `server/server-linux` 交叉编译成功（CGO_ENABLED=0，6.1MB，git 忽略）
- `mac-app`: `pip install -e '.[test]'` 后 `pytest` **21 例全绿**（原 mac-client 14 + 原 receiver 5
  拆为 test_protocol/test_writer + 新增 2 回归：静默 15s 保活 / 错误指纹 ST_FATAL）
- CLI 真实链路冒烟：本地 server + `remote-voice-recv --sink null` + fakephone 假手机
  —— 桥接建立（auth-ok 事件）、50.0 帧/秒收流、手机退出后正确暂停收音等待重连
- GUI offscreen 冒烟：MainWindow 实例化 + show + QTimer 1.5s 退出，无异常
- `remote-voice-recv --help` 参数与旧 receiver.py 完全对齐（10 参数 + config.toml 回退）

## 实施细节记录

- 目录移动用物理 `mv` + `git add`（git rename 检测生效 80-100%，历史保留）
- `git mv` 在"先物理移动后"会报 not under version control——正确顺序是纯物理移动再统一 add
- mac-client 的 requirements.txt 被 pyproject 取代并删除；`pip install -e` 用 conda 环境
  （已含 PySide6，--no-build-isolation 跳过重建）
- CLI 初版把 REGISTERED（等待对端）误判为"已掉线"提示——冒烟中发现并修复
  （区分首达 REGISTERED 与桥接后掉线：_was_bridged 标志）
- 踩坑：`pkill -f server-test` 会匹配到携带同样字符串的 bash 自身（工具会话被杀）——
  review 探针/冒烟脚本用精确端口与 PID 管理进程

## 遗留事项

1. **M-2 peer-offline 未定**（评审文档 §三 M-2）：Mac 离线坍缩为 invalid-secret 且计入限速，
   与计划 I6 意图不符，待用户拍板（补原因码 / 记档维持 / 离线失败不计数）。
2. 真机部署与 mac-app 长跑观察（见 HANDOFF.md 下一步）。
3. Android 端运行中切换激活设备不重启服务（评审 L-4，未纳入本次）。
