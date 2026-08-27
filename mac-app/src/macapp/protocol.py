#!/usr/bin/env python3
"""remote-voice 线路协议（v3，与 server/internal/protocol 对齐）。

帧格式: [1B type][4B length BigEndian][payload(length 字节)]
本模块只做编解码与常量定义，不含会话语义。
另提供 FrameWriter（带锁串行写，心跳/读循环/测试共用）与指纹规整。
"""

import json
import socket
import struct
import threading

FRAME_AUTH = 0x01      # C→S 认证
FRAME_AUTH_OK = 0x02   # S→C 认证成功(JSON；手机侧含 mac 设备名)
FRAME_AUTH_ERR = 0x03  # S→C 认证失败，UTF-8 原因码，随后关闭连接
FRAME_AUDIO = 0x04     # 桥接后双向透传音频帧（relay 不解析内容）
FRAME_PING = 0x05
FRAME_PONG = 0x06
FRAME_PEER_STATE = 0x07  # S→C 对端状态: 0x01 上线 / 0x00 掉线
FRAME_REGISTER = 0x08    # Mac→S 注册/热更秘密
FRAME_EVENT = 0x09       # S→Mac 事件通知（认证成功/失败）

PEER_ONLINE = b"\x01"
PEER_OFFLINE = b"\x00"

LEGACY_PROTO_VERSION = 2
PROTO_VERSION = 3
ROLE_PHONE = "phone"
ROLE_MAC = "mac"

# AUTH_ERR 原因码（客户端按此分支）
REASON_INVALID_KEY = "invalid-key"
REASON_INVALID_DEVICE = "invalid-device"
REASON_DEVICE_BUSY = "device-busy"
REASON_DEVICE_STORE_UNAVAILABLE = "device-store-unavailable"
REASON_INVALID_SECRET = "invalid-secret"
REASON_SECRET_EXPIRED = "secret-expired"
REASON_PEER_BUSY = "peer-busy"
REASON_RATE_LIMITED = "rate-limited"
REASON_BAD_REQUEST = "bad-request"
REASON_UNSUPPORTED_VER = "unsupported-proto"

# EVENT 类型
EVENT_AUTH_OK = "auth-ok"
EVENT_AUTH_FAIL = "auth-fail"

SECRET_KIND_PERM = "perm"
SECRET_KIND_TEMP = "temp"

MAX_PAYLOAD = 65536
HEADER_LEN = 5


class AuthFatal(Exception):
    """认证被拒（reject reason 随 INFO 附上）；调用方应终止重连。"""


class FatalError(Exception):
    """不可恢复错误（指纹不符等）；调用方应终止重连。"""


def auth_payload(role: str, device_id: str = "", device_key: str = "",
                 secret_hex: str = "", key: str = "") -> bytes:
    """生成 AUTH payload；key 仅保留旧 v2 调试/兼容调用。"""
    body = {"role": role, "proto": PROTO_VERSION}
    if role == ROLE_MAC:
        if device_id or device_key:
            body["device_id"] = device_id
            body["device_key"] = device_key
        elif key:
            body["proto"] = LEGACY_PROTO_VERSION
            body["key"] = key
    else:
        body["secret"] = secret_hex
    return json.dumps(body, separators=(",", ":")).encode()


def register_payload(name: str, perm: str = "", temp: str = "", temp_exp: int = 0) -> bytes:
    return json.dumps({
        "name": name, "perm": perm, "temp": temp, "temp_exp": int(temp_exp),  # Go 侧 int64：杜绝浮点
    }, separators=(",", ":")).encode()


def parse_auth_ok(payload: bytes) -> dict:
    if not payload:
        return {}
    return json.loads(payload.decode())


def parse_event(payload: bytes) -> dict:
    return json.loads(payload.decode())


def write_frame(sock: socket.socket, ftype: int, payload: bytes = b"") -> None:
    """写一帧；头部与 payload 一次写入，调用方须持写锁防撕裂。"""
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("frame payload exceeds limit")
    sock.sendall(struct.pack(">BI", ftype, len(payload)) + payload)


def read_frame(sock) -> tuple:
    """读一帧。超限/截断视为恶意流量，抛 ConnectionError（上层断开）。"""
    header = _read_full(sock, HEADER_LEN)
    ftype, length = struct.unpack(">BI", header)
    if length > MAX_PAYLOAD:
        raise ConnectionError("frame payload exceeds limit")
    if length == 0:
        return ftype, b""
    payload = _read_full(sock, length)
    return ftype, payload


def _read_full(sock, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf.extend(chunk)
    return bytes(buf)


def normalize_fingerprint(s: str) -> str:
    """指纹规整：去冒号/空格后小写（兼容 SSL 与 banner 两种格式）。"""
    return s.replace(":", "").replace(" ", "").strip().lower()


class FrameWriter:
    """带锁的帧发送器：心跳线程与读循环可能并发发送（PING/PONG/REGISTER），
    sendall 的部分写不保证原子，锁内一次写出保证帧头与 payload 不被撕裂。
    供 RelayClient（core.py）、CLI 与并发测试共用。"""

    def __init__(self, sock):
        self._sock = sock
        self._lock = threading.Lock()

    def send(self, ftype: int, payload: bytes = b"") -> None:
        with self._lock:
            write_frame(self._sock, ftype, payload)
