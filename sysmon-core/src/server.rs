/// server.rs — WebSocket 服务器模块
///
/// 每个连入的客户端都会收到独立的推送流，每秒推送一次系统指标 JSON。
/// 多客户端并发安全：指标采集在单独任务中运行，通过 broadcast channel 分发。
///
/// `run_server` 返回一个 `Arc<AtomicUsize>` 表示当前活跃连接数，
/// 主循环可据此判断是否有设备连接，从而切换采集模式。
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

/// 广播 channel 容量（允许最多积压 N 条消息）
const BROADCAST_CAPACITY: usize = 16;

/// 启动 WebSocket 服务器
///
/// # 参数
/// - `addr`: 监听地址，例如 `"0.0.0.0:9001"`
/// - `interval_ms`: 推送间隔（毫秒），默认 1000
///
/// # 返回
/// `Arc<AtomicUsize>` — 当前活跃连接数，可在外部轮询
pub async fn run_server(addr: &str, interval_ms: u64) -> anyhow::Result<Arc<AtomicUsize>> {
    let listener = TcpListener::bind(addr).await?;
    info!("WebSocket server listening on ws://{}", addr);

    // 活跃连接计数器，对外暴露
    let conn_count = Arc::new(AtomicUsize::new(0));

    // broadcast channel：采集任务 → 所有连接的客户端
    let (tx, _rx) = broadcast::channel::<Arc<String>>(BROADCAST_CAPACITY);
    let tx = Arc::new(tx);

    // 启动后台采集任务
    {
        let tx = Arc::clone(&tx);
        tokio::spawn(async move {
            metrics_broadcast_task(tx, interval_ms).await;
        });
    }

    // 接受客户端连接（在独立 task 中运行，不阻塞调用方）
    {
        let conn_count = Arc::clone(&conn_count);
        tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, peer_addr)) => {
                        info!("New connection from {}", peer_addr);
                        let rx = tx.subscribe();
                        let conn_count = Arc::clone(&conn_count);
                        tokio::spawn(async move {
                            conn_count.fetch_add(1, Ordering::SeqCst);
                            if let Err(e) = handle_connection(stream, peer_addr, rx).await {
                                warn!("Connection {} closed: {}", peer_addr, e);
                            }
                            conn_count.fetch_sub(1, Ordering::SeqCst);
                        });
                    }
                    Err(e) => {
                        error!("Accept error: {}", e);
                    }
                }
            }
        });
    }

    Ok(conn_count)
}

/// 后台任务：每隔 interval_ms 采集一次指标并广播
async fn metrics_broadcast_task(tx: Arc<broadcast::Sender<Arc<String>>>, interval_ms: u64) {
    let mut collector = MetricsCollector::new();

    // 预热：等待一个采样周期，让 sysinfo 计算出有效的 CPU 使用率
    time::sleep(Duration::from_millis(interval_ms)).await;

    let mut ticker = time::interval(Duration::from_millis(interval_ms));
    ticker.set_missed_tick_behavior(time::MissedTickBehavior::Skip);

    loop {
        ticker.tick().await;

        // 如果没有订阅者，跳过采集（节省 CPU）
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
            // 收到新的指标广播 → 推送给客户端
            result = rx.recv() => {
                match result {
                    Ok(json) => {
                        ws_sender.send(Message::Text(json.as_str().to_owned().into())).await?;
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("Client {} lagged, skipped {} messages", peer_addr, n);
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        info!("Broadcast channel closed, disconnecting {}", peer_addr);
                        break;
                    }
                }
            }

            // 处理客户端发来的消息（Ping / Close）
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
                    _ => {} // 忽略其他消息类型
                }
            }
        }
    }

    Ok(())
}
