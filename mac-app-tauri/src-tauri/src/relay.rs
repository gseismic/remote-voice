use crate::audio::{AudioError, AudioSink, SinkStats};
use crate::config;
use crate::protocol::{self, ProtocolError};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use sha2::{Digest, Sha256};
use std::fmt::Debug;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::Duration;
use thiserror::Error;
use tokio::io::{split, AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};
use tokio::time::{interval, sleep, timeout, Instant, MissedTickBehavior};
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

const AUTH_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const PING_INTERVAL: Duration = Duration::from_secs(10);
const MAX_BACKOFF: u64 = 30;
/// 音频输出失败后的自愈重试间隔：瞬时 CoreAudio 错误不应导致整场会话静音
const AUDIO_RETRY_INTERVAL: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Default)]
pub struct Registration {
    pub name: String,
    pub perm_hash: String,
    pub temp_hash: String,
    pub temp_exp: i64,
}

#[derive(Debug, Clone)]
pub enum RelayEvent {
    Connecting {
        server: String,
    },
    Reconnecting {
        detail: String,
    },
    TofuEstablished,
    Registered,
    PeerName {
        name: String,
    },
    PeerOnline,
    PeerOffline,
    /// 手机 PTT 按下/松开（FRAME_TALK 透传），用于模拟 Fn 触发语音输入
    PeerTalking {
        on: bool,
    },
    AuthAccepted {
        ip: String,
        kind: String,
    },
    AuthFailed {
        ip: String,
        reason: String,
    },
    AudioStats {
        stats: SinkStats,
    },
    AudioError {
        message: String,
    },
    /// 音频输出恢复可用（启动成功或从错误中自愈），控制器据此清除 audio_error
    AudioReady,
    Fatal {
        code: String,
        message: String,
    },
    Stopped,
}

#[derive(Debug, Error)]
enum RelayFailure {
    #[error("网络连接失败: {0}")]
    Network(String),
    #[error("客户端已停止")]
    Stopped,
    #[error("{message}")]
    Fatal { code: String, message: String },
}

enum ClientCommand {
    Register,
    Stop,
}

/// 音频输出错误锁存：同一错误消息只上报一次，失败期间按固定间隔允许重试。
/// 没有它时，输出失败后的每个音频帧都会触发一次 UI 状态推送（约 50 次/秒）。
#[derive(Debug, Default)]
struct AudioErrorLatch {
    message: Option<String>,
    last_retry: Option<Instant>,
}

impl AudioErrorLatch {
    /// 记录错误消息；返回是否为「新错误」（需要上报）
    fn note_error(&mut self, message: &str) -> bool {
        let changed = self.message.as_deref() != Some(message);
        self.message = Some(message.to_string());
        changed
    }

    /// 清除错误锁存；返回之前是否有错误（需要通知恢复）
    fn note_ok(&mut self) -> bool {
        self.message.take().is_some()
    }

    /// 重试门槛：距上次重试不足 interval 时返回 false
    fn retry_due(&mut self, now: Instant, interval: Duration) -> bool {
        match self.last_retry {
            Some(last) if now.duration_since(last) < interval => false,
            _ => {
                self.last_retry = Some(now);
                true
            }
        }
    }
}

pub type RelayEventHandler = Arc<dyn Fn(RelayEvent) + Send + Sync + 'static>;
pub type TofuStoreHandler =
    Arc<dyn Fn(String, String) -> Result<(), String> + Send + Sync + 'static>;

/// 创建 relay 会话所需的依赖。将连接参数集中在一个对象中，避免调用方遗漏
/// TOFU 持久化或误调换设备凭据的参数顺序。
pub(crate) struct RelayClientOptions {
    pub(crate) server: String,
    pub(crate) fingerprint: String,
    pub(crate) device_id: String,
    pub(crate) device_key: String,
    pub(crate) registration: Registration,
    pub(crate) sink: Arc<dyn AudioSink>,
    pub(crate) events: RelayEventHandler,
    pub(crate) tofu_store: TofuStoreHandler,
}

/// 无 Tauri 依赖的 v3 Mac 会话。一个连接由读任务和写/控制任务组成。
pub struct RelayClient {
    server: String,
    device_id: String,
    device_key: String,
    initial_fingerprint: Option<String>,
    registration: Arc<RwLock<Registration>>,
    sink: Arc<dyn AudioSink>,
    audio_error: Mutex<AudioErrorLatch>,
    events: RelayEventHandler,
    tofu_store: TofuStoreHandler,
    commands: UnboundedSender<ClientCommand>,
    command_receiver: Mutex<Option<UnboundedReceiver<ClientCommand>>>,
    stop: Arc<AtomicBool>,
}

impl RelayClient {
    #[allow(dead_code)]
    pub fn new(
        server: String,
        fingerprint: String,
        device_id: String,
        device_key: String,
        registration: Registration,
        sink: Arc<dyn AudioSink>,
        events: RelayEventHandler,
    ) -> Arc<Self> {
        Self::new_with_tofu_store(RelayClientOptions {
            server,
            fingerprint,
            device_id,
            device_key,
            registration,
            sink,
            events,
            tofu_store: Arc::new(|_: String, _: String| Ok(())),
        })
    }

    /// 创建带 TOFU 持久化回调的客户端；回调失败时本次连接必须进入 fatal。
    pub(crate) fn new_with_tofu_store(options: RelayClientOptions) -> Arc<Self> {
        let (commands, command_receiver) = mpsc::unbounded_channel();
        Arc::new(Self {
            server: options.server,
            device_id: options.device_id,
            device_key: options.device_key,
            initial_fingerprint: (!options.fingerprint.is_empty()).then_some(options.fingerprint),
            registration: Arc::new(RwLock::new(options.registration)),
            sink: options.sink,
            audio_error: Mutex::new(AudioErrorLatch::default()),
            events: options.events,
            tofu_store: options.tofu_store,
            commands,
            command_receiver: Mutex::new(Some(command_receiver)),
            stop: Arc::new(AtomicBool::new(false)),
        })
    }

    pub fn start(self: &Arc<Self>) -> JoinHandle<()> {
        let client = Arc::clone(self);
        let receiver = self
            .command_receiver
            .lock()
            .expect("command receiver mutex poisoned")
            .take()
            .expect("RelayClient can only be started once");
        std::thread::Builder::new()
            .name("remote-voice-relay".to_string())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_io()
                    .enable_time()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        (client.events)(RelayEvent::Fatal {
                            code: "runtime".to_string(),
                            message: format!("无法启动网络线程: {error}"),
                        });
                        return;
                    }
                };
                runtime.block_on(client.run(receiver));
            })
            .expect("failed to spawn relay thread")
    }

    pub fn stop(&self) {
        self.stop.store(true, Ordering::Release);
        let _ = self.commands.send(ClientCommand::Stop);
        self.sink.stop();
    }

    pub fn update_registration(&self, registration: Registration) {
        if let Ok(mut current) = self.registration.write() {
            *current = registration;
        }
        let _ = self.commands.send(ClientCommand::Register);
    }

    async fn run(self: Arc<Self>, mut commands: UnboundedReceiver<ClientCommand>) {
        let mut fingerprint = self.initial_fingerprint.clone();
        let mut backoff = 1_u64;
        loop {
            if self.stop.load(Ordering::Acquire) {
                break;
            }
            (self.events)(RelayEvent::Connecting {
                server: self.server.clone(),
            });
            match self.connect_once(&mut fingerprint, &mut commands).await {
                Ok(()) => {
                    backoff = 1;
                }
                Err(RelayFailure::Stopped) => break,
                Err(RelayFailure::Fatal { code, message }) => {
                    (self.events)(RelayEvent::Fatal { code, message });
                    break;
                }
                Err(RelayFailure::Network(message)) => {
                    if self.stop.load(Ordering::Acquire) {
                        break;
                    }
                    let detail = format!("{message}；{} 秒后重连", backoff);
                    (self.events)(RelayEvent::Reconnecting { detail });
                    if self.wait_backoff(&mut commands, backoff).await.is_err() {
                        break;
                    }
                    backoff = (backoff * 2).min(MAX_BACKOFF);
                }
            }
        }
        self.sink.stop();
        (self.events)(RelayEvent::Stopped);
    }

    async fn wait_backoff(
        &self,
        commands: &mut UnboundedReceiver<ClientCommand>,
        seconds: u64,
    ) -> Result<(), RelayFailure> {
        let deadline = Instant::now() + Duration::from_secs(seconds);
        while Instant::now() < deadline {
            if self.stop.load(Ordering::Acquire) {
                return Err(RelayFailure::Stopped);
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            tokio::select! {
                _ = sleep(remaining.min(Duration::from_millis(100))) => {},
                command = commands.recv() => {
                    if matches!(command, Some(ClientCommand::Stop) | None) {
                        return Err(RelayFailure::Stopped);
                    }
                }
            }
        }
        Ok(())
    }

    async fn connect_once(
        &self,
        fingerprint: &mut Option<String>,
        commands: &mut UnboundedReceiver<ClientCommand>,
    ) -> Result<(), RelayFailure> {
        let (host, port) =
            config::parse_server(&self.server).map_err(|error| RelayFailure::Fatal {
                code: "invalid-server".to_string(),
                message: error.to_string(),
            })?;
        let tcp = tokio::select! {
            result = timeout(CONNECT_TIMEOUT, TcpStream::connect((host.as_str(), port))) => {
                result
                    .map_err(|_| RelayFailure::Network("连接 server 超时".to_string()))?
                    .map_err(|error| RelayFailure::Network(error.to_string()))?
            }
            _ = wait_until_stopped(Arc::clone(&self.stop)) => {
                return Err(RelayFailure::Stopped);
            }
        };
        tcp.set_nodelay(true)
            .map_err(|error| RelayFailure::Network(error.to_string()))?;
        let connector = TlsConnector::from(Arc::new(tls_config()));
        let server_name = ServerName::try_from(host.clone()).map_err(|_| RelayFailure::Fatal {
            code: "invalid-server".to_string(),
            message: "服务器主机名无效".to_string(),
        })?;
        let tls = tokio::select! {
            result = timeout(CONNECT_TIMEOUT, connector.connect(server_name, tcp)) => {
                result
                    .map_err(|_| RelayFailure::Network("TLS 握手超时".to_string()))?
                    .map_err(|error| RelayFailure::Network(error.to_string()))?
            }
            _ = wait_until_stopped(Arc::clone(&self.stop)) => {
                return Err(RelayFailure::Stopped);
            }
        };

        let actual_fingerprint = certificate_fingerprint(&tls)?;
        if let Some(expected) = fingerprint.as_deref() {
            if actual_fingerprint != expected {
                return Err(RelayFailure::Fatal {
                    code: "tofu-mismatch".to_string(),
                    message: "服务器身份与已保存记录不符，请检查服务端配置或清除本机信任记录后重试"
                        .to_string(),
                });
            }
        } else {
            let key = config::server_key(&self.server).map_err(|error| RelayFailure::Fatal {
                code: "invalid-server".to_string(),
                message: error.to_string(),
            })?;
            (self.tofu_store)(actual_fingerprint.clone(), key.clone()).map_err(|message| {
                RelayFailure::Fatal {
                    code: "tofu-store".to_string(),
                    message: format!("无法保存服务器信任记录: {message}"),
                }
            })?;
            *fingerprint = Some(actual_fingerprint.clone());
            (self.events)(RelayEvent::TofuEstablished);
        }

        let (reader, mut writer) = split(tls);
        let (frame_sender, mut frames) = mpsc::channel(256);
        let reader_task = tokio::spawn(read_frames(reader, frame_sender));
        let result = self
            .drive_connection(&mut writer, &mut frames, commands)
            .await;
        reader_task.abort();
        let _ = reader_task.await;
        self.sink.stop();
        result
    }

    async fn drive_connection<W>(
        &self,
        writer: &mut W,
        frames: &mut mpsc::Receiver<(u8, Vec<u8>)>,
        commands: &mut UnboundedReceiver<ClientCommand>,
    ) -> Result<(), RelayFailure>
    where
        W: AsyncWrite + Unpin,
    {
        let auth =
            protocol::auth_payload(&self.device_id, &self.device_key).map_err(protocol_failure)?;
        self.write_frame_or_stop(writer, protocol::FRAME_AUTH, &auth)
            .await?;
        let first = timeout(AUTH_TIMEOUT, async {
            loop {
                tokio::select! {
                    frame = frames.recv() => {
                        return frame.ok_or_else(|| RelayFailure::Network("服务器关闭连接".to_string()));
                    }
                    command = commands.recv() => {
                        match command {
                            Some(ClientCommand::Stop) | None => return Err(RelayFailure::Stopped),
                            Some(ClientCommand::Register) => {}
                        }
                    }
                }
            }
        })
        .await
        .map_err(|_| RelayFailure::Network("等待 server 认证应答超时".to_string()))??;
        match first.0 {
            protocol::FRAME_AUTH_OK => {
                let auth_ok = protocol::parse_auth_ok(&first.1).map_err(protocol_failure)?;
                if !auth_ok.mac.is_empty() {
                    (self.events)(RelayEvent::PeerName { name: auth_ok.mac });
                }
            }
            protocol::FRAME_AUTH_ERR => {
                let code = String::from_utf8_lossy(&first.1).to_string();
                return Err(RelayFailure::Fatal {
                    message: auth_reason(&code),
                    code,
                });
            }
            _ => {
                return Err(RelayFailure::Fatal {
                    code: "bad-auth-response".to_string(),
                    message: "server 返回了异常认证应答".to_string(),
                });
            }
        }

        self.send_register(writer).await?;
        (self.events)(RelayEvent::Registered);
        let mut pings = interval(PING_INTERVAL);
        pings.set_missed_tick_behavior(MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = pings.tick() => {
                    self.write_frame_or_stop(writer, protocol::FRAME_PING, &[]).await?;
                }
                command = commands.recv() => {
                    match command {
                        Some(ClientCommand::Register) => self.send_register(writer).await?,
                        Some(ClientCommand::Stop) | None => return Err(RelayFailure::Stopped),
                    }
                }
                frame = frames.recv() => {
                    let (frame_type, payload) = frame
                        .ok_or_else(|| RelayFailure::Network("服务器连接已关闭".to_string()))?;
                    self.handle_frame(writer, frame_type, &payload).await?;
                }
            }
        }
    }

    async fn send_register<W: AsyncWrite + Unpin>(
        &self,
        writer: &mut W,
    ) -> Result<(), RelayFailure> {
        let registration = self
            .registration
            .read()
            .map_err(|_| RelayFailure::Fatal {
                code: "state-lock".to_string(),
                message: "注册状态锁已损坏".to_string(),
            })?
            .clone();
        let payload = protocol::register_payload(
            &registration.name,
            &registration.perm_hash,
            &registration.temp_hash,
            registration.temp_exp,
        )
        .map_err(protocol_failure)?;
        self.write_frame_or_stop(writer, protocol::FRAME_REGISTER, &payload)
            .await
    }

    async fn write_frame_or_stop<W: AsyncWrite + Unpin>(
        &self,
        writer: &mut W,
        frame_type: u8,
        payload: &[u8],
    ) -> Result<(), RelayFailure> {
        tokio::select! {
            result = protocol::write_frame(writer, frame_type, payload) => {
                result.map_err(protocol_failure)
            }
            _ = wait_until_stopped(Arc::clone(&self.stop)) => Err(RelayFailure::Stopped),
        }
    }

    /// 上报音频输出失败；同一消息只发一次，防止每帧刷屏（约 50 次/秒）
    fn report_audio_failure(&self, error: AudioError) {
        let message = error.to_string();
        let changed = self
            .audio_error
            .lock()
            .map(|mut latch| latch.note_error(&message))
            .unwrap_or(true);
        if changed {
            (self.events)(RelayEvent::AudioError { message });
        }
    }

    /// 音频输出可用：清除错误锁存并通知控制器（低频事件，可直接发）
    fn report_audio_ready(&self) {
        if let Ok(mut latch) = self.audio_error.lock() {
            latch.note_ok();
        }
        (self.events)(RelayEvent::AudioReady);
    }

    /// feed 成功说明输出确实可用；此前锁存过错误时才补发恢复通知
    fn clear_audio_error_if_needed(&self) {
        let cleared = self
            .audio_error
            .lock()
            .map(|mut latch| latch.note_ok())
            .unwrap_or(false);
        if cleared {
            (self.events)(RelayEvent::AudioReady);
        }
    }

    /// 输出失败期间按 AUDIO_RETRY_INTERVAL 限频重建流，使瞬时错误可自愈
    fn retry_audio_start(&self) {
        let due = self
            .audio_error
            .lock()
            .map(|mut latch| latch.retry_due(Instant::now(), AUDIO_RETRY_INTERVAL))
            .unwrap_or(false);
        if !due {
            return;
        }
        match self.sink.start() {
            Ok(()) => self.report_audio_ready(),
            Err(error) => self.report_audio_failure(error),
        }
    }

    async fn handle_frame<W: AsyncWrite + Unpin>(
        &self,
        writer: &mut W,
        frame_type: u8,
        payload: &[u8],
    ) -> Result<(), RelayFailure> {
        match frame_type {
            protocol::FRAME_PING => {
                self.write_frame_or_stop(writer, protocol::FRAME_PONG, &[])
                    .await
            }
            protocol::FRAME_PONG => Ok(()),
            protocol::FRAME_PEER_STATE => {
                if payload.first() == Some(&protocol::PEER_ONLINE) {
                    match self.sink.start() {
                        Ok(()) => self.report_audio_ready(),
                        Err(error) => self.report_audio_failure(error),
                    }
                    (self.events)(RelayEvent::PeerOnline);
                } else if payload.first() == Some(&protocol::PEER_OFFLINE) {
                    self.sink.stop();
                    if let Ok(mut latch) = self.audio_error.lock() {
                        latch.note_ok();
                    }
                    (self.events)(RelayEvent::PeerOffline);
                }
                Ok(())
            }
            protocol::FRAME_AUDIO => {
                match self.sink.feed(payload) {
                    Ok(()) => self.clear_audio_error_if_needed(),
                    Err(error) => {
                        self.report_audio_failure(error);
                        self.retry_audio_start();
                    }
                }
                let stats = self.sink.stats();
                if stats.frames > 0 && stats.frames % 25 == 0 {
                    (self.events)(RelayEvent::AudioStats { stats });
                }
                Ok(())
            }
            protocol::FRAME_TALK => {
                (self.events)(RelayEvent::PeerTalking {
                    on: payload.first() == Some(&0x01),
                });
                Ok(())
            }
            protocol::FRAME_EVENT => match protocol::parse_event(payload) {
                Ok(event) if event.event == "auth-ok" => {
                    (self.events)(RelayEvent::AuthAccepted {
                        ip: event.ip,
                        kind: event.kind,
                    });
                    Ok(())
                }
                Ok(event) if event.event == "auth-fail" => {
                    (self.events)(RelayEvent::AuthFailed {
                        ip: event.ip,
                        reason: event.reason,
                    });
                    Ok(())
                }
                _ => Ok(()),
            },
            _ => Ok(()),
        }
    }
}

async fn wait_until_stopped(stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::Acquire) {
        sleep(Duration::from_millis(100)).await;
    }
}

async fn read_frames<R>(mut reader: R, sender: mpsc::Sender<(u8, Vec<u8>)>)
where
    R: AsyncRead + Unpin + Send + 'static,
{
    while let Ok(frame) = protocol::read_frame(&mut reader).await {
        if sender.send(frame).await.is_err() {
            break;
        }
    }
}

fn protocol_failure(error: ProtocolError) -> RelayFailure {
    RelayFailure::Network(error.to_string())
}

fn auth_reason(code: &str) -> String {
    match code {
        protocol::REASON_INVALID_DEVICE => {
            "本机设备身份无效，请恢复原设备身份或重新登记".to_string()
        }
        protocol::REASON_DEVICE_BUSY => "本机设备已经在线，请关闭另一份客户端".to_string(),
        protocol::REASON_DEVICE_STORE_UNAVAILABLE => {
            "server 无法保存设备身份，请检查 data 目录权限".to_string()
        }
        "invalid-key" => "旧版注册密钥无效".to_string(),
        _ => format!("认证被 server 拒绝（{code}）"),
    }
}

fn certificate_fingerprint(tls: &TlsStream<TcpStream>) -> Result<String, RelayFailure> {
    let (_, connection) = tls.get_ref();
    let cert = connection
        .peer_certificates()
        .and_then(|certificates| certificates.first())
        .ok_or_else(|| RelayFailure::Network("服务器没有返回证书".to_string()))?;
    Ok(hex::encode(Sha256::digest(cert.as_ref())))
}

fn tls_config() -> ClientConfig {
    ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(TofuVerifier))
        .with_no_client_auth()
}

/// 证书链由应用层 TOFU 校验；此 verifier 只让自签证书完成 TLS 握手。
#[derive(Debug)]
struct TofuVerifier;

impl ServerCertVerifier for TofuVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls12_signature(
            _message,
            _cert,
            _dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls13_signature(
            _message,
            _cert,
            _dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio::NullSink;
    use rustls::pki_types::{pem::PemObject, CertificateDer, PrivateKeyDer};
    use serde_json::Value;
    use std::sync::atomic::AtomicUsize;
    use std::sync::Mutex as StdMutex;
    use tokio::io::split;
    use tokio::net::TcpListener;
    use tokio_rustls::TlsAcceptor;

    const TEST_CERT_CHAIN: &str = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/certs/chain.pem"
    ));
    const TEST_PRIVATE_KEY: &str =
        include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/certs/end.key"));

    #[test]
    fn auth_error_is_user_readable_without_secret_leak() {
        let message = auth_reason(protocol::REASON_DEVICE_BUSY);
        assert!(message.contains("已经在线"));
        assert!(!message.contains("device_key"));
    }

    #[test]
    fn audio_error_latch_dedupes_messages_and_gates_retries() {
        // 测试目的：同一错误只上报一次；重试受固定间隔约束；恢复只通知一次。
        let mut latch = AudioErrorLatch::default();
        let start = Instant::now();
        assert!(latch.note_error("A"));
        assert!(!latch.note_error("A"));
        assert!(latch.note_error("B"));
        assert!(latch.retry_due(start, Duration::from_secs(5)));
        assert!(!latch.retry_due(start + Duration::from_secs(1), Duration::from_secs(5)));
        assert!(latch.retry_due(start + Duration::from_secs(6), Duration::from_secs(5)));
        assert!(latch.note_ok());
        assert!(!latch.note_ok());
    }

    #[test]
    fn registration_updates_are_shared_safely() {
        let client = RelayClient::new(
            "127.0.0.1:9432".to_string(),
            String::new(),
            "mac-test".to_string(),
            "a".repeat(64),
            Registration {
                name: "A".to_string(),
                ..Default::default()
            },
            Arc::new(NullSink::default()),
            Arc::new(|_| {}),
        );
        client.update_registration(Registration {
            name: "B".to_string(),
            ..Default::default()
        });
        assert_eq!(client.registration.read().unwrap().name, "B");
    }

    #[tokio::test]
    async fn auth_ok_is_required_before_register_is_sent() {
        // 测试目的：验证 v3 Mac 严格遵守 AUTH → AUTH_OK → REGISTER 顺序。
        let client = RelayClient::new(
            "127.0.0.1:9432".to_string(),
            String::new(),
            "mac-test".to_string(),
            "a".repeat(64),
            Registration {
                name: "Test Mac".to_string(),
                temp_hash: "b".repeat(64),
                temp_exp: 1_900_000_000,
                ..Default::default()
            },
            Arc::new(NullSink::default()),
            Arc::new(|_| {}),
        );
        let (client_io, mut server_io) = tokio::io::duplex(4096);
        let (client_reader, mut client_writer) = split(client_io);
        let (frame_sender, mut frames) = mpsc::channel(8);
        let reader_task = tokio::spawn(read_frames(client_reader, frame_sender));
        let (command_sender, mut commands) = mpsc::unbounded_channel();
        let (release_sender, release_receiver) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let (frame_type, auth_payload) = protocol::read_frame(&mut server_io).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_AUTH);
            let auth: Value = serde_json::from_slice(&auth_payload).unwrap();
            assert_eq!(auth["role"], "mac");
            assert_eq!(auth["proto"], 3);
            assert_eq!(auth["device_id"], "mac-test");

            protocol::write_frame(&mut server_io, protocol::FRAME_AUTH_OK, b"{}")
                .await
                .unwrap();
            let (frame_type, register_payload) =
                protocol::read_frame(&mut server_io).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_REGISTER);
            let registration: Value = serde_json::from_slice(&register_payload).unwrap();
            assert_eq!(registration["name"], "Test Mac");
            assert_eq!(registration["temp"], "b".repeat(64));
            command_sender.send(ClientCommand::Stop).unwrap();
            // 保持 server 端存活，确保客户端先处理 Stop，而不是与 EOF 竞态。
            let _ = release_receiver.await;
        });

        let result = timeout(
            Duration::from_secs(1),
            client.drive_connection(&mut client_writer, &mut frames, &mut commands),
        )
        .await
        .unwrap();
        assert!(matches!(result, Err(RelayFailure::Stopped)));
        release_sender.send(()).unwrap();
        server_task.await.unwrap();
        reader_task.abort();
        let _ = reader_task.await;
    }

    /// 恒失败的音频 sink：验证失败错误去重与重试门槛，不依赖真实声卡。
    #[derive(Default)]
    struct FailingSink {
        starts: AtomicUsize,
        feeds: AtomicUsize,
    }

    impl AudioSink for FailingSink {
        fn start(&self) -> Result<(), AudioError> {
            self.starts.fetch_add(1, Ordering::Relaxed);
            Err(AudioError::Device("测试音频输出不可用".to_string()))
        }

        fn stop(&self) {}

        fn feed(&self, _payload: &[u8]) -> Result<(), AudioError> {
            self.feeds.fetch_add(1, Ordering::Relaxed);
            Err(AudioError::Device("测试音频输出不可用".to_string()))
        }

        fn stats(&self) -> SinkStats {
            SinkStats::default()
        }
    }

    #[tokio::test]
    async fn repeated_audio_failures_are_reported_once_and_retry_is_gated() {
        // 测试目的：输出失败后每帧都会 feed 失败，但 UI 事件只应发一次（回归防护）；
        // 自愈重试按门槛限频——本用例 3 帧音频只触发 1 次额外 start。
        let sink = Arc::new(FailingSink::default());
        let failing_sink: Arc<dyn AudioSink> = sink.clone();
        let events_log = Arc::new(StdMutex::new(Vec::<RelayEvent>::new()));
        let events_capture = Arc::clone(&events_log);
        let client = RelayClient::new(
            "127.0.0.1:9432".to_string(),
            String::new(),
            "mac-test".to_string(),
            "a".repeat(64),
            Registration {
                name: "Test Mac".to_string(),
                ..Default::default()
            },
            failing_sink,
            Arc::new(move |event| events_capture.lock().unwrap().push(event)),
        );
        let (client_io, mut server_io) = tokio::io::duplex(4096);
        let (client_reader, mut client_writer) = split(client_io);
        let (frame_sender, mut frames) = mpsc::channel(8);
        let reader_task = tokio::spawn(read_frames(client_reader, frame_sender));
        let (command_sender, mut commands) = mpsc::unbounded_channel();
        let (release_sender, release_receiver) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let (frame_type, _) = protocol::read_frame(&mut server_io).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_AUTH);
            protocol::write_frame(&mut server_io, protocol::FRAME_AUTH_OK, b"{}")
                .await
                .unwrap();
            let (frame_type, _) = protocol::read_frame(&mut server_io).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_REGISTER);
            protocol::write_frame(
                &mut server_io,
                protocol::FRAME_PEER_STATE,
                &[protocol::PEER_ONLINE],
            )
            .await
            .unwrap();
            for _ in 0..3 {
                protocol::write_frame(&mut server_io, protocol::FRAME_AUDIO, &[0, 0, 1, 0])
                    .await
                    .unwrap();
            }
            // PING/PONG 作为处理屏障：读到 PONG 即说明 3 帧音频都已处理完
            protocol::write_frame(&mut server_io, protocol::FRAME_PING, &[])
                .await
                .unwrap();
            let (frame_type, _) = protocol::read_frame(&mut server_io).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_PONG);
            command_sender.send(ClientCommand::Stop).unwrap();
            let _ = release_receiver.await;
        });

        let result = timeout(
            Duration::from_secs(2),
            client.drive_connection(&mut client_writer, &mut frames, &mut commands),
        )
        .await
        .unwrap();
        assert!(matches!(result, Err(RelayFailure::Stopped)));
        release_sender.send(()).unwrap();
        server_task.await.unwrap();
        reader_task.abort();
        let _ = reader_task.await;

        let audio_errors = events_log
            .lock()
            .unwrap()
            .iter()
            .filter(|event| matches!(event, RelayEvent::AudioError { .. }))
            .count();
        assert_eq!(audio_errors, 1);
        assert_eq!(sink.feeds.load(Ordering::Relaxed), 3);
        assert_eq!(sink.starts.load(Ordering::Relaxed), 2);
    }

    #[tokio::test]
    async fn tls_tofu_v3_registration_and_audio_roundtrip() {
        // 测试目的：在真实 TCP/TLS 上验证首次 TOFU、v3 认证、REGISTER 与音频接收。
        let certs = CertificateDer::pem_slice_iter(TEST_CERT_CHAIN.as_bytes())
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        let fingerprint = hex::encode(Sha256::digest(certs[0].as_ref()));
        let key = PrivateKeyDer::from_pem_slice(TEST_PRIVATE_KEY.as_bytes()).unwrap();
        let server_config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(certs, key)
            .unwrap();
        let acceptor = TlsAcceptor::from(Arc::new(server_config));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let tls = acceptor.accept(stream).await.unwrap();
            let (mut reader, mut writer) = split(tls);
            let (frame_type, auth_payload) = protocol::read_frame(&mut reader).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_AUTH);
            let auth: Value = serde_json::from_slice(&auth_payload).unwrap();
            assert_eq!(auth["role"], "mac");
            assert_eq!(auth["proto"], 3);
            assert_eq!(auth["device_id"], "integration-mac");
            protocol::write_frame(&mut writer, protocol::FRAME_AUTH_OK, b"{}")
                .await
                .unwrap();

            let (frame_type, registration_payload) =
                protocol::read_frame(&mut reader).await.unwrap();
            assert_eq!(frame_type, protocol::FRAME_REGISTER);
            let registration: Value = serde_json::from_slice(&registration_payload).unwrap();
            assert_eq!(registration["name"], "Integration Mac");
            assert_eq!(registration["temp"], "b".repeat(64));
            protocol::write_frame(
                &mut writer,
                protocol::FRAME_PEER_STATE,
                &[protocol::PEER_ONLINE],
            )
            .await
            .unwrap();
            protocol::write_frame(&mut writer, protocol::FRAME_AUDIO, &[0, 0, 1, 0])
                .await
                .unwrap();
            protocol::write_frame(&mut writer, protocol::FRAME_TALK, &[0x01])
                .await
                .unwrap();

            // 等待客户端处理 Stop；连接关闭时读错误属于预期收尾路径。
            let _ = protocol::read_frame(&mut reader).await;
        });

        let tofu = Arc::new(StdMutex::new(None::<(String, String)>));
        let tofu_capture = Arc::clone(&tofu);
        let events_log = Arc::new(StdMutex::new(Vec::<RelayEvent>::new()));
        let events_capture = Arc::clone(&events_log);
        let events = Arc::new(move |event: RelayEvent| {
            events_capture.lock().unwrap().push(event);
        });
        let sink = Arc::new(NullSink::default());
        let sink_capture = Arc::clone(&sink);
        let client = RelayClient::new_with_tofu_store(RelayClientOptions {
            server: addr.to_string(),
            fingerprint: String::new(),
            device_id: "integration-mac".to_string(),
            device_key: "a".repeat(64),
            registration: Registration {
                name: "Integration Mac".to_string(),
                temp_hash: "b".repeat(64),
                temp_exp: 1_900_000_000,
                ..Default::default()
            },
            sink,
            events,
            tofu_store: Arc::new(move |got, key| {
                *tofu_capture.lock().unwrap() = Some((got, key));
                Ok(())
            }),
        });
        let client_thread = client.start();

        timeout(Duration::from_secs(2), async {
            loop {
                let got_talk = events_log
                    .lock()
                    .unwrap()
                    .iter()
                    .any(|event| matches!(event, RelayEvent::PeerTalking { on: true }));
                if sink_capture.stats().frames >= 1 && got_talk {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        client.stop();
        client_thread.join().unwrap();
        server_task.await.unwrap();

        assert_eq!(sink_capture.stats().frames, 1);
        assert!(events_log
            .lock()
            .unwrap()
            .iter()
            .any(|event| matches!(event, RelayEvent::PeerTalking { on: true })));
        assert_eq!(
            tofu.lock().unwrap().as_ref(),
            Some(&(fingerprint, format!("127.0.0.1:{}", addr.port())))
        );
    }
}
