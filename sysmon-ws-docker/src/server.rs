/// server.rs — WebSocket 服务器模块
///
/// 每个连入的客户端都会收到独立的推送流，每秒推送一次系统指标 JSON。
/// 没有客户端连接时，不进行指标采集（节省 CPU）。
use std::net::SocketAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::broadcast;
use tokio::time;
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message;
use tracing::{error, info, warn};

use crate::metrics::MetricsCollector;

const BROADCAST_CAPACITY: usize = 16;

/// 启动 WebSocket 服务器，阻塞直到进程退出
pub async fn run_server(addr: &str, interval_ms: u64) -> anyhow::Result<()> {
    let listener = TcpListener::bind(addr).await?;
    info!("WebSocket server listening on ws://{}", addr);

    let (tx, _rx) = broadcast::channel::<Arc<String>>(BROADCAST_CAPACITY);
    let tx = Arc::new(tx);

    // 后台指标采集任务
    {
        let tx = Arc::clone(&tx);
        tokio::spawn(async move {
            metrics_broadcast_task(tx, interval_ms).await;
        });
    }

    // 接受客户端连接
    let conn_count = Arc::new(AtomicUsize::new(0));
    loop {
        match listener.accept().await {
            Ok((stream, peer_addr)) => {
                info!("New connection from {}", peer_addr);
                let rx = tx.subscribe();
                let conn_count = Arc::clone(&conn_count);
                tokio::spawn(async move {
                    conn_count.fetch_add(1, Ordering::SeqCst);
                    info!(
                        "Active connections: {}",
                        conn_count.load(Ordering::SeqCst)
                    );
                    if let Err(e) = handle_connection(stream, peer_addr, rx).await {
                        warn!("Connection {} closed: {}", peer_addr, e);
                    }
                    conn_count.fetch_sub(1, Ordering::SeqCst);
                    info!(
                        "Active connections: {}",
                        conn_count.load(Ordering::SeqCst)
                    );
                });
            }
            Err(e) => {
                error!("Accept error: {}", e);
            }
        }
    }
}

/// 后台任务：每隔 interval_ms 采集一次指标并广播
async fn metrics_broadcast_task(tx: Arc<broadcast::Sender<Arc<String>>>, interval_ms: u64) {
    let mut collector = MetricsCollector::new();

    // 预热
    time::sleep(Duration::from_millis(interval_ms)).await;

    let mut ticker = time::interval(Duration::from_millis(interval_ms));
    ticker.set_missed_tick_behavior(time::MissedTickBehavior::Skip);

    loop {
        ticker.tick().await;

        // 没有订阅者时跳过采集
        if tx.receiver_count() == 0 {
            continue;
        }

        let metrics = collector.collect();
        match serde_json::to_string(&metrics) {
            Ok(json) => {
                let _ = tx.send(Arc::new(json));
            }
            Err(e) => {
                error!("Serialization error: {}", e);
            }
        }
    }
}

/// 处理单个 WebSocket 连接
async fn handle_connection(
    stream: TcpStream,
    peer_addr: SocketAddr,
    mut rx: broadcast::Receiver<Arc<String>>,
) -> anyhow::Result<()> {
    let ws_stream = accept_async(stream).await?;
    let (mut ws_sender, mut ws_receiver) = ws_stream.split();

    info!("WebSocket handshake complete with {}", peer_addr);

    loop {
        tokio::select! {
            result = rx.recv() => {
                match result {
                    Ok(json) => {
                        ws_sender
                            .send(Message::Text(json.as_str().to_owned().into()))
                            .await?;
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("Client {} lagged, skipped {} messages", peer_addr, n);
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
            msg = ws_receiver.next() => {
                match msg {
                    Some(Ok(Message::Close(_))) | None => {
                        info!("Client {} disconnected", peer_addr);
                        break;
                    }
                    Some(Ok(Message::Ping(data))) => {
                        ws_sender.send(Message::Pong(data)).await?;
                    }
                    Some(Err(e)) => {
                        warn!("WebSocket error from {}: {}", peer_addr, e);
                        break;
                    }
                    _ => {}
                }
            }
        }
    }

    Ok(())
}
