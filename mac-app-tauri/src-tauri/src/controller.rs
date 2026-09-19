use crate::audio::{self, AudioSink, CpalSink, SinkStats};
use crate::config::{self, AppConfig};
use crate::history::{self, HistoryEntry};
use crate::identity;
use crate::keyinject::{install_fn_release_tap, InjectMode, KeyInjector};
use crate::relay::{Registration, RelayClient, RelayClientOptions, RelayEvent};
use crate::secrets;
use crate::storage::SystemKeychain;
use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, State};

const MAX_EVENTS: usize = 50;

#[derive(Debug, Clone, Serialize)]
pub struct CommandError {
    pub code: String,
    pub message: String,
}

impl CommandError {
    fn new(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct UiEvent {
    pub at: i64,
    pub level: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AppState {
    pub status: String,
    pub detail: String,
    /// fatal 事件的机器可读代码（如 cert-changed）；前端据此渲染专用恢复 UI
    pub fatal_code: String,
    pub server: String,
    pub name: String,
    pub audio_device: String,
    /// 服务器密码是否已设置（密码本身永不进入 AppState）
    pub password_set: bool,
    /// 断线自动重连开关（默认开）
    pub auto_reconnect: bool,
    pub peer_name: String,
    pub talking: bool,
    pub dictation_enabled: bool,
    pub dictation_mode: String,
    pub audio_frames: u64,
    pub audio_bytes: u64,
    pub dropped_audio_frames: u64,
    pub audio_error: String,
    pub events: Vec<UiEvent>,
}

impl AppState {
    fn from_config(config: &AppConfig, password_set: bool) -> Self {
        Self {
            status: "stopped".to_string(),
            detail: String::new(),
            fatal_code: String::new(),
            server: config.server.clone(),
            name: config.name.clone(),
            audio_device: config.audio_device.clone(),
            password_set,
            auto_reconnect: config.auto_reconnect,
            peer_name: String::new(),
            talking: false,
            dictation_enabled: config.dictation_enabled,
            dictation_mode: config.dictation_mode.clone(),
            audio_frames: 0,
            audio_bytes: 0,
            dropped_audio_frames: 0,
            audio_error: String::new(),
            events: Vec::new(),
        }
    }
}

/// Tauri 与无 UI relay 核心之间的编排层；内部凭据永远不进入 AppState。
pub struct AppController {
    config_dir: std::path::PathBuf,
    config: Mutex<AppConfig>,
    state: Mutex<AppState>,
    /// 服务器密码哈希（原文只在 Keychain/回退文件中）
    password_hash: Mutex<String>,
    history_lock: Mutex<()>,
    lifecycle_lock: Mutex<()>,
    client: Mutex<Option<Arc<RelayClient>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
    app: Mutex<Option<AppHandle>>,
    injector: Arc<KeyInjector>,
    inject_warned: std::sync::atomic::AtomicBool,
}

impl AppController {
    pub fn new() -> Result<Self, CommandError> {
        let config_dir = config::config_dir()
            .map_err(|error| CommandError::new("config-dir", error.to_string()))?;
        let mut config =
            config::load().map_err(|error| CommandError::new("config-read", error.to_string()))?;
        config.apply_defaults();
        let password = secrets::load_system(&config_dir);
        let password_set = password.is_some();
        let password_hash = password
            .as_deref()
            .map(secrets::hash_of)
            .unwrap_or_default();
        let mut state = AppState::from_config(&config, password_set);
        state.events = load_history_events(&config_dir);
        Ok(Self {
            state: Mutex::new(state),
            config: Mutex::new(config),
            password_hash: Mutex::new(password_hash),
            history_lock: Mutex::new(()),
            lifecycle_lock: Mutex::new(()),
            client: Mutex::new(None),
            thread: Mutex::new(None),
            app: Mutex::new(None),
            injector: Arc::new(KeyInjector::new()),
            inject_warned: std::sync::atomic::AtomicBool::new(false),
            config_dir,
        })
    }

    pub fn attach_app(&self, app: AppHandle) {
        if let Ok(mut slot) = self.app.lock() {
            *slot = Some(app);
        }
    }

    pub fn snapshot(&self) -> Result<AppState, CommandError> {
        self.state
            .lock()
            .map(|state| state.clone())
            .map_err(|_| CommandError::new("state-lock", "应用状态锁已损坏"))
    }

    pub fn connect(self: &Arc<Self>) -> Result<(), CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        self.connect_inner()
    }

    fn connect_inner(self: &Arc<Self>) -> Result<(), CommandError> {
        self.stop_client();
        // 重连前强制释放 Fn：旧连接的 PeerOffline 事件可能因 fatal 而未送达
        self.injector.release();
        let (config, state) = {
            let config = self
                .config
                .lock()
                .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?
                .clone();
            let state = self
                .state
                .lock()
                .map_err(|_| CommandError::new("state-lock", "应用状态锁已损坏"))?
                .clone();
            (config, state)
        };
        config::parse_server(&config.server)
            .map_err(|error| CommandError::new("invalid-server", error.to_string()))?;
        let device = identity::load_or_create()
            .map_err(|error| CommandError::new("device-identity", error.to_string()))?;
        let tls_mode = config::tls_mode(&config.server);
        let fingerprint = match tls_mode {
            config::TlsMode::Strict => String::new(), // 标准验证不使用指纹
            _ => config::trusted_fingerprint(&config, &config.server),
        };
        let registration = Registration {
            name: state.name.clone(),
            password_hash: self
                .password_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "服务器密码状态锁已损坏"))?
                .clone(),
        };
        let sink: Arc<dyn AudioSink> = Arc::new(CpalSink::new(state.audio_device.clone()));
        let weak = Arc::downgrade(self);
        let events = Arc::new(move |event: RelayEvent| {
            if let Some(controller) = weak.upgrade() {
                controller.handle_relay_event(event);
            }
        });
        let tofu_controller = Arc::downgrade(self);
        let tofu_store = Arc::new(move |fingerprint: String, server_key: String| {
            let controller = tofu_controller
                .upgrade()
                .ok_or_else(|| "应用控制器已退出".to_string())?;
            controller
                .persist_fingerprint(&server_key, &fingerprint)
                .map_err(|error| error.message)
        });
        let client = RelayClient::new_with_tofu_store(RelayClientOptions {
            server: config.server.clone(),
            fingerprint,
            tls_mode,
            auto_reconnect: config.auto_reconnect,
            device_id: device.device_id,
            device_key: device.device_key,
            registration,
            sink,
            events,
            tofu_store,
        });
        // 先发布连接中状态，再启动线程，避免极快的本地连接被旧状态覆盖。
        self.update_state(|state| {
            state.status = "connecting".to_string();
            state.detail = config.server.clone();
            state.fatal_code.clear();
            state.audio_error.clear();
            state.peer_name.clear();
            state.talking = false;
            state.audio_frames = 0;
            state.audio_bytes = 0;
            state.dropped_audio_frames = 0;
        });
        *self
            .client
            .lock()
            .map_err(|_| CommandError::new("client-lock", "连接状态锁已损坏"))? =
            Some(Arc::clone(&client));
        let thread = client.start();
        match self.thread.lock() {
            Ok(mut slot) => *slot = Some(thread),
            Err(_) => {
                client.stop();
                let _ = thread.join();
                let _ = self.client.lock().map(|mut slot| slot.take());
                return Err(CommandError::new("thread-lock", "网络线程状态锁已损坏"));
            }
        }
        Ok(())
    }

    pub fn disconnect(&self) {
        let _lifecycle_guard = self.lifecycle_lock.lock().ok();
        self.injector.release();
        self.stop_client();
        self.update_state(|state| {
            state.status = "stopped".to_string();
            state.detail.clear();
            state.fatal_code.clear();
            state.talking = false;
        });
    }

    fn stop_client(&self) {
        if let Ok(mut client) = self.client.lock() {
            if let Some(client) = client.take() {
                client.stop();
            }
        }
        if let Ok(mut thread) = self.thread.lock() {
            if let Some(thread) = thread.take() {
                let _ = thread.join();
            }
        }
    }

    #[allow(clippy::too_many_arguments)] // Tauri command 参数须扁平供前端按名调用
    pub fn save_settings(
        self: &Arc<Self>,
        server: String,
        name: String,
        audio_device: String,
        dictation_enabled: bool,
        dictation_mode: String,
        auto_reconnect: bool,
    ) -> Result<AppState, CommandError> {
        config::parse_server(&server)
            .map_err(|error| CommandError::new("invalid-server", error.to_string()))?;
        if name.chars().count() > 128 {
            return Err(CommandError::new(
                "invalid-name",
                "设备名不能超过 128 个字符",
            ));
        }
        if audio_device.trim().is_empty() || audio_device.chars().count() > 128 {
            return Err(CommandError::new("invalid-audio-device", "音频设备名无效"));
        }
        if dictation_mode != "hold" && dictation_mode != "double" {
            return Err(CommandError::new(
                "invalid-dictation-mode",
                "语音输入模拟方式无效",
            ));
        }
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        let current_state = self.snapshot()?;
        let active = matches!(
            current_state.status.as_str(),
            "connecting" | "registered" | "bridged" | "reconnecting"
        );
        let server = server.trim().to_string();
        let name = name.trim().to_string();
        let audio_device = audio_device.trim().to_string();
        {
            let mut config = self
                .config
                .lock()
                .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
            config.server = server.clone();
            config.name = name.clone();
            config.audio_device = audio_device.clone();
            config.auto_reconnect = auto_reconnect;
            config.dictation_enabled = dictation_enabled;
            config.dictation_mode = dictation_mode.clone();
            config::save(&config)
                .map_err(|error| CommandError::new("config-write", error.to_string()))?;
        }
        self.update_state(|state| {
            state.server = server.clone();
            state.name = name.clone();
            state.audio_device = audio_device.clone();
            state.auto_reconnect = auto_reconnect;
            state.dictation_enabled = dictation_enabled;
            state.dictation_mode = dictation_mode.clone();
        });
        // 配置切换（关闭功能/换模式）时强制释放，避免旧模式下的 Fn 悬空
        self.injector.release();
        self.inject_warned
            .store(false, std::sync::atomic::Ordering::Relaxed);
        if active {
            self.connect_inner()?;
        }
        self.snapshot()
    }

    /// 设置服务器密码（协议 v4）：原文进 Keychain，REGISTER 把哈希推送给服务器。
    pub fn set_server_password(
        self: &Arc<Self>,
        password: String,
    ) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        let password = password.trim().to_string();
        let in_keychain = secrets::save_permanent(&self.config_dir, &SystemKeychain, &password)
            .map_err(|error| CommandError::new("password-write", error.to_string()))?;
        {
            let mut config = self
                .config
                .lock()
                .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
            config.perm_in_keychain = in_keychain;
            config::save(&config)
                .map_err(|error| CommandError::new("config-write", error.to_string()))?;
        }
        {
            let mut hash = self
                .password_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "服务器密码状态锁已损坏"))?;
            *hash = secrets::hash_of(&password);
        }
        self.update_state(|state| state.password_set = true);
        self.update_registration();
        self.add_event("info", "服务器密码已更新，手机端输入该密码即可控制本机");
        self.snapshot()
    }

    /// 停用服务器密码（清空后 Mac 只注册设备名，手机端无法再认证）。
    pub fn clear_server_password(&self) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        secrets::clear_permanent(&self.config_dir, &SystemKeychain)
            .map_err(|error| CommandError::new("password-delete", error.to_string()))?;
        {
            let mut config = self
                .config
                .lock()
                .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
            config.perm_in_keychain = false;
            config::save(&config)
                .map_err(|error| CommandError::new("config-write", error.to_string()))?;
        }
        {
            let mut hash = self
                .password_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "服务器密码状态锁已损坏"))?;
            hash.clear();
        }
        self.update_state(|state| state.password_set = false);
        self.update_registration();
        self.add_event("warning", "服务器密码已停用，手机端将无法连接");
        self.snapshot()
    }

    /// 手机扫码配对载荷：rv://<host>[:<port>]?s=<服务器密码>&n=<设备名>。
    /// 需要密码原文，只在用户显式出示二维码时从 Keychain 读取。
    pub fn get_pairing_payload(&self) -> Result<String, CommandError> {
        let password = secrets::load_system(&self.config_dir).ok_or_else(|| {
            CommandError::new("password-missing", "请先在连接设置里设置服务器密码")
        })?;
        let state = self.snapshot()?;
        let authority = state
            .server
            .trim()
            .split('?')
            .next()
            .unwrap_or("")
            .trim()
            .to_string();
        if authority.is_empty() {
            return Err(CommandError::new(
                "server-missing",
                "请先在连接设置里填写服务器地址",
            ));
        }
        let mut payload = format!("rv://{authority}?s={}", urlencode(&password));
        if !state.name.trim().is_empty() {
            payload.push_str("&n=");
            payload.push_str(&urlencode(state.name.trim()));
        }
        Ok(payload)
    }

    pub fn list_audio_devices(&self) -> Result<Vec<String>, CommandError> {
        audio::list_output_devices()
            .map_err(|error| CommandError::new("audio-enumeration", error.to_string()))
    }

    pub fn clear_history(&self) -> Result<AppState, CommandError> {
        let _history_guard = self
            .history_lock
            .lock()
            .map_err(|_| CommandError::new("history-lock", "历史记录锁已损坏"))?;
        history::clear(&self.config_dir)
            .map_err(|error| CommandError::new("history-delete", error.to_string()))?;
        self.update_state(|state| state.events.clear());
        self.snapshot()
    }

    /// 清除当前服务器的 TOFU 记录；证书更换后需用户明确确认并重新连接。
    pub fn clear_server_trust(self: &Arc<Self>) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        let current = self.snapshot()?;
        config::parse_server(&current.server)
            .map_err(|error| CommandError::new("invalid-server", error.to_string()))?;
        {
            let mut config = self
                .config
                .lock()
                .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
            let mut next = config.clone();
            config::clear_fingerprint(&mut next, &current.server)
                .map_err(|error| CommandError::new("tofu-clear", error.to_string()))?;
            config::save(&next)
                .map_err(|error| CommandError::new("tofu-clear", error.to_string()))?;
            *config = next;
        }
        let active = matches!(
            current.status.as_str(),
            "connecting" | "registered" | "bridged" | "reconnecting"
        );
        self.add_event("warning", "已清除当前服务器信任，下次连接将重新建立");
        if active {
            self.connect_inner()?;
        }
        self.snapshot()
    }

    /// 手动放下 Fn（界面按钮 / 物理 Fn 抬起之外的确定性兜底）。
    /// 解除后保持释放，直到手机新一轮按下（TALK true）才恢复模拟。
    pub fn manual_release(&self) {
        self.injector.release();
        self.update_state(|state| state.talking = false);
        self.add_event("info", "已手动放下 Fn；手机再次按下说话时会自动恢复");
    }

    fn persist_fingerprint(&self, server: &str, fingerprint: &str) -> Result<(), CommandError> {
        let mut config = self
            .config
            .lock()
            .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
        let mut next = config.clone();
        config::remember_fingerprint(&mut next, server, fingerprint)
            .map_err(|error| CommandError::new("tofu-store", error.to_string()))?;
        config::save(&next).map_err(|error| CommandError::new("tofu-store", error.to_string()))?;
        *config = next;
        Ok(())
    }

    fn update_registration(&self) {
        let (client, state, password_hash) = match (
            self.client.lock().ok().and_then(|client| client.clone()),
            self.state.lock().ok().map(|state| state.clone()),
            self.password_hash.lock().ok().map(|hash| hash.clone()),
        ) {
            (Some(client), Some(state), Some(password_hash)) => (client, state, password_hash),
            _ => return,
        };
        client.update_registration(Registration {
            name: state.name,
            password_hash,
        });
    }

    fn handle_relay_event(&self, event: RelayEvent) {
        match event {
            RelayEvent::Connecting { server } => self.update_state(|state| {
                state.status = "connecting".to_string();
                state.detail = server;
            }),
            RelayEvent::Reconnecting { detail } => {
                // 与中继失联即释放 Fn：断线期间收不到手机的"松开"帧，
                // 主动释放防止 Fn 悬空（重桥后手机会重发当前状态恢复语义）
                self.injector.release();
                self.update_state(|state| {
                    state.status = "reconnecting".to_string();
                    state.detail = detail;
                    state.peer_name.clear();
                    state.talking = false;
                })
            }
            RelayEvent::TofuEstablished => self.add_event("info", "已建立服务器信任"),
            RelayEvent::Registered => self.update_state(|state| {
                state.status = "registered".to_string();
                state.detail = "等待手机连接".to_string();
                state.audio_error.clear();
            }),
            RelayEvent::PeerName { name } => self.update_state(|state| state.peer_name = name),
            RelayEvent::PeerOnline => self.update_state(|state| {
                state.status = "bridged".to_string();
                state.detail = if state.peer_name.is_empty() {
                    "手机已连接".to_string()
                } else {
                    format!("{} 已连接", state.peer_name)
                };
            }),
            RelayEvent::PeerOffline => {
                // 对端掉线必须强制释放 Fn，防止手机崩溃/断网后 Fn 悬空（设计 §1）
                self.injector.release();
                self.update_state(|state| {
                    state.status = "registered".to_string();
                    state.detail = "等待手机连接".to_string();
                    state.peer_name.clear();
                    state.talking = false;
                })
            }
            RelayEvent::PeerTalking { on } => {
                self.update_state(|state| state.talking = on);
                let (enabled, mode) = match self.state.lock() {
                    Ok(state) => (
                        state.dictation_enabled,
                        InjectMode::parse(&state.dictation_mode),
                    ),
                    Err(_) => return,
                };
                if !enabled {
                    return;
                }
                if let Err(message) = self.injector.set_talking(on, mode) {
                    // 权限类错误每次 PTT 都会触发，只提示一次避免刷屏
                    if !self
                        .inject_warned
                        .swap(true, std::sync::atomic::Ordering::Relaxed)
                    {
                        self.add_event("warning", message);
                    }
                }
            }
            RelayEvent::AuthAccepted { ip } => {
                self.add_history(HistoryEntry {
                    ts: unix_now() as f64,
                    device: self.snapshot().map(|state| state.name).unwrap_or_default(),
                    kind: "手机连接".to_string(),
                    ip: ip.clone(),
                    dur_s: 0.0,
                    bytes: 0,
                    result: "✓ 已桥接".to_string(),
                });
                self.add_event("success", format!("手机已连接 · {}", ip));
            }
            RelayEvent::AuthFailed { ip, reason } => {
                self.add_history(HistoryEntry {
                    ts: unix_now() as f64,
                    device: self.snapshot().map(|state| state.name).unwrap_or_default(),
                    kind: "未知".to_string(),
                    ip: ip.clone(),
                    dur_s: 0.0,
                    bytes: 0,
                    result: "✗ 拒绝".to_string(),
                });
                self.add_event("warning", format!("连接被拒绝 · {} · {}", reason, ip));
            }
            RelayEvent::AudioStats { stats } => self.apply_audio_stats(stats),
            RelayEvent::AudioError { message } => {
                self.update_state(|state| state.audio_error = message.clone())
            }
            RelayEvent::AudioReady => self.update_state(|state| state.audio_error.clear()),
            RelayEvent::Fatal { code, message } => {
                self.update_state(|state| {
                    state.status = "fatal".to_string();
                    state.detail = message.clone();
                    state.fatal_code = code.clone();
                    state.peer_name.clear();
                });
                // 证书更换是可恢复操作而非故障：不进错误事件流，前端有专用恢复入口
                if code != "cert-changed" {
                    self.add_event("error", format!("{}: {}", code, message));
                }
            }
            RelayEvent::Stopped => {
                self.injector.release();
                self.update_state(|state| {
                    if state.status != "fatal" {
                        state.status = "stopped".to_string();
                        state.detail.clear();
                        state.talking = false;
                    }
                })
            }
        }
    }

    fn apply_audio_stats(&self, stats: SinkStats) {
        self.update_state(|state| {
            state.audio_frames = stats.frames;
            state.audio_bytes = stats.bytes;
            state.dropped_audio_frames = stats.dropped_frames;
        });
    }

    fn add_history(&self, entry: HistoryEntry) {
        let failed = self
            .history_lock
            .lock()
            .map(|_history_guard| history::append(&self.config_dir, entry).is_err())
            .unwrap_or(true);
        if failed {
            self.add_event("warning", "历史记录保存失败");
        }
    }

    fn add_event(&self, level: impl Into<String>, message: impl Into<String>) {
        self.update_state(|state| {
            state.events.insert(
                0,
                UiEvent {
                    at: unix_now(),
                    level: level.into(),
                    message: message.into(),
                },
            );
            state.events.truncate(MAX_EVENTS);
        });
    }

    /// 状态真正变化才推送给前端：音频输出失败时同一错误可能按帧重复上报
    /// （约 50 次/秒），值未变就不应触发全量 UI 重绘
    fn update_state<F>(&self, update: F)
    where
        F: FnOnce(&mut AppState),
    {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        let before = state.clone();
        update(&mut state);
        if *state == before {
            return;
        }
        let snapshot = state.clone();
        drop(state);
        self.emit(snapshot);
    }

    fn emit(&self, state: AppState) {
        if let Ok(app) = self.app.lock() {
            if let Some(app) = app.as_ref() {
                let _ = app.emit("app-state", state);
            }
        }
    }
}

impl Drop for AppController {
    fn drop(&mut self) {
        self.injector.release();
        self.stop_client();
    }
}

/// 配对载荷的 query 值编码（与前端 encodeURIComponent 等价的子集：组件编码）。
fn urlencode(value: &str) -> String {
    let mut out = String::new();
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

fn load_history_events(config_dir: &std::path::Path) -> Vec<UiEvent> {
    let Ok(entries) = history::load(config_dir) else {
        return Vec::new();
    };
    entries
        .into_iter()
        .take(MAX_EVENTS)
        .map(|entry| {
            let level = if entry.result.starts_with('✓') {
                "success"
            } else if entry.result.starts_with('✗') {
                "warning"
            } else {
                "info"
            };
            let message = [
                (!entry.device.is_empty()).then_some(entry.device),
                (!entry.kind.is_empty()).then_some(entry.kind),
                (!entry.ip.is_empty()).then_some(entry.ip),
                (!entry.result.is_empty()).then_some(entry.result),
            ]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(" · ");
            UiEvent {
                at: entry.ts as i64,
                level: level.to_string(),
                message,
            }
        })
        .collect()
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

#[tauri::command]
pub fn get_state(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.snapshot()
}

#[tauri::command]
pub fn connect(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.connect()?;
    state.snapshot()
}

#[tauri::command]
pub fn disconnect(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.disconnect();
    state.snapshot()
}

#[tauri::command]
#[allow(clippy::too_many_arguments)] // 与 controller.save_settings 同参，扁平传递
pub fn save_settings(
    state: State<'_, Arc<AppController>>,
    server: String,
    name: String,
    audio_device: String,
    dictation_enabled: bool,
    dictation_mode: String,
    auto_reconnect: bool,
) -> Result<AppState, CommandError> {
    state.save_settings(
        server,
        name,
        audio_device,
        dictation_enabled,
        dictation_mode,
        auto_reconnect,
    )
}

#[tauri::command]
pub fn set_server_password(
    state: State<'_, Arc<AppController>>,
    password: String,
) -> Result<AppState, CommandError> {
    state.set_server_password(password)
}

#[tauri::command]
pub fn clear_server_password(
    state: State<'_, Arc<AppController>>,
) -> Result<AppState, CommandError> {
    state.clear_server_password()
}

#[tauri::command]
pub fn get_pairing_payload(state: State<'_, Arc<AppController>>) -> Result<String, CommandError> {
    state.get_pairing_payload()
}

#[tauri::command]
pub fn list_audio_devices(
    state: State<'_, Arc<AppController>>,
) -> Result<Vec<String>, CommandError> {
    state.list_audio_devices()
}

#[tauri::command]
pub fn release_fn(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.manual_release();
    state.snapshot()
}

#[tauri::command]
pub fn clear_history(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.clear_history()
}

#[tauri::command]
pub fn clear_server_trust(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.clear_server_trust()
}

pub fn run_tauri() {
    let controller = match AppController::new() {
        Ok(controller) => Arc::new(controller),
        Err(error) => {
            eprintln!("{}: {}", error.code, error.message);
            return;
        }
    };
    let setup_controller = Arc::clone(&controller);
    let run = tauri::Builder::default()
        .manage(controller)
        .setup(move |app| {
            setup_controller.attach_app(app.handle().clone());
            // 物理 Fn 抬起即可手动放下悬空的模拟 Fn；失败仅提示（界面按钮仍可用）
            if let Err(message) = install_fn_release_tap(Arc::clone(&setup_controller.injector)) {
                setup_controller.add_event("warning", message);
            }
            if setup_controller
                .snapshot()
                .map(|state| !state.server.trim().is_empty())
                .unwrap_or(false)
            {
                let _ = setup_controller.connect();
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_state,
            connect,
            disconnect,
            save_settings,
            set_server_password,
            clear_server_password,
            get_pairing_payload,
            list_audio_devices,
            clear_history,
            clear_server_trust,
            release_fn
        ])
        .run(tauri::generate_context!());
    if let Err(error) = run {
        eprintln!("Tauri 运行失败: {error}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn history_is_restored_as_recent_ui_events() {
        // 测试目的：验证客户端重启后仍能恢复最近的桥接与拒绝记录。
        let dir = tempdir().unwrap();
        let entries = vec![
            HistoryEntry {
                ts: 1_700_000_002.0,
                device: "Mac B".to_string(),
                kind: "手机连接".to_string(),
                ip: "203.0.113.2".to_string(),
                result: "✗ 拒绝".to_string(),
                ..Default::default()
            },
            HistoryEntry {
                ts: 1_700_000_001.0,
                device: "Mac B".to_string(),
                kind: "手机连接".to_string(),
                ip: "203.0.113.1".to_string(),
                result: "✓ 已桥接".to_string(),
                ..Default::default()
            },
        ];
        std::fs::write(
            history::path(dir.path()),
            serde_json::to_vec(&entries).unwrap(),
        )
        .unwrap();

        let events = load_history_events(dir.path());
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].level, "warning");
        assert!(events[0].message.contains("203.0.113.2"));
        assert_eq!(events[1].level, "success");
    }

    #[test]
    fn repeated_audio_error_keeps_state_unchanged() {
        // 测试目的：update_state 的 emit 门依赖 PartialEq——同一错误重复写入
        // 不产生状态差异（否则音频失败时约 50 次/秒全量 UI 推送），清除能识别。
        let mut config = AppConfig::default();
        config.apply_defaults();
        let mut state = AppState::from_config(&config, false);
        let idle = state.clone();
        state.audio_error = "音频输出尚未启动".to_string();
        assert_ne!(state, idle);
        let failed = state.clone();
        state.audio_error = "音频输出尚未启动".to_string();
        assert_eq!(state, failed);
        state.audio_error.clear();
        assert_ne!(state, failed);
    }

    #[test]
    fn pairing_payload_encodes_query_values() {
        // 测试目的：配对载荷的密码/设备名必须按组件规则编码，& 等字符不得破坏结构。
        assert_eq!(urlencode("AB-CD 12"), "AB-CD%2012");
        assert_eq!(urlencode("a&b=c"), "a%26b%3Dc");
        assert_eq!(urlencode("书房"), "%E4%B9%A6%E6%88%BF");
    }
}
