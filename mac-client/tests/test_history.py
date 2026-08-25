#!/usr/bin/env python3
"""history 模块纯逻辑测试（临时目录注入）。"""

import time
from pathlib import Path

from history import Entry, History, MAX_ENTRIES

now = time.time()


def _mk(tmp_path: Path) -> History:
    return History(tmp_path / "history.json")


def test_add_load_and_fields(tmp_path):
    h = _mk(tmp_path)
    h.add(Entry(ts=now, device="iPhone 15", kind="临时", ip="192.168.1.23",
                dur_s=734, bytes=34 * 1024 * 1024, result="✓ 已桥接"))
    items = h.load()
    assert len(items) == 1
    assert items[0].device == "iPhone 15"
    row = items[0].to_row()
    assert row[4] == "12分14秒" and row[5] == "34.0 MB"


def test_cap_200(tmp_path):
    h = _mk(tmp_path)
    for i in range(250):
        h.add(Entry(ts=now + i, device="P", kind="临时", ip="1.1.1.1",
                    dur_s=1, bytes=1, result="✓"))
    assert len(h.load()) == MAX_ENTRIES


def test_clear(tmp_path):
    h = _mk(tmp_path)
    for i in range(3):
        h.add(Entry(ts=now, device="P", kind="临时", ip="", dur_s=0, bytes=0, result="✗ 拒绝"))
    h.clear()
    assert h.load() == []


def test_load_bad_file(tmp_path):
    p = tmp_path / "history.json"
    p.write_text("{not json")
    assert History(p).load() == []
