use crate::controller::{AppController, AppState};
use std::sync::Arc;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{ActivationPolicy, App, AppHandle, CloseRequestApi, Manager, Window, Wry};

const MAIN_WINDOW: &str = "main";
const SHOW_WINDOW: &str = "tray-show-window";
const QUIT: &str = "tray-quit";
const DISCONNECTED: &str = "未连接";

/// 菜单栏入口就绪后持有状态项，关闭窗口时据此确认用户可以从菜单栏返回。
struct TrayMenu {
    status: MenuItem<Wry>,
}

pub(super) fn setup(app: &App) -> tauri::Result<()> {
    let status = MenuItem::new(app, DISCONNECTED, false, None::<&str>)?;
    let show = MenuItem::with_id(app, SHOW_WINDOW, "显示主窗口", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT, "退出 Remote Voice", true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&status, &separator, &show, &quit])?;

    TrayIconBuilder::with_id("remote-voice")
        .icon(tauri::include_image!("icons/menu-bar.png"))
        .icon_as_template(true)
        .tooltip("Remote Voice")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id().as_ref() {
            SHOW_WINDOW => show_main_window(app),
            QUIT => app.exit(0),
            _ => {}
        })
        .build(app)?;
    app.manage(TrayMenu { status });
    Ok(())
}

pub(super) fn hide_on_close(window: &Window, api: &CloseRequestApi) {
    if window.label() != MAIN_WINDOW || window.try_state::<TrayMenu>().is_none() {
        return;
    }
    match window.hide() {
        Ok(()) => {
            api.prevent_close();
            set_activation_policy(window.app_handle(), ActivationPolicy::Accessory);
        }
        Err(error) => eprintln!("收起主窗口失败：{error}"),
    }
}

pub(super) fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW) {
        set_activation_policy(app, ActivationPolicy::Regular);
        let result = window
            .unminimize()
            .and_then(|()| window.show())
            .and_then(|()| window.set_focus());
        if let Err(error) = result {
            eprintln!("显示主窗口失败：{error}");
        }
    }
}

fn set_activation_policy(app: &AppHandle, policy: ActivationPolicy) {
    if let Err(error) = app.set_activation_policy(policy) {
        eprintln!("切换 Dock 显示状态失败 · {error}");
    }
}

pub(super) fn status_text(state: &AppState) -> &'static str {
    match state.status.as_str() {
        "connecting" => "连接中",
        "reconnecting" => "重连中",
        "registered" => "等待手机",
        "bridged" if !state.audio_error.is_empty() => "音频输出异常",
        "bridged" if state.talking => "正在说话",
        "bridged" => "手机已连接",
        "fatal" => "连接失败",
        _ => DISCONNECTED,
    }
}

pub(super) fn refresh_status(app: &AppHandle) {
    let handle = app.clone();
    // 只向主线程投递更新，以免退出时主线程等待中继，而中继又等待原生菜单更新。
    let _ = app.run_on_main_thread(move || {
        if let Some(tray) = handle.try_state::<TrayMenu>() {
            // 并发事件的投递顺序可能交错，执行时读取最新状态。
            if let Ok(state) = handle.state::<Arc<AppController>>().snapshot() {
                let _ = tray.status.set_text(status_text(&state));
            }
        }
    });
}
