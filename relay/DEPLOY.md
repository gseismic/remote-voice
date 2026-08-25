# relay 中转服务器部署指南

本文档面向第一次部署 relay 的人，把 `../README.md` §1 中每条命令的作用讲清楚。

---

## 0. 先读这段：三个最容易误解的点

1. **两组编译命令是「二选一」，不是按顺序全部执行。**
   它们的目的相同——得到一个能在 Linux 服务器上运行的 `relay` 可执行文件，
   区别只是在哪里编译（见 §2 决策表）。
2. **`relay` 和 `relay-linux` 是同一个程序的两个构建产物，服务器上只需要其中一个。**
   名字不同仅仅因为编译命令里 `-o` 参数指定的输出文件名不同（详见 §3）。
3. **token 文件必须先创建再启动。**
   `-tokenfile ./token.txt` 要求该文件已存在，否则启动直接失败
   （`main.go:88` 打印 `读取 tokenfile 失败` 并退出），见 §4。

> 只想最快跑通？依次执行：§2 路线 A → §4 生成 token → §5 启动命令，共 3 步。

---

## 1. 这个程序是什么

`relay/` 目录是一个 Go 编写的中转服务器（入口 `relay/main.go`）：
在公网上监听一个 **TLS TCP 端口**，把 Android 手机推上来的音频流桥接转发给 Mac 接收器。
它需要部署在一台**有公网 IP 的 Linux 服务器**上。

---

## 2. 获取可执行文件（两条路线，二选一）

| | 路线 A：服务器上直接编译 | 路线 B：本机交叉编译后上传 |
|---|---|---|
| 前提 | 服务器装有 Go ≥ 1.22（`go.mod:3` 声明） | 任意装有 Go ≥ 1.22 的机器（服务器不需要 Go） |
| 适用 | 服务器方便装环境 | 服务器不想装任何编译环境 |
| 产物 | `relay` | `relay-linux` |

### 路线 A：服务器上直接编译

```bash
cd relay && go build -o relay .
```

| 命令片段 | 作用 |
|---|---|
| `cd relay` | 进入源码目录（`go.mod` 所在处，Go 以此定位模块） |
| `go build` | 编译 Go 包 |
| `-o relay` | 指定输出文件名为 `relay`（**名字随意**，只是个标签） |
| `.` | 编译对象：当前目录这个包，即 `main.go` 及其引用的 `internal/` |

### 路线 B：本机交叉编译后上传

```bash
# 在任意开发机上执行（不是在服务器上）
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o relay-linux .

# 上传到服务器并赋予执行权限
scp relay-linux user@<服务器IP>:/opt/remote-voice/
ssh user@<服务器IP> chmod +x /opt/remote-voice/relay-linux
```

逐项解释：

| 参数 | 作用 |
|---|---|
| `CGO_ENABLED=0` | 禁用 cgo，产出**不依赖服务器系统库（libc）的纯静态二进制**，拷到任何 Linux 都能跑 |
| `GOOS=linux` | 目标操作系统为 Linux |
| `GOARCH=amd64` | 目标 CPU 架构：x86_64 服务器用 `amd64`，ARM 服务器改为 `arm64` |
| `-trimpath` | 从二进制中抹去编译机的源码绝对路径：体积更小、不泄露本机路径 |
| `-ldflags="-s -w"` | 链接时 `-s` 去除符号表、`-w` 去除 DWARF 调试信息，体积减约三成 |
| `-o relay-linux` | 输出文件名；加 `-linux` 后缀只是人为标记「这是给 Linux 的」 |
| `.` | 同路线 A：编译当前目录的包 |

> 若选错架构（如 ARM 服务器跑了 amd64 二进制），运行时会报
> `exec format error`，回到这里换 `GOARCH=arm64` 重编即可。

---

## 3. `relay` 和 `relay-linux` 到底是什么关系？

**同一份源码（`main.go` + `internal/`）、两种编译方式、两个文件名，功能完全相同。**

用 `file` 命令实测本仓库目录下现存的两个产物：

| | `relay/relay` | `relay/relay-linux` |
|---|---|---|
| 由哪条命令产出 | 路线 A：`go build -o relay .` | 路线 B：交叉编译命令 |
| 格式 | ELF 64 位 x86-64，**动态链接** | ELF 64 位 x86-64，**静态链接**（`CGO_ENABLED=0`） |
| 调试信息 | 保留（not stripped） | 已去除（stripped，因 `-s -w`） |
| 大小 | ≈ 8.6 MB | ≈ 5.8 MB |
| 能否互换使用 | 能（x86-64 Linux 下等价） | 能，且对服务器更省事（无 libc 依赖） |

结论与建议：

1. **二选一上传到服务器即可**，不要两个都传（没有意义，浪费磁盘）。
2. 本地开发目录里的这两个文件只是历史构建遗留，**部署时不需要上传它们**，
   要么传源码走路线 A，要么自己重新交叉编译一份走路线 B。
3. 二者都是构建产物，**不应提交进 git**（`.gitignore` 已忽略 `relay/relay`，
   `relay/relay-linux` 同理）。

---

## 4. 启动前准备：创建 token 文件

token 是两端客户端连入 relay 的口令（防陌生人占用）。生成一个随机 token：

```bash
openssl rand -hex 32 > token.txt    # 生成 64 位随机 hex 写入 token.txt
cat token.txt                        # 内容记下来 —— Android App 和 Mac receiver 都要用
```

relay 读 token 的三种来源，优先级从高到低（实现见 `main.go:84-96`）：

| 优先级 | 方式 | 说明 |
|---|---|---|
| 1 | `-tokenfile ./token.txt` | **推荐**。读文件首行并去空白（`main.go:29,90`） |
| 2 | 环境变量 `RELAY_TOKEN` | 可用于容器等场景 |
| 3 | `-token xxx` 命令行直传 | **仅限本地调试**：命令行参数会暴露在 `ps` 和 shell history 里（`main.go:2-3` 注释） |

---

## 5. 启动命令逐参数解释

```bash
./relay -addr :9432 -data ./data -tokenfile ./token.txt
```

| 参数 | 作用 | 出处 |
|---|---|---|
| `-addr :9432` | TLS 监听地址。`:9432` 表示监听**所有网卡**的 9432 端口；只想内网测试可用 `127.0.0.1:9432` | `main.go:27` |
| `-data ./data` | 证书与私钥的存放目录，不存在会自动创建 | `main.go:28`、`selfcert.go:40` |
| `-tokenfile ./token.txt` | 从该文件读取认证 token（见 §4） | `main.go:29` |
| `-pprof 127.0.0.1:6060` | （可选）开启 Go 性能分析端点，排查性能问题时才需要 | `main.go:31` |

启动成功会打印 `relay 启动，监听 :9432`（`main.go:77`）。停止：`Ctrl+C`
（程序捕获 SIGINT/SIGTERM 后优雅关闭，`main.go:68-75`）。

---

## 6. 首次启动会发生什么：自签证书与指纹

首次启动（`-data` 目录里还没有证书时），relay 会：

1. 自动生成一张 **ECDSA P-256 自签证书**，有效期 **10 年**，私钥以 0600 权限落盘
   （`selfcert.go:29-35`、`selfcert.go:93`）；
2. 在 stdout 打印该证书的 **SHA-256 指纹（64 位小写 hex）**（`main.go:47-49`）。

```text
==================================================
服务端证书指纹 (SHA-256 hex)，请填入两端客户端配置:
aabbccdd...共64个字符...
==================================================
```

这个指纹必须同时填到 **Android App** 和 **Mac receiver** 的配置里——
两端靠它核对「我连的确实是这台服务器」，防止中间人攻击（TLS pinning，设计见 `selfcert.go:5-6`）。

之后每次重启都会**复用已有证书**（`selfcert.go:46-54`），指纹保持不变。

⚠️ 注意：

- **删除 `-data` 目录 = 下次启动重新生成证书 = 指纹改变**，两端客户端都要重配；
- **不要多个 relay 实例共用同一空 data 目录并发首启**，会互相覆盖证书，
  导致打印的指纹与实际生效证书不符；
- 服务器重启/换机器不影响指纹，只要 `data/` 目录还在。

### 运行日志速查（2026-08-26 起生效）

relay 对**每一次连接尝试与数据流动**都有日志，排查"是否连上"以此为准：

| 日志行 | 含义 |
|---|---|
| `accepted peer=<IP:端口>` | 收到 TCP 连接（任何来源，含扫描器） |
| `tls handshake ok / failed peer=..` | TLS 握手成功/失败；失败常见于客户端指纹填错或非 TLS 探测，**仅断开该连接，服务不受影响** |
| `authenticated role=.. peer=..` | 认证成功 |
| `rejected peer=.. reason=..` | 拒绝（token 错/协议版本不符/role 占用等） |
| `auth failed peer=.. role=.. (bad token)` | token 与 relay 启动值不一致 |
| `bridged role=..` | phone+mac 配对完成，开始桥接 |
| `stats role=phone audio_frames=N audio_bytes=M (窗口 10s)` | 每 10s 汇总的音频流量；持续出现说明链路有数据流动 |
| `disconnected role=.. peer=..` | 连接断开 |

> 判读技巧：只有 `accepted` 没有 `handshake ok` → 客户端不是 TLS 或指纹错；
> 有 `bridged` 但无 `stats` → 对端没在发音频（手机未授权麦克风或未点开始）。

---

## 7. 常驻运行（可选，生产建议）

裸跑 `./relay` 在 SSH 断开后会退出，生产环境建议用 systemd 托管：

```ini
# /etc/systemd/system/remote-voice-relay.service
[Unit]
Description=remote-voice relay server
After=network.target

[Service]
WorkingDirectory=/opt/remote-voice
ExecStart=/opt/remote-voice/relay-linux -addr :9432 -data ./data -tokenfile ./token.txt
Restart=on-failure
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now remote-voice-relay
journalctl -u remote-voice-relay -f      # 查看日志（含启动时打印的指纹）
```

别忘了放行防火墙与云安全组的 **TCP 9432** 端口：

```bash
sudo ufw allow 9432/tcp        # Ubuntu ufw
# 或 firewalld: sudo firewall-cmd --permanent --add-port=9432/tcp && sudo firewall-cmd --reload
```

---

## 8. 故障速查

| 现象 | 原因与处理 |
|---|---|
| `exec format error` | 二进制架构与服务器 CPU 不符，按 §2 路线 B 更换 `GOARCH` 重编 |
| `读取 tokenfile 失败` | token 文件不存在，先执行 §4 的 `openssl rand -hex 32 > token.txt` |
| `监听 :9432 失败: address already in use` | 端口被占用：`ss -tlnp \| grep 9432` 查看，或换 `-addr` 端口 |
| 手机/Mac 连不上（connection refused/timeout） | 防火墙或云安全组未放行 TCP 9432（见 §7） |
| 客户端报 `certificate fingerprint mismatch` | 客户端填的指纹与服务器 `data/` 里实际证书不一致：多为 data 目录被删后重新生成过，或曾多实例并发首启。以 `journalctl`/stdout 当前打印的指纹为准重新填入两端 |
| 错误：未提供 token | 未按 §4 提供任何一种 token 来源 |

---

*文档编写：2026-08-26，基于当日代码状态（main.go、internal/selfcert/selfcert.go、go.mod）。
若启动参数或证书逻辑后续变更，请同步更新本文档对应小节及行号引用。*
