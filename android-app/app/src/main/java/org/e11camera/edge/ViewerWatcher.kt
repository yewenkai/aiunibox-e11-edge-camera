package org.e11camera.edge

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 仅统计观看状态。灯光由用户手动控制，不随观看、守护或服务停止变化。 */
class ViewerWatcher(private val markerPath: String = "/sdcard/e11_edge_camera_watching") {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val watching = AtomicBoolean(false)
    val isWatching: Boolean get() = watching.get()
    fun start() {
        scheduler.scheduleAtFixedRate({
            try {
                watching.set(ShellUtil.execSu("cat $markerPath 2>/dev/null").trim() == "1")
            } catch (e: Exception) { Log.w("ViewerWatcher", "读取观看状态失败", e) }
        }, 3, 2, TimeUnit.SECONDS)
    }
    fun stop() { scheduler.shutdownNow(); watching.set(false) }
}
