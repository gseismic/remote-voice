# PLAN-003: relay 可观测性增强 + Android 配置体验优化

- 制定时间: 2026-08-26
- 基线 commit: ca3ad13
- 来源需求:
  1. 用户："如果有任何连接或消息打 log，否则我不知道是否连上"
  2. 用户："优化 Android 界面，希望用户友好；对应服务器也优化，日志丰富"
  3. 前置讨论结论（本轮对话）：采用"一行配置串 rv://host:port?t=&f="方案消除两端手打

## 一、问题定义

### A. relay 可观测性缺陷（含一个致命缺陷）
1. **致命**: `Serve` 的 Accept 循环把任何错误当致命错误返回，`main.go` 随即
   `log.Fatalf` 退出。而 `tls.Listen` 的 TLS 握手发生在 Accept 内——Mac 端
   指纹不匹配主动断开、公网扫描器探针，都会**杀死整个服务进程**且无日志。
   （server.go:71-79 + main.go:78-80）
2. 连接尝试无日志：TCP 层谁连过来看不到。
3. 握手成功与否看不到。
4. 音频是否在流动看不到（50 帧/s 不能逐帧打）。

### B. Android 配置体验缺陷
1. 三个字段手打（64 位 hex 极易错），与 relay/Mac 端信息重复搬运。
2. 状态展示只有一行纯文本轮询，无颜色语义、无流量反馈。
3. 运行中"开始"按钮仍可点，无状态区分。

## 二、方案设计

### A. relay（Go）

**架构调整**: TLS 握手从 Listener 移入每连接 goroutine。

- `main.go`: `net.Listen` 裸 TCP 监听，TLS 配置经 `server.Config.TlsConfig` 下传；
  启动横幅追加打印手机端配置串 `rv://<host>[:port]?t=<token>&f=<fp>`。
- `server.go`:
  - `Config.TlsConfig *tls.Config`（nil = 不启用，保持测试兼容）
  - `handleConn`: 记录 `accepted peer=`；若启用 TLS 则 `tls.Server` +
    10s 截止的握手，失败记 `tls handshake failed peer=..: err` 并关闭该连接
    （不再影响其他连接）；成功记 `tls handshake ok`。
  - `Serve`: Accept 错误区分处理——listener 已关(ErrClosed/closed 标志)才退出；
    其余记日志后 sleep 100ms 继续（防忙转）。
  - 日志统一带 `peer=` 远端地址；`reject()` 增加 reason 日志。
  - 会话音频计数（atomic）：readLoop 中 AUDIO 帧计数；每会话 10s ticker
    goroutine，仅当窗口内增量>0 时输出 `stats role=.. audio_frames=.. bytes=..`；
    生命周期由 handleConn 的 done channel 控制，无泄漏。
- `session.go`: 增加 `audioFrames/audioBytes atomic.Int64`。

**兼容性自查**:
- [x] 测试全部以 `TlsConfig` 零值 + 裸 TCP 构造，行为不变
- [x] fakephone/receiver 走既有 TLS 客户端流程，线路协议零改动
- [x] 计数器 atomic，读循环热路径仅一次原子加
- [x] ticker goroutine 经 done channel 必然退出（defer close）

**回归测试新增**: TLS 服务器遭裸 TCP 垃圾字节攻击后，后续合法 TLS 客户端
仍能正常认证转发（防"一击毙命"回归）；用注入 Logger 断言出现 handshake failed 日志。

### B. Android（Kotlin，零第三方依赖不变）

> **需求变更记录（2026-08-26，用户追加）**：主界面只保留"连接密码"单一输入；
> 服务器地址等移入设置页并预置默认服务器 `43.139.226.138:9432`；
> 支持系统生成强密码。经评估否决"由用户秘密透明派生复杂 token"方案
> （低熵输入经哈希仍是低熵，属伪安全，且三端不一致增加排障成本），
> 采用"自设口令原文直用 / 一键生成高熵随机"双路径。

1. **主界面极简**（activity_main.xml 重写）：
   - 「连接密码」输入框 + 内嵌「生成」按钮（SecureRandom，28 位 base58 ≈163bit）
   - 开始 / 停止按钮、大号着色状态栏、推流帧计数
   - 右上角菜单入口 → 设置
2. **新增 SettingsActivity**（activity_settings.xml + 清单注册）：
   - 服务器地址（默认 `43.139.226.138:9432`）、服务端指纹（必填）
   - 回声消除开关；「导入 rv:// 配置串」粘贴解析（保留方案 A 能力）
3. **ConfigParser.kt**: 解析 `rv://host[:port]?t=<pwd>&f=<fp>`，
   指纹归一化校验 64 位 hex、端口默认 9432。
4. **状态机可视化**: `AudioStreamService.Status` 增加枚举
   （STOPPED/CONNECTING/WAIT_PEER/STREAMING/ERROR）与 `framesSent`
   原子计数；Activity 按 500ms 轮询着色（绿/蓝/红/灰），推流态显示已发帧数；
   运行中禁用"开始"。
5. **兼容**: prefs key 不变（server/token/fingerprint/aec）；
   server 缺省时回落内置默认值；主题换 `Theme.DeviceDefault.DayNight`。

**自查**:
- [x] 剪贴板读取发生在点击回调内（应用前台聚焦），无需权限
- [x] 密码框 inputType 正常明文（本人设备本人使用，避免二次确认困扰）
- [x] 不引入任何新依赖（androidx 也不用）
- [x] Service 对缺失配置的容错顺序：prefs 值 → 内置默认服务器 → 报错

### C. 文档同步
- relay/DEPLOY.md：§6 后补"运行日志速查表"；故障表更新（进程被握手失败杀死的条目删除，改为说明已修复）
- android-app/BUILD.md §3.7：改为"一键粘贴"流程说明

## 三、验证标准
1. `cd relay && go build ./... && go vet ./... && go test ./... -race -count=1` 全绿
2. 本地回环：relay + fakephone + receiver(--sink null)，观察新日志序列
   （accepted/handshake ok/authenticated/bridged/stats/disconnected）
3. `./gradlew assembleDebug` 通过；真机在线则 adb install 冒烟
4. 手动核对：裸 TCP 垃圾字节攻击后 relay 存活且其余客户端不受影响（由新回归测试覆盖）

## 四、实施顺序
relay 代码 → relay 测试 → Android 资源/代码 → 双端构建验证 → 回环冒烟 → OUTCOME/INDEX → 提交推送。
