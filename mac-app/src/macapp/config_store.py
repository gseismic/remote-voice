#!/usr/bin/env python3
"""配置持久化：~/.config/remote-voice/config.json。

字段: server / fingerprint / regkey / name / keep / temp_secret / temp_exp / perm_in_keychain
注意：临时秘密仅在"重启后保持"勾选时落盘；注册密钥建议放 regkey.txt 而非 GUI 编辑器，
但 GUI 字段会写入本文件（0600 权限）。
"""

import json
import os
from pathlib import Path

CONFIG_PATH = Path.home() / ".config" / "remote-voice" / "config.json"
_REGPATH = Path.home() / ".config" / "remote-voice" / "regkey.txt"


def load_config() -> dict:
    try:
        data = json.loads(CONFIG_PATH.read_text())
        if isinstance(data, dict):
            return data
    except (OSError, json.JSONDecodeError):
        pass
    return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(cfg, ensure_ascii=False, indent=2))
        os.chmod(CONFIG_PATH, 0o600)  # 含临时秘密/regkey，限本人可读
    except OSError:
        pass


def regkey_hint() -> str:
    """GUI 编辑框空值时读出 regkey.txt 提示一句。"""
    try:
        if _REGPATH.is_file():
            return _REGPATH.read_text().strip()[:6] + "••••（见 regkey.txt）"
    except OSError:
        pass
    return ""
