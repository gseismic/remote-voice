# 设计定稿 · 仓库三端目录重组 + Mac 侧 Python 包化（repo-restructure）

- 日期: 2026-08-28
- 状态: 定稿（用户已拍板：server/mac-app/android-app 三端命名；mac-client+mac-receiver 合并；
  每子项目带 README + 根总 README）
- 前置: docs/dev/20260826-1540-REVIEW-13aadb1-v2-impl.md（H-1/H-2 缺陷一并修复）

## 一、命名与目录（终局，不再变动）

| 新目录 | 语言 | 运行端 | 内容 |
|---|---|---|---|
| `server/` | Go（module `remote-voice/server`） | 公网 Linux | 中继服务器 + `cmd/fakephone`（原 `relay/` 整树） |
| `mac-app/` | Python（PySide6，可安装包） | macOS | 图形客户端 + CLI 轻接收器（原 `mac-client/` + `mac-receiver/` 合并） |
| `android-app/` | Kotlin | Android 手机 | 不变（目录名不动） |

- "relay" 字样仅保留为**职能描述**（中继服务器），不再作为目录/module/产物名。
- 理由：三程序部署在三台不同设备，目录名即运行端名；根 README 一句话即可解释中继职能。

## 二、server/ 改名波及面（全量清单）

1. `go.mod`：`module remote-voice/relay` → `module remote-voice/server`。
2. 全部 import：`remote-voice/relay/...` → `remote-voice/server/...`（main.go / cmd/fakephone / internal
   各包含测试文件；用 `grep -rl` 全量替换，禁止手工漏项）。
3. 构建产物名：本地 `relay/relay`→`server/server`；交叉编译 `relay-linux`→`server-linux`。
4. `.gitignore`：`relay/` 前缀全部改 `server/`（data/、relay、relay-linux、token.txt）。
5. 文档路径引用：README.md、server/DEPLOY.md（含 systemd ExecStart 举例路径 `relay-linux`→`server-linux`）、
   docs/HANDOFF.md、docs/dev/INDEX.md、docs/design/*.md、android-app/BUILD.md（交叉编译命令的 `cd relay`→`cd server`）。
6. 代码内文案暂不改（日志/注释中的"relay"与中继语义一致，避免无 diff）。

## 三、mac-app/ 包结构（Python）

```
mac-app/
├── pyproject.toml            # setuptools；deps: PySide6/sounddevice/numpy；scripts 双入口
├── README.md
├── src/macapp/
│   ├── __init__.py
│   ├── protocol.py           # 帧编解码+常量+指纹规整（合并 mac-client/protocol.py 与 receiver.py 帧函数）
│   │                         #   + FrameWriter（带锁发送，供 core/CLI/测试共用）
│   ├── secretsgen.py         # 秘密生成/规范化/哈希/Keychain/regkey.txt 路径（原 mac-client/secretsgen.py）
│   ├── history.py            # 连接历史（原 mac-client/history.py）
│   ├── config_store.py       # config.json 读写（原 mac-client/config_store.py）
│   ├── audio.py              # NullSink(统计)/AudioSink(CLI 显式启停)/SoundDeviceSink(GUI 懒建) 三合一
│   ├── core.py               # RelayClient 会话引擎（原 mac-client/core.py + H-1/H-2 修复）
│   ├── ui_main.py            # 主窗口（原 ui_main.py）
│   ├── ui_history.py         # 历史窗口（原 ui_history.py）
│   ├── main_gui.py           # GUI 入口 main()（原 main.py）
│   └── receiver_cli.py       # CLI 入口 main()（原 mac-receiver/receiver.py 行为等价）
└── tests/
    ├── conftest.py           # sys.path 注入 src/（离线可跑；安装后亦可跑）
    ├── test_protocol.py      # 帧 roundtrip/空帧/超限+指纹规整（原 test_receiver.py 1/2/3/4 迁移）
    ├── test_writer.py        # FrameWriter 并发串行化不撕裂（原 test_receiver.py 5 迁移）
    ├── test_secrets.py       # 原样迁移
    ├── test_history.py       # 原样迁移
    └── test_loopback.py      # 回环集成（import 改 macapp.*；relay 路径改 server/）
```

## 四、对外接口（pyproject 入口）

```
pip install -e .          # 安装（开发机建议 venv，或 python3 -m venv .venv）
remote-voice-gui          # 图形客户端（等价原 python3 mac-client/main.py）
remote-voice-recv         # CLI 轻接收器（等价原 python3 mac-receiver/receiver.py，参数不变）
```

- CLI 参数与行为等价清单（receiver_cli 用 core.RelayClient 实现，NON-goal 是全量照抄内部实现）：
  `--server/--regkey/--name/--temp-secret/--perm-secret/--temp-expiry/--fingerprint/--device/--sink {audio,null}`
  + `config.toml`（cwd 下）优先、缺参致命退出、心跳 10s、桥接在线才出声、离线暂停、断线退避 1→30s、
  认证拒/指纹不符致命退出（exit 1）。
- sink 行为：AudioSink（CLI，启动即建流、在线 start/offline stop）、SoundDeviceSink（GUI，首次 feed 懒建）。

## 五、缺陷修复（H-1/H-2，并入 core.py）

- **H-1 心跳**：连接建立且注册后启动心跳线程（10s PING，与 receiver.py 同规格）；读循环 socket
  超时固定恢复为 READ_TIMEOUT(40s)，不再继承 AUTH 阶段 10s。修复后空闲 Mac 保持在线，桥接中
  静默不断链。
- **H-2 指纹不符**：`_attempt` 内捕获 `proto.FatalError`（指纹不符）→ 作为致命错误返回，
  状态置 ST_FATAL 且不再重连（与 bad-regkey 同路径）；GUI 显示"致命错误"并在连接按钮复位。
- 回归测试：回环新增 a) 注册后静默 ≥15s 不掉线；b) 错误指纹 → ST_FATAL 且线程退出。

## 六、README 分工

| 文档 | 内容 |
|---|---|
| `README.md`（根） | 一句话目标、三端架构图、快速启动（部署→Mac→手机）、指向三子 README、开发验证命令 |
| `server/README.md` | 程序简介、构建（本机/交叉编译）、参数表、常见问题；部署细节链 `server/DEPLOY.md` |
| `mac-app/README.md` | 安装（venv+pip）、GUI/CLI 用法、秘密与 Keychain 说明、常见问题 |
| `android-app/README.md` | 简介、构建与装机（链 BUILD.md 深度）、App 使用流程 |
| 保留 | `server/DEPLOY.md`（systemd/上线细节）、`android-app/BUILD.md`（构建环境细节） |

## 七、不做的（NON-goal）

- Android 目录内不动（`RelayClient` 类名保留——它是协议角色名，非目录名问题）。
- 不改协议、不改 Android 功能、不动 server 运行参数（-addr/-data/-regkeyfile 保持）。
- 不做 CI/发布流水线（超出本次范围）。
