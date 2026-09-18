use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use thiserror::Error;

const SAMPLE_RATE: u32 = 48_000;
const MAX_QUEUE_SAMPLES: usize = SAMPLE_RATE as usize;

#[derive(Debug, Error, Clone)]
pub enum AudioError {
    #[error("音频设备不可用: {0}")]
    Device(String),
    #[error("音频帧格式错误")]
    InvalidFrame,
    #[error("音频输出格式不支持")]
    UnsupportedFormat,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct SinkStats {
    pub frames: u64,
    pub bytes: u64,
    pub dropped_frames: u64,
}

/// 网络核心依赖的最小音频接口；实现必须线程安全，feed 不得无限制缓存输入。
pub trait AudioSink: Send + Sync {
    fn start(&self) -> Result<(), AudioError>;
    fn stop(&self);
    fn feed(&self, payload: &[u8]) -> Result<(), AudioError>;
    fn stats(&self) -> SinkStats;
}

#[derive(Default)]
struct QueueState {
    samples: VecDeque<i16>,
    frames: u64,
    bytes: u64,
    dropped_frames: u64,
}

/// cpal 输出实现。队列按单声道样本限制为约 1 秒，延迟不会无限增长。
pub struct CpalSink {
    device_name: String,
    queue: Arc<Mutex<QueueState>>,
    stream: Mutex<Option<Stream>>,
    last_error: Arc<Mutex<Option<String>>>,
}

impl CpalSink {
    pub fn new(device_name: impl Into<String>) -> Self {
        Self {
            device_name: device_name.into(),
            queue: Arc::new(Mutex::new(QueueState::default())),
            stream: Mutex::new(None),
            last_error: Arc::new(Mutex::new(None)),
        }
    }

    fn build_stream(&self) -> Result<Stream, AudioError> {
        let host = cpal::default_host();
        let device = host
            .output_devices()
            .map_err(|error| AudioError::Device(error.to_string()))?
            .find(|candidate| {
                candidate
                    .to_string()
                    .to_lowercase()
                    .contains(&self.device_name.to_lowercase())
            })
            .ok_or_else(|| AudioError::Device(format!("未找到输出设备 {}", self.device_name)))?;

        let config_range = device
            .supported_output_configs()
            .map_err(|error| AudioError::Device(error.to_string()))?
            .filter(|range| {
                range.channels() > 0
                    && range.channels() <= 2
                    && range.min_sample_rate() <= SAMPLE_RATE
                    && range.max_sample_rate() >= SAMPLE_RATE
                    && matches!(
                        range.sample_format(),
                        SampleFormat::I16 | SampleFormat::I32 | SampleFormat::F32
                    )
            })
            .max_by_key(|range| {
                let format_score = match range.sample_format() {
                    SampleFormat::F32 => 3,
                    SampleFormat::I16 => 2,
                    SampleFormat::I32 => 1,
                    _ => 0,
                };
                (format_score, range.channels())
            })
            .ok_or(AudioError::UnsupportedFormat)?;
        let sample_format = config_range.sample_format();
        let config = config_range.with_sample_rate(SAMPLE_RATE).config();
        let channels = config.channels as usize;
        let queue = Arc::clone(&self.queue);
        let error_slot = self.last_error.clone();
        let error_callback = move |error: cpal::Error| {
            if let Ok(mut slot) = error_slot.lock() {
                *slot = Some(error.to_string());
            }
        };

        // cpal 的回调类型由设备运行时决定，因此在这里把三种常见格式展开。
        let stream = match sample_format {
            SampleFormat::I16 => device
                .build_output_stream(
                    config,
                    move |data: &mut [i16], _| fill_i16(data, channels, &queue),
                    error_callback,
                    None,
                )
                .map_err(|error| AudioError::Device(error.to_string()))?,
            SampleFormat::I32 => {
                let queue = Arc::clone(&self.queue);
                let error_slot = self.last_error.clone();
                device
                    .build_output_stream(
                        config,
                        move |data: &mut [i32], _| fill_i32(data, channels, &queue),
                        move |error| {
                            if let Ok(mut slot) = error_slot.lock() {
                                *slot = Some(error.to_string());
                            }
                        },
                        None,
                    )
                    .map_err(|error| AudioError::Device(error.to_string()))?
            }
            SampleFormat::F32 => {
                let queue = Arc::clone(&self.queue);
                let error_slot = self.last_error.clone();
                device
                    .build_output_stream(
                        config,
                        move |data: &mut [f32], _| fill_f32(data, channels, &queue),
                        move |error| {
                            if let Ok(mut slot) = error_slot.lock() {
                                *slot = Some(error.to_string());
                            }
                        },
                        None,
                    )
                    .map_err(|error| AudioError::Device(error.to_string()))?
            }
            _ => return Err(AudioError::UnsupportedFormat),
        };
        Ok(stream)
    }

    /// 记录启动/播放失败原因，使后续 feed 返回同一消息（配合 relay 侧去重，避免刷屏）
    fn remember_error(&self, error: AudioError) -> AudioError {
        if let Ok(mut slot) = self.last_error.lock() {
            *slot = Some(error.to_string());
        }
        error
    }

    pub fn last_error(&self) -> Option<String> {
        self.last_error.lock().ok().and_then(|slot| slot.clone())
    }
}

impl AudioSink for CpalSink {
    fn start(&self) -> Result<(), AudioError> {
        let mut stream_slot = self
            .stream
            .lock()
            .map_err(|_| AudioError::Device("音频状态锁已损坏".to_string()))?;
        let had_error = self
            .last_error
            .lock()
            .map(|error| error.is_some())
            .unwrap_or(true);
        if stream_slot.is_some() && !had_error {
            return Ok(());
        }
        // 流存活但 last_error 粘滞（CoreAudio 瞬时错误）时必须重建，
        // 否则 feed 会一直命中 last_error，整个会话都无法恢复
        *stream_slot = None;
        if let Ok(mut error) = self.last_error.lock() {
            *error = None;
        }
        let stream = match self.build_stream() {
            Ok(stream) => stream,
            Err(error) => return Err(self.remember_error(error)),
        };
        if let Err(error) = stream.play() {
            return Err(self.remember_error(AudioError::Device(error.to_string())));
        }
        *stream_slot = Some(stream);
        Ok(())
    }

    fn stop(&self) {
        if let Ok(mut queue) = self.queue.lock() {
            queue.samples.clear();
        }
        if let Ok(mut stream) = self.stream.lock() {
            stream.take();
        }
    }

    fn feed(&self, payload: &[u8]) -> Result<(), AudioError> {
        if payload.len() % 2 != 0 {
            return Err(AudioError::InvalidFrame);
        }
        if let Some(error) = self.last_error() {
            return Err(AudioError::Device(error));
        }
        if self
            .stream
            .lock()
            .map_err(|_| AudioError::Device("音频状态锁已损坏".to_string()))?
            .is_none()
        {
            return Err(AudioError::Device("音频输出尚未启动".to_string()));
        }
        let mut queue = self
            .queue
            .lock()
            .map_err(|_| AudioError::Device("音频队列锁已损坏".to_string()))?;
        let incoming: Vec<i16> = payload
            .chunks_exact(2)
            .map(|bytes| i16::from_le_bytes([bytes[0], bytes[1]]))
            .collect();
        let frame_samples = incoming.len();
        let overflow = queue
            .samples
            .len()
            .saturating_add(frame_samples)
            .saturating_sub(MAX_QUEUE_SAMPLES);
        for _ in 0..overflow {
            queue.samples.pop_front();
        }
        if overflow > 0 {
            queue.dropped_frames = queue.dropped_frames.saturating_add(1);
        }
        queue.samples.extend(incoming);
        queue.frames = queue.frames.saturating_add(1);
        queue.bytes = queue.bytes.saturating_add(payload.len() as u64);
        Ok(())
    }

    fn stats(&self) -> SinkStats {
        self.queue
            .lock()
            .map(|queue| SinkStats {
                frames: queue.frames,
                bytes: queue.bytes,
                dropped_frames: queue.dropped_frames,
            })
            .unwrap_or_default()
    }
}

/// 测试和无音频设备环境使用的 sink；仍统计帧，确保桥接测试不依赖声卡。
#[cfg(test)]
#[derive(Default)]
pub struct NullSink {
    stats: Mutex<SinkStats>,
}

#[cfg(test)]
impl AudioSink for NullSink {
    fn start(&self) -> Result<(), AudioError> {
        Ok(())
    }

    fn stop(&self) {}

    fn feed(&self, payload: &[u8]) -> Result<(), AudioError> {
        if payload.len() % 2 != 0 {
            return Err(AudioError::InvalidFrame);
        }
        let mut stats = self
            .stats
            .lock()
            .map_err(|_| AudioError::Device("统计锁已损坏".to_string()))?;
        stats.frames += 1;
        stats.bytes += payload.len() as u64;
        Ok(())
    }

    fn stats(&self) -> SinkStats {
        self.stats.lock().map(|stats| *stats).unwrap_or_default()
    }
}

pub fn list_output_devices() -> Result<Vec<String>, AudioError> {
    cpal::default_host()
        .output_devices()
        .map_err(|error| AudioError::Device(error.to_string()))
        .map(|devices| devices.map(|device| device.to_string()).collect())
}

fn fill_i16(data: &mut [i16], channels: usize, queue: &Arc<Mutex<QueueState>>) {
    fill(data, channels, queue, |sample| sample);
}

fn fill_i32(data: &mut [i32], channels: usize, queue: &Arc<Mutex<QueueState>>) {
    fill(data, channels, queue, |sample| (sample as i32) << 16);
}

fn fill_f32(data: &mut [f32], channels: usize, queue: &Arc<Mutex<QueueState>>) {
    fill(data, channels, queue, |sample| sample as f32 / 32_768.0);
}

fn fill<T, F>(data: &mut [T], channels: usize, queue: &Arc<Mutex<QueueState>>, convert: F)
where
    T: Copy + Default,
    F: Fn(i16) -> T,
{
    let Ok(mut queue) = queue.lock() else {
        data.fill(T::default());
        return;
    };
    for frame in data.chunks_mut(channels.max(1)) {
        let sample = queue.samples.pop_front().unwrap_or(0);
        let converted = convert(sample);
        for channel in frame {
            *channel = converted;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_sink_counts_valid_frames_and_rejects_odd_payloads() {
        let sink = NullSink::default();
        sink.start().unwrap();
        sink.feed(&[0, 0, 1, 0]).unwrap();
        assert_eq!(sink.stats().frames, 1);
        assert!(matches!(sink.feed(&[1]), Err(AudioError::InvalidFrame)));
    }
}
