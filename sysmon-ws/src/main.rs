/// main.rs — sysmon-ws 托盘应用入口（跨平台：macOS / Windows）
mod metrics;
mod server;
mod statusbar_view;
mod tray_app;

fn main() {
    // Windows：隐藏控制台窗口，作为纯 GUI 托盘应用运行
    // 注意：更彻底的方案是在 Cargo.toml 设置 windows_subsystem = "windows"，
    // 但那样会导致 tracing 日志无法输出到控制台（调试不便）。
    // 这里用运行时隐藏，保留调试能力。
    #[cfg(target_os = "windows")]
    hide_console_window();

    tray_app::run();
}

/// Windows：隐藏当前进程的控制台窗口
#[cfg(target_os = "windows")]
fn hide_console_window() {
    use winapi::um::wincon::GetConsoleWindow;
    use winapi::um::winuser::{ShowWindow, SW_HIDE};
    unsafe {
        let hwnd = GetConsoleWindow();
        if !hwnd.is_null() {
            ShowWindow(hwnd, SW_HIDE);
        }
    }
}
