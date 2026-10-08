package com.example.aquiethours.ui

import com.example.aquiethours.data.Profile
import com.example.aquiethours.data.Schedule
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need from the app. MainViewModel is the real implementation; screenshot
 * tests pass a fake so screens render without Room, alarms or services.
 */
interface QuietStore {
    val profiles: StateFlow<List<Profile>>
    val schedules: StateFlow<List<Schedule>>
    val isGlobalEnabled: StateFlow<Boolean>
    val skippingNow: StateFlow<Set<Int>>

    /** [onAdded] receives the new profile's id (on the main thread) so callers can select it. */
    fun addProfile(profile: Profile, onAdded: (Int) -> Unit = {})
    fun updateProfile(profile: Profile)
    fun deleteProfile(profile: Profile)
    fun addSchedule(schedule: Schedule)
    fun addSchedules(schedules: List<Schedule>)
    fun updateSchedule(schedule: Schedule)
    fun deleteSchedule(schedule: Schedule)
    fun toggleGlobalState(enabled: Boolean)
    fun toggleScheduleStatus(schedule: Schedule, isActive: Boolean)
    fun toggleSkipNext(schedule: Schedule, skip: Boolean)
}
