package com.example.aquiethours.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.aquiethours.data.AppDatabase
import com.example.aquiethours.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import java.util.Calendar

class VolumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        val profileId = intent.getIntExtra("PROFILE_ID", -1)
        val isStart = intent.getBooleanExtra("IS_START", true)
        val scheduleId = intent.getIntExtra("SCHEDULE_ID", -1)
        
        if (profileId == -1) return
        
        Log.d(TAG, "Received alarm for profile: $profileId, isStart: $isStart, scheduleId: $scheduleId")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val pendingResult = goAsync()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appPrefs = AppPreferences.getInstance(context)
                if (!appPrefs.isGlobalEnabled()) {
                    Log.d(TAG, "App is globally disabled. Ignoring schedule intent.")
                    return@launch
                }

                val database = AppDatabase.getDatabase(context)
                
                val schedule = database.scheduleDao().getScheduleById(scheduleId)

                if (isStart) {
                    if (schedule != null && schedule.skipNext) {
                        Log.d(TAG, "Skipping schedule $scheduleId because skipNext is true")
                        database.scheduleDao().updateSkipNext(scheduleId, false)
                        
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("skip_end_$scheduleId", true).commit()

                        AlarmScheduler(context).schedule(schedule.copy(skipNext = false), isRescheduling = true)
                        return@launch
                    }
                    
                    schedule?.let {
                        AlarmScheduler(context).schedule(it, isRescheduling = true)
                    }
                } else {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (prefs.getBoolean("skip_end_$scheduleId", false)) {
                        prefs.edit().remove("skip_end_$scheduleId").commit()
                        Log.d(TAG, "Skipping schedule end because it was skipped at start")
                        
                        schedule?.let {
                            AlarmScheduler(context).schedule(it, isRescheduling = true)
                        }
                        return@launch
                    }
                    
                    schedule?.let {
                        AlarmScheduler(context).schedule(it, isRescheduling = true)
                    }
                }

                try {
                    val evaluateIntent = Intent(context, MainService::class.java).also {
                        it.action = MainService.ACTION_EVALUATE_SCHEDULES
                    }
                    ContextCompat.startForegroundService(context, evaluateIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger MainService evaluation", e)
                }

            } finally {
                pendingResult.finish()
                Log.d(TAG, "goAsync() finished for profile=$profileId, isStart=$isStart")
            }
        }
    }

    companion object {
        private const val TAG = "VolumeReceiver"
        private const val PREFS_NAME = "aquiethours_volume_backup"
        private const val KEY_SAVED_RINGER_MODE = "saved_ringer_mode"
        private const val KEY_SAVED_RING_VOLUME = "saved_ring_volume"
        private const val KEY_SAVED_MEDIA_VOLUME = "saved_media_volume"
        private const val KEY_SAVED_ALARM_VOLUME = "saved_alarm_volume"
        private const val KEY_HAS_SAVED_STATE = "has_saved_state"
        private const val ENFORCEMENT_PREFS = "aquiethours_enforcement"
        private const val KEY_ENFORCING_DISABLED = "enforcing_disabled"

        /**
         * Sets/clears the "enforcing disabled" flag in SharedPreferences.
         * VolumeEnforcerService checks this before re-applying profiles.
         */
        fun setEnforcingDisabled(context: Context, disabled: Boolean) {
            context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENFORCING_DISABLED, disabled).commit()  // commit() for synchronous write
            Log.d(TAG, "Set enforcing_disabled=$disabled")
        }

        /**
         * Checks whether enforcement has been disabled (schedule ended).
         */
        fun isEnforcingDisabled(context: Context): Boolean {
            return context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENFORCING_DISABLED, false)
        }

        fun calendarDayToJavaDayOfWeek(calDay: Int): java.time.DayOfWeek {
            return when (calDay) {
                Calendar.SUNDAY -> java.time.DayOfWeek.SUNDAY
                Calendar.MONDAY -> java.time.DayOfWeek.MONDAY
                Calendar.TUESDAY -> java.time.DayOfWeek.TUESDAY
                Calendar.WEDNESDAY -> java.time.DayOfWeek.WEDNESDAY
                Calendar.THURSDAY -> java.time.DayOfWeek.THURSDAY
                Calendar.FRIDAY -> java.time.DayOfWeek.FRIDAY
                Calendar.SATURDAY -> java.time.DayOfWeek.SATURDAY
                else -> java.time.DayOfWeek.MONDAY
            }
        }

        fun scaleVolume(audioManager: AudioManager, streamType: Int, percentage: Int): Int {
            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            return (percentage * maxVolume / 100).coerceIn(0, maxVolume)
        }

        suspend fun applyProfileSafely(
            context: Context,
            audioManager: AudioManager,
            ringerMode: Int,
            ringVolumePercent: Int,
            mediaVolumePercent: Int,
            alarmVolumePercent: Int
        ) {
            var scaledRing = scaleVolume(audioManager, AudioManager.STREAM_RING, ringVolumePercent)
            if (ringerMode == AudioManager.RINGER_MODE_NORMAL && scaledRing == 0) {
                // In Normal mode, Android automatically switches to Vibrate if ring volume is set to 0.
                // We clamp it to 1 so it stays in Normal mode.
                scaledRing = 1
            }
            val scaledMedia = scaleVolume(audioManager, AudioManager.STREAM_MUSIC, mediaVolumePercent)
            val scaledAlarm = if (alarmVolumePercent == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(AudioManager.STREAM_ALARM) else 1
            } else scaleVolume(audioManager, AudioManager.STREAM_ALARM, alarmVolumePercent)

            var notificationManager: android.app.NotificationManager? = null
            var hasDndAccess = false
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
            }
            
            // 2. Apply volumes and ringer mode
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                // Try true silent mode first (no vibration, no sound)
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                } catch (e: SecurityException) {
                    Log.w(TAG, "RINGER_MODE_SILENT blocked, falling back to VIBRATE", e)
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
                
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set silent stream volumes", e)
                }
            } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                // Do not explicitly unmute ring/notification streams here, as it can force the phone out of vibrate mode!
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set vibrate stream volumes", e)
                }
            } else {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                // Explicitly unmute Ring and Notification to pull out of silent states
                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, scaledRing, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, scaledRing, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, scaledRing, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set normal stream volumes", e)
                }
            }
            
            // === AUDIO FOCUS TRICK for Media Volume Enforcement ===
            val currentMedia = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val isMediaMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_MUSIC) else currentMedia == 0
            
            val mediaNeedsChange = if (scaledMedia == 0) {
                !isMediaMuted || currentMedia > 0
            } else {
                currentMedia != scaledMedia || isMediaMuted
            }

            var focusRequestOreo: android.media.AudioFocusRequest? = null
            if (mediaNeedsChange) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequestOreo = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            .setAcceptsDelayedFocusGain(false)
                            .build()
                        audioManager.requestAudioFocus(focusRequestOreo)
                    } else {
                        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request audio focus for media mute", e)
                }
            }

            // Media volume
            try {
                if (scaledMedia > 0) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledMedia, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set media volume", e)
            }

            // Release focus with a delay so OS has time to process setStreamVolume!
            if (mediaNeedsChange) {
                delay(500)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequestOreo != null) {
                        audioManager.abandonAudioFocusRequest(focusRequestOreo)
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocus(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to abandon audio focus", e)
                }
            }

            // Alarm volume
            try {
                if (scaledAlarm > 0) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                }
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, scaledAlarm, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set alarm volume", e)
            }

            // 3. Set the final DND state correctly based on ringer mode to prevent OEM desync
            if (hasDndAccess) {
                try {
                    if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                        notificationManager?.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    } else {
                        notificationManager?.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set final DND state", e)
                }
            }

            Log.d(TAG, "Volumes set: ring=$scaledRing, media=$scaledMedia, alarm=$scaledAlarm")
        }

        fun saveCurrentVolumes(context: Context, audioManager: AudioManager) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_HAS_SAVED_STATE, false)) {
                Log.d(TAG, "Volume state already saved, skipping to preserve original values")
                return
            }
            
            var currentFilter = -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                currentFilter = notificationManager.currentInterruptionFilter
            }
            
            prefs.edit()
                .putInt(KEY_SAVED_RINGER_MODE, audioManager.ringerMode)
                .putInt(KEY_SAVED_RING_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_RING))
                .putInt(KEY_SAVED_MEDIA_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .putInt(KEY_SAVED_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
                .putInt("saved_notification_volume", audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                .putInt("saved_dnd_filter", currentFilter)
                .putBoolean(KEY_HAS_SAVED_STATE, true)
                .commit()  // commit() for synchronous write
            Log.d(TAG, "Saved current volumes: ring=${audioManager.getStreamVolume(AudioManager.STREAM_RING)}, " +
                    "media=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, " +
                    "alarm=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}, " +
                    "notification=${audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)}, " +
                    "ringerMode=${audioManager.ringerMode}, dndFilter=$currentFilter")
        }

        fun restoreSavedVolumes(context: Context, audioManager: AudioManager) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            try {
                // CRITICAL: First force NORMAL mode to exit SILENT/VIBRATE mode
                // On Android, volume changes are silently ignored in SILENT mode
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException setting RINGER_MODE_NORMAL during restore", e)
                }

                // CRITICAL: Restore DND filter before setting volumes!
                // If DND is still active, Android will block all volume changes!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val savedFilter = prefs.getInt("saved_dnd_filter", -1)
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (notificationManager.isNotificationPolicyAccessGranted) {
                        try {
                            val filterToSet = if (savedFilter != -1) savedFilter else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            notificationManager.setInterruptionFilter(filterToSet)
                            Log.d(TAG, "Restored DND filter to $filterToSet before volume change")
                            
                            // Give Android a moment to process the DND exit before we blast volumes
                            Thread.sleep(100)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restore DND filter before volume change", e)
                        }
                    }
                }

                // Unmute ALL streams before setting volumes
                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unmute streams during restore", e)
                }

                if (prefs.getBoolean(KEY_HAS_SAVED_STATE, false)) {
                    val savedRingerMode = prefs.getInt(KEY_SAVED_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                    val savedRing = prefs.getInt(KEY_SAVED_RING_VOLUME, (audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.7).toInt())
                    val savedMedia = prefs.getInt(KEY_SAVED_MEDIA_VOLUME, (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.5).toInt())
                    val savedAlarm = prefs.getInt(KEY_SAVED_ALARM_VOLUME, (audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).toInt())
                    val savedNotification = prefs.getInt("saved_notification_volume", savedRing)

                    // Set volumes while in NORMAL mode (guaranteed to work)
                    var focusRequestOreo: android.media.AudioFocusRequest? = null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            focusRequestOreo = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                                )
                                .setAcceptsDelayedFocusGain(false)
                                .build()
                            audioManager.requestAudioFocus(focusRequestOreo)
                        } else {
                            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request audio focus for restore", e)
                    }

                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRing, 0)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMedia, 0)
                        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotification, 0)
                        val minAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(AudioManager.STREAM_ALARM) else 1
                        val actualAlarmVol = if (savedAlarm == 0) minAlarm else savedAlarm
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, actualAlarmVol, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore volumes", e)
                    }

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequestOreo != null) {
                                audioManager.abandonAudioFocusRequest(focusRequestOreo)
                            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                                audioManager.abandonAudioFocus(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to abandon audio focus after restore", e)
                        }
                    }, 500)

                    // Now set the final ringer mode the user had before
                    // If user was in silent before, keep NORMAL (they get their volumes back)
                    if (savedRingerMode != AudioManager.RINGER_MODE_SILENT) {
                        try {
                            audioManager.ringerMode = savedRingerMode
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Could not restore ringer mode $savedRingerMode", e)
                        }
                    }

                    prefs.edit()
                        .putBoolean(KEY_HAS_SAVED_STATE, false)
                        .remove("saved_dnd_filter")
                        .remove(KEY_SAVED_RINGER_MODE)
                        .remove(KEY_SAVED_RING_VOLUME)
                        .remove(KEY_SAVED_MEDIA_VOLUME)
                        .remove(KEY_SAVED_ALARM_VOLUME)
                        .remove("saved_notification_volume")
                        .commit()

                    Log.d(TAG, "Restored volumes: ring=$savedRing, media=$savedMedia, alarm=$savedAlarm, ringerMode=$savedRingerMode")
                } else {
                    try {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException setting RINGER_MODE_NORMAL in defaults", e)
                    }
                    val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                    val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, (maxRing * 0.7).toInt(), 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMedia * 0.5).toInt(), 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (maxAlarm * 0.8).toInt(), 0)
                    
                    Log.d(TAG, "No saved state found, reverted to defaults")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException restoring volumes", e)
            }
        }
    }
}

