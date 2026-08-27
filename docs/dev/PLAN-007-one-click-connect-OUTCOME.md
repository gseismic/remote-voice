# PLAN-007 · 实施结果（OUTCOME）

- 对应计划: [PLAN-007-one-click-connect.md](PLAN-007-one-click-connect.md)
- 完成时间: 2026-08-28
- 状态: 全部完成

## 提交链

| commit | 范围 |
|---|---|
| 本次 | Android 一键连接（MainActivity/布局/strings）+ mac-app 自动连接 + README/HANDOFF/INDEX 同步 |

## 验证汇总

- **Android**：`./gradlew assembleDebug` BUILD SUCCESSFUL（0.2.0/versionCode 2 未变）
  ——改动：删除「连接/断开」按钮与相关逻辑；状态条点击=连接/停止
  （停止写 `user_stopped`，onResume 不自动拉起，再点恢复）；芯片切换=
  激活+重连（运行中 stop→600ms→start）；打开 App 自动连接（权限检查前置）；
  传输中状态行显示按住秒数+帧计数
- **mac-app**：pytest 21 例全绿；offscreen 冒烟验证 `_connect_if_ready`：
  配置齐的 MainWindow 构造后 1.5s 状态进入重连态（auto-connect 触发），
  缺配置时显示"待配置…"提示
- README（根/mac-app/android-app）操作段更新为"打开即连/自动连接/切换即重连"

## 经验与遗留

- Android 无 adb 真机，按 PLAN-005 惯例以 assembleDebug + review 为界，
  真机验证（自动连接/切换重连手感）移交用户
- 波形/动画反馈（android-ptt.html 高保真）留待可视化计划；本次以秒数+帧计数替代（NON-goal 声明）
- mac-app GUI 无新增自动化用例（offscreen 冒烟脚本为临时验证，未入库）；
  如后续要防回归可把 auto-connect 分支单测化（History/config 注入）
- M-2（peer-offline）仍未拍板（见 review §三）
