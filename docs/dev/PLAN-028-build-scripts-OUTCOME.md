# PLAN-028 · 实施结果（OUTCOME）

- 对应计划: [PLAN-028-build-scripts.md](PLAN-028-build-scripts.md)
- 完成时间: 2026-09-19

## 验证（本机 Linux 实测）

| 用例 | 结果 |
| --- | --- |
| `scripts/build-server.sh`（全新 dist） | ✅ server-linux / -amd64 / -arm64 / fakephone 四产物，sha256 打印 |
| 产物可执行冒烟（server-linux 起 127.0.0.1:19499） | ✅ 日志「server 启动」 |
| `scripts/build-android.sh`（debug） | ✅ app-debug.apk 2.0M + adb install 提示 |
| `scripts/build-android.sh --release` | ✅ app-release-unsigned.apk 892K + 签名提醒 |
| `scripts/build.sh all`（Linux） | ✅ server+android 完成、mac 段明确提示跳过 |
| `scripts/build-mac.sh`（在 Linux 上） | ✅ 守卫生效：明确报错并指路 |
| `scripts/build-mac.sh --debug/--install`、rustc 预检 | ⏳ 只能在 Mac 上验证（逻辑按 PLAN-021 实证门槛 rustc≥1.88 编写） |

## 使用说明

已写入根 README「构建（scripts/）」章节。典型流程：
- 发服务器：`scripts/build-server.sh` → 上传 `server/dist/server-linux`。
- 发手机：`scripts/build-android.sh` → `adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk`。
- 发 Mac：在 Mac 上 `scripts/build-mac.sh --install`。

## 遗留

- 无。
