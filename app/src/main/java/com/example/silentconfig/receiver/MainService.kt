package com.example.aquiethours.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.aquiethours.data.AppDatabase
import com.example.aquiethours.data.AppPreferences
import com.example.aquiethours.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

class MainService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wasEnforcing = false
    private var lastEnforcedProfileId = -1
    private var checkerJob: Job? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val applyMutex = Mutex()
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var volumeChangeReceiver: android.content.BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null

    private fun saveEnforcementState() {
        val prefs = getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_WAS_ENFORCING, wasEnforcing)
            .putInt(KEY_LAST_PROFILE_ID, lastEnforcedProfileId)
            .commit()
    }

    private fun loadEnforcementState() {
        val prefs = getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
        wasEnforcing = prefs.getBoolean(KEY_WAS_ENFORCING, false)
        lastEnforcedProfileId = prefs.getInt(KEY_LAST_PROFILE_ID, -1)
        Log.d(TAG, "Loaded enforcement state: wasEnforcing=$wasEnforcing, lastEnforcedProfileId=$lastEnforcedProfileId")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    Log.d(TAG, "Screen turned on. Checking schedules instantly to bypass Doze delays.")
                    serviceScope.launch {
                        updateEnforcementState("ScreenOn")
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isEnabled = AppPreferences.getInstance(applicationContext).isGlobalEnabled()
        if (!isEnabled) {
            Log.d(TAG, "App is globally disabled, stopping MainService")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "MainService started and running in foreground.")

        loadEnforcementState()

        if (wasEnforcing) {
            registerObservers()
        }

        startScheduleChecker()

        if (intent?.action == ACTION_EVALUATE_SCHEDULES) {
            serviceScope.launch {
                updateEnforcementState("AlarmBroadcast")
            }
        } else {
            serviceScope.launch {
                updateEnforcementState("ServiceStart")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        checkerJob?.cancel()
        screenReceiver?.let { unregisterReceiver(it) }
        unregisterObservers()
        
        if (wasEnforcing) {
            Log.d(TAG, "Service being destroyed while enforcing. Restoring volumes safely.")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            VolumeReceiver.restoreSavedVolumes(applicationContext, audioManager)
            
            wasEnforcing = false
            lastEnforcedProfileId = -1
            saveEnforcementState()
        }
        Log.d(TAG, "MainService destroyed.")
    }

    private fun registerObservers() {
        if (volumeChangeReceiver != null) return
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        volumeChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION" || intent?.action == AudioManager.RINGER_MODE_CHANGED_ACTION) {
                    serviceScope.launch {
                        enforceProfileNow("Broadcast")
                    }
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeChangeReceiver, filter)
        }

        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (selfChange) return
                serviceScope.launch {
                    enforceProfileNow("ContentObserver")
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver!!)

        playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                super.onPlaybackConfigChanged(configs)
                val activeCount = configs?.size ?: 0
                if (activeCount > 0) {
                    serviceScope.launch {
                        delay(100)
                        enforceProfileNow("PlaybackCallback")
                    }
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback!!, Handler(Looper.getMainLooper()))
    }

    private fun unregisterObservers() {
        try { volumeChangeReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        volumeChangeReceiver = null
        
        try { volumeObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (e: Exception) {}
        volumeObserver = null
        
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            playbackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }
        } catch (e: Exception) {}
        playbackCallback = null
    }

    private val stateMutex = Mutex()
    
    private suspend fun updateEnforcementState(source: String) = stateMutex.withLock {
        val database = AppDatabase.getDatabase(applicationContext)
        val activeSchedules = database.scheduleDao().getActiveSchedules()
        val now = Calendar.getInstance()
        val currentDayOfWeek = VolumeReceiver.calendarDayToJavaDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
        val currentTimeMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        
        var currentlyActiveProfileId: Int? = null
        
        for (schedule in activeSchedules) {
            if (!schedule.isActive) continue
            
            val startMins = schedule.startHour * 60 + schedule.startMinute
            val endMins = schedule.endHour * 60 + schedule.endMinute
            val previousDayOfWeek = currentDayOfWeek.minus(1)

            val isNormal = schedule.dayOfWeek == currentDayOfWeek && startMins < endMins && currentTimeMins in startMins until endMins
            val crossesMidnight = schedule.dayOfWeek == currentDayOfWeek && startMins >= endMins && currentTimeMins >= startMins
            val crossesMidnightYesterday = schedule.dayOfWeek == previousDayOfWeek && startMins >= endMins && currentTimeMins < endMins
            
            if (isNormal || crossesMidnight || crossesMidnightYesterday) {
                currentlyActiveProfileId = schedule.profileId
                break
            }
        }
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (currentlyActiveProfileId != null) {
            if (!wasEnforcing || lastEnforcedProfileId != currentlyActiveProfileId) {
                Log.d(TAG, "[$source] Transition to active profile $currentlyActiveProfileId.")
                if (!wasEnforcing) {
                    VolumeReceiver.saveCurrentVolumes(applicationContext, audioManager)
                }
                
                wasEnforcing = true
                lastEnforcedProfileId = currentlyActiveProfileId
                saveEnforcementState()
                
                registerObservers()
                enforceProfileNow("NewSchedule")
            } else {
                enforceProfileNow("ReVerify")
            }
        } else {
            if (wasEnforcing) {
                Log.d(TAG, "[$source] No active schedules. Restoring volumes.")
                wasEnforcing = false
                lastEnforcedProfileId = -1
                saveEnforcementState()
                
                unregisterObservers()
                
                delay(1500)
                VolumeReceiver.restoreSavedVolumes(applicationContext, audioManager)
            }
        }
    }

    private fun startScheduleChecker() {
        checkerJob?.cancel()
        checkerJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateEnforcementState("PeriodicChecker")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in schedule checker loop", e)
                }
                delay(30 * 1000L)
            }
        }
    }

    private suspend fun enforceProfileNow(source: String) {
        if (!wasEnforcing || lastEnforcedProfileId == -1) return
        
        if (!AppPreferences.getInstance(applicationContext).isGlobalEnabled()) {
            updateEnforcementState("GlobalDisabled")
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val database = AppDatabase.getDatabase(applicationContext)
        val profile = database.profileDao().getProfileById(lastEnforcedProfileId) ?: return

        if (shouldReapplyProfile(profile, audioManager)) {
            applyMutex.withLock {
                if (!shouldReapplyProfile(profile, audioManager)) return
                
                Log.d(TAG, "[$source] Deviation detected. Applying profile ${profile.name}.")
                VolumeReceiver.applyProfileSafely(
                    applicationContext, audioManager,
                    profile.ringerMode, profile.ringVolume, profile.mediaVolume, profile.silentVolume
                )
                
                delay(100)
                
                if (shouldReapplyProfile(profile, audioManager)) {
                    Log.w(TAG, "[$source] Retry apply...")
                    VolumeReceiver.applyProfileSafely(
                        applicationContext, audioManager,
                        profile.ringerMode, profile.ringVolume, profile.mediaVolume, profile.silentVolume
                    )
                    delay(100)
                }
            }
        }
    }

    private fun shouldReapplyProfile(profile: Profile, audioManager: AudioManager): Boolean {
        val currentRingerMode = audioManager.ringerMode
        val currentRing = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val currentNotification = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val currentMedia = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentAlarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        
        var expectedRing = if (profile.ringerMode == AudioManager.RINGER_MODE_SILENT) 0 
            else VolumeReceiver.scaleVolume(audioManager, AudioManager.STREAM_RING, profile.ringVolume)
        if (profile.ringerMode == AudioManager.RINGER_MODE_NORMAL && expectedRing == 0) {
            expectedRing = 1
        }
        val expectedMedia = VolumeReceiver.scaleVolume(audioManager, AudioManager.STREAM_MUSIC, profile.mediaVolume)
        val expectedAlarm = if (profile.silentVolume == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(AudioManager.STREAM_ALARM) else 1
        } else VolumeReceiver.scaleVolume(audioManager, AudioManager.STREAM_ALARM, profile.silentVolume)

        val isSilentProfile = profile.ringerMode == AudioManager.RINGER_MODE_SILENT
        val ringerModeOk = if (isSilentProfile) {
            currentRingerMode == AudioManager.RINGER_MODE_SILENT || 
            currentRingerMode == AudioManager.RINGER_MODE_VIBRATE
        } else if (profile.ringerMode == AudioManager.RINGER_MODE_NORMAL && expectedRing == 0) {
            currentRingerMode == AudioManager.RINGER_MODE_NORMAL || 
            currentRingerMode == AudioManager.RINGER_MODE_VIBRATE ||
            currentRingerMode == AudioManager.RINGER_MODE_SILENT
        } else {
            currentRingerMode == profile.ringerMode
        }
        
        val isRingMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_RING) else currentRing == 0
        val isNotificationMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION) else currentNotification == 0
        val isMediaMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_MUSIC) else currentMedia == 0
        val isAlarmMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_ALARM) else currentAlarm == 0

        val ringNeedsReapply = if (expectedRing == 0) (!isRingMuted || currentRing > 0) else (currentRing != expectedRing || isRingMuted)
        val notificationNeedsReapply = if (expectedRing == 0) (!isNotificationMuted || currentNotification > 0) else (currentNotification != expectedRing || isNotificationMuted)
        
        val mediaNeedsReapply = if (expectedMedia == 0) {
            !isMediaMuted || currentMedia > 0
        } else {
            currentMedia != expectedMedia || isMediaMuted
        }

        val alarmNeedsReapply = if (profile.silentVolume == 0) {
            currentAlarm > expectedAlarm || (!isAlarmMuted && currentAlarm >= expectedAlarm)
        } else {
            currentAlarm != expectedAlarm || isAlarmMuted
        }

        return !ringerModeOk ||
                (!isSilentProfile && profile.ringerMode == AudioManager.RINGER_MODE_NORMAL && (ringNeedsReapply || notificationNeedsReapply)) ||
                mediaNeedsReapply ||
                alarmNeedsReapply
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.e(TAG, "App was swiped from recents! Trying to revive...")

        val restartIntent = Intent(applicationContext, RestartReceiver::class.java).apply {
            action = "com.example.aquiethours.RESTART_SERVICE"
            putExtra("SERVICE_TO_RESTART", "MainService")
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            applicationContext,
            RESTART_REQUEST_CODE,
            restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        } else {
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        }
        
        val broadcastIntent = Intent(applicationContext, RestartReceiver::class.java)
        broadcastIntent.action = "com.example.aquiethours.RESTART_SERVICE"
        sendBroadcast(broadcastIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "AQuietHours Background Core"
            val descriptionText = "Keeps the app alive and enforces volume schedules"
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val iconRes = try {
            android.R.drawable.ic_dialog_info
        } catch (e: Exception) {
            android.R.drawable.ic_lock_silent_mode
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AQuietHours is active")
            .setContentText("Enforcing volume schedules...")
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MainService"
        const val ACTION_EVALUATE_SCHEDULES = "com.example.aquiethours.ACTION_EVALUATE_SCHEDULES"
        private const val CHANNEL_ID = "main_service_channel"
        private const val NOTIFICATION_ID = 2002
        private const val RESTART_REQUEST_CODE = 2001
        private const val ENFORCEMENT_PREFS = "main_service_enforcement"
        private const val KEY_WAS_ENFORCING = "was_enforcing"
        private const val KEY_LAST_PROFILE_ID = "last_enforced_profile_id"
    }
}
