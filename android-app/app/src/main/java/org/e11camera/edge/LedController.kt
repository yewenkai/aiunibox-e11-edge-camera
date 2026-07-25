package org.e11camera.edge

/**
 * LED / IR-CUT / 补光灯 控制器。
 * - RGB LED + IR 红外灯 + 补光灯(camera) + 心跳灯(work)：写 sysfs
 * - IR-CUT 滤光片 + 场景模式：通过 ipcamera_test
 *
 * 设备上的 LED 列表：
 *   blue / green / red  — RGB 状态灯
 *   ir                  — 红外夜视灯
 *   camera              — 补光灯（默认常亮 255）
 *   work                — 心跳指示灯（默认绑 heartbeat trigger）
 */
object LedController {

    /** sysfs 控制的所有 LED 名 */
    val allLeds = listOf("blue", "green", "red", "ir", "camera", "work")

    /** 设置 LED 亮度（sysfs 路径），level 0~255
     *  对 work 灯：先解绑 heartbeat trigger，再写亮度 */
    fun setLed(name: String, level: Int): Boolean {
        if (name !in allLeds) return false
        val l = level.coerceIn(0, 255)
        // work 灯默认绑了 heartbeat 触发器，必须先解绑
        if (name == "work") {
            ShellUtil.execSu("echo none > /sys/class/leds/work/trigger")
        }
        ShellUtil.execSu("echo $l > /sys/class/leds/$name/brightness")
        return true
    }

    /** 读取 LED 当前亮度 */
    fun getLed(name: String): Int {
        if (name !in allLeds) return 0
        val out = ShellUtil.execSu("cat /sys/class/leds/$name/brightness")
        return out.trim().toIntOrNull() ?: 0
    }

    /** 关闭所有灯（RGB + IR + 补光 + 心跳） */
    fun allOff() {
        for (led in allLeds) {
            setLed(led, 0)
        }
    }

    /** IR-CUT 开关：on=true 切到夜视（移除红外滤光片） */
    fun setIrcut(on: Boolean) {
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -i ${if (on) 1 else 0}")
    }

    /** 补光灯亮度 0~255（通过 ipcamera_test） */
    fun setFillLight(level: Int) {
        val l = level.coerceIn(0, 255)
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -l $l")
    }

    /** 场景模式：0=日间, 1=夜间, 2=自动 */
    fun setSceneMode(mode: Int) {
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -m ${mode.coerceIn(0, 2)}")
    }
}
