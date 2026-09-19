#!/usr/bin/env bash
# 构建 remote-voice 中继服务器（Go ≥ 1.22）。
# 用法: scripts/build-server.sh [all|host|linux-amd64|linux-arm64]
#   all（默认）= linux-amd64 + linux-arm64 + 本机原生
# 产物输出到 server/dist/，并打印 sha256。
set -euo pipefail
cd "$(dirname "$0")/../server"

TARGET="${1:-all}"
OUT=dist
mkdir -p "$OUT"

command -v go >/dev/null 2>&1 || {
  echo "错误：未找到 go（需要 Go ≥ 1.22）。安装：https://go.dev/dl/ 或 brew install go" >&2
  exit 1
}

build_one() { # $1=goos $2=goarch $3=输出名
  echo "==> 构建 $3 ($1/$2)"
  CGO_ENABLED=0 GOOS="$1" GOARCH="$2" \
    go build -trimpath -ldflags="-s -w" -o "$OUT/$3" .
}

case "$TARGET" in
  all)
    build_one linux amd64 server-linux-amd64
    build_one linux arm64 server-linux-arm64
    # 兼容既有部署习惯（systemd 单元/文档使用 server-linux 名字）
    cp "$OUT/server-linux-amd64" "$OUT/server-linux"
    HOST_OS="$(go env GOOS)"; HOST_ARCH="$(go env GOARCH)"
    if [[ "$HOST_OS/$HOST_ARCH" != "linux/amd64" ]]; then
      build_one "$HOST_OS" "$HOST_ARCH" "server-$HOST_OS-$HOST_ARCH"
    fi
    ;;
  host) build_one "$(go env GOOS)" "$(go env GOARCH)" "server-$(go env GOOS)-$(go env GOARCH)" ;;
  linux-amd64)
    build_one linux amd64 server-linux-amd64
    cp "$OUT/server-linux-amd64" "$OUT/server-linux"
    ;;
  linux-arm64) build_one linux arm64 server-linux-arm64 ;;
  *) echo "未知目标: $TARGET（可选 all|host|linux-amd64|linux-arm64）" >&2; exit 1 ;;
esac

# 顺带构建调试工具 fakephone（联调用，体积小）
echo "==> 构建 fakephone（联调工具）"
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$OUT/fakephone" ./cmd/fakephone

echo
echo "产物清单："
for f in "$OUT"/*; do
  printf '  %-40s %s\n' "$f" "$(sha256sum "$f" | cut -c1-16)…"
done
echo
echo "部署提示：上传 server-linux-amd64 到服务器后 chmod +x 即可运行"
echo "  ./server-linux-amd64 -addr :9432 -data ./data"
