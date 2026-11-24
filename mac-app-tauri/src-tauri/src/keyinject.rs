//! 语音输入按键注入：把手机 PTT 状态映射为 macOS 系统级 Fn（Globe）键，
//! 使任意输入框可经系统语音输入（听写）把链路音频转成文字。
//!
//! 模式（设计文档 docs/design/ptt-dictation-20260918-holdfn.md §2）：
//! - hold（默认）：按下时设置 Fn 修饰位，松开时清除；
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

/// 读取发送按键所需的辅助功能权限，供 Mac 界面提示。
pub fn accessibility_trusted() -> bool {
    #[cfg(target_os = "macos")]
    {
        ffi::accessibility_trusted()
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

/// 线程安全注入器。hold 模式串行更新按键状态与事件，double 模式在线程中完成敲击。
pub struct KeyInjector {
    #[cfg(any(target_os = "macos", test))]
    pressed: std::sync::Mutex<bool>,
}

impl Default for KeyInjector {
    fn default() -> Self {
        Self::new()
    }
}

impl KeyInjector {
    pub fn new() -> Self {
        Self {
            #[cfg(any(target_os = "macos", test))]
            pressed: std::sync::Mutex::new(false),
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

    #[cfg(any(target_os = "macos", test))]
    fn set_hold(
        &self,
        on: bool,
        post: impl FnOnce(bool) -> Result<(), String>,
    ) -> Result<(), String> {
        let mut pressed = self.pressed.lock().map_err(|_| "Fn 联动状态不可用")?;
        if *pressed != on {
            post(on)?;
            *pressed = on;
        }
        Ok(())
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
    pub const FN_FLAG: u64 = 0x0080_0000;
    /// kCGHIDEventTap
    pub const K_CG_HID_EVENT_TAP: u32 = 0;
    /// 修饰键（包括 Fn）用 flagsChanged 表示按下与释放。
    pub const K_CG_EVENT_FLAGS_CHANGED: u32 = 12;
    /// kCGKeyboardEventKeycode（CGEventField）
    pub const K_KEYBOARD_EVENT_KEYCODE: u32 = 9;
    /// kCGEventSourceUserData，供物理 Fn 监听器区分本程序发出的事件。
    const K_EVENT_SOURCE_USER_DATA: u32 = 42;
    const FN_EVENT_MARKER: i64 = 0x5256_464e;
    const K_HID_SYSTEM_STATE: i32 = 1;
    /// kCGHeadInsertEventTap / kCGEventTapOptionListenOnly
    pub const K_HEAD_INSERT: u32 = 0;
    pub const K_TAP_LISTEN_ONLY: u32 = 1;

    pub type TapCallback = extern "C" fn(*mut c_void, u32, *mut c_void, *mut c_void) -> *mut c_void;

    extern "C" {
        fn CGEventCreateKeyboardEvent(
            source: *mut c_void,
            key_code: u16,
            key_down: bool,
        ) -> *mut c_void;
        fn CGEventSetType(event: *mut c_void, event_type: u32);
        fn CGEventGetFlags(event: *mut c_void) -> u64;
        fn CGEventSetFlags(event: *mut c_void, flags: u64);
        fn CGEventSourceFlagsState(state_id: i32) -> u64;
        fn CGEventPost(tap: u32, event: *mut c_void);
        fn CGEventGetIntegerValueField(event: *mut c_void, field: u32) -> i64;
        fn CGEventSetIntegerValueField(event: *mut c_void, field: u32, value: i64);
        fn CGEventTapCreate(
            tap: u32,
            place: u32,
            options: u32,
            event_mask: u64,
            callback: TapCallback,
            user_info: *mut c_void,
        ) -> *mut c_void;
        fn CGEventTapEnable(tap: *mut c_void, enable: bool);
        fn CFMachPortCreateRunLoopSource(
            allocator: *mut c_void,
            port: *mut c_void,
            order: i64,
        ) -> *mut c_void;
        fn CFRunLoopAddSource(rl: *mut c_void, source: *mut c_void, mode: *mut c_void);
        fn CFRunLoopGetCurrent() -> *mut c_void;
        fn CFRunLoopRun();
        fn CFRelease(cf: *mut c_void);
        fn AXIsProcessTrusted() -> bool;
        static kCFRunLoopCommonModes: *mut c_void;
    }

    pub fn accessibility_trusted() -> bool {
        unsafe { AXIsProcessTrusted() }
    }

    pub fn post_fn_key(key_down: bool) -> Result<(), String> {
        let flags = unsafe { CGEventSourceFlagsState(K_HID_SYSTEM_STATE) };
        let event = create_fn_event(key_down, flags)?;
        unsafe {
            CGEventPost(K_CG_HID_EVENT_TAP, event);
            CFRelease(event);
        }
        Ok(())
    }

    fn create_fn_event(key_down: bool, flags: u64) -> Result<*mut c_void, String> {
        unsafe {
            let event = CGEventCreateKeyboardEvent(std::ptr::null_mut(), K_VK_FUNCTION, key_down);
            if event.is_null() {
                return Err("创建 Fn 按键事件失败".to_string());
            }
            let flags = if key_down {
                flags | FN_FLAG
            } else {
                flags & !FN_FLAG
            };
            CGEventSetType(event, K_CG_EVENT_FLAGS_CHANGED);
            CGEventSetFlags(event, flags);
            CGEventSetIntegerValueField(event, K_EVENT_SOURCE_USER_DATA, FN_EVENT_MARKER);
            Ok(event)
        }
    }

    /// 监听物理 Fn 键抬起事件；tap 在专用线程的 CFRunLoop 上运行。
    /// 返回 Err 表示 tap 创建失败（多为辅助功能权限未授予）。
    pub fn spawn_fn_keyup_listener(
        injector: std::sync::Arc<crate::keyinject::KeyInjector>,
    ) -> Result<(), String> {
        unsafe {
            let user_info = std::sync::Arc::into_raw(injector) as *mut c_void;
            // Fn 使用 flagsChanged；回调区分物理按键松开和本应用注入的事件。
            let tap = CGEventTapCreate(
                K_CG_HID_EVENT_TAP,
                K_HEAD_INSERT,
                K_TAP_LISTEN_ONLY,
                1_u64 << K_CG_EVENT_FLAGS_CHANGED,
                fn_keyup_tap_callback,
                user_info,
            );
            if tap.is_null() {
                // 失败路径回收 Arc，避免泄漏
                drop(std::sync::Arc::from_raw(
                    user_info as *const crate::keyinject::KeyInjector,
                ));
                return Err(
                    "无法监听物理 Fn 键（需要辅助功能权限）：手动解除请用界面的「放下 Fn」按钮"
                        .to_string(),
                );
            }
            let source = CFMachPortCreateRunLoopSource(std::ptr::null_mut(), tap, 0);
            if source.is_null() {
                CFRelease(tap);
                drop(std::sync::Arc::from_raw(
                    user_info as *const crate::keyinject::KeyInjector,
                ));
                return Err("创建 Fn 监听 runloop source 失败".to_string());
            }
            // 裸指针不是 Send，转 usize 传入线程再还原；tap/source 生命周期为进程级，
            // 不会释放，线程内还原是安全的
            let source_addr = source as usize;
            let tap_addr = tap as usize;
            std::thread::Builder::new()
                .name("fn-keyup-tap".to_string())
                .spawn(move || {
                    let source = source_addr as *mut c_void;
                    let tap = tap_addr as *mut c_void;
                    let rl = CFRunLoopGetCurrent();
                    CFRunLoopAddSource(rl, source, kCFRunLoopCommonModes);
                    CGEventTapEnable(tap, true);
                    CFRunLoopRun();
                })
                .map_err(|error| format!("无法启动 Fn 监听线程: {error}"))?;
        }
        Ok(())
    }

    extern "C" fn fn_keyup_tap_callback(
        _proxy: *mut c_void,
        tap_type: u32,
        event: *mut c_void,
        user_info: *mut c_void,
    ) -> *mut c_void {
        if tap_type == K_CG_EVENT_FLAGS_CHANGED && !event.is_null() && !user_info.is_null() {
            unsafe {
                let key_code = CGEventGetIntegerValueField(event, K_KEYBOARD_EVENT_KEYCODE);
                let user_data = CGEventGetIntegerValueField(event, K_EVENT_SOURCE_USER_DATA);
                if key_code == i64::from(K_VK_FUNCTION)
                    && user_data != FN_EVENT_MARKER
                    && CGEventGetFlags(event) & FN_FLAG == 0
                {
                    let injector = &*(user_info as *const crate::keyinject::KeyInjector);
                    injector.release();
                }
            }
        }
        event
    }

    #[cfg(test)]
    #[test]
    fn mac_fn_modifier_events_hold_and_release() {
        // 检查 Quartz 事件对象；测试不把按键发送到桌面。
        extern "C" {
            fn CGEventGetType(event: *mut c_void) -> u32;
        }
        const SHIFT_FLAG: u64 = 0x0002_0000;
        for down in [true, false] {
            let event = create_fn_event(down, SHIFT_FLAG | if down { 0 } else { FN_FLAG }).unwrap();
            unsafe {
                let event_type = CGEventGetType(event);
                let flags = CGEventGetFlags(event);
                let key = CGEventGetIntegerValueField(event, K_KEYBOARD_EVENT_KEYCODE);
                let marker = CGEventGetIntegerValueField(event, K_EVENT_SOURCE_USER_DATA);
                CFRelease(event);
                assert_eq!(event_type, K_CG_EVENT_FLAGS_CHANGED);
                assert_eq!(key, i64::from(K_VK_FUNCTION));
                assert_eq!(marker, FN_EVENT_MARKER);
                assert_eq!(flags, SHIFT_FLAG | if down { FN_FLAG } else { 0 });
            }
        }
    }
}

#[cfg(target_os = "macos")]
impl KeyInjector {
    const PERMISSION_HINT: &str =
        "模拟 Fn 需要辅助功能权限：系统设置 → 隐私与安全性 → 辅助功能，勾选本应用后重试";

    fn set_talking_impl(&self, on: bool, mode: InjectMode) -> Result<(), String> {
        match mode {
            InjectMode::Hold => self.set_hold(on, |down| {
                if down && !accessibility_trusted() {
                    return Err(Self::PERMISSION_HINT.to_string());
                }
                ffi::post_fn_key(down)
            }),
            InjectMode::Double => {
                if !accessibility_trusted() {
                    return Err(Self::PERMISSION_HINT.to_string());
                }
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
        let _ = self.set_hold(false, ffi::post_fn_key);
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

/// 安装物理 Fn 键监听（应用启动时调用一次）：悬空状态下按一次物理 Fn 即放下。
/// 非 macOS 平台为空实现。
pub fn install_fn_release_tap(injector: std::sync::Arc<KeyInjector>) -> Result<(), String> {
    install_fn_release_tap_impl(injector)
}

#[cfg(not(target_os = "macos"))]
fn install_fn_release_tap_impl(_injector: std::sync::Arc<KeyInjector>) -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "macos")]
fn install_fn_release_tap_impl(injector: std::sync::Arc<KeyInjector>) -> Result<(), String> {
    ffi::spawn_fn_keyup_listener(injector)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hold_tracks_posted_events_and_retries_failures() {
        // 重复状态不发事件，未成功的按下或释放可再次尝试。
        let injector = KeyInjector::new();
        let mut events = Vec::new();
        let mut post = |down| {
            events.push(down);
            Ok(())
        };
        injector.set_hold(false, &mut post).unwrap();
        assert!(injector.set_hold(true, |_| Err("按下失败".into())).is_err());
        injector.set_hold(true, &mut post).unwrap();
        injector.set_hold(true, &mut post).unwrap();
        assert!(injector
            .set_hold(false, |_| Err("释放失败".into()))
            .is_err());
        injector.set_hold(false, &mut post).unwrap();
        injector.set_hold(false, &mut post).unwrap();
        assert_eq!(events, [true, false]);
    }

    #[test]
    fn mode_parse_and_stub_are_safe() {
        // 测试目的：模式解析收敛 + 调用安全（不 panic）。
        assert_eq!(InjectMode::parse("hold"), InjectMode::Hold);
        assert_eq!(InjectMode::parse("double"), InjectMode::Double);
        assert_eq!(InjectMode::parse("unknown"), InjectMode::Hold);
        let injector = KeyInjector::new();
        // macOS 测试进程通常未获辅助功能权限，返回中文提示属预期；
        // 非 macOS stub 恒成功——两种结果都合法，只要求错误文案可读
        if let Err(message) = injector.set_talking(true, InjectMode::Hold) {
            assert!(message.contains("辅助功能"));
        }
        injector.release();
        #[cfg(not(target_os = "macos"))]
        {
            // macOS 上不调用 double：若测试进程恰好已授权会真的敲击系统按键
            injector.set_talking(false, InjectMode::Double).unwrap();
            injector.release();
        }
    }
}
