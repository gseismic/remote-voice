# mac-app-tauri · Rust/Tauri Mac 客户端

`mac-app-tauri/` 是 remote-voice 的 Mac 图形客户端，使用 v4 中继协议与 BlackHole 虚拟声卡。

## 首次使用

1. 在 Mac 上安装 BlackHole，运行 `brew install blackhole-2ch`。
2. 打开 Remote Voice，在「连接设置」填写 `https://voice.gpt60.com` 和服务器密码。公网中继使用已登记的 Mac 身份。新设备的登记方式见[部署说明](../deploy/voice.gpt60.com/README.md#登记-mac)。
3. 连接后点击首页「二维码」。Android 扫码会自动连接这台 Mac，手机按住说话时音频写入 BlackHole。

每台 Mac 有独立的设备身份。使用同一中继的 Mac 共用一个服务器密码，二维码通过设备身份匹配目标。

手机按住说话时，Mac 默认按下 Fn，松开时释放。Mac 的「连接设置」可以关闭 Fn 联动或选择双击模式。使用 Fn 联动时，在 macOS 的「隐私与安全性 → 辅助功能」中允许 Remote Voice。

## 菜单栏

点按主窗口左上角的关闭按钮后，Remote Voice 会继续在后台接收手机音频和 Fn 联动。点按 macOS 菜单栏的麦克风图标即可查看连接和说话状态，选择「显示主窗口」可回到同一窗口。也可以从 Dock 重新打开窗口。

选择「退出 Remote Voice」或在应用前台按 `⌘Q`，会结束中继连接和音频转发，并释放模拟 Fn。

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

HTTPS 地址验证公有证书与主机名。原生 TLS 地址可以使用首次信任并固定证书的模式，证书更换需要在 App 内确认。

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
