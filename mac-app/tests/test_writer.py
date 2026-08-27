#!/usr/bin/env python3
"""FrameWriter 并发串行化测试（源自 mac-receiver/test_receiver.py 发送锁用例）。

心担线程 / 读循环 PONG / 热更 REGISTER 并发写同一 socket，
锁内一次写出保证帧头与 payload 不被交错撕裂。
"""

import threading

from macapp import protocol as proto


class FakeSock:
    """内存 socket：捕获 sendall 字节流。"""

    def __init__(self):
        self.sent = bytearray()
        self._lock = threading.Lock()
        self._recv_buf = bytearray()

    def sendall(self, data: bytes) -> None:
        with self._lock:
            self.sent.extend(data)


def test_writer_serializes_concurrent_frames():
    fake = FakeSock()
    w = proto.FrameWriter(fake)
    errors = []

    def worker(tag: int):
        try:
            for _ in range(200):
                w.send(proto.FRAME_PING if tag else proto.FRAME_PONG, b"x" * 16)
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
        assert fake.sent[off] in (proto.FRAME_PING, proto.FRAME_PONG)
        n = int.from_bytes(fake.sent[off + 1: off + 5], "big")
        assert n == 16
        off += 5 + n
