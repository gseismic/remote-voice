#!/usr/bin/env python3
"""连接历史：本地 JSON 存储（上限 200 条，可清空，仅元数据）。

条目字段: ts(epoch) / device / kind(临时|永久) / ip / dur_s / bytes / result(✓已桥接|✗拒绝)
数据源：RelayClient 的事件回调（auth-ok/auth-fail）+ 本地桥接统计。
"""

import json
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path

MAX_ENTRIES = 200
STATE_PATH = Path.home() / ".config" / "remote-voice" / "history.json"


@dataclass
class Entry:
    ts: float
    device: str
    kind: str      # 临时 / 永久
    ip: str
    dur_s: float
    bytes: int
    result: str    # ✓ 已桥接 / ✗ 拒绝

    def to_row(self) -> list:
        return [
            time.strftime("%m-%d %H:%M", time.localtime(self.ts)),
            self.device,
            self.kind,
            self.ip,
            _fmt_dur(self.dur_s),
            _fmt_bytes(self.bytes),
            self.result,
        ]


def _fmt_dur(s: float) -> str:
    if s <= 0:
        return "—"
    m, r = divmod(int(s), 60)
    return f"{m}分{r:02d}秒"


def _fmt_bytes(n: int) -> str:
    if n <= 0:
        return "—"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / 1024 / 1024:.1f} MB"


class History:
    def __init__(self, path: Path = STATE_PATH):
        self.path = path
        self._lock = threading.Lock()

    def load(self) -> list:
        with self._lock:
            return self._load_unlocked()

    def _load_unlocked(self) -> list:
        try:
            data = json.loads(self.path.read_text())
        except (OSError, json.JSONDecodeError):
            return []
        return [Entry(**e) for e in data if isinstance(e, dict)]

    def add(self, entry: Entry) -> None:
        with self._lock:
            items = self._load_unlocked()
            items.insert(0, entry)
            del items[MAX_ENTRIES:]
            self._save(items)

    def clear(self) -> None:
        with self._lock:
            self._save([])

    def _save(self, items: list) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps([asdict(e) for e in items], ensure_ascii=False))
        except OSError:
            pass
