package com.sysmon.monitor.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 持久化保存 WebSocket 链接列表（最多 MAX_URLS 个）。
 * 使用 SharedPreferences 存储，key 为 "url_0" ~ "url_9"，
 * "url_count" 记录当前数量。
 */
class UrlRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "sysmon_urls"
        private const val KEY_COUNT  = "url_count"
        private const val KEY_PREFIX = "url_"
        const val MAX_URLS = 10
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _urls = MutableStateFlow<List<String>>(loadAll())
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    // ── 读取 ──────────────────────────────────────────────────────────────────

    private fun loadAll(): List<String> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { i ->
            prefs.getString("$KEY_PREFIX$i", null)
        }
    }

    // ── 写入 ──────────────────────────────────────────────────────────────────

    /**
     * 添加一个链接。若已存在则移到最前；超过上限时删除最旧的。
     * 返回操作后的列表。
     */
    fun addUrl(url: String): List<String> {
        val current = _urls.value.toMutableList()
        current.remove(url)          // 去重：先移除旧的
        current.add(0, url)          // 插到最前（最近使用）
        if (current.size > MAX_URLS) current.removeAt(current.lastIndex)
        persist(current)
        _urls.value = current
        return current
    }

    /**
     * 删除指定链接。
     */
    fun removeUrl(url: String): List<String> {
        val current = _urls.value.toMutableList()
        current.remove(url)
        persist(current)
        _urls.value = current
        return current
    }

    // ── 持久化 ────────────────────────────────────────────────────────────────

    private fun persist(list: List<String>) {
        prefs.edit().apply {
            // 先清空旧 key
            val oldCount = prefs.getInt(KEY_COUNT, 0)
            for (i in 0 until oldCount) remove("$KEY_PREFIX$i")
            // 写入新数据
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, url -> putString("$KEY_PREFIX$i", url) }
            apply()
        }
    }
}
