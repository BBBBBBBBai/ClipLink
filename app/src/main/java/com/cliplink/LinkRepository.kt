package com.cliplink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 链接历史记录。
 *
 * 用单文件 JSON 持久化而不是 Room：记录量小（默认上限 200 条）、
 * 无查询需求，引入注解处理器不划算。所有方法都在调用方线程同步执行，
 * 调用点均已放在后台线程或本身轻量。
 */
class LinkRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val prefs = Prefs(context)

    data class Entry(
        val url: String,
        val timestamp: Long,
        val source: String
    ) {
        /** 形如 `09-22 19:30`。 */
        fun formattedTime(pattern: String = "MM-dd HH:mm"): String =
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * 追加一条记录。相同 URL 已存在时更新时间戳并移到最前，避免重复堆叠。
     */
    @Synchronized
    fun add(url: String, source: String, timestamp: Long = System.currentTimeMillis()) {
        val list = load().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, Entry(url, timestamp, source))

        val limit = prefs.historyLimit
        val trimmed = if (list.size > limit) list.subList(0, limit) else list
        save(trimmed)
    }

    @Synchronized
    fun all(): List<Entry> = load()

    @Synchronized
    fun delete(url: String) {
        val list = load().toMutableList()
        list.removeAll { it.url == url }
        save(list)
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString(KEY_URL)
                    if (url.isEmpty()) continue
                    add(
                        Entry(
                            url = url,
                            timestamp = obj.optLong(KEY_TIMESTAMP),
                            source = obj.optString(KEY_SOURCE)
                        )
                    )
                }
            }
        }.getOrElse {
            // 文件损坏时不应让整个 UI 挂掉，丢弃并重建。
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun save(list: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { entry ->
                arr.put(
                    JSONObject().apply {
                        put(KEY_URL, entry.url)
                        put(KEY_TIMESTAMP, entry.timestamp)
                        put(KEY_SOURCE, entry.source)
                    }
                )
            }
            // 先写临时文件再改名，避免写入中途被杀导致历史记录半截损坏。
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val FILE_NAME = "link_history.json"
        const val KEY_URL = "url"
        const val KEY_TIMESTAMP = "ts"
        const val KEY_SOURCE = "src"
    }
}
