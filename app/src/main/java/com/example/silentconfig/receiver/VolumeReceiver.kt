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
        private const val KEY_DND_COUPLED_ROM = "dnd_coupled_rom"
        private const val KEY_EFFECTIVE_SILENT_MODE = "effective_silent_mode"

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

        /** How silence was actually achieved, so the enforcer can validate that exact state. */
        const val SILENT_MODE_TRUE_SILENT = 0
        const val SILENT_MODE_VIBRATE = 1
        const val SILENT_MODE_ZEN_FREE = 2

        fun getEffectiveSilentMode(context: Context): Int {
            return context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_EFFECTIVE_SILENT_MODE, SILENT_MODE_TRUE_SILENT)
        }

        fun setEffectiveSilentMode(context: Context, mode: Int) {
            context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_EFFECTIVE_SILENT_MODE, mode).commit()
        }

        /**
         * True once we've observed this ROM forcing Zen/DND on as a side effect of entering
         * RINGER_MODE_SILENT (Transsion XOS/HiOS do this). Such devices link the two inside
         * ZenModeHelper via VolumePolicy.doNotDisturbWhenSilent, which no app API can override.
         */
        fun isDndCoupledRom(context: Context): Boolean {
            return context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DND_COUPLED_ROM, false)
        }

        fun setDndCoupledRom(context: Context, coupled: Boolean) {
            context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DND_COUPLED_ROM, coupled).commit()
        }

        /**
         * Silence without ever touching Zen: keep the ringer in NORMAL so the ROM's
         * silent -> DND link never fires, and mute the audible streams instead.
         *
         * Deliberately uses ADJUST_MUTE rather than setStreamVolume(stream, 0): a zero index on
         * the ringer stream makes AudioService.getNewRingerMode() flip the device into VIBRATE,
         * which would defeat the whole point (and buzz in class).
         */
        suspend fun applyZenFreeSilence(audioManager: AudioManager) {
            for (attempt in 0 until 3) {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not set RINGER_MODE_NORMAL for zen-free silence", e)
                }

                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to mute ringer streams", e)
                }

                if (isZenFreeSilenceHolding(audioManager)) {
                    Log.d(TAG, "Zen-free silence applied (ringer NORMAL, ring stream muted)")
                    return
                }
                delay(120)
            }
            Log.w(TAG, "Zen-free silence did not fully hold. ringerMode=${audioManager.ringerMode}")
        }

        /** Ringer left in NORMAL (so Zen never triggers) with the ring stream actually muted. */
        fun isZenFreeSilenceHolding(audioManager: AudioManager): Boolean {
            val ringMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_RING)
            } else {
                audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0
            }
            return ringMuted && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
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
                if (isDndCoupledRom(context)) {
                    // This ROM (XOS/HiOS and friends) forces Zen/DND on whenever
                    // RINGER_MODE_SILENT is set, so we never ask for silent at all here.
                    setEffectiveSilentMode(context, SILENT_MODE_ZEN_FREE)
                    applyZenFreeSilence(audioManager)
                } else {
                    val filterBefore = notificationManager?.currentInterruptionFilter
                        ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL

                    // True silent. Some ROMs apply the ringer-mode write asynchronously, so an
                    // immediate read-back can still report the old value — retry before
                    // downgrading, otherwise we'd needlessly land on VIBRATE and the phone
                    // buzzes when the user asked for silence.
                    var silentApplied = false
                    for (attempt in 0 until 3) {
                        try {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                        } catch (e: SecurityException) {
                            Log.w(TAG, "RINGER_MODE_SILENT blocked by permission", e)
                            break
                        }
                        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                            silentApplied = true
                            break
                        }
                        delay(120)
                    }

                    // Did the ROM switch DND on behind our back as a side effect of going
                    // silent? If so this device links the two at the framework level, which no
                    // app API can override — remember it and use the zen-free path from now on.
                    val filterAfter = notificationManager?.currentInterruptionFilter
                        ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    val romForcedDnd = hasDndAccess &&
                            filterBefore == android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                            filterAfter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                            filterAfter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN

                    if (romForcedDnd) {
                        Log.w(TAG, "ROM forced DND on entering silent. Switching to zen-free silence permanently.")
                        setDndCoupledRom(context, true)
                        setEffectiveSilentMode(context, SILENT_MODE_ZEN_FREE)
                        try {
                            notificationManager?.setInterruptionFilter(
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to undo ROM-forced DND", e)
                        }
                        applyZenFreeSilence(audioManager)
                    } else {
                        if (!silentApplied) {
                            Log.w(TAG, "Could not reach RINGER_MODE_SILENT, falling back to VIBRATE")
                            setEffectiveSilentMode(context, SILENT_MODE_VIBRATE)
                            try {
                                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Failed to fall back to VIBRATE", e)
                            }
                        } else {
                            setEffectiveSilentMode(context, SILENT_MODE_TRUE_SILENT)
                        }
                        // Safe here: the device is already out of NORMAL, so a 0 index can't
                        // trigger the ringer-mode flip that getNewRingerMode() would apply.
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set silent stream volumes", e)
                        }
                    }
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
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledMedia, 0)
                } else {
                    // A bare index of 0 does NOT set the stream's mute flag, so isStreamMute()
                    // stays false and every enforcement pass sees the profile as still unmet —
                    // re-grabbing audio focus forever while leaving a level any volume-key press
                    // trivially overrides. ADJUST_MUTE sets the real mute flag and sticks.
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                }
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

            // 3. Do Not Disturb handling.
            // A Silent profile must NOT switch DND on. Since Android 8, RINGER_MODE_SILENT
            // alone is true silent (no ring, no vibrate, no notification sound) and is
            // independent of DND — ACCESS_NOTIFICATION_POLICY is only the permission gate
            // for calling it. Enabling DND additionally suppresses notifications entirely
            // and shows the system DND indicator, which is not what "Silent" should mean.
            //
            // So we only ever CLEAR DND, never set it: an active filter blocks ring and
            // notification volume changes, so a Normal/Vibrate profile can't take effect
            // until it's lifted. A Silent profile leaves the user's own DND choice alone.
            val nm = notificationManager
            if (hasDndAccess && nm != null && ringerMode != AudioManager.RINGER_MODE_SILENT) {
                try {
                    val currentFilter = nm.currentInterruptionFilter
                    val dndIsBlocking = currentFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                            currentFilter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    if (dndIsBlocking) {
                        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear DND state", e)
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

            val ringerMode = audioManager.ringerMode
            var ring = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            var notification = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            val media = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val alarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

            // A snapshot must never be able to silence the phone forever. "Ringer says NORMAL but the
            // ring stream is at zero / muted" is not a state a user can reach with the volume keys -
            // it is exactly the zen-free silence WE apply. If we see it here (process was killed and
            // restarted mid-schedule, so wasEnforcing was lost), the snapshot is our own enforcement
            // leaking in, and restoring it later would leave the user deaf. Fall back to a sane level.
            val ringLooksEnforced = ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                    (ring == 0 || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            audioManager.isStreamMute(AudioManager.STREAM_RING)))
            if (ringLooksEnforced) {
                ring = (audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.7).toInt().coerceAtLeast(1)
                notification = (audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) * 0.7).toInt().coerceAtLeast(1)
                Log.w(TAG, "Snapshot looked like our own enforced silence; saving audible ring=$ring instead")
            }

            prefs.edit()
                .putInt(KEY_SAVED_RINGER_MODE, ringerMode)
                .putInt(KEY_SAVED_RING_VOLUME, ring)
                .putInt(KEY_SAVED_MEDIA_VOLUME, media)
                .putInt(KEY_SAVED_ALARM_VOLUME, alarm)
                .putInt("saved_notification_volume", notification)
                .putInt("saved_dnd_filter", currentFilter)
                .putBoolean(KEY_HAS_SAVED_STATE, true)
                .commit()  // commit() for synchronous write
            Log.d(TAG, "Saved current volumes: ring=$ring, media=$media, alarm=$alarm, " +
                    "notification=$notification, ringerMode=$ringerMode, dndFilter=$currentFilter")
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
                            val filterToSet = if (savedFilter > 0) savedFilter else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
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

                    // Unmute AGAIN, after the writes. setStreamVolume(stream, 0) re-arms the mute flag
                    // on most ROMs, so a user whose media volume was genuinely 0 would be handed back a
                    // *muted* stream - volume keys then have to fight the flag before sound returns.
                    // Index 0 is the user's choice; the mute flag is ours, and it must not survive.
                    try {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                        audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear mute flags after restore", e)
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

