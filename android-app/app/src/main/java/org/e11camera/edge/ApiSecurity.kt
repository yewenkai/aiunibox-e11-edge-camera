package org.e11camera.edge

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest

/**
 * 局域网 API 鉴权。
 *
 * token 由部署者写入 /data/local/tmp/e11-edge-camera/api_token。文件不存在或为空时
 * 保持兼容模式，不启用鉴权；启用后接受 Authorization: Bearer <token>。
 */
class ApiSecurity(
    private val tokenPath: String = "/data/local/tmp/e11-edge-camera/api_token"
) {
    val token: String by lazy {
        ShellUtil.execSu("cat $tokenPath 2>/dev/null").trim()
    }

    val isEnabled: Boolean
        get() = token.isNotEmpty()

    fun isAuthorized(session: NanoHTTPD.IHTTPSession): Boolean {
        if (!isEnabled) return true
        val header = session.headers["authorization"].orEmpty()
        val supplied = if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substringAfter(" ").trim()
        } else {
            session.parms["token"].orEmpty()
        }
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )
    }
}
