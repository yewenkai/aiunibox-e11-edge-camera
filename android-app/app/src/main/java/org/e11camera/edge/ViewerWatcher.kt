package org.e11camera.edge

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拉流感知：读取 mediamtx hook 脚本写的标记文件，判断是否有人拉流。
 * 有人拉流时亮指示灯（camera 补光灯），没人时灭灯。
 * 让被监控者知道自己正在被观看。
 *
 * 工作原理：
 * mediamtx 的 runOnRead hook → 写 "1" 到 /sdcard/e11_edge_camera_watching
 * mediamtx 的 runOnUnread hook → 写 "0" 到 /sdcard/e11_edge_camera_watching
 * App 每 2 秒读这个文件，控制 LED。
 */
class ViewerWatcher(
    private val watchLed: String = "camera",
    private val watchLedLevel: Int = 255,
    private val markerPath: String = "/sdcard/e11_edge_camera_watching"
) {
    companion object {
        private const val TAG = "ViewerWatcher"
        private const val POLL_INTERVAL_SEC = 2L
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val wasWatching = AtomicBoolean(false)

    fun start() {
        // 标记由 mediamtx 的会话级 hook 维护。这里不主动清零，
        // 避免 App 重启时把仍在观看的会话误判为无人观看。
        scheduler.scheduleAtFixedRate({ check() }, 3, POLL_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "拉流感知已启动（每 ${POLL_INTERVAL_SEC}s 轮询标记文件）")
    }

    private fun check() {
        try {
            // 用 ShellUtil 读标记文件（避免主线程 IO 限制）
            val content = ShellUtil.execSu("cat $markerPath 2>/dev/null").trim()
            val hasViewers = content == "1"

            if (hasViewers && !wasWatching.get()) {
                Log.i(TAG, "检测到观看者，亮灯（$watchLed=$watchLedLevel）")
                LedController.setLed(watchLed, watchLedLevel)
                wasWatching.set(true)
            } else if (!hasViewers && wasWatching.get()) {
                Log.i(TAG, "无观看者，灭灯（$watchLed=0）")
                LedController.setLed(watchLed, 0)
                wasWatching.set(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "check 异常", e)
        }
    }

    fun stop() {
        scheduler.shutdownNow()
        if (wasWatching.get()) {
            LedController.setLed(watchLed, 0)
        }
    }
}
