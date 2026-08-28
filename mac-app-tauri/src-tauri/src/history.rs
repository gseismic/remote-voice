use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use thiserror::Error;

const MAX_ENTRIES: usize = 200;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct HistoryEntry {
    pub ts: f64,
    pub device: String,
    pub kind: String,
    pub ip: String,
    pub dur_s: f64,
    pub bytes: u64,
    pub result: String,
}

impl Default for HistoryEntry {
    fn default() -> Self {
        Self {
            ts: 0.0,
            device: String::new(),
            kind: String::new(),
            ip: String::new(),
            dur_s: 0.0,
            bytes: 0,
            result: String::new(),
        }
    }
}

#[derive(Debug, Error)]
pub enum HistoryError {
    #[error("历史记录读写失败: {0}")]
    Io(#[from] std::io::Error),
    #[error("历史记录格式错误: {0}")]
    Json(#[from] serde_json::Error),
}

pub fn path(config_dir: &Path) -> PathBuf {
    config_dir.join("history.json")
}

pub fn load(config_dir: &Path) -> Result<Vec<HistoryEntry>, HistoryError> {
    let file = path(config_dir);
    if !file.exists() {
        return Ok(Vec::new());
    }
    let content = fs::read_to_string(file)?;
    let mut entries: Vec<HistoryEntry> = serde_json::from_str(&content)?;
    entries.truncate(MAX_ENTRIES);
    Ok(entries)
}

pub fn append(config_dir: &Path, entry: HistoryEntry) -> Result<Vec<HistoryEntry>, HistoryError> {
    // 历史文件损坏时停止写入，避免一次新事件静默覆盖已有数据。
    let mut entries = load(config_dir)?;
    entries.insert(0, entry);
    entries.truncate(MAX_ENTRIES);
    save(config_dir, &entries)?;
    Ok(entries)
}

pub fn clear(config_dir: &Path) -> Result<(), HistoryError> {
    save(config_dir, &[])
}

fn save(config_dir: &Path, entries: &[HistoryEntry]) -> Result<(), HistoryError> {
    fs::create_dir_all(config_dir)?;
    set_mode(config_dir, 0o700)?;
    let file = path(config_dir);
    let temp = config_dir.join(format!(".history-{}.tmp", std::process::id()));
    let bytes = serde_json::to_vec_pretty(entries)?;
    let mut options = OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut output = options.open(&temp)?;
        set_mode(&temp, 0o600)?;
        output.write_all(&bytes)?;
        output.write_all(b"\n")?;
        output.sync_all()?;
        fs::rename(&temp, file)?;
        Ok::<(), std::io::Error>(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result.map_err(HistoryError::Io)
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
    fn append_reports_corrupt_history_instead_of_overwriting_it() {
        let dir = tempdir().unwrap();
        fs::write(path(dir.path()), b"not-json").unwrap();
        let error = append(dir.path(), HistoryEntry::default()).unwrap_err();
        assert!(matches!(error, HistoryError::Json(_)));
        assert_eq!(fs::read_to_string(path(dir.path())).unwrap(), "not-json");
    }
}
