package com.example.aquiethours.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Beyond standard Android battery-optimization exemption, several OEMs (MIUI, XOS/HiOS,
 * ColorOS, FuntouchOS, OxygenOS, EMUI) gate background execution behind their own separate
 * "Autostart"/"Startup Manager" permission. Without it these ROMs kill the foreground service
 * outright and no alarm or WorkManager job can wake it back up in time. There is no public API
 * to grant this; the best an app can do is detect the OEM and deep-link to its settings screen,
 * with a fallback since exact component names vary by device model and OS version.
 */
object OemBackgroundHelper {
    private const val TAG = "OemBackgroundHelper"

    private data class OemEntry(val label: String, val brands: List<String>, val candidates: List<Pair<String, String>>)

    private val OEMS = listOf(
        OemEntry(
            "Transsion (Infinix/Tecno/itel)",
            listOf("infinix", "tecno", "itel", "transsion"),
            listOf(
                // Verified on Infinix X6857B / XOS 16.2. PhoneMaster -- not "phonemanager",
                // which does not exist on any current XOS/HiOS build -- owns the autostart
                // whitelist that the Usf_Hiber freezer consults before proxying our alarms.
                "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
                "com.transsion.phonemaster" to "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity",
                "com.transsion.phonemanager" to "com.transsion.phonemanager.ui.autobootmanage.AutoBootManageActivity",
                "com.itel.autobootmanager" to "com.itel.autobootmanager.AutoBootMangerActivity"
            )
        ),
        OemEntry(
            "Xiaomi (MIUI)",
            listOf("xiaomi", "redmi", "poco"),
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        OemEntry(
            "Oppo/Realme (ColorOS)",
            listOf("oppo", "realme"),
            listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),
        OemEntry(
            "Vivo (FuntouchOS)",
            listOf("vivo", "iqoo"),
            listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        ),
        OemEntry(
            "OnePlus (OxygenOS)",
            listOf("oneplus"),
            listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        ),
        OemEntry(
            "Huawei/Honor (EMUI)",
            listOf("huawei", "honor"),
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )
    )

    private fun resolves(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null

    /** True only when this device actually has a reachable autostart screen to send the user to. */
    fun hasAutostartScreen(context: Context): Boolean {
        val oem = currentOem() ?: return false
        return oem.candidates.any { (pkg, activity) ->
            resolves(context, Intent().apply { setClassName(pkg, activity) })
        }
    }

    private fun currentOem(): OemEntry? {
        val brand = Build.BRAND?.lowercase().orEmpty()
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        return OEMS.firstOrNull { entry -> entry.brands.any { it in brand || it in manufacturer } }
    }

    fun isAggressiveOem(): Boolean = currentOem() != null

    fun getManufacturerLabel(): String = currentOem()?.label ?: (Build.MANUFACTURER ?: "your device")

    /**
     * Tries each known autostart-settings component for the detected OEM in order. Falls back to
     * the app's details settings page (and returns false) since exact component names drift
     * across OS versions and can't be relied on to resolve.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val oem = currentOem()
        if (oem != null) {
            for ((pkg, activity) in oem.candidates) {
                try {
                    val intent = Intent().apply {
                        setClassName(pkg, activity)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // Resolve first: startActivity() only throws for a *missing* component, but a
                    // present-yet-unexported one dies with SecurityException after we have already
                    // reported success to the caller. Checking up front keeps the return value
                    // honest, which is what the setup checklist keys off.
                    if (!resolves(context, intent)) {
                        Log.d(TAG, "Autostart activity does not resolve: $pkg/$activity")
                        continue
                    }
                    context.startActivity(intent)
                    return true
                } catch (e: ActivityNotFoundException) {
                    Log.d(TAG, "Autostart activity not found: $pkg/$activity")
                } catch (e: SecurityException) {
                    Log.d(TAG, "Autostart activity blocked: $pkg/$activity", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch autostart activity: $pkg/$activity", e)
                }
            }
        }

        try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app details settings fallback", e)
        }
        return false
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open battery optimization settings", e)
        }
    }
}
