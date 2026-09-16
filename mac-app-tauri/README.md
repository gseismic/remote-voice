# mac-app-tauri · Rust/Tauri Mac 客户端

`mac-app-tauri/` 是 remote-voice 的独立 Rust/Tauri 图形客户端。它与
[`mac-app/`](../mac-app/README.md) Python 客户端共用 v3 relay 协议、Keychain
账号和本地配置约定；两者可以按使用环境任选其一。

## 首次使用

1. 先启动 [`server/`](../server/README.md)，只需给客户端一个 `host:9432` 地址，服务端不再要求全局 `regkey`。
2. 在 Mac 上安装 BlackHole：`brew install blackhole-2ch`。
3. 打开 Remote Voice，在「连接设置」填写服务器地址和可选设备名，点击连接。
4. 客户端首次连接时自动生成本机设备身份并保存；用户不需要输入 `regkey`、设备密钥或证书指纹。
5. 把窗口顶部显示的临时配对秘密填入 Android 的「添加设备」，手机连接后音频会写入 BlackHole。

每台 Mac 都有独立的本机身份，所以同一个 relay 可以同时登记多台 Mac。每台 Mac 的临时/永久配对秘密分别路由到对应设备。

## 安装与构建

前置：安装 pnpm（任选其一）：`corepack enable pnpm` 或 `npm install -g pnpm`。
`package.json` 已通过 `packageManager` 字段固定 pnpm 版本。

在 macOS 上构建发布包：

```bash
cd mac-app-tauri
pnpm install
pnpm run tauri dev
pnpm run tauri build
```

`pnpm run tauri build` 会按当前平台生成安装包。签名和公证需要用户自己的 Apple 开发者环境；Linux 只能验证 Rust 核心和 Tauri 构建流程，不能替代 macOS 安装、Keychain 与音频验收。

## 本地数据

- 本机设备身份：优先保存到 macOS Keychain 的 `remote-voice / device-identity`；Keychain 不可用时回落到 `~/.config/remote-voice/device.json`。
- 永久秘密：优先保存到 `remote-voice / permanent-secret`；不可用时回落到 `~/.config/remote-voice/perm.secret`。
- 连接设置、按服务器隔离的 TOFU 记录和可选临时秘密：`~/.config/remote-voice/config.json`。
- 连接历史：`~/.config/remote-voice/history.json`。

回落目录权限为 0700，敏感文件权限为 0600。Python 客户端使用相同的存储约定，可以在两种客户端之间切换。

## 信任与兼容

服务端仍使用 TLS。首次连接时客户端内部按服务器地址建立 TOFU 信任，后续连接固定已记录的证书；正常用户不需要查看或填写指纹。设置页的盾牌按钮只用于证书更换等明确场景，清除后下一次连接会重新建立信任，并且会先要求确认。

当前是开放自助入网：知道 relay 地址的人可以尝试登记新的 Mac。它适合自托管和小规模使用，不等同于面向互不信任用户的 SaaS 账号/邀请/配额系统；后者需要后续增加管理面。

## 开发验证

```bash
cd mac-app-tauri/src-tauri
cargo fmt -- --check
cargo clippy --all-targets -- -D warnings
cargo test

cd ..
pnpm run build
node --check web/app.js
```

核心测试包含帧边界、v3 AUTH/REGISTER 顺序、真实 TCP/TLS TOFU、身份持久化、秘密存储和音频 sink；BlackHole 实际输出需要在 macOS 真机上验证。
