#!/usr/bin/env python3
"""回环集成：真实 relay（Go 二进制）↔ mac-client（Core）↔ 假手机端。

链路验证：
1. mac 首次设备自助入网 + REGISTER（临时秘密）
2. 假手机用同一秘密认证 → AUTH_OK 带设备名 → 桥接 PEER_STATE(online)
3. 手机→Mac AUDIO 帧透传（sink 收到）；Mac→手机 帧透传
4. 错误秘密 → invalid-secret；Mac 收到 EVENT auth-ok/auth-fail
5. 到期秘密 → secret-expired
6. 断开（Mac 死亡）→ 手机再认证失败 invalid-secret

前置：go 可用、relay 可编译（构建一次，缓存在 /tmp）。
"""

import hashlib
import re
import socket
import ssl
import subprocess
import threading
import time

import pytest

from macapp import protocol as proto
from macapp import secretsgen as sec  # noqa: F401 - 供测试直接使用
from macapp.audio import CollectSink
from macapp.core import RelayClient, ST_REGISTERED, ST_BRIDGED, ST_FATAL

OK = {"state": [], "events": [], "stats": []}


def wait_for(cond, timeout=5.0, poll=0.05, msg="timeout"):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return True
        time.sleep(poll)
    raise AssertionError(msg)


@pytest.fixture(scope="session")
def relay_proc(tmp_path_factory):
    """构建并启动不配置 regkey 的真实 server；产出 {addr, fp, proc, log}。"""
    repo = _find_repo()
    if not repo:
        pytest.skip("找不到仓库根（relay/ 目录）")
    build = tmp_path_factory.mktemp("build")
    binpath = build / "relay-test"
    r = subprocess.run(
        ["go", "build", "-o", str(binpath), "."],
        cwd=repo / "server", capture_output=True, text=True, timeout=120,
    )
    if r.returncode != 0:
        pytest.skip(f"server 构建失败: {r.stderr}")

    data = tmp_path_factory.mktemp("data")
    port = free_port()
    proc = subprocess.Popen(
        [str(binpath), "-addr", f"127.0.0.1:{port}", "-data", str(data)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        bufsize=1,
    )
    lines = []
    fp = None

    def pump():
        assert proc.stdout is not None
        for line in proc.stdout:
            lines.append(line)

    t = threading.Thread(target=pump, daemon=True)
    t.start()

    deadline = time.time() + 10
    while time.time() < deadline:
        blob = "".join(lines)
        m = re.search(r"([0-9a-f]{64})", blob)
        if m and "指纹" in blob:
            fp = m.group(1)
            break
        if proc.poll() is not None:
            break
        time.sleep(0.05)
    if fp is None:
        pytest.skip(f"取不到指纹: {''.join(lines)[-500:]}")
    yield {
        "addr": f"127.0.0.1:{port}",
        "fp": fp,
        "log": lambda: "".join(lines),
    }
    proc.terminate()
    proc.wait(timeout=5)


def _find_repo():
    import pathlib
    p = pathlib.Path(__file__).absolute()
    while p.parent != p:
        if (p / "server").is_dir():
            return p
        p = p.parent
    return None


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# ---------- 工具 ----------

def mac_client(relay_proc, name="MacBook-Pro", perm="", temp="", temp_exp=0, sink=None,
               device_id=None, device_key=None):
    identity = sec.new_device_identity()
    device_id = device_id or identity.device_id
    device_key = device_key or identity.device_key
    events = []
    states = []
    stats = []
    cli = RelayClient(
        relay_proc["addr"], relay_proc["fp"], device_id, device_key, name,
        sink=sink or CollectSink(),
        on_state=lambda s, d: states.append((s, d)),
        on_event=events.append,
        on_bridge_stats=lambda dur, b: stats.append((dur, b)),
    )
    if perm:
        cli.update_perm(sec.hash_of(perm))
    if temp:
        cli.update_temp(sec.hash_of(temp), temp_exp)
    cli.start()
    return cli, states, events, stats


def phone_auth(addr, fp, secret):
    """假手机：TLS pinning + 秘密认证，返回 (sock, auth_ok_json) 或 (None, 错误)。"""
    host, port = addr.split(":")
    raw = socket.create_connection((host, int(port)), timeout=5)
    raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    tls = ctx.wrap_socket(raw, server_hostname=host)
    der = tls.getpeercert(binary_form=True)
    assert hashlib.sha256(der).hexdigest() == fp, "指纹不符"
    proto.write_frame(tls, proto.FRAME_AUTH, proto.auth_payload(proto.ROLE_PHONE, secret_hex=sec.hash_of(secret)))
    ftype, payload = proto.read_frame(tls)
    if ftype == proto.FRAME_AUTH_ERR:
        return None, payload.decode()
    assert ftype == proto.FRAME_AUTH_OK
    ok = proto.parse_auth_ok(payload)
    # 认证成功的固定契约：随后必有一条 PEER_STATE(online)
    ftype, payload = proto.read_frame(tls)
    assert ftype == proto.FRAME_PEER_STATE and payload == proto.PEER_ONLINE
    return tls, ok


# ---------- 用例 ----------

def test_full_bridge_then_teardown(relay_proc):
    cli, states, events, stats = mac_client(
        relay_proc, temp="TEST-1234", temp_exp=int(time.time()) + 3600,
    )
    wait_for(lambda: cli.state == ST_REGISTERED, msg="mac 未达 registered")

    tls, ok = phone_auth(relay_proc["addr"], relay_proc["fp"], "TEST-1234")
    assert isinstance(ok, dict) and ok.get("mac") == "MacBook-Pro", ok
    wait_for(lambda: cli.state == ST_BRIDGED, msg="mac 未达 bridged")

    # 手机→Mac 音频帧透传
    frame = bytes(range(256)) * 8  # 2048 B
    proto.write_frame(tls, proto.FRAME_AUDIO, frame)
    sink = cli.sink
    wait_for(lambda: getattr(sink, "total_bytes", 0) >= len(frame), msg="sink 未收到帧")

    # Mac→手机 音频帧透传
    proto.write_frame(cli._conn, proto.FRAME_AUDIO, b"\x9f" * 32)
    ftype, got = proto.read_frame(tls)
    assert ftype == proto.FRAME_AUDIO and got == b"\x9f" * 32

    # EVENTS: mac 已收到 auth-ok（kind=temp）
    wait_for(lambda: any(e.get("event") == "auth-ok" and e.get("kind") == "temp" for e in events),
             msg="mac 未收 auth-ok 事件")

    # 手机断开 → mac 回 registered，stats 结算（duration>0）
    tls.close()
    wait_for(lambda: cli.state == ST_REGISTERED, msg="断开后 mac 未回 registered")
    wait_for(lambda: len(stats) == 1, msg="桥接统计未结算")

    cli.stop()


def test_two_macs_are_isolated(relay_proc):
    """测试目的：同一 server 上两台 Mac 同时在线时，秘密只路由到各自 Mac。"""
    mac_a, _states_a, _events_a, _stats_a = mac_client(
        relay_proc, name="Mac-A", temp="MAC-A-SECRET", temp_exp=int(time.time()) + 3600,
    )
    mac_b, _states_b, _events_b, _stats_b = mac_client(
        relay_proc, name="Mac-B", temp="MAC-B-SECRET", temp_exp=int(time.time()) + 3600,
    )
    try:
        wait_for(lambda: mac_a.state == ST_REGISTERED, msg="Mac-A 未达 registered")
        wait_for(lambda: mac_b.state == ST_REGISTERED, msg="Mac-B 未达 registered")

        phone_a, ok_a = phone_auth(relay_proc["addr"], relay_proc["fp"], "MAC-A-SECRET")
        phone_b, ok_b = phone_auth(relay_proc["addr"], relay_proc["fp"], "MAC-B-SECRET")
        assert ok_a.get("mac") == "Mac-A"
        assert ok_b.get("mac") == "Mac-B"
        try:
            wait_for(lambda: mac_a.state == ST_BRIDGED, msg="Mac-A 未达 bridged")
            wait_for(lambda: mac_b.state == ST_BRIDGED, msg="Mac-B 未达 bridged")
            frame_a = b"A" * 32
            frame_b = b"B" * 32
            proto.write_frame(phone_a, proto.FRAME_AUDIO, frame_a)
            proto.write_frame(phone_b, proto.FRAME_AUDIO, frame_b)
            wait_for(lambda: mac_a.sink.total_bytes >= len(frame_a), msg="Mac-A 未收到自己的音频")
            wait_for(lambda: mac_b.sink.total_bytes >= len(frame_b), msg="Mac-B 未收到自己的音频")
            assert mac_a.sink.chunks[-1] == frame_a
            assert mac_b.sink.chunks[-1] == frame_b
        finally:
            phone_a.close()
            phone_b.close()
    finally:
        mac_a.stop()
        mac_b.stop()


def test_wrong_secret_and_event(relay_proc):
    cli, states, events, stats = mac_client(
        relay_proc, temp="GOOD-0001", temp_exp=int(time.time()) + 3600,
    )
    wait_for(lambda: cli.state == ST_REGISTERED, msg="mac 未达 registered")

    _tls, err = phone_auth(relay_proc["addr"], relay_proc["fp"], "WRONG-XX")
    assert err == proto.REASON_INVALID_SECRET, err
    # 错误秘密无法路由，不会产生 auth-fail 事件（此为设计：仅过期类可路由）
    time.sleep(0.3)
    assert not any(e.get("reason") == proto.REASON_INVALID_SECRET for e in events)

    cli.stop()


def test_expired_secret_event(relay_proc):
    cli, states, events, stats = mac_client(
        relay_proc, temp="EXP-9001", temp_exp=int(time.time()) + 2,
    )
    wait_for(lambda: cli.state == ST_REGISTERED, msg="mac 未达 registered")
    time.sleep(2.0)  # 等过期
    _tls, err = phone_auth(relay_proc["addr"], relay_proc["fp"], "EXP-9001")
    assert err == proto.REASON_SECRET_EXPIRED, err
    wait_for(lambda: any(e.get("reason") == proto.REASON_SECRET_EXPIRED for e in events),
             msg="expired 失败事件未回投 Mac")
    cli.stop()


def test_auth_fatal_on_bad_device_key(relay_proc):
    ev = []
    device_id = "python-test-device"
    good = mac_client(
        relay_proc, name="Good", temp="GOOD-DEVICE", temp_exp=int(time.time()) + 3600,
        device_id=device_id, device_key="aa" * 32,
    )[0]
    wait_for(lambda: good.state == ST_REGISTERED, msg="good device 未达 registered")
    cli = RelayClient(
        relay_proc["addr"], relay_proc["fp"], device_id, "bb" * 32, "B",
        sink=CollectSink(), on_state=lambda s, d: ev.append((s, d)),
    )
    cli.start()
    wait_for(lambda: cli.state == ST_FATAL, msg="bad device key 应 fatal")
    assert any(s == ST_FATAL for s, _ in ev)
    cli.stop()
    good.stop()


def test_mac_disconnect_invalidates_secret(relay_proc):
    cli, states, events, stats = mac_client(
        relay_proc, temp="OFF-7007", temp_exp=int(time.time()) + 3600,
    )
    wait_for(lambda: cli.state == ST_REGISTERED, msg="mac 未达 registered")
    cli.stop()
    time.sleep(0.4)  # 等待 relay 清理注册表
    _tls, err = phone_auth(relay_proc["addr"], relay_proc["fp"], "OFF-7007")
    assert err == proto.REASON_INVALID_SECRET, err


def test_idle_keepalive(relay_proc):
    """回归(Review H-1)：注册后静默 15s 必须仍在线（心跳保活 + 读超时与 AUTH 分离）。"""
    cli, _states, _events, _stats = mac_client(
        relay_proc, temp="IDLE-0001", temp_exp=int(time.time()) + 3600,
    )
    wait_for(lambda: cli.state == ST_REGISTERED, msg="mac 未达 registered")
    time.sleep(15.0)
    assert cli.state == ST_REGISTERED, f"静默 15s 后状态应仍为 registered，got {cli.state}"
    cli.stop()


def test_fingerprint_mismatch_fatal(relay_proc):
    """回归(Review H-2)：指纹不符必须转为 ST_FATAL（此前 FatalError 逃逸导致线程死亡）。"""
    bad_fp = "ab" * 32 if relay_proc["fp"] != "ab" * 32 else "cd" * 32
    ev = []
    cli = RelayClient(
        relay_proc["addr"], bad_fp, sec.new_device_identity().device_id,
        sec.new_device_identity().device_key, "B",
        sink=CollectSink(), on_state=lambda s, d: ev.append((s, d)),
    )
    cli.start()
    wait_for(lambda: cli.state == ST_FATAL, msg="指纹不符应 fatal")
    assert any(s == ST_FATAL for s, _ in ev)
    cli.stop()


def test_tofu_auto_trust_and_reuse(relay_proc):
    """回归(PLAN-008)：指纹留空 → 首次连接自动记录并注册成功；回调返回真指纹。"""
    got_fps = []
    cli = RelayClient(
        relay_proc["addr"], "", sec.new_device_identity().device_id,
        sec.new_device_identity().device_key, "TofuMac",
        sink=CollectSink(), on_fingerprint=got_fps.append,
    )
    cli.start()
    wait_for(lambda: cli.state == ST_REGISTERED, msg="tofu 模式应注册成功")
    assert got_fps and got_fps[-1] == relay_proc["fp"], "TOFU 应记录到实际证书指纹"
    assert cli.fingerprint == relay_proc["fp"], "内部指纹应已固化"
    cli.stop()


def test_register_is_gated_until_auth():
    """测试目的：秘密热更不能在 Mac AUTH 成功前抢跑 REGISTER。"""
    class FakeWriter:
        def __init__(self):
            self.frames = []

        def send(self, ftype, payload=b""):
            self.frames.append((ftype, payload))

    writer = FakeWriter()
    identity = sec.new_device_identity()
    client = RelayClient(
        "127.0.0.1:9432", "", identity.device_id, identity.device_key, "GateMac",
        sink=CollectSink(),
    )
    client._writer = writer

    client.update_temp(sec.hash_of("TEMP-GATE"), int(time.time()) + 3600)
    assert writer.frames == []

    client._auth_ready.set()
    client.update_perm(sec.hash_of("PERM-GATE"))
    assert len(writer.frames) == 1
    assert writer.frames[0][0] == proto.FRAME_REGISTER
