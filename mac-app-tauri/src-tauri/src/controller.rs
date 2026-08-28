use crate::audio::{self, AudioSink, CpalSink, SinkStats};
use crate::config::{self, AppConfig};
use crate::history::{self, HistoryEntry};
use crate::identity;
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

#[derive(Debug, Clone, Serialize)]
pub struct UiEvent {
    pub at: i64,
    pub level: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct AppState {
    pub status: String,
    pub detail: String,
    pub server: String,
    pub name: String,
    pub audio_device: String,
    pub temp_secret: String,
    pub temp_exp: i64,
    pub temp_duration: i64,
    pub keep: bool,
    pub permanent_enabled: bool,
    pub peer_name: String,
    pub audio_frames: u64,
    pub audio_bytes: u64,
    pub dropped_audio_frames: u64,
    pub audio_error: String,
    pub events: Vec<UiEvent>,
}

impl AppState {
    fn from_config(
        config: &AppConfig,
        temp_secret: String,
        temp_exp: i64,
        permanent: bool,
    ) -> Self {
        Self {
            status: "stopped".to_string(),
            detail: String::new(),
            server: config.server.clone(),
            name: config.name.clone(),
            audio_device: config.audio_device.clone(),
            temp_secret,
            temp_exp,
            temp_duration: config.temp_duration,
            keep: config.keep,
            permanent_enabled: permanent,
            peer_name: String::new(),
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
    perm_hash: Mutex<String>,
    history_lock: Mutex<()>,
    lifecycle_lock: Mutex<()>,
    client: Mutex<Option<Arc<RelayClient>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
    app: Mutex<Option<AppHandle>>,
}

impl AppController {
    pub fn new() -> Result<Self, CommandError> {
        let config_dir = config::config_dir()
            .map_err(|error| CommandError::new("config-dir", error.to_string()))?;
        let mut config =
            config::load().map_err(|error| CommandError::new("config-read", error.to_string()))?;
        config.apply_defaults();
        if !config.keep {
            let had_persisted_temp = !config.temp_secret.is_empty() || config.temp_exp != 0;
            config.temp_secret.clear();
            config.temp_exp = 0;
            if had_persisted_temp {
                config::save(&config)
                    .map_err(|error| CommandError::new("config-write", error.to_string()))?;
            }
        }
        let now = unix_now();
        let (temp_secret, temp_exp, generated_temp) =
            if config.keep && !config.temp_secret.is_empty() && config.temp_exp > now {
                (config.temp_secret.clone(), config.temp_exp, false)
            } else {
                (secrets::generate_temp(), now + config.temp_duration, true)
            };
        if config.keep && generated_temp {
            config.temp_secret = temp_secret.clone();
            config.temp_exp = temp_exp;
            config::save(&config)
                .map_err(|error| CommandError::new("config-write", error.to_string()))?;
        }
        let permanent_secret = secrets::load_system(&config_dir);
        let permanent = permanent_secret.is_some();
        let perm_hash = permanent_secret
            .as_deref()
            .map(secrets::hash_of)
            .unwrap_or_default();
        let mut state = AppState::from_config(&config, temp_secret, temp_exp, permanent);
        state.events = load_history_events(&config_dir);
        Ok(Self {
            state: Mutex::new(state),
            config: Mutex::new(config),
            perm_hash: Mutex::new(perm_hash),
            history_lock: Mutex::new(()),
            lifecycle_lock: Mutex::new(()),
            client: Mutex::new(None),
            thread: Mutex::new(None),
            app: Mutex::new(None),
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
        let fingerprint = config::trusted_fingerprint(&config, &config.server);
        let registration = Registration {
            name: state.name.clone(),
            perm_hash: self
                .perm_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "永久秘密状态锁已损坏"))?
                .clone(),
            temp_hash: secrets::hash_of(&state.temp_secret),
            temp_exp: state.temp_exp,
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
            state.audio_error.clear();
            state.peer_name.clear();
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
        self.stop_client();
        self.update_state(|state| {
            state.status = "stopped".to_string();
            state.detail.clear();
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

    pub fn save_settings(
        self: &Arc<Self>,
        server: String,
        name: String,
        audio_device: String,
        temp_duration: i64,
        keep: bool,
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
        if !matches!(temp_duration, 600 | 3600 | 28_800 | 86_400) {
            return Err(CommandError::new("invalid-expiry", "临时秘密有效期无效"));
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
        let duration_changed = current_state.temp_duration != temp_duration;
        let temp_exp = if duration_changed {
            unix_now() + temp_duration
        } else {
            current_state.temp_exp
        };
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
            config.temp_duration = temp_duration;
            config.keep = keep;
            if keep {
                config.temp_secret = current_state.temp_secret.clone();
                config.temp_exp = temp_exp;
            } else {
                config.temp_secret.clear();
                config.temp_exp = 0;
            }
            config::save(&config)
                .map_err(|error| CommandError::new("config-write", error.to_string()))?;
        }
        self.update_state(|state| {
            state.server = server.clone();
            state.name = name.clone();
            state.audio_device = audio_device.clone();
            state.temp_duration = temp_duration;
            state.keep = keep;
            if duration_changed {
                state.temp_exp = temp_exp;
            }
        });
        if active {
            self.connect_inner()?;
        }
        self.snapshot()
    }

    pub fn regenerate_temp(&self) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        let current = self.snapshot()?;
        let duration = current.temp_duration;
        let secret = secrets::generate_temp();
        let exp = unix_now() + duration;
        self.persist_temp_if_needed(&secret, exp, current.keep)?;
        self.update_state(|state| {
            state.temp_secret = secret.clone();
            state.temp_exp = exp;
        });
        self.update_registration();
        self.add_event("info", "已重新生成临时秘密");
        self.snapshot()
    }

    pub fn set_permanent(&self, secret: String) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        let secret = secret.trim().to_string();
        let in_keychain = secrets::save_permanent(&self.config_dir, &SystemKeychain, &secret)
            .map_err(|error| CommandError::new("permanent-write", error.to_string()))?;
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
                .perm_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "永久秘密状态锁已损坏"))?;
            *hash = secrets::hash_of(&secret);
        }
        self.update_state(|state| state.permanent_enabled = true);
        self.update_registration();
        self.add_event("info", "永久秘密已启用");
        self.snapshot()
    }

    pub fn clear_permanent(&self) -> Result<AppState, CommandError> {
        let _lifecycle_guard = self
            .lifecycle_lock
            .lock()
            .map_err(|_| CommandError::new("lifecycle-lock", "连接生命周期锁已损坏"))?;
        secrets::clear_permanent(&self.config_dir, &SystemKeychain)
            .map_err(|error| CommandError::new("permanent-delete", error.to_string()))?;
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
                .perm_hash
                .lock()
                .map_err(|_| CommandError::new("state-lock", "永久秘密状态锁已损坏"))?;
            hash.clear();
        }
        self.update_state(|state| state.permanent_enabled = false);
        self.update_registration();
        self.add_event("info", "永久秘密已停用");
        self.snapshot()
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

    fn persist_temp_if_needed(
        &self,
        temp_secret: &str,
        temp_exp: i64,
        keep: bool,
    ) -> Result<(), CommandError> {
        let mut config = self
            .config
            .lock()
            .map_err(|_| CommandError::new("config-lock", "配置状态锁已损坏"))?;
        if keep {
            config.temp_secret = temp_secret.to_string();
            config.temp_exp = temp_exp;
        } else {
            config.temp_secret.clear();
            config.temp_exp = 0;
        }
        config::save(&config).map_err(|error| CommandError::new("config-write", error.to_string()))
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
        let (client, state, perm_hash) = match (
            self.client.lock().ok().and_then(|client| client.clone()),
            self.state.lock().ok().map(|state| state.clone()),
            self.perm_hash.lock().ok().map(|hash| hash.clone()),
        ) {
            (Some(client), Some(state), Some(perm_hash)) => (client, state, perm_hash),
            _ => return,
        };
        client.update_registration(Registration {
            name: state.name,
            perm_hash,
            temp_hash: secrets::hash_of(&state.temp_secret),
            temp_exp: state.temp_exp,
        });
    }

    fn handle_relay_event(&self, event: RelayEvent) {
        match event {
            RelayEvent::Connecting { server } => self.update_state(|state| {
                state.status = "connecting".to_string();
                state.detail = server;
            }),
            RelayEvent::Reconnecting { detail } => self.update_state(|state| {
                state.status = "reconnecting".to_string();
                state.detail = detail;
                state.peer_name.clear();
            }),
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
            RelayEvent::PeerOffline => self.update_state(|state| {
                state.status = "registered".to_string();
                state.detail = "等待手机连接".to_string();
                state.peer_name.clear();
            }),
            RelayEvent::AuthAccepted { ip, kind } => {
                let label = if kind == "perm" { "永久" } else { "临时" };
                self.add_history(HistoryEntry {
                    ts: unix_now() as f64,
                    device: self.snapshot().map(|state| state.name).unwrap_or_default(),
                    kind: label.to_string(),
                    ip: ip.clone(),
                    dur_s: 0.0,
                    bytes: 0,
                    result: "✓ 已桥接".to_string(),
                });
                self.add_event("success", format!("手机已连接 · {} · {}", label, ip));
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
            RelayEvent::Fatal { code, message } => {
                self.update_state(|state| {
                    state.status = "fatal".to_string();
                    state.detail = message.clone();
                    state.peer_name.clear();
                });
                self.add_event("error", format!("{}: {}", code, message));
            }
            RelayEvent::Stopped => self.update_state(|state| {
                if state.status != "fatal" {
                    state.status = "stopped".to_string();
                    state.detail.clear();
                }
            }),
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

    fn update_state<F>(&self, update: F)
    where
        F: FnOnce(&mut AppState),
    {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        update(&mut state);
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
        self.stop_client();
    }
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
pub fn save_settings(
    state: State<'_, Arc<AppController>>,
    server: String,
    name: String,
    audio_device: String,
    temp_duration: i64,
    keep: bool,
) -> Result<AppState, CommandError> {
    state.save_settings(server, name, audio_device, temp_duration, keep)
}

#[tauri::command]
pub fn regenerate_temp(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.regenerate_temp()
}

#[tauri::command]
pub fn set_permanent(
    state: State<'_, Arc<AppController>>,
    secret: String,
) -> Result<AppState, CommandError> {
    state.set_permanent(secret)
}

#[tauri::command]
pub fn clear_permanent(state: State<'_, Arc<AppController>>) -> Result<AppState, CommandError> {
    state.clear_permanent()
}

#[tauri::command]
pub fn list_audio_devices(
    state: State<'_, Arc<AppController>>,
) -> Result<Vec<String>, CommandError> {
    state.list_audio_devices()
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
            regenerate_temp,
            set_permanent,
            clear_permanent,
            list_audio_devices,
            clear_history,
            clear_server_trust
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
                kind: "临时".to_string(),
                ip: "203.0.113.2".to_string(),
                result: "✗ 拒绝".to_string(),
                ..Default::default()
            },
            HistoryEntry {
                ts: 1_700_000_001.0,
                device: "Mac B".to_string(),
                kind: "永久".to_string(),
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
}
