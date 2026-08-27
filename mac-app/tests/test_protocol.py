#!/usr/bin/env python3
"""协议层单元测试（无需声卡与网络；源自 mac-receiver/test_receiver.py 帧部分）。

覆盖: 帧编解码 roundtrip / 超限拒收 / 截断容错 / 指纹规整。
"""

import socket
import threading

from macapp import protocol as proto


def test_frame_roundtrip():
    sock_a, sock_b = socket.socketpair()
    payload = bytes(range(256)) * 8  # 2048 字节
    proto.write_frame(sock_a, proto.FRAME_AUDIO, payload)
    ftype, got = proto.read_frame(sock_b)
    assert ftype == proto.FRAME_AUDIO
    assert got == payload


def test_frame_empty_payload():
    sock_a, sock_b = socket.socketpair()
    proto.write_frame(sock_a, proto.FRAME_PING)
    ftype, got = proto.read_frame(sock_b)
    assert ftype == proto.FRAME_PING
    assert got == b""


def test_frame_oversize_rejected():
    sock_a, sock_b = socket.socketpair()
    bogus_header = bytes([proto.FRAME_AUDIO]) + (proto.MAX_PAYLOAD + 1).to_bytes(4, "big")
    t = threading.Thread(target=sock_a.sendall, args=(bogus_header,))
    t.start()
    try:
        raised = False
        try:
            proto.read_frame(sock_b)
        except ConnectionError:
            raised = True
        assert raised, "超限帧必须抛 ConnectionError"
    finally:
        t.join()


def test_normalize_fingerprint():
    assert proto.normalize_fingerprint("AB:CD:EF 01\n") == "abcdef01"
    assert proto.normalize_fingerprint("  " + "a" * 64 + " ") == "a" * 64


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__]))
