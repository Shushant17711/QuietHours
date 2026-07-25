package com.example.aquiethours.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.aquiethours.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, rescheduling silents...")
            val scheduler = AlarmScheduler(context)
            
            // Start the persistent MainService if globally enabled
            val serviceIntent = Intent(context, MainService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            
            CoroutineScope(Dispatchers.IO).launch {
                val database = AppDatabase.getDatabase(context)
                val activeSchedules = database.scheduleDao().getActiveSchedules()
                
                for (schedule in activeSchedules) {
                    scheduler.schedule(schedule)
                }
            }
        }
    }
}
