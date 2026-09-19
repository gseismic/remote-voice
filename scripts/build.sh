#!/usr/bin/env bash
# 一键构建入口：自动判断当前机器能构建什么。
# 用法: scripts/build.sh [all|server|android|mac]
#   all（默认）= 当前机器上可构建的全部（Mac 上=全部三端；Linux 上=server+android）
# 各端详情：build-server.sh / build-android.sh / build-mac.sh（--help 见各脚本头部）
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:-all}"
OS="$(uname -s)"

section() { echo; echo "======== $1 ========"; }

case "$TARGET" in
  server) scripts/build-server.sh ;;
  android) scripts/build-android.sh ;;
  mac)
    if [[ "$OS" == "Darwin" ]]; then
      scripts/build-mac.sh
    else
      echo "错误：macOS 客户端只能在 Mac 上构建（当前是 ${OS}）。" >&2
      exit 1
    fi
    ;;
  all)
    section "server";  scripts/build-server.sh
    section "android"; scripts/build-android.sh
    if [[ "$OS" == "Darwin" ]]; then
      section "mac";   scripts/build-mac.sh
    else
      section "mac（跳过）"
      echo "当前是 ${OS}，macOS 客户端请把仓库拉到 Mac 上运行 scripts/build-mac.sh"
    fi
    ;;
  *) echo "未知目标: ${TARGET}（可选 all|server|android|mac）" >&2; exit 1 ;;
esac

echo
echo "全部完成。"
