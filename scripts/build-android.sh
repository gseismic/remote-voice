#!/usr/bin/env bash
# 构建 Android App（JDK 17 + Android SDK 35，环境要求见 android-app/BUILD.md）。
# 用法: scripts/build-android.sh [--release]
#   默认构建 debug APK（可直接安装）；--release 产出未签名 release APK（需自行签名）。
set -euo pipefail
cd "$(dirname "$0")/../android-app"

VARIANT="debug"
TASK="assembleDebug"
if [[ "${1:-}" == "--release" ]]; then
  VARIANT="release"
  TASK="assembleRelease"
elif [[ -n "${1:-}" ]]; then
  echo "未知参数: $1（可选 --release）" >&2
  exit 1
fi

command -v java >/dev/null 2>&1 || {
  echo "错误：未找到 java（需要 JDK 17）。" >&2
  echo "  Ubuntu/Debian: sudo apt install -y openjdk-17-jdk" >&2
  echo "  macOS:         brew install openjdk@17" >&2
  exit 1
}
JAVA_VER=$(java -version 2>&1 | head -1)
echo "==> Java: $JAVA_VER"

# 传 SDK 路径（local.properties 优先，其次 ANDROID_HOME）
if [[ ! -f local.properties && -n "${ANDROID_HOME:-}" ]]; then
  echo "sdk.dir=${ANDROID_HOME}" > local.properties
  echo "==> 已生成 local.properties（sdk.dir=${ANDROID_HOME}）"
fi

echo "==> gradlew $TASK"
./gradlew "$TASK"

APK_DIR="app/build/outputs/apk/$VARIANT"
echo
echo "产物清单："
for apk in "$APK_DIR"/*.apk; do
  [[ -e "$apk" ]] || { echo "  （$APK_DIR 下没有 APK）" >&2; exit 1; }
  printf '  %-60s %s\n' "$apk" "$(du -h "$apk" | cut -f1)"
done

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ "$VARIANT" == "debug" && -f "$DEBUG_APK" ]]; then
  echo
  echo "安装到已连接的手机："
  echo "  adb install -r $DEBUG_APK"
fi
if [[ "$VARIANT" == "release" ]]; then
  echo
  echo "注意：release APK 未签名，安装前需自行签名（apksigner / Android Studio）。"
fi
