import Foundation

// ══════════════════════════════════════════════════════════════════════════════
// SystemMetrics  —  对应 sysmon-ws Rust 服务端的 SystemMetrics 结构
// ══════════════════════════════════════════════════════════════════════════════

struct SystemMetrics: Codable {
    let timestamp:           String
    let cpuUsagePercent:     Float
    let cpuPerCore:          [Float]
    let memoryUsagePercent:  Float
    let memoryUsedMb:        Int64
    let memoryTotalMb:       Int64
    let netRxKbps:           Double
    let netTxKbps:           Double

    enum CodingKeys: String, CodingKey {
        case timestamp
        case cpuUsagePercent   = "cpu_usage_percent"
        case cpuPerCore        = "cpu_per_core"
        case memoryUsagePercent = "memory_usage_percent"
        case memoryUsedMb      = "memory_used_mb"
        case memoryTotalMb     = "memory_total_mb"
        case netRxKbps         = "net_rx_kbps"
        case netTxKbps         = "net_tx_kbps"
    }

    init(
        timestamp:          String = "",
        cpuUsagePercent:    Float  = 0,
        cpuPerCore:         [Float] = [],
        memoryUsagePercent: Float  = 0,
        memoryUsedMb:       Int64  = 0,
        memoryTotalMb:      Int64  = 0,
        netRxKbps:          Double = 0,
        netTxKbps:          Double = 0
    ) {
        self.timestamp          = timestamp
        self.cpuUsagePercent    = cpuUsagePercent
        self.cpuPerCore         = cpuPerCore
        self.memoryUsagePercent = memoryUsagePercent
        self.memoryUsedMb       = memoryUsedMb
        self.memoryTotalMb      = memoryTotalMb
        self.netRxKbps          = netRxKbps
        self.netTxKbps          = netTxKbps
    }
}
