package jetzy.permissions

import org.jetbrains.compose.resources.StringResource

/**
 * One capability the user must grant or enable before a P2P transfer can begin.
 * Each [P2PManager][jetzy.managers.P2PManager] exposes a list of these so the
 * permission-gate dialog can walk the user through them one tap at a time.
 *
 * The fields are deliberately read every time the dialog re-composes — the user
 * may flip a system toggle or accept a runtime prompt and come back, and we want
 * the UI to reflect that without forcing a manager rebuild.
 *
 * [titleRes] and [descriptionRes] are [StringResource]s so manager code (which
 * runs outside Compose) can describe its requirements without knowing the
 * current locale; the dialog resolves them at render time via `stringResource`.
 */
class PermissionRequirement(
    val id: String,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
    val kind: Kind,
    val isGrantedNow: () -> Boolean,
    val request: () -> Unit,
) {
    enum class Kind {
        /** OS-prompt-style permission (NEARBY_WIFI_DEVICES, POST_NOTIFICATIONS, …). */
        RUNTIME_PERMISSION,

        /** A toggle the user has to flip in Settings (Wi-Fi, location services, hotspot). */
        SYSTEM_TOGGLE,

        /** Battery / background-activity opt-in (ignoring battery optimizations). */
        BACKGROUND_OPTIN,
    }
}
