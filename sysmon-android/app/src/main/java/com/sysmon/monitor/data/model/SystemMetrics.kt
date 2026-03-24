package com.sysmon.monitor.data.model

import com.google.gson.annotations.SerializedName

/**
 * 与 sysmon-ws Rust 服务端 SystemMetrics 结构对应
 */
data class SystemMetrics(
    @SerializedName("timestamp")
    val timestamp: String = "",

    @SerializedName("cpu_usage_percent")
    val cpuUsagePercent: Float = 0f,

    @SerializedName("cpu_per_core")
    val cpuPerCore: List<Float> = emptyList(),

    @SerializedName("memory_usage_percent")
    val memoryUsagePercent: Float = 0f,

    @SerializedName("memory_used_mb")
    val memoryUsedMb: Long = 0L,

    @SerializedName("memory_total_mb")
    val memoryTotalMb: Long = 0L,

    @SerializedName("net_rx_kbps")
    val netRxKbps: Double = 0.0,

    @SerializedName("net_tx_kbps")
    val netTxKbps: Double = 0.0,
)
