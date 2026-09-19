# PLAN-028 · 一键构建脚本（scripts/）

- 日期: 2026-09-19
- 依赖 commit: 5338f41（PLAN-025/026/027 之后）
- 需求来源: 用户「为我准备好脚本，使得我可以直接运行构建」

## 方案

新增 `scripts/` 四个脚本（纯增量，不改任何产品代码），约定从仓库任意目录调用均可：

| 脚本 | 作用 | 产物 |
| --- | --- | --- |
| `build-server.sh [all\|host\|linux-amd64\|linux-arm64]` | Go 交叉编译（CGO=0/-trimpath/-s -w）+ fakephone | `server/dist/server-linux{-amd64,-arm64}`、`server-linux`（兼容既有 systemd 部署名）等 |
| `build-android.sh [--release]` | gradlew assembleDebug（默认）/assembleRelease | `app/build/outputs/apk/{debug,release}/*.apk`，debug 附 adb install 提示 |
| `build-mac.sh [--debug] [--install]` | ⚠️ 仅 macOS：pnpm install + `pnpm tauri build`；rustc ≥1.88 预检；`--install` 拷入 /Applications（运行中则跳过） | `src-tauri/target/{release,debug}/bundle/macos/Remote Voice.app` + dmg |
| `build.sh [all\|server\|android\|mac]` | 总入口：按当前 OS 构建可构建项（Linux=server+android，Mac=全部），`mac` 在非 macOS 明确报错 | — |

要点：`server-linux` 兼容名保持用户现有 systemd/部署流程不变；工具链缺失/过旧时
给出可操作的安装提示（含本仓库已实证的 rustc ≥1.88 门槛与 Linux 上 cargo PATH 前置问题）。

## 自查

1. Go 构建必须在 `server/`（go.mod 所在）目录执行——脚本内 cd 处理（实施中发现并修复）。
2. `build.sh` 的分段函数只打标题不吞命令（实施中发现 run() 误执行标签的笔误并修复）。
3. host 原生目标与 linux-amd64 同名冲突 → `all` 下自动去重 + `server-linux` 兼容拷贝。
4. `server/dist/` 加入 .gitignore（构建产物不入库）。
