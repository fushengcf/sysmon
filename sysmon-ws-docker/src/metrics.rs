/// metrics.rs — Linux 系统指标采集模块
///
/// 采集项：CPU 使用率、内存使用率、网络上下行速率
use serde::{Deserialize, Serialize};
use sysinfo::{Networks, System};

/// 单次采集的系统快照
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SystemMetrics {
    /// 时间戳（ISO 8601）
    pub timestamp: String,
    /// 全局 CPU 使用率（0.0 ~ 100.0）
    pub cpu_usage_percent: f32,
    /// 每核 CPU 使用率列表
    pub cpu_per_core: Vec<f32>,
    /// 内存使用率（0.0 ~ 100.0）
    pub memory_usage_percent: f32,
    /// 已用内存（MB）
    pub memory_used_mb: u64,
    /// 总内存（MB）
    pub memory_total_mb: u64,
    /// 网络下行速率（KB/s）
    pub net_rx_kbps: f64,
    /// 网络上行速率（KB/s）
    pub net_tx_kbps: f64,
}

/// 系统指标采集器，持有 sysinfo 状态以便增量计算
pub struct MetricsCollector {
    sys: System,
    networks: Networks,
    prev_rx: u64,
    prev_tx: u64,
    prev_instant: std::time::Instant,
}

impl MetricsCollector {
    pub fn new() -> Self {
        let mut sys = System::new_all();
        sys.refresh_all();

        let mut networks = Networks::new_with_refreshed_list();
        networks.refresh();

        let (rx, tx) = total_net_bytes(&networks);

        Self {
            sys,
            networks,
            prev_rx: rx,
            prev_tx: tx,
            prev_instant: std::time::Instant::now(),
        }
    }

    pub fn collect(&mut self) -> SystemMetrics {
        use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind};
        self.sys.refresh_specifics(
            RefreshKind::new()
                .with_cpu(CpuRefreshKind::everything())
                .with_memory(MemoryRefreshKind::everything()),
        );
        self.networks.refresh();

        // CPU
        let cpu_per_core: Vec<f32> = self.sys.cpus().iter().map(|c| c.cpu_usage()).collect();
        let cpu_usage_percent = if cpu_per_core.is_empty() {
            0.0
        } else {
            cpu_per_core.iter().sum::<f32>() / cpu_per_core.len() as f32
        };

        // Memory
        let memory_total_mb = self.sys.total_memory() / 1024 / 1024;
        let memory_used_mb = self.sys.used_memory() / 1024 / 1024;
        let memory_usage_percent = if memory_total_mb == 0 {
            0.0
        } else {
            memory_used_mb as f32 / memory_total_mb as f32 * 100.0
        };

        // Network speed
        let (cur_rx, cur_tx) = total_net_bytes(&self.networks);
        let elapsed = self.prev_instant.elapsed().as_secs_f64().max(0.001);
        let net_rx_kbps = (cur_rx.saturating_sub(self.prev_rx)) as f64 / elapsed / 1024.0;
        let net_tx_kbps = (cur_tx.saturating_sub(self.prev_tx)) as f64 / elapsed / 1024.0;

        self.prev_rx = cur_rx;
        self.prev_tx = cur_tx;
        self.prev_instant = std::time::Instant::now();

        let timestamp = chrono::Utc::now().to_rfc3339();

        SystemMetrics {
            timestamp,
            cpu_usage_percent,
            cpu_per_core,
            memory_usage_percent,
            memory_used_mb,
            memory_total_mb,
            net_rx_kbps,
            net_tx_kbps,
        }
    }
}

fn total_net_bytes(networks: &Networks) -> (u64, u64) {
    let mut rx = 0u64;
    let mut tx = 0u64;
    for (_name, data) in networks.iter() {
        rx = rx.saturating_add(data.total_received());
        tx = tx.saturating_add(data.total_transmitted());
    }
    (rx, tx)
}
