package com.abhirha.shraa.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val time: Long,
    val command: String,
    val action: String,
    val result: String,
    val success: Boolean
)

/** Local-only command history (newest first), kept as a JSON file in app-private storage. */
class HistoryRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "history.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryItem>> = _items

    @Synchronized
    fun add(item: HistoryItem) {
        val list = (listOf(item) + _items.value).take(MAX_ITEMS)
        _items.value = list
        save(list)
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        file.delete()
    }

    private fun load(): List<HistoryItem> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getLong("t"), o.getString("c"), o.getString("a"), o.getString("r"), o.getBoolean("ok"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().put("t", it.time).put("c", it.command)
                        .put("a", it.action).put("r", it.result).put("ok", it.success)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // History is best-effort; never crash a command because the disk write failed.
        }
    }

    private companion object {
        const val MAX_ITEMS = 500
    }
}
