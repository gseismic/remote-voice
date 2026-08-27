# PLAN-006 · 三端目录重组 + Mac 侧包化（repo-restructure）

- 日期: 2026-08-28
- 状态: 待实施
- 前置: 设计定稿 docs/design/repo-restructure-20260828-v3.md；缺陷依据 docs/dev/20260826-1540-REVIEW-13aadb1-v2-impl.md (§三 H-1/H-2)

## 〇、验收口径（最终整体）

1. `cd server && go test ./... -count=1 -race` 全绿；`go build -o server-linux`（CGO_ENABLED=0）成功。
2. `pip install -e '.[test]'` 后 `remote-voice-gui` 可 offscreen 实例化；`remote-voice-recv --help` 参数齐全。
3. `pytest`（mac-app/tests）全绿：原 mac-client 9 逻辑 + 回环 5 + 原 receiver 5 用例全部迁移后通过，
   另新增 2 项回归（静默保活、指纹 fatal）。
4. 全库无 `remote-voice/relay`、`relay-linux`、`mac-client/`、`mac-receiver/` 路径残留（文档正文中的
   职能词"relay/中继"除外，以 grep 清单核对）。
5. 三子 README + 根 README 存在且互相链接；HANDOFF/INDEX 更新。

## 一、Phase A · server 改名（commit 1）

- A1 `git mv relay server`；`git mv server/token.txt` 不动（git mv 整个目录，ignored 文件本就不在库，
  但本地盘上的 data/token 随之移动，避免残留目录）。
- A2 go.mod: module `remote-voice/server`；全库替换 import 前缀 `remote-voice/relay`→`remote-voice/server`
  （`grep -rl` 定位 → sed 全替换，范围仅 .go）。
- A3 产物名：构建输出 `relay`→`server`、`relay-linux`→`server-linux`；.gitignore 相应前缀替换
  `relay/data/`→`server/data/`、`relay/relay`→`server/server`、`relay/relay-linux`→`server/server-linux`、
  `relay/token.txt`→`server/token.txt`。
- A4 文档路径引用替换：README.md、DEPLOY.md（含 `./relay`、`relay-linux` 字面）、docs/HANDOFF.md、
  docs/dev/INDEX.md、docs/design/*.md、android-app/BUILD.md（`cd relay`→`cd server`；
  若 BUILD.md 无引用则跳过）。
- A5 验证：`go test ./... -count=1 -race`；`cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64
  go build -trimpath -ldflags="-s -w" -o server-linux .` 成功；本机 build 掉 server/relay 旧产物。
- 暂不动的：`relay` 字面仅剩代码注释/日志（职能词）与历史文档（HANDOFF 记录"v2 实施后再改名"）。

## 二、Phase B · mac-app 包化合并（commit 2）

- B1 建目录骨架：
  - `git mv mac-client mac-app` 前置，再内部重组：`src/macapp/`；`git mv` 保历史。
  - 文件映射：protocol.py→同上；secretsgen.py / history.py / config_store.py / audio.py / core.py /
    ui_main.py / ui_history.py / main.py→main_gui.py（内部 main() 符号不变）；tests/* 移动；
    mac-client/requirements.txt、mac-receiver/ 整体并入（receiver.py→src/macapp/receiver_cli.py，
    test_receiver.py→tests/test_protocol.py+test_writer.py 拆分，config.example.toml→mac-app/config.example.toml）。
  - mac-client/.pytest_cache、mac-receiver/.pytest_cache 一律不提交（git 不追踪，直接删）。
- B2 包化改造（相对路径 import 全部改 `from macapp... import ...`）：
  - `__init__.py` 空（注明包用途）。
  - protocol.py：并入 receiver 的帧函数（read_frame/write_frame 与现 protocol.py 等价——保留现版本
    实现即 v1 约定），追加 `normalize_fingerprint`（receiver 版）、`FrameWriter`（含 threading.Lock
    的串行写，供 core/CLI/测试共用）。
  - core.py：
    1) 用 FrameWriter 替换 `_write_lock` 用法（行为等价）；
    2) **H-1**：AUTH_OK+REGISTER 完成后启动心跳线程（10s PING，写失败退出），stop() 时置停止位；
       `_read_loop` 起手 `self._conn.settimeout(READ_TIMEOUT)` 恢复 40s 读超时（不再继承 AUTH 的 10s）；
    3) **H-2**：`_attempt` 内包一层 catch `proto.FatalError` → 返回致命标记字符串（与 auth_error 同传
       ST_FATAL 语义）。
  - audio.py：原 SoundDeviceSink（GUI）保留；并入 receiver 的 NullSink（含 5s 帧率统计日志）与
    AudioSink（显式建流/start/stop；设备查找失败 sys.exit 消息沿旧文案）。CollectSink 保留。
  - receiver_cli.py：
    - 参数与校验沿 receiver.py `main()`（server/regkey/fingerprint 必填 + fp 64 校验）；
    - 用 core.RelayClient 承载连接：构造 sink=AudioSink 或 NullSink（--sink null）；
      on_state 回调驱动 sink start/stop（BRIDGED→start，REGISTERED/STOPPED→stop）；
      on_event 打日志（EVENT 原样）；on_bridge_stats 打总时长/流量；
    - 致命（认证拒/指纹不符，ST_FATAL）→ `sys.exit(1)` 并打印原因；
    - temp/perm 秘密：--temp-secret/--perm-secret 传入 core.update_temp/update_perm，
      临时留空则由本端生成并打印（secretsgen.gen_temp_secret），temp_expiry 默认 8h；
      先 REGISTER 后阻塞 join；
    - config.toml 加载逻辑保留（`load_config("config.toml")`）。
  - main_gui.py：去 os.chdir；`from macapp.ui_main import MainWindow` 等；main() 与 QApplication 逻辑不变。
  - ui_main.py：`import secretsgen as sec` → `from macapp import secretsgen as sec` 等全部改包内导入。
  - 测试迁移：
    - conftest.py：sys.path 插入 `src/`（本目录=mac-app，路径：`Path(__file__).parents[1]/"src"`），
      保证非安装态 `pytest` 可跑；导入一律 `from macapp... import ...`。
    - test_secrets.py / test_history.py：仅改 import。
    - test_loopback.py：import 改 macapp.*；`repo/"relay"`→`repo/"server"`；
      `_find_repo` 检查 `(p/"server")`；构建产物名 relay-test→server-test（任意名可）。
      **新增**：test_idle_keepalive（注册后 sleep 15s，断言 state 仍 REGISTERED、无重连事件）；
      test_fingerprint_mismatch_fatal（错指纹 → ST_FATAL 且线程退出）。
    - test_protocol.py：帧 roundtrip/空帧/超限（原 1/2/3）+ normalize_fingerprint（原 4）。
    - test_writer.py：并发 4 线程×200 帧经 FrameWriter 写 FakeSock，断言长度精确+帧头完整（原 5）。
- B3 pyproject.toml：
  ```toml
  [build-system] requires=["setuptools>=68"] build-backend="setuptools.build_meta"
  [project] name="remote-voice-mac" version="0.3.0" requires-python=">=3.11"
      dependencies=["PySide6>=6.6","sounddevice>=0.4.6","numpy>=1.26"]
  [project.optional-dependencies] test=["pytest>=8"]
  [project.scripts] remote-voice-gui="macapp.main_gui:main" remote-voice-recv="macapp.receiver_cli:main"
  [tool.setuptools.packages.find] where=["src"]
  ```
- B4 验证：`pip install -e '.[test]' --no-build-isolation`（用现有 conda 环境，避免装 PySide6 重复）；
  `python3 -m pytest tests/ -q` 全绿；`QT_QPA_PLATFORM=offscreen` 起 remote-voice-gui 冒烟
  （QTimer 驱动 2s 后退出——用 `timeout 3` 观察进程活着即可）；`remote-voice-recv --help` 输出全参数。

## 三、Phase C · README 三件套 + 根 README（commit 3）

- C1 server/README.md：简介/构建/参数/常见问题；链 DEPLOY.md。
- C2 mac-app/README.md：安装（venv+pip install -e .）、GUI/CLI 用法命令示例、config.json 与
  Keychain 说明、常见问题（含 别把 regkey.txt 传手机等安全提示）。
- C3 android-app/README.md：简介/构建装机/使用流程；链 BUILD.md。
- C4 根 README.md 重写：总览图（server/mac-app/android-app）+ 每端快速启动段 + 三子 README 链接 +
  开发验证命令（go test/ pytest / fakephone 回环路径更新为 server/）。
- C5 清理旧引用：README.md/根 中原 mac-client/mac-receiver/relay/ 路径段全部收敛；DEPLOY.md 内
  systemd 产物名 server-linux 已在 A4 处理，此处复核。

## 四、Phase D · 文档与交付（commit 3/4）

- D1 docs/HANDOFF.md：更新为三端目录结构、server/mac-app 状态、H-1/H-2 已在重构中修复的说明、
  下一步（真机部署）。
- D2 docs/dev/INDEX.md：追加 PLAN-006 条目（计划+结果文件对应、日期精确到分钟）。
- D3 docs/dev/PLAN-006-repo-restructure-OUTCOME.md：结果文件（提交表+验证表+遗留）。
- D4 复核 grep：`remote-voice/relay|relay-linux|mac-client/|mac-receiver/` 只允许存在于
  docs/design/*.md 的历史叙述与 DESIGN 文档、HANDOFF 历史记录，正文无路径残留。
- D5 分段提交+push：A/B/C+D 按 commit 1/2/3 推送（D1-D4 随 C3 或独立 commit 4，按 diff 干净度切）。

## 五、自查（计划三重检查）

- [ ] 设计一致性：三端命名/包结构/双入口/README 分工与设计定稿一致；NON-goal 未侵入。
- [ ] 行为等价：CLI 参数表逐项对照 receiver.py main()（--sink 两值、--temp-expiry、config.toml）；
  GUI 行为（临时卡/永久卡/历史/热更）不因 import 改造变化。
- [ ] 风险面：`git mv` 保历史；import 改包后 pytest 离线可跑（conftest 注入 src）；
  PySide6 安装用 `--no-build-isolation` 防 rebuild；回环测试构建 server 依赖 go（已在开发机）；
  删除 requirements.txt 前确认 pyproject 依赖覆盖（PySide6/sounddevice/numpy/pytest）。
- [ ] 测试面：原 14(9+5)+5 用例全量迁移 + 新增 2 回归；无测试删除（仅平移/拆分）。
- [ ] 交付物：server-linux 交叉编译生产物（不进库）、三个 README、设计/计划/结果三文档、HANDOFF/INDEX。
