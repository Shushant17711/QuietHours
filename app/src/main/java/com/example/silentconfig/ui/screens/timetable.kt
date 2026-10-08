package com.example.aquiethours.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aquiethours.data.Profile
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.ui.QuietStore
import com.example.aquiethours.ui.icons.CalendarMonth
import com.example.aquiethours.ui.icons.CameraAlt
import com.example.aquiethours.ui.icons.Code
import com.example.aquiethours.ui.icons.KeyboardArrowDown
import com.example.aquiethours.ui.icons.Schedule
import com.example.aquiethours.ui.icons.Share
import com.example.aquiethours.ui.icons.Warning
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.ParsedScheduleEntry
import com.example.aquiethours.util.ScheduleUtils
import com.example.aquiethours.util.SetupHealth
import com.example.aquiethours.util.ShareHelper
import com.example.aquiethours.util.format12Hour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime

// ---------------------------------------------------------------------------------------------
// Now / next computation (week minutes, Monday 00:00 = 0)
// ---------------------------------------------------------------------------------------------

private const val WEEK = 7 * 24 * 60

private sealed interface NowState {
    data class Live(val schedule: Schedule, val minutesLeft: Int, val progress: Float) : NowState
    data class Next(val schedule: Schedule, val minutesUntil: Int) : NowState
    data object Idle : NowState
}

private fun Schedule.startAbs() = (dayOfWeek.value - 1) * 1440 + startHour * 60 + startMinute
private fun Schedule.durationMins(): Int {
    val d = (endHour * 60 + endMinute) - (startHour * 60 + startMinute)
    return if (d <= 0) d + 1440 else d
}

private fun computeNow(schedules: List<Schedule>, skippingNow: Set<Int>, now: LocalDateTime): NowState {
    val nowAbs = (now.dayOfWeek.value - 1) * 1440 + now.hour * 60 + now.minute
    val active = schedules.filter { it.isActive }
    for (s in active) {
        if (s.id in skippingNow) continue
        for (k in -1..0) {
            val st = s.startAbs() + k * WEEK
            if (nowAbs >= st && nowAbs < st + s.durationMins()) {
                val dur = s.durationMins()
                return NowState.Live(s, st + dur - nowAbs, (nowAbs - st).toFloat() / dur)
            }
        }
    }
    val next = active.map { s ->
        var delta = ((s.startAbs() - nowAbs) % WEEK + WEEK) % WEEK
        if (delta == 0) delta = WEEK
        if (s.skipNext) delta += WEEK
        s to delta
    }.minByOrNull { it.second } ?: return NowState.Idle
    return NowState.Next(next.first, next.second)
}

private fun dayShort(d: DayOfWeek) = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
private fun dayFull(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

/** State of the "Found N classes" → "New profile" flow on the home screen. */
class ImportFlowState(entries: List<ParsedScheduleEntry>? = null) {
    var parsedEntries by mutableStateOf(entries)
    var selectedProfileId by mutableStateOf<Int?>(null)
    var showAddProfile by mutableStateOf(false)

    /** "New profile" tapped: the class dialog hides and the profile sheet takes its place. */
    fun startNewProfile() { showAddProfile = true }

    /** Profile saved: back to the class dialog with the new profile already ticked. */
    fun saveNewProfile(store: QuietStore, profile: Profile) {
        store.addProfile(profile) { id -> selectedProfileId = id }
        showAddProfile = false
    }
}

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: QuietStore,
    onNavigateToAddSchedule: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onEditSchedule: (Int) -> Unit = {},
    /** Screenshot tests pin the clock; the app passes null and ticks in real time. */
    fixedNow: LocalDateTime? = null,
    /** Hoisted so screenshot tests can drive the import → "New profile" flow without taps. */
    importFlow: ImportFlowState = remember { ImportFlowState() }
) {
    val schedules by viewModel.schedules.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val isGlobalEnabled by viewModel.isGlobalEnabled.collectAsState()
    val skippingNow by viewModel.skippingNow.collectAsState()
    val context = LocalContext.current

    // Ticks so the hero countdown and "live" highlight stay current.
    var now by remember { mutableStateOf(fixedNow ?: LocalDateTime.now()) }
    LaunchedEffect(fixedNow) {
        if (fixedNow != null) return@LaunchedEffect
        while (true) {
            delay(15_000)
            now = LocalDateTime.now()
        }
    }
    val nowState = remember(schedules, skippingNow, now) { computeNow(schedules, skippingNow, now) }
    val liveId = (nowState as? NowState.Live)?.schedule?.id

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importCode by rememberSaveable { mutableStateOf("") }
    var parsedImportEntries by importFlow::parsedEntries
    var selectedProfileId by importFlow::selectedProfileId
    var importError by remember { mutableStateOf<String?>(null) }
    var showAddProfileDialog by importFlow::showAddProfile
    var fabExpanded by remember { mutableStateOf(false) }

    val days = DayOfWeek.values().toList()
    var selectedDay by rememberSaveable { mutableStateOf(now.dayOfWeek.value - 1) }
    // Remember which way we moved so the day title slides the right direction.
    var lastDirection by remember { mutableStateOf(1) }
    fun selectDay(i: Int) {
        val target = ((i % 7) + 7) % 7
        if (target == selectedDay) return
        lastDirection = if (target > selectedDay) 1 else -1
        selectedDay = target
    }

    val accent = when {
        !isGlobalEnabled -> OnSurfaceVariantDark
        nowState is NowState.Live -> SecondaryLight
        else -> PrimaryLight
    }

    AuroraBackground(accent = accent) {
        if (schedules.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                QuietHeader(title = "Quiet Hours", subtitle = greeting(now.hour))
                SetupChecklist(Modifier.padding(horizontal = Q.Gutter))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.CalendarMonth,
                        title = "Your week is wide open",
                        body = "Add your classes and Quiet Hours will silence your phone when each one starts, then bring the sound back after.",
                        modifier = Modifier.padding(bottom = Q.BottomBarSpace)
                    ) {
                        PrimaryButton("Scan my timetable", onNavigateToImport, icon = Icons.Outlined.CameraAlt, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SecondaryButton("Add a class", onNavigateToAddSchedule, icon = Icons.Filled.Add, modifier = Modifier.weight(1f))
                            SecondaryButton("Paste code", { showImportDialog = true }, icon = Icons.Outlined.Code, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            val day = days[selectedDay]
            val daySchedules = remember(schedules, day) {
                schedules.filter { it.dayOfWeek == day }.sortedBy { it.startHour * 60 + it.startMinute }
            }
            val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
            val currentSelected by rememberUpdatedState(selectedDay)

            val listState = rememberLazyListState()
            // The week strip only gets a backdrop once it is actually pinned to the top.
            val stuck by remember { derivedStateOf { listState.firstVisibleItemIndex >= 3 } }
            val backdrop by animateFloatAsState(if (stuck) 1f else 0f, tween(Motion.SHORT), label = "stuck")

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    // Swipe sideways anywhere on the list to change day.
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -swipeThreshold) selectDay(currentSelected + 1)
                                else if (total > swipeThreshold) selectDay(currentSelected - 1)
                            },
                            onHorizontalDrag = { _, dx -> total += dx }
                        )
                    },
                contentPadding = PaddingValues(bottom = Q.BottomBarSpace + 24.dp)
            ) {
                item(key = "header") {
                    QuietHeader(
                        title = "Quiet Hours",
                        subtitle = greeting(now.hour),
                        statusBarPadding = false,
                        actions = {
                            CircleIconButton(Icons.Filled.Share, "Share timetable", onClick = {
                                val encoded = ShareHelper.encodeSchedules(schedules)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, encoded)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(send, "Share timetable"))
                            })
                        }
                    )
                }
                item(key = "hero") {
                    NowCard(
                        state = nowState,
                        enabled = isGlobalEnabled,
                        profiles = profiles,
                        onToggleEnabled = { viewModel.toggleGlobalState(it) },
                        onSkipLive = { viewModel.toggleSkipNext(it, true) },
                        onOpen = { s -> selectDay(s.dayOfWeek.value - 1) },
                        modifier = Modifier.padding(horizontal = Q.Gutter).enterAnimation(0)
                    )
                }
                item(key = "setup") {
                    SetupChecklist(Modifier.padding(horizontal = Q.Gutter).enterAnimation(1))
                }
                stickyHeader(key = "week") {
                    // Opaque-ish backdrop so classes scroll *under* the strip, not through it.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark.copy(alpha = 0.94f * backdrop))
                            .padding(horizontal = Q.Gutter)
                            .padding(top = 12.dp, bottom = 12.dp)
                    ) {
                        WeekStrip(
                            days = days,
                            selected = selectedDay,
                            today = now.dayOfWeek,
                            counts = days.map { d -> schedules.count { it.dayOfWeek == d } },
                            onSelect = { selectDay(it) }
                        )
                    }
                }
                item(key = "dayTitle") {
                    AnimatedContent(
                        targetState = day,
                        transitionSpec = {
                            val dir = lastDirection
                            (slideInHorizontally(tween(Motion.MEDIUM)) { dir * it / 3 } + fadeIn(tween(Motion.MEDIUM))) togetherWith
                                (slideOutHorizontally(tween(Motion.SHORT)) { -dir * it / 3 } + fadeOut(tween(Motion.SHORT)))
                        },
                        label = "dayTitle"
                    ) { d ->
                        val count = schedules.count { it.dayOfWeek == d }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Q.Gutter).padding(top = 4.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                if (d == now.dayOfWeek) "Today" else dayFull(d),
                                style = MaterialTheme.typography.titleLarge,
                                color = OnSurfaceDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (count == 0) "free day" else "$count class${if (count != 1) "es" else ""}",
                                color = Q.Muted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
                if (daySchedules.isEmpty()) {
                    item(key = "free-$day") {
                        FreeDayCard(day, onNavigateToAddSchedule, Modifier.padding(horizontal = Q.Gutter).enterAnimation())
                    }
                } else {
                    itemsIndexed(daySchedules, key = { _, s -> s.id }) { index, s ->
                        val skipped = s.skipNext || s.id in skippingNow
                        TimelineRow(
                            schedule = s,
                            profile = profiles.find { it.id == s.profileId },
                            isLive = s.id == liveId,
                            isSkipped = skipped,
                            isLast = index == daySchedules.lastIndex,
                            onEdit = { onEditSchedule(s.id) },
                            onToggle = { viewModel.toggleScheduleStatus(s, it) },
                            onSkip = { viewModel.toggleSkipNext(s, !skipped) },
                            onDelete = { viewModel.deleteSchedule(s) },
                            modifier = Modifier
                                .padding(horizontal = Q.Gutter)
                                .animateItemPlacement()
                                .enterAnimation(index)
                        )
                    }
                }
            }

            SpeedDial(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                actions = listOf(
                    DialAction("Add a class", Icons.Filled.Add, PrimaryLight) { onNavigateToAddSchedule() },
                    DialAction("Scan timetable", Icons.Outlined.CameraAlt, SecondaryLight) { onNavigateToImport() },
                    DialAction("Paste share code", Icons.Outlined.Code, TertiaryLight) { showImportDialog = true }
                )
            )
        }
    }

    // ----- Import from code -----
    if (showImportDialog) {
        val clipboardManager = LocalClipboardManager.current
        val promptText = "Extract the timetable from the image and format it exactly as a single line string like this: day;startH;startM;endH;endM;label|day;startH;startM;endH;endM;label\n\nDays should be numbers from 1 (Monday) to 7 (Sunday). Hours should be 24-hour format. Do not add any markdown, markdown code blocks, or extra text. Output only the delimited string."
        QuietDialog(
            title = "Import from code",
            icon = Icons.Outlined.Code,
            onDismiss = { showImportDialog = false; importCode = ""; importError = null },
            confirmText = "Next",
            confirmEnabled = importCode.isNotBlank(),
            onConfirm = {
                val decoded = ShareHelper.decodeSchedules(importCode.trim())
                if (decoded.isNullOrEmpty()) {
                    importError = "That code isn't valid. Check you copied all of it."
                } else {
                    parsedImportEntries = decoded
                    showImportDialog = false
                }
            }
        ) {
            Text(
                "Paste a code a friend shared, or ask ChatGPT/Gemini to read your timetable photo using the prompt below.",
                style = MaterialTheme.typography.bodyMedium,
                color = Q.Muted
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                "Copy AI prompt",
                onClick = {
                    clipboardManager.setText(AnnotatedString(promptText))
                    Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                },
                icon = Icons.Outlined.Code,
                tint = TertiaryLight,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = importCode,
                onValueChange = { importCode = it; importError = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste code here") },
                minLines = 3,
                maxLines = 5,
                isError = importError != null,
                shape = RoundedCornerShape(Q.ControlRadius),
                colors = quietFieldColors()
            )
            AnimatedVisibility(visible = importError != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    importError.orEmpty(),
                    color = ErrorDark,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    // ----- Assign imported classes to a profile -----
    // Hidden while the "new profile" sheet is open: the sheet used to open *underneath*
    // this dialog, so tapping "New profile" seemed to do nothing.
    parsedImportEntries?.takeIf { !showAddProfileDialog }?.let { entries ->
        QuietDialog(
            title = "Found ${entries.size} class${if (entries.size != 1) "es" else ""}",
            onDismiss = { parsedImportEntries = null; selectedProfileId = null },
            confirmText = "Import",
            confirmEnabled = selectedProfileId != null,
            onConfirm = {
                val profileId = selectedProfileId ?: return@QuietDialog
                val toSave = entries.map { e ->
                    Schedule(
                        dayOfWeek = e.day, startHour = e.startHour, startMinute = e.startMinute,
                        endHour = e.endHour, endMinute = e.endMinute, profileId = profileId, label = e.label
                    )
                }
                val current = schedules.toMutableList()
                var overlap = false
                for (s in toSave) {
                    if (ScheduleUtils.hasOverlap(s, current)) { overlap = true; break }
                    current.add(s)
                }
                if (overlap) {
                    // Back to the code dialog so the error is actually visible.
                    importError = "Some of these classes overlap with ones you already have, or with each other."
                    parsedImportEntries = null
                    selectedProfileId = null
                    showImportDialog = true
                } else {
                    viewModel.addSchedules(toSave)
                    importCode = ""
                    importError = null
                    parsedImportEntries = null
                    selectedProfileId = null
                }
            }
        ) {
            Text("Choose how your phone should sound during these classes.", color = Q.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            ProfilePickerList(
                profiles = profiles,
                selectedId = selectedProfileId,
                onSelect = { selectedProfileId = it },
                onCreate = { importFlow.startNewProfile() }
            )
        }
    }

    if (showAddProfileDialog) {
        AddProfileDialog(
            onDismiss = { showAddProfileDialog = false },
            onSave = { profile ->
                // Back to the import dialog with the new profile already ticked.
                importFlow.saveNewProfile(viewModel, profile)
            }
        )
    }
}

@Composable
fun quietFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
    focusedBorderColor = PrimaryLight,
    errorBorderColor = ErrorDark,
    cursorColor = PrimaryLight,
    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    focusedContainerColor = Color.White.copy(alpha = 0.06f),
    errorContainerColor = Color.White.copy(alpha = 0.04f)
)

@Composable
private fun ProfilePickerList(profiles: List<Profile>, selectedId: Int?, onSelect: (Int) -> Unit, onCreate: () -> Unit) {
    Column(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 200.dp)) {
            items(profiles, key = { it.id }) { p ->
                val v = ringerVisual(p.ringerMode)
                val sel = p.id == selectedId
                val border by animateColorAsState(if (sel) v.color else Color.White.copy(alpha = 0.08f), tween(Motion.SHORT), label = "pb")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Q.ControlRadius))
                        .background(if (sel) v.color.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
                        .border(1.dp, border, RoundedCornerShape(Q.ControlRadius))
                        .clickable { onSelect(p.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(v.icon, v.color, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                        Text(v.label, color = Q.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .border(2.dp, if (sel) v.color else Q.Muted, CircleShape)
                            .padding(4.dp)
                    ) {
                        PopIn(sel) { Box(Modifier.fillMaxSize().clip(CircleShape).background(v.color)) }
                    }
                }
            }
        }
        SecondaryButton("New profile", onCreate, icon = Icons.Filled.Add, tint = PrimaryLight, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------------------------
// Hero "now" card
// ---------------------------------------------------------------------------------------------

@Composable
private fun NowCard(
    state: NowState,
    enabled: Boolean,
    profiles: List<Profile>,
    onToggleEnabled: (Boolean) -> Unit,
    onSkipLive: (Schedule) -> Unit,
    onOpen: (Schedule) -> Unit,
    modifier: Modifier = Modifier
) {
    val live = state as? NowState.Live
    val accent = when {
        !enabled -> OnSurfaceVariantDark
        live != null -> SecondaryLight
        else -> PrimaryLight
    }
    QuietCard(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        radius = Q.HeroRadius,
        contentPadding = PaddingValues(20.dp),
        onClick = { (state as? NowState.Live)?.schedule?.let(onOpen) ?: (state as? NowState.Next)?.schedule?.let(onOpen) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = when {
                    !enabled -> "Paused"
                    live != null -> "Silenced now"
                    state is NowState.Next -> "Up next"
                    else -> "All clear"
                },
                color = accent,
                pulsing = enabled && live != null
            )
            Spacer(Modifier.weight(1f))
            PillToggle(checked = enabled, onCheckedChange = onToggleEnabled)
        }
        Spacer(Modifier.height(16.dp))

        val key = when {
            !enabled -> "off"
            state is NowState.Live -> "live-${state.schedule.id}"
            state is NowState.Next -> "next-${state.schedule.id}"
            else -> "idle"
        }
        AnimatedContent(
            targetState = key,
            transitionSpec = {
                (fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM)) { it / 4 }) togetherWith
                    (fadeOut(tween(Motion.SHORT)) + slideOutVertically(tween(Motion.SHORT)) { -it / 4 })
            },
            label = "hero"
        ) { k ->
            Column {
                when {
                    k == "off" -> {
                        HeroTitle("Quiet Hours is off")
                        HeroSub("Your phone won't change sound on its own. Flip the switch to resume.")
                    }
                    live != null -> {
                        val s = live.schedule
                        val profile = profiles.find { it.id == s.profileId }
                        HeroTitle(s.label.ifBlank { "Class in progress" })
                        HeroSub("Until ${format12Hour(s.endHour, s.endMinute)} · ${formatDuration(live.minutesLeft)} left")
                        Spacer(Modifier.height(14.dp))
                        AnimatedBar(progress = live.progress, color = accent)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            profile?.let { val v = ringerVisual(it.ringerMode); Chip(it.name, v.color, icon = v.icon) }
                            Spacer(Modifier.weight(1f))
                            SecondaryButton("Skip this class", { onSkipLive(s) }, tint = TertiaryLight, modifier = Modifier.height(40.dp))
                        }
                    }
                    state is NowState.Next -> {
                        val s = state.schedule
                        HeroTitle(s.label.ifBlank { "Next class" })
                        val whenText = when {
                            state.minutesUntil < 60 -> "in ${state.minutesUntil} min"
                            state.minutesUntil < 24 * 60 -> "in ${formatDuration(state.minutesUntil)}"
                            else -> dayFull(s.dayOfWeek)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Chip(whenText, accent)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${format12Hour(s.startHour, s.startMinute)} – ${format12Hour(s.endHour, s.endMinute)}",
                                color = Q.Muted, style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (s.skipNext) {
                            Spacer(Modifier.height(10.dp))
                            Chip("Skipping once", TertiaryLight)
                        }
                    }
                    else -> {
                        HeroTitle("Nothing scheduled")
                        HeroSub("All your classes are switched off.")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = OnSurfaceDark,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HeroSub(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = Q.Muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun StatusPill(text: String, color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            if (pulsing) {
                Box(
                    Modifier.size(10.dp)
                        .graphicsLayer { scaleX = 1f + pulse * 1.4f; scaleY = 1f + pulse * 1.4f; alpha = 1f - pulse }
                        .clip(CircleShape).background(color)
                )
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        AnimatedContent(targetState = text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pill") {
            Text(it, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AnimatedBar(progress: Float, color: Color) {
    var started by remember { mutableStateOf(false) }
    if (androidx.compose.ui.platform.LocalInspectionMode.current) started = true
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) progress.coerceIn(0f, 1f) else 0f, tween(900, easing = FastOutSlowInEasing), label = "bar")
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

// ---------------------------------------------------------------------------------------------
// Reliability checklist (slim banner, expands)
// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(runCatching { SetupHealth.missing(context) }.getOrDefault(emptyList())) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) missing = runCatching { SetupHealth.missing(context) }.getOrDefault(emptyList())
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AnimatedVisibility(
        visible = missing.isNotEmpty(),
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        QuietCard(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            onClick = { expanded = !expanded }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(TertiaryLight))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Fix ${missing.size} setting${if (missing.size != 1) "s" else ""} so it works when closed",
                    color = OnSurfaceDark,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                val rot by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.MEDIUM), label = "chev")
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Q.Muted, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rot })
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    missing.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = OnSurfaceDark, style = MaterialTheme.typography.titleSmall)
                                Text(item.why, color = Q.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (item == SetupHealth.Item.AUTOSTART) {
                                Column(horizontalAlignment = Alignment.End) {
                                    SmallAction("Open") { SetupHealth.open(context, item) }
                                    Spacer(Modifier.height(4.dp))
                                    SmallAction("Done", Q.Muted) {
                                        SetupHealth.confirmAutostart(context)
                                        missing = SetupHealth.missing(context)
                                    }
                                }
                            } else {
                                SmallAction("Fix") { SetupHealth.open(context, item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, color: Color = TertiaryLight, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Text(
        text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .pressScale(source, 0.9f)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

// ---------------------------------------------------------------------------------------------
// Week strip + timeline
// ---------------------------------------------------------------------------------------------

/** Seven equal cells measured from the real width, so it can never overflow the screen. */
@Composable
private fun WeekStrip(
    days: List<DayOfWeek>,
    selected: Int,
    today: DayOfWeek,
    counts: List<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainerDark)
            .border(1.dp, Q.CardBorder, RoundedCornerShape(20.dp))
            .padding(4.dp)
    ) {
        val cell = maxWidth / days.size
        val x by animateDpAsState(cell * selected, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "wx")
        Box(
            Modifier
                .offset(x = x)
                .width(cell)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(PrimaryLight)
        )
        Row(Modifier.fillMaxSize()) {
            days.forEachIndexed { i, d ->
                val isSel = i == selected
                val fg by animateColorAsState(
                    when { isSel -> OnPrimaryInk; d == today -> PrimaryLight; else -> OnSurfaceVariantDark },
                    tween(Motion.SHORT), label = "wfg"
                )
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .width(cell)
                        .fillMaxHeight()
                        .clickable(interactionSource = source, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        dayShort(d),
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSel || d == today) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(4.dp)) {
                        repeat(counts[i].coerceAtMost(4)) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(fg.copy(alpha = if (isSel) 0.6f else 0.5f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeDayCard(day: DayOfWeek, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    QuietCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Schedule, PrimaryLight)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("No classes on ${dayFull(day)}", color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                Text("Your phone stays as you leave it.", color = Q.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton("Add a class", onAdd, icon = Icons.Filled.Add, tint = PrimaryLight, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TimelineRow(
    schedule: Schedule,
    profile: Profile?,
    isLive: Boolean,
    isSkipped: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dim by animateFloatAsState(if (schedule.isActive) 1f else 0.45f, tween(Motion.MEDIUM), label = "dim")
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val railColor = if (isLive) SecondaryLight else Color.White.copy(alpha = 0.35f)

    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Start time
        Column(Modifier.width(52.dp).padding(top = 15.dp), horizontalAlignment = Alignment.End) {
            val (t, ampm) = format12Hour(schedule.startHour, schedule.startMinute).split(" ").let { it[0] to it.getOrElse(1) { "" } }
            Text(
                t.trimStart('0'),
                color = if (isLive) SecondaryLight else OnSurfaceDark,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(ampm, color = Q.Muted, style = MaterialTheme.typography.labelSmall)
        }
        // Rail
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!isLast) {
                Box(Modifier.padding(top = 32.dp).width(1.5.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
            }
            Box(Modifier.padding(top = 18.dp)) { RailDot(color = railColor, live = isLive) }
        }
        // Card
        QuietCard(
            modifier = Modifier.weight(1f).padding(bottom = 12.dp).graphicsLayer { alpha = dim },
            accent = if (isLive) SecondaryLight else null,
            onClick = onEdit,
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        schedule.label.ifBlank { "Untitled class" },
                        color = OnSurfaceDark,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "until ${format12Hour(schedule.endHour, schedule.endMinute)} · ${formatDuration(schedule.durationMins())}",
                        color = Q.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                PillToggle(schedule.isActive, onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                profile?.let { val v = ringerVisual(it.ringerMode); Chip(it.name, v.color, icon = v.icon) }
                    ?: Chip("No profile", Q.Muted)
                Spacer(Modifier.width(6.dp))
                PopIn(isSkipped) { Chip("Skipping", TertiaryLight) }
                Spacer(Modifier.weight(1f))
                Box {
                    TinyIcon(Icons.Filled.MoreVert, Q.Muted, "More") { menuOpen = true }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(SurfaceContainerHighDark)
                    ) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                        if (schedule.isActive) {
                            DropdownMenuItem(
                                text = { Text(if (isSkipped) "Don't skip" else "Skip next time") },
                                onClick = { menuOpen = false; onSkip() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = ErrorDark) },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        QuietDialog(
            title = "Delete class?",
            icon = Icons.Filled.Delete,
            destructive = true,
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; onDelete() }
        ) {
            Text(
                "${schedule.label.ifBlank { "This class" }} on ${dayFull(schedule.dayOfWeek)} will be removed.",
                color = Q.Muted
            )
        }
    }
}

@Composable
private fun RailDot(color: Color, live: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "dp")
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (live) {
            Box(
                Modifier.size(10.dp)
                    .graphicsLayer { scaleX = 1f + pulse * 1.6f; scaleY = 1f + pulse * 1.6f; alpha = 0.7f * (1f - pulse) }
                    .clip(CircleShape).background(color)
            )
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(SurfaceDark).border(2.dp, color, CircleShape))
    }
}

@Composable
private fun TinyIcon(icon: ImageVector, tint: Color, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(34.dp)
            .pressScale(source, 0.85f)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Speed-dial FAB. Sits level with the floating nav bar (MainActivity leaves room for it).
// ---------------------------------------------------------------------------------------------

private data class DialAction(val label: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

/** Must match FloatingNavBar's outer padding/height in MainActivity. */
val FabSize = 64.dp

@Composable
private fun SpeedDial(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, actions: List<DialAction>) {
    val scrim by animateFloatAsState(if (expanded) 0.65f else 0f, tween(Motion.MEDIUM), label = "scrim")
    Box(Modifier.fillMaxSize()) {
        if (scrim > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrim))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExpandedChange(false) }
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(end = 20.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions.forEachIndexed { i, a ->
                val delayIn = (actions.size - 1 - i) * 45
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(Motion.SHORT, delayMillis = delayIn)) +
                        slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { it / 2 } +
                        scaleIn(initialScale = 0.8f, animationSpec = tween(Motion.SHORT, delayMillis = delayIn)),
                    exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.85f, animationSpec = tween(120))
                ) {
                    val source = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .pressScale(source, 0.95f)
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceContainerHighDark)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                            .clickable(interactionSource = source, indication = null) { onExpandedChange(false); a.onClick() }
                            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(a.icon, a.color, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(a.label, color = OnSurfaceDark, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val rot by animateFloatAsState(if (expanded) 135f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "fabRot")
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(FabSize)
                    .pressScale(source, 0.9f)
                    .clip(CircleShape)
                    .background(PrimaryLight)
                    .clickable(interactionSource = source, indication = null) { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, if (expanded) "Close" else "Add", tint = OnPrimaryInk, modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rot })
            }
        }
    }
}

fun getDayColor(day: DayOfWeek): Color = when (day) {
    DayOfWeek.MONDAY -> DayMon
    DayOfWeek.TUESDAY -> DayTue
    DayOfWeek.WEDNESDAY -> DayWed
    DayOfWeek.THURSDAY -> DayThu
    DayOfWeek.FRIDAY -> DayFri
    DayOfWeek.SATURDAY -> DaySat
    DayOfWeek.SUNDAY -> DaySun
}
