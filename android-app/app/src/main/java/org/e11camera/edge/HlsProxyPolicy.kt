package org.e11camera.edge

/**
 * 将浏览器同源的 /media/cam 路径严格映射到设备本机 MediaMTX HLS 服务。
 *
 * MediaMTX 的播放器会使用相对地址请求 index.m3u8 和分片，因此只要保留路径尾部，
 * 浏览器就不需要直接访问 8888 端口，也不依赖远程访问服务转发额外端口。
 */
internal object HlsProxyPolicy {
    const val PUBLIC_CAMERA_PREFIX = "/media/cam"
    const val UPSTREAM_ORIGIN = "http://127.0.0.1:8888"

    fun upstreamPath(uri: String): String? {
        if (uri != PUBLIC_CAMERA_PREFIX && !uri.startsWith("$PUBLIC_CAMERA_PREFIX/")) {
            return null
        }
        if (uri.any { it == '\\' || it.code < 0x20 }) return null

        val lowered = uri.lowercase()
        if (lowered.contains("%2e") || lowered.contains("%2f") || lowered.contains("%5c")) {
            return null
        }

        val upstream = uri.removePrefix("/media")
        if (upstream.split('/').any { it == "." || it == ".." }) return null
        return upstream
    }

    fun upstreamUrl(uri: String, rawQuery: String?): String? {
        val path = upstreamPath(uri) ?: return null
        if (rawQuery?.any { it == '\r' || it == '\n' } == true) return null
        return buildString {
            append(UPSTREAM_ORIGIN)
            append(path)
            if (!rawQuery.isNullOrBlank()) {
                append('?')
                append(rawQuery)
            }
        }
    }

    fun rewriteLocation(location: String): String {
        val upstreamCamera = "$UPSTREAM_ORIGIN/cam"
        return when {
            location == "/cam" || location.startsWith("/cam/") -> "/media$location"
            location == upstreamCamera || location.startsWith("$upstreamCamera/") ->
                "/media" + location.removePrefix(UPSTREAM_ORIGIN)
            else -> location
        }
    }
}
