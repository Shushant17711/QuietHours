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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val profileDao = database.profileDao()
    private val scheduleDao = database.scheduleDao()
    private val silentScheduler = AlarmScheduler(application)
    private val appPrefs = AppPreferences.getInstance(application)

    private val _isGlobalEnabled = MutableStateFlow(appPrefs.isGlobalEnabled())
    val isGlobalEnabled = _isGlobalEnabled.asStateFlow()

    val profiles: StateFlow<List<Profile>> = profileDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val schedules: StateFlow<List<Schedule>> = scheduleDao.getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addProfile(profile: Profile) {
        viewModelScope.launch {
            profileDao.insert(profile)
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            profileDao.delete(profile)
        }
    }

    fun addSchedule(schedule: Schedule) {
        viewModelScope.launch {
            val id = scheduleDao.insert(schedule)
            val newSchedule = schedule.copy(id = id.toInt())
            silentScheduler.schedule(newSchedule)
        }
    }

    fun addSchedules(schedules: List<Schedule>) {
        viewModelScope.launch {
            val ids = scheduleDao.insertAll(schedules)
            ids.forEachIndexed { index, id ->
                val newSchedule = schedules[index].copy(id = id.toInt())
                silentScheduler.schedule(newSchedule)
            }
        }
    }

    fun updateSchedule(schedule: Schedule) {
        viewModelScope.launch {
            scheduleDao.update(schedule)
            silentScheduler.cancel(schedule) // Cancel old silent
            silentScheduler.schedule(schedule) // Schedule new one
        }
    }

    fun deleteSchedule(schedule: Schedule) {
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

    fun toggleGlobalState(enabled: Boolean) {
        appPrefs.setGlobalEnabled(enabled)
        _isGlobalEnabled.value = enabled
        
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, MainService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    fun toggleScheduleStatus(schedule: Schedule, isActive: Boolean) {
        viewModelScope.launch {
            val updatedSchedule = schedule.copy(isActive = isActive)
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

    fun toggleSkipNext(schedule: Schedule, skip: Boolean) {
        viewModelScope.launch {
            scheduleDao.updateSkipNext(schedule.id, skip)
            
            if (skip) {
                // If they decide to skip, we don't need to do anything to alarms immediately.
                // The VolumeReceiver will handle the skip.
                // But we must clear any pre-existing skip_end flag just in case.
                val prefs = getApplication<Application>().getSharedPreferences("aquiethours_volume_backup", android.content.Context.MODE_PRIVATE)
                prefs.edit().remove("skip_end_${schedule.id}").apply()
            }
        }
    }
}
