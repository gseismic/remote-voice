# remote-voice

Mac 麦克风硬件损坏的替代方案：Android 手机采音 → 公网中继服务器 → Mac 虚拟麦克风。
全链路自研、完全可控，TLS 加密 + 证书指纹固定，无需域名。

## 架构

```
Android 手机(任意网络)       Linux 服务器(公网IP)        Mac(任意网络)
┌──────────────┐ 出站TLS   ┌─────────────────┐ 出站TLS ┌──────────────┐
│ android-app   │ ────────→│ relay (Go)       │────────→│ mac-receiver  │
│ 48k/mono/s16le│           │ token认证+桥接转发 │         │ 写入 BlackHole │
└──────────────┘           └─────────────────┘         └──────────────┘
                                                        ↑ 会议软件选 BlackHole 2ch 当麦克风
```

- 设计文档：`docs/design/voice-relay-20260822-mvp.md`（协议帧格式 §4、TLS 方案 §5）
- 线路协议：`[1B type][4B len BE][payload]`，音频为 48kHz/mono/s16le/20ms 帧裸 PCM

## 组件

| 目录 | 语言 | 说明 |
|---|---|---|
| `relay/` | Go（标准库零依赖） | 中转服务器；含调试工具 `cmd/fakephone`（正弦波假手机） |
| `mac-receiver/` | Python 3.11+ | 接收器：TLS pinning 校验 → 写入 BlackHole |
| `android-app/` | Kotlin（零第三方依赖） | 前台服务采音推流 App |

## 部署步骤

### 1. 服务器（Linux，有公网 IP）

```bash
cd relay && go build -o relay .
./relay -addr :9432 -data ./data -tokenfile ./token.txt
# 首次启动会生成自签证书并打印 64 位证书指纹 —— 记下来，两端配置要用
```

> 注意：证书仅在首次启动（data 目录为空）时生成，**不要多实例并发首启**，
> 否则会互相覆盖证书导致打印的指纹与实际不符。

token 来源优先级：`-tokenfile` > 环境变量 `RELAY_TOKEN` > `-token`（命令行传参仅限本地调试）。

### 2. Mac 接收器

```bash
brew install blackhole-2ch          # 虚拟声卡
pip install -r mac-receiver/requirements.txt
python3 mac-receiver/receiver.py \
    --server <服务器IP>:9432 \
    --token "$RELAY_TOKEN" \
    --fingerprint <relay启动时打印的64位hex> \
    --device BlackHole
```

无声卡环境可加 `--sink null` 只统计帧率用于验证链路。

### 3. Android App

```bash
cd android-app
gradle wrapper && ./gradlew assembleDebug   # 本机需 Android SDK
adb install app/build/outputs/apk/debug/app-debug.apk
```

App 内填入：服务器地址、Token、证书指纹 → 开始推流。
"回声消除"开关映射 `VOICE_COMMUNICATION` 音源（Mac 外放时建议开启，防回声）。

### 4. 验收

会议软件中把麦克风切换为 **BlackHole 2ch**，对手机说话即可。

## 开发验证

```bash
cd relay
go build ./... && go vet ./... && go test ./... -count=1
# 全链路回环测试（无需声卡与真机）：
go build -o /tmp/relay-bin . && go build -o /tmp/fakephone ./cmd/fakephone
/tmp/relay-bin -addr 127.0.0.1:19432 -data /tmp/rv-data -token t1 &
/tmp/fakephone -addr 127.0.0.1:19432 -token t1 -fingerprint <指纹> &
python3 ../mac-receiver/receiver.py --server 127.0.0.1:19432 --token t1 \
    --fingerprint <指纹> --sink null     # 应显示 ~50 帧/秒
```

## 已知限制（v2 方向）

单会话（一组 phone/mac）、TCP 队头阻塞（丢包时延迟抖动）、单向音频、无防爆破限速。
演进路径见设计文档 §9。
