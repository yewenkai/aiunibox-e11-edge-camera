package org.e11camera.edge

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bark 直推通知（iOS，多设备）。
 *
 * 设备直接调用 Bark 服务器，不依赖 Home Assistant / NAS 在线。
 * 支持多个推送目标（各自带备注），启用中的目标同时收到通知；
 * 想只推某台设备时，把其他目标停用即可。
 */
class BarkNotifier(private val state: DeviceStateStore) {

    companion object {
        private const val TAG = "BarkNotifier"
        private const val SERVER = "https://api.day.app"
        private const val TIMEOUT_MS = 8000
    }

    data class Target(val name: String, val key: String, val enabled: Boolean)

    /** 当前目标列表（含停用的）。 */
    fun targets(): List<Target> = try {
        val arr = JSONArray(state.barkTargets)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Target(
                o.optString("name", "未命名"),
                o.optString("key", ""),
                o.optBoolean("enabled", true)
            )
        }.filter { it.key.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "解析推送目标失败: ${e.message}")
        emptyList()
    }

    fun saveTargets(list: List<Target>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("name", t.name.take(16))
                    .put("key", t.key)
                    .put("enabled", t.enabled)
            )
        }
        state.barkTargets = arr.toString()
    }

    /** 添加目标；Key 重复返回 false。 */
    fun add(name: String, key: String): Boolean {
        val k = key.trim()
        if (k.length !in 8..128) return false
        val list = targets().toMutableList()
        if (list.any { it.key == k }) return false
        val n = name.trim().ifEmpty { "设备${list.size + 1}" }
        list.add(Target(n, k, true))
        saveTargets(list)
        return true
    }

    fun remove(key: String) {
        saveTargets(targets().filter { it.key != key })
    }

    fun setEnabled(key: String, enabled: Boolean) {
        saveTargets(targets().map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    /** 至少有一个启用目标。 */
    val configured: Boolean
        get() = targets().any { it.enabled }

    /**
     * 向所有启用目标推送。timeSensitive 级别可穿透 iPhone 专注模式
     * （需在 Bark App 内允许）。
     */
    fun notify(title: String, body: String) {
        for (t in targets().filter { it.enabled }) {
            push(t, title, body)
        }
    }

    /** 只向指定 Key 推送（测试按钮用）。 */
    fun notifyOne(key: String, title: String, body: String) {
        targets().firstOrNull { it.key == key }?.let { push(it, title, body) }
    }

    private fun push(t: Target, title: String, body: String) {
        Thread {
            try {
                val url = Uri.parse("$SERVER/${t.key}/")
                    .buildUpon()
                    .appendPath(title)
                    .appendPath(body)
                    .appendQueryParameter("group", "宝宝守护")
                    .appendQueryParameter("level", "timeSensitive")
                    .appendQueryParameter("isArchive", "1")
                    .build()
                    .toString()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    Log.w(TAG, "Bark(${t.name}) 返回 HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bark(${t.name}) 推送失败: ${e.message}")
            }
        }.start()
    }
}
