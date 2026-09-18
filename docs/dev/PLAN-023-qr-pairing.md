# PLAN-023: 扫码配对——Mac 出二维码、手机扫码一键添加连接

- 依赖 commit：9d4110b（PLAN-022 闪退修复）
- 设计文档：docs/design/qr-pairing-20260919-overview.md（含方案比较与自查，先读它）
- 编写时间：2026-09-19

## 背景

添加连接现状：手机要在设置页手填服务器地址、再在添加对话框手抄 Mac 屏上的密码，
两步都易错（历史联测出现过服务器地址配错、密码抄错）。本计划实现：Mac 屏上出
二维码（含服务器+密码+设备名），手机扫码即完成配置并连接。

## 实施步骤

### A. Mac 端（纯前端，无 Rust 变更）

1. `mac-app-tauri/web/vendor/qrcode.js`：内嵌 qrcode-generator 2.0.4（MIT，UMD）。
2. `web/index.html`：
   - 临时密码区加「二维码」图标按钮（`#qr-temp`）；
   - 永久密码区加「二维码」图标按钮（`#qr-perm`，未设置时禁用）；
   - 新增 QR 模态 overlay（`#qr-modal`：标题/QR 容器/提示/关闭）。
3. `web/app.js`：
   - `buildPairingUri(secret)`：`rv://<server 归一化>?s=<secret>&n=<encodeURIComponent(state.name)>`；
     server 为空或不可解析 → 按钮禁用态（title 提示先配置服务器）；
   - `showQr(kind)`：调 vendor 库生成 module 矩阵 → SVG（`<rect>` 黑块），填入模态；
   - 两个按钮 + 关闭/点击遮罩关闭的事件绑定。
4. `pnpm build` 通过（离线，vendor 不影响依赖图）。

### B. Android 端

1. `settings.gradle.kts`：repositories 加腾讯 maven 镜像（`mirrors.cloud.tencent.com/nexus/repository/maven-public/`），置于 mavenCentral 之前（开发机直连 central 超时）。
2. `app/build.gradle.kts`：`implementation("com.google.zxing:core:3.5.3")`；更新零依赖注释指向设计文档修订。
3. `ConfigParser.kt`：新增 `data class Pairing(server, secret, name)` 与
   `fun parsePairing(raw: String): Pairing?`——分离 query（`?`后）解析 `s`/`n`
   （URLDecoder, UTF-8），地址部分复用 `parseAuthority`；`s` 缺失或地址非法返回 null。
   不改 `parse()` 现有语义（手填地址仍禁 query）。
4. `ScanActivity.kt`（新增，portrait 锁定）：
   - CAMERA 运行时权限流程；拒绝 → 预览区显示提示 + 手动输入仍可用；
   - Camera2 打开后置摄像头 + `ImageReader` YUV 640×480 → 亮度平面提取（rowStride 处理）
     → 按 sensorOrientation 旋转 → zxing `PlanarYUVLuminanceSource`/`HybridBinarizer`/
     `MultiFormatReader`(QR_CODE) 解码，~8fps 节流；
   - 解码成功 → 回传配对码字符串给调用方（`finish()` with result）；
   - 底部「手动输入配对码」文本框 + 按钮 → 同一回传通道；
   - 顶部提示文案「对准 Mac 端二维码」。
5. `MainActivity.kt`：
   - `showAddDeviceDialog()` 加中性按钮「扫码配对」→ `startActivityForResult(ScanActivity)`；
   - `onActivityResult`：`ConfigParser.parsePairing` 解析 → server 变更则写入 prefs 并
     toast → `store.add(alias=n, secret, typeOf)` → `restartForActiveDevice()` →
     `renderDevices()`；解析失败 toast 原因。
6. `AndroidManifest.xml`：加 `CAMERA` 权限；注册 `ScanActivity`（exported=false，
   screenOrientation=portrait）。

### C. 验证

1. 离线闭环（已完成）：vendor 库编码 → PNG → zbarimg 逐字断言（docs/design 文档 §5.1）。
2. `assembleDebug` 通过（zxing 经腾讯镜像）。
3. 真机 e2e（假 relay + `adb reverse`，同 PLAN-022 harness）：
   - 扫码页手动输入 `rv://127.0.0.1:19432?s=26S3-YRRW`（无 `&`，规避 adb input 转义）→
     添加设备、假 relay 收 AUTH、界面已就绪；
   - 原有添加对话框手填路径回归不破。
4. 相机实拍：留用户屏对屏 10 秒验收（环境无法无人值守对焦）。

### D. 文档与提交

- OUTCOME：`docs/dev/PLAN-023-qr-pairing-OUTCOME.md`；
- INDEX 追加条目；HANDOFF「关键技术决策速查」补 QR 载荷 scheme 与镜像备忘；
- commit 中文、含计划/结果文件路径，push。

## 风险与回滚

- Camera2 在 MIUI 的兼容性：标准 API、minSdk 26；如个别机型预览异常，手动输入兜底可用，
  不阻塞主流程。
- zxing 拉包失败：镜像已实测可达；若仍失败，回退方案为 vendor zxing core 源码子集
  （Apache-2.0），本计划不预先实施。
- Mac 端纯前端，`pnpm build` 即可验证，不触碰 Rust/签名链。
