#!/usr/bin/env bash
# 构建 macOS 客户端（Tauri 2：pnpm + Rust）。⚠️ 只能在 Mac 上运行。
# 用法: scripts/build-mac.sh [--debug] [--install]
#   --debug   构建调试版（更快，适合日常自用验证）
#   --install 构建完成后把 App 拷入 /Applications
# 产物：src-tauri/target/release/bundle/macos/Remote Voice.app 与 dmg。
set -euo pipefail
cd "$(dirname "$0")/../mac-app-tauri"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "错误：macOS 客户端只能在 Mac 上构建（当前是 $(uname -s)）。" >&2
  echo "在 Linux 上可构建：scripts/build-server.sh 与 scripts/build-android.sh" >&2
  exit 1
fi

BUILD_MODE="release"
DO_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --debug) BUILD_MODE="debug" ;;
    --install) DO_INSTALL=1 ;;
    *) echo "未知参数: $arg（可选 --debug --install）" >&2; exit 1 ;;
  esac
done

command -v pnpm >/dev/null 2>&1 || {
  echo "错误：未找到 pnpm。安装：corepack enable 或 brew install pnpm" >&2
  exit 1
}
command -v cargo >/dev/null 2>&1 || {
  echo "错误：未找到 cargo。安装：curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh" >&2
  exit 1
}

# 依赖锁定的 crates 需要 rustc ≥ 1.88（edition2024 系依赖）
RUSTC_VER=$(rustc --version | awk '{print $2}')
RUSTC_MAJOR=${RUSTC_VER%%.*}
RUSTC_MINOR=${RUSTC_VER#*.}; RUSTC_MINOR=${RUSTC_MINOR%%.*}
if (( RUSTC_MAJOR < 1 )) || (( RUSTC_MAJOR == 1 && RUSTC_MINOR < 88 )); then
  echo "错误：rustc $RUSTC_VER 过旧（依赖需要 ≥ 1.88）。升级：rustup update stable" >&2
  exit 1
fi
echo "==> 工具链：pnpm $(pnpm --version) / rustc $RUSTC_VER / 模式 $BUILD_MODE"

echo "==> pnpm install（首次会下载前端依赖）"
pnpm install --frozen-lockfile

# 首次构建需要 crates.io 可达（webpki-roots 等会按 Cargo.lock 拉取）
echo "==> pnpm tauri build（$BUILD_MODE）"
if [[ "$BUILD_MODE" == "debug" ]]; then
  pnpm tauri build --debug
else
  pnpm tauri build
fi

APP="src-tauri/target/$BUILD_MODE/bundle/macos/Remote Voice.app"
DMG=$(ls src-tauri/target/$BUILD_MODE/bundle/dmg/*.dmg 2>/dev/null | head -1 || true)

echo
echo "产物："
[[ -d "$APP" ]] && echo "  $APP" || { echo "  （未找到 .app，构建可能失败）" >&2; exit 1; }
[[ -n "$DMG" ]] && echo "  $DMG"

if (( DO_INSTALL )); then
  if pgrep -xq "remote-voice|Remote Voice" 2>/dev/null; then
    echo "提示：检测到 Remote Voice 正在运行，请先退出再安装（或手动覆盖）。跳过安装。" >&2
    exit 0
  fi
  echo "==> 安装到 /Applications"
  ditto "$APP" "/Applications/Remote Voice.app"
  echo "已安装 /Applications/Remote Voice.app"
fi

echo
echo "提示：未签名 App 首次打开若被 Gatekeeper 拦截，右键 → 打开 即可。"
