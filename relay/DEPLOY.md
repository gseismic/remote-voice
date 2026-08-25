# relay 中转服务器部署指南

本文档面向第一次部署 relay 的人，把 `../README.md` §1 中每条命令的作用讲清楚。

---

## 0. 先读这段：三个最容易误解的点

1. **两组编译命令是「二选一」，不是按顺序全部执行。**
   它们的目的相同——得到一个能在 Linux 服务器上运行的 `relay` 可执行文件，
   区别只是在哪里编译（见 §2 决策表）。
2. **`relay` 和 `relay-linux` 是同一个程序的两个构建产物，服务器上只需要其中一个。**
   名字不同仅仅因为编译命令里 `-o` 参数指定的输出文件名不同（详见 §3）。
3. **regkey 文件必须先创建再启动。**
   `-regkeyfile ./regkey.txt` 要求该文件已存在，否则启动直接失败
   （`main.go` 打印 `读取 regkeyfile 失败` 并退出），见 §4。

> 只想最快跑通？依次执行：§2 路线 A → §4 生成 regkey → §5 启动命令，共 3 步。

---

## 1. 这个程序是什么

`relay/` 目录是一个 Go 编写的中转服务器（入口 `relay/main.go`）：
在公网上监听一个 **TLS TCP 端口**，把 Android 手机推上来的音频流桥接转发给 Mac。协议 v2 注册制：
Mac 以 regkey 认证后注册（临时/永久）秘密哈希，手机认证只带秘密哈希；
relay 按哈希路由到对应 Mac 建 1:1 桥接，并按来源 IP 限速防爆破（见 §6.2）。
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

## 4. 启动前准备：创建 regkey 文件

regkey 是 **Mac 端** 连入 relay 的注册密钥（防他人冒充 Mac 注册秘密、诱导手机连入）。
手机端不需要它——手机只输 Mac 显示的临时/永久秘密。生成一个随机 regkey：

```bash
openssl rand -hex 32 > regkey.txt    # 生成 64 位随机 hex 写入 regkey.txt
cat regkey.txt                        # 内容记下来 —— 填进 Mac 客户端「注册密钥」/ receiver --regkey
```

relay 读 regkey 的三种来源，优先级从高到低：

| 优先级 | 方式 | 说明 |
|---|---|---|
| 1 | `-regkeyfile ./regkey.txt` | **推荐**。读文件首行并去空白 |
| 2 | 环境变量 `RELAY_REGKEY` | 可用于容器等场景 |
| 3 | `-regkey xxx` 命令行直传 | **仅限本地调试**：命令行参数会暴露在 `ps` 和 shell history 里 |

---

## 5. 启动命令逐参数解释

```bash
./relay -addr :9432 -data ./data -regkeyfile ./regkey.txt
```

| 参数 | 作用 | 出处 |
|---|---|---|
| `-addr :9432` | TLS 监听地址。`:9432` 表示监听**所有网卡**的 9432 端口；只想内网测试可用 `127.0.0.1:9432` | `main.go` |
| `-data ./data` | 证书与私钥的存放目录，不存在会自动创建 | `main.go`、`selfcert.go` |
| `-regkeyfile ./regkey.txt` | 从该文件读取 Mac 注册密钥（见 §4） | `main.go` |
| `-pprof 127.0.0.1:6060` | （可选）开启 Go 性能分析端点，排查性能问题时才需要 | `main.go` |

启动成功会打印 `relay 启动，监听 :9432`。停止：`Ctrl+C`
（程序捕获 SIGINT/SIGTERM 后优雅关闭）。

---

## 6. 首次启动会发生什么：自签证书与指纹

首次启动（`-data` 目录里还没有证书时），relay 会：

1. 自动生成一张 **ECDSA P-256 自签证书**，有效期 **10 年**，私钥以 0600 权限落盘
   （`selfcert.go:29-35`、`selfcert.go:93`）；
2. 在 stdout 打印该证书的 **SHA-256 指纹（64 位小写 hex）**（`main.go`）。

```text
==================================================
服务端证书指纹 (SHA-256 hex)，请填入两端客户端配置:
aabbccdd...共64个字符...
==================================================
```

这个指纹必须同时填到 **Android App** 和 **Mac 客户端** 的配置里——
两端靠它核对「我连的确实是这台服务器」，防止中间人攻击（TLS pinning，设计见 `selfcert.go`）。
启动横幅同时打印**手机端配置串** `rv://<服务器IP>:<端口>?f=<指纹>`，
在 App「设置→导入配置串」粘贴即可免手打（v2 已不含密码字段）。

之后每次重启都会**复用已有证书**（`selfcert.go:46-54`），指纹保持不变。

⚠️ 注意：

- **删除 `-data` 目录 = 下次启动重新生成证书 = 指纹改变**，两端客户端都要重配；
- **不要多个 relay 实例共用同一空 data 目录并发首启**，会互相覆盖证书，
  导致打印的指纹与实际生效证书不符；
- 服务器重启/换机器不影响指纹，只要 `data/` 目录还在。

### 6.1 运行日志速查

relay 对**每一次连接尝试与数据流动**都有日志，排查"是否连上"以此为准：

| 日志行 | 含义 |
|---|---|
| `accepted peer=<IP:端口>` | 收到 TCP 连接（任何来源，含扫描器） |
| `tls handshake ok / failed peer=..` | TLS 握手成功/失败；失败常见于客户端指纹填错或非 TLS 探测，**仅断开该连接，服务不受影响** |
| `authenticated role=mac peer=..` | Mac 认证成功（等待 REGISTER）|
| `mac registered name=".." ..` | Mac 注册/热更秘密成功 |
| `bridged role=phone peer=.. ↔ mac=..` | 桥接建立（手机↔Mac 1:1） |
| `auth failed peer=.. role=phone (invalid-secret)` | 秘密哈希查无条目 |
| `auth failed peer=.. role=phone (secret-expired)` | 临时秘密已过期（并回投 Mac 事件） |
| `auth failed peer=.. role=phone (rate-limited)` | 按 IP 限速锁定中，恒拒绝 |
| `rejected peer=.. reason=..` | 拒绝（协议版本不符/格式错/peer-busy 等） |
| `stats role=phone audio_frames=N audio_bytes=M (窗口 10s)` | 每 10s 汇总的音频流量；持续出现说明链路有数据流动 |
| `disconnected role=.. peer=..` | 连接断开 |

> 判读技巧：只有 `accepted` 没有 `handshake ok` → 客户端不是 TLS 或指纹错；
> 有 `bridged` 但无 `stats` → 对端没在发音频（手机未授权麦克风或没按住说话）。

### 6.2 防爆破限速说明

- 同一来源 IP 在 60 秒滑窗内认证失败达到 **5 次**（无效秘密/已过期秘密）即触发锁定，
  逐级递增：**1 分钟 → 5 分钟 → 30 分钟（封顶）**；
- 锁定期内该 IP 的任何认证（即使秘密正确）恒被拒绝并记 `rate-limited`；成功认证清零；
- Mac 在线时会收到认证事件（成功/过期失败），Mac 客户端据此落「连接历史」；
- 注意：锁定按 IP 计，**同路由器多台设备共享出口 IP**——手机误输 5 次会连带锁住同一网络其他客户端，属预期设计。

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
ExecStart=/opt/remote-voice/relay-linux -addr :9432 -data ./data -regkeyfile ./regkey.txt
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
| `读取 regkeyfile 失败` | regkey 文件不存在，先执行 §4 的 `openssl rand -hex 32 > regkey.txt` |
| 手机报「未找到匹配设备」 | relay 重启或无此 Mac 注册：确认 Mac 端已启动、regkey 一致；临时秘密换了要在手机「设备」里改 |
| 手机报「尝试过于频繁，已被临时锁定」 | 按 §6.2 限速：等锁定期结束或换网络；日志用于定位源 IP |
| `监听 :9432 失败: address already in use` | 端口被占用：`ss -tlnp \| grep 9432` 查看，或换 `-addr` 端口 |
| 手机/Mac 连不上（connection refused/timeout） | 防火墙或云安全组未放行 TCP 9432（见 §7） |
| 客户端报 `certificate fingerprint mismatch` | 客户端填的指纹与服务器 `data/` 里实际证书不一致：多为 data 目录被删后重新生成过，或曾多实例并发首启。以 `journalctl`/stdout 当前打印的指纹为准重新填入两端 |
| 错误：未提供 regkey | 未按 §4 提供任何一种 regkey 来源 |

---

*文档编写：2026-08-26，基于当日代码状态（main.go、internal/selfcert/selfcert.go、go.mod）。
若启动参数或证书逻辑后续变更，请同步更新本文档对应小节及行号引用。*
