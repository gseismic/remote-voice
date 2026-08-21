# PLAN-002-review-fixes

| 项 | 内容 |
|---|---|
| 创建时间 | 2026-08-22 |
| 依据 | Code Review 报告（对话内交付，commit 447b740）发现的高危×2 / 中危×1 / 低危×4 |
| 状态 | 待实施 |

## 1. 目标

修复 Review 发现的全部高危与中危缺陷及可低成本处理的低危项，补充回归测试，
以 `-race` 与回环集成测试双重验证后提交。修复循环直至复查无新发现。

## 2. 任务分解

### F-H1 relay role 注册 TOCTOU（高危）
- [ ] `authenticate()` 在校验通过的同一临界区内完成槽位检查+占位（check-and-reserve），返回对端会话；删除独立的 `register()` 登记语义
- [ ] `handleConn` 适配新签名：AUTH_OK 与 PEER_STATE(online) 的发送顺序不变
- [ ] `unregister()` 增加防御：仅当本 role 槽位确被本次删除清空才返回对端（否则不通知，防虚假掉线）
- [ ] 回归测试：`TestRegisterAtomicReserve`——同 role 二次认证必须收到 `role in use` 拒绝（原探针场景转正）

### F-H2 TCP_NODELAY 静默失效（高危）
- [ ] `setNoDelay()` 先经 `NetConn()` 解包 TLS 层再断言设置
- [ ] 回归测试：fake TCP 包装于 tls.Client 内，调用 `setNoDelay` 后断言底层标记已置位

### F-M1 receiver.py 并发发送撕裂（中危）
- [ ] 增加 `threading.Lock` 发送锁；AUTH/PONG/PING 全部写操作经锁串行

### 低危项
- [ ] L-2 `trimAll` → `strings.TrimSpace`
- [ ] L-1 README 标注"证书生成仅限单实例首启"
- [ ] L-3 AudioStreamService：采音启停回调统一 post 到主线程执行；`audioRecord/captureThread` 加 `@Volatile`

## 3. 验证定义

- `go build/vet/test -race ./...` 全绿（含新增回归测试）
- `python3 -m py_compile` 通过；发送锁存在性走查
- 本地回环集成测试四断言重跑全过
- 第二轮 diff 复查无新发现（发现问题→回到 §2 继续修）

## 4. 自查记录

1. 第 1 遍：authenticate 占位失败时的清理路径——占位发生在全部校验之后，失败路径均未触碰槽位 ✓；占位成功但后续 AuthOK 写失败 → 读循环退出 → unregister 正常回收 ✓。
2. 第 2 遍：PEER_STATE 时序保持"后到者触发双向通知"不变量；unregister 新防御分支在正常路径（无顶替）行为与旧逻辑一致，测试不受影响 ✓。
3. 第 3 遍：-race 对现有并发结构（每连接写锁 + roles 锁分离）应无告警；若报出即为本计划范围外新问题，进入下一修复迭代。
