package jetzy.utils

import com.russhwolf.settings.Settings
import jetzy.theme.NightMode

/**
 * Tiny persistent preference store (SharedPreferences / NSUserDefaults / java.util.prefs via
 * multiplatform-settings). Holds only what must survive a relaunch — everything session-scoped
 * stays in the viewmodel.
 */
object JetzyPrefs {
    private val settings = Settings()

    /**
     * User-chosen display name override; null = use the platform's name. Edited from the
     * "You're visible as …" line on the discovery screen.
     */
    var deviceNameOverride: String?
        get() = settings.getStringOrNull(KEY_DEVICE_NAME)?.takeIf { it.isNotBlank() }
        set(value) {
            val trimmed = value?.trim()
            if (trimmed.isNullOrBlank()) settings.remove(KEY_DEVICE_NAME)
            else settings.putString(KEY_DEVICE_NAME, trimmed.take(MAX_NAME_LENGTH))
        }

    /** Theme choice — used to reset to SYSTEM on every launch. */
    var nightMode: NightMode
        get() = settings.getStringOrNull(KEY_NIGHT_MODE)
            ?.let { stored -> NightMode.entries.firstOrNull { it.name == stored } }
            ?: NightMode.SYSTEM
        set(value) = settings.putString(KEY_NIGHT_MODE, value.name)

    /** Matches the mDNS service-name cap so the advertised name is never silently truncated. */
    private const val MAX_NAME_LENGTH = 63

    private const val KEY_DEVICE_NAME = "device_name_override"
    private const val KEY_NIGHT_MODE = "night_mode"
}
