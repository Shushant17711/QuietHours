package com.example.aquiethours.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aquiethours.data.AppDatabase
import com.example.aquiethours.data.Profile
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.receiver.AlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.aquiethours.data.AppPreferences
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.aquiethours.receiver.MainService
import com.example.aquiethours.receiver.VolumeReceiver

class MainViewModel(application: Application) : AndroidViewModel(application), QuietStore {
    private val database = AppDatabase.getDatabase(application)
    private val profileDao = database.profileDao()
    private val scheduleDao = database.scheduleDao()
    private val silentScheduler = AlarmScheduler(application)
    private val appPrefs = AppPreferences.getInstance(application)

    private val _isGlobalEnabled = MutableStateFlow(appPrefs.isGlobalEnabled())
    override val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled.asStateFlow()

    override val profiles: StateFlow<List<Profile>> = profileDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    override val schedules: StateFlow<List<Schedule>> = scheduleDao.getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val skipPrefs = application.getSharedPreferences(VolumeReceiver.SKIP_PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /**
     * Ids of schedules whose *current* occurrence is being skipped. Once START (or the
     * service sweep) consumes Schedule.skipNext the skip only exists as a pref, so the
     * card flipped back to "Skip Next" and there was no way to un-skip a running class.
     */
    private val _skippingNow = MutableStateFlow(readSkippingNow())
    override val skippingNow: StateFlow<Set<Int>> = _skippingNow.asStateFlow()

    private val skipListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("skip_end_")) _skippingNow.value = readSkippingNow()
    }

    init {
        skipPrefs.registerOnSharedPreferenceChangeListener(skipListener)
    }

    override fun onCleared() {
        skipPrefs.unregisterOnSharedPreferenceChangeListener(skipListener)
    }

    private fun readSkippingNow(): Set<Int> = skipPrefs.all
        .filter { (k, v) -> k.startsWith("skip_end_") && v == true }
        .keys.mapNotNull { it.removePrefix("skip_end_").toIntOrNull() }
        .toSet()

    override fun addProfile(profile: Profile, onAdded: (Int) -> Unit) {
        viewModelScope.launch {
            val id = profileDao.insert(profile).toInt()
            onAdded(id)
        }
    }

    override fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            profileDao.update(profile)
            nudgeService() // apply edited volumes now if this profile is enforcing
        }
    }

    override fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            // Schedule rows for this profile cascade-delete in the DB, but their AlarmManager
            // entries don't clean themselves up — cancel them first or they linger forever.
            // Read from the DB: schedules.value is an empty Lazily-started flow if the
            // home screen never collected it, which left these alarms armed forever.
            scheduleDao.getAllSchedulesOnce().filter { it.profileId == profile.id }.forEach { silentScheduler.cancel(it) }
            profileDao.delete(profile)
            nudgeService() // unmute now if the deleted profile was enforcing
        }
    }

    override fun addSchedule(schedule: Schedule) {
        viewModelScope.launch {
            val id = scheduleDao.insert(schedule)
            val newSchedule = schedule.copy(id = id.toInt())
            silentScheduler.schedule(newSchedule)
        }
    }

    override fun addSchedules(schedules: List<Schedule>) {
        viewModelScope.launch {
            val ids = scheduleDao.insertAll(schedules)
            ids.forEachIndexed { index, id ->
                val newSchedule = schedules[index].copy(id = id.toInt())
                silentScheduler.schedule(newSchedule)
            }
        }
    }

    override fun updateSchedule(schedule: Schedule) {
        viewModelScope.launch {
            scheduleDao.update(schedule)
            silentScheduler.cancel(schedule) // Cancel old silent
            silentScheduler.schedule(schedule) // Schedule new one
            // cancel() dropped the old END alarm; if the class was moved away from "now"
            // nothing else would unmute promptly.
            nudgeService()
        }
    }

    override fun deleteSchedule(schedule: Schedule) {
        viewModelScope.launch {
            silentScheduler.cancel(schedule)
            scheduleDao.delete(schedule)
            
            // If the deleted schedule is currently active, explicitly end it!
            val context = getApplication<Application>()
            val endIntent = Intent(context, com.example.aquiethours.receiver.VolumeReceiver::class.java).apply {
                action = "com.example.aquiethours.ACTION_SCHEDULE_END"
                putExtra("PROFILE_ID", schedule.profileId)
                putExtra("IS_START", false)
                putExtra("SCHEDULE_ID", schedule.id)
            }
            context.sendBroadcast(endIntent)
        }
    }

    override fun toggleGlobalState(enabled: Boolean) {
        appPrefs.setGlobalEnabled(enabled)
        _isGlobalEnabled.value = enabled
        
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, MainService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(context, serviceIntent)
            // Alarms that fired while the app was off were ignored and never re-armed
            // (setAlarmClock is one-shot), so arm them all again right away instead of
            // waiting for the 15-minute worker.
            viewModelScope.launch {
                scheduleDao.getActiveSchedules().forEach { silentScheduler.schedule(it, isRescheduling = true) }
            }
        } else {
            context.stopService(serviceIntent)
            // stopService only restores via onDestroy. If an OEM already killed the
            // service mid-class, nothing ran and the phone stayed muted while "off".
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(1500) // let a live service's onDestroy win first
                MainService.restoreIfStillEnforcing(context)
            }
        }
    }

    override fun toggleScheduleStatus(schedule: Schedule, isActive: Boolean) {
        viewModelScope.launch {
            // Clear any pending skip when switching off, otherwise re-enabling weeks later
            // silently skips the first class.
            val updatedSchedule = if (isActive) schedule.copy(isActive = true)
                else schedule.copy(isActive = false, skipNext = false)
            if (!isActive) skipPrefs.edit().remove("skip_end_${schedule.id}").apply()
            scheduleDao.update(updatedSchedule)
            
            if (isActive) {
                silentScheduler.schedule(updatedSchedule)
            } else {
                silentScheduler.cancel(updatedSchedule)
                
                // If the cancelled schedule is currently active, explicitly end it!
                val context = getApplication<Application>()
                val endIntent = Intent(context, com.example.aquiethours.receiver.VolumeReceiver::class.java).apply {
                    action = "com.example.aquiethours.ACTION_SCHEDULE_END"
                    putExtra("PROFILE_ID", schedule.profileId)
                    putExtra("IS_START", false)
                    putExtra("SCHEDULE_ID", schedule.id)
                }
                context.sendBroadcast(endIntent)
            }
        }
    }

    override fun toggleSkipNext(schedule: Schedule, skip: Boolean) {
        viewModelScope.launch {
            scheduleDao.updateSkipNext(schedule.id, skip)
            val context = getApplication<Application>()
            if (!skip) {
                // "Restore": also un-skip a class that is being skipped right now.
                context.getSharedPreferences(VolumeReceiver.SKIP_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().remove("skip_end_${schedule.id}").apply()
            }
            // Re-evaluate now instead of waiting up to 30s: skipping during a class
            // ends it immediately, restoring re-applies it.
            nudgeService()
        }
    }

    private fun nudgeService() {
        if (!appPrefs.isGlobalEnabled()) return
        val context = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(context, Intent(context, MainService::class.java).apply {
                action = MainService.ACTION_EVALUATE_SCHEDULES
            })
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Could not nudge MainService", e)
        }
    }
}
