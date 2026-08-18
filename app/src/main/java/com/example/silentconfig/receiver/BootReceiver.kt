package com.example.aquiethours.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.aquiethours.data.AppDatabase
import com.example.aquiethours.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        // We are directBootAware so we get registered early, but Room and SharedPreferences live
        // in credential-encrypted storage. Touching them before the first unlock throws. The
        // real BOOT_COMPLETED arrives after unlock, so simply stand down until then.
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && userManager?.isUserUnlocked == false) {
            Log.d(TAG, "$action before user unlock - waiting for BOOT_COMPLETED")
            return
        }

        Log.d(TAG, "$action - rescheduling silents...")

        if (!AppPreferences.getInstance(context).isGlobalEnabled()) {
            Log.d(TAG, "App is globally disabled. Not rearming.")
            return
        }

        // Keep the process alive across the async DB read. Without this the receiver returns
        // immediately, the process becomes killable, and rescheduling loses the race on a
        // freshly booted device that is busy starting everything else.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = AlarmScheduler(context)
                val activeSchedules = AppDatabase.getDatabase(context).scheduleDao().getActiveSchedules()
                for (schedule in activeSchedules) {
                    // isRescheduling = false: on boot/time-change we *want* the current profile
                    // applied straight away if we are already inside a schedule window.
                    scheduler.schedule(schedule)
                }
                Log.d(TAG, "Rearmed ${activeSchedules.size} schedules after $action")

                val serviceIntent = Intent(context, MainService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rearm after $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
