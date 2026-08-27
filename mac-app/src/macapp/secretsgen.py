#!/usr/bin/env python3
"""秘密管理：生成/规范化/哈希 + macOS Keychain 存储。

安全模型：
- relay 全程只收 SHA-256 hex（规范化后），本机内存亦不长期驻留明文；
- 永久秘密首选手 Keychain（`security` CLI，零第三方依赖），不可用时回落
  至 ~/.config/remote-voice/perm.secret（0600）；
- Mac 设备身份首选 Keychain，不可用时回落至 ~/.config/remote-voice/device.json（0600）；
- 临时秘密默认仅在内存（配置文件保持项除外）。
"""

from dataclasses import dataclass
import hashlib
import json
import os
import secrets as _secrets
import subprocess
import tempfile
from pathlib import Path

# 与高保真稿一致：剔除易混字符 0O1lI
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

PERM_MIN_LEN = 12

CONFIG_DIR = Path.home() / ".config" / "remote-voice"
PERM_FILE = CONFIG_DIR / "perm.secret"
DEVICE_FILE = CONFIG_DIR / "device.json"
KEYCHAIN_SERVICE = "remote-voice"
KEYCHAIN_ACCOUNT = "permanent-secret"
DEVICE_KEYCHAIN_ACCOUNT = "device-identity"


class DeviceIdentityError(RuntimeError):
    """设备身份无法读取或持久化时返回给 GUI/CLI 的可恢复错误。"""


@dataclass(frozen=True)
class DeviceIdentity:
    """单台 Mac 的长期身份；device_key 只在本机与 server 认证时使用。"""

    device_id: str
    device_key: str


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

def _keychain_set(account: str, value: str) -> bool:
    """写入 Keychain（-U 覆盖）；非 macOS 或失败返回 False。"""
    try:
        r = subprocess.run(
            ["security", "add-generic-password", "-s", KEYCHAIN_SERVICE,
             "-a", account, "-U", "-w", value],
            capture_output=True, timeout=10,
        )
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _keychain_get(account: str) -> str | None:
    try:
        r = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE,
             "-a", account, "-w"],
            capture_output=True, text=True, timeout=10,
        )
        if r.returncode == 0:
            return r.stdout.rstrip("\n")
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def keychain_set(secret: str) -> bool:
    return _keychain_set(KEYCHAIN_ACCOUNT, secret)


def keychain_get() -> str | None:
    return _keychain_get(KEYCHAIN_ACCOUNT)


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


def _parse_device_identity(raw: str | None) -> DeviceIdentity | None:
    if not raw:
        return None
    try:
        value = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return None
    if not isinstance(value, dict):
        return None
    device_id = value.get("device_id")
    device_key = value.get("device_key")
    if not isinstance(device_id, str) or not isinstance(device_key, str):
        return None
    if not (8 <= len(device_id) <= 128 and all(
            c.isascii() and (c.isalnum() or c in "-_.:") for c in device_id)):
        return None
    if len(device_key) != 64 or any(c not in "0123456789abcdefABCDEF" for c in device_key):
        return None
    return DeviceIdentity(device_id, device_key.lower())


def _encode_device_identity(identity: DeviceIdentity) -> str:
    return json.dumps({
        "version": 1,
        "device_id": identity.device_id,
        "device_key": identity.device_key,
    }, separators=(",", ":"))


def _device_keychain_get() -> DeviceIdentity | None:
    return _parse_device_identity(_keychain_get(DEVICE_KEYCHAIN_ACCOUNT))


def _device_keychain_set(identity: DeviceIdentity) -> bool:
    return _keychain_set(DEVICE_KEYCHAIN_ACCOUNT, _encode_device_identity(identity))


def device_file_get() -> DeviceIdentity | None:
    try:
        identity = _parse_device_identity(DEVICE_FILE.read_text(encoding="utf-8"))
        if identity is None:
            return None
        # 兼容早期版本创建的宽权限文件；读取成功后立即收紧本机身份存储。
        CONFIG_DIR.chmod(0o700)
        DEVICE_FILE.chmod(0o600)
        return identity
    except OSError:
        return None


def device_file_set(identity: DeviceIdentity) -> None:
    """以 0600 权限原子写入设备身份，避免中断时留下半份凭据。"""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_DIR.chmod(0o700)
    fd, temp_name = tempfile.mkstemp(prefix=".device-", suffix=".tmp", dir=CONFIG_DIR)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(_encode_device_identity(identity))
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp_name, DEVICE_FILE)
        DEVICE_FILE.chmod(0o600)
    except OSError:
        try:
            os.close(fd)
        except OSError:
            pass
        try:
            Path(temp_name).unlink(missing_ok=True)
        except OSError:
            pass
        raise


def new_device_identity() -> DeviceIdentity:
    """生成不可预测的设备 ID 与 32 字节设备凭据。"""
    return DeviceIdentity(
        device_id=f"mac-{_secrets.token_hex(16)}",
        device_key=_secrets.token_hex(32),
    )


def load_or_create_device_identity() -> DeviceIdentity:
    """读取本机身份；没有可用身份时首次生成并持久化。"""
    identity = _device_keychain_get() or device_file_get()
    if identity is not None:
        return identity

    identity = new_device_identity()
    if _device_keychain_set(identity):
        return identity
    try:
        device_file_set(identity)
    except OSError as e:
        raise DeviceIdentityError(f"无法保存本机设备身份：{e}") from e
    return identity
