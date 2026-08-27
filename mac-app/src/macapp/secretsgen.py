#!/usr/bin/env python3
"""秘密管理：生成/规范化/哈希 + macOS Keychain 存储。

安全模型：
- relay 全程只收 SHA-256 hex（规范化后），本机内存亦不长期驻留明文；
- 永久秘密首选手 Keychain（`security` CLI，零第三方依赖），不可用时回落
  至 ~/.config/remote-voice/perm.secret（0600）；
- 临时秘密默认仅在内存（配置文件保持项除外）。
"""

import hashlib
import secrets as _secrets
import subprocess
from pathlib import Path

# 与高保真稿一致：剔除易混字符 0O1lI
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

PERM_MIN_LEN = 12

CONFIG_DIR = Path.home() / ".config" / "remote-voice"
PERM_FILE = CONFIG_DIR / "perm.secret"
KEYCHAIN_SERVICE = "remote-voice"
KEYCHAIN_ACCOUNT = "permanent-secret"


def gen_temp_secret() -> str:
    """生成 4+4 位 base32 短码（如 K7M-2QX9）。"""
    def part():
        return "".join(_secrets.choice(ALPHABET) for _ in range(4))
    return f"{part()}-{part()}"


def gen_perm_secret() -> str:
    """生成 16 位永久秘密（人工可输可复制）。"""
    return "".join(_secrets.choice(ALPHABET) for _ in range(16))


def normalize(raw: str) -> str:
    """规范化：去空格/连字符 + 大写。客户端进哈希前唯一入口。"""
    return "".join(c.upper() for c in raw if c not in " -")


def hash_of(raw: str) -> str:
    """规范化秘密的 SHA-256 hex（relay 注册表与手机认证均用此值）。"""
    return hashlib.sha256(normalize(raw).encode()).hexdigest()


def is_valid_perm(secret: str) -> bool:
    return len(normalize(secret)) >= PERM_MIN_LEN


# ---------- macOS Keychain（`security` CLI，无第三方依赖） ----------

def keychain_set(secret: str) -> None:
    """写入 Keychain（-U 覆盖）；非 macOS 或失败返回 False。"""
    try:
        r = subprocess.run(
            ["security", "add-generic-password", "-s", KEYCHAIN_SERVICE,
             "-a", KEYCHAIN_ACCOUNT, "-U", "-w", secret],
            capture_output=True, timeout=10,
        )
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def keychain_get() -> str | None:
    try:
        r = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE,
             "-a", KEYCHAIN_ACCOUNT, "-w"],
            capture_output=True, text=True, timeout=10,
        )
        if r.returncode == 0:
            return r.stdout.rstrip("\n")
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def perm_file_set(secret: str) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    PERM_FILE.write_text(secret)
    PERM_FILE.chmod(0o600)


def perm_file_get() -> str | None:
    try:
        if PERM_FILE.is_file():
            return PERM_FILE.read_text().strip()
    except OSError:
        pass
    return None


def load_perm_secret() -> str | None:
    return keychain_get() or perm_file_get()


def save_perm_secret(secret: str) -> bool:
    """返回是否落入了 Keychain（否则文件回落）。"""
    if keychain_set(secret):
        return True
    perm_file_set(secret)
    return False


def delete_perm_secret() -> None:
    try:
        subprocess.run(  # 无 Keychain 条目时非零返回，忽略
            ["security", "delete-generic-password", "-s", KEYCHAIN_SERVICE,
             "-a", KEYCHAIN_ACCOUNT],
            capture_output=True, timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        PERM_FILE.unlink(missing_ok=True)
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass


def regkey_path() -> Path:
    return CONFIG_DIR / "regkey.txt"
