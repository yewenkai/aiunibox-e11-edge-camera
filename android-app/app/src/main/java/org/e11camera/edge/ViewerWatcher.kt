package org.e11camera.edge

import android.os.FileObserver
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 读取 MediaMTX hooks 写入的短租约，仅报告观看状态，不改变摄像头或灯光设置。
 *
 * marker 位于 app 自有目录，直接读取即可；FileObserver 处理连接变化，低频读取
 * 兜底丢失事件及失效租约。格式为 `0|1 elapsedRealtimeMs`，写入者每 10 秒续租。
 */
class ViewerWatcher(
    private val markerPath: String = DEFAULT_MARKER_PATH,
    private val onWatchingChanged: (Boolean) -> Unit = {}
) {
    companion object {
        const val DEFAULT_MARKER_PATH =
            "/data/user/0/org.e11camera.edge/files/viewer-state/watchers"
        private const val TAG = "ViewerWatcher"
        private const val FALLBACK_INTERVAL_SECONDS = 15L
        private const val MAX_MARKER_BYTES = 128
        private const val OBSERVER_EVENTS = FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "viewer-state")
    }
    private val marker = File(markerPath)
    private val watching = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val observerInvalidated = AtomicBoolean(false)
    // 强引用保留 observer；它的创建和停止都在 scheduler 上执行。
    private var observer: FileObserver? = null

    val isWatching: Boolean get() = watching.get()

    @Synchronized
    fun start() {
        if (stopped.get() || !started.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(
            { refresh() }, 0, FALLBACK_INTERVAL_SECONDS, TimeUnit.SECONDS
        )
    }

    private fun refresh() {
        if (stopped.get()) return
        val active = try {
            ensureObserver()
            val text = marker.inputStream().use { stream ->
                val bytes = ByteArray(MAX_MARKER_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > MAX_MARKER_BYTES) "" else String(bytes, 0, count, Charsets.US_ASCII)
            }
            ViewerMarkerPolicy.isWatching(text, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            // 缺失、不可读和格式错误都必须撤销 watching=true，而不能保留旧值。
            Log.w(TAG, "观看状态租约不可读: ${e.javaClass.simpleName}")
            false
        }
        publish(active)
    }

    @Synchronized
    private fun publish(active: Boolean) {
        if (stopped.get()) return
        if (watching.getAndSet(active) != active) {
            try {
                onWatchingChanged(active)
            } catch (e: Exception) {
                Log.w(TAG, "观看状态回调失败", e)
            }
        }
    }

    private fun ensureObserver() {
        val directory = requireNotNull(marker.parentFile)
        val invalidated = observerInvalidated.getAndSet(false)
        if (observer != null && !invalidated && directory.isDirectory) return
        observer?.stopWatching()
        observer = null
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create viewer state directory" }
        // 由 app 创建目录和文件，让 root hooks 原位写入时保留 app UID 和 SELinux 标签。
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        if (!marker.exists()) marker.createNewFile()
        Os.chmod(marker.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        observer = object : FileObserver(directory, OBSERVER_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (stopped.get()) return
                if (event and (DELETE_SELF or MOVE_SELF) != 0) observerInvalidated.set(true)
                if (path != null && path != marker.name) return
                try {
                    scheduler.execute { refresh() }
                } catch (_: RejectedExecutionException) {
                    // stop 后可能仍有正在递送的 inotify 事件。
                }
            }
        }.also { it.startWatching() }
    }

    @Synchronized
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        watching.set(false)
        // 将 observer 清理排到同一线程，避免和 refresh 的创建过程交错。
        if (started.get()) scheduler.execute {
            observer?.stopWatching()
            observer = null
        }
        scheduler.shutdown()
    }
}
