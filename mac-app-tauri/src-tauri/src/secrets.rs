use crate::storage::{KeychainStore, SystemKeychain, PERMANENT_KEYCHAIN_ACCOUNT};
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;
use thiserror::Error;

const ALPHABET: &[u8] = b"23456789ABCDEFGHJKMNPQRSTUVWXYZ";

#[derive(Debug, Error)]
pub enum SecretError {
    #[error("秘密存储失败: {0}")]
    Io(#[from] std::io::Error),
    #[error("永久秘密至少需要 12 位")]
    TooShort,
    #[error("无法替换已有 Keychain 永久秘密")]
    KeychainUnavailable,
    #[error("无法删除 Keychain 永久秘密")]
    KeychainDeleteUnavailable,
}

pub fn normalize(raw: &str) -> String {
    raw.chars()
        .filter(|c| *c != ' ' && *c != '-')
        .flat_map(char::to_uppercase)
        .collect()
}

pub fn hash_of(raw: &str) -> String {
    hex::encode(Sha256::digest(normalize(raw).as_bytes()))
}

pub fn generate_temp() -> String {
    let mut rng = rand::thread_rng();
    let pick = |rng: &mut rand::rngs::ThreadRng| -> char {
        use rand::Rng;
        ALPHABET[rng.gen_range(0..ALPHABET.len())] as char
    };
    format!(
        "{}{}{}{}-{}{}{}{}",
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng),
        pick(&mut rng)
    )
}

pub fn valid_permanent(raw: &str) -> bool {
    normalize(raw).chars().count() >= 12
}

pub fn load_permanent(config_dir: &Path, keychain: &dyn KeychainStore) -> Option<String> {
    keychain
        .get(PERMANENT_KEYCHAIN_ACCOUNT)
        .filter(|secret| valid_permanent(secret))
        .or_else(|| {
            fs::read_to_string(config_dir.join("perm.secret"))
                .ok()
                .map(|s| s.trim().to_string())
        })
        .filter(|secret| valid_permanent(secret))
}

pub fn save_permanent(
    config_dir: &Path,
    keychain: &dyn KeychainStore,
    raw: &str,
) -> Result<bool, SecretError> {
    let secret = raw.trim();
    if !valid_permanent(secret) {
        return Err(SecretError::TooShort);
    }
    let had_keychain_value = keychain.get(PERMANENT_KEYCHAIN_ACCOUNT).is_some();
    if keychain.set(PERMANENT_KEYCHAIN_ACCOUNT, secret) {
        remove_fallback(config_dir)?;
        return Ok(true);
    }
    if had_keychain_value && !keychain.delete(PERMANENT_KEYCHAIN_ACCOUNT) {
        return Err(SecretError::KeychainUnavailable);
    }
    write_fallback(config_dir, secret)?;
    Ok(false)
}

pub fn clear_permanent(config_dir: &Path, keychain: &dyn KeychainStore) -> Result<(), SecretError> {
    let had_keychain_value = keychain.get(PERMANENT_KEYCHAIN_ACCOUNT).is_some();
    if had_keychain_value && !keychain.delete(PERMANENT_KEYCHAIN_ACCOUNT) {
        return Err(SecretError::KeychainDeleteUnavailable);
    }
    remove_fallback(config_dir)
}

fn write_fallback(config_dir: &Path, secret: &str) -> Result<(), SecretError> {
    fs::create_dir_all(config_dir)?;
    set_mode(config_dir, 0o700)?;
    let path = config_dir.join("perm.secret");
    let mut options = OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&path)?;
    set_mode(&path, 0o600)?;
    file.write_all(secret.as_bytes())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    Ok(())
}

fn remove_fallback(config_dir: &Path) -> Result<(), SecretError> {
    let path = config_dir.join("perm.secret");
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(SecretError::Io(error)),
    }
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

pub fn load_system(config_dir: &Path) -> Option<String> {
    load_permanent(config_dir, &SystemKeychain)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::KeychainStore;
    use std::sync::Mutex;
    use tempfile::tempdir;

    struct FakeKeychain {
        value: Mutex<Option<String>>,
        allow_set: bool,
        allow_delete: bool,
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
            if !self.allow_delete {
                return false;
            }
            self.value.lock().unwrap().take().is_some()
        }
    }

    #[test]
    fn normalize_and_hash_are_stable() {
        assert_eq!(normalize(" ab-cd "), "ABCD");
        assert_eq!(hash_of("ab-cd"), hash_of("AB CD"));
    }

    #[test]
    fn fallback_secret_is_private_and_reloadable() {
        let dir = tempdir().unwrap();
        let keychain = FakeKeychain {
            value: Mutex::new(None),
            allow_set: false,
            allow_delete: true,
        };
        // 测试目的：Keychain 不可用时，永久秘密仍能以私有文件保存并重新读取。
        assert!(!save_permanent(dir.path(), &keychain, "a-long-secret1").unwrap());
        assert_eq!(
            load_permanent(dir.path(), &keychain).as_deref(),
            Some("a-long-secret1")
        );
        clear_permanent(dir.path(), &keychain).unwrap();
        assert!(load_permanent(dir.path(), &keychain).is_none());
    }

    #[test]
    fn clear_reports_keychain_delete_failure_without_removing_fallback() {
        let dir = tempdir().unwrap();
        let keychain = FakeKeychain {
            value: Mutex::new(Some("a-long-secret1".to_string())),
            allow_set: false,
            allow_delete: false,
        };
        write_fallback(dir.path(), "fallback-secret1").unwrap();
        assert!(matches!(
            clear_permanent(dir.path(), &keychain),
            Err(SecretError::KeychainDeleteUnavailable)
        ));
        assert!(dir.path().join("perm.secret").exists());
    }
}
