/// main.rs — sysmon-ws 托盘应用入口
///
/// macOS 系统托盘应用，内嵌 WebSocket 服务器
/// 托盘菜单显示实时 CPU / 内存 / 网速，支持启停服务
mod statusbar_view;
mod tray_app;

fn main() {
    tray_app::run();
}
