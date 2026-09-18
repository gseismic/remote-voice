# PLAN-023 结果：扫码配对——Mac 出二维码、手机扫码一键添加连接

- 对应计划：[PLAN-023-qr-pairing.md](PLAN-023-qr-pairing.md)
- 设计文档：[../design/qr-pairing-20260919-overview.md](../design/qr-pairing-20260919-overview.md)
- 实施时间：2026-09-19
- 依赖 commit：9d4110b（PLAN-022）

## 结果摘要

**Mac 端**（纯前端，Rust 零改动）：
- `web/public/vendor/qrcode.js`：内嵌 qrcode-generator 2.0.4（MIT，UMD 单文件）。
  放 public 目录是因为 Vite 只原样拷贝 `publicDir`（首版放 `web/vendor/` 构建产物缺失，
  已修正）；`pnpm build` 离线通过，`dist/vendor/qrcode.js` 存在。
- 临时密码区新增「二维码」按钮 → 模态弹层展示 QR（SVG，白底保证扫码对比度）+ 载荷提示 +
  密码明文。服务器未配置/无临时密码时按钮禁用（title 提示原因）。
- 载荷：`rv://<host>[:<port>]?s=<临时密码>&n=<设备名>`（n 走 UTF-8 percent-encoding，
  库的 stringToBytes 切到 UTF-8 模式）。
- **范围收窄**：永久密码 QR 未做——永久密码原文存 Keychain，前端只有掩码，需要新 Rust
  命令才能出码；本机无法编译 macOS 目标验证，记为后续项（见遗留）。

**Android 端**：
- `settings.gradle.kts` 加腾讯 maven 镜像（开发机直连 Maven Central 超时，实测可达）；
  `app` 唯一 maven 依赖 `com.google.zxing:core:3.5.3`（纯 Java 无传递依赖），
  「零第三方依赖」约束修订已记录于设计文档 §4.1。
- `ConfigParser.parsePairing`：解析 `rv://...?s=&n=`（s 必填、n UTF-8 解码、未知参数忽略、
  地址复用既有 parseAuthority）；`parse()` 原语义不变（手填地址仍禁 query）。
- 新增 `ScanActivity`（portrait 锁定）：Camera2 预览 + ImageReader YUV 640×480 →
  亮度提取（rowStride 处理）+ 传感器方向旋转 → zxing MultiFormatReader（仅 QR），
  ~8fps 节流；CAMERA 运行时权限，拒绝时保留手动输入；「手动输入配对码」兜底与扫码
  同一回传通道。
- `MainActivity`：添加对话框新增「扫码配对」→ `onActivityResult` → `applyPairing`：
  服务器地址变化则切换并 toast；`store.add`（名空待 AUTH_OK 回填）+ 清 user_stopped +
  `restartForActiveDevice()`。

## 验证

1. 离线编码闭环 ✅：vendor 库（node 经典脚本沙箱）编码 → PNG → zbarimg（apt 本地解包，
   独立解码实现）逐字断言；URI 构建/守卫/中文 UTF-8 名/SVG 矩阵 node 断言全过。
2. 构建 ✅：`pnpm build`（Mac web）、`assembleDebug`（APK 923KB→2.0MB，zxing 经镜像拉取）。
3. 真机 e2e ✅（小米 21091116AC + 假 relay 19432 + adb reverse，同 PLAN-022 harness）：
   「＋ 添加 → 扫码配对 → 手动输入 `rv://127.0.0.1:19432?s=26S3-YRRW`」→ 服务器自动切换
   （prefs server 变更）→ 新设备激活（名空显示密码前缀）→ 自动连接 → 假 relay 收 AUTH、
   回 AUTH_OK+PEER_STATE online → 界面「已就绪 · TestMac」（**Mac 名自动回填生效**）→
   桥接同步 TALK 0x00 到达，全程无崩溃。
4. **待用户 10 秒验收**：相机实拍路径（屏对屏扫码）。开发环境无法无人值守把摄像头对准
   屏幕；解码管线（zxing）与落地逻辑均已分别验证，风险点仅剩 MIUI 相机预览实拍表现。

## 过程中发现并修正的问题

- Vite 不拷贝 `web/vendor/`（非 publicDir）→ 移至 `web/public/vendor/`。
- `mac-app-tauri/package.json` 的 `"type": "module"` 使 node 把 UMD 库当 ESM 加载
  （require 得空命名空间）——仅影响 node 测试方式，浏览器 `<script>` 经典加载正常；
  测试改用 vm 经典脚本沙箱。
- CaptureRequest.Builder 的 `set()` 返回 void，Kotlin 不能链式调用（编译期发现）。

## 遗留事项

- 永久密码 QR：需 Rust 新增命令读 Keychain 出码（前端无法拿到明文），下次在 Mac 上
  构建时一并做。
- 相机实拍验收（见上）。
