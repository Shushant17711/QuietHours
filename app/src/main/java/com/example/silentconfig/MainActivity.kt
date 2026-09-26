package com.example.aquiethours

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.example.aquiethours.worker.ScheduleEnforcerWorker
import com.example.aquiethours.receiver.MainService
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.compose.runtime.LaunchedEffect
import com.example.aquiethours.data.AppPreferences
import com.example.aquiethours.util.GitHubUpdateChecker
import com.example.aquiethours.util.OemBackgroundHelper
import com.example.aquiethours.util.UpdateInfo
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.screens.AddScheduleScreen
import com.example.aquiethours.ui.screens.HomeScreen
import com.example.aquiethours.ui.screens.ImportTimetableScreen
import com.example.aquiethours.ui.screens.ProfileScreen
import com.example.aquiethours.ui.screens.SettingsScreen
import com.example.aquiethours.ui.theme.AQuietHoursTheme

class MainActivity : ComponentActivity() {


    private var hasRequestedExactAlarm = false
    private var hasRequestedBatteryOpt = false
    private var hasRequestedAutostart = false
    private var hasRequestedDnd = false
    private var hasRequestedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        // Start the persistent foreground service to keep the app alive
        val serviceIntent = Intent(this, MainService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        val workRequest = PeriodicWorkRequestBuilder<ScheduleEnforcerWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ScheduleEnforcer",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        setContent {
            AQuietHoursTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showTermsDialog by remember { 
                        mutableStateOf(!AppPreferences.getInstance(this@MainActivity).hasAcceptedTerms()) 
                    }

                    if (showTermsDialog) {
                        TermsAndConditionsDialog(
                            onAccept = {
                                AppPreferences.getInstance(this@MainActivity).setTermsAccepted(true)
                                showTermsDialog = false
                            },
                            onDecline = {
                                finishAffinity() // Close the app if they decline
                            }
                        )
                    }

                    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                    
                    LaunchedEffect(Unit) {
                        val prefs = AppPreferences.getInstance(this@MainActivity)
                        val lastCheck = prefs.getLastUpdateCheckTime()
                        val now = System.currentTimeMillis()
                        
                        // 24 hours in millis: 86400000
                        if (now - lastCheck > 86400000) {
                            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                            } else {
                                @Suppress("DEPRECATION")
                                packageManager.getPackageInfo(packageName, 0)
                            }
                            val versionName = packageInfo.versionName ?: "1.0"
                            
                            val info = GitHubUpdateChecker.checkForUpdate(versionName)
                            if (info != null) {
                                updateInfo = info
                            }
                            prefs.setLastUpdateCheckTime(now)
                        }
                    }

                    if (updateInfo != null) {
                        val uriHandler = LocalUriHandler.current
                        AlertDialog(
                            onDismissRequest = { updateInfo = null },
                            title = { Text("Update Available") },
                            text = { Text("A new version (${updateInfo!!.newVersion}) of Quiet Hours is available. Would you like to download it now?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    uriHandler.openUri(updateInfo!!.downloadUrl)
                                    updateInfo = null
                                }) {
                                    Text("Update Now")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { updateInfo = null }) {
                                    Text("Later")
                                }
                            }
                        )
                    }

                    AppNavigation()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestNextPermission()
    }

    private fun checkAndRequestNextPermission() {
        // Request exact alarm permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val silentManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!silentManager.canScheduleExactAlarms()) {
                if (!hasRequestedExactAlarm) {
                    hasRequestedExactAlarm = true
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                }
            }
        }

        // Request battery optimization exemption so alarms work when app is swiped away
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                if (!hasRequestedBatteryOpt) {
                    hasRequestedBatteryOpt = true
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                }
            }
        }

        // Some OEMs (MIUI, XOS/HiOS, ColorOS, FuntouchOS, OxygenOS, EMUI) gate background
        // execution behind their own separate "Autostart" permission on top of the standard
        // battery-optimization exemption above. Without it these ROMs kill the foreground
        // service outright, so schedules only ever apply when the app is manually reopened.
        if (!hasRequestedAutostart) {
            hasRequestedAutostart = true
            if (OemBackgroundHelper.isAggressiveOem()) {
                OemBackgroundHelper.openAutostartSettings(this)
                return
            }
        }

        // Request Notification permission (Required for Foreground Services on Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!hasRequestedNotifications) {
                    hasRequestedNotifications = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    return
                }
            }
        }
        
        // Request DND permission to allow setting true Mute (RINGER_MODE_SILENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                if (!hasRequestedDnd) {
                    hasRequestedDnd = true
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                    return
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // When notification permission dialog is dismissed, check the next permission
        if (requestCode == 101) {
            checkAndRequestNextPermission()
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToAddSchedule = { navController.navigate("add_schedule") },
                onNavigateToProfiles = { navController.navigate("profiles") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToImport = { navController.navigate("import_timetable") },
                onEditSchedule = { scheduleId -> navController.navigate("edit_schedule/$scheduleId") }
            )
        }
        composable("profiles") {
            ProfileScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("add_schedule") {
            AddScheduleScreen(
                viewModel = viewModel,
                scheduleId = null,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "edit_schedule/{scheduleId}",
            arguments = listOf(androidx.navigation.navArgument("scheduleId") { type = androidx.navigation.NavType.IntType })
        ) { backStackEntry ->
            val scheduleId = backStackEntry.arguments?.getInt("scheduleId")
            AddScheduleScreen(
                viewModel = viewModel,
                scheduleId = scheduleId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("import_timetable") {
            ImportTimetableScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun TermsAndConditionsDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = { /* Prevent dismissing by clicking outside */ },
        title = { Text("Terms & Privacy Policy") },
        text = {
            Column {
                Text("Please accept our Terms and Conditions and Privacy Policy to continue using the app.")
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(onClick = { uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/TERMS_AND_CONDITIONS.md") }) {
                    Text("Read Terms and Conditions")
                }
                TextButton(onClick = { uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/PRIVACY_POLICY.md") }) {
                    Text("Read Privacy Policy")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
        }
    )
}
