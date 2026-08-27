#!/usr/bin/env python3
"""secrets 模块纯逻辑测试。"""

from macapp import secretsgen as sec
from macapp import config_store


def test_gen_temp_secret_format():
    s = sec.gen_temp_secret()
    assert len(s) == 9 and s[4] == "-"
    for c in s.replace("-", ""):
        assert c in sec.ALPHABET, f"非法字符 {c!r}"


def test_normalize_rules():
    assert sec.normalize("k7m-2qx9 ") == "K7M2QX9"
    assert sec.normalize(" abc-def ") == "ABCDEF"
    assert sec.normalize("a b-c") == "ABC"


def test_hash_stable():
    assert sec.hash_of("k7m-2qx9") == sec.hash_of("K7M2QX9")
    assert len(sec.hash_of("X")) == 64


def test_perm_validation():
    assert sec.is_valid_perm("ABCDEFGHIJKLMN")
    assert not sec.is_valid_perm("short")
    # 规范化后计长（去空格/连字符后整整 12 位）
    assert sec.is_valid_perm(" ab-cd-ef-gh-ij-kl ")
    assert not sec.is_valid_perm(" ab-cd-ef-gh- ")


def test_perm_roundtrip_file_only():
    """非 macOS 环境回落文件存储的往返测试（真实 Keychain 依赖 CLI，本环境预期走文件）。"""
    import tempfile
    from pathlib import Path

    old = sec.PERM_FILE
    old_dir = sec.CONFIG_DIR
    try:
        # mypy 兼容写法：直接换模块级路径
        sec.PERM_FILE = Path(tempfile.mkdtemp()) / "perm.secret"
        sec.CONFIG_DIR = sec.PERM_FILE.parent
        secret = "PERMTESTPERMTEST01"
        sec.save_perm_secret(secret)
        assert sec.perm_file_get() == secret
        assert sec.load_perm_secret() is not None
        sec.delete_perm_secret()
        assert sec.load_perm_secret() is None
    finally:
        sec.PERM_FILE = old
        sec.CONFIG_DIR = old_dir


def test_device_identity_generated_once_and_reloaded(monkeypatch, tmp_path):
    """测试目的：无 Keychain 时首次生成的设备身份必须落盘，第二次不能换 ID。"""
    monkeypatch.setattr(sec, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(sec, "DEVICE_FILE", tmp_path / "device.json")
    monkeypatch.setattr(sec, "_device_keychain_get", lambda: None)
    monkeypatch.setattr(sec, "_device_keychain_set", lambda identity: False)

    first = sec.load_or_create_device_identity()
    second = sec.load_or_create_device_identity()

    assert second == first
    assert len(first.device_key) == 64
    assert first.device_id.startswith("mac-")
    assert (tmp_path / "device.json").stat().st_mode & 0o777 == 0o600
    assert tmp_path.stat().st_mode & 0o777 == 0o700


def test_tofu_fingerprint_is_scoped_to_server():
    """测试目的：切换 server 时不能误用另一台服务器的 TOFU 指纹。"""
    fp = "ab" * 32
    cfg = {}
    config_store.remember_fingerprint(cfg, "Relay.Example", fp)

    assert config_store.trusted_fingerprint(cfg, "relay.example:9432") == fp
    assert config_store.trusted_fingerprint(cfg, "other.example:9432") == ""
