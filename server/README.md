# server · 中继服务器

公网 Linux 服务器端程序（Go 编写，标准库零依赖）：接收 Android 手机推来的音频流，
按秘密哈希注册表路由到归属 Mac，建立 1:1 桥接。

- 协议 v3 注册制：Mac 首次连接自动登记本机设备身份，之后注册（临时/永久）秘密哈希；
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
./server -addr :9432 -data ./data
```

参数：

| 参数 | 说明 |
|---|---|
| `-addr` | TLS 监听地址，默认 `:9432` |
| `-data` | 证书/密钥与 Mac 设备身份目录（**保持目录不动则证书和设备身份不丢失**） |
| `-regkeyfile` | 可选：旧 v2 Mac 兼容，从文件读取全局 regkey |
| `-regkey` | 可选：旧 v2 兼容，直接指定 regkey（仅限本地调试） |
| `-pprof` | 可选：pprof 监听地址（如 `127.0.0.1:6060`） |

旧 v2 regkey 优先级仍为：`-regkeyfile` > 环境变量 `RELAY_REGKEY` > `-regkey`。
新 v3 Mac 不读取或要求 regkey。设备身份登记保存在 `data/devices.json`。

启动后横幅打印：
1. 服务端证书指纹（SHA-256 hex）——仅供排障和人工审计；
2. 客户端配置串 `rv://<服务器IP>:9432`——App 里导入即可。

## 常见问题

- **端口被占/公网连不上**：检查防火墙入站规则与云安全组；`./server -addr :9432` 需放行 9432/TCP。
- **证书指纹变了**：删除了 `./data` 目录——保留该目录即可让 Mac/手机配置永不失效。
- **手机报「尝试过于频繁，已被临时锁定」**：按 IP 限速（60s 内 5 次失败），等锁定期或换网络；详见 [DEPLOY.md](DEPLOY.md#62-防爆破限速说明)。

## 调试工具

`cmd/fakephone`（假手机/假 Mac，无真机联调用）：

```bash
go run ./cmd/fakephone -addr 127.0.0.1:9432 -role mac                                  # 假 Mac：自动生成设备身份
go run ./cmd/fakephone -addr 127.0.0.1:9432 -role phone -secret <秘密>               # 假手机：推流 440Hz 正弦
```

`-fingerprint` 现在是可选高级参数；留空时 fakephone 首次连接自动 TOFU。旧 v2
fake Mac 如需兼容，仍可显式使用旧版本参数（新流程不需要 regkey）。
