#!/usr/bin/env python3
"""remote-voice Mac 图形客户端入口（协议 v3）。

运行: remote-voice-gui （安装后任意目录；或 python3 -m macapp.main_gui）
配置: ~/.config/remote-voice/config.json
"""

import sys

from PySide6.QtWidgets import QApplication

from macapp.config_store import load_config
from macapp.history import History
from macapp.ui_main import MainWindow

QSS = """
* { font-family: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif; }
QWidget { background: #0e1116; color: #e6e9ee; font-size: 13px; }
QMainWindow, QDialog { background: #0e1116; }

QLabel { background: transparent; }
QLabel#muted { color: #8b949e; font-size: 11.5px; }
QLabel#bigcode {
    color: #f5b83d; font-family: "JetBrains Mono", "IBM Plex Mono", Consolas, monospace;
    font-size: 32px; letter-spacing: 3px; padding: 12px 0;
    background: #141008; border: 1px dashed #4a3b1c; border-radius: 8px;
}
QLabel#mono { font-family: "JetBrains Mono", Consolas, monospace; color: #8b949e; font-size: 12px; }
QLabel#pill { font-size: 10.5px; padding: 3px 8px; border-radius: 3px; border: 1px solid #2a313c; color: #8b949e; }
QLabel#pill[ok="true"] { color: #3fb950; border-color: #1d4429; background: #12261a; }
QLabel#masked { font-family: "JetBrains Mono", Consolas, monospace; font-size: 17px; letter-spacing: 3px; }

QFrame#card { background: #1a212b; border: 1px solid #2a313c; border-radius: 10px; }
QFrame#bar { background: #232a35; border-radius: 2px; }
QFrame#barfill { background: #f5b83d; border-radius: 2px; min-width: 0px; }

QLabel#led { font-size: 13px; }
QLabel#led[led="green"] { color: #3fb950; }
QLabel#led[led="red"] { color: #f85149; }
QLabel#led[led="amber"] { color: #f5b83d; }
QLabel#led[led="gray"] { color: #3d444d; }

QPushButton {
    background: #212936; border: 1px solid #2a313c; border-radius: 7px;
    padding: 7px 12px; color: #e6e9ee;
}
QPushButton:hover { border-color: #3d4654; }
QPushButton#primary { background: #f5b83d; border-color: #f5b83d; color: #14100a; font-weight: bold; }
QPushButton#primary:disabled { background: #6f5a2a; border-color: #6f5a2a; }
QPushButton#danger { color: #f85149; }
QPushButton#danger:disabled { color: #5b3a39; }
QPushButton:checked { border-color: #f5b83d; color: #f5b83d; background: #211a0c; }

QLineEdit {
    background: #0c0f13; border: 1px solid #2a313c; border-radius: 7px;
    padding: 6px 9px; color: #e6e9ee; selection-background-color: #f5b83d;
}
QLineEdit:focus { border-color: #f5b83d; }
QCheckBox { color: #8b949e; }
QCheckBox::indicator { width: 15px; height: 15px; border: 1px solid #2a313c; border-radius: 4px; background: #0c0f13; }
QCheckBox::indicator:checked { background: #f5b83d; border-color: #f5b83d; }

QTableWidget { background: #141a23; border: 1px solid #2a313c; border-radius: 8px; gridline-color: #20262f; }
QTableWidget::item { padding: 2px 6px; }
QHeaderView::section { background: #11151b; color: #8b949e; border: none; border-bottom: 1px solid #2a313c; padding: 6px; font-size: 11px; }

QMessageBox, QInputDialog, QDialog { background: #161b22; }
QComboBox { background: #212936; border: 1px solid #2a313c; border-radius: 7px; padding: 6px 9px; }
QComboBox QAbstractItemView { background: #1a212b; border: 1px solid #2a313c; }
QScrollBar:vertical { background: transparent; width: 8px; }
QScrollBar::handle:vertical { background: #2a313c; border-radius: 4px; }
"""


def main() -> None:
    cfg = load_config()
    history = History()

    qapp = QApplication(sys.argv)
    qapp.setStyleSheet(QSS)
    win = MainWindow(cfg, history)
    win.show()
    sys.exit(qapp.exec())


if __name__ == "__main__":
    main()
