# PLAN-005 · 实施结果（OUTCOME）

- 对应计划: [PLAN-005-v2-protocol-clients.md](PLAN-005-v2-protocol-clients.md)
- 完成时间: 2026-08-26
- 状态: 全部完成（分三段提交并推送）

## 提交链

| commit | 范围 | 说明 |
|---|---|---|
| `20c15ee` | Phase A relay v2 | 注册表路由、限速防爆破、REGISTER/EVENT、fakephone v2 双角色 |
| `09f3edf` | Phase B mac-client | PySide6 GUI + core + secrets + history + 14 测试；receiver.py v2 |
| `116db06` | Phase C Android v2 | PTT 底部大按钮、多设备单激活、设置页设备管理、rv:// 无 token |
| 本次 | Phase D 文档/交付 | README/buILD/DEPLOY/设计文档/HANDOFF 同步；relay-linux 产物 |

## 验证汇总

- `cd relay && go test ./... -count=1 -race`：全绿（server 16 + protocol 4，共 20 例）
  - 覆盖：bad regkey/错秘密/过期秘密（注册后到期）/多 Mac 并存/peer-busy/热更旧秘密即失效/
    Mac 断开注册清理/限速锁定+递增+成功清零/EVENT(auth-ok/auth-fail)/到期不断已建桥/未知帧/心跳/TLS 攻击存活
  - 修复的缺陷：ratelimit 零值误判清计数；过期测试时序放宽为 2s 窗口消除 CPU 争用偶发
- `mac-client`: 9 纯逻辑单测 + 5 回环集成（真实 relay 进程 + 假手机 pinning socket），全绿；
  GUI offscreen 冒烟（窗口实例化/倒计时/短码）通过
- `mac-receiver`: 5 项协议单测保持全绿
- `android-app`: `./gradlew assembleDebug` BUILD SUCCESSFUL（versionName 0.2.0）
- `relay-linux`：CGO_ENABLED=0 静态编译，5.9MB stripped（git 忽略，交付用）

## 经验记录

- Python 模块命名冲突：`secrets.py` 与标准库同名导致内部 import 自引用 → 改名 secretsgen.py
- Go 侧 JSON int64 字段收到浮点（Python `int(time)+1.6`）会解不出来 → 注册 payload 统一 int()
- Android 端 PTT：capture 循环常开+发送门控（非启停 AudioRecord），避免热启动延迟；
  数据不出本机，合规说明在代码注释里
- 到期秘密的 EVENT 回投仅对"已知过期"（注册表存在但过期）生效；无效秘密无法路由属设计

## 交付清单（用户执行）

1. 服务器：`openssl rand -hex 32 > regkey.txt` → 上传 relay-linux → systemd 改 `-regkeyfile` 重启
   （data 目录保留，指纹不变；命令明细见 relay/DEPLOY.md）
2. Mac：`pip install -r mac-client/requirements.txt` → `python3 mac-client/main.py` 填 服务器/指纹/regkey/设备名
3. 手机：装新版 APK（0.2.0）→ 设置导入 `rv://…?f=指纹` → ＋添加输入 Mac 短码 → 连接→按住说话
