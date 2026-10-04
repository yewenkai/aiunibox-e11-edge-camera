package org.e11camera.edge

/**
 * LED / IR-CUT / 补光灯 控制器。
 * - RGB LED、IR 红外灯、补光灯、心跳灯与存储活动灯：写 sysfs
 * - IR-CUT 滤光片 + 场景模式：通过 ipcamera_test
 *
 * 设备上的 LED 列表：
 *   blue / green / red  — RGB 状态灯
 *   ir                  — 红外夜视灯
 *   camera              — 补光灯（默认常亮 255）
 *   work                — 心跳指示灯（默认绑 heartbeat trigger）
 *   mmc2::              — 存储活动 LED 节点（默认绑 mmc2 trigger）
 */
object LedController {

    /** sysfs 控制的所有 LED 名 */
    val allLeds = listOf("blue", "green", "red", "ir", "camera", "work", "mmc2::")
    private val knownLevels = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var requestedCameraLevel = 0

    /** 相机 HAL 打开时可能恢复默认补光亮度，重新应用用户选择（初始为关闭）。 */
    @Synchronized
    fun restoreRequestedLights() {
        val levels = allLeds.associateWith { name ->
            if (name == "camera") requestedCameraLevel else knownLevels[name] ?: 0
        }
        // HAL 重新打开后恢复用户选择；一次 root 调用覆盖所有节点与触发器。
        val commands = levels.map { (name, level) -> ledWriteCommand(name, level) }
        ShellUtil.execSu(commands.joinToString("; "))
        knownLevels.putAll(levels)
    }

    private fun ledWriteCommand(name: String, level: Int): String {
        val base = "/sys/class/leds/$name"
        return "if [ -f $base/trigger ]; then echo none > $base/trigger; fi; echo $level > $base/brightness"
    }

    /** 设置 LED 亮度（sysfs 路径），level 0~255
     *  先解绑自动触发器，再写亮度，避免心跳或存储活动重新点亮。 */
    @Synchronized
    fun setLed(name: String, level: Int): Boolean {
        if (name !in allLeds) return false
        val l = level.coerceIn(0, 255)
        if (name == "camera") requestedCameraLevel = l
        ShellUtil.execSu(ledWriteCommand(name, l))
        knownLevels[name] = l
        return true
    }

    /** 读取 LED 当前亮度；与用户写入共享锁，防止旧读取覆盖关灯选择。 */
    @Synchronized
    fun getLed(name: String): Int {
        if (name !in allLeds) return 0
        knownLevels[name]?.let { return it }
        val out = ShellUtil.execSu("cat /sys/class/leds/$name/brightness")
        val value = out.trim().toIntOrNull() ?: 0
        knownLevels[name] = value
        return value
    }

    /** 关闭全部可控灯，同时取消心跳、存储活动等自动触发器。 */
    @Synchronized
    fun allOff() {
        setFillLight(0)
        for (led in allLeds) {
            setLed(led, 0)
        }
    }

    /** IR-CUT 开关：on=true 切到夜视（移除红外滤光片） */
    fun setIrcut(on: Boolean) {
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -i ${if (on) 1 else 0}")
    }

    /** 补光灯亮度 0~255（通过 ipcamera_test） */
    @Synchronized
    fun setFillLight(level: Int) {
        val l = level.coerceIn(0, 255)
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -l $l")
        requestedCameraLevel = l
        knownLevels["camera"] = l
    }

    /** 场景模式：0=日间, 1=夜间, 2=自动 */
    fun setSceneMode(mode: Int) {
        ShellUtil.execSu("/vendor/bin/hw/ipcamera_test -m ${mode.coerceIn(0, 2)}")
    }
}
