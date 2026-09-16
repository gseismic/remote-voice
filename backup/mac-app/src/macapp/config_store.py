#!/usr/bin/env python3
"""配置持久化：~/.config/remote-voice/config.json。

字段: server / fingerprints / name / keep / temp_secret / temp_exp / perm_in_keychain
注意：临时秘密仅在"重启后保持"勾选时落盘；旧版本的 regkey 字段只做兼容读取，不参与新认证。
"""

import json
import os
import tempfile
from pathlib import Path

CONFIG_PATH = Path.home() / ".config" / "remote-voice" / "config.json"


def load_config() -> dict:
    try:
        data = json.loads(CONFIG_PATH.read_text())
        if isinstance(data, dict):
            return data
    except (OSError, json.JSONDecodeError):
        pass
    return {}


def save_config(cfg: dict) -> None:
    """以 0600 权限原子写配置，避免秘密配置被截断或暴露。"""
    tmp_name = None
    try:
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(CONFIG_PATH.parent, 0o700)
        fd, tmp_name = tempfile.mkstemp(
            prefix=".config-", suffix=".tmp", dir=CONFIG_PATH.parent,
        )
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(json.dumps(cfg, ensure_ascii=False, indent=2))
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, CONFIG_PATH)
        tmp_name = None
    except OSError:
        if tmp_name:
            try:
                Path(tmp_name).unlink(missing_ok=True)
            except OSError:
                pass


def normalize_server(server: str) -> str:
    """生成 TOFU 索引；未写端口时补默认端口，避免同一 server 两份信任记录。"""
    value = server.strip().lower() if isinstance(server, str) else ""
    if value and ":" not in value:
        value += ":9432"
    return value


def _normalize_fingerprint(value: str) -> str:
    if not isinstance(value, str):
        return ""
    return value.replace(":", "").replace("-", "").replace(" ", "").strip().lower()


def _valid_fingerprint(value: str) -> bool:
    return len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def trusted_fingerprint(cfg: dict, server: str) -> str:
    """按 server 地址取内部 TOFU 记录；旧单值字段仅在地址相同时兼容。"""
    key = normalize_server(server)
    records = cfg.get("fingerprints")
    if isinstance(records, dict):
        value = _normalize_fingerprint(records.get(key, ""))
        if _valid_fingerprint(value):
            return value
    # PLAN-008 的旧格式只有一个 fingerprint，且只能安全迁移到旧 server 地址。
    legacy = _normalize_fingerprint(cfg.get("fingerprint", ""))
    if legacy and normalize_server(cfg.get("server", "")) == key and _valid_fingerprint(legacy):
        return legacy
    return ""


def remember_fingerprint(cfg: dict, server: str, fingerprint: str) -> None:
    """记录某个 server 的 TOFU 指纹；非法指纹不进入配置。"""
    value = _normalize_fingerprint(fingerprint)
    if not _valid_fingerprint(value):
        raise ValueError("fingerprint must be 64 hexadecimal characters")
    records = cfg.setdefault("fingerprints", {})
    if not isinstance(records, dict):
        records = {}
        cfg["fingerprints"] = records
    records[normalize_server(server)] = value
