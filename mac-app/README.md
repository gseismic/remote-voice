# mac-app · Mac 端（图形客户端 + CLI 轻接收器）

macOS 端统一包：一个安装包、两个入口——图形客户端（日常用）与 CLI 轻接收器
（无桌面/脚本化场景），共用协议/秘密管理/音频输出实现。

## 安装

```bash
cd mac-app
python3 -m venv .venv && source .venv/bin/activate
pip install -e .            # 安装依赖（PySide6 ~200MB，首次需等待）
pip install -e '.[test]'    # 开发机可选：含 pytest
```

> 前置：声卡需安装 BlackHole 虚拟麦克风：`brew install blackhole-2ch`。

## 图形客户端（推荐）

```bash
remote-voice-gui
```

启动后底部连接设置只需填写 **服务器**（`<IP>:9432`）和可选的**设备名**。
本机设备身份在首次连接时自动生成并安全保存，**不需要输入 regkey 或证书指纹**；
**填一次即保存，下次启动自动连接**。
界面提供：

- 临时秘密卡：短码 + 倒计时 + 4 档有效期 + 复制/重新生成；勾选「重启后保持」可固定
- 永久秘密卡：经 macOS Keychain 存储（`security` CLI），掩码展示、修改/停用
- 连接历史：全部/今日/被拒绝 过滤，清空
- 状态条：已注册（绿）/ 桥接中（红）/ 重连（琥珀）/ 致命错误（红）+ 自动重连（1s→30s 退避）

手机端按住说话时，Mac 收流写入 BlackHole；会议软件把麦克风选为 **BlackHole 2ch** 即可。

## CLI 轻接收器

```bash
remote-voice-recv --server <IP>:9432 --device BlackHole
```

- 启动自动生成并打印临时秘密（手机端输入），可 `--temp-secret/--perm-secret` 复用；
- 缺参从 `config.toml`（当前目录）读取；`--sink null` 为无声卡验证模式（仅统计帧率）；
- 认证拒/指纹不符 → 致命退出（exit 1）；断线指数退避重连。

## 配置与安全说明

- 运行配置：`~/.config/remote-voice/config.json`（0600；GUI 打开时写入）
- 永久秘密：Keychain（`remote-voice` service）；无 Keychain 时回落
  `~/.config/remote-voice/perm.secret`（0600）
- 本机设备身份：优先 macOS Keychain；不可用时回落
  `~/.config/remote-voice/device.json`（目录 0700、文件 0600）
- 临时秘密默认只在内存（勾选「重启后保持」才落盘）
- 协议仅传输配对秘密的 SHA-256（规范化后）；TLS 首次连接自动 TOFU，之后按服务器地址固定

## 开发

```bash
pip install -e '.[test]'
python3 -m pytest tests/ -q        # 纯逻辑 + 真实 server 回环集成（需 go 可编译 server）
```
