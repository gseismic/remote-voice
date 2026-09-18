//! 语音输入按键注入：把手机 PTT 状态映射为 macOS 系统级 Fn（Globe）键，
//! 使任意输入框可经系统语音输入（听写）把链路音频转成文字。
//!
//! 模式（设计文档 docs/design/ptt-dictation-20260918-holdfn.md §2）：
//! - hold（默认）：按下=Fn keyDown 保持，松开=keyUp；
//! - double：按下/松开各模拟一次"双击 Fn"（对应系统听写快捷键），兜底方案。
//!
//! 非 macOS 平台全部空实现，保证 Linux 构建与测试可运行；
//! macOS 真机行为（含辅助功能权限）列入人工验证项。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InjectMode {
    Hold,
    Double,
}

impl InjectMode {
    pub fn parse(value: &str) -> Self {
        if value == MODE_DOUBLE {
            Self::Double
        } else {
            Self::Hold
        }
    }
}

pub const MODE_DOUBLE: &str = "double";

/// 线程安全注入器。hold 模式用原子状态防重复 down / 悬空 up；
/// double 模式每次起独立线程敲击（幂等，无共享状态）。
pub struct KeyInjector {
    /// 仅 macOS hold 模式读写；非 macOS 平台为占位
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pressed: std::sync::atomic::AtomicBool,
}

impl Default for KeyInjector {
    fn default() -> Self {
        Self::new()
    }
}

impl KeyInjector {
    pub fn new() -> Self {
        Self {
            pressed: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// 手机说话状态变化。返回 Err 表示需要用户介入（辅助功能权限等）。
    pub fn set_talking(&self, on: bool, mode: InjectMode) -> Result<(), String> {
        self.set_talking_impl(on, mode)
    }

    /// 强制释放（对端掉线/配置变更/应用退出），防止 Fn 悬空。
    pub fn release(&self) {
        self.release_impl()
    }
}

#[cfg(not(target_os = "macos"))]
impl KeyInjector {
    fn set_talking_impl(&self, _on: bool, _mode: InjectMode) -> Result<(), String> {
        Ok(())
    }

    fn release_impl(&self) {}
}

#[cfg(target_os = "macos")]
mod ffi {
    use std::os::raw::c_void;

    /// kVK_Function（Fn/Globe 键的虚拟键码）
    pub const K_VK_FUNCTION: u16 = 63;
    /// kCGEventFlagMaskSecondaryFn（NX_SECONDARYFNMASK）
    pub const FN_FLAG: u64 = 0x20;
    /// kCGHIDEventTap
    pub const K_CG_HID_EVENT_TAP: u32 = 0;

    extern "C" {
        fn CGEventCreateKeyboardEvent(
            source: *mut c_void,
            key_code: u16,
            key_down: bool,
        ) -> *mut c_void;
        fn CGEventSetFlags(event: *mut c_void, flags: u64);
        fn CGEventPost(tap: u32, event: *mut c_void);
        fn CFRelease(cf: *mut c_void);
        fn AXIsProcessTrusted() -> bool;
    }

    pub fn accessibility_trusted() -> bool {
        unsafe { AXIsProcessTrusted() }
    }

    pub fn post_fn_key(key_down: bool) -> Result<(), String> {
        unsafe {
            let event = CGEventCreateKeyboardEvent(std::ptr::null_mut(), K_VK_FUNCTION, key_down);
            if event.is_null() {
                return Err("创建 Fn 按键事件失败".to_string());
            }
            CGEventSetFlags(event, FN_FLAG);
            CGEventPost(K_CG_HID_EVENT_TAP, event);
            CFRelease(event);
        }
        Ok(())
    }
}

#[cfg(target_os = "macos")]
impl KeyInjector {
    const PERMISSION_HINT: &str = "模拟 Fn 需要辅助功能权限：系统设置 → 隐私与安全性 → 辅助功能，勾选本应用后重试";

    fn set_talking_impl(&self, on: bool, mode: InjectMode) -> Result<(), String> {
        use std::sync::atomic::Ordering;
        if !ffi::accessibility_trusted() {
            return Err(Self::PERMISSION_HINT.to_string());
        }
        match mode {
            InjectMode::Hold => {
                if on {
                    // 防重复 down：已按住时忽略
                    if !self.pressed.swap(true, Ordering::AcqRel) {
                        ffi::post_fn_key(true)?;
                    }
                } else if self.pressed.swap(false, Ordering::AcqRel) {
                    ffi::post_fn_key(false)?;
                }
                Ok(())
            }
            InjectMode::Double => {
                // 双击模拟耗时约 300ms，放独立线程避免阻塞 relay 事件循环；
                // 权限已同步校验，线程内失败仅记录日志（下一条状态会重新触发）。
                std::thread::Builder::new()
                    .name("dictation-tap".to_string())
                    .spawn(move || {
                        if let Err(error) = double_tap() {
                            eprintln!("语音输入按键模拟失败: {error}");
                        }
                    })
                    .map_err(|error| format!("无法启动按键模拟线程: {error}"))?;
                Ok(())
            }
        }
    }

    fn release_impl(&self) {
        use std::sync::atomic::Ordering;
        if self.pressed.swap(false, Ordering::AcqRel) {
            let _ = ffi::post_fn_key(false);
        }
    }
}

#[cfg(target_os = "macos")]
fn double_tap() -> Result<(), String> {
    ffi::post_fn_key(true)?;
    std::thread::sleep(std::time::Duration::from_millis(30));
    ffi::post_fn_key(false)?;
    std::thread::sleep(std::time::Duration::from_millis(120));
    ffi::post_fn_key(true)?;
    std::thread::sleep(std::time::Duration::from_millis(30));
    ffi::post_fn_key(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mode_parse_and_stub_are_safe() {
        // 测试目的：模式解析收敛 + 非 macOS stub 在真机上可直接验证调用安全。
        assert_eq!(InjectMode::parse("hold"), InjectMode::Hold);
        assert_eq!(InjectMode::parse("double"), InjectMode::Double);
        assert_eq!(InjectMode::parse("unknown"), InjectMode::Hold);
        let injector = KeyInjector::new();
        injector.set_talking(true, InjectMode::Hold).unwrap();
        injector.set_talking(true, InjectMode::Hold).unwrap();
        injector.set_talking(false, InjectMode::Double).unwrap();
        injector.release();
    }
}
