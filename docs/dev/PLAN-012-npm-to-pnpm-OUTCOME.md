# PLAN-012 实施结果：Tauri 客户端包管理器从 npm 切换为 pnpm

实施日期：2026-09-17
基线提交：`70aade4`
对应计划：[PLAN-012-npm-to-pnpm.md](PLAN-012-npm-to-pnpm.md)

## 1. 用户意图

用户明确指示："凡是涉及 npm 的，全部改为 pnpm"。目标是 Tauri 客户端（mac-app-tauri）的
依赖安装、脚本执行、文档指引全部统一到 pnpm，避免双包管理器混用导致的锁文件与行为漂移。

## 2. 实施内容

- `mac-app-tauri/src-tauri/tauri.conf.json`：`beforeDevCommand` → `pnpm dev`，
  `beforeBuildCommand` → `pnpm build`（Tauri CLI 在 dev/build 前执行这两条，改这里才能保证
  `pnpm tauri dev/build` 全链路无 npm）。
- `mac-app-tauri/package.json`：增加 `"packageManager": "pnpm@10.5.2"`，corepack 环境
  自动固定版本，防止协作者混用。
- `mac-app-tauri/README.md`：安装/构建/开发验证命令改 pnpm，新增 pnpm 前置说明
  （`corepack enable pnpm` 或 `npm install -g pnpm`——仅作 pnpm 自身引导安装，属唯一保留
  的 npm 字样）。
- 根 `README.md`：快速体验与开发验证两处命令改 pnpm。
- `docs/design/tauri-mac-client-20260828-overview.md`：验证清单构建命令改 pnpm。
- `docs/HANDOFF.md`：更新时间与当前计划链接；环境备忘新增 pnpm/Rust 工具链说明
  （计划实施中增量，已同步回计划文件 §1）。
- 锁文件：`git rm mac-app-tauri/package-lock.json`，`pnpm install` 生成
  `mac-app-tauri/pnpm-lock.yaml` 入库；依赖按 semver 重新解析（vite 8.3.0、lucide 1.46.0、
  @tauri-apps/api 2.11.1、@tauri-apps/cli 2.11.4），node_modules 重建为 pnpm 布局。
- 历史记录不改写：PLAN-010/011、REVIEW、INDEX 既有条目保留当时以 npm 验证的记载。

## 3. 实施中发现并处理的环境问题（开发机，非仓库变更）

1. **PATH 上旧 cargo 挡住新工具链**：`/usr/bin/cargo` 1.75 排在 `~/.cargo/bin/cargo`
   1.98（rustup）之前，旧版编译报 `edition2024` 不支持。构建须
   `export PATH="$HOME/.cargo/bin:$PATH"`（8 月底 PLAN-010 构建即用新工具链）。
2. **crates.io Fastly CDN 直连不通**：本机经 TUN 代理（198.18.x 网段），到
   static.crates.io / index.crates.io 的 TCP 建连后无响应，curl 同样复现；pnpm 在线安装
   也会被挂起。已写用户级 `~/.cargo/config.toml` 配置 rsproxy.cn 镜像后 `cargo fetch`
   正常；pnpm 用 `--offline` 从本地 store（2.3G）安装成功。两处均为用户级配置，不入仓库。
3. **Cargo.lock 相对 8 月构建有新增依赖**：tauri 2.11.5 → reqwest 0.13.4 →
   http-body-util 等不在本地 cargo 缓存，经镜像补齐后构建通过。

## 4. 验证结果

- `pnpm install --offline`：Done in 466ms，pnpm-lock.yaml 生成。
- `pnpm run build`：vite 8.3.0，✓ built in 134ms，dist/ 产物正常。
- `node --check web/app.js`：通过。
- `pnpm run tauri build`：release 编译 17.85s，产出 deb / rpm / AppImage 三个 Linux
  bundle，对齐 PLAN-010 的 Linux 验证口径。
- 残留检查：`grep -rn "npm"`（排除历史文档/依赖目录）仅剩 pnpm 引导安装说明与
  PLAN-012 文档本身，活文档与配置无 npm 命令残留。
- `git status`：package-lock.json 删除、pnpm-lock.yaml 新增。

## 5. 遗留与说明

- macOS 真机侧的 pnpm 构建路径未验证；用户在 Mac 上按 README 前置装好 pnpm 后
  `pnpm run tauri build` 即可，属真机验收（PLAN-011 遗留）的一部分。
- Rust 代码零改动，故未重跑 cargo fmt/clippy/test（tauri build 已完成 release 编译验证）。
