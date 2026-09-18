# 扫码配对（QR Pairing）设计 — 20260919

- 状态：定稿（经 2 轮自查，见文末）
- 关联计划：docs/dev/PLAN-023-qr-pairing.md

## 1. 目标与非目标

**目标**：手机扫描 Mac 屏幕上的二维码，一步完成「服务器地址 + 密码 + 设备名」的配置并连接，
取代「设置页手填服务器 + 添加对话框手抄密码」两步手工流程。

**非目标**：不做反向扫码（Mac 扫手机）；不做局域网直连（仍经 relay）；不改认证协议。

## 2. 载荷格式（两端的对外接口）

```
rv://<host>[:<port>]?s=<secret>&n=<name>
```

- 沿用既有 `rv://` 地址 scheme（Android `ConfigParser.kt:15`、Mac `config.rs:155` 双端已支持）；
  Mac 端 `parse_server` 本就剥离 `?query`（config.rs:160），Android 端同步补齐。
- `s`：密码原文（临时或永久，屏显形态，含连字符）。**必填**，无 `s` 视为无效配对码。
- `n`：Mac 设备名（手机端条目别名，选填）。percent-encoding（UTF-8），与 URL 规范一致。
- 端口省略时按协议默认 9432（两端 parse 现行为）。

### 方案比较（为何不是 JSON / 自定义 scheme）

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| A：rv:// + query（**选定**） | 与现有 rv:// 地址生态一致；扫码器识别为 URI；双端 parser 增量小 | 名字需 percent-encoding |
| B：JSON 文本 | 无转义问题 | 扫码历史记录里是一坨 JSON 不可读；与 rv:// 割裂 |
| C：新 scheme rvq:// | 语义最严格 | 双端各多一套解析；无收益 |

### 安全评估

密码原文进 QR = 密码原文上屏（现状本就明文展示供手抄），暴露面不变，无降级。
临时密码有时效，QR 随之失效；永久密码 QR 由用户主动打开，风险与手抄相同。

## 3. Mac 端（纯前端改动，无 Rust 变更）

- `web/vendor/qrcode.js`：内嵌 kazuhikoarase/qrcode-generator 2.0.4（MIT，UMD 单文件 56KB）。
  **不用 npm 依赖**：开发机 pnpm 在线 install 会被 TUN 代理挂起（交接文档备忘），vendor 文件
  使 `pnpm build` 离线构建不受影响；MIT 版权头保留。
- 入口：临时密码区、永久密码区（已设置时）各加「二维码」图标按钮 → 模态 overlay 展示
  QR（SVG）+「手机端扫码添加本机」提示 + 密码类型标题 + 关闭按钮。
- QR 内容：`rv://<当前配置的 relay 归一化地址>?s=<对应密码>&n=<本机名>`（`n` 经
  `encodeURIComponent`）。
- 渲染：ECC=M、版本自动，`qrcode(0,'M')`，SVG 按 module 输出 `<rect>`，CSS 控制尺寸。

## 4. Android 端

### 4.1 依赖决策修订（重要）

原约束「零第三方依赖：仅 Kotlin 标准库与 Android 框架 API（设计文档 §6.3）」修订为：
**允许 `com.google.zxing:core`（纯 Java、无传递依赖，Apache-2.0）作为唯一 maven 依赖**，
理由：QR 解码（RS 纠错/掩码/定位）自研不可行，intent 调外部扫码 App 在国产 ROM 不可靠
（无标准协议），ML Kit/CameraX 依赖面更大。仓库镜像：腾讯 maven（开发机直连 Maven Central
不通，2026-09-19 实测）。

### 4.2 扫码页（新增 `ScanActivity`）

- Camera2 + `ImageReader(YUV_420_888)`（640×480 级预览流）→ 平面亮度提取
  （处理 rowStride）→ zxing `PlanarYUVLuminanceSource` + `HybridBinarizer` →
  `MultiFormatReader`（仅 QR_CODE hint）。竖屏锁定（manifest `screenOrientation="portrait"`），
  按 `sensorOrientation` 旋转裁剪。
- 权限：CAMERA 运行时权限；拒绝 → 界面提示并保留「手动输入配对码」可用。
- **手动输入配对码**兜底入口（相机糊/暗/无权限场景）：文本框 + 确认，走同一解析落点；
  亦是本功能的自动化测试通道。
- 解码节流 ~8fps，避免持续全帧解码烧 CPU。

### 4.3 载荷落地（与既有添加流程合流）

`ConfigParser` 新增 `parsePairing(raw): Parsed?`（提取 query 的 s/n 后复用既有
`parseAuthority`；未知 query 参数忽略）。解析成功后：
1. 归一化 server 与当前设置不同 → 更新全局 server prefs（toast 告知）；
2. `store.add(alias=n, secret, typeOf)` → 新设备即激活（DeviceStore 现行为）；
3. `user_stopped=false` → 复用 `restartForActiveDevice()` 拉起/重连服务。
解析失败（非 rv:// / 缺 s / 地址非法）→ toast 具体原因，留在扫码页。

### 4.4 入口

主屏「＋ 添加」对话框加「扫码配对」中性按钮 → `ScanActivity`。设置页添加入口保持手填
（低频路径不动）。

## 5. 验证方案

1. **离线编码闭环**（无手、无相机）：node + vendor 库编码 → 自写 PNG（node zlib）→
   `zbarimg`（apt 包本地解包，独立实现解码器）逐字断言。已跑通 ✅
2. Android 构建：`assembleDebug`（zxing 经腾讯镜像拉取）。
3. 真机 e2e（小米 21091116AC + 假 relay + adb reverse，同 PLAN-022 harness）：
   扫码页「手动输入配对码」输入载荷 → 设备添加、服务器更新、自动连接 →
   假 relay 收到 AUTH / 界面进入已就绪。
4. 相机路径（预览→解码）：代码评审 + 构建验证；**实拍扫码留用户 10 秒验收**
   （开发环境无法无人值守对准摄像头），预期屏对屏扫码即可。

## 6. 自查记录（2 轮）

- 第 1 轮发现：Android 现行 `ConfigParser.parse` 对含 `?` 的输入返回 null（ConfigParser.kt:24），
  直接复用会让带 query 的配对码被判非法 → 新增 `parsePairing` 而不是改 `parse` 语义，
  避免影响「设置页手填地址报错」的既有行为（手填仍不允许带 query）。
- 第 1 轮发现：`input text` 对 `&` 的转义在 MIUI 上不可靠 → e2e 用省略 `n` 的载荷
  （`rv://host:port?s=...`）走自动化；完整载荷由真实扫码覆盖。
- 第 2 轮发现：Mac 的 QR 按钮若在断连状态下 server 为空 → 按钮禁用并提示先配置服务器
  （与首页「未配置服务器」态一致）。
- 第 2 轮确认：扫码成功后 `store.add` 即激活（DeviceStore.kt:64），无需额外 activate；
  `KEY_USER_STOPPED` 复用 MainActivity 常量，`restartForActiveDevice` 复用其实现——
  把该函数逻辑保持在 MainActivity 内，ScanActivity 通过回调回传载荷交给 MainActivity 处理，
  避免复制服务重启逻辑（ScanActivity 只产「配对码字符串」，消费在调用方）。
