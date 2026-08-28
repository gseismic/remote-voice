use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use thiserror::Error;

pub const DEFAULT_PORT: u16 = 9432;
pub const DEFAULT_AUDIO_DEVICE: &str = "BlackHole";
pub const DEFAULT_TEMP_DURATION: i64 = 8 * 60 * 60;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("配置文件读写失败: {0}")]
    Io(#[from] std::io::Error),
    #[error("配置文件格式错误: {0}")]
    Json(#[from] serde_json::Error),
    #[error("无法确定用户配置目录")]
    HomeMissing,
    #[error("服务器地址无效: {0}")]
    InvalidServer(String),
    #[error("服务器证书指纹无效")]
    InvalidFingerprint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AppConfig {
    pub server: String,
    pub name: String,
    pub audio_device: String,
    pub keep: bool,
    pub temp_secret: String,
    pub temp_exp: i64,
    pub temp_duration: i64,
    pub fingerprints: BTreeMap<String, String>,
    pub perm_in_keychain: bool,
    /// 只用于迁移 PLAN-008 的单值指纹；保存时不再写回旧字段。
    #[serde(rename = "fingerprint", skip_serializing)]
    pub legacy_fingerprint: String,
    /// 只用于读取旧配置；新客户端永远不使用全局 regkey。
    #[serde(rename = "regkey", skip_serializing)]
    pub legacy_regkey: String,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            server: String::new(),
            name: String::new(),
            audio_device: DEFAULT_AUDIO_DEVICE.to_string(),
            keep: false,
            temp_secret: String::new(),
            temp_exp: 0,
            temp_duration: DEFAULT_TEMP_DURATION,
            fingerprints: BTreeMap::new(),
            perm_in_keychain: false,
            legacy_fingerprint: String::new(),
            legacy_regkey: String::new(),
        }
    }
}

impl AppConfig {
    pub fn apply_defaults(&mut self) {
        if self.audio_device.trim().is_empty() {
            self.audio_device = DEFAULT_AUDIO_DEVICE.to_string();
        }
        if !matches!(self.temp_duration, 600 | 3600 | 28_800 | 86_400) {
            self.temp_duration = DEFAULT_TEMP_DURATION;
        }
    }
}

pub fn config_dir() -> Result<PathBuf, ConfigError> {
    dirs::home_dir()
        .map(|home| home.join(".config").join("remote-voice"))
        .ok_or(ConfigError::HomeMissing)
}

pub fn config_path() -> Result<PathBuf, ConfigError> {
    Ok(config_dir()?.join("config.json"))
}

pub fn load() -> Result<AppConfig, ConfigError> {
    let path = config_path()?;
    if !path.exists() {
        return Ok(AppConfig::default());
    }
    load_from(&path)
}

pub fn load_from(path: &Path) -> Result<AppConfig, ConfigError> {
    let content = fs::read_to_string(path)?;
    let mut config: AppConfig = serde_json::from_str(&content)?;
    config.apply_defaults();
    Ok(config)
}

pub fn save(config: &AppConfig) -> Result<(), ConfigError> {
    save_to(&config_path()?, config)
}

pub fn save_to(path: &Path, config: &AppConfig) -> Result<(), ConfigError> {
    let parent = path.parent().ok_or_else(|| {
        ConfigError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "配置文件没有父目录",
        ))
    })?;
    fs::create_dir_all(parent)?;
    set_mode(parent, 0o700)?;

    let tmp = parent.join(format!(
        ".config-{}-{}.tmp",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    ));
    let bytes = serde_json::to_vec_pretty(config)?;
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(&tmp)?;
        set_mode(&tmp, 0o600)?;
        file.write_all(&bytes)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&tmp, path)?;
        Ok::<(), std::io::Error>(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result.map_err(ConfigError::Io)
}

pub fn parse_server(raw: &str) -> Result<(String, u16), ConfigError> {
    let mut value = raw.trim();
    if let Some(rest) = value.strip_prefix("rv://") {
        value = rest;
    }
    value = value.split('?').next().unwrap_or(value).trim();
    if value.is_empty() || value.chars().any(char::is_whitespace) {
        return Err(ConfigError::InvalidServer("地址为空或包含空格".to_string()));
    }

    let (host, port) = if let Some(rest) = value.strip_prefix('[') {
        let end = rest
            .find(']')
            .ok_or_else(|| ConfigError::InvalidServer("IPv6 地址缺少 ]".to_string()))?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        let port = if suffix.is_empty() {
            DEFAULT_PORT
        } else if let Some(port) = suffix.strip_prefix(':') {
            parse_port(port)?
        } else {
            return Err(ConfigError::InvalidServer("端口格式错误".to_string()));
        };
        (host.to_string(), port)
    } else {
        let colon_count = value.matches(':').count();
        if colon_count == 1 {
            let (host, port) = value
                .rsplit_once(':')
                .ok_or_else(|| ConfigError::InvalidServer("端口格式错误".to_string()))?;
            (host.to_string(), parse_port(port)?)
        } else if colon_count > 1 {
            // 未加括号的 IPv6 只接受默认端口，带端口时应使用 [addr]:port。
            (value.to_string(), DEFAULT_PORT)
        } else {
            (value.to_string(), DEFAULT_PORT)
        }
    };

    if host.is_empty() || host.contains('/') || host.contains('?') {
        return Err(ConfigError::InvalidServer("主机名格式错误".to_string()));
    }
    Ok((host, port))
}

fn parse_port(raw: &str) -> Result<u16, ConfigError> {
    raw.parse::<u16>()
        .ok()
        .filter(|port| *port > 0)
        .ok_or_else(|| ConfigError::InvalidServer("端口必须在 1-65535 之间".to_string()))
}

pub fn server_key(raw: &str) -> Result<String, ConfigError> {
    let (host, port) = parse_server(raw)?;
    if host.contains(':') {
        Ok(format!("[{}]:{}", host.to_lowercase(), port))
    } else {
        Ok(format!("{}:{}", host.to_lowercase(), port))
    }
}

pub fn trusted_fingerprint(config: &AppConfig, server: &str) -> String {
    let key = match server_key(server) {
        Ok(key) => key,
        Err(_) => return String::new(),
    };
    if let Some(value) = config.fingerprints.get(&key) {
        let normalized = normalize_fingerprint(value);
        if valid_fingerprint(&normalized) {
            return normalized;
        }
    }
    if !config.legacy_fingerprint.is_empty()
        && server_key(&config.server).ok().as_deref() == Some(key.as_str())
    {
        let normalized = normalize_fingerprint(&config.legacy_fingerprint);
        if valid_fingerprint(&normalized) {
            return normalized;
        }
    }
    String::new()
}

pub fn remember_fingerprint(
    config: &mut AppConfig,
    server: &str,
    fingerprint: &str,
) -> Result<(), ConfigError> {
    let normalized = normalize_fingerprint(fingerprint);
    if !valid_fingerprint(&normalized) {
        return Err(ConfigError::InvalidFingerprint);
    }
    let key = server_key(server)?;
    config.fingerprints.insert(key, normalized);
    Ok(())
}

/// 清除指定服务器的本地 TOFU 记录；调用方必须让用户明确触发此危险操作。
pub fn clear_fingerprint(config: &mut AppConfig, server: &str) -> Result<bool, ConfigError> {
    let key = server_key(server)?;
    let mut removed = config.fingerprints.remove(&key).is_some();
    if server_key(&config.server).ok().as_deref() == Some(key.as_str())
        && !config.legacy_fingerprint.is_empty()
    {
        config.legacy_fingerprint.clear();
        removed = true;
    }
    Ok(removed)
}

pub fn normalize_fingerprint(value: &str) -> String {
    value
        .chars()
        .filter(|c| !c.is_whitespace() && *c != ':' && *c != '-')
        .flat_map(char::to_lowercase)
        .collect()
}

pub fn valid_fingerprint(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn set_mode(path: &Path, mode: u32) -> Result<(), std::io::Error> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    #[cfg(not(unix))]
    {
        let _ = (path, mode);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn server_keys_normalize_default_and_ipv6_ports() {
        assert_eq!(server_key("Relay.Example").unwrap(), "relay.example:9432");
        assert_eq!(server_key("[::1]:9440").unwrap(), "[::1]:9440");
        assert_eq!(
            server_key("rv://Relay.Example:9432?f=old").unwrap(),
            "relay.example:9432"
        );
    }

    #[test]
    fn config_roundtrip_is_private_and_keeps_tofu_by_server() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("config.json");
        let mut config = AppConfig {
            server: "relay.example:9432".to_string(),
            ..Default::default()
        };
        remember_fingerprint(&mut config, "relay.example", &"a".repeat(64)).unwrap();
        save_to(&path, &config).unwrap();
        let loaded = load_from(&path).unwrap();
        assert_eq!(
            trusted_fingerprint(&loaded, "relay.example:9432"),
            "a".repeat(64)
        );
        assert_eq!(trusted_fingerprint(&loaded, "other.example:9432"), "");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn clear_fingerprint_only_removes_the_selected_server() {
        let mut config = AppConfig {
            server: "relay.example:9432".to_string(),
            ..Default::default()
        };
        remember_fingerprint(&mut config, "relay.example", &"a".repeat(64)).unwrap();
        remember_fingerprint(&mut config, "other.example", &"b".repeat(64)).unwrap();

        assert!(clear_fingerprint(&mut config, "relay.example").unwrap());
        assert_eq!(trusted_fingerprint(&config, "relay.example"), "");
        assert_eq!(
            trusted_fingerprint(&config, "other.example"),
            "b".repeat(64)
        );
        assert!(!clear_fingerprint(&mut config, "relay.example").unwrap());
    }
}
