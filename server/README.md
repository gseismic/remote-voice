# server · 中继服务器

公网 Linux 服务器端程序（Go 编写，标准库零依赖）：接收 Android 手机推来的音频流，
按秘密哈希注册表路由到归属 Mac，建立 1:1 桥接。

- 协议 v2 注册制：Mac 以 regkey 认证后注册（临时/永久）秘密哈希；
  手机认证只带秘密的 SHA-256 hex（服务器零明文）；按 IP 滑窗限速防爆破。
- 部署指南（systemd、防火墙、日志速查、故障表）：[DEPLOY.md](DEPLOY.md)

## 构建

```bash
# 要求 Go ≥ 1.22
go build -o server .                  # 本机运行
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o server-linux .   # 交叉编译给 Linux 服务器（ARM 换 arm64）
```

## 运行

```bash
openssl rand -hex 32 > regkey.txt     # Mac 注册密钥（勿泄露、勿入库）
./server -addr :9432 -data ./data -regkeyfile ./regkey.txt
```

参数：

| 参数 | 说明 |
|---|---|
| `-addr` | TLS 监听地址，默认 `:9432` |
| `-data` | 证书/密钥目录（自签证书生成于此；**保持目录不动则证书指纹不变**） |
| `-regkeyfile` | 从文件读 regkey（推荐；首行去空白） |
| `-regkey` | 直接指定 regkey（仅限本地调试，会泄露到 ps/history） |
| `-pprof` | 可选：pprof 监听地址（如 `127.0.0.1:6060`） |

regkey 优先级：`-regkeyfile` > 环境变量 `RELAY_REGKEY` > `-regkey`。
**更换 regkey 即踢掉全部 Mac 注册。**

启动后横幅打印：
1. 服务端证书指纹（SHA-256 hex）——填入 Mac 端配置；
2. 手机端配置串 `rv://<服务器IP>:9432?f=<指纹>`——App 里导入即可。

## 常见问题

- **端口被占/公网连不上**：检查防火墙入站规则与云安全组；`./server -addr :9432` 需放行 9432/TCP。
- **证书指纹变了**：删除了 `./data` 目录——保留该目录即可让 Mac/手机配置永不失效。
- **手机报「尝试过于频繁，已被临时锁定」**：按 IP 限速（60s 内 5 次失败），等锁定期或换网络；详见 [DEPLOY.md](DEPLOY.md#62-防爆破限速说明)。

## 调试工具

`cmd/fakephone`（假手机/假 Mac，无真机联调用）：

```bash
go run ./cmd/fakephone -addr 127.0.0.1:9432 -fingerprint <指纹> -role mac -regkey <regkey>   # 假 Mac：注册并统计下行
go run ./cmd/fakephone -addr 127.0.0.1:9432 -fingerprint <指纹> -role phone -secret <秘密>   # 假手机：推流 440Hz 正弦
```
