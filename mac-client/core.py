#!/usr/bin/env python3
"""Mac 端会话引擎（无 Qt 依赖，可无头测试）。

RelayClient 职责:
- TLS 连接 + 证书指纹 pinning（与 receiver.py 同语义）
- AUTH(role=mac, regkey) → REGISTER(name + perm/temp 哈希) → 读循环
- AUDIO 帧 → sink（默认 BlackHole）；PING/PONG 心跳；PEER_STATE 桥接状态
- EVENT（auth-ok/auth-fail）→ 事件回调（上层落连接历史）
- 断线指数退避重连（1s→30s）；认证被拒/指纹不符 → 终止（不静默重试）
- 支持热更：update_temp()/clear_temp()/set_perm()/clear_perm() 随发随生效
"""

import hashlib
import json
import logging
import socket
import ssl
import threading
import time

import protocol as proto
from audio import SoundDeviceSink

log = logging.getLogger("mac-client.core")

# 状态枚举（UI/测试消费）
ST_STOPPED = "stopped"
ST_CONNECTING = "connecting"
ST_REGISTERED = "registered"     # 已连接且注册完成，等待对端
ST_BRIDGED = "bridged"           # 手机已连入，正在桥接
ST_RECONNECTING = "reconnecting"
ST_FATAL = "fatal"               # regkey 错/指纹不符/协议错误：停止重连

READ_TIMEOUT = 40.0
AUTH_TIMEOUT = 10.0
LINGER_RECORDS = 30  # 事件记录上限（供 UI 轨迹展示）


class RelayClient:
    """一个 Mac 身份的 relay 会话。start() 后以独立线程运行，回调线程=连接线程。"""

    def __init__(self, server: str, fingerprint: str, regkey: str,
                 device_name: str, sink=None,
                 on_state=None, on_event=None, on_bridge_stats=None):
        """
        server: host[:port]（默认端口 9432）
        fingerprint: 64 hex 服务端证书 SHA-256
        sink: 具备 feed(bytes) 的实例（默认 SoundDeviceSink）
        on_state(state, detail): 状态变更
        on_event(ev: dict): auth-ok / auth-fail 事件（含 ip/kind/reason）
        on_bridge_stats(dur_s, bytes): 一次桥接会话结束统计
        """
        self.server = server
        self.fingerprint = fingerprint.lower()
        self.regkey = regkey
        self.device_name = device_name
        self.sink = sink if sink is not None else SoundDeviceSink()
        self.on_state = on_state or (lambda s, d: None)
        self.on_event = on_event or (lambda ev: None)
        self.on_bridge_stats = on_bridge_stats or (lambda d, b: None)

        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._state = ST_STOPPED
        self._state_lock = threading.Lock()
        self._conn: socket.socket | None = None
        self._write_lock = threading.Lock()
        self._reg_lock = threading.Lock()      # 保护注册内容（GUI 热更与连接线程共用）
        self._reg = {"perm": "", "temp": "", "temp_exp": 0}

        # 桥接统计（连接线程写）
        self._bridge_start = 0.0
        self._bridge_bytes = 0

    # ---------- 生命周期 ----------

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="relay-client", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        conn = self._conn
        if conn is not None:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass
        if self._thread is not None:
            self._thread.join(timeout=3)

    @property
    def state(self) -> str:
        with self._state_lock:
            return self._state

    def _set_state(self, state: str, detail: str = "") -> None:
        with self._state_lock:
            if state == self._state:
                return
            self._state = state
        self.on_state(state, detail)
        log.info("state=%s detail=%s", state, detail)

    # ---------- 注册热更（GUI 线程调用） ----------

    def update_temp(self, secret_hex: str, exp_unix: int) -> None:
        with self._reg_lock:
            self._reg["temp"] = secret_hex
            self._reg["temp_exp"] = exp_unix
        self._send_register_if_ready()

    def update_perm(self, secret_hex: str) -> None:
        with self._reg_lock:
            self._reg["perm"] = secret_hex
        self._send_register_if_ready()

    def _snapshot_reg(self) -> dict:
        with self._reg_lock:
            return dict(self._reg)

    def _send_register_if_ready(self) -> None:
        conn = self._conn
        if conn is None:
            return
        reg = self._snapshot_reg()
        name = self.device_name
        payload = proto.register_payload(name, reg["perm"], reg["temp"], reg["temp_exp"])
        with self._write_lock:
            try:
                proto.write_frame(conn, proto.FRAME_REGISTER, payload)
            except OSError:
                pass

    # ---------- 主循环 ----------

    def _run(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            auth_error = self._attempt()
            if auth_error is not None:
                self._set_state(ST_FATAL, auth_error)
                return
            if self._stop.is_set():
                break
            self._set_state(ST_RECONNECTING, f"{backoff:.0f}s 后重连")
            if self._stop.wait(backoff):
                break
            backoff = min(backoff * 2, 30.0)

    def _attempt(self) -> str | None:
        """一次连接尝试。返回非空 = 认证级致命错误（不再重试）。"""
        self._set_state(ST_CONNECTING, self.server)
        host, port = _split_host_port(self.server, 9432)
        try:
            raw = socket.create_connection((host, port), timeout=10)
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            tls = ctx.wrap_socket(raw, server_hostname=host)
            self._verify_pinning(tls)
            self._conn = tls
        except (OSError, ssl.SSLError) as e:
            log.warning("connect failed: %s", e)
            return None

        # AUTH
        try:
            proto.write_frame(self._conn, proto.FRAME_AUTH,
                              proto.auth_payload(proto.ROLE_MAC, key=self.regkey))
            ftype, payload = self._read_frame_timeout(AUTH_TIMEOUT)
            if ftype == proto.FRAME_AUTH_ERR:
                reason = payload.decode("utf-8", "replace")
                log.warning("auth rejected: %s", reason)
                if reason in (proto.REASON_INVALID_KEY, proto.REASON_RATE_LIMITED):
                    return f"认证被拒（{reason}）"
                return None  # 其余（版本/格式）重试也无益，但保持回环可实现
            if ftype != proto.FRAME_AUTH_OK:
                return f"意外帧类型 0x{ftype:02x}"
        except (OSError, proto.AuthFatal) as e:
            log.warning("auth io error: %s", e)
            return None

        # REGISTER（注册当前秘密）
        self._send_register_if_ready()
        self._set_state(ST_REGISTERED, f"已注册为 {self.device_name}")
        self._read_loop()
        self._finish_bridge()
        self._conn = None
        return None

    def _verify_pinning(self, tls: ssl.SSLSocket) -> None:
        """证书级 pinning：对服务端叶子证书 DER 做 SHA-256（与 relay selfcert 同语义）。"""
        der = tls.getpeercert(binary_form=True)
        got = hashlib.sha256(der).hexdigest()
        if got != self.fingerprint:
            raise proto.FatalError("certificate fingerprint mismatch")

    def _read_frame_timeout(self, timeout: float):
        self._conn.settimeout(timeout)
        return proto.read_frame(self._conn)

    def _read_loop(self) -> None:
        """逐帧分发；返回后重连（指数退避在 _run）。"""
        while not self._stop.is_set():
            try:
                ftype, payload = proto.read_frame(self._conn)
            except (TimeoutError, OSError, ConnectionError):
                return
            if ftype == proto.FRAME_PING:
                with self._write_lock:
                    try:
                        proto.write_frame(self._conn, proto.FRAME_PONG)
                    except OSError:
                        pass
            elif ftype == proto.FRAME_PEER_STATE:
                if payload == proto.PEER_ONLINE:
                    if self._state != ST_BRIDGED:
                        self._bridge_start = time.time()
                        self._bridge_bytes = 0
                        self._set_state(ST_BRIDGED, "对端已上线")
                else:
                    self._end_bridge()
                    self._set_state(ST_REGISTERED, "对端已掉线，等待重连")
            elif ftype == proto.FRAME_AUDIO:
                self._bridge_bytes += len(payload)
                self.sink.feed(payload)  # 阻塞写：TCP 背压自然流控（< 见 receiver.py 注释）
            elif ftype == proto.FRAME_EVENT:
                try:
                    self.on_event(proto.parse_event(payload))
                except json.JSONDecodeError:
                    pass

    def _end_bridge(self) -> None:
        if self._bridge_start > 0:
            self.on_bridge_stats(time.time() - self._bridge_start, self._bridge_bytes)
            self._bridge_start = 0.0
            self._bridge_bytes = 0

    def _finish_bridge(self) -> None:
        self._end_bridge()


# ---------- 工具 ----------

def _split_host_port(server: str, default_port: int) -> tuple:
    if ":" in server:
        host, port = server.rsplit(":", 1)
        return host, int(port)
    return server, default_port
