# remote-voice

Mac 麦克风硬件损坏的替代方案：Android 手机采音 → 公网中继服务器 → Mac 虚拟麦克风。
全链路自研、完全可控，TLS 加密 + 证书指纹固定，无需域名。

## 架构（协议 v2 注册制）

```
Android 手机: 按住说话(PTT)          Linux 服务器(公网IP)          Mac: 图形客户端 / 接收器
┌──────────────────┐ 出站TLS ┌──────────────────────┐ 出站TLS ┌──────────────────┐
│ android-app       │ ─────→│ relay (Go)            │───────→│ mac-client (GUI)  │
│ 秘密SHA-256 hex   │        │ 秘密哈希注册表路由+桥接 │         │ 或 receiver.py    │
│ 48k/mono/s16le    │        │ Mac regkey 认证注册     │         │ 写入 BlackHole   │
└──────────────────┘        │ 按IP限速防爆破           │         └──────────────────┘
                            └──────────────────────┘            ↑ 会议软件选 BlackHole 2ch 当麦克风
```

- 设计文档：`docs/design/voice-relay-20260822-mvp.md`（v1 协议 §4）+ `docs/design/client-ui-20260826-v2.md`（v2 交互/安全 §一/§二）
- 线路协议 v2：`[1B type][4B len BE][payload]`；Mac 用 regkey 认证后注册 {密封哈希}，
  手机认证只带秘密哈希（SHA-256 hex，全程无明文）；桥接 1:1；按 IP 滑窗限速（5 失败/分钟 → 1m/5m/30m 递增锁）
- 音频仍为 48kHz/mono/s16le/20ms 帧裸 PCM，relay 不解析内容

## 组件

| 目录 | 语言 | 说明 |
|---|---|---|
| `relay/` | Go（标准库零依赖） | 中转服务器；含调试工具 `cmd/fakephone`（假手机/假 Mac） |
| `mac-client/` | Python 3.11+ + PySide6 | Mac 图形客户端：临时/永久秘密、连接历史、自动注册 |
| `mac-receiver/` | Python（CLI） | 轻接收器：TLS pinning → 写入 BlackHole（调试/服务器场景） |
| `android-app/` | Kotlin（零第三方依赖） | 前台服务 + 按住说话，多设备单激活 |

## 部署步骤

### 1. 服务器（Linux，有公网 IP）

> 逐命令详解、systemd 托管与故障排查见 **`relay/DEPLOY.md`**（v2 参数已同步）。

> 要求 Go **≥ 1.22**。若不想在服务器装 Go，可在任意机器交叉编译静态二进制后 scp 上去：
> ```bash
> cd relay
> CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o relay-linux .
> # x86_64 服务器用 amd64；ARM 服务器换 arm64
> ```

```bash
openssl rand -hex 32 > regkey.txt          # Mac 注册密钥（只给 Mac，不出服务器日志）
cd relay && go build -o relay .
./relay -addr :9432 -data ./data -regkeyfile ./regkey.txt
# 首次启动会生成自签证书并打印 64 位证书指纹（保持 ./data 目录则指纹不变）
# 同时打印手机端配置串：rv://<服务器IP>:9432?f=<指纹>（粘贴进 App 设置→导入配置串）
```

regkey 来源优先级：`-regkeyfile` > 环境变量 `RELAY_REGKEY` > `-regkey`（命令行传参仅限本地调试）。
**更换 regkey 即踢掉全部 Mac 注册**；转发前手机端只认秘密，不需要 regkey。

### 2. Mac（二选一）

**A. 图形客户端（推荐）**

```bash
brew install blackhole-2ch blackhole-16ch  # 虚拟声卡（2ch 已够）
pip install -r mac-client/requirements.txt
# 连接设置（GUI 底部）：服务器 / 指纹 / regkey.txt 内容 / 设备名
python3 mac-client/main.py
# 临时密码默认每次启动重新生成；勾选"重启后保持"可固定；永久密码经系统钥匙串管理
# 手机输入 GUI 中显示的短码即可连入；全部连接记录见"查看连接历史"
```

**B. 轻量接收器（无 GUI）**

```bash
python3 mac-receiver/receiver.py --server <服务器IP>:9432 \
    --regkey "$(cat regkey.txt)" --fingerprint <64位hex> --device BlackHole
# 每次启动自动生成并打印临时秘密（--temp-secret 可复用；--perm-secret 配永久秘密）
```

无声卡环境可加 `--sink null` 只统计帧率用于验证链路。

### 3. Android App

```bash
cd android-app
./gradlew assembleDebug   # 本机需 JDK 17 与 Android SDK，环境搭建详见 android-app/BUILD.md
adb install app/build/outputs/apk/debug/app-debug.apk
```

App 内设置（设置页）：导入服务器端打印的 `rv://…?f=指纹`；主界面"＋添加"输入 Mac 显示的
临时/永久秘密（新增即激活）。点击「连接」→ 听中间道「按住 说话」大按钮，按住即传、松开即停；
可保存多台 Mac 芯片点击切换；会议场景可在设置中开「免提常开」。

### 4. 验收

会议软件中把麦克风切换为 **BlackHole 2ch**，手机上按住说话即可。

## 开发验证

```bash
# Go 侧：构建 + 竞态检测测试（含 v2 认证/限速/热更/多Mac用例）
cd relay && go build ./... && go vet ./... && go test ./... -count=1 -race

# Mac 客户端：纯逻辑 + 真实 relay 回环集成（无需声卡）
pip install -r mac-client/requirements.txt
python3 -m pytest mac-client/tests/ -q

# legacy 接收器协议单测
python3 -m pytest mac-receiver/test_receiver.py -q

# 全链路回环（无需声卡与真机）：fakephone 双角色
go build -o /tmp/relay-bin . && go build -o /tmp/fakephone ./cmd/fakephone
openssl rand -hex 32 > /tmp/rv-regkey && /tmp/relay-bin -addr 127.0.0.1:19432 \
    -data /tmp/rv-data -regkeyfile /tmp/rv-regkey &
/tmp/fakephone -addr 127.0.0.1:19432 -role mac -regkey "$(cat /tmp/rv-regkey)" \
    -fingerprint <指纹> &        # 打印临时秘密；手机侧模式用 --role phone -secret 复用它
```

## 已知限制（v2 现状）

Mac 单桥接（一台 Mac 同时只服务一部手机；多 Mac 可并存）、TCP 队头阻塞（丢包时延迟抖动）、
单向音频（只取手机说话）。防爆破限速已按 IP 生效（v1 无）。
