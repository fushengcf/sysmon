/// tray_app.rs — 跨平台系统托盘应用（macOS / Windows）
///
/// 平台差异说明：
///   macOS  — NSStatusBar 两行网速 View + NSMenu，Cocoa 主线程驱动
///   Windows — tray-icon 系统托盘图标 + 右键菜单，winit 事件循环驱动
///             托盘图标 tooltip 实时显示网速（Windows 无状态栏概念）
///
/// 采集策略（两平台共用）：
///   - 无客户端连接：只用 NetOnlyCollector 采网速（轻量）
///   - 有客户端连接：用 MetricsCollector 采全量指标
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use winit::event_loop::EventLoopBuilder;
use winit::platform::pump_events::{EventLoopExtPumpEvents, PumpStatus};

#[cfg(target_os = "macos")]
use winit::platform::macos::{ActivationPolicy, EventLoopBuilderExtMacOS};

use crate::metrics::{MetricsCollector, NetOnlyCollector, SystemMetrics};

// ─── 共享状态 ─────────────────────────────────────────────────────────────────

struct AppState {
    ws_running:  AtomicBool,
    port:        u16,
    interval_ms: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            ws_running:  AtomicBool::new(false),
            port:        9001,
            interval_ms: 1000,
        }
    }
}

// ─── 入口 ─────────────────────────────────────────────────────────────────────

pub fn run() {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_max_level(tracing::Level::INFO)
        .init();

    let state = Arc::new(AppState::new());

    // mpsc channel：采集线程 → 主线程
    let (metrics_tx, metrics_rx) =
        std::sync::mpsc::sync_channel::<(f64, f64, Option<SystemMetrics>)>(1);

    // 连接数计数器
    let conn_count: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));

    // 采集线程（常驻）
    {
        let metrics_tx  = metrics_tx.clone();
        let conn_count  = Arc::clone(&conn_count);
        let interval_ms = state.interval_ms;

        thread::spawn(move || {
            let mut net_collector  = NetOnlyCollector::new();
            let mut full_collector: Option<MetricsCollector> = None;

            thread::sleep(Duration::from_secs(1));

            loop {
                let has_clients = conn_count.load(Ordering::SeqCst) > 0;

                if has_clients {
                    let collector = full_collector.get_or_insert_with(MetricsCollector::new);
                    let snap = collector.collect();
                    let rx = snap.net_rx_kbps;
                    let tx = snap.net_tx_kbps;
                    let _ = metrics_tx.try_send((rx, tx, Some(snap)));
                } else {
                    if full_collector.is_some() {
                        tracing::info!("No clients, switching to net-only collection");
                        full_collector = None;
                    }
                    let (rx, tx) = net_collector.collect_net();
                    let _ = metrics_tx.try_send((rx, tx, None));
                }

                thread::sleep(Duration::from_millis(interval_ms));
            }
        });
    }

    // 启动 WebSocket 服务
    let port     = state.port;
    let interval = state.interval_ms;
    state.ws_running.store(true, Ordering::SeqCst);
    let stop_tx: Option<tokio::sync::oneshot::Sender<()>> =
        Some(start_server(port, interval, Arc::clone(&conn_count)));

    // ── 平台分发 ──────────────────────────────────────────────────────────────
    #[cfg(target_os = "macos")]
    run_macos(state, metrics_rx, conn_count, stop_tx);

    #[cfg(target_os = "windows")]
    run_windows(state, metrics_rx, conn_count, stop_tx);

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        // Linux / 其他平台：仅运行 WebSocket 服务，无 GUI
        tracing::warn!("No GUI support on this platform, running headless WebSocket server");
        loop {
            thread::sleep(Duration::from_secs(60));
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// macOS 实现（原有逻辑，保持不变）
// ══════════════════════════════════════════════════════════════════════════════

#[cfg(target_os = "macos")]
fn run_macos(
    state:      Arc<AppState>,
    metrics_rx: std::sync::mpsc::Receiver<(f64, f64, Option<SystemMetrics>)>,
    conn_count: Arc<AtomicUsize>,
    mut stop_tx: Option<tokio::sync::oneshot::Sender<()>>,
) {
    use crate::statusbar_view::macos as sb;

    let mut event_loop = EventLoopBuilder::new()
        .with_activation_policy(ActivationPolicy::Accessory)
        .with_default_menu(false)
        .build()
        .expect("EventLoop");

    let (menu_state, _status_item) = unsafe { build_statusbar_macos(state.port) };

    tracing::info!("macOS tray started. WebSocket: ws://localhost:{}", state.port);

    let mut last_ui_update = Instant::now() - Duration::from_secs(2);

    loop {
        let status = event_loop.pump_events(
            Some(Duration::from_millis(200)),
            |_event, elwt| {
                elwt.set_control_flow(winit::event_loop::ControlFlow::Wait);
            },
        );

        if let PumpStatus::Exit(_) = status {
            break;
        }

        // 检查菜单动作
        let action = menu_state.action.swap(0, Ordering::SeqCst);
        match action {
            1 => {
                if state.ws_running.load(Ordering::SeqCst) {
                    if let Some(tx) = stop_tx.take() { let _ = tx.send(()); }
                    state.ws_running.store(false, Ordering::SeqCst);
                } else {
                    let new_stop_tx = start_server(state.port, state.interval_ms, Arc::clone(&conn_count));
                    stop_tx = Some(new_stop_tx);
                    state.ws_running.store(true, Ordering::SeqCst);
                }
                unsafe {
                    update_toggle_text_macos(
                        menu_state.toggle_item,
                        state.ws_running.load(Ordering::SeqCst),
                    );
                }
            }
            2 => std::process::exit(0),
            _ => {}
        }

        // 更新 UI
        let mut latest: Option<(f64, f64, Option<SystemMetrics>)> = None;
        while let Ok(snap) = metrics_rx.try_recv() { latest = Some(snap); }

        if latest.is_some() && last_ui_update.elapsed() >= Duration::from_millis(900) {
            last_ui_update = Instant::now();
            let (rx_kbps, tx_kbps, full) = latest.unwrap();

            sb::update_speed(rx_kbps, tx_kbps);

            if state.ws_running.load(Ordering::SeqCst) {
                if let Some(snap) = full {
                    unsafe { update_menu_metrics_macos(&menu_state, &snap); }
                }
            }
        }
    }
}

// ── macOS 菜单状态 ────────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
struct MenuStateMacos {
    action:      std::sync::atomic::AtomicU8,
    toggle_item: cocoa::base::id,
    cpu_item:    cocoa::base::id,
    mem_item:    cocoa::base::id,
    net_item:    cocoa::base::id,
}

#[cfg(target_os = "macos")]
unsafe fn build_statusbar_macos(port: u16) -> (Arc<MenuStateMacos>, cocoa::base::id) {
    use cocoa::appkit::{NSMenu, NSMenuItem};
    use cocoa::base::{id, nil, NO};
    use cocoa::foundation::NSString;
    use objc::declare::ClassDecl;
    use objc::runtime::{Object, Sel};
    use objc::{class, msg_send, sel, sel_impl};
    use std::sync::OnceLock;

    static HANDLER_CLASS: OnceLock<()> = OnceLock::new();
    HANDLER_CLASS.get_or_init(|| {
        let superclass = class!(NSObject);
        let mut decl = ClassDecl::new("SysMonMenuHandler", superclass)
            .expect("SysMonMenuHandler");
        decl.add_ivar::<*mut std::ffi::c_void>("menuStatePtr");
        decl.add_method(sel!(handleToggle:), handle_toggle_macos as extern "C" fn(&mut Object, Sel, id));
        decl.add_method(sel!(handleQuit:),   handle_quit_macos   as extern "C" fn(&mut Object, Sel, id));
        decl.register();
    });

    let menu_state = Arc::new(MenuStateMacos {
        action:      std::sync::atomic::AtomicU8::new(0),
        toggle_item: nil,
        cpu_item:    nil,
        mem_item:    nil,
        net_item:    nil,
    });

    let handler: id = msg_send![class!(SysMonMenuHandler), new];
    let raw_ptr = Arc::as_ptr(&menu_state) as *mut std::ffi::c_void;
    (*handler).set_ivar("menuStatePtr", raw_ptr);
    let _: () = msg_send![handler, retain];

    let menu: id = NSMenu::new(nil);
    let _: () = msg_send![menu, setAutoenablesItems: NO];

    let title_item = make_menu_item_macos("SysMon WS", nil, None, false);
    NSMenu::addItem_(menu, title_item);
    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let cpu_item = make_menu_item_macos("CPU  --", nil, None, false);
    NSMenu::addItem_(menu, cpu_item);
    let mem_item = make_menu_item_macos("MEM  --", nil, None, false);
    NSMenu::addItem_(menu, mem_item);
    let net_item = make_menu_item_macos("NET  ↓-- ↑--", nil, None, false);
    NSMenu::addItem_(menu, net_item);
    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let addr_str  = format!("ws://localhost:{}", port);
    let addr_item = make_menu_item_macos(&addr_str, nil, None, false);
    NSMenu::addItem_(menu, addr_item);

    let toggle_item = make_menu_item_macos("⏹ 停止服务", handler, Some(sel!(handleToggle:)), true);
    NSMenu::addItem_(menu, toggle_item);
    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let quit_item = make_menu_item_macos("退出", handler, Some(sel!(handleQuit:)), true);
    NSMenu::addItem_(menu, quit_item);

    let ms_ptr = Arc::as_ptr(&menu_state) as *mut MenuStateMacos;
    (*ms_ptr).toggle_item = toggle_item;
    (*ms_ptr).cpu_item    = cpu_item;
    (*ms_ptr).mem_item    = mem_item;
    (*ms_ptr).net_item    = net_item;

    let status_item = crate::statusbar_view::macos::create_status_item(menu);
    (menu_state, status_item)
}

#[cfg(target_os = "macos")]
unsafe fn make_menu_item_macos(
    title:   &str,
    target:  cocoa::base::id,
    action:  Option<objc::runtime::Sel>,
    enabled: bool,
) -> cocoa::base::id {
    use cocoa::appkit::NSMenuItem;
    use cocoa::base::{id, nil, NO, YES};
    use cocoa::foundation::NSString;
    use objc::{msg_send, sel, sel_impl};

    let ns_title = NSString::alloc(nil).init_str(title);
    let sel = action.unwrap_or_else(|| std::mem::zeroed::<objc::runtime::Sel>());
    let item: id = NSMenuItem::alloc(nil).initWithTitle_action_keyEquivalent_(
        ns_title, sel, NSString::alloc(nil).init_str(""),
    );
    let _: () = msg_send![item, setEnabled: if enabled { YES } else { NO }];
    if !target.is_null() {
        let _: () = msg_send![item, setTarget: target];
    }
    item
}

#[cfg(target_os = "macos")]
unsafe fn update_toggle_text_macos(item: cocoa::base::id, ws_running: bool) {
    use cocoa::base::nil;
    use cocoa::foundation::NSString;
    use objc::{msg_send, sel, sel_impl};
    let text = if ws_running { "⏹ 停止服务" } else { "▶ 启动服务" };
    let ns_str = NSString::alloc(nil).init_str(text);
    let _: () = msg_send![item, setTitle: ns_str];
}

#[cfg(target_os = "macos")]
unsafe fn update_menu_metrics_macos(ms: &MenuStateMacos, snap: &SystemMetrics) {
    use cocoa::base::nil;
    use cocoa::foundation::NSString;
    use objc::{msg_send, sel, sel_impl};

    let cpu_str = NSString::alloc(nil).init_str(
        &format!("CPU  {:.1}%", snap.cpu_usage_percent));
    let _: () = msg_send![ms.cpu_item, setTitle: cpu_str];

    let mem_str = NSString::alloc(nil).init_str(
        &format!("MEM  {:.1}%  ({}/{} MB)",
            snap.memory_usage_percent, snap.memory_used_mb, snap.memory_total_mb));
    let _: () = msg_send![ms.mem_item, setTitle: mem_str];

    let net_str = NSString::alloc(nil).init_str(
        &format!("NET  ↓{:.1}  ↑{:.1} KB/s",
            snap.net_rx_kbps, snap.net_tx_kbps));
    let _: () = msg_send![ms.net_item, setTitle: net_str];
}

#[cfg(target_os = "macos")]
extern "C" fn handle_toggle_macos(
    this: &mut objc::runtime::Object, _sel: objc::runtime::Sel, _sender: cocoa::base::id,
) {
    unsafe {
        let ptr: *mut std::ffi::c_void = *this.get_ivar("menuStatePtr");
        if ptr.is_null() { return; }
        let ms = &*(ptr as *const MenuStateMacos);
        ms.action.store(1, Ordering::SeqCst);
    }
}

#[cfg(target_os = "macos")]
extern "C" fn handle_quit_macos(
    this: &mut objc::runtime::Object, _sel: objc::runtime::Sel, _sender: cocoa::base::id,
) {
    unsafe {
        let ptr: *mut std::ffi::c_void = *this.get_ivar("menuStatePtr");
        if ptr.is_null() { return; }
        let ms = &*(ptr as *const MenuStateMacos);
        ms.action.store(2, Ordering::SeqCst);
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Windows 实现
// ══════════════════════════════════════════════════════════════════════════════

#[cfg(target_os = "windows")]
fn run_windows(
    state:      Arc<AppState>,
    metrics_rx: std::sync::mpsc::Receiver<(f64, f64, Option<SystemMetrics>)>,
    conn_count: Arc<AtomicUsize>,
    mut stop_tx: Option<tokio::sync::oneshot::Sender<()>>,
) {
    use tray_icon::{
        TrayIconBuilder, TrayIconEvent,
        menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem},
    };
    use winit::event::Event;
    use winit::event_loop::ControlFlow;

    // ── 加载托盘图标 ──────────────────────────────────────────────────────────
    let icon = load_tray_icon_windows();

    // ── 构建右键菜单 ──────────────────────────────────────────────────────────
    let menu = Menu::new();

    let title_item  = MenuItem::new("SysMon WS", false, None);
    let cpu_item    = MenuItem::new("CPU  --", false, None);
    let mem_item    = MenuItem::new("MEM  --", false, None);
    let net_item    = MenuItem::new("NET  ↓-- ↑--", false, None);
    let sep1        = PredefinedMenuItem::separator();
    let addr_item   = MenuItem::new(format!("ws://localhost:{}", state.port), false, None);
    let toggle_item = MenuItem::new("⏹ 停止服务", true, None);
    let sep2        = PredefinedMenuItem::separator();
    let quit_item   = MenuItem::new("退出", true, None);

    let toggle_id = toggle_item.id().clone();
    let quit_id   = quit_item.id().clone();

    menu.append_items(&[
        &title_item,
        &sep1,
        &cpu_item,
        &mem_item,
        &net_item,
        &PredefinedMenuItem::separator(),
        &addr_item,
        &toggle_item,
        &sep2,
        &quit_item,
    ]).expect("menu append");

    // ── 创建托盘图标 ──────────────────────────────────────────────────────────
    let tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("SysMon WS — 正在运行")
        .with_icon(icon)
        .build()
        .expect("TrayIcon");

    tracing::info!("Windows tray started. WebSocket: ws://localhost:{}", state.port);

    // ── winit 事件循环 ────────────────────────────────────────────────────────
    let mut event_loop = EventLoopBuilder::new()
        .build()
        .expect("EventLoop");

    let mut last_ui_update = Instant::now() - Duration::from_secs(2);

    loop {
        let status = event_loop.pump_events(
            Some(Duration::from_millis(200)),
            |event, elwt| {
                elwt.set_control_flow(ControlFlow::Wait);
                if let Event::LoopExiting = event { std::process::exit(0); }
            },
        );

        if let PumpStatus::Exit(_) = status { break; }

        // 处理菜单点击事件
        if let Ok(event) = MenuEvent::receiver().try_recv() {
            if event.id == toggle_id {
                if state.ws_running.load(Ordering::SeqCst) {
                    if let Some(tx) = stop_tx.take() { let _ = tx.send(()); }
                    state.ws_running.store(false, Ordering::SeqCst);
                    let _ = toggle_item.set_text("▶ 启动服务");
                    let _ = tray.set_tooltip(Some("SysMon WS — 已停止"));
                } else {
                    let new_stop_tx = start_server(state.port, state.interval_ms, Arc::clone(&conn_count));
                    stop_tx = Some(new_stop_tx);
                    state.ws_running.store(true, Ordering::SeqCst);
                    let _ = toggle_item.set_text("⏹ 停止服务");
                    let _ = tray.set_tooltip(Some("SysMon WS — 正在运行"));
                }
            } else if event.id == quit_id {
                tracing::info!("Quit requested.");
                std::process::exit(0);
            }
        }

        // 消费最新指标，每秒更新一次
        let mut latest: Option<(f64, f64, Option<SystemMetrics>)> = None;
        while let Ok(snap) = metrics_rx.try_recv() { latest = Some(snap); }

        if latest.is_some() && last_ui_update.elapsed() >= Duration::from_millis(900) {
            last_ui_update = Instant::now();
            let (rx_kbps, tx_kbps, full) = latest.unwrap();

            // Windows：用 tooltip 显示实时网速（替代 macOS 状态栏两行 view）
            let tooltip = format!(
                "SysMon WS  ↓{:.1} ↑{:.1} KB/s",
                rx_kbps, tx_kbps
            );
            let _ = tray.set_tooltip(Some(&tooltip));

            // 更新菜单中的指标项
            if state.ws_running.load(Ordering::SeqCst) {
                if let Some(snap) = full {
                    let _ = cpu_item.set_text(format!(
                        "CPU  {:.1}%", snap.cpu_usage_percent));
                    let _ = mem_item.set_text(format!(
                        "MEM  {:.1}%  ({}/{} MB)",
                        snap.memory_usage_percent, snap.memory_used_mb, snap.memory_total_mb));
                    let _ = net_item.set_text(format!(
                        "NET  ↓{:.1}  ↑{:.1} KB/s",
                        snap.net_rx_kbps, snap.net_tx_kbps));
                }
            }
        }
    }
}

/// Windows：从 assets 目录加载 .ico 图标，失败时生成内置默认图标
#[cfg(target_os = "windows")]
fn load_tray_icon_windows() -> tray_icon::Icon {
    // 优先尝试加载同目录下的 icon.ico
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()));

    if let Some(dir) = exe_dir {
        let ico_path = dir.join("icon.ico");
        if ico_path.exists() {
            if let Ok(img) = image::open(&ico_path) {
                let rgba = img.to_rgba8();
                let (w, h) = rgba.dimensions();
                if let Ok(icon) = tray_icon::Icon::from_rgba(rgba.into_raw(), w, h) {
                    return icon;
                }
            }
        }
    }

    // 回退：生成一个 32x32 的纯色默认图标（深蓝色）
    let size = 32u32;
    let mut pixels = vec![0u8; (size * size * 4) as usize];
    for i in (0..pixels.len()).step_by(4) {
        pixels[i]     = 0x1a; // R
        pixels[i + 1] = 0x2f; // G
        pixels[i + 2] = 0x5e; // B
        pixels[i + 3] = 0xff; // A
    }
    tray_icon::Icon::from_rgba(pixels, size, size).expect("default icon")
}

// ─── 启动 WebSocket 服务（两平台共用）────────────────────────────────────────

fn start_server(
    port:        u16,
    interval_ms: u64,
    conn_count:  Arc<AtomicUsize>,
) -> tokio::sync::oneshot::Sender<()> {
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");
        rt.block_on(async move {
            let addr = format!("0.0.0.0:{}", port);
            tracing::info!("WebSocket server starting on ws://{}", addr);
            tokio::select! {
                result = async {
                    match crate::server::run_server(&addr, interval_ms).await {
                        Ok(server_conn_count) => {
                            let conn_count_sync = Arc::clone(&conn_count);
                            tokio::spawn(async move {
                                loop {
                                    let n = server_conn_count.load(Ordering::SeqCst);
                                    conn_count_sync.store(n, Ordering::SeqCst);
                                    tokio::time::sleep(Duration::from_millis(200)).await;
                                }
                            });
                            futures_util::future::pending::<anyhow::Result<()>>().await
                        }
                        Err(e) => Err(e),
                    }
                } => {
                    if let Err(e) = result {
                        tracing::error!("WebSocket server error: {}", e);
                    }
                }
                _ = stop_rx => {
                    tracing::info!("WebSocket server stopped");
                    conn_count.store(0, Ordering::SeqCst);
                }
            }
        });
    });
    stop_tx
}
