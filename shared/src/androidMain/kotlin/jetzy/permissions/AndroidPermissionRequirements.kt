package jetzy.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import jetzy.MainActivity
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.perm_background_desc
import jetzy.shared.generated.resources.perm_background_title
import jetzy.shared.generated.resources.perm_hotspot_off_desc
import jetzy.shared.generated.resources.perm_hotspot_off_title
import jetzy.shared.generated.resources.perm_location_desc
import jetzy.shared.generated.resources.perm_location_services_desc
import jetzy.shared.generated.resources.perm_location_services_title
import jetzy.shared.generated.resources.perm_location_title
import jetzy.shared.generated.resources.perm_nearby_desc
import jetzy.shared.generated.resources.perm_nearby_title
import jetzy.shared.generated.resources.perm_notifications_desc
import jetzy.shared.generated.resources.perm_notifications_title
import jetzy.shared.generated.resources.perm_wifi_state_desc
import jetzy.shared.generated.resources.perm_wifi_state_title

/**
 * Catalog of Android-side [PermissionRequirement]s used by the permission-gate
 * dialog. Each builder produces a *live* requirement — `isGrantedNow` is re-read
 * by the dialog every 500ms so the user flipping a system toggle reflects
 * immediately when they return to Jetzy.
 */
object AndroidPermissionRequirements {

    fun nearbyDevices(activity: MainActivity): PermissionRequirement {
        // NEARBY_WIFI_DEVICES (declared with neverForLocation) is API 33+.
        // On 32 and below the same role is filled by ACCESS_FINE_LOCATION.
        val onModernAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val perm = if (onModernAndroid) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return runtimePermission(
            activity = activity,
            manifestPermission = perm,
            id = "nearby_devices",
            titleRes = if (onModernAndroid) Res.string.perm_nearby_title else Res.string.perm_location_title,
            descriptionRes = if (onModernAndroid) Res.string.perm_nearby_desc else Res.string.perm_location_desc,
        )
    }

    fun postNotifications(activity: MainActivity): PermissionRequirement {
        // POST_NOTIFICATIONS only meaningful on API 33+. On older APIs notifications
        // are granted at install time, so we report this as already-satisfied so the
        // dialog doesn't display a no-op row.
        return PermissionRequirement(
            id = "post_notifications",
            titleRes = Res.string.perm_notifications_title,
            descriptionRes = Res.string.perm_notifications_desc,
            kind = PermissionRequirement.Kind.RUNTIME_PERMISSION,
            isGrantedNow = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
                else ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            },
            request = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.requestRuntimePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
        )
    }

    fun wifiEnabled(activity: MainActivity): PermissionRequirement {
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return PermissionRequirement(
            id = "wifi_enabled",
            titleRes = Res.string.perm_wifi_state_title,
            descriptionRes = Res.string.perm_wifi_state_desc,
            kind = PermissionRequirement.Kind.SYSTEM_TOGGLE,
            isGrantedNow = { wifi.isWifiEnabled },
            request = {
                // Direct toggle was removed in API 29. Send users to the Wi-Fi panel
                // — they flip it themselves and come back.
                runCatching {
                    activity.startActivity(
                        Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.recoverCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    fun locationServicesEnabled(activity: MainActivity): PermissionRequirement {
        // Only relevant on API 28..32 — modern Android with NEARBY_WIFI_DEVICES doesn't
        // need location services. Caller should only include this on those API levels.
        val locationManager = activity.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return PermissionRequirement(
            id = "location_services",
            titleRes = Res.string.perm_location_services_title,
            descriptionRes = Res.string.perm_location_services_desc,
            kind = PermissionRequirement.Kind.SYSTEM_TOGGLE,
            isGrantedNow = {
                locationManager?.let {
                    runCatching { it.isLocationEnabled }.getOrDefault(true)
                } ?: true
            },
            request = {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    /**
     * "Mobile hotspot is currently off" — only needed for [HotspotP2PM] which uses
     * LocalOnlyHotspot and clashes with an active mobile hotspot.
     *
     * There's no public API to query the user-visible "Mobile Hotspot" toggle; we
     * approximate by checking the AP state via reflection. If reflection fails the
     * requirement reports satisfied so we don't block users on a false alarm.
     */
    fun mobileHotspotOff(activity: MainActivity): PermissionRequirement {
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return PermissionRequirement(
            id = "mobile_hotspot_off",
            titleRes = Res.string.perm_hotspot_off_title,
            descriptionRes = Res.string.perm_hotspot_off_desc,
            kind = PermissionRequirement.Kind.SYSTEM_TOGGLE,
            isGrantedNow = { !isWifiApEnabled(wifi) },
            request = {
                runCatching {
                    activity.startActivity(
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.android.settings",
                                "com.android.settings.TetherSettings"
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }.recoverCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_WIRELESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    fun ignoreBatteryOptimizations(activity: MainActivity): PermissionRequirement {
        val pm = activity.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val pkg = activity.packageName
        return PermissionRequirement(
            id = "ignore_battery_opt",
            titleRes = Res.string.perm_background_title,
            descriptionRes = Res.string.perm_background_desc,
            kind = PermissionRequirement.Kind.BACKGROUND_OPTIN,
            isGrantedNow = { pm.isIgnoringBatteryOptimizations(pkg) },
            request = {
                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS triggers a system dialog
                // on most OEMs; fall back to the settings list if the targeted intent
                // is filtered out (Play store policy varies on OEM forks).
                runCatching {
                    @SuppressWarnings("BatteryLife")
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.recoverCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    private fun runtimePermission(
        activity: MainActivity,
        manifestPermission: String,
        id: String,
        titleRes: org.jetbrains.compose.resources.StringResource,
        descriptionRes: org.jetbrains.compose.resources.StringResource,
    ): PermissionRequirement = PermissionRequirement(
        id = id,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        kind = PermissionRequirement.Kind.RUNTIME_PERMISSION,
        isGrantedNow = {
            ContextCompat.checkSelfPermission(activity, manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
        },
        request = { activity.requestRuntimePermissions(arrayOf(manifestPermission)) },
    )

    /**
     * Reflective check of the SoftAp (mobile hotspot) state. There is no public
     * API for this; the hidden method has been stable across versions but is
     * not guaranteed to exist on every OEM build. On reflection failure we
     * return `false` (i.e. "AP is off") to avoid blocking the user on a false
     * positive — a real conflict will show up later as a hotspot-start failure.
     */
    private fun isWifiApEnabled(wifi: WifiManager): Boolean {
        return runCatching {
            val method = wifi.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifi) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
