#!/usr/bin/env python3
"""Mac 主窗口（对齐高保真稿 v2/index.html 的 Mac 侧）：

状态条（已连接中继 / 对讲在线）· 临时秘密卡（短码/倒计时/时长档/复制/重生成/保持）
· 永久秘密卡（掩码/修改/停用）· 连接历史入口 · 底部连接设置（服务器/指纹/regkey）。
"""

import time

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import (QCheckBox, QFrame, QGridLayout, QHBoxLayout, QLabel,
                               QLineEdit, QMainWindow, QMessageBox, QPushButton,
                               QVBoxLayout, QWidget)

from macapp import secretsgen as sec
from macapp import core as core_mod
from macapp.history import Entry, History
from macapp.ui_history import HistoryWindow

EXP_CHOICES = [("10分钟", 600), ("1小时", 3600), ("8小时", 28800), ("24小时", 86400)]
DEFAULT_EXP = 8 * 3600

KIND_PERM = "永久"
KIND_TEMP = "临时"


class MainWindow(QMainWindow):
    def __init__(self, cfg: dict, history: History):
        super().__init__()
        self.cfg = cfg
        self.history = history
        self.client = None           # core.RelayClient
        self.hist_win = None
        self.pending_bridge = None   # auth-ok 事件 → 桥接结束时补 dur/bytes

        self.setWindowTitle("远程麦克风 · 遥控说话")
        self.resize(460, 700)

        root = QWidget()
        lay = QVBoxLayout(root)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(10)

        # ---- 状态条 ----
        self.led = QLabel("●")
        self.led.setObjectName("led")
        self.status_text = QLabel("未连接")
        row = QHBoxLayout()
        row.addWidget(self.led)
        row.addWidget(self.status_text)
        row.addStretch()
        meta = QLabel(self.cfg.get("server", ""))
        meta.setObjectName("muted")
        row.addWidget(meta)
        lay.addLayout(row)

        # ---- 临时秘密卡 ----
        card_temp = QFrame()
        card_temp.setObjectName("card")
        ct = QVBoxLayout(card_temp)
        ct.setContentsMargins(14, 12, 14, 12)
        ct.setSpacing(8)
        head = QHBoxLayout()
        head.addWidget(QLabel("临时秘密 — 给来访手机输入"))
        head.addStretch()
        ct.addLayout(head)
        self.code_label = QLabel("")
        self.code_label.setObjectName("bigcode")
        self.code_label.setAlignment(Qt.AlignCenter)
        self.code_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        ct.addWidget(self.code_label)

        btn_row = QHBoxLayout()
        self.btn_copy = QPushButton("复制")
        self.btn_regen = QPushButton("⟳ 重新生成")
        btn_row.addWidget(self.btn_copy)
        btn_row.addWidget(self.btn_regen)
        ct.addLayout(btn_row)

        cd_row = QHBoxLayout()
        cd_row.addWidget(QLabel("有效期剩余"))
        cd_row.addStretch()
        self.countdown = QLabel("--:--:--")
        self.countdown.setObjectName("mono")
        cd_row.addWidget(self.countdown)
        ct.addLayout(cd_row)

        self.exp_bar = QFrame()
        self.exp_bar.setObjectName("bar")
        self.exp_bar.setFixedHeight(4)
        bh = QHBoxLayout(self.exp_bar)
        bh.setContentsMargins(0, 0, 0, 0)
        self.exp_fill = QFrame()
        self.exp_fill.setObjectName("barfill")
        bh.addWidget(self.exp_fill)
        ct.addWidget(self.exp_bar)

        chips = QHBoxLayout()
        self.exp_chips = {}
        for text, s in EXP_CHOICES:
            b = QPushButton(text)
            b.setCheckable(True)
            b.setFixedHeight(26)
            chips.addWidget(b)
            self.exp_chips[s] = b
            b.clicked.connect(lambda _=False, s=s: self._pick_exp(s))
        ct.addLayout(chips)
        self.chk_keep = QCheckBox("重启后保持此秘密（默认每次重启重新生成）")
        ct.addWidget(self.chk_keep)
        lay.addWidget(card_temp)

        # ---- 永久秘密卡 ----
        card_perm = QFrame()
        card_perm.setObjectName("card")
        pt = QVBoxLayout(card_perm)
        pt.setContentsMargins(14, 12, 14, 12)
        pt.setSpacing(8)
        phead = QHBoxLayout()
        phead.addWidget(QLabel("永久秘密 — 长期设备用"))
        phead.addStretch()
        self.perm_pill = QLabel("已停用")
        self.perm_pill.setObjectName("pill")
        phead.addWidget(self.perm_pill)
        pt.addLayout(phead)
        self.perm_shown = QLabel("••••••••••••")
        self.perm_shown.setObjectName("masked")
        pt.addWidget(self.perm_shown)
        pbtns = QHBoxLayout()
        self.btn_perm = QPushButton("设置 / 修改")
        self.btn_perm_del = QPushButton("停用")
        self.btn_perm_del.setObjectName("danger")
        pbtns.addWidget(self.btn_perm)
        pbtns.addWidget(self.btn_perm_del)
        pt.addLayout(pbtns)
        lay.addWidget(card_perm)

        # ---- 审计入口 ----
        self.btn_hist = QPushButton("查看连接历史 →")
        self.btn_hist.setObjectName("primary")
        lay.addWidget(self.btn_hist)

        # ---- 连接设置 ----
        conn_box = QFrame()
        conn_box.setObjectName("card")
        cg = QGridLayout(conn_box)
        cg.setContentsMargins(14, 10, 14, 10)
        cg.setVerticalSpacing(6)
        self.ed_server = QLineEdit(self.cfg.get("server", ""))
        self.ed_fp = QLineEdit(self.cfg.get("fingerprint", ""))
        self.ed_key = QLineEdit(self.cfg.get("regkey", ""))
        self.ed_key.setEchoMode(QLineEdit.Password)
        self.ed_name = QLineEdit(self.cfg.get("name", _hostname()))
        cg.addWidget(QLabel("服务器"), 0, 0)
        cg.addWidget(self.ed_server, 0, 1)
        cg.addWidget(QLabel("指纹"), 1, 0)
        cg.addWidget(self.ed_fp, 1, 1)
        cg.addWidget(QLabel("注册密钥"), 2, 0)
        cg.addWidget(self.ed_key, 2, 1)
        cg.addWidget(QLabel("设备名"), 3, 0)
        cg.addWidget(self.ed_name, 3, 1)
        lay.addWidget(conn_box)

        bpx = QHBoxLayout()
        self.btn_conn = QPushButton("连接")
        self.btn_conn.setObjectName("primary")
        self.btn_dis = QPushButton("断开")
        self.btn_dis.setObjectName("danger")
        bpx.addWidget(self.btn_conn)
        bpx.addWidget(self.btn_dis)
        lay.addLayout(bpx)
        lay.addStretch()
        self.setCentralWidget(root)

        # ---- 交互绑定 ----
        self.btn_copy.clicked.connect(self._copy)
        self.btn_regen.clicked.connect(self._regen)
        self.chk_keep.stateChanged.connect(self._persist)
        self.btn_perm.clicked.connect(self._perm_dialog)
        self.btn_perm_del.clicked.connect(self._perm_disable)
        self.btn_hist.clicked.connect(self._open_history)
        self.btn_conn.clicked.connect(self._connect)
        self.btn_dis.clicked.connect(self._disconnect)

        # ---- 状态供给 ----
        self._load_perm_ui()
        self._init_temp()
        self._tick_timer = QTimer(self)
        self._tick_timer.timeout.connect(self._tick)
        self._tick_timer.start(1000)
        self._drain_timer = QTimer(self)
        self._drain_timer.timeout.connect(self._drain_events)
        self._drain_timer.start(200)
        self._queue = []
        # 启动即自动连接：一次性配置齐全时零点击（傻瓜式一键连接，对齐 v2 设计稿）
        QTimer.singleShot(0, self._connect_if_ready)

    # ---------- 临时秘密 ----------

    def _init_temp(self) -> None:
        now = int(time.time())
        keep = bool(self.cfg.get("keep", False))
        stored = self.cfg.get("temp_secret", "")
        exp = int(self.cfg.get("temp_exp", 0))
        if stored and exp > now:
            self.code = stored
            self.exp = exp
        else:
            self.code = sec.gen_temp_secret()
            self.exp = now + DEFAULT_EXP
        self.chk_keep.setChecked(keep)
        self._sync_keep()
        self._refresh_on()
        self._push()

    def _push(self) -> None:
        if self.client is not None:
            self.client.update_temp(sec.hash_of(self.code), self.exp)

    def _sync_keep(self) -> None:
        keep = self.chk_keep.isChecked()
        exp = self.exp
        for s, b in self.exp_chips.items():
            b.setChecked(abs(exp - (int(time.time()) + s)) < 2)
        self.cfg["keep"] = keep
        if keep:
            self.cfg["temp_secret"] = self.code
            self.cfg["temp_exp"] = self.exp
        else:
            self.cfg.pop("temp_secret", None)
            self.cfg.pop("temp_exp", None)
        from macapp.config_store import save_config
        save_config(self.cfg)

    def _pick_exp(self, s: int) -> None:
        self.exp = int(time.time()) + s
        self._refresh_on()
        self._sync_keep()
        self._push()

    def _refresh_on(self) -> None:
        if self.exp_chips:
            for s, b in self.exp_chips.items():
                b.setChecked(abs(self.exp - (int(time.time()) + s)) < 2)
        self.code_label.setText(self.code)

    def _regen(self) -> None:
        self.code = sec.gen_temp_secret()
        self.exp = int(time.time()) + self._chosen_exp()
        self._refresh_on()
        self._sync_keep()
        self._push()
        self.status_text.setText("已重新生成临时秘密")

    def _chosen_exp(self) -> int:
        for s, b in self.exp_chips.items():
            if b.isChecked():
                return s
        return DEFAULT_EXP

    def _copy(self) -> None:
        if _try_clip(self.code):
            self.status_text.setText("✓ 已复制临时秘密")
        else:
            QMessageBox.information(self, "临时秘密", self.code)

    def _persist(self) -> None:
        self._sync_keep()

    def _tick(self) -> None:
        left = int(self.exp) - int(time.time())
        if left <= 0:
            self._regen()
            return
        self.countdown.setText(f"{fmt_hms(left)}")
        # 进度条
        total = self._chosen_exp()
        pct = max(0.0, min(1.0, left / total))
        self.exp_fill.setFixedWidth(int(420 * pct))

    # ---------- 永久秘密 ----------

    def _load_perm_ui(self) -> None:
        perm = sec.load_perm_secret()
        self.perm = perm
        self._render_perm()

    def _render_perm(self) -> None:
        if self.perm:
            self.perm_pill.setText("已启用")
            self.perm_pill.setProperty("ok", True)
            self.perm_shown.setText("•" * 12)
            self.btn_perm.setText("修改")
            self.btn_perm_del.setEnabled(True)
        else:
            self.perm_pill.setText("已停用")
            self.perm_pill.setProperty("ok", False)
            self.perm_shown.setText("未设置")
            self.btn_perm.setText("设置")
            self.btn_perm_del.setEnabled(False)
        self._polish(self.perm_pill)
        registry_push = getattr(self, "client", None)
        if registry_push is not None:
            registry_push.update_perm(sec.hash_of(self.perm) if self.perm else "")

    def _perm_dialog(self) -> None:
        from PySide6.QtWidgets import QInputDialog
        mode, ok_ = _two_choice(self, "永久秘密", "生成随机 16 位，或自设 ≥12 位？", ["生成本机随机", "手动输入"])
        if not ok_ or mode == "":
            return
        if mode == "生成本机随机":
            sec_input = sec.gen_perm_secret()
            _try_clip(sec_input)
            QMessageBox.information(self, "永久秘密", f"{sec_input}\n已复制到剪贴板，请提示手机输入。")
        else:
            sec_input, ok2 = QInputDialog.getText(self, "永久秘密", "输入（≥12 位，可含数字/字母/符号）")
            if not ok2:
                return
        if not sec.is_valid_perm(sec_input):
            QMessageBox.warning(self, "永久秘密", "长度不足 12 位，请重试。")
            return
        self.perm = sec_input
        in_keychain = sec.save_perm_secret(sec_input)
        self.cfg["perm_in_keychain"] = in_keychain
        from macapp.config_store import save_config
        save_config(self.cfg)
        self._render_perm()
        self._push_reg()

    def _perm_disable(self) -> None:
        self.perm = None
        sec.delete_perm_secret()
        self._render_perm()
        self._push_reg()

    def _push_reg(self) -> None:
        if self.client is not None:
            self.client.update_perm(sec.hash_of(self.perm) if self.perm else "")

    # ---------- 连接 ----------

    def _connect_if_ready(self) -> None:
        """配置齐（服务器/指纹/regkey）时自动连接；缺配置则提示待填。"""
        server = self.cfg.get("server", "").strip()
        fp = (self.cfg.get("fingerprint", "") or "").strip().lower().replace(":", "").replace(" ", "")
        regkey = self.cfg.get("regkey", "").strip()
        if server and len(fp) == 64 and regkey:
            self._connect()
        else:
            self.status_text.setText("待配置：服务器 / 指纹 / 注册密钥（底部填写后点连接）")

    def _connect(self) -> None:
        server = self.ed_server.text().strip()
        fp = self.ed_fp.text().strip().lower().replace(":", "").replace(" ", "")
        regkey = self.ed_key.text().strip()
        if not server or len(fp) != 64 or not regkey:
            QMessageBox.warning(self, "连接设置", "请先填写 服务器 / 指纹（64 hex）/ 注册密钥。\n"
                                              "三者见 relay 启动横幅与 regkey.txt。")
            return
        self.cfg.update(server=server, fingerprint=fp, regkey=regkey, name=self.ed_name.text().strip() or _hostname())
        from macapp.config_store import save_config
        save_config(self.cfg)
        self.client = core_mod.RelayClient(
            server, fp, regkey, self.cfg["name"],
            on_state=self._on_state, on_event=self._on_event,
            on_bridge_stats=self._on_stats,
        )
        self._queue.clear()
        self.client.start()
        self.btn_conn.setEnabled(False)
        self.btn_dis.setEnabled(True)
        self._push_reg()

    def _disconnect(self) -> None:
        if self.client:
            self.client.stop()
            self.client = None
        self._on_state(core_mod.ST_STOPPED, "")
        self.btn_conn.setEnabled(True)
        self.btn_dis.setEnabled(False)

    # ----- core 回调（连接线程）→ 队列 → UI 主线程 -----

    def _on_state(self, state: str, detail: str) -> None:
        self._queue.append(("state", state, detail))

    def _on_event(self, ev: dict) -> None:
        self._queue.append(("event", ev))

    def _on_stats(self, dur: float, total_bytes: int) -> None:
        self._queue.append(("stats", dur, total_bytes))

    def _drain_events(self) -> None:
        while self._queue:
            item = self._queue.pop(0)
            if item[0] == "state":
                self._apply_state(item[1], item[2])
            elif item[0] == "event":
                self._apply_event(item[1])
            elif item[0] == "stats":
                self._apply_stats(item[1])

    def _apply_state(self, state: str, detail: str) -> None:
        map_ = {
            core_mod.ST_CONNECTING: ("amber", "连接中…"),
            core_mod.ST_REGISTERED: ("green", "已注册 · 等待手机连入"),
            core_mod.ST_BRIDGED: ("red", "对讲在线 · 正在桥接"),
            core_mod.ST_RECONNECTING: ("amber", f"重连中（{detail}）"),
            core_mod.ST_FATAL: ("red", f"致命错误（{detail or ''}）"),
            core_mod.ST_STOPPED: ("gray", "未连接"),
        }
        color, text = map_[state]
        self.led.setProperty("led", color)
        self.status_text.setText(text)
        self._polish(self.led)

    def _apply_event(self, ev: dict) -> None:
        kind = KIND_TEMP if ev.get("kind") == "temp" else KIND_PERM
        ip = ev.get("ip", "") or ""
        if ev.get("event") == "auth-ok":
            self.pending_bridge = {
                "ts": time.time(), "kind": kind, "ip": ip,
                "device": self.cfg.get("name", _hostname()),
            }
        elif ev.get("event") == "auth-fail":
            self.history.add(Entry(
                ts=time.time(), device=_hostname(), kind=kind, ip=ip,
                dur_s=0, bytes=0, result="✗ 拒绝",
            ))

    def _apply_stats(self, dur: float, total_bytes: int) -> None:
        if self.pending_bridge:
            self.history.add(Entry(
                ts=self.pending_bridge["ts"],
                device=self.pending_bridge["device"],
                kind=self.pending_bridge["kind"],
                ip=self.pending_bridge["ip"],
                dur_s=dur, bytes=total_bytes, result="✓ 已桥接",
            ))
            self.pending_bridge = None

    def _open_history(self) -> None:
        if self.hist_win is None:
            self.hist_win = HistoryWindow(self.history)
        self.hist_win.show()
        self.hist_win.raise_()

    def _polish(self, w) -> None:
        w.style().unpolish(w)
        w.style().polish(w)


def fmt_hms(s: int) -> str:
    s = max(0, s)
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def _hostname() -> str:
    import socket as _s
    return _s.gethostname().split(".")[0]


def _try_clip(text: str) -> bool:
    try:
        from PySide6.QtGui import QGuiApplication
        QGuiApplication.clipboard().setText(text)
        return True
    except Exception:  # noqa: BLE001
        return False


def _two_choice(parent, title: str, prompt: str, options: list) -> tuple:
    from PySide6.QtWidgets import QDialog, QDialogButtonBox, QLabel, QVBoxLayout, QComboBox
    dlg = QDialog(parent)
    dlg.setWindowTitle(title)
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel(prompt))
    cb = QComboBox()
    cb.addItems(options)
    lay.addWidget(cb)
    bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
    lay.addWidget(bb)
    bb.accepted.connect(dlg.accept)
    bb.rejected.connect(dlg.reject)
    ok = dlg.exec() == QDialog.Accepted
    return (cb.currentText(), ok) if ok else ("", False)
