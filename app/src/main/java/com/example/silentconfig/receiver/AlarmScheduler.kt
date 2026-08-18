package com.example.aquiethours.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.aquiethours.MainActivity
import com.example.aquiethours.data.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class AlarmScheduler(private val context: Context) {

    private val silentManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Tapping the status-bar alarm icon that setAlarmClock() creates opens the app. This is
     * required by AlarmClockInfo; passing null is allowed but makes the icon a dead end.
     */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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

        // Exact alarms need the user to have granted "Alarms & reminders". If it's missing,
        // fall back to inexact-but-still-while-idle alarms instead of scheduling nothing at
        // all — a late trigger is far better than one that silently never fires.
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || silentManager.canScheduleExactAlarms()
        if (!canScheduleExact) {
            Log.w("AlarmScheduler", "Exact alarm permission denied. Falling back to inexact alarms.")
        }

        try {
            val startMillis = nextStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = nextEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            when {
                // Highest-priority alarm class Android has. Unlike setExactAndAllowWhileIdle,
                // alarm-clock alarms are exempt from Doze, app-standby AND -- critically -- from
                // the proprietary app-freezers shipped by Transsion (XOS/HiOS "Usf_Hiber"),
                // MIUI, ColorOS etc., which otherwise proxy/hold a frozen app's alarms
                // indefinitely ("p_alarm: uid=..."). Those ROMs cannot block this class without
                // breaking every third-party alarm-clock app, so it is the only delivery
                // mechanism that survives being frozen. Cost: the OS shows a next-alarm icon.
                canScheduleExact -> {
                    silentManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(startMillis, showIntent()),
                        startPendingIntent
                    )
                    silentManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(endMillis, showIntent()),
                        endPendingIntent
                    )
                }
                // No "Alarms & reminders" permission: setExact* would throw SecurityException,
                // so drop straight to inexact-while-idle. Late is better than never.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    silentManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, startPendingIntent)
                    silentManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, endPendingIntent)
                }
                else -> {
                    silentManager.set(AlarmManager.RTC_WAKEUP, startMillis, startPendingIntent)
                    silentManager.set(AlarmManager.RTC_WAKEUP, endMillis, endPendingIntent)
                }
            }

            Log.d("AlarmScheduler", "Scheduled start at $nextStart and end at $nextEnd for profile ${schedule.profileId} (exact=$canScheduleExact)")
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "Security exception when scheduling silent", e)
        }
    }

    fun cancel(schedule: Schedule) {
        // The action must match what schedule() used, or PendingIntent.getBroadcast()
        // returns an unrelated PendingIntent (Intent.filterEquals() includes action),
        // and the real alarm never actually gets cancelled.
        val startIntent = Intent(context, VolumeReceiver::class.java).apply {
            action = "com.example.aquiethours.ACTION_SCHEDULE_START"
        }
        val startPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id * 10,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        silentManager.cancel(startPendingIntent)

        val endIntent = Intent(context, VolumeReceiver::class.java).apply {
            action = "com.example.aquiethours.ACTION_SCHEDULE_END"
        }
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
