#!/usr/bin/env python3
"""音频输出：对端 AUDIO 帧 → 无卡写 BlackHole（sounddevice）。

sink 抽象: 具备 feed(payload: bytes) 的实例，便于替换与测试。
"""

import threading

SAMPLE_RATE = 48000
CHANNELS = 1
FRAME_BYTES = 1920  # 20ms s16le mono


class NullSink:
    """丢弃型 sink（自测/无声卡环境）。"""

    def feed(self, payload: bytes) -> None:
        pass


class CollectSink:
    """收集收到的 payload（回环集成测试用）。"""

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

    def reset(self) -> None:
        with self._lock:
            self._chunks.clear()


class SoundDeviceSink(NullSink):
    """sounddevice 输出（BlackHole 虚拟麦克风）。首次 feed 时懒创建流表。"""

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
            import sounddevice as sd  # 惰性导入：无声卡环境不阻断启动
            if sd._check_input_settings(0) is None:  # noqa: SLF001 - 探测 API 可用性
                pass
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
