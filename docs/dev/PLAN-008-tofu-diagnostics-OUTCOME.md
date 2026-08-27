# PLAN-008 · 实施结果（OUTCOME）

- 对应计划: [PLAN-008-tofu-diagnostics.md](PLAN-008-tofu-diagnostics.md)
- 完成时间: 2026-08-28
- 状态: 全部完成

## 提交链

| commit | 范围 |
|---|---|
| 本次 | Android TOFU+诊断（ConfigParser/RelayClient/AudioStreamService/SettingsActivity/strings/layout）+ mac-app TOFU（core/ui_main）+ 新增回环用例 + 文档 |

## 验证汇总

- **Android**：`./gradlew assembleDebug` BUILD SUCCESSFUL
  - ConfigParser：`f=` 改为可选（旧串含 f= 仍解析）
  - RelayClient：空指纹=TOFU（TrustAll + 握手后记录 DER SHA-256 → onPeerFingerprint 回调 +
    实例内固化）；异常分类中文（超时/拒连/DNS/指纹不符/TLS 失败），
    状态条与通知显示"重连失败(第N次): 原因"
  - AudioStreamService：空指纹不再报错阻断；onPeerFingerprint 持久化到 prefs
  - SettingsActivity：指纹编辑框 → "服务器信任"展示（已信任 xx…/未信任）+ 清除信任按钮；
    导入串无 f= 时不覆盖已有信任
- **mac-app**：pytest **22 例全绿**（新增 test_tofu_auto_trust_and_reuse：空指纹→注册成功+
  on_fingerprint 回调=真实指纹+内部固化）；offscreen 冒烟（修改后重跑通过）
  - core：fp=""=TOFU（首连记录并回调、后续固定）；指纹不符 fatal 文案含期望/实际前 12 位
    与"清空指纹重新信任"指引
  - ui_main：连接校验放宽（fp 可空），placeholder 提示"留空=自动信任"，自动信任后写回
    config.json 并提示"已连接中继（首次连接已自动信任证书）"
- **文档**：根/子 README 指纹描述改 TOFU；DEPLOY §6.1 增加客户端原因↔日志行对照表行

## 关键记录

- 指纹不符时 Android 端 SSLHandshake 异常 message 被 cause-chain 查找"指纹不符"还原为专用文案
- TOFU 首次记录的证书若被 MITM 替换，信任的是替换者——敞口已记入设计文档（个人自用接受）
- 排障对照表（客户端文本 ↔ server 日志）写入 DEPLOY.md §6.1：

| Android/mac 界面原因 | server 日志行 | 排查 |
|---|---|---|
| 连接超时 | 无新行（accept 不可达） | 防火墙/安全组 9432 是否放行 |
| 连接被拒绝 | 无新行 | server 未启动/地址错 |
| 证书指纹不符 | `tls handshake failed` | 两端指纹一致性/换证书后需清除信任 |
| auth 拒绝（invalid-secret 等） | `auth failed peer=.. (invalid-secret)` | Mac 是否在线/秘密是否已更新 |

## 遗留

- 真机验证移交用户（Android 无 adb）；M-2（peer-offline）仍未拍板
