package com.example.aquiethours.worker

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aquiethours.data.AppDatabase
import com.example.aquiethours.data.AppPreferences
import com.example.aquiethours.receiver.AlarmScheduler
import com.example.aquiethours.receiver.MainService
import com.example.aquiethours.receiver.VolumeReceiver
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar

class ScheduleEnforcerWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Checking for active schedules...")

        // Respect the global enabled toggle
        val appPrefs = AppPreferences.getInstance(context)
        if (!appPrefs.isGlobalEnabled()) {
            Log.d(TAG, "App is globally disabled. Skipping enforcement.")
            return Result.success()
        }

        val database = AppDatabase.getDatabase(context)
        val scheduleDao = database.scheduleDao()
        val profileDao = database.profileDao()
        val alarmScheduler = AlarmScheduler(context)

        try {
            val activeSchedules = scheduleDao.getActiveSchedules()
            
            // Re-schedule all active alarms as a safety net
            for (schedule in activeSchedules) {
                alarmScheduler.schedule(schedule, true)
            }
            Log.d(TAG, "Re-registered ${activeSchedules.size} active schedule alarms as safety net.")

            // Ensure MainService is running and evaluates schedules
            try {
                val evaluateIntent = Intent(context, MainService::class.java).also {
                    it.action = MainService.ACTION_EVALUATE_SCHEDULES
                }
                ContextCompat.startForegroundService(context, evaluateIntent)
                Log.d(TAG, "Ensured MainService is running and checking schedules")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MainService from worker", e)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing schedule", e)
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "ScheduleEnforcerWorker"
    }
}
