package com.sysmon.monitor.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 持久化保存 WebSocket 链接列表（最多 MAX_URLS 个）及每条链接的备注。
 * 使用 SharedPreferences 存储：
 *   "url_count"   — 数量
 *   "url_0" ~ "url_9"    — 链接
 *   "remark_0" ~ "remark_9" — 对应备注（可为空）
 *   "cookie"      — 全局 Cookie 字符串（发起 WS 握手时附加到请求头）
 */
class UrlRepository(context: Context) {

    companion object {
        private const val PREFS_NAME    = "sysmon_urls"
        private const val KEY_COUNT     = "url_count"
        private const val KEY_PREFIX    = "url_"
        private const val REMARK_PREFIX = "remark_"
        private const val KEY_COOKIE    = "cookie"
        const val MAX_URLS = 10
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _urls    = MutableStateFlow<List<String>>(loadAll())
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    // remarks 与 urls 一一对应，空字符串表示无备注
    private val _remarks = MutableStateFlow<List<String>>(loadAllRemarks())
    val remarks: StateFlow<List<String>> = _remarks.asStateFlow()

    // 全局 Cookie（所有连接共用），空字符串表示不附加
    private val _cookie = MutableStateFlow(prefs.getString(KEY_COOKIE, "") ?: "")
    val cookie: StateFlow<String> = _cookie.asStateFlow()

    // ── 读取 ──────────────────────────────────────────────────────────────────

    private fun loadAll(): List<String> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { i ->
            prefs.getString("$KEY_PREFIX$i", null)
        }
    }

    private fun loadAllRemarks(): List<String> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).map { i ->
            prefs.getString("$REMARK_PREFIX$i", "") ?: ""
        }
    }

    /** 获取指定 url 的备注，不存在返回空字符串 */
    fun getRemarkFor(url: String): String {
        val idx = _urls.value.indexOf(url)
        return if (idx >= 0) _remarks.value.getOrElse(idx) { "" } else ""
    }

    // ── 写入 ──────────────────────────────────────────────────────────────────

    /**
     * 添加一个链接。
     * 若已存在则保持原顺序不变；只有新链接才追加到列表末尾。
     * 超过上限时删除最旧的。
     */
    fun addUrl(url: String): List<String> {
        val currentUrls    = _urls.value.toMutableList()
        val currentRemarks = _remarks.value.toMutableList()

        val existingIdx = currentUrls.indexOf(url)
        if (existingIdx >= 0) {
            return currentUrls
        }

        currentUrls.add(url)
        currentRemarks.add("")

        if (currentUrls.size > MAX_URLS) {
            currentUrls.removeAt(0)
            currentRemarks.removeAt(0)
        }
        persist(currentUrls, currentRemarks)
        _urls.value    = currentUrls
        _remarks.value = currentRemarks
        return currentUrls
    }

    /**
     * 删除指定链接（同时删除对应备注）。
     */
    fun removeUrl(url: String): List<String> {
        val currentUrls    = _urls.value.toMutableList()
        val currentRemarks = _remarks.value.toMutableList()
        val idx = currentUrls.indexOf(url)
        if (idx >= 0) {
            currentUrls.removeAt(idx)
            if (idx < currentRemarks.size) currentRemarks.removeAt(idx)
        }
        persist(currentUrls, currentRemarks)
        _urls.value    = currentUrls
        _remarks.value = currentRemarks
        return currentUrls
    }

    /**
     * 保存/更新指定链接的备注。
     */
    fun saveRemark(url: String, remark: String) {
        val currentUrls    = _urls.value.toMutableList()
        val currentRemarks = _remarks.value.toMutableList()
        val idx = currentUrls.indexOf(url)
        if (idx < 0) return
        while (currentRemarks.size <= idx) currentRemarks.add("")
        currentRemarks[idx] = remark.trim()
        persist(currentUrls, currentRemarks)
        _remarks.value = currentRemarks
    }

    /**
     * 保存/更新全局 Cookie。
     */
    fun saveCookie(cookie: String) {
        val trimmed = cookie.trim()
        prefs.edit().putString(KEY_COOKIE, trimmed).apply()
        _cookie.value = trimmed
    }

    // ── 持久化 ────────────────────────────────────────────────────────────────

    private fun persist(urls: List<String>, remarks: List<String>) {
        prefs.edit().apply {
            val oldCount = prefs.getInt(KEY_COUNT, 0)
            for (i in 0 until oldCount) {
                remove("$KEY_PREFIX$i")
                remove("$REMARK_PREFIX$i")
            }
            putInt(KEY_COUNT, urls.size)
            urls.forEachIndexed { i, url ->
                putString("$KEY_PREFIX$i", url)
                putString("$REMARK_PREFIX$i", remarks.getOrElse(i) { "" })
            }
            apply()
        }
    }
}
