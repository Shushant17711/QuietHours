package com.example.aquiethours.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.aquiethours.data.AppPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RestartReceiver", "Received restart intent.")
        if (AppPreferences.getInstance(context).isGlobalEnabled()) {
            val serviceToRestart = intent.getStringExtra("SERVICE_TO_RESTART")
            

            // Crucial: ALWAYS Re-register alarms, even if starting the service failed.
            // Custom ROMs (OxygenOS, MIUI, etc.) often wipe AlarmManager alarms when the app is swiped.
            val pendingResult = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val database = com.example.aquiethours.data.AppDatabase.getDatabase(context)
                    val activeSchedules = database.scheduleDao().getActiveSchedules()
                    val scheduler = AlarmScheduler(context)
                    for (schedule in activeSchedules) {
                        // Pass isRescheduling = true so it doesn't immediately broadcast start
                        scheduler.schedule(schedule, true)
                    }
                    Log.d("RestartReceiver", "Rescheduled ${activeSchedules.size} alarms after restart")
                    // Restart the service, or apply/restore directly if Android refuses.
                    MainService.evaluateWithoutService(context)
                } catch (e: Exception) {
                    Log.e("RestartReceiver", "Error rescheduling alarms", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
