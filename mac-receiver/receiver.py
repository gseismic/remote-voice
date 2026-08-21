#!/usr/bin/env python3
"""remote-voice Mac 接收器。

从 relay 中转服务器接收 PCM 音频帧（TLS + 证书指纹固定），写入 BlackHole
虚拟声卡的输出端；会议软件将 BlackHole 2ch 选为麦克风即可收音。

用法示例:
    python3 receiver.py --server relay.example.com:9432 \
        --token "$RELAY_TOKEN" --fingerprint <64位hex> --device BlackHole

配置优先级: 命令行参数 > config.toml > 内置默认值。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import socket
import ssl
import sys
import threading
import time
import tomllib

try:
    import numpy as np
    import sounddevice as sd
except ImportError:  # --sink null 模式允许在无声卡环境运行，此处仅提示
    np = None
    sd = None

# ---- 协议常量（必须与 relay/internal/protocol 保持一致）----
FRAME_AUTH = 0x01
FRAME_AUTH_OK = 0x02
FRAME_AUTH_ERR = 0x03
FRAME_AUDIO = 0x04
FRAME_PING = 0x05
FRAME_PONG = 0x06
FRAME_PEER_STATE = 0x07
PEER_ONLINE = 0x01
PEER_OFFLINE = 0x00
MAX_PAYLOAD = 65536

SAMPLE_RATE = 48000
CHANNELS = 1
FRAME_BYTES = 1920  # 48kHz / mono / s16le / 20ms

AUTH_TIMEOUT = 10.0
READ_TIMEOUT = 40.0
PING_INTERVAL = 10.0
BACKOFF_MAX = 30.0


def log(msg: str) -> None:
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True)


def recv_exact(sock: socket.socket, n: int) -> bytes:
    """循环读取直到凑满 n 字节；对端断开抛 ConnectionError。"""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("连接被对端关闭")
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock: socket.socket) -> tuple[int, bytes]:
    header = recv_exact(sock, 5)
    ftype = header[0]
    length = int.from_bytes(header[1:], "big")
    if length > MAX_PAYLOAD:
        raise ConnectionError(f"帧长超限: {length}")
    return ftype, recv_exact(sock, length)


def write_frame(sock: socket.socket, ftype: int, payload: bytes = b"") -> None:
    sock.sendall(bytes([ftype]) + len(payload).to_bytes(4, "big") + payload)


def normalize_fingerprint(s: str) -> str:
    return s.replace(":", "").replace(" ", "").strip().lower()


class NullSink:
    """无声卡环境的验证钩子：只统计帧数与速率，不发声。"""

    def __init__(self) -> None:
        self.frames = 0
        self._last_t = time.monotonic()
        self._last_frames = 0

    def start(self) -> None:
        log("null 输出模式：仅统计帧率，不发声")

    def feed(self, payload: bytes) -> None:
        self.frames += 1
        now = time.monotonic()
        if now - self._last_t >= 5.0:
            fps = (self.frames - self._last_frames) / (now - self._last_t)
            log(f"接收速率: {fps:.1f} 帧/秒 (累计 {self.frames})")
            self._last_t, self._last_frames = now, self.frames

    def stop(self) -> None:
        pass


class AudioSink:
    """把收到的 PCM 写入指定输出设备（BlackHole 输出端 = 环回输入端）。"""

    def __init__(self, device_substring: str) -> None:
        if sd is None or np is None:
            sys.exit("错误：未安装 sounddevice/numpy，请执行 pip install -r requirements.txt "
                     "或改用 --sink null")
        self.stream = sd.OutputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="int16",
            device=self._find_device(device_substring),
            blocksize=FRAME_BYTES // 2,
            latency="low",
        )

    @staticmethod
    def _find_device(substring: str) -> int:
        substring = substring.lower()
        for dev in sd.query_devices():
            if dev["max_output_channels"] > 0 and substring in dev["name"].lower():
                log(f"输出设备: {dev['name']} (id={dev['index']})")
                return dev["index"]
        sys.exit(
            f"错误：找不到名称包含 {substring!r} 的输出设备。\n"
            "当前可用输出设备:\n"
            + "\n".join(
                f"  [{d['index']}] {d['name']}"
                for d in sd.query_devices() if d["max_output_channels"] > 0
            )
            + "\n修复指引: brew install blackhole-2ch 后重试，"
              "或用 --device 指定其他设备名。"
        )

    def start(self) -> None:
        self.stream.start()

    def feed(self, payload: bytes) -> None:
        # 阻塞写：经 TCP 背压自然流控，缓冲恒定、延迟不累积（设计文档 §6.2）
        self.stream.write(np.frombuffer(payload, dtype=np.int16).reshape(-1, 1))

    def stop(self) -> None:
        self.stream.stop()
        self.stream.close()


class Receiver:
    def __init__(self, args: argparse.Namespace, sink: NullSink | AudioSink) -> None:
        self.host, _, self.port = args.server.rpartition(":")
        if not self.host or not self.port.isdigit():
            sys.exit(f"错误：--server 需为 host:port 格式，收到 {args.server!r}")
        self.port = int(self.port)
        self.token = args.token
        self.fingerprint = normalize_fingerprint(args.fingerprint)
        self.sink = sink
        self.peer_online = threading.Event()
        self._sock_lock = threading.Lock()
        self._sock: socket.socket | None = None

    def _connect_tls(self) -> ssl.SSLSocket:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # 信任根是证书指纹而非 CA：关闭系统校验，握手后手动比对指纹
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        raw = socket.create_connection((self.host, self.port), timeout=AUTH_TIMEOUT)
        sock = ctx.wrap_socket(raw)
        der = sock.getpeercert(binary_form=True)
        if not der:
            raise ConnectionError("未取得服务端证书，无法完成指纹校验")
        actual = hashlib.sha256(der).hexdigest()
        if actual != self.fingerprint:
            sock.close()
            raise PermissionError(
                f"证书指纹不符！\n  期望: {self.fingerprint}\n  实际: {actual}\n"
                "修复指引: 核对 relay 启动时打印的指纹，或服务器证书已更换需更新配置"
            )
        return sock

    def _attempt(self) -> None:
        sock = self._connect_tls()
        with self._sock_lock:
            self._sock = sock
        sock.settimeout(AUTH_TIMEOUT)
        auth = json.dumps(
            {"role": "mac", "token": self.token, "proto": 1}
        ).encode()
        write_frame(sock, FRAME_AUTH, auth)

        ftype, payload = read_frame(sock)
        if ftype == FRAME_AUTH_ERR:
            raise PermissionError(f"认证被拒: {payload.decode(errors='replace')}")
        if ftype != FRAME_AUTH_OK:
            raise ConnectionError(f"认证应答异常: type=0x{ftype:02x}")
        log("认证成功，等待手机上线…")

        sock.settimeout(READ_TIMEOUT)
        threading.Thread(target=self._heartbeat_loop, args=(sock,), daemon=True).start()
        while True:
            ftype, payload = read_frame(sock)
            if ftype == FRAME_AUDIO:
                if self.peer_online.is_set():
                    self.sink.feed(payload)
            elif ftype == FRAME_PEER_STATE:
                if payload and payload[0] == PEER_ONLINE:
                    log("手机已上线，开始收音")
                    self.sink.start()
                    self.peer_online.set()
                else:
                    log("手机已掉线，暂停收音（保持连接等待重连）")
                    self.peer_online.clear()
                    self.sink.stop()
            elif ftype == FRAME_PING:
                write_frame(sock, FRAME_PONG)
            # 其余类型丢弃（向前兼容）

    def _heartbeat_loop(self, sock: ssl.SSLSocket) -> None:
        while True:
            time.sleep(PING_INTERVAL)
            with self._sock_lock:
                if self._sock is not sock:
                    return  # 连接已被主循环更换/关闭
                try:
                    write_frame(sock, FRAME_PING)
                except OSError:
                    return

    def run(self) -> None:
        backoff = 1.0
        while True:
            try:
                self._attempt()
                backoff = 1.0  # 认证成功过才重置退避
            except PermissionError as e:
                sys.exit(f"致命错误: {e}")
            except (OSError, ConnectionError, ssl.SSLError) as e:
                log(f"连接中断: {e}")
            finally:
                self.peer_online.clear()
                self.sink.stop()
                with self._sock_lock:
                    if self._sock:
                        try:
                            self._sock.close()
                        except OSError:
                            pass
                        self._sock = None
            log(f"{backoff:.0f} 秒后重连…")
            time.sleep(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX)


def load_config(path: str) -> dict:
    try:
        with open(path, "rb") as f:
            return tomllib.load(f)
    except FileNotFoundError:
        return {}


def main() -> None:
    cfg = load_config("config.toml")
    p = argparse.ArgumentParser(description="remote-voice Mac 接收器")
    p.add_argument("--server", default=cfg.get("server"),
                   help="relay 地址 host:port")
    p.add_argument("--token", default=cfg.get("token"),
                   help="预共享 token（建议用环境变量传入）")
    p.add_argument("--fingerprint", default=cfg.get("fingerprint"),
                   help="服务端证书 SHA-256 指纹（64 位 hex）")
    p.add_argument("--device", default=cfg.get("device", "BlackHole"),
                   help="输出设备名称子串匹配（默认 BlackHole）")
    p.add_argument("--sink", choices=["audio", "null"], default="audio",
                   help="null=无声卡验证模式，仅统计帧率")
    args = p.parse_args()

    for name in ("server", "token", "fingerprint"):
        if not getattr(args, name):
            sys.exit(f"错误：缺少 --{name}（或在 config.toml 中配置）")
    if len(normalize_fingerprint(args.fingerprint)) != 64:
        sys.exit("错误：--fingerprint 应为 64 位 SHA-256 hex（relay 启动时会打印）")

    sink: NullSink | AudioSink = NullSink() if args.sink == "null" else AudioSink(args.device)
    Receiver(args, sink).run()


if __name__ == "__main__":
    main()
