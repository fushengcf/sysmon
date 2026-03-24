/// metrics.rs — 跨平台系统指标采集模块
///
/// 支持平台：macOS / Windows / Ubuntu (Linux)
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
    /// 上一次采集时各网卡的累计接收字节数
    prev_rx: u64,
    /// 上一次采集时各网卡的累计发送字节数
    prev_tx: u64,
    /// 上一次采集的时间（用于计算速率）
    prev_instant: std::time::Instant,
}

impl MetricsCollector {
    /// 创建采集器并做一次预热刷新（sysinfo 需要两次采样才能计算 CPU）
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

    /// 刷新并返回最新指标快照
    ///
    /// 建议每秒调用一次，间隔过短会导致 CPU 数值不准确。
    pub fn collect(&mut self) -> SystemMetrics {
        // --- 精准刷新：只刷新 CPU + 内存，跳过进程列表扫描（性能优化）---
        use sysinfo::RefreshKind;
        use sysinfo::CpuRefreshKind;
        use sysinfo::MemoryRefreshKind;
        self.sys.refresh_specifics(
            RefreshKind::new()
                .with_cpu(CpuRefreshKind::everything())
                .with_memory(MemoryRefreshKind::everything()),
        );

        // --- 刷新网络 ---
        self.networks.refresh();

        // --- CPU ---
        let cpu_per_core: Vec<f32> = self
            .sys
            .cpus()
            .iter()
            .map(|c| c.cpu_usage())
            .collect();

        let cpu_usage_percent = if cpu_per_core.is_empty() {
            0.0
        } else {
            cpu_per_core.iter().sum::<f32>() / cpu_per_core.len() as f32
        };

        // --- 内存 ---
        let memory_total_mb = self.sys.total_memory() / 1024 / 1024;
        let memory_used_mb = self.sys.used_memory() / 1024 / 1024;
        let memory_usage_percent = if memory_total_mb == 0 {
            0.0
        } else {
            memory_used_mb as f32 / memory_total_mb as f32 * 100.0
        };

        // --- 网速 ---
        let (cur_rx, cur_tx) = total_net_bytes(&self.networks);
        let elapsed = self.prev_instant.elapsed().as_secs_f64().max(0.001);

        let net_rx_kbps = (cur_rx.saturating_sub(self.prev_rx)) as f64 / elapsed / 1024.0;
        let net_tx_kbps = (cur_tx.saturating_sub(self.prev_tx)) as f64 / elapsed / 1024.0;

        self.prev_rx = cur_rx;
        self.prev_tx = cur_tx;
        self.prev_instant = std::time::Instant::now();

        // --- 时间戳 ---
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

/// 汇总所有网卡的累计收发字节数
fn total_net_bytes(networks: &Networks) -> (u64, u64) {
    let mut rx = 0u64;
    let mut tx = 0u64;
    for (_name, data) in networks.iter() {
        rx = rx.saturating_add(data.total_received());
        tx = tx.saturating_add(data.total_transmitted());
    }
    (rx, tx)
}

// ─────────────────────────────────────────────────────────────────────────────
// 轻量网速采集器（无客户端连接时使用，不持有 System 对象）
// ─────────────────────────────────────────────────────────────────────────────

/// 只采集网速的轻量采集器
///
/// 相比 `MetricsCollector`：
/// - 不持有 `System`（省去 ~1-2MB 内存 + CPU/内存刷新开销）
/// - 每次 `collect_net()` 只读取网卡计数器，耗时 < 0.1ms
pub struct NetOnlyCollector {
    networks: Networks,
    prev_rx: u64,
    prev_tx: u64,
    prev_instant: std::time::Instant,
}

impl NetOnlyCollector {
    pub fn new() -> Self {
        let mut networks = Networks::new_with_refreshed_list();
        networks.refresh();
        let (rx, tx) = total_net_bytes(&networks);
        Self {
            networks,
            prev_rx: rx,
            prev_tx: tx,
            prev_instant: std::time::Instant::now(),
        }
    }

    /// 返回 (rx_kbps, tx_kbps)
    pub fn collect_net(&mut self) -> (f64, f64) {
        self.networks.refresh();
        let (cur_rx, cur_tx) = total_net_bytes(&self.networks);
        let elapsed = self.prev_instant.elapsed().as_secs_f64().max(0.001);

        let rx_kbps = (cur_rx.saturating_sub(self.prev_rx)) as f64 / elapsed / 1024.0;
        let tx_kbps = (cur_tx.saturating_sub(self.prev_tx)) as f64 / elapsed / 1024.0;

        self.prev_rx = cur_rx;
        self.prev_tx = cur_tx;
        self.prev_instant = std::time::Instant::now();

        (rx_kbps, tx_kbps)
    }
}
