#!/usr/bin/env python3
"""音频输出：对端 AUDIO 帧 → 无声卡丢弃 / BlackHole（sounddevice）输出。

sink 抽象: 具备 feed(payload: bytes) 的实例；GUI 用 SoundDeviceSink（懒建流），
CLI 用 AudioSink（显式启停）或 StatsSink（--sink null 验证模式）。
"""

import threading
import time

SAMPLE_RATE = 48000
CHANNELS = 1
FRAME_BYTES = 1920  # 20ms s16le mono


def log(msg: str) -> None:
    import sys
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True, file=sys.stderr)


class CollectSink:
    """收集收到的 payload（测试用）。"""

    def __init__(self):
        self._lock = threading.Lock()
        self._chunks: list[bytes] = []

    def feed(self, payload: bytes) -> None:
        with self._lock:
            self._chunks.append(payload)

    @property
    def packet_count(self) -> int:
        with self._lock:
            return len(self._chunks)

    @property
    def total_bytes(self) -> int:
        with self._lock:
            return sum(len(c) for c in self._chunks)

    @property
    def chunks(self) -> list[bytes]:
        """返回收到帧的快照，供桥接隔离测试检查路由结果。"""
        with self._lock:
            return list(self._chunks)

    def reset(self) -> None:
        with self._lock:
            self._chunks.clear()


class NullSink:
    """丢弃型 sink（无声卡环境兜底/测试）。"""

    def feed(self, payload: bytes) -> None:
        pass

    def start(self) -> None:
        pass

    def stop(self) -> None:
        pass


class StatsSink(NullSink):
    """无声卡验证输出（原 receiver --sink null）：只统计帧数与速率并每 5s 打一行日志。"""

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


class AudioSink:
    """CLI 用：把收到的 PCM 写入指定输出设备（BlackHole 输出端 = 环回输入端）。
    显式 start/stop 控制设备占用期间（桥接在线才开、离线即释放）。"""

    def __init__(self, device_substring: str) -> None:
        try:
            import numpy as np  # noqa: F401
            import sounddevice as sd
        except ImportError:
            import sys
            sys.exit("错误：未安装 sounddevice/numpy，请执行 pip install -e . "
                     "或改用 --sink null")
        self._sd = sd
        self._np = np
        self.stream = None
        self._device = self._find_device(device_substring)

    @staticmethod
    def _find_device(substring: str) -> int:
        import sounddevice as sd
        import sys
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
        import numpy as np
        import sounddevice as sd
        if self.stream is None:
            self.stream = sd.OutputStream(
                samplerate=SAMPLE_RATE,
                channels=CHANNELS,
                dtype="int16",
                device=self._device,
                blocksize=FRAME_BYTES // 2,
                latency="low",
            )
        self.stream.start()

    def feed(self, payload: bytes) -> None:
        if self.stream is None:
            return
        # 阻塞写：经 TCP 背压自然流控，缓冲恒定、延迟不累积（设计文档 §6.2）
        self.stream.write(self._np.frombuffer(payload, dtype=self._np.int16).reshape(-1, 1))

    def stop(self) -> None:
        if self.stream is not None:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:  # noqa: BLE001 - 设备被拔等场景，降级为忽略
                pass
            self.stream = None


class SoundDeviceSink(NullSink):
    """GUI 用：sounddevice 输出（BlackHole 虚拟麦克风）。首次 feed 时懒创建流表。"""

    def __init__(self, device_name: str = "BlackHole", latency: str = "low"):
        self._device_name = device_name
        self._latency = latency
        self._stream = None
        self._lock = threading.Lock()
        self._fail_reason: str | None = None

    @property
    def fail_reason(self) -> str | None:
        return self._fail_reason

    def open_stream(self) -> object | None:
        try:
            import sounddevice as sd
            match = None
            for dev in sd.query_devices():
                name = dev.get("name", "")
                if self._device_name.lower() in name.lower() and dev.get("max_output_channels", 0) > 0:
                    match = int(dev["index"])
                    break
            if match is None:
                self._fail_reason = f"未找到输出设备 {self._device_name!r}（brew install blackhole-2ch?）"
                return None
            return sd.OutputStream(
                samplerate=SAMPLE_RATE, channels=CHANNELS, dtype="int16",
                device=match, blocksize=960,  # 20ms 帧的采样数
                latency=self._latency,
            )
        except Exception as e:  # noqa: BLE001 - 任何初始化失败降级为记录
            self._fail_reason = str(e)
            return None

    def feed(self, payload: bytes) -> None:
        if self._stream is None:
            with self._lock:
                if self._stream is None:
                    self._stream = self.open_stream()
        if self._stream is None:
            return
        try:
            import numpy as np
            self._stream.write(np.frombuffer(payload, np.int16).reshape(-1, 1))
        except Exception:  # noqa: BLE001 - 写失败视流已死，重建
            self._stream = None

    def close(self) -> None:
        with self._lock:
            if self._stream is not None:
                try:
                    self._stream.close()
                except Exception:  # noqa: BLE001
                    pass
                self._stream = None
