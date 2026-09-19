# REVIEW · 构建脚本（scripts/，commit 184c1e6）

- 审查时间: 2026-09-19 20:26
- 审查对象: `scripts/build-server.sh`、`scripts/build-android.sh`、`scripts/build-mac.sh`、
  `scripts/build.sh`（引入于 commit 184c1e6，PLAN-028）
- 审查方式: 逐行静态复查 + Linux 实测复验；macOS 专属路径为逻辑审查（无 Mac 环境）
- 结论: **4 项问题已当场修复并复验通过（F1–F4），4 项记录为设计取舍/待 Mac 复核（F5–F8）**。
  修复 commit 见文末。

## 发现与处置

### F1（中 · 已修复）build-server.sh：sha256sum 在 macOS 不存在
- 现象：脚本支持在 Mac 上跑 `host` 原生构建，但产物清单用 `sha256sum`（GNU coreutils，
  macOS 默认没有，只有 `shasum -a 256`）。`set -euo pipefail` 下构建成功后清单阶段仍会
  报错退出非零——Mac 上必踩。
- 修复：新增 `hash16()` 函数，`sha256sum` 不存在时回退 `shasum -a 256`。
- 复验：Linux 重建 dist 全产物清单正常（修复不影响 Linux 路径）。

### F2（中 · 已修复）build-mac.sh --install：ditto 合并语义残留旧版本文件
- 现象：`ditto "$APP" "/Applications/Remote Voice.app"` 是**合并拷贝**，长期多版本覆盖后
  旧版本已删除的资源会残留（例如改名/删除的前端资源），可能产生诡异行为与垃圾文件。
- 修复：安装前先 `rm -rf "/Applications/Remote Voice.app"` 再 ditto（目标为脚本内常量
  固定路径，无误删面）。
- 复验：逻辑审查（需 Mac 实机）。

### F3（低 · 已修复）build-mac.sh 运行检测用 `pgrep -xq "remote-voice|Remote Voice"` 猜测进程名
- 现象：`-x` 精确匹配进程名，Tauri 打包后的实际 comm（15 字符截断、含空格的
  productName）是猜测；漏检后果是 `--install` 在 App 运行中覆盖二进制——旧进程继续跑
  旧代码，用户困惑。
- 修复：改 `pgrep -qf "Remote Voice\.app"`——完整命令行必含 .app 路径，不依赖进程名。
- 复验：逻辑审查（需 Mac 实机）。

### F4（低 · 已修复）build-android.sh：`java -version | head -1` 在 pipefail 下理论 SIGPIPE
- 现象：`java -version` 输出多行、`head` 提前退出，pipefail 下 java 若因 EPIPE 以 141
  退出会中止脚本。实践难触发（3 行输出远小于管道缓冲），属防御性修复。
- 修复：`|| true` 兜底（版本串仅用于展示）。
- 复验：debug 构建重跑正常。

### F5（信息 · 不修，记录）build.sh all 为 fail-fast
- server 失败则 android 不构建。个人工具链上失败应停下排查，不引入 continue-on-error
  的汇总复杂度；需要独立构建时用分端脚本。

### F6（信息 · 不修，记录）build-server.sh 未预检 Go ≥ 1.22
- go 对 go.mod 的版本声明自带明确报错（"go.mod requires go >= 1.22"），重复检查无增益。

### F7（信息 · 不修，记录）各脚本无 --help 参数
- 用法已在每个脚本头部注释与根 README「构建（scripts/）」章节，重复实现无增益。

### F8（信息 · 待复核）build-mac.sh 的 macOS 路径未实机验证
- `--debug`/`--install`、rustc ≥1.88 预检、pgrep 运行检测只能实机验证。逻辑按
  PLAN-021 实证门槛编写。**待用户在 Mac 上跑 `scripts/build-mac.sh --install` 复核**。

## 复验记录（修复后，Linux）

| 用例 | 结果 |
| --- | --- |
| `bash -n` 四个脚本语法检查 | ✅ |
| `scripts/build-server.sh`（全新 dist） | ✅ 四产物 + 清单（hash16 生效） |
| server-linux 可执行冒烟（127.0.0.1:19501 启动） | ✅ |
| `scripts/build-android.sh`（debug） | ✅ |
| `scripts/build.sh`（默认 all，Linux） | ✅ server+android 完成、mac 跳过提示正常 |

## 附带发现（非脚本缺陷，记录）

- 排障时曾用 `pkill -f "server-linux -addr"` 停测试进程，`-f` 匹配到自身命令行导致
  shell 被误杀（两次输出截断的根因）。与脚本无关，属操作手法；后续停止构建产物进程
  应使用精确 PID 或换不含自匹配的模式。
