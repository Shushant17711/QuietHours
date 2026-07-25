package com.example.aquiethours.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.aquiethours.data.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class AlarmScheduler(private val context: Context) {

    private val silentManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(schedule: Schedule, isRescheduling: Boolean = false) {
        if (!schedule.isActive) return
        
        val startMins = schedule.startHour * 60 + schedule.startMinute
        val endMins = schedule.endHour * 60 + schedule.endMinute
        val endDayOfWeek = if (startMins >= endMins) schedule.dayOfWeek.plus(1) else schedule.dayOfWeek

        val nextStart = getNextOccurrence(schedule.dayOfWeek, schedule.startHour, schedule.startMinute)
        val nextEnd = getNextOccurrence(endDayOfWeek, schedule.endHour, schedule.endMinute)

        // Check if currently active (only if NOT rescheduling, to prevent infinite loops)
        if (!isRescheduling) {
            val now = LocalDateTime.now()
            val currentMins = now.hour * 60 + now.minute
            
            val isNormalToday = schedule.dayOfWeek == now.dayOfWeek && startMins < endMins && currentMins in startMins until endMins
            val crossesMidnightToday = schedule.dayOfWeek == now.dayOfWeek && startMins >= endMins && currentMins >= startMins
            val crossesMidnightYesterday = schedule.dayOfWeek == now.dayOfWeek.minus(1) && startMins >= endMins && currentMins < endMins
            
            if (isNormalToday || crossesMidnightToday || crossesMidnightYesterday) {
                Log.d("AlarmScheduler", "Schedule is currently active. Applying profile immediately.")
                val startIntent = Intent(context, VolumeReceiver::class.java).apply {
                    putExtra("PROFILE_ID", schedule.profileId)
                    putExtra("IS_START", true)
                    putExtra("SCHEDULE_ID", schedule.id)
                }
                context.sendBroadcast(startIntent)
            }
        }

        // Schedule START silent
        // Schedule START silent
        val startIntent = Intent(context, VolumeReceiver::class.java).apply {
            action = "com.example.aquiethours.ACTION_SCHEDULE_START"
            putExtra("PROFILE_ID", schedule.profileId)
            putExtra("IS_START", true)
            putExtra("SCHEDULE_ID", schedule.id)
        }
        val startPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id * 10, // Unique ID for start
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(context, VolumeReceiver::class.java).apply {
            action = "com.example.aquiethours.ACTION_SCHEDULE_END"
            putExtra("PROFILE_ID", schedule.profileId)
            putExtra("IS_START", false)
            putExtra("SCHEDULE_ID", schedule.id)
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id * 10 + 1, // Unique ID for end
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Check if we can schedule exact silents
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !silentManager.canScheduleExactAlarms()) {
            Log.e("AlarmScheduler", "Cannot schedule exact silents. Permission denied.")
            return
        }

        try {
            val startMillis = nextStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = nextEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                silentManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, startPendingIntent)
                silentManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, endPendingIntent)
            } else {
                silentManager.setExact(AlarmManager.RTC_WAKEUP, startMillis, startPendingIntent)
                silentManager.setExact(AlarmManager.RTC_WAKEUP, endMillis, endPendingIntent)
            }
            
            Log.d("AlarmScheduler", "Scheduled start at $nextStart and end at $nextEnd for profile ${schedule.profileId}")
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "Security exception when scheduling exact silent", e)
        }
    }

    fun cancel(schedule: Schedule) {
        val startIntent = Intent(context, VolumeReceiver::class.java)
        val startPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id * 10,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        silentManager.cancel(startPendingIntent)

        val endIntent = Intent(context, VolumeReceiver::class.java)
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id * 10 + 1,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        silentManager.cancel(endPendingIntent)
    }

    private fun getNextOccurrence(dayOfWeek: DayOfWeek, hour: Int, minute: Int): LocalDateTime {
        val now = LocalDateTime.now()
        val targetTime = LocalTime.of(hour, minute)
        
        var targetDate = now.toLocalDate()
        
        if (now.dayOfWeek == dayOfWeek) {
            if (!now.toLocalTime().isBefore(targetTime)) {
                // If it's today but time has passed (or is EXACTLY now), schedule for next week
                targetDate = targetDate.plusWeeks(1)
            }
        } else {
            // Find the next day of week
            targetDate = targetDate.with(TemporalAdjusters.next(dayOfWeek))
        }
        
        return targetDate.atTime(targetTime)
    }
}
