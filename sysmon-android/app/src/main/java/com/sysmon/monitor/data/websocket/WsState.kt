package com.sysmon.monitor.data.websocket

sealed class WsState {
    object Disconnected : WsState()
    object Connecting : WsState()
    object Connected : WsState()
    data class Error(val message: String) : WsState()
}
