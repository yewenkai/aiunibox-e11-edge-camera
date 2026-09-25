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
        private const val KEY_BABY_MODE = "baby_monitor_enabled"
        private const val KEY_LENS_COVER_CLOSED = "lens_cover_closed"
        private const val KEY_BABY_SENSITIVITY = "baby_sensitivity"
        private const val KEY_MOTION_ROI = "motion_roi"
        private const val LEGACY_KEY_BABY_ROI = "baby_roi"
        private const val KEY_BARK_KEY = "bark_key"
        private const val KEY_BARK_TARGETS = "bark_targets"
        private const val KEY_CRY_NOISE_RMS = "cry_noise_rms"
        private const val KEY_CRY_NOISE_SCORE = "cry_noise_score"
        private const val KEY_CRY_CALIBRATED_AT = "cry_calibrated_at"
        private const val KEY_ADULT_MIN_HEIGHT = "adult_min_height"
        private const val KEY_ADULT_MIN_AREA = "adult_min_area"
        private const val KEY_ADULT_MEDIAN_HEIGHT = "adult_median_height"
        private const val KEY_ADULT_MEDIAN_AREA = "adult_median_area"
        private const val KEY_ADULT_CALIBRATED_AT = "adult_calibrated_at"
        private const val KEY_POSE_ANALYSIS = "pose_analysis_enabled"
        private const val KEY_POSE_NOTIFICATIONS = "pose_notifications_enabled"
        private const val KEY_POSE_DEBUG_OVERLAY = "pose_debug_overlay_enabled"
        private const val KEY_POSE_SIDE_RISK = "pose_side_risk_enabled"
        private const val KEY_FACE_VISIBILITY = "face_visibility_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val position = AtomicInteger(prefs.getInt(KEY_POSITION, 0))
    private val bootCount = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    /** 同一次开机内保留手动暂停；设备重启默认恢复，隐私开关另行保留。 */
    var captureEnabled: Boolean
        get() = prefs.getInt("capture_boot", -2) != bootCount || prefs.getBoolean("capture_enabled", true)
        set(value) { prefs.edit().putInt("capture_boot", bootCount).putBoolean("capture_enabled", value).apply() }

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

    /** 宝宝守护总开关（动作检测 + LED 抑制）。
     *  用同步 commit：守护脚本 force-stop 重启 App 时，apply 的异步落盘可能丢失。 */
    var babyMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_BABY_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BABY_MODE, value).commit()
        }

    /**
     * 实体镜头罩的人工声明状态。它不会关闭摄像头硬件；守护据此停用视觉分析和短片，
     * 只保留音频检测。使用同步 commit，确保异常重启后仍按实际镜头状态恢复。
     */
    var lensCoverClosed: Boolean
        get() = prefs.getBoolean(KEY_LENS_COVER_CLOSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LENS_COVER_CLOSED, value).commit()
        }

    /** 宝宝守护灵敏度：0=低 1=中 2=高 */
    var babySensitivity: Int
        get() = prefs.getInt(KEY_BABY_SENSITIVITY, 1)
        set(value) {
            prefs.edit().putInt(KEY_BABY_SENSITIVITY, value.coerceIn(0, 2)).commit()
        }

    /** 动作检测重点区域："x,y,w,h" 归一化坐标（0-1），默认全画面。 */
    var motionRoi: String
        get() {
            prefs.getString(KEY_MOTION_ROI, null)?.let { return it }
            val legacy = prefs.getString(LEGACY_KEY_BABY_ROI, null) ?: "0,0,1,1"
            prefs.edit()
                .putString(KEY_MOTION_ROI, legacy)
                .remove(LEGACY_KEY_BABY_ROI)
                .commit()
            return legacy
        }
        set(value) {
            prefs.edit().putString(KEY_MOTION_ROI, value).commit()
        }

    /** Bark 直推 Key（旧单设备字段，仅用于向 barkTargets 迁移） */
    var barkKey: String
        get() = prefs.getString(KEY_BARK_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_BARK_KEY, value.trim()).commit()
        }

    /** Bark 推送目标列表 JSON：[{"name":"..","key":"..","enabled":true}]。
     *  首次读取时把旧单 Key 字段迁移为默认目标。 */
    var barkTargets: String
        get() {
            val stored = prefs.getString(KEY_BARK_TARGETS, "") ?: ""
            if (stored.isNotEmpty()) return stored
            val legacy = barkKey
            if (legacy.isEmpty()) return "[]"
            val seeded = org.json.JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("name", "默认设备")
                        .put("key", legacy)
                        .put("enabled", true)
                )
                .toString()
            prefs.edit().putString(KEY_BARK_TARGETS, seeded).commit()
            return seeded
        }
        set(value) {
            prefs.edit().putString(KEY_BARK_TARGETS, value).commit()
        }

    /** 场景校准只保存统计量，不保存录音或画面。 */
    var cryNoiseRms: Float
        get() = prefs.getFloat(KEY_CRY_NOISE_RMS, 0f)
        set(value) { prefs.edit().putFloat(KEY_CRY_NOISE_RMS, value.coerceIn(0f, 1f)).commit() }

    var cryNoiseScore: Float
        get() = prefs.getFloat(KEY_CRY_NOISE_SCORE, 0f)
        set(value) { prefs.edit().putFloat(KEY_CRY_NOISE_SCORE, value.coerceIn(0f, 1f)).commit() }

    var cryCalibratedAt: Long
        get() = prefs.getLong(KEY_CRY_CALIBRATED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_CRY_CALIBRATED_AT, value.coerceAtLeast(0L)).commit() }

    var adultLikeMinHeight: Float
        get() = prefs.getFloat(KEY_ADULT_MIN_HEIGHT, 0.48f)
        set(value) { prefs.edit().putFloat(KEY_ADULT_MIN_HEIGHT, value.coerceIn(0.20f, 0.80f)).commit() }

    var adultLikeMinArea: Float
        get() = prefs.getFloat(KEY_ADULT_MIN_AREA, 0.14f)
        set(value) { prefs.edit().putFloat(KEY_ADULT_MIN_AREA, value.coerceIn(0.03f, 0.40f)).commit() }

    var adultMedianHeight: Float
        get() = prefs.getFloat(KEY_ADULT_MEDIAN_HEIGHT, 0f)
        set(value) { prefs.edit().putFloat(KEY_ADULT_MEDIAN_HEIGHT, value.coerceIn(0f, 1f)).commit() }

    var adultMedianArea: Float
        get() = prefs.getFloat(KEY_ADULT_MEDIAN_AREA, 0f)
        set(value) { prefs.edit().putFloat(KEY_ADULT_MEDIAN_AREA, value.coerceIn(0f, 1f)).commit() }

    var adultCalibratedAt: Long
        get() = prefs.getLong(KEY_ADULT_CALIBRATED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_ADULT_CALIBRATED_AT, value.coerceAtLeast(0L)).commit() }

    /** 睡姿分析总开关。关闭时释放 Pose 与按需面部模型，不占用 NPU。 */
    var poseAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_ANALYSIS, false)
        set(value) { prefs.edit().putBoolean(KEY_POSE_ANALYSIS, value).commit() }

    /** 实验性姿态通知独立开关；默认关闭，影子模式仍会保留 24 小时统计。 */
    var poseNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_NOTIFICATIONS, false)
        set(value) { prefs.edit().putBoolean(KEY_POSE_NOTIFICATIONS, value).commit() }

    /** 只在浏览器叠加调试骨架，不写入直播编码或事件短片。 */
    var poseDebugOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_DEBUG_OVERLAY, false)
        set(value) { prefs.edit().putBoolean(KEY_POSE_DEBUG_OVERLAY, value).commit() }

    /** 默认只把疑似俯卧作为风险；可选将侧卧也作为风险候选。 */
    var poseSideRiskEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_SIDE_RISK, false)
        set(value) { prefs.edit().putBoolean(KEY_POSE_SIDE_RISK, value).commit() }

    /** 风险候选出现时，是否按需分析头部裁剪区域的面部可见性。 */
    var faceVisibilityEnabled: Boolean
        get() = prefs.getBoolean(KEY_FACE_VISIBILITY, false)
        set(value) { prefs.edit().putBoolean(KEY_FACE_VISIBILITY, value).commit() }

    fun resetSceneCalibration() {
        prefs.edit()
            .remove(KEY_CRY_NOISE_RMS)
            .remove(KEY_CRY_NOISE_SCORE)
            .remove(KEY_CRY_CALIBRATED_AT)
            .remove(KEY_ADULT_MIN_HEIGHT)
            .remove(KEY_ADULT_MIN_AREA)
            .remove(KEY_ADULT_MEDIAN_HEIGHT)
            .remove(KEY_ADULT_MEDIAN_AREA)
            .remove(KEY_ADULT_CALIBRATED_AT)
            .commit()
    }
}
