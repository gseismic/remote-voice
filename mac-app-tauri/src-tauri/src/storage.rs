use std::process::{Command, Output, Stdio};
use std::thread::sleep;
use std::time::{Duration, Instant};

pub const KEYCHAIN_SERVICE: &str = "remote-voice";
pub const DEVICE_KEYCHAIN_ACCOUNT: &str = "device-identity";
pub const PERMANENT_KEYCHAIN_ACCOUNT: &str = "permanent-secret";
const SECURITY_TIMEOUT: Duration = Duration::from_secs(10);

/// Keychain 的最小接口；核心逻辑通过它注入 fake，避免测试依赖 macOS 命令行工具。
pub trait KeychainStore: Send + Sync {
    fn get(&self, account: &str) -> Option<String>;
    fn set(&self, account: &str, value: &str) -> bool;
    fn delete(&self, account: &str) -> bool;
}

#[derive(Debug, Clone, Copy, Default)]
pub struct SystemKeychain;

impl KeychainStore for SystemKeychain {
    fn get(&self, account: &str) -> Option<String> {
        let output = run_security(&[
            "find-generic-password".to_string(),
            "-s".to_string(),
            KEYCHAIN_SERVICE.to_string(),
            "-a".to_string(),
            account.to_string(),
            "-w".to_string(),
        ])?;
        if !output.status.success() {
            return None;
        }
        String::from_utf8(output.stdout)
            .ok()
            .map(|value| value.trim_end_matches(['\r', '\n']).to_string())
    }

    fn set(&self, account: &str, value: &str) -> bool {
        run_security(&[
            "add-generic-password".to_string(),
            "-s".to_string(),
            KEYCHAIN_SERVICE.to_string(),
            "-a".to_string(),
            account.to_string(),
            "-U".to_string(),
            "-w".to_string(),
            value.to_string(),
        ])
        .map(|output| output.status.success())
        .unwrap_or(false)
    }

    fn delete(&self, account: &str) -> bool {
        run_security(&[
            "delete-generic-password".to_string(),
            "-s".to_string(),
            KEYCHAIN_SERVICE.to_string(),
            "-a".to_string(),
            account.to_string(),
        ])
        .map(|output| output.status.success())
        .unwrap_or(false)
    }
}

/// 执行 Keychain CLI 并设置截止时间，避免安全工具或系统弹窗让连接命令永久阻塞。
fn run_security(args: &[String]) -> Option<Output> {
    let mut child = Command::new("security")
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;
    let deadline = Instant::now() + SECURITY_TIMEOUT;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return child.wait_with_output().ok(),
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
            Ok(None) => sleep(Duration::from_millis(50)),
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}
