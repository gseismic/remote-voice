# server 中转服务器部署指南

本文档面向第一次部署 server 的人，把 `../README.md` §1 中每条命令的作用讲清楚。

---

## ⚠️ 协议 v4 升级说明（2026-09-19，PLAN-026）

v4 是破坏性升级：**服务器与两端 App 必须同步更新**（v3 客户端连 v4 服务器会被拒绝）。

1. 重新编译并部署 server（`-regkey/-regkeyfile` 参数已删除，systemd 单元若引用需去掉）；
   `devices.json` 自动从 v1 升级到 v2（已登记的 Mac 身份保留）。
2. Mac 与 Android 安装新版；旧版「每台 Mac 一个秘密」作废。
3. 首次使用：Mac 端连接设置里设置**服务器密码**（≥12 位）→ 手机端「＋ 添加」手填
   地址+密码，或扫 Mac 端二维码（载荷内含该密码）。
4. 手机登录后即可看到服务器上全部 Mac（含离线），点「连接」建桥；Android 可同时
   连接多台，左右滑动切换当前目标。
5. 证书（有域名时）：`-cert/-key` 指向 Let's Encrypt 证书（签发步骤见
   docs/dev/PLAN-024-tls-ca-mode-OUTCOME.md §部署 runbook）。客户端裸地址会自动探测：
   标准验证失败回落首连信任，配上真证书后自动升级，无需任何客户端操作。
6. 多台 Mac 请设置**相同**的服务器密码（后注册的会覆盖先注册的，日志有记录）。

## 0. 先读这段：三个最容易误解的点

1. **两组编译命令是「二选一」，不是按顺序全部执行。**
   它们的目的相同——得到一个能在 Linux 服务器上运行的 `server` 可执行文件，
   区别只是在哪里编译（见 §2 决策表）。
2. **`server` 和 `server-linux` 是同一个程序的两个构建产物，服务器上只需要其中一个。**
   名字不同仅仅因为编译命令里 `-o` 参数指定的输出文件名不同（详见 §3）。
3. **新版本启动不需要 regkey 文件。**
   Mac 首次连接会自动生成并登记本机设备身份；旧 v2 客户端仍可按 §4 的兼容说明使用 regkey。

> 只想最快跑通？依次执行：§2 路线 A → §5 启动命令 → Mac/Android 首次配置，共 3 步。

---

## 1. 这个程序是什么

`server/` 目录是一个 Go 编写的中转服务器（入口 `server/main.go`）：
在公网上监听一个 **TLS TCP 端口**，把 Android 手机推上来的音频流桥接转发给 Mac。协议 v3 注册制：
Mac 首次以本机设备身份自助登记，之后注册（临时/永久）秘密哈希，手机认证只带秘密哈希；
server 按哈希路由到对应 Mac 建 1:1 桥接，并按来源 IP 限速防爆破（见 §6.2）。
它需要部署在一台**有公网 IP 的 Linux 服务器**上。

---

## 2. 获取可执行文件（两条路线，二选一）

| | 路线 A：服务器上直接编译 | 路线 B：本机交叉编译后上传 |
|---|---|---|
| 前提 | 服务器装有 Go ≥ 1.22（`go.mod:3` 声明） | 任意装有 Go ≥ 1.22 的机器（服务器不需要 Go） |
| 适用 | 服务器方便装环境 | 服务器不想装任何编译环境 |
| 产物 | `server` | `server-linux` |

### 路线 A：服务器上直接编译

```bash
cd server && go build -o server .
```

| 命令片段 | 作用 |
|---|---|
| `cd server` | 进入源码目录（`go.mod` 所在处，Go 以此定位模块） |
| `go build` | 编译 Go 包 |
| `-o server` | 指定输出文件名为 `server`（**名字随意**，只是个标签） |
| `.` | 编译对象：当前目录这个包，即 `main.go` 及其引用的 `internal/` |

### 路线 B：本机交叉编译后上传

```bash
# 在任意开发机上执行（不是在服务器上）
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o server-linux .

# 上传到服务器并赋予执行权限
scp server-linux user@<服务器IP>:/opt/remote-voice/
ssh user@<服务器IP> chmod +x /opt/remote-voice/server-linux
```

逐项解释：

| 参数 | 作用 |
|---|---|
| `CGO_ENABLED=0` | 禁用 cgo，产出**不依赖服务器系统库（libc）的纯静态二进制**，拷到任何 Linux 都能跑 |
| `GOOS=linux` | 目标操作系统为 Linux |
| `GOARCH=amd64` | 目标 CPU 架构：x86_64 服务器用 `amd64`，ARM 服务器改为 `arm64` |
| `-trimpath` | 从二进制中抹去编译机的源码绝对路径：体积更小、不泄露本机路径 |
| `-ldflags="-s -w"` | 链接时 `-s` 去除符号表、`-w` 去除 DWARF 调试信息，体积减约三成 |
| `-o server-linux` | 输出文件名；加 `-linux` 后缀只是人为标记「这是给 Linux 的」 |
| `.` | 同路线 A：编译当前目录的包 |

> 若选错架构（如 ARM 服务器跑了 amd64 二进制），运行时会报
> `exec format error`，回到这里换 `GOARCH=arm64` 重编即可。

---

## 3. `server` 和 `server-linux` 到底是什么关系？

**同一份源码（`main.go` + `internal/`）、两种编译方式、两个文件名，功能完全相同。**

用 `file` 命令实测本仓库目录下现存的两个产物：

| | `server/server` | `server/server-linux` |
|---|---|---|
| 由哪条命令产出 | 路线 A：`go build -o server .` | 路线 B：交叉编译命令 |
| 格式 | ELF 64 位 x86-64，**动态链接** | ELF 64 位 x86-64，**静态链接**（`CGO_ENABLED=0`） |
| 调试信息 | 保留（not stripped） | 已去除（stripped，因 `-s -w`） |
| 大小 | ≈ 8.6 MB | ≈ 5.8 MB |
| 能否互换使用 | 能（x86-64 Linux 下等价） | 能，且对服务器更省事（无 libc 依赖） |

结论与建议：

1. **二选一上传到服务器即可**，不要两个都传（没有意义，浪费磁盘）。
2. 本地开发目录里的这两个文件只是历史构建遗留，**部署时不需要上传它们**，
   要么传源码走路线 A，要么自己重新交叉编译一份走路线 B。
3. 二者都是构建产物，**不应提交进 git**（`.gitignore` 已忽略 `server/server`，
   `server/server-linux` 同理）。

---

## 4. 启动前准备：不需要全局 regkey

新 v3 流程不再要求管理员生成或分发全局 regkey。启动 server 后，任意新的 Mac
首次连接会提交本机自动生成的 `device_id/device_key`；server 将设备凭据哈希保存到
`data/devices.json`，以后用同一身份重连。

手机端仍只需要 Mac 界面或 CLI 打印的临时/永久配对秘密。配对秘密不是 server
启动参数，也不会以明文写入 server。

为滚动升级，以下旧 v2 兼容入口仍保留：

| 方式 | 说明 |
|---|---|
| `-regkeyfile ./regkey.txt` | 读取旧 v2 Mac 注册密钥（可选） |
| 环境变量 `RELAY_REGKEY` | 旧 v2 兼容，可用于容器等场景 |
| `-regkey xxx` | 旧 v2 兼容，仅限本地调试；会暴露在 `ps` 和 shell history |

只要没有旧 v2 Mac，部署时完全不需要上述参数。

---

## 5. 启动命令逐参数解释

```bash
./server -addr :9432 -data ./data
```

| 参数 | 作用 | 出处 |
|---|---|---|
| `-addr :9432` | TLS 监听地址。`:9432` 表示监听**所有网卡**的 9432 端口；只想内网测试可用 `127.0.0.1:9432` | `main.go` |
| `-data ./data` | 证书、私钥和设备身份的存放目录，不存在会自动创建 | `main.go`、`selfcert.go`、`device_registry.go` |
| `-pprof 127.0.0.1:6060` | （可选）开启 Go 性能分析端点，排查性能问题时才需要 | `main.go` |
| `-cert/-key` | （可选）外部证书 PEM（如 Let's Encrypt）；不提供则用 data 目录自签证书（10 年有效） | `main.go` |

启动成功会打印 `server 启动，监听 :9432`。停止：`Ctrl+C`
（程序捕获 SIGINT/SIGTERM 后优雅关闭）。

---

## 6. 首次启动会发生什么：自签证书与指纹

首次启动（`-data` 目录里还没有证书时），server 会：

1. 自动生成一张 **ECDSA P-256 自签证书**，有效期 **10 年**，私钥以 0600 权限落盘
   （`selfcert.go:29-35`、`selfcert.go:93`）；
2. 在 stdout 打印该证书的 **SHA-256 指纹（64 位小写 hex）**（`main.go`）。

```text
==================================================
客户端接入（共两样，别无其他）:
  1) 服务器地址: <服务器IP>:9432   ← 填在 Mac 客户端「连接设置」；手机扫 Mac 二维码或手动添加
  2) 服务器密码: 在 Mac 客户端「连接设置 → 服务器密码」设置（≥12 位），手机输入同一密码
证书: 自签（客户端首次连接自动信任并固定，自动处理，无需任何配置）
  诊断指纹 (SHA-256，仅排障用，客户端不消费): aabbccdd...共64个字符...
==================================================
```

客户端**什么都不需要**填指纹或任何前缀：裸地址下自动先标准证书验证、自签自动回落
首连信任并按 server 地址固定（协议 v4 + PLAN-025 自动探测）。指纹仅用于人工排障比对。

之后每次重启都会**复用已有证书**（`selfcert.go:46-54`），指纹保持不变。

⚠️ 注意：

- **删除 `-data` 目录 = 下次启动重新生成证书并丢失设备身份登记**；客户端会重新建立 TOFU，
  但旧设备身份需要重新登记；
- **不要多个 server 实例共用同一空 data 目录并发首启**，会互相覆盖证书，
  导致打印的指纹与实际生效证书不符；
- 服务器重启/换机器不影响指纹，只要 `data/` 目录还在。

### 6.1 运行日志速查

server 对**每一次连接尝试与数据流动**都有日志，排查"是否连上"以此为准：

| 日志行 | 含义 |
|---|---|
| `accepted peer=<IP:端口>` | 收到 TCP 连接（任何来源，含扫描器） |
| `tls handshake ok / failed peer=..` | TLS 握手成功/失败；失败常见于非 TLS 探测、客户端已记录的证书变化或协议不匹配，**仅断开该连接，服务不受影响** |
| 客户端「重连失败: 原因」对照 | 客户端状态文本原因 ↔ 本表行：连接超时→看防火墙/端口；连接被拒绝→server 未启动；TLS 握手失败/指纹不符→检查 server `data/` 是否更换及客户端内部信任记录 |
| `authenticated role=mac peer=..` | Mac 认证成功（等待 REGISTER）|
| `mac registered name=".." ..` | Mac 注册/热更秘密成功 |
| `bridged role=phone peer=.. ↔ mac=..` | 桥接建立（手机↔Mac 1:1） |
| `auth failed peer=.. role=phone (invalid-secret)` | 秘密哈希查无条目 |
| `auth failed peer=.. role=phone (secret-expired)` | 临时秘密已过期（并回投 Mac 事件） |
| `auth failed peer=.. role=phone (rate-limited)` | 按 IP 限速锁定中，恒拒绝 |
| `rejected peer=.. reason=..` | 拒绝（协议版本不符/格式错/peer-busy 等） |
| `stats role=phone audio_frames=N audio_bytes=M (窗口 10s)` | 每 10s 汇总的音频流量；持续出现说明链路有数据流动 |
| `disconnected role=.. peer=..` | 连接断开 |

> 判读技巧：只有 `accepted` 没有 `handshake ok` → 客户端不是 TLS、证书发生变化或握手失败；
> 有 `bridged` 但无 `stats` → 对端没在发音频（手机未授权麦克风或没按住说话）。

### 6.2 防爆破限速说明

- 同一来源 IP 在 60 秒滑窗内认证失败达到 **5 次**（无效秘密/已过期秘密）即触发锁定，
  逐级递增：**1 分钟 → 5 分钟 → 30 分钟（封顶）**；
- 锁定期内该 IP 的任何认证（即使秘密正确）恒被拒绝并记 `rate-limited`；成功认证清零；
- Mac 在线时会收到认证事件（成功/过期失败），Mac 客户端据此落「连接历史」；
- 注意：锁定按 IP 计，**同路由器多台设备共享出口 IP**——手机误输 5 次会连带锁住同一网络其他客户端，属预期设计。

---

## 7. 常驻运行（可选，生产建议）

裸跑 `./server` 在 SSH 断开后会退出，生产环境建议用 systemd 托管：

```ini
# /etc/systemd/system/remote-voice-server.service
[Unit]
Description=remote-voice server server
After=network.target

[Service]
WorkingDirectory=/opt/remote-voice
ExecStart=/opt/remote-voice/server-linux -addr :9432 -data ./data
Restart=on-failure
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now remote-voice-server
journalctl -u remote-voice-server -f      # 查看日志（含启动横幅：客户端接入两要素 + 诊断指纹）
```

只需放行防火墙与云安全组的 **TCP 9432** 端口。
局域网发现（UDP 同端口）自 V3.2 起降级为本地测试工具：`-discovery` 默认关闭，
仅在同一 Wi-Fi 测试扫描时显式开启（`-discovery`），公网部署无需放行 UDP：

```bash
sudo ufw allow 9432/tcp        # Ubuntu ufw
# 或 firewalld: sudo firewall-cmd --permanent --add-port=9432/tcp && sudo firewall-cmd --reload
```

---

## 8. 故障速查

| 现象 | 原因与处理 |
|---|---|
| `exec format error` | 二进制架构与服务器 CPU 不符，按 §2 路线 B 更换 `GOARCH` 重编 |
| `加载设备身份失败` | `data/devices.json` 损坏、版本不支持或权限不足；恢复有效备份并保持文件 0600 |
| 手机报「未找到匹配设备」 | Mac 尚未在线或尚未注册该配对秘密；先启动 Mac，再确认手机「设备」里的秘密与 Mac 当前显示一致 |
| 手机报「尝试过于频繁，已被临时锁定」 | 按 §6.2 限速：等锁定期结束或换网络；日志用于定位源 IP |
| `监听 :9432 失败: address already in use` | 端口被占用：`ss -tlnp \| grep 9432` 查看，或换 `-addr` 端口 |
| 手机/Mac 连不上（connection refused/timeout） | 防火墙或云安全组未放行 TCP 9432（见 §7）；本地扫描测试无结果时另查 server 是否以 -discovery 开启与 AP 广播隔离 |
| 客户端报 `certificate fingerprint mismatch` | server 证书与该地址此前 TOFU 记录不一致；确认 `data/` 没被替换。若确实更换过证书，需要清理对应客户端的内部信任记录后重新连接 |
| Mac 报 `invalid-device` | 本机设备身份文件/Keychain 被替换或损坏；恢复原设备身份，或删除本机身份后让它生成新身份并重新入网 |

---

*文档编写：2026-08-26，基于当日代码状态（main.go、internal/selfcert/selfcert.go、go.mod）。
若启动参数或证书逻辑后续变更，请同步更新本文档对应小节及行号引用。*
