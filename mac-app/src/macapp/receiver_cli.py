#!/usr/bin/env python3
"""remote-voice Mac 轻接收器（CLI，协议 v2 注册制）。

本机以 regkey 认证并注册（临时/永久）秘密，手机凭秘密连入；
接收 AUDIO 帧写入 BlackHole 输出端（作为 Mac 的虚拟麦克风）。

替代旧 mac-receiver/receiver.py：`remote-voice-recv` 命令等价
`python3 receiver.py`（参数与行为兼容）。

用法示例:
    remote-voice-recv --server server.example.com:9432 \
        --regkey "$RELAY_REGKEY" --fingerprint <64位hex> --device BlackHole

配置优先级: 命令行参数 > config.toml > 内置默认值。
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import tomllib

from macapp import core as core_mod
from macapp.audio import AudioSink, StatsSink, log
from macapp.secretsgen import gen_temp_secret, hash_of


def load_config(path: str) -> dict:
    try:
        with open(path, "rb") as f:
            return tomllib.load(f)
    except FileNotFoundError:
        return {}


class CliReceiver:
    """把 RelayClient 的事件映射为 CLI 行为（桥接→开声/离线→停声/事件→日志/致命→退出）。"""

    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.sink = StatsSink() if args.sink == "null" else AudioSink(args.device)
        self.temp_secret = args.temp_secret or ""
        self.perm_secret = args.perm_secret or ""
        self._fatal_msg: str | None = None
        self._was_bridged = False

        self.client = core_mod.RelayClient(
            args.server, args.fingerprint, args.regkey, args.name,
            sink=self.sink,
            on_state=self._on_state, on_event=self._on_event,
            on_bridge_stats=self._on_stats,
        )
        if self.perm_secret:
            self.client.update_perm(hash_of(self.perm_secret))

    def _on_state(self, state: str, detail: str) -> None:
        try:
            if state == core_mod.ST_BRIDGED:
                self._was_bridged = True
                self.sink.start()
                log("手机已上线，开始收音")
            elif state in (core_mod.ST_REGISTERED, core_mod.ST_STOPPED):
                self.sink.stop()
                if self._was_bridged:
                    log("手机已掉线，暂停收音（保持连接等待重连）")
                    self._was_bridged = False
            elif state == core_mod.ST_FATAL:
                self.sink.stop()
                self._fatal_msg = detail or "认证/指纹/协议失败"
            elif state == core_mod.ST_CONNECTING:
                log("连接中…")
            elif state == core_mod.ST_RECONNECTING:
                log(detail)
        except Exception as e:  # noqa: BLE001 - 音频设备异常不得杀死网络线程
            log(f"音频设备错误: {e}")

    def _on_event(self, ev: dict) -> None:
        # 认证结果事件（仅元数据）：记录后丢弃
        log(f"事件: {json.dumps(ev, ensure_ascii=False)[:120]}")

    def _on_stats(self, dur: float, total_bytes: int) -> None:
        log(f"本次桥接会话: {dur:.1f}s / {total_bytes / 1024:.1f} KB")

    def run(self) -> None:
        if not self.temp_secret:
            self.temp_secret = gen_temp_secret()
            log(f"临时秘密（手机端输入，复制到 App 添加设备）: {self.temp_secret}")
        else:
            log(f"临时秘密: {self.temp_secret}")
        self.client.update_temp(hash_of(self.temp_secret),
                                int(time.time()) + self.args.temp_expiry)
        if self.args.temp_expiry:
            log(f"临时秘密有效期: {self.args.temp_expiry} 秒")

        self.client.start()
        try:
            while True:
                # 阻塞读取：fatal 后 client.state 置 ST_FATAL，线程退出
                fatal = self.client.state == core_mod.ST_FATAL
                if fatal and self._fatal_msg is not None:
                    sys.exit(f"致命错误: {self._fatal_msg}")
                time.sleep(0.5)
        except KeyboardInterrupt:
            log("退出")
        finally:
            self.client.stop()


def main() -> None:
    cfg = load_config("config.toml")
    p = argparse.ArgumentParser(description="remote-voice Mac 接收器（协议 v2，CLI）")
    p.add_argument("--server", default=cfg.get("server"),
                   help="server 地址 host:port")
    p.add_argument("--regkey", default=cfg.get("regkey"),
                   help="Mac 注册密钥（建议用环境变量/文件注入）")
    p.add_argument("--name", default=cfg.get("name", socket.gethostname().split(".")[0]),
                   help="向手机展示的设备名（默认本机主机名）")
    p.add_argument("--temp-secret", default=cfg.get("temp_secret"),
                   help="临时秘密原文（留空则每次随机生成并打印）")
    p.add_argument("--perm-secret", default=cfg.get("perm_secret"),
                   help="永久秘密原文（可选，与临时秘密并存）")
    p.add_argument("--temp-expiry", type=int, default=cfg.get("temp_expiry", 8 * 3600),
                   help="临时秘密有效期（秒，默认 8 小时）")
    p.add_argument("--fingerprint", default=cfg.get("fingerprint"),
                   help="服务端证书 SHA-256 指纹（64 位 hex）")
    p.add_argument("--device", default=cfg.get("device", "BlackHole"),
                   help="输出设备名称子串匹配（默认 BlackHole）")
    p.add_argument("--sink", choices=["audio", "null"], default="audio",
                   help="null=无声卡验证模式，仅统计帧率")
    args = p.parse_args()

    for name in ("server", "regkey", "fingerprint"):
        if not getattr(args, name):
            sys.exit(f"错误：缺少 --{name}（或在 config.toml 中配置）")
    from macapp.protocol import normalize_fingerprint
    if len(normalize_fingerprint(args.fingerprint)) != 64:
        sys.exit("错误：--fingerprint 应为 64 位 SHA-256 hex（server 启动时会打印）")
    args.fingerprint = normalize_fingerprint(args.fingerprint)

    CliReceiver(args).run()


if __name__ == "__main__":
    main()
