package org.e11camera.edge

/** 宝宝守护运行模式的纯逻辑，避免网页状态与实际检测链路出现分歧。 */
enum class GuardianOperatingMode(val apiValue: String) {
    OFF("off"),
    PRIVACY_PAUSED("privacy_paused"),
    AUDIO_ONLY("audio_only"),
    NORMAL("normal")
}

internal object GuardianModePolicy {
    fun resolve(
        guardianEnabled: Boolean,
        privacyMode: Boolean,
        lensCoverClosed: Boolean
    ): GuardianOperatingMode = when {
        !guardianEnabled -> GuardianOperatingMode.OFF
        privacyMode -> GuardianOperatingMode.PRIVACY_PAUSED
        lensCoverClosed -> GuardianOperatingMode.AUDIO_ONLY
        else -> GuardianOperatingMode.NORMAL
    }

    fun visualEnabled(mode: GuardianOperatingMode): Boolean =
        mode == GuardianOperatingMode.NORMAL

    fun audioEnabled(mode: GuardianOperatingMode): Boolean =
        mode == GuardianOperatingMode.NORMAL || mode == GuardianOperatingMode.AUDIO_ONLY

    fun clipEnabled(mode: GuardianOperatingMode): Boolean = visualEnabled(mode)
}
