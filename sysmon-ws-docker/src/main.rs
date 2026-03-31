mod metrics;
mod server;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_max_level(tracing::Level::INFO)
        .init();

    let addr = std::env::var("LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:9527".to_string());
    let interval_ms: u64 = std::env::var("INTERVAL_MS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(1000);

    tracing::info!("sysmon-ws-docker starting on ws://{}", addr);
    tracing::info!("Push interval: {}ms", interval_ms);

    server::run_server(&addr, interval_ms).await?;

    Ok(())
}
