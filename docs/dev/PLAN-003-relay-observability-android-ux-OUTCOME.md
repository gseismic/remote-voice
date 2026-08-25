# PLAN-003 实施结果（OUTCOME）

- 计划: [PLAN-003-relay-observability-android-ux.md](PLAN-003-relay-observability-android-ux.md)
- 完成: 2026-08-26 02:40
- 基线 commit: ca3ad13 → 实施 commit: 见 git log

## 一、交付内容

### A. relay 可观测性（含致命缺陷修复）
| 项 | 文件:位置 | 说明 |
|---|---|---|
| 握手移出 Accept | `relay/internal/server/server.go` handleConn | 单个 TLS 握手失败只断该连接并记日志，不再杀死进程；`Serve` 对暂时性 Accept 错误退避重试 |
| 全连接日志 | 同上 | `accepted / tls handshake ok / tls handshake failed / authenticated / rejected / bridged / disconnected`，统一带 peer 地址 |
| 流量统计 | server.go statsLoop + session.go 计数器 | 每 10s 窗口汇总 AUDIO 帧/字节，仅增量>0 时输出，不逐帧刷屏、空闲无噪音 |
| 配置串打印 | `relay/main.go` | 启动横幅输出 `rv://host:port?t=&f=` 一行配置 |
| 回归测试 | server_test.go TestTLSHandshakeFailureDoesNotKillServer | 裸 TCP 垃圾攻击后服务器存活、合法客户端正常桥接；断言失败日志存在 |

### B. Android 极简体验
| 项 | 文件 | 说明 |
|---|---|---|
| 主界面极简 | activity_main.xml + MainActivity.kt | 仅"连接密码"输入 + 生成强密码按钮 + 启停 + 着色状态（绿/蓝/红/灰）与推流帧计数；运行中禁用开始键 |
| 设置页 | SettingsActivity.kt + activity_settings.xml | 服务器地址预置默认 `43.139.226.138:9432`、指纹、回声消除、`rv://` 导入一键填全 |
| 解析器 | ConfigParser.kt | rv:// 解析 + 指纹归一化校验（64 位 hex） |
| 状态机 | AudioStreamService.kt | StatusState 五态枚举、framesSent 计数、配置缺失分类报错、prefs 缺省回落默认服务器 |
| 兼容 | — | prefs key 不变；零第三方依赖不变；主题换 DeviceDefault.DayNight |

### C. 需求变更记录
用户中途追加"密码自设/可生成、服务器入设置页带默认值"，已并入计划 v2；
否决"由弱秘密透明派生复杂 token"方案（伪安全+排障成本），理由见计划 §B 变更记录。

## 二、验证记录
1. `go build ./... && go vet ./... && go test ./... -race -count=1` ✅ 全绿
   （期间 -race 抓到测试自身 bytes.Buffer 并发读写，已用 syncBuf 包装修复——生产代码无竞争）
2. 回环冒烟（relay+fakephone+receiver --sink null）✅：
   日志序列 accepted→handshake ok→authenticated→bridged→stats(role=phone audio_frames=454/10s)；
   Mac 端稳定 50 帧/秒。
3. `./gradlew assembleDebug` ✅（app-debug.apk 857KB）
4. 真机安装 ✅：adb install Success（设备 5LFAJ7TWUGUCCERO）

## 三、遗留与后续建议
- App 内 rv:// 导入后主界面密码框需重进页面刷新显示（onResume 读 prefs），不影响保存值正确性
- relay 无防爆破限速仍为已知限制（README §已知限制）；自设短口令时风险上升，文档已提示优先「生成」
- fakephone 未同步打印配置串（低价值，未做）
