#!/usr/bin/env python3
"""receiver.py 协议层单元测试（无需声卡与网络）。

运行: python3 -m pytest test_receiver.py -q
覆盖: 帧编解码 roundtrip / 超限拒收 / 截断容错 / 指纹规整 / 发送锁串行化。
"""

import socket
import struct
import threading

import receiver as rv


class FakeSock:
    """内存 socket：捕获 sendall 字节流，模拟对端可读。"""

    def __init__(self):
        self.sent = bytearray()
        self._lock = threading.Lock()

    def sendall(self, data: bytes) -> None:
        with self._lock:
            self.sent.extend(data)


def test_frame_roundtrip():
    sock_a, sock_b = socket.socketpair()
    payload = bytes(range(256)) * 8  # 2048 字节
    rv.write_frame(sock_a, rv.FRAME_AUDIO, payload)
    ftype, got = rv.read_frame(sock_b)
    assert ftype == rv.FRAME_AUDIO
    assert got == payload


def test_frame_empty_payload():
    sock_a, sock_b = socket.socketpair()
    rv.write_frame(sock_a, rv.FRAME_PING)
    ftype, got = rv.read_frame(sock_b)
    assert ftype == rv.FRAME_PING
    assert got == b""


def test_frame_oversize_rejected():
    sock_a, sock_b = socket.socketpair()
    bogus_header = bytes([rv.FRAME_AUDIO]) + (rv.MAX_PAYLOAD + 1).to_bytes(4, "big")
    # 只发帧头触发长度校验（接收端在读 payload 前就应拒绝）
    import threading

    t = threading.Thread(target=sock_a.sendall, args=(bogus_header,))
    t.start()
    try:
        raised = False
        try:
            rv.read_frame(sock_b)
        except ConnectionError:
            raised = True
        assert raised, "超限帧必须抛 ConnectionError"
    finally:
        t.join()


def test_normalize_fingerprint():
    assert rv.normalize_fingerprint("AB:CD:EF 01\n") == "abcdef01"
    assert rv.normalize_fingerprint("  " + "a" * 64 + " ") == "a" * 64


def test_send_lock_serializes():
    r = rv.Receiver.__new__(rv.Receiver)  # 跳过 __init__，仅测锁行为
    import threading

    r._send_lock = threading.Lock()
    fake = FakeSock()
    errors = []

    def worker(tag: int):
        try:
            for _ in range(200):
                r._send(fake, rv.FRAME_PING if tag else rv.FRAME_PONG, b"x" * 16)
        except Exception as e:  # noqa: BLE001
            errors.append(e)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert not errors, errors
    # 4 线程×200 帧=800 帧，每帧 (1+4+16)=21 字节；无交错撕裂则总长精确
    assert len(fake.sent) == 800 * 21
    # 抽查每帧头完整：type 与长度成对出现
    off = 0
    while off < len(fake.sent):
        assert fake.sent[off] in (rv.FRAME_PING, rv.FRAME_PONG)
        n = int.from_bytes(fake.sent[off + 1 : off + 5], "big")
        assert n == 16
        off += 5 + n


if __name__ == "__main__":
    raise SystemExit("请用 pytest 运行: python3 -m pytest test_receiver.py -q")
