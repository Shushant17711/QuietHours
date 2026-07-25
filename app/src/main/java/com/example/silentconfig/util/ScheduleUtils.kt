package com.example.aquiethours.util

import com.example.aquiethours.data.Schedule

object ScheduleUtils {
    
    /**
     * Checks if a new schedule overlaps with any existing schedules.
     */
    fun hasOverlap(newSchedule: Schedule, existingSchedules: List<Schedule>, ignoreId: Int? = null): Boolean {
        val newIntervals = getIntervals(newSchedule)
        
        for (existing in existingSchedules) {
            if (existing.id == ignoreId) continue
            val exIntervals = getIntervals(existing)
            
            for (newInt in newIntervals) {
                for (exInt in exIntervals) {
                    if (maxOf(newInt.first, exInt.first) < minOf(newInt.second, exInt.second)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Converts a schedule into absolute minutes of the week (0 to 10080).
     * If a schedule spans across the weekend boundary (Saturday night to Sunday morning),
     * it splits into two intervals.
     */
    fun getIntervals(s: Schedule): List<Pair<Int, Int>> {
        // Sunday is 0, Monday is 1, ..., Saturday is 6
        val dayIndex = s.dayOfWeek.value % 7 
        val startMin = dayIndex * 24 * 60 + s.startHour * 60 + s.startMinute
        var duration = (s.endHour * 60 + s.endMinute) - (s.startHour * 60 + s.startMinute)
        if (duration <= 0) {
            duration += 24 * 60
        }
        val endMin = startMin + duration
        
        val maxWeekMins = 7 * 24 * 60
        return if (endMin > maxWeekMins) {
            listOf(
                startMin to maxWeekMins,
                0 to (endMin % maxWeekMins)
            )
        } else {
            listOf(startMin to endMin)
        }
    }
}
