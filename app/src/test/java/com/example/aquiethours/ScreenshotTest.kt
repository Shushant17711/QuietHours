package com.example.aquiethours

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.example.aquiethours.data.Profile
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.ui.QuietStore
import com.example.aquiethours.ui.screens.AddProfileDialog
import com.example.aquiethours.ui.screens.AddScheduleScreen
import com.example.aquiethours.ui.screens.HomeScreen
import com.example.aquiethours.ui.screens.ImportFlowState
import com.example.aquiethours.ui.screens.ProfileScreen
import com.example.aquiethours.ui.theme.AQuietHoursTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/** In-memory QuietStore with data shaped like the user's real (AI-imported) timetable. */
class FakeStore(
    schedules: List<Schedule> = sampleSchedules,
    enabled: Boolean = true,
    profiles: List<Profile> = sampleProfiles
) : QuietStore {
    override val profiles = MutableStateFlow(profiles)
    override val schedules = MutableStateFlow(schedules)
    override val isGlobalEnabled = MutableStateFlow(enabled)
    override val skippingNow = MutableStateFlow(emptySet<Int>())
    override fun addProfile(profile: Profile, onAdded: (Int) -> Unit) {
        val id = (profiles.value.maxOfOrNull { it.id } ?: 0) + 1
        profiles.value = profiles.value + profile.copy(id = id)
        onAdded(id)
    }
    override fun updateProfile(profile: Profile) {}
    override fun deleteProfile(profile: Profile) {}
    override fun addSchedule(schedule: Schedule) {}
    override fun addSchedules(schedules: List<Schedule>) {}
    override fun updateSchedule(schedule: Schedule) {}
    override fun deleteSchedule(schedule: Schedule) {}
    override fun toggleGlobalState(enabled: Boolean) {}
    override fun toggleScheduleStatus(schedule: Schedule, isActive: Boolean) {}
    override fun toggleSkipNext(schedule: Schedule, skip: Boolean) {}

    companion object {
        val sampleProfiles = listOf(
            Profile(1, "class", 0, 0, 0, 40),
            Profile(2, "Library", 1, 0, 20, 60),
            Profile(3, "Lab", 2, 30, 0, 80)
        )
        private var nextId = 1
        private fun s(day: DayOfWeek, sh: Int, sm: Int, eh: Int, em: Int, label: String, profile: Int = 1, active: Boolean = true, skip: Boolean = false) =
            Schedule(nextId++, day, sh, sm, eh, em, profile, active, label, skip)
        val sampleSchedules = listOf(
            s(DayOfWeek.WEDNESDAY, 9, 30, 10, 20, "25CSH-230::P::GP-B: Data Structures Lab"),
            s(DayOfWeek.WEDNESDAY, 10, 20, 11, 10, "25CSH-230::P::GP-B: Data Structures Lab"),
            s(DayOfWeek.WEDNESDAY, 11, 20, 12, 10, "25CST-237::L::GP-All: By Dr. Sharma", 2),
            s(DayOfWeek.WEDNESDAY, 13, 0, 13, 50, "Discrete Mathematics", 1, active = false),
            s(DayOfWeek.WEDNESDAY, 14, 0, 14, 50, "Physics", 3, skip = true),
            s(DayOfWeek.MONDAY, 9, 0, 10, 0, "Maths"),
            s(DayOfWeek.TUESDAY, 9, 0, 10, 0, "Chemistry"),
            s(DayOfWeek.TUESDAY, 10, 0, 11, 0, "English"),
            s(DayOfWeek.THURSDAY, 9, 30, 10, 20, "25CST-237::L::GP-All: By Dr. Sharma"),
            s(DayOfWeek.FRIDAY, 9, 0, 10, 0, "Biology"),
            s(DayOfWeek.FRIDAY, 11, 0, 12, 0, "Economics"),
            s(DayOfWeek.SATURDAY, 10, 0, 11, 0, "Sports")
        )
    }
}

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        maxPercentDifference = 1.0
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        paparazzi.snapshot(name) {
            CompositionLocalProvider(LocalInspectionMode provides true) { AQuietHoursTheme { content() } }
        }
    }

    // Wednesday 05:27 PM like the user's screenshot (next class tomorrow) and 10:00 AM (live class).
    private val evening = LocalDateTime.of(2026, 10, 7, 17, 27)
    private val morning = LocalDateTime.of(2026, 10, 7, 10, 0)

    @Test fun home_upNext() = shot("home_upNext") { HomeScreen(FakeStore(), {}, {}, fixedNow = evening) }
    @Test fun home_live() = shot("home_live") { HomeScreen(FakeStore(), {}, {}, fixedNow = morning) }
    @Test fun home_paused() = shot("home_paused") { HomeScreen(FakeStore(enabled = false), {}, {}, fixedNow = morning) }
    @Test fun home_empty() = shot("home_empty") { HomeScreen(FakeStore(schedules = emptyList()), {}, {}, fixedNow = morning) }
    @Test fun addSchedule_new() = shot("addSchedule_new") { AddScheduleScreen(FakeStore(), null, {}) }
    @Test fun addSchedule_edit() = shot("addSchedule_edit") { AddScheduleScreen(FakeStore(), 3, {}) }
    @Test fun profiles() = shot("profiles") { ProfileScreen(FakeStore(), {}) }
    @Test fun settings() = shot("settings") { com.example.aquiethours.ui.screens.SettingsScreen {} }
    // Pasted-code import with no profiles yet -> "New profile" -> save -> back with it ticked.
    private val pasted = listOf(
        com.example.aquiethours.util.ParsedScheduleEntry(DayOfWeek.MONDAY, 9, 0, 10, 0, "Maths"),
        com.example.aquiethours.util.ParsedScheduleEntry(DayOfWeek.TUESDAY, 11, 0, 12, 0, "Physics")
    )
    @Test fun importCode_1_noProfiles() = shot("importCode_1_noProfiles") {
        HomeScreen(FakeStore(profiles = emptyList()), {}, {}, fixedNow = evening, importFlow = ImportFlowState(pasted))
    }
    @Test fun importCode_2_newProfileTapped() {
        val flow = ImportFlowState(pasted).apply { startNewProfile() }
        // Sheet is a ModalBottomSheet (its own window) that animates in, so record frames over time.
        val view = androidx.compose.ui.platform.ComposeView(paparazzi.context).apply {
            setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AQuietHoursTheme { HomeScreen(FakeStore(profiles = emptyList()), {}, {}, fixedNow = evening, importFlow = flow) }
                }
            }
        }
        paparazzi.gif(view, name = "importCode_2_newProfileTapped", end = 1000, fps = 4)
    }
    @Test fun importCode_3_profileSaved() {
        val store = FakeStore(profiles = emptyList())
        val flow = ImportFlowState(pasted).apply {
            startNewProfile()
            saveNewProfile(store, Profile(0, "Class", 0, 0, 0, 40))
        }
        shot("importCode_3_profileSaved") { HomeScreen(store, {}, {}, fixedNow = evening, importFlow = flow) }
    }

    /** Bottom dock on a narrow phone with bigger system text (the half-cut "Timetable" bug). */
    @Test fun navBar_narrow() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1080, xdpi = 480, ydpi = 480, density = com.android.resources.Density.XXHIGH, fontScale = 1.15f))
        for ((i, route) in listOf("home", "profiles", "settings").withIndex()) {
            paparazzi.snapshot("navBar_narrow_$i") {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AQuietHoursTheme {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                            com.example.aquiethours.FloatingNavBar(
                                endPadding = if (route == "home") 92.dp else 24.dp,
                                tabs = listOf(
                                    com.example.aquiethours.Tab("home", "Timetable", androidx.compose.material.icons.Icons.Filled.Home),
                                    com.example.aquiethours.Tab("profiles", "Profiles", androidx.compose.material.icons.Icons.Outlined.Person),
                                    com.example.aquiethours.Tab("settings", "Settings", androidx.compose.material.icons.Icons.Filled.Settings)
                                ),
                                current = route,
                                onSelect = {}
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun importTimetable() = shot("import") {
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                    input: I,
                    options: androidx.core.app.ActivityOptionsCompat?
                ) {}
            }
        }
        CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            com.example.aquiethours.ui.screens.ImportTimetableScreen(FakeStore(), {})
        }
    }
}
