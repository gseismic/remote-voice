mod audio;
mod config;
mod controller;
mod history;
mod identity;
mod keyinject;
mod protocol;
mod relay;
mod secrets;
mod storage;

pub fn run() {
    controller::run_tauri();
}
