package com.example.aquiethours

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aquiethours.data.AppPreferences
import com.example.aquiethours.receiver.MainService
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.icons.Person
import com.example.aquiethours.ui.icons.Schedule
import com.example.aquiethours.ui.icons.Settings
import com.example.aquiethours.ui.screens.AddScheduleScreen
import com.example.aquiethours.ui.screens.HomeScreen
import com.example.aquiethours.ui.screens.ImportTimetableScreen
import com.example.aquiethours.ui.screens.ProfileScreen
import com.example.aquiethours.ui.screens.SettingsScreen
import com.example.aquiethours.ui.theme.AQuietHoursTheme
import com.example.aquiethours.ui.theme.Motion
import com.example.aquiethours.ui.theme.OnPrimaryInk
import com.example.aquiethours.ui.theme.OnSurfaceDark
import com.example.aquiethours.ui.theme.PrimaryLight
import com.example.aquiethours.ui.theme.Q
import com.example.aquiethours.ui.theme.QuietDialog
import com.example.aquiethours.ui.theme.SurfaceContainerHighDark
import com.example.aquiethours.ui.theme.pressScale
import com.example.aquiethours.util.GitHubUpdateChecker
import com.example.aquiethours.util.UpdateInfo
import com.example.aquiethours.worker.ScheduleEnforcerWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Start the persistent foreground service to keep the app alive
        try {
            ContextCompat.startForegroundService(this, android.content.Intent(this, MainService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Could not start MainService", e)
        }

        val workRequest = PeriodicWorkRequestBuilder<ScheduleEnforcerWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ScheduleEnforcer",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        setContent {
            AQuietHoursTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val prefs = remember { AppPreferences.getInstance(this@MainActivity) }
                    var showTermsDialog by remember { mutableStateOf(!prefs.hasAcceptedTerms()) }

                    AppNavigation()

                    if (showTermsDialog) {
                        TermsAndConditionsDialog(
                            onAccept = {
                                prefs.setTermsAccepted(true)
                                showTermsDialog = false
                                requestNotificationsOnce()
                            },
                            onDecline = { finishAffinity() }
                        )
                    }

                    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                    LaunchedEffect(Unit) {
                        val lastCheck = prefs.getLastUpdateCheckTime()
                        val now = System.currentTimeMillis()
                        if (now - lastCheck > 86_400_000L) {
                            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                            } else {
                                @Suppress("DEPRECATION")
                                packageManager.getPackageInfo(packageName, 0)
                            }
                            GitHubUpdateChecker.checkForUpdate(packageInfo.versionName ?: "1.0")?.let { updateInfo = it }
                            prefs.setLastUpdateCheckTime(now)
                        }
                    }
                    updateInfo?.let { info ->
                        val uriHandler = LocalUriHandler.current
                        QuietDialog(
                            title = "Update available",
                            onDismiss = { updateInfo = null },
                            confirmText = "Download",
                            onConfirm = { uriHandler.openUri(info.downloadUrl); updateInfo = null },
                            dismissText = "Later"
                        ) {
                            Text("Quiet Hours ${info.newVersion} is out. Download it now?", color = OnSurfaceDark)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AppPreferences.getInstance(this).hasAcceptedTerms()) requestNotificationsOnce()
    }

    /**
     * Only the notification permission is a runtime dialog. Everything else (exact alarms,
     * battery, DND, autostart) used to be fired at the user as a chain of settings screens on
     * EVERY launch; it now lives in the home-screen checklist where the user can see why.
     */
    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("aquiethours_setup", MODE_PRIVATE)
        if (p.getBoolean("asked_notifications", false)) return
        p.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }
}

private val TabRoutes = listOf("home", "profiles", "settings")

internal data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val ease = FastOutSlowInEasing
    fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
        initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = {
                if (isTabSwitch()) fadeIn(tween(220)) + scaleIn(tween(260, easing = ease), initialScale = 0.98f)
                else slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { it / 5 } + fadeIn(tween(Motion.MEDIUM))
            },
            exitTransition = {
                if (isTabSwitch()) fadeOut(tween(160))
                else slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { -it / 10 } + fadeOut(tween(Motion.MEDIUM / 2))
            },
            popEnterTransition = {
                if (isTabSwitch()) fadeIn(tween(220))
                else slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { -it / 10 } + fadeIn(tween(Motion.MEDIUM))
            },
            popExitTransition = {
                if (isTabSwitch()) fadeOut(tween(160))
                else slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { it / 5 } + fadeOut(tween(Motion.MEDIUM / 2))
            }
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToAddSchedule = { navController.safeNavigate("add_schedule") },
                    onNavigateToProfiles = { navController.switchTab("profiles") },
                    onNavigateToSettings = { navController.switchTab("settings") },
                    onNavigateToImport = { navController.safeNavigate("import_timetable") },
                    onEditSchedule = { id -> navController.safeNavigate("edit_schedule/$id") }
                )
            }
            composable("profiles") {
                ProfileScreen(viewModel = viewModel, onNavigateBack = { navController.switchTab("home") })
            }
            composable("add_schedule") {
                AddScheduleScreen(viewModel = viewModel, scheduleId = null, onNavigateBack = { navController.safePopBack() })
            }
            composable(
                route = "edit_schedule/{scheduleId}",
                arguments = listOf(navArgument("scheduleId") { type = NavType.IntType })
            ) { entry ->
                AddScheduleScreen(
                    viewModel = viewModel,
                    scheduleId = entry.arguments?.getInt("scheduleId"),
                    onNavigateBack = { navController.safePopBack() }
                )
            }
            composable("settings") {
                SettingsScreen(onNavigateBack = { navController.switchTab("home") })
            }
            composable("import_timetable") {
                ImportTimetableScreen(viewModel = viewModel, onNavigateBack = { navController.safePopBack() })
            }
        }

        AnimatedVisibility(
            visible = currentRoute in TabRoutes,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(Motion.SHORT)) { it } + fadeOut(tween(Motion.SHORT))
        ) {
            // On Home the + button sits level with the bar, so the bar makes room for it.
            val endPad by animateDpAsState(
                if (currentRoute == "home") 24.dp + com.example.aquiethours.ui.screens.FabSize - 8.dp + 12.dp else 24.dp,
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "navEnd"
            )
            FloatingNavBar(
                endPadding = endPad,
                tabs = listOf(
                    Tab("home", "Timetable", Icons.Filled.Home),
                    Tab("profiles", "Profiles", Icons.Outlined.Person),
                    Tab("settings", "Settings", Icons.Filled.Settings)
                ),
                current = currentRoute,
                onSelect = { navController.switchTab(it) }
            )
        }
    }
}

/** Pill-shaped floating tab bar with a springy sliding highlight. */
@Composable
internal fun FloatingNavBar(tabs: List<Tab>, current: String?, onSelect: (String) -> Unit, endPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    val selected = tabs.indexOfFirst { it.route == current }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = endPadding, top = 14.dp, bottom = 14.dp)
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceContainerHighDark.copy(alpha = 0.96f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(6.dp)
    ) {
        // The selected tab gets just over half the bar so its label ("Timetable") always fits;
        // the others share the rest and show just their icon.
        val others = (tabs.size - 1).coerceAtLeast(1)
        val selW = if (tabs.size > 1) maxWidth * 0.56f else maxWidth
        val otherW = (maxWidth - selW) / others
        val navSpring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
        val x by animateDpAsState(otherW * selected, navSpring, label = "navX")
        Box(
            Modifier
                .offset(x = x)
                .width(selW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(PrimaryLight)
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                val isSel = i == selected
                val tint by animateColorAsState(if (isSel) OnPrimaryInk else Q.Muted, tween(Motion.SHORT), label = "navTint")
                val source = remember { MutableInteractionSource() }
                val tabW by animateDpAsState(if (isSel) selW else otherW, navSpring, label = "navW")
                Row(
                    Modifier
                        .width(tabW)
                        .fillMaxHeight()
                        .pressScale(source, 0.92f)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab.route) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(22.dp))
                    AnimatedVisibility(
                        visible = isSel,
                        enter = expandHorizontally(tween(Motion.MEDIUM)) + fadeIn(tween(Motion.MEDIUM)),
                        exit = shrinkHorizontally(tween(Motion.SHORT)) + fadeOut(tween(Motion.SHORT))
                    ) {
                        Row {
                            Spacer(Modifier.width(6.dp))
                            Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Tab switch: single instance per tab, state kept, Back from a tab returns to Home. */
private fun NavController.switchTab(route: String) {
    if (currentBackStackEntry?.destination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Ignore a second tap that lands while a transition is still running. */
private fun NavController.safeNavigate(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route)
}

/** Ignore a second Back/Save tap that lands while the first pop is still animating. */
private fun NavController.safePopBack() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}

@Composable
fun TermsAndConditionsDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    QuietDialog(
        title = "Welcome to Quiet Hours",
        onDismiss = { /* must choose */ },
        confirmText = "Accept",
        onConfirm = onAccept,
        dismissText = "Decline",
        onDismissButton = onDecline,
        icon = Icons.Outlined.Schedule
    ) {
        Text(
            "Your phone goes quiet during class, automatically. Please accept the Terms and Privacy Policy to continue.",
            color = OnSurfaceDark
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/TERMS_AND_CONDITIONS.md") }) {
            Text("Read Terms and Conditions", color = PrimaryLight)
        }
        TextButton(onClick = { uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/PRIVACY_POLICY.md") }) {
            Text("Read Privacy Policy", color = PrimaryLight)
        }
    }
}
