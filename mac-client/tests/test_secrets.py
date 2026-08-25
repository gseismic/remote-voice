#!/usr/bin/env python3
"""secrets 模块纯逻辑测试。"""

import secretsgen as sec


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
