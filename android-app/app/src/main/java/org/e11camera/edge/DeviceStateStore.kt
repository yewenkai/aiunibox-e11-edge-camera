package org.e11camera.edge

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 保存 Home Assistant 需要的设备状态。
 *
 * 云台没有可靠的硬件绝对零点，因此这里只维护“软件位置”。首次部署时当前位置为 0，
 * 之后每次由本应用发出的转动都会更新位置；外部直接调用厂商工具会造成位置漂移。
 */
class DeviceStateStore(context: Context) {
    companion object {
        private const val PREFS = "e11_device_state"
        private const val KEY_POSITION = "pan_position_steps"
        private const val KEY_PRESETS = "pan_presets"
        private const val KEY_PRIVACY = "privacy_mode"
        private const val KEY_SCENE = "scene_mode"
        private const val KEY_IRCUT = "ircut"
        private const val KEY_FILL_LIGHT = "fill_light"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val position = AtomicInteger(prefs.getInt(KEY_POSITION, 0))

    fun positionSteps(): Int = position.get()

    fun positionDegrees(): Double =
        position.get() * 360.0 / MotorController.STEPS_PER_CIRCLE

    @Synchronized
    fun applyMovement(deltaSteps: Int) {
        val half = MotorController.STEPS_PER_CIRCLE / 2
        var next = (position.get() + deltaSteps) % MotorController.STEPS_PER_CIRCLE
        if (next > half) next -= MotorController.STEPS_PER_CIRCLE
        if (next < -half) next += MotorController.STEPS_PER_CIRCLE
        position.set(next)
        prefs.edit().putInt(KEY_POSITION, next).apply()
    }

    fun setSoftZero() {
        position.set(0)
        prefs.edit().putInt(KEY_POSITION, 0).apply()
    }

    @Synchronized
    fun savePreset(name: String): Boolean {
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return false
        val presets = readPresets()
        presets.put(name, position.get())
        prefs.edit().putString(KEY_PRESETS, presets.toString()).apply()
        return true
    }

    @Synchronized
    fun presetPosition(name: String): Int? {
        val presets = readPresets()
        return if (presets.has(name)) presets.optInt(name) else null
    }

    @Synchronized
    fun presetNames(): List<String> {
        val presets = readPresets()
        val names = mutableListOf<String>()
        val keys = presets.keys()
        while (keys.hasNext()) names += keys.next()
        if ("home" !in names) {
            presets.put("home", 0)
            prefs.edit().putString(KEY_PRESETS, presets.toString()).apply()
            names += "home"
        }
        return names.sorted()
    }

    private fun readPresets(): JSONObject =
        try {
            JSONObject(prefs.getString(KEY_PRESETS, """{"home":0}""") ?: """{"home":0}""")
        } catch (_: Exception) {
            JSONObject("""{"home":0}""")
        }

    var privacyMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY, value).apply()
        }

    var sceneMode: Int
        get() = prefs.getInt(KEY_SCENE, 2)
        set(value) {
            prefs.edit().putInt(KEY_SCENE, value.coerceIn(0, 2)).apply()
        }

    var ircutEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRCUT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IRCUT, value).apply()
        }

    var fillLightLevel: Int
        get() = prefs.getInt(KEY_FILL_LIGHT, 0)
        set(value) {
            prefs.edit().putInt(KEY_FILL_LIGHT, value.coerceIn(0, 255)).apply()
        }
}
