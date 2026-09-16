#!/usr/bin/env python3
"""连接历史窗口：表格 + 过滤（全部/今日/被拒）+ 清空。"""

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QPushButton,
                               QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget)

from macapp.history import History

COLS = ["时间", "来源", "类型", "IP", "时长", "流量", "结果"]
WIDTHS = [86, 110, 46, 110, 64, 64, 64]


class HistoryWindow(QWidget):
    def __init__(self, history: History):
        super().__init__()
        self._h = history
        self.setWindowTitle("连接历史 · 谁连过我")
        self.resize(560, 420)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(10)

        chips = QHBoxLayout()
        self.chip_btns = {}
        for key, text in (("all", "全部"), ("today", "今日"), ("denied", "被拒绝"), ("ok", "成功")):
            b = QPushButton(text)
            b.setCheckable(True)
            b.setFixedHeight(26)
            chips.addWidget(b)
            self.chip_btns[key] = b
        chips.addStretch()
        btn_clear = QPushButton("清空记录")
        btn_clear.setObjectName("danger")
        chips.addWidget(btn_clear)
        lay.addLayout(chips)
        self.chip_btns["all"].setChecked(True)

        self.table = QTableWidget(0, len(COLS))
        self.table.setHorizontalHeaderLabels(COLS)
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setSelectionMode(QTableWidget.NoSelection)
        self.table.setFocusPolicy(Qt.NoFocus)
        self.table.verticalHeader().setDefaultSectionSize(30)
        for i, w in enumerate(WIDTHS):
            self.table.setColumnWidth(i, w)
        self.table.horizontalHeader().setStretchLastSection(False)
        lay.addWidget(self.table)

        foot = QLabel("仅元数据 · 无任何音频内容 · 上限 200 条")
        foot.setObjectName("muted")
        lay.addWidget(foot)

        for key, b in self.chip_btns.items():
            b.clicked.connect(lambda _=False, k=key: self._apply(k))
        btn_clear.clicked.connect(self._clear)
        self._apply("all")

    def _apply(self, key: str) -> None:
        for k, b in self.chip_btns.items():
            b.setChecked(k == key)
        entries = self._h.load()
        rows = []
        for e in entries:
            ok = key == "all" or (
                (key == "today" and e.ts >= _day_start()) or
                (key == "denied" and e.result.startswith("✗")) or
                (key == "ok" and e.result.startswith("✓"))
            )
            if ok:
                rows.append(e)
        self._show(rows)

    def _show(self, entries) -> None:
        self.table.setRowCount(0)
        for e in entries:
            r = self.table.rowCount()
            self.table.insertRow(r)
            for c, txt in enumerate(e.to_row()):
                item = QTableWidgetItem(txt)
                if c == len(COLS) - 1:
                    item.setForeground(QColor("#3fb950" if txt.startswith("✓") else "#f85149"))
                self.table.setItem(r, c, item)

    def _clear(self) -> None:
        self._h.clear()
        self._apply("all")
        self._current_chip()

    def _current_chip(self) -> None:
        for key, b in self.chip_btns.items():
            if b.isChecked():
                self._apply(key)
                return


def _day_start() -> float:
    import time
    lt = time.localtime()
    midnight = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1))
    return midnight
