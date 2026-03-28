package com.sysmon.monitor

import android.app.Application
import com.sysmon.monitor.data.repository.UrlRepository
import com.sysmon.monitor.data.websocket.SysMonWebSocket

/**
 * Application 单例：持有全局共享的 SysMonWebSocket 和 UrlRepository。
 * Service 和 ViewModel 都从这里取同一实例，保证状态一致。
 */
class SysMonApp : Application() {

    /** 全局唯一的 WebSocket 客户端 */
    val wsClient: SysMonWebSocket by lazy { SysMonWebSocket(this) }

    /** 全局唯一的 URL 仓库 */
    val urlRepo: UrlRepository by lazy { UrlRepository(this) }
}
