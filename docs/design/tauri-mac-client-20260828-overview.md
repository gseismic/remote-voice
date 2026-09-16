# Rust/Tauri Mac 客户端设计

日期：2026-08-28
基线提交：`bfb44b3143e7267e8b6696462792c2ff5c16d970`
适用范围：`mac-app-tauri/`
设计依据：`api-design-zh`、`frontend-design`

## 1. 目标与边界

为现有 Python Mac 客户端增加一个 Rust/Tauri 实现，满足同一条 v3 relay 链路：

- 首次使用自动生成设备身份，用户只需要服务器地址和设备名；
- 自动生成并显示临时配对秘密，支持永久秘密；
- 内部完成 TLS TOFU，不让用户输入 regkey 或证书指纹；
- 收到手机音频后写入 BlackHole；
- 显示连接、桥接、错误和最近事件；
- 与 Python 版共用身份、秘密和基础配置格式；
- 不修改 server 协议，不替换 Python 客户端，不实现账号/租户/邀请后台。

## 2. 方案比较

### 方案 A：Tauri 2 + Rust 核心 + Vite 静态前端（采用）

Rust 核心独立于 Tauri，使用 Tokio/Tokio-rustls 处理 TLS TCP，使用 cpal 输出音频；前端通过 Tauri command 和事件调用核心。

优点：网络和音频不依赖 Python；核心可在 Linux 无 UI 测试；Tauri 包体和内存占用低；前端交互可独立迭代。缺点：需要维护 Rust TLS、音频和持久化代码；macOS 真机仍需要验收。

### 方案 B：Tauri UI 调用现有 Python 客户端子进程

优点：实现快，协议和音频逻辑复用率高。缺点：运行时仍依赖 Python/PySide6/sounddevice；进程生命周期、错误传递和凭据管理复杂；不能兑现 Rust 客户端的独立交付目标。

结论：不采用。

### 方案 C：原生 SwiftUI 重写 Mac 客户端

优点：macOS 音频和 Keychain 集成自然。缺点：不满足 Rust/Tauri 诉求；跨平台核心无法复用；需要维护 Swift 与现有三端协议两套实现。

结论：不采用。

## 3. 用户接口定稿

### 3.1 主窗口

主窗口采用“夜间音频控制台”方向：近黑背景、暖白文字、琥珀连接态、绿色在线态、珊瑚异常态；使用原生 macOS 字体栈和等宽数字显示秘密与计时。布局按工作频率排序：

1. 顶部状态与 server 摘要；
2. 临时配对秘密、剩余时间、复制和重新生成；
3. 永久秘密启用/修改/停用；
4. 连接设置：服务器、设备名、音频输出、临时秘密有效期、重启后保持；
5. 最近事件日志和连接/断开动作。

界面不显示技术实现字段 `device_id`、`device_key`、regkey 或证书指纹。服务器地址是唯一必填连接字段；音频输出默认 `BlackHole`，找不到设备时显示可修复错误和设备列表。

### 3.2 Tauri 用户接口

面向前端的命令使用业务语义命名，核心字段如下：

| 命令 | 输入 | 行为 | 副作用 |
|---|---|---|---|
| `get_state` | 无 | 返回完整 `AppState` | 无 |
| `save_settings` | server、name、audio_device、temp_expiry、keep | 校验并保存设置 | 写 `config.json`；若已连接则请求重连 |
| `connect` | 无 | 加载/创建身份并启动连接循环 | 网络连接、首次登记、写 TOFU |
| `disconnect` | 无 | 停止当前连接和音频输出 | 关闭网络和音频设备 |
| `regenerate_temp` | 无 | 生成新临时秘密并重新注册 | 更新配置（keep 时持久化）、发送 REGISTER |
| `set_permanent` | secret | 校验长度并保存永久秘密 | Keychain 或 `perm.secret`、发送 REGISTER |
| `clear_permanent` | 无 | 显式停用永久秘密 | 删除本地永久秘密、发送 REGISTER |
| `list_audio_devices` | 无 | 返回可用输出设备名 | 枚举本机音频设备 |
| `clear_history` | 无 | 清除本地连接历史 | 删除历史记录内容 |
| `clear_server_trust` | 无 | 清除当前服务器的本地 TOFU 记录 | 删除当前 server 地址的信任记录；若已连接则重新连接并重新建立信任 |

命令失败统一返回带 `code` 和用户可读 `message` 的错误对象；网络认证失败、TOFU 不符、设备不存在和音频设备不可用分别使用稳定错误码，前端不依赖错误字符串分支。

### 3.3 状态事件

后端向前端发出 `app-state` 事件，payload 是完整快照而不是局部 patch，避免 UI 因丢失某个事件而进入错误组合状态。`AppState.status` 取值：`idle`、`connecting`、`registered`、`bridged`、`reconnecting`、`fatal`、`stopped`。

## 4. Rust 内部接口与状态机

### 4.1 核心组件

- `protocol`：帧头编解码、AUTH/REGISTER/事件 JSON 类型和大小边界；纯函数，单元测试覆盖截断/超限/往返。
- `identity`：Keychain CLI 与 0600 文件回落；`load_or_create()` 保证成功返回的身份可在下一次进程启动读取。
- `config`：共享 JSON schema、server 地址规范化、按 `host:port` 读取/写入 TOFU。
- `relay`：`RelayClient` 状态机、TLS/TOFU、认证、REGISTER、心跳、重连和音频帧处理；不依赖 Tauri。
- `audio`：`AudioSink` trait 与 `CpalSink`；网络线程只投递 PCM，音频回调从有界队列消费。
- `controller`：Tauri command 的业务编排、状态快照、配置持久化和事件转发。

### 4.2 合法状态转换

```text
idle/stopped -> connecting -> registered -> bridged
                     |             ^          |
                     v             |          v
                reconnecting <------+------ registered
                     |
                     v
                   fatal
```

认证拒绝、设备身份错误、设备存储损坏和 TOFU 指纹不符进入 `fatal`，不自动重试；网络断开进入 `reconnecting`，使用 1、2、4、8、16、30 秒退避；用户 `disconnect` 清理 socket、心跳、音频流和线程。

### 4.3 网络并发契约

每个连接使用一个读任务和一个写/控制任务：读任务完整消费帧并通过有界 channel 传递；写任务独占 TLS 写半部，串行发送 AUTH、REGISTER、PING/PONG；停止信号可取消两个任务。连接切换时旧任务必须先关闭，不得把旧 socket 的事件写入新连接状态。

## 5. 协议与安全

### 5.1 v3 线路

Mac 首帧：

```json
{"role":"mac","proto":3,"device_id":"mac-...","device_key":"<64 hex>"}
```

认证成功后发送：

```json
{"name":"MacBook","perm":"<sha256 hex or empty>","temp":"<sha256 hex or empty>","temp_exp":1787}
```

只允许在收到 AUTH_OK 后发送 REGISTER。AUDIO payload 只在内存中传递给 `AudioSink`，不落盘。

### 5.2 TLS TOFU

首连 custom verifier 接受服务器证书，握手完成后计算叶证书 DER 的 SHA-256，并通过 controller 按 server 地址保存；后续连接 verifier 必须匹配该指纹。指纹不再进入用户表单，但文档和错误状态要说明首次 TOFU 的信任窗口。

### 5.3 凭据持久化

设备身份和永久秘密优先复用现有 Keychain service/account；fallback 与 Python 版共享文件名和权限。任何保存失败都返回可识别错误，不得返回“连接成功”或在 server 未落盘时继续声称首次登记完成。device_key 不写日志、不发送到前端事件。

## 6. 音频输出

`CpalSink` 搜索名称包含 `BlackHole` 的可写输出设备，优先选择 48 kHz、单声道/双声道且支持 i16 或 f32 的配置。输入固定为 48 kHz mono s16le 20 ms 帧；双声道设备复制样本到左右声道。音频队列有界，设备暂时阻塞时丢弃最旧帧并记录计数，避免延迟无限累积。设备打开/写入失败只影响音频状态，网络连接仍可保持并允许重新选择设备。

## 7. 验收与测试

- Rust 单元测试：帧往返/截断/超限、v3 JSON、秘密规范化和哈希、设备身份校验、配置/TOFU 隔离、状态转换和错误码。
- Rust 集成测试：本地 TLS server + v3 Mac client 完成 AUTH→REGISTER→手机桥接，验证音频帧透传、心跳、错误设备凭据、TOFU mismatch 和停止清理。
- 前端测试：设置保存、秘密显示/复制/重生成、状态事件渲染、认证错误展示；至少通过 Vite production build。
- 构建：`cargo fmt --check`、`cargo test`、`cargo check`、`pnpm run build`、Tauri Linux bundle/check；macOS bundle、Keychain 和 BlackHole 在 macOS 另行验收。

## 8. 三轮查漏补缺

### 第 1 轮：用户接口

- 默认路径只有 server 地址和设备名；regkey、device key、证书指纹不会进入表单。
- 复制、重生成、停用永久秘密都是明确按钮和命令；停用会同时清本地存储并发送 REGISTER。
- 清除服务器信任是显式危险操作；UI 先确认，后端只删除当前 server 地址的记录，不影响其它 server。
- 错误使用稳定 code，前端不解析技术异常字符串。

### 第 2 轮：并发与生命周期

- 读写半部分工，写出串行；REGISTER 受 AUTH_OK 门闩保护。
- reconnect、disconnect 和音频设备失败都有资源清理路径；旧连接不能覆盖新状态。
- 音频队列有界，不能因为网络/设备差异无限增长内存和延迟。

### 第 3 轮：安全与兼容

- 使用 v3 设备身份，保留现有 v2 server 兼容但不让新 UI 走 regkey。
- TOFU 按 server 地址隔离；首次信任窗口、设备凭据复制和 Keychain fallback 都写入边界说明。
- Rust 与 Python 共享 Keychain account、fallback 文件和配置字段，切换客户端不会无故生成第二个设备身份。
