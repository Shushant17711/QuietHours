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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Volatile for the same reason as suppressEnforcementUntil/cachedProfile below: written inside
    // updateEnforcementState() (stateMutex, one IO worker thread) but read from startEnforcementLoop()'s
    // poll and enforceProfileNow()'s early-return checks, which run on other IO worker threads via
    // observer/broadcast callbacks. Without a memory barrier a stale wasEnforcing=true can keep being
    // observed on a different thread after restoreSavedVolumes() already flipped it to false, so the
    // fast poll keeps re-applying the (now stale) profile right after a restore -- the phone never
    // audibly un-mutes even though the state machine thinks it did. Back-to-back/continuous schedules
    // hit this far more often simply because they cause many more state transitions per hour.
    @Volatile private var wasEnforcing = false
    @Volatile private var lastEnforcedProfileId = -1

    /**
     * Restore is not atomic: it writes ringer mode, DND filter and four stream volumes, and every
     * one of those writes echoes back as a VOLUME_CHANGED broadcast / ContentObserver callback.
     * Those callbacks land in enforceProfileNow(), see the volumes climbing away from the profile,
     * and re-apply the mute -- which fires more callbacks. The loop sustains itself and the last
     * re-apply wins, leaving the phone silent after the schedule ended. Volatile because the
     * observer callbacks arrive on the main thread while restore runs on serviceScope.
     */
    @Volatile private var suppressEnforcementUntil = 0L
    private var checkerJob: Job? = null
    private var enforcerJob: Job? = null
    @Volatile private var cachedProfile: Profile? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val applyMutex = Mutex()
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var volumeChangeReceiver: android.content.BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null

    /*
     * User media override. Pressing volume up inside Instagram/YouTube is a deliberate choice,
     * so while media is playing we leave the media level alone instead of fighting it. Once
     * playback has been silent for MEDIA_OVERRIDE_GRACE_MS (the user left the video/app) or the
     * screen turns off, the profile's media level comes back. Ring/alarm are still enforced.
     */
    @Volatile private var mediaOverride = false
    /** Media matched the profile at some point, so a later deviation came from the user. */
    @Volatile private var mediaWasCompliant = false
    @Volatile private var lastMusicActiveAt = 0L

    private fun resetMediaOverride(reason: String) {
        if (mediaOverride) Log.d(TAG, "Media override ended ($reason).")
        mediaOverride = false
        mediaWasCompliant = false
    }

    private fun mediaDeviates(profile: Profile, audioManager: AudioManager): Boolean {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_MUSIC) else current == 0
        val expected = VolumeReceiver.scaleVolume(audioManager, AudioManager.STREAM_MUSIC, profile.mediaVolume)
        return if (expected == 0) !muted || current > 0 else current != expected || muted
    }

    /** Updates the override from the current audio state; returns true if media should be left alone. */
    private fun updateMediaOverride(profile: Profile, audioManager: AudioManager): Boolean {
        val now = System.currentTimeMillis()
        val musicActive = audioManager.isMusicActive
        if (musicActive) lastMusicActiveAt = now
        val deviates = mediaDeviates(profile, audioManager)
        if (mediaOverride) {
            if (!musicActive && now - lastMusicActiveAt > MEDIA_OVERRIDE_GRACE_MS) {
                resetMediaOverride("playback stopped")
            }
        } else if (deviates && musicActive && mediaWasCompliant) {
            Log.d(TAG, "Media volume changed by the user during playback; leaving it until playback stops.")
            mediaOverride = true
        }
        if (!deviates) mediaWasCompliant = true
        return mediaOverride
    }

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
        loadEnforcementState()
        createNotificationChannel()
        
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    if (mediaOverride) {
                        resetMediaOverride("screen off")
                        serviceScope.launch { enforceProfileNow("ScreenOff") }
                    }
                } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    Log.d(TAG, "Screen turned on. Checking schedules instantly to bypass Doze delays.")
                    serviceScope.launch {
                        updateEnforcementState("ScreenOn")
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground() unconditionally: this service is always launched via
        // startForegroundService(), and skipping this call crashes the process with
        // ForegroundServiceDidNotStartInTimeException, which was silently killing the
        // background schedule checker.
        startForeground(NOTIFICATION_ID, createNotification())

        val isEnabled = AppPreferences.getInstance(applicationContext).isGlobalEnabled()
        if (!isEnabled) {
            Log.d(TAG, "App is globally disabled, stopping MainService")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "MainService started and running in foreground.")

        // Loaded once in onCreate. Re-loading here on every alarm/nudge ran unlocked on
        // the main thread and could resurrect wasEnforcing=true mid-restore.

        if (wasEnforcing) {
            registerObservers()
        }

        startScheduleChecker()
        startEnforcementLoop()

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
        enforcerJob?.cancel()
        screenReceiver?.let { unregisterReceiver(it) }
        unregisterObservers()
        
        val userDisabled = !AppPreferences.getInstance(applicationContext).isGlobalEnabled()
        if (wasEnforcing && userDisabled) {
            Log.d(TAG, "App turned off while enforcing. Restoring volumes.")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            VolumeReceiver.restoreSavedVolumes(applicationContext, audioManager)

            wasEnforcing = false
            lastEnforcedProfileId = -1
            saveEnforcementState()
        } else if (!userDisabled) {
            // Destroyed by the system / an OEM task killer (swipe from recents on MIUI,
            // XOS, ColorOS...). Restoring here used to UNMUTE the phone mid-class the
            // moment the app was swiped away. Keep the persisted state so whoever runs
            // next (restarted service, alarm, worker) continues it, and ask to be revived.
            Log.w(TAG, "Destroyed while enabled (wasEnforcing=$wasEnforcing). Keeping state, scheduling revive.")
            scheduleRevive(applicationContext)
        }
        Log.d(TAG, "MainService destroyed.")
    }

    private fun registerObservers() {
        if (volumeChangeReceiver != null) return
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        volumeChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // React to every action we registered for — enumerating a subset here silently
                // dropped the mute-changed broadcasts the filter was asking for.
                if (intent?.action != null) {
                    serviceScope.launch {
                        enforceProfileNow("Broadcast:${intent.action}")
                    }
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        // Volume-key changes inside another app (Instagram etc.) mute/unmute the stream rather
        // than writing Settings.System, so the ContentObserver never sees them. Without this
        // action those changes were only caught by the slow periodic sweep.
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        filter.addAction("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION")
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
        cachedProfile = null // re-read once per cycle so profile edits take effect
        val currentlyActiveProfileId = resolveActiveProfileId(applicationContext)

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
                // A new profile always starts by applying its own media level.
                resetMediaOverride("new profile")

                // A back-to-back schedule can start inside the previous one's restore window;
                // don't let a stale deaf window swallow the new schedule's first apply.
                suppressEnforcementUntil = 0L

                registerObservers()
                enforceProfileNow("NewSchedule")
            } else {
                // Idempotent, but guarantees we still have listeners if they were ever lost
                // (process restart, observer torn down) — otherwise enforcement silently
                // degrades to the periodic sweep only.
                registerObservers()
                enforceProfileNow("ReVerify")
            }
        } else {
            if (wasEnforcing) {
                Log.d(TAG, "[$source] No active schedules. Restoring volumes.")

                // NonCancellable is load-bearing, not defensive. The END alarm fires at the same
                // second this sweep notices the schedule is over, and the alarm's
                // startForegroundService() -> onStartCommand() restarts the checker/enforcer
                // loops, cancelling this very coroutine. That killed the restore *after* the
                // state teardown below had already persisted wasEnforcing=false, so the
                // relaunched service saw "nothing to restore" and the phone stayed muted
                // forever. Once we have decided to restore, the restore must complete.
                withContext(NonCancellable) {
                    unregisterObservers()

                    // Tear the enforcement state down BEFORE restoring, never after. Restore
                    // takes ~100ms+ and emits a volume-change event per stream it touches; any
                    // of those that were already queued still run enforceProfileNow(), and with
                    // the old ordering they still saw wasEnforcing==true and re-muted everything
                    // we had just restored. Clearing first makes them all hit the guard.
                    wasEnforcing = false
                    lastEnforcedProfileId = -1
                    cachedProfile = null
                    saveEnforcementState()

                    // unregisterObservers() cannot recall callbacks already dispatched to the
                    // main looper, and the manifest volume receiver is still live, so also hold
                    // a short deaf window over the restore itself.
                    suppressEnforcementUntil = System.currentTimeMillis() + RESTORE_SUPPRESSION_MS

                    // Serialise against enforceProfileNow(). Without this an enforcer already
                    // blocked on applyMutex resumes mid-restore and interleaves its mute writes
                    // with our unmute writes, which is how the phone ended up silent after the
                    // schedule had already ended.
                    applyMutex.withLock {
                        VolumeReceiver.restoreSavedVolumes(applicationContext, audioManager)
                    }
                }
            }
        }
    }

    /**
     * Tight enforcement poll. Broadcasts are the fast path, but volume-key changes made inside
     * another app don't reliably reach a background receiver on every ROM — this loop needs no
     * broadcast at all. The check itself is just AudioManager getters against a cached profile,
     * so it stays cheap; the expensive re-apply only runs when an actual deviation is found.
     */
    private fun startEnforcementLoop() {
        // Never restart a healthy loop. onStartCommand runs on every alarm/broadcast, and a
        // cancel-then-relaunch there tears down whatever the old job was in the middle of.
        if (enforcerJob?.isActive == true) return
        enforcerJob = serviceScope.launch {
            while (isActive) {
                if (wasEnforcing) {
                    try {
                        enforceProfileNow("FastPoll")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in enforcement loop", e)
                    }
                    delay(1000L)
                } else {
                    delay(5000L)
                }
            }
        }
    }

    private fun startScheduleChecker() {
        // Same reason as startEnforcementLoop(): the END alarm arriving mid-sweep used to
        // cancel this job while it was restoring volumes.
        if (checkerJob?.isActive == true) return
        checkerJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateEnforcementState("PeriodicChecker")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in schedule checker loop", e)
                }
                // Schedule evaluation only (hits the DB) — actual volume enforcement runs on
                // the much tighter startEnforcementLoop() poll.
                delay(30 * 1000L)
            }
        }
    }

    private suspend fun enforceProfileNow(source: String) {
        if (!wasEnforcing || lastEnforcedProfileId == -1) return

        // Our own restore is writing volumes right now; every write echoes back here. Applying
        // the profile again would undo the restore and re-trigger itself indefinitely.
        if (System.currentTimeMillis() < suppressEnforcementUntil) {
            Log.d(TAG, "[$source] Restore in progress, skipping enforcement.")
            return
        }

        // Must NOT call updateEnforcementState() here: this method is itself invoked from
        // inside that function while it holds stateMutex, and kotlinx Mutex is not reentrant —
        // re-entering suspends forever, permanently freezing the checker loop and all
        // broadcast-driven enforcement. onStartCommand/the periodic sweep handle teardown.
        if (!AppPreferences.getInstance(applicationContext).isGlobalEnabled()) {
            Log.d(TAG, "[$source] Globally disabled, skipping enforcement.")
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Cached so the 1s poll doesn't hit the DB every tick; invalidated on every schedule
        // re-evaluation (30s) so profile edits are picked up.
        val profile = cachedProfile?.takeIf { it.id == lastEnforcedProfileId }
            ?: AppDatabase.getDatabase(applicationContext).profileDao()
                .getProfileById(lastEnforcedProfileId)?.also { cachedProfile = it }
            ?: return

        val skipMedia = updateMediaOverride(profile, audioManager)
        if (shouldReapplyProfile(profile, audioManager, skipMedia)) {
            applyMutex.withLock {
                // Re-check AFTER winning the lock. The checks above ran before we suspended;
                // a restore can have started and finished while we waited here, and applying
                // now would silently re-mute the phone the instant the schedule ended.
                if (!wasEnforcing || System.currentTimeMillis() < suppressEnforcementUntil) {
                    Log.d(TAG, "[$source] Enforcement ended while queued, standing down.")
                    return
                }
                if (!shouldReapplyProfile(profile, audioManager, skipMedia)) return

                Log.d(TAG, "[$source] Deviation detected. Applying profile ${profile.name}.")
                VolumeReceiver.applyProfileSafely(
                    applicationContext, audioManager,
                    profile.ringerMode, profile.ringVolume, profile.mediaVolume, profile.silentVolume,
                    applyMedia = !skipMedia
                )
                
                delay(100)

                // Same reason as above: the 100ms nap is long enough for the END alarm to land.
                if (!wasEnforcing || System.currentTimeMillis() < suppressEnforcementUntil) return

                if (shouldReapplyProfile(profile, audioManager, skipMedia)) {
                    Log.w(TAG, "[$source] Retry apply...")
                    VolumeReceiver.applyProfileSafely(
                        applicationContext, audioManager,
                        profile.ringerMode, profile.ringVolume, profile.mediaVolume, profile.silentVolume,
                        applyMedia = !skipMedia
                    )
                    delay(100)
                }
            }
        }
    }

    private fun shouldReapplyProfile(profile: Profile, audioManager: AudioManager, skipMedia: Boolean = false): Boolean {
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

        val isRingMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_RING) else currentRing == 0
        val isNotificationMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION) else currentNotification == 0
        val isMediaMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_MUSIC) else currentMedia == 0
        val isAlarmMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_ALARM) else currentAlarm == 0

        val isSilentProfile = profile.ringerMode == AudioManager.RINGER_MODE_SILENT
        val ringerModeOk = if (isSilentProfile) {
            // Validate the exact state we actually applied. Accepting "SILENT or VIBRATE or
            // NORMAL" here is what let the user switch the ringer to vibrate/ring mid-schedule
            // and never get corrected — every reachable state counted as satisfied.
            when (VolumeReceiver.getEffectiveSilentMode(applicationContext)) {
                VolumeReceiver.SILENT_MODE_ZEN_FREE ->
                    currentRingerMode == AudioManager.RINGER_MODE_NORMAL && isRingMuted
                VolumeReceiver.SILENT_MODE_VIBRATE ->
                    currentRingerMode == AudioManager.RINGER_MODE_VIBRATE
                else ->
                    currentRingerMode == AudioManager.RINGER_MODE_SILENT
            }
        } else if (profile.ringerMode == AudioManager.RINGER_MODE_NORMAL && expectedRing == 0) {
            currentRingerMode == AudioManager.RINGER_MODE_NORMAL ||
            currentRingerMode == AudioManager.RINGER_MODE_VIBRATE ||
            currentRingerMode == AudioManager.RINGER_MODE_SILENT
        } else {
            currentRingerMode == profile.ringerMode
        }

        val ringNeedsReapply = if (expectedRing == 0) (!isRingMuted || currentRing > 0) else (currentRing != expectedRing || isRingMuted)
        val notificationNeedsReapply = if (expectedRing == 0) (!isNotificationMuted || currentNotification > 0) else (currentNotification != expectedRing || isNotificationMuted)
        
        val mediaNeedsReapply = !skipMedia && if (expectedMedia == 0) {
            !isMediaMuted || currentMedia > 0
        } else {
            currentMedia != expectedMedia || isMediaMuted
        }

        val alarmNeedsReapply = if (profile.silentVolume == 0) {
            currentAlarm > expectedAlarm || (!isAlarmMuted && currentAlarm >= expectedAlarm)
        } else {
            currentAlarm != expectedAlarm || isAlarmMuted
        }

        val needsReapply = !ringerModeOk ||
                (!isSilentProfile && profile.ringerMode == AudioManager.RINGER_MODE_NORMAL && (ringNeedsReapply || notificationNeedsReapply)) ||
                mediaNeedsReapply ||
                alarmNeedsReapply

        if (needsReapply) {
            Log.d(TAG, "DEVIATION ringerMode=$currentRingerMode(ok=$ringerModeOk) " +
                    "ring=$currentRing/muted=$isRingMuted " +
                    "media=$currentMedia/muted=$isMediaMuted/expected=$expectedMedia/reapply=$mediaNeedsReapply " +
                    "alarm=$currentAlarm/expected=$expectedAlarm/reapply=$alarmNeedsReapply")
        }
        return needsReapply
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.e(TAG, "App was swiped from recents! Trying to revive...")

        scheduleRevive(applicationContext)

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
        /** Playback must stay stopped this long before the user's media level is reverted. */
        private const val MEDIA_OVERRIDE_GRACE_MS = 3_000L
        private const val ENFORCEMENT_PREFS = "main_service_enforcement"
        private const val KEY_WAS_ENFORCING = "was_enforcing"
        private const val KEY_LAST_PROFILE_ID = "last_enforced_profile_id"

        /**
         * How long to ignore volume events after a restore begins. Restore itself sleeps 100ms
         * for the DND exit and then writes 4 streams; the observed re-mute storm ran ~10s. Kept
         * short so a genuine user volume change right after a schedule ends is still respected.
         */
        private const val RESTORE_SUPPRESSION_MS = 3000L

        /**
         * Restore volumes if a previous service instance died while enforcing. Called when
         * the user turns the app off: a live service restores in onDestroy (and clears the
         * flag), so this is a no-op then.
         */
        /** Serialises fallback evaluations from receivers/worker running concurrently. */
        private val evalMutex = Mutex()

        /**
         * Which profile should be active right now, or null. Single source of truth for
         * the service sweep, the alarm receiver and the worker. Also consumes "skip next"
         * for a window that has started and clears stale skip flags.
         */
        suspend fun resolveActiveProfileId(context: Context): Int? {
            val database = AppDatabase.getDatabase(context)
            val skipPrefs = context.getSharedPreferences(VolumeReceiver.SKIP_PREFS_NAME, Context.MODE_PRIVATE)
            val now = Calendar.getInstance()
            val today = VolumeReceiver.calendarDayToJavaDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
            val yesterday = today.minus(1)
            val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            var active: Int? = null
            for (schedule in database.scheduleDao().getActiveSchedules()) {
                val startMins = schedule.startHour * 60 + schedule.startMinute
                val endMins = schedule.endHour * 60 + schedule.endMinute
                val inWindow =
                    (schedule.dayOfWeek == today && startMins < endMins && nowMins in startMins until endMins) ||
                    (schedule.dayOfWeek == today && startMins >= endMins && nowMins >= startMins) ||
                    (schedule.dayOfWeek == yesterday && startMins >= endMins && nowMins < endMins)

                // "Skip next" lives in Schedule.skipNext (next occurrence) and the
                // skip_end_<id> pref (occurrence running now).
                val skipKey = "skip_end_${schedule.id}"
                if (!inWindow) {
                    // Window over: drop a leftover flag so a lost END alarm can't make
                    // this class skip itself every week.
                    if (skipPrefs.contains(skipKey)) skipPrefs.edit().remove(skipKey).commit()
                    continue
                }
                if (schedule.skipNext) {
                    database.scheduleDao().updateSkipNext(schedule.id, false)
                    skipPrefs.edit().putBoolean(skipKey, true).commit()
                }
                if (skipPrefs.getBoolean(skipKey, false)) continue
                if (active == null) active = schedule.profileId
            }
            return active
        }

        /**
         * Make the phone match the timetable even when MainService can't run. Android 12+
         * refuses to start a foreground service from the background (worker, inexact alarm,
         * or after an OEM killed us on swipe-from-recents), and the alarms used to depend on
         * the service entirely, so nothing muted or unmuted until the app was reopened.
         *
         * With [startServiceFirst], a successful service start hands the work over to the
         * service (it re-evaluates in onStartCommand) and this does nothing else.
         */
        suspend fun evaluateWithoutService(context: Context, startServiceFirst: Boolean = true) {
            val app = context.applicationContext
            if (!AppPreferences.getInstance(app).isGlobalEnabled()) return
            if (startServiceFirst) {
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        app, Intent(app, MainService::class.java).setAction(ACTION_EVALUATE_SCHEDULES)
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Can't start service from background ($e); applying directly.")
                }
            }
            evalMutex.withLock {
                val prefs = app.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
                val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val activeProfileId = resolveActiveProfileId(app)
                val wasEnforcing = prefs.getBoolean(KEY_WAS_ENFORCING, false)
                if (activeProfileId != null) {
                    val profile = AppDatabase.getDatabase(app).profileDao().getProfileById(activeProfileId) ?: return
                    if (!wasEnforcing) VolumeReceiver.saveCurrentVolumes(app, audioManager)
                    prefs.edit().putBoolean(KEY_WAS_ENFORCING, true).putInt(KEY_LAST_PROFILE_ID, activeProfileId).commit()
                    VolumeReceiver.applyProfileSafely(
                        app, audioManager, profile.ringerMode, profile.ringVolume, profile.mediaVolume, profile.silentVolume
                    )
                    Log.d(TAG, "Fallback applied profile ${profile.name}")
                } else if (wasEnforcing) {
                    restoreIfStillEnforcing(app)
                }
            }
        }

        /**
         * Ask to be brought back shortly. setAlarmClock is the one alarm class that app
         * freezers (Transsion Usf_Hiber, MIUI, ColorOS) can't hold back, and it grants the
         * exemption needed to start a foreground service from the background.
         */
        fun scheduleRevive(context: Context) {
            val app = context.applicationContext
            val pi = android.app.PendingIntent.getBroadcast(
                app, RESTART_REQUEST_CODE,
                Intent(app, RestartReceiver::class.java).setAction("com.example.aquiethours.RESTART_SERVICE"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val at = System.currentTimeMillis() + 3000
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    val show = android.app.PendingIntent.getActivity(
                        app, 0, Intent(app, com.example.aquiethours.MainActivity::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(at, show), pi)
                } else {
                    am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Could not schedule revive", e)
            }
        }

        fun restoreIfStillEnforcing(context: Context) {
            val prefs = context.getSharedPreferences(ENFORCEMENT_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_WAS_ENFORCING, false)) return
            Log.d(TAG, "Service was dead while enforcing; restoring volumes directly.")
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            VolumeReceiver.restoreSavedVolumes(context.applicationContext, audioManager)
            prefs.edit().putBoolean(KEY_WAS_ENFORCING, false).putInt(KEY_LAST_PROFILE_ID, -1).commit()
        }
    }
}
