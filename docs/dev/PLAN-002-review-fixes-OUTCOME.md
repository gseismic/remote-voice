# PLAN-002-review-fixes-OUTCOME

| 项 | 内容 |
|---|---|
| 执行时间 | 2026-08-22 04:10 – 04:40 (UTC+8) |
| 对应计划 | docs/dev/PLAN-002-review-fixes.md |
| 依据 | Code Review 报告（commit 447b740） |
| 结果 | ✅ 全部修复并验证，复查循环收敛（3 轮） |

## 1. 修复清单

| 项 | 严重度 | 修复方式 | 验证 |
|---|---|---|---|
| H-1 role 注册 TOCTOU | 高 | `authenticate` 在单一临界区内完成校验+槽位原子占位，删除独立 `register()`；`unregister` 增加"槽位被顶替则不通知离线"防御 | 回归测试 `TestAuthenticateReservesRoleAtomically`（原探针场景转正）+ `TestUnregisterStaleSessionSkipsNotify` |
| H-2 TCP_NODELAY 静默失效 | 高 | `setNoDelay` 先经 `NetConn()` 解包 TLS 层 | 回归测试 `TestSetNoDelayUnwrapsTLS`（fakeTCP 标记断言） |
| M-1 Python 并发发送撕裂 | 中 | `receiver.py` 新增 `_send_lock`，AUTH/PING/PONG 全部写操作经锁串行 | 走查 + 回环集成回归 |
| L-1 双实例首启证书竞争 | 低 | README 部署节增加单实例首启警告 | 文档 |
| L-2 手写 trimAll | 低 | 替换为 `strings.TrimSpace` | build |
| L-3 Android 采音生命周期跨线程 | 低 | onPeerOnline/Offline 统一 post 主线程；`captureThread` 加 `@Volatile` | 走查（无 SDK 环境限制） |

## 2. 修复过程中新发现并修复的问题（循环第 2 轮）

| 发现 | 严重度 | 修复 |
|---|---|---|
| `Serve()` 写 `s.ln` 与 `Close()` 读 `s.ln` 数据竞争（`-race` 实证；生产 SIGTERM 路径同样触发） | 高 | `ln/closed` 全部纳入 `mu` 保护；`Close` 改为锁内置 `closed`+快照后锁外关闭，顺带实现幂等 |
| Close 先于 Serve 时，Serve 发布 listener 后永久阻塞 Accept | 中 | `Serve` 锁内检查 `closed`，已关闭直接返回 |
| `authenticate` 持全局锁期间做网络写（reject） | 低 | 占位判定在锁内完成，拒绝帧移到锁外发送 |

## 3. 验证证据（最终状态）

```
go build ./... && go vet ./... && go test ./... -count=1 -race
ok  remote-voice/relay/internal/protocol  1.009s
ok  remote-voice/relay/internal/server    2.973s   # 15 用例含 3 个新增回归
python3 -m py_compile mac-receiver/receiver.py    # 通过
```

本地回环集成测试（真实 TLS + 自签证书 + fakephone + receiver null 模式）：

1. ✅ 双端认证配对（对端上线通知送达）
2. ✅ 桥接转发稳定 50.0 帧/秒
3. ✅ 指纹错配拒绝（exit=1）
4. ✅ 错误 token 拒绝（exit=1）

## 4. 复查循环记录

- 第 1 轮（修复 H1/H2/M1/L1-3）：`-race` 抓出 Serve/Close 数据竞争 → 修复；新回归测试自身有裸 JSON 未加帧头的测试缺陷 → 修正测试
- 第 2 轮（diff 走查）：发现 Close-before-Serve 阻塞边界 + 锁内 IO → 修复；一次命名返回值编译冲突 → 修正
- 第 3 轮（增量复查）：无新发现 → **收敛**

## 5. 遗留

- Android 端 L-3 修复无法本机编译验证（无 SDK），与 PLAN-001 遗留项一并待真机验收

## 6. 验证来源记录（重要）

- Oracle 通道在本环境不可用：6 次尝试 = 30 分钟超时×5 + `Insufficient balance` 报错×1，从未产生任何审查结论（计费欠费所致，非任务问题）
- 独立怀疑式复核改由 general 代理完成（会话 ses_fd98f0899ffex2fioXEthfB5sL）：**VERIFIED**——6 项声明全部确认，并静态追溯 3 个回归测试在修复前代码（7a75c95）上必然失败；其提出的 2 个残留项（authenticate 缺 closed 检查、拒绝路径误导日志）已在 340594e 修复
- 复核指出的良性残留（已评估，不处理）：Python 心跳换连窗口的极端时序（自愈）；`-race` 结论以本环境实际执行记录为准（复核环境无 Go 工具链，为静态分析）
