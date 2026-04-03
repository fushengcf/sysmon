/// main.rs — sysmon-un 系统监控（Ubuntu/Linux 桌面版）
///
/// 功能与 sysmon-ws 完全一致：
///   - 系统指标采集（CPU / 内存 / 网速）
///   - WebSocket 服务器（ws://0.0.0.0:9001，每秒推送 JSON）
///   - 系统托盘图标 + 右键菜单（D-Bus StatusNotifierItem）
///
/// Ubuntu 25 兼容：ksni → D-Bus SNI，支持 GNOME / KDE / X11 / Wayland
mod tray;

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use sysmon_core::metrics::{MetricsCollector, NetOnlyCollector};
use sysmon_core::server;

const WS_PORT: u16 = 9001;
const INTERVAL_MS: u64 = 1000;

fn main() {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_max_level(tracing::Level::INFO)
        .init();

    if std::env::args().any(|a| a == "--no-tray") {
        run_headless();
    } else {
        run_tray();
    }
}

// ─── Headless 模式 ────────────────────────────────────────────────────────────

fn run_headless() {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");

    rt.block_on(async {
        let addr = format!("0.0.0.0:{}", WS_PORT);
        tracing::info!("Headless mode — WS on ws://{}", addr);
        match server::run_server(&addr, INTERVAL_MS).await {
            Ok(_) => futures_util::future::pending::<()>().await,
            Err(e) => tracing::error!("Server error: {}", e),
        }
    });
}

// ─── 托盘模式 ─────────────────────────────────────────────────────────────────

fn run_tray() {
    let ws_running = Arc::new(AtomicBool::new(true));
    let conn_count: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));

    // 启动 WS 服务
    let mut stop_tx: Option<tokio::sync::oneshot::Sender<()>> =
        Some(start_ws_server(Arc::clone(&conn_count)));

    // 指标 → 托盘 channel
    let (metrics_tx, metrics_rx) =
        mpsc::sync_channel::<(f64, f64, Option<f32>, Option<f32>)>(1);

    // 托盘动作 channel
    let (action_tx, action_rx) = mpsc::channel::<tray::TrayAction>();

    // ── 采集线程 ──────────────────────────────────────────────────────────────
    {
        let conn_count = Arc::clone(&conn_count);
        let metrics_tx = metrics_tx.clone();
        thread::spawn(move || {
            let mut net_collector = NetOnlyCollector::new();
            let mut full_collector: Option<MetricsCollector> = None;
            thread::sleep(Duration::from_secs(1));

            loop {
                let has_clients = conn_count.load(Ordering::SeqCst) > 0;
                if has_clients {
                    let collector = full_collector.get_or_insert_with(MetricsCollector::new);
                    let snap = collector.collect();
                    let _ = metrics_tx.try_send((
                        snap.net_rx_kbps,
                        snap.net_tx_kbps,
                        Some(snap.cpu_usage_percent),
                        Some(snap.memory_usage_percent),
                    ));
                } else {
                    if full_collector.is_some() {
                        full_collector = None;
                    }
                    let (rx, tx) = net_collector.collect_net();
                    let _ = metrics_tx.try_send((rx, tx, None, None));
                }
                thread::sleep(Duration::from_millis(INTERVAL_MS));
            }
        });
    }

    // ── 托盘线程 ──────────────────────────────────────────────────────────────
    let tray_ws_running = Arc::clone(&ws_running);
    let tray_service = ksni::TrayService::new(tray::SysmonTray {
        rx: metrics_rx,
        action_tx,
        ws_running: tray_ws_running,
    });
    let tray_handle = tray_service.handle();
    tray_service.spawn();

    tracing::info!("Tray started. WebSocket: ws://0.0.0.0:{}", WS_PORT);

    // ── 主循环：处理托盘动作 ──────────────────────────────────────────────────
    loop {
        match action_rx.recv() {
            Ok(tray::TrayAction::Quit) => {
                tracing::info!("Quit requested.");
                std::process::exit(0);
            }
            Ok(tray::TrayAction::Toggle) => {
                if ws_running.load(Ordering::SeqCst) {
                    if let Some(tx) = stop_tx.take() {
                        let _ = tx.send(());
                    }
                    ws_running.store(false, Ordering::SeqCst);
                    tracing::info!("WebSocket service stopped");
                } else {
                    stop_tx = Some(start_ws_server(Arc::clone(&conn_count)));
                    ws_running.store(true, Ordering::SeqCst);
                    tracing::info!("WebSocket service restarted");
                }
                // 通知托盘刷新菜单
                let _ = tray_handle.update(|_| {});
            }
            Err(_) => {
                tracing::warn!("Tray channel closed, exiting");
                break;
            }
        }
    }
}

// ─── 启动 WS 服务（后台线程）──────────────────────────────────────────────────

fn start_ws_server(
    conn_count: Arc<AtomicUsize>,
) -> tokio::sync::oneshot::Sender<()> {
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();

    thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        rt.block_on(async move {
            let addr = format!("0.0.0.0:{}", WS_PORT);
            tokio::select! {
                result = async {
                    match server::run_server(&addr, INTERVAL_MS).await {
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
