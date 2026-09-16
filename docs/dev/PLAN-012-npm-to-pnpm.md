# PLAN-012：Tauri 客户端包管理器从 npm 切换为 pnpm

日期：2026-09-17
基线提交：`70aade4`（PLAN-011 提交后）
用户指令：凡是涉及 npm 的，全部改为 pnpm。
环境：开发机 node v22.14.0、pnpm 10.5.2、corepack 0.31.0 可用。

## 1. 范围与原则

- **只改活文档 + 配置 + 锁文件**：
  - 配置：`mac-app-tauri/src-tauri/tauri.conf.json`（beforeDevCommand/beforeBuildCommand）、`mac-app-tauri/package.json`（增加 packageManager 固定 pnpm 版本）
  - 活文档：`mac-app-tauri/README.md`、根 `README.md`、`docs/design/tauri-mac-client-20260828-overview.md`；实施中发现开发机 cargo/pnpm 网络与工具链问题，顺带在 `docs/HANDOFF.md` 环境备忘补充（计划实施中增量）
  - 锁文件：`git rm package-lock.json`，`pnpm install` 生成 `pnpm-lock.yaml` 入库
- **不改写历史记录**：`docs/dev/PLAN-010*`、REVIEW 文档、INDEX 既有条目记录的是当时事实（当时以 npm 验证通过），保持原样；pnpm 切换后的验证结果写入本计划 OUTCOME。
- `src-tauri/Cargo.lock` 与包管理器无关，不动；`node_modules/`、`dist/` 已被根 `.gitignore` 忽略，无需改。

## 2. 实施步骤

1. `package.json` 增加 `"packageManager": "pnpm@10.5.2"`，corepack 环境下自动固定版本，防止协作者混用 npm。
2. `tauri.conf.json`：`beforeDevCommand` → `pnpm dev`，`beforeBuildCommand` → `pnpm build`（Tauri CLI 起 dev/build 前会执行这两条，改这里才能保证 `pnpm tauri dev/build` 全链路无 npm）。
3. `mac-app-tauri/README.md`：安装、构建、开发验证三处命令改为 pnpm，并补一句 pnpm 前置说明（corepack enable 或 npm i -g pnpm）。
4. 根 `README.md`：快速体验（`npm install && npm run tauri dev`）与开发验证（`npm run build`）两处改 pnpm。
5. 设计文档 `tauri-mac-client-20260828-overview.md` 验证清单中的 `npm run build` 改为 `pnpm run build`。
6. 删除 `package-lock.json`，在 `mac-app-tauri/` 执行 `pnpm install` 生成 `pnpm-lock.yaml`（node_modules 将按 pnpm 符号链接布局重建）。

## 3. 验证

- `cd mac-app-tauri && pnpm install && pnpm run build`：vite 产物 dist/ 正常生成。
- `node --check web/app.js`：通过。
- `pnpm run tauri build`：Linux bundle 完成即可对齐 PLAN-010 的 Linux 验证口径（macOS 侧验收仍待真机，不在本计划范围）。若本机缺 webkit2gtk 等系统依赖导致无法打包，降级为 `pnpm run build` + `cargo check` 并在 OUTCOME 如实记录。
- `grep -rn "npm " 排除历史文档/依赖目录`：活文档与配置中不再有 npm 命令残留（"corepack/npm i -g pnpm" 的安装说明除外）。
- `git status`：package-lock.json 删除、pnpm-lock.yaml 新增。

## 4. 交付

- OUTCOME 文件：`PLAN-012-npm-to-pnpm-OUTCOME.md`
- INDEX 追加计划-结果条目
- commit 消息包含本计划与 OUTCOME 路径，push 到 origin/main
