package org.e11camera.edge

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

/**
 * 网络工具：获取设备局域网 IP 地址。
 */
object NetworkUtil {

    /** 获取设备 IP（优先 wlan0），失败返回 0.0.0.0 */
    @Suppress("DEPRECATION")
    fun getIpAddress(context: Context): String {
        // 先遍历网络接口（更可靠）
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return fallback(context)
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                        return a.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback(context)
    }

    @Suppress("DEPRECATION")
    private fun fallback(context: Context): String {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip == 0) "0.0.0.0"
            else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }
}
