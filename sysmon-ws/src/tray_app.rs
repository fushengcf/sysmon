/// tray_app.rs — macOS 系统托盘应用（两行状态栏版）
///
/// 采集策略：
///   - 无客户端连接时：只用 NetOnlyCollector 采网速（轻量，省 CPU/内存）
///   - 有客户端连接时：用 MetricsCollector 采全量指标（CPU + 内存 + 网速）
///
/// 停止服务：只停 WebSocket 服务，网速采集继续运行（状态栏仍显示网速）
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use winit::event_loop::EventLoopBuilder;
use winit::platform::macos::{ActivationPolicy, EventLoopBuilderExtMacOS};
use winit::platform::pump_events::{EventLoopExtPumpEvents, PumpStatus};

#[cfg(target_os = "macos")]
use crate::statusbar_view::macos as sb;

use crate::metrics::{MetricsCollector, NetOnlyCollector, SystemMetrics};

// ─── 共享状态 ─────────────────────────────────────────────────────────────────

struct AppState {
    /// WebSocket 服务是否运行中
    ws_running: AtomicBool,
    port: u16,
    interval_ms: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            ws_running: AtomicBool::new(false),
            port: 9001,
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

    // ── mpsc channel：采集线程 → 主线程 ──────────────────────────────────────
    // 传递 (rx_kbps, tx_kbps, Option<SystemMetrics>)
    // Option<SystemMetrics> 有值时表示全量数据（有客户端连接），None 时只有网速
    let (metrics_tx, metrics_rx) =
        std::sync::mpsc::sync_channel::<(f64, f64, Option<SystemMetrics>)>(1);

    // ── 连接数（由 server 维护，采集线程读取）────────────────────────────────
    // 初始为 0，启动 WS 服务后替换为 server 返回的计数器
    let conn_count: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));

    // ── 采集线程（常驻，不随 WS 停止而退出）─────────────────────────────────
    {
        let metrics_tx = metrics_tx.clone();
        let conn_count = Arc::clone(&conn_count);
        let interval_ms = state.interval_ms;

        thread::spawn(move || {
            // 始终持有轻量网速采集器
            let mut net_collector = NetOnlyCollector::new();
            // 全量采集器按需创建（有连接时）
            let mut full_collector: Option<MetricsCollector> = None;

            thread::sleep(Duration::from_secs(1)); // 预热

            loop {
                let has_clients = conn_count.load(Ordering::SeqCst) > 0;

                if has_clients {
                    // 有客户端：用全量采集器
                    let collector = full_collector.get_or_insert_with(MetricsCollector::new);
                    let snap = collector.collect();
                    let rx = snap.net_rx_kbps;
                    let tx = snap.net_tx_kbps;
                    let _ = metrics_tx.try_send((rx, tx, Some(snap)));
                } else {
                    // 无客户端：只采网速，释放全量采集器
                    if full_collector.is_some() {
                        tracing::info!("No clients connected, switching to net-only collection");
                        full_collector = None;
                    }
                    let (rx, tx) = net_collector.collect_net();
                    let _ = metrics_tx.try_send((rx, tx, None));
                }

                thread::sleep(Duration::from_millis(interval_ms));
            }
        });
    }

    // ── 启动 WebSocket 服务 ───────────────────────────────────────────────────
    let port = state.port;
    let interval = state.interval_ms;
    state.ws_running.store(true, Ordering::SeqCst);
    let mut stop_tx: Option<tokio::sync::oneshot::Sender<()>> =
        Some(start_server(port, interval, Arc::clone(&conn_count)));

    // ── winit EventLoop（驱动 Cocoa runloop）─────────────────────────────────
    let mut event_loop = EventLoopBuilder::new()
        .with_activation_policy(ActivationPolicy::Accessory)
        .with_default_menu(false)
        .build()
        .expect("EventLoop");

    // ── 构建 NSMenu + 两行状态栏 View（macOS）────────────────────────────────
    #[cfg(target_os = "macos")]
    let (menu_state, _status_item) = unsafe { build_statusbar(state.port) };

    tracing::info!("Tray app started. WebSocket: ws://localhost:{}", state.port);

    // ── UI 节流 ───────────────────────────────────────────────────────────────
    let mut last_ui_update = Instant::now() - Duration::from_secs(2);

    // ── 主事件循环 ────────────────────────────────────────────────────────────
    loop {
        let status = event_loop.pump_events(
            Some(Duration::from_millis(200)),
            |_event, elwt| {
                elwt.set_control_flow(winit::event_loop::ControlFlow::Wait);
            },
        );

        if let PumpStatus::Exit(_) = status {
            tracing::info!("Event loop exited.");
            break;
        }

        // ── 检查菜单动作 ──────────────────────────────────────────────────────
        #[cfg(target_os = "macos")]
        {
            let action = menu_state.action.swap(0, Ordering::SeqCst);
            match action {
                1 => {
                    // Toggle WebSocket 服务（网速采集不受影响）
                    if state.ws_running.load(Ordering::SeqCst) {
                        if let Some(tx) = stop_tx.take() {
                            let _ = tx.send(());
                        }
                        state.ws_running.store(false, Ordering::SeqCst);
                        tracing::info!("WebSocket service stopped by user (net collection continues)");
                    } else {
                        let new_stop_tx = start_server(
                            state.port,
                            state.interval_ms,
                            Arc::clone(&conn_count),
                        );
                        stop_tx = Some(new_stop_tx);
                        state.ws_running.store(true, Ordering::SeqCst);
                        tracing::info!("WebSocket service restarted by user");
                    }
                    unsafe {
                        update_toggle_text(
                            menu_state.toggle_item,
                            state.ws_running.load(Ordering::SeqCst),
                        );
                    }
                }
                2 => {
                    tracing::info!("Quit requested.");
                    std::process::exit(0);
                }
                _ => {}
            }
        }

        // ── 消费最新指标，每秒更新一次 ───────────────────────────────────────
        let mut latest: Option<(f64, f64, Option<SystemMetrics>)> = None;
        while let Ok(snap) = metrics_rx.try_recv() {
            latest = Some(snap);
        }

        if latest.is_some() && last_ui_update.elapsed() >= Duration::from_millis(900) {
            last_ui_update = Instant::now();
            let (rx_kbps, tx_kbps, full) = latest.unwrap();

            // 状态栏网速始终更新（无论 WS 是否运行）
            #[cfg(target_os = "macos")]
            sb::update_speed(rx_kbps, tx_kbps);

            // 菜单项：只在 WS 运行且有全量数据时更新
            #[cfg(target_os = "macos")]
            if state.ws_running.load(Ordering::SeqCst) {
                if let Some(snap) = full {
                    unsafe {
                        use cocoa::base::nil;
                        use cocoa::foundation::NSString;
                        use objc::{msg_send, sel, sel_impl};

                        let cpu_str = NSString::alloc(nil).init_str(&format!(
                            "CPU  {:.1}%",
                            snap.cpu_usage_percent
                        ));
                        let _: () = msg_send![menu_state.cpu_item, setTitle: cpu_str];

                        let mem_str = NSString::alloc(nil).init_str(&format!(
                            "MEM  {:.1}%  ({}/{} MB)",
                            snap.memory_usage_percent,
                            snap.memory_used_mb,
                            snap.memory_total_mb
                        ));
                        let _: () = msg_send![menu_state.mem_item, setTitle: mem_str];

                        let net_str = NSString::alloc(nil).init_str(&format!(
                            "NET  ↓{:.1}  ↑{:.1} KB/s",
                            rx_kbps, tx_kbps
                        ));
                        let _: () = msg_send![menu_state.net_item, setTitle: net_str];
                    }
                }
            }
        }
    }
}

// ─── macOS 菜单状态 ───────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
struct MenuState {
    /// 0=无动作, 1=toggle, 2=quit
    action: std::sync::atomic::AtomicU8,
    toggle_item: cocoa::base::id,
    cpu_item: cocoa::base::id,
    mem_item: cocoa::base::id,
    net_item: cocoa::base::id,
}

// ─── 构建状态栏 ───────────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
unsafe fn build_statusbar(port: u16) -> (Arc<MenuState>, cocoa::base::id) {
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
        decl.add_method(
            sel!(handleToggle:),
            handle_toggle as extern "C" fn(&mut Object, Sel, id),
        );
        decl.add_method(
            sel!(handleQuit:),
            handle_quit as extern "C" fn(&mut Object, Sel, id),
        );
        decl.register();
    });

    let menu_state = Arc::new(MenuState {
        action: std::sync::atomic::AtomicU8::new(0),
        toggle_item: nil,
        cpu_item: nil,
        mem_item: nil,
        net_item: nil,
    });

    let handler: id = msg_send![class!(SysMonMenuHandler), new];
    let raw_ptr = Arc::as_ptr(&menu_state) as *mut std::ffi::c_void;
    (*handler).set_ivar("menuStatePtr", raw_ptr);
    let _: () = msg_send![handler, retain];

    let menu: id = NSMenu::new(nil);
    let _: () = msg_send![menu, setAutoenablesItems: NO];

    let title_item = make_menu_item("SysMon WS", nil, None, false);
    NSMenu::addItem_(menu, title_item);

    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let cpu_item = make_menu_item("CPU  --", nil, None, false);
    NSMenu::addItem_(menu, cpu_item);
    let mem_item = make_menu_item("MEM  --", nil, None, false);
    NSMenu::addItem_(menu, mem_item);
    let net_item = make_menu_item("NET  ↓-- ↑--", nil, None, false);
    NSMenu::addItem_(menu, net_item);

    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let addr_str = format!("ws://localhost:{}", port);
    let addr_item = make_menu_item(&addr_str, nil, None, false);
    NSMenu::addItem_(menu, addr_item);

    let toggle_item = make_menu_item("⏹ 停止服务", handler, Some(sel!(handleToggle:)), true);
    NSMenu::addItem_(menu, toggle_item);

    NSMenu::addItem_(menu, NSMenuItem::separatorItem(nil));

    let quit_item = make_menu_item("退出", handler, Some(sel!(handleQuit:)), true);
    NSMenu::addItem_(menu, quit_item);

    let ms_ptr = Arc::as_ptr(&menu_state) as *mut MenuState;
    (*ms_ptr).toggle_item = toggle_item;
    (*ms_ptr).cpu_item = cpu_item;
    (*ms_ptr).mem_item = mem_item;
    (*ms_ptr).net_item = net_item;

    let status_item = sb::create_status_item(menu);

    (menu_state, status_item)
}

#[cfg(target_os = "macos")]
unsafe fn make_menu_item(
    title: &str,
    target: cocoa::base::id,
    action: Option<objc::runtime::Sel>,
    enabled: bool,
) -> cocoa::base::id {
    use cocoa::appkit::NSMenuItem;
    use cocoa::base::{id, nil, NO, YES};
    use cocoa::foundation::NSString;
    use objc::{msg_send, sel, sel_impl};

    let ns_title = NSString::alloc(nil).init_str(title);
    let sel = action.unwrap_or_else(|| std::mem::zeroed::<objc::runtime::Sel>());
    let item: id = NSMenuItem::alloc(nil).initWithTitle_action_keyEquivalent_(
        ns_title,
        sel,
        NSString::alloc(nil).init_str(""),
    );
    let _: () = msg_send![item, setEnabled: if enabled { YES } else { NO }];
    if !target.is_null() {
        let _: () = msg_send![item, setTarget: target];
    }
    item
}

#[cfg(target_os = "macos")]
unsafe fn update_toggle_text(item: cocoa::base::id, ws_running: bool) {
    use cocoa::base::nil;
    use cocoa::foundation::NSString;
    use objc::{msg_send, sel, sel_impl};

    let text = if ws_running { "⏹ 停止服务" } else { "▶ 启动服务" };
    let ns_str = NSString::alloc(nil).init_str(text);
    let _: () = msg_send![item, setTitle: ns_str];
}

// ── ObjC 菜单回调 ─────────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
extern "C" fn handle_toggle(this: &mut objc::runtime::Object, _sel: objc::runtime::Sel, _sender: cocoa::base::id) {
    unsafe {
        let ptr: *mut std::ffi::c_void = *this.get_ivar("menuStatePtr");
        if ptr.is_null() { return; }
        let ms = &*(ptr as *const MenuState);
        ms.action.store(1, Ordering::SeqCst);
    }
}

#[cfg(target_os = "macos")]
extern "C" fn handle_quit(this: &mut objc::runtime::Object, _sel: objc::runtime::Sel, _sender: cocoa::base::id) {
    unsafe {
        let ptr: *mut std::ffi::c_void = *this.get_ivar("menuStatePtr");
        if ptr.is_null() { return; }
        let ms = &*(ptr as *const MenuState);
        ms.action.store(2, Ordering::SeqCst);
    }
}

// ─── 启动 WebSocket 服务 ──────────────────────────────────────────────────────

/// 启动 WS 服务，将 server 返回的连接计数器写入 `conn_count`
fn start_server(
    port: u16,
    interval_ms: u64,
    conn_count: Arc<AtomicUsize>,
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
                            // 将 server 内部的连接计数器同步到外部
                            // 用一个轮询任务每 200ms 同步一次
                            let conn_count_sync = Arc::clone(&conn_count);
                            tokio::spawn(async move {
                                loop {
                                    let n = server_conn_count.load(Ordering::SeqCst);
                                    conn_count_sync.store(n, Ordering::SeqCst);
                                    tokio::time::sleep(Duration::from_millis(200)).await;
                                }
                            });
                            // run_server 本身不再阻塞，等待 stop 信号
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
                    tracing::info!("WebSocket server stopped (user request)");
                    // 停止时清零连接数
                    conn_count.store(0, Ordering::SeqCst);
                }
            }
        });
    });
    stop_tx
}
