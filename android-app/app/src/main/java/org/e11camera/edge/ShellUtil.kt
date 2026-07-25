package org.e11camera.edge

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root shell 工具：通过 su -c 执行命令并读取输出。
 * 设备已用 Magisk 获取 root。
 */
object ShellUtil {
    private const val TAG = "ShellUtil"

    /** 执行 root 命令，返回标准输出（合并 stderr）。 */
    fun execSu(cmd: String): String {
        val out = StringBuilder()
        try {
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val br = BufferedReader(InputStreamReader(p.inputStream))
            var line = br.readLine()
            while (line != null) {
                out.append(line).append('\n')
                line = br.readLine()
            }
            p.waitFor()
            br.close()
        } catch (e: Exception) {
            Log.e(TAG, "execSu 失败: $cmd", e)
            out.append("ERROR: ").append(e.message)
        }
        return out.toString()
    }

    /** 异步执行（fire and forget），不关心输出。 */
    fun execSuAsync(cmd: String) {
        Thread { execSu(cmd) }.start()
    }

    /** 检查 root 是否可用 */
    fun hasRoot(): Boolean = execSu("id").contains("uid=0")
}
