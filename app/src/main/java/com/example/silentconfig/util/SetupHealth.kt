package com.example.aquiethours.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * What the app needs to keep working after it is swiped from recents, and how to fix each
 * item. Shown as a checklist on the home screen instead of firing every settings screen at
 * the user on each launch.
 */
object SetupHealth {

    enum class Item(val title: String, val why: String) {
        EXACT_ALARMS("Alarms & reminders", "Starts and ends classes on the exact minute, even when the app is closed."),
        BATTERY("Unrestricted battery", "Stops Android from freezing Quiet Hours in the background."),
        AUTOSTART("Autostart / background launch", "Your phone kills apps swiped from recents unless this is on."),
        DND("Do Not Disturb access", "Needed to switch your phone to true silent."),
        NOTIFICATIONS("Notifications", "Lets the background service stay alive on Android 13+.")
    }

    private const val PREFS = "aquiethours_setup"
    private const val KEY_AUTOSTART_DONE = "autostart_confirmed"

    /** Items still missing. Autostart can't be detected, so it's user-confirmed. */
    fun missing(context: Context): List<Item> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) add(Item.EXACT_ALARMS)
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) add(Item.BATTERY)
        if (OemBackgroundHelper.isAggressiveOem() && !isAutostartConfirmed(context)) add(Item.AUTOSTART)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) add(Item.DND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add(Item.NOTIFICATIONS)
    }

    fun isAutostartConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART_DONE, false)

    fun confirmAutostart(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART_DONE, true).apply()
    }

    /** Opens the screen that fixes [item]. Notifications are requested by the caller (runtime permission). */
    fun open(context: Context, item: Item) {
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = when (item) {
            Item.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            Item.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Item.AUTOSTART -> {
                OemBackgroundHelper.openAutostartSettings(context)
                return
            }
            Item.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Item.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Some ROMs strip these screens; fall back to the app's info page.
            Log.w("SetupHealth", "No activity for $item, opening app details", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }
    }
}
