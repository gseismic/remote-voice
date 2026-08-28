use crate::config;
use crate::storage::{KeychainStore, SystemKeychain, DEVICE_KEYCHAIN_ACCOUNT};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum IdentityError {
    #[error("设备身份存储失败: {0}")]
    Io(#[from] std::io::Error),
    #[error("无法确定用户配置目录")]
    HomeMissing,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceIdentity {
    pub device_id: String,
    pub device_key: String,
}

pub fn load_or_create() -> Result<DeviceIdentity, IdentityError> {
    let dir = config::config_dir().map_err(|_| IdentityError::HomeMissing)?;
    load_or_create_at(&dir, &SystemKeychain)
}

pub fn load_or_create_at(
    config_dir: &Path,
    keychain: &dyn KeychainStore,
) -> Result<DeviceIdentity, IdentityError> {
    let keychain_value = keychain.get(DEVICE_KEYCHAIN_ACCOUNT);
    if let Some(identity) = keychain_value.as_deref().and_then(parse) {
        return Ok(identity);
    }
    let file = config_dir.join("device.json");
    if let Some(identity) = read_file(&file) {
        set_mode(config_dir, 0o700)?;
        set_mode(&file, 0o600)?;
        return Ok(identity);
    }

    let identity = new_identity();
    if keychain.set(DEVICE_KEYCHAIN_ACCOUNT, &encode(&identity)) {
        return Ok(identity);
    }
    if keychain_value.is_some() && !keychain.delete(DEVICE_KEYCHAIN_ACCOUNT) {
        return Err(IdentityError::Io(std::io::Error::other(
            "无法替换已有 Keychain 设备身份",
        )));
    }
    write_file(&file, &identity)?;
    Ok(identity)
}

pub fn parse(raw: &str) -> Option<DeviceIdentity> {
    let value: serde_json::Value = serde_json::from_str(raw).ok()?;
    let device_id = value.get("device_id")?.as_str()?.to_string();
    let device_key = value.get("device_key")?.as_str()?.to_ascii_lowercase();
    if !valid_device_id(&device_id) || !valid_device_key(&device_key) {
        return None;
    }
    Some(DeviceIdentity {
        device_id,
        device_key,
    })
}

fn encode(identity: &DeviceIdentity) -> String {
    serde_json::json!({
        "version": 1,
        "device_id": identity.device_id,
        "device_key": identity.device_key,
    })
    .to_string()
}

fn read_file(path: &Path) -> Option<DeviceIdentity> {
    fs::read_to_string(path).ok().and_then(|raw| parse(&raw))
}

fn write_file(path: &Path, identity: &DeviceIdentity) -> Result<(), IdentityError> {
    let dir = path.parent().ok_or_else(|| {
        IdentityError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "设备身份没有父目录",
        ))
    })?;
    fs::create_dir_all(dir)?;
    set_mode(dir, 0o700)?;
    let temp = dir.join(format!(".device-{}.tmp", std::process::id()));
    let mut options = OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(&temp)?;
        set_mode(&temp, 0o600)?;
        file.write_all(encode(identity).as_bytes())?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temp, path)?;
        Ok::<(), std::io::Error>(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result.map_err(IdentityError::Io)
}

fn new_identity() -> DeviceIdentity {
    let mut rng = rand::thread_rng();
    let mut id_bytes = [0_u8; 16];
    let mut key_bytes = [0_u8; 32];
    rng.fill(&mut id_bytes);
    rng.fill(&mut key_bytes);
    DeviceIdentity {
        device_id: format!("mac-{}", hex::encode(id_bytes)),
        device_key: hex::encode(key_bytes),
    }
}

fn valid_device_id(value: &str) -> bool {
    (8..=128).contains(&value.len())
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':'))
}

fn valid_device_key(value: &str) -> bool {
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
    use crate::storage::KeychainStore;
    use std::sync::Mutex;
    use tempfile::tempdir;

    #[derive(Default)]
    struct FakeKeychain {
        value: Mutex<Option<String>>,
        allow_set: bool,
    }

    impl KeychainStore for FakeKeychain {
        fn get(&self, _account: &str) -> Option<String> {
            self.value.lock().unwrap().clone()
        }

        fn set(&self, _account: &str, value: &str) -> bool {
            if !self.allow_set {
                return false;
            }
            *self.value.lock().unwrap() = Some(value.to_string());
            true
        }

        fn delete(&self, _account: &str) -> bool {
            self.value.lock().unwrap().take().is_some()
        }
    }

    #[test]
    fn invalid_identity_is_rejected() {
        assert!(parse(r#"{"device_id":"bad id","device_key":"aa"}"#).is_none());
    }

    #[test]
    fn fallback_identity_is_created_once() {
        let dir = tempdir().unwrap();
        let keychain = FakeKeychain {
            value: Mutex::new(None),
            allow_set: false,
        };
        let first = load_or_create_at(dir.path(), &keychain).unwrap();
        let second = load_or_create_at(dir.path(), &keychain).unwrap();
        assert_eq!(first, second);
        assert!(dir.path().join("device.json").exists());
    }
}
