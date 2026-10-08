package com.example.aquiethours.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aquiethours.data.Profile
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.ui.QuietStore
import com.example.aquiethours.ui.icons.*
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.ScheduleUtils
import kotlinx.coroutines.flow.drop
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs


@Composable
fun AddScheduleScreen(
    viewModel: QuietStore,
    scheduleId: Int?,
    onNavigateBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val schedules by viewModel.schedules.collectAsState()

    val initialDateTime = remember { LocalDateTime.now() }
    val initialEndDateTime = remember { initialDateTime.plusHours(1) }

    // Fill the form from the schedule on the very first frame when it's already loaded
    // (it always is when coming from Home). Otherwise the time tiles visibly animated from
    // "now" to the class's real time. The LaunchedEffect below still covers late loads.
    val prefill = remember { scheduleId?.let { id -> viewModel.schedules.value.find { it.id == id } } }
    var selectedDay by rememberSaveable { mutableStateOf(prefill?.dayOfWeek ?: initialDateTime.dayOfWeek) }
    var startHour by rememberSaveable { mutableIntStateOf(prefill?.startHour ?: initialDateTime.hour) }
    var startMinute by rememberSaveable { mutableIntStateOf(prefill?.startMinute ?: initialDateTime.minute) }
    var endHour by rememberSaveable { mutableIntStateOf(prefill?.endHour ?: initialEndDateTime.hour) }
    var endMinute by rememberSaveable { mutableIntStateOf(prefill?.endMinute ?: initialEndDateTime.minute) }
    var selectedProfileId by rememberSaveable { mutableStateOf(prefill?.profileId) }
    var label by rememberSaveable { mutableStateOf(prefill?.label ?: "") }

    var isEditing by rememberSaveable { mutableStateOf(prefill != null) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showAddProfileDialog by remember { mutableStateOf(false) }

    // UI-only: which end of the window the wheel is currently editing.
    var editingEnd by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(scheduleId, schedules) {
        if (scheduleId != null && !isEditing) {
            val existing = schedules.find { it.id == scheduleId }
            if (existing != null) {
                selectedDay = existing.dayOfWeek
                startHour = existing.startHour
                startMinute = existing.startMinute
                endHour = existing.endHour
                endMinute = existing.endMinute
                selectedProfileId = existing.profileId
                label = existing.label
                isEditing = true
            }
        }
    }

    val save: () -> Unit = {
        if (selectedProfileId != null) {
            // Editing must keep isActive/skipNext. Building a fresh Schedule
            // reset them to their defaults, so editing a switched-off schedule
            // silently switched it back on and dropped a pending skip.
            val existing = scheduleId?.let { id -> schedules.find { it.id == id } }
            val scheduleToSave = Schedule(
                id = scheduleId ?: 0,
                isActive = existing?.isActive ?: true,
                skipNext = existing?.skipNext ?: false,
                dayOfWeek = selectedDay,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                profileId = selectedProfileId!!,
                label = label.trim()
            )

            if (startHour == endHour && startMinute == endMinute) {
                // getIntervals() treats a 0-length window as +24h, so this
                // used to save a schedule that muted the phone all day.
                errorMessage = "Start and end time are the same. Pick an end time after the start."
                showErrorDialog = true
            } else if (ScheduleUtils.hasOverlap(scheduleToSave, schedules, scheduleId)) {
                errorMessage = "This schedule overlaps with an existing schedule. Please choose a different time."
                showErrorDialog = true
            } else {
                if (scheduleId != null) {
                    viewModel.updateSchedule(scheduleToSave)
                } else {
                    viewModel.addSchedule(scheduleToSave)
                }
                onNavigateBack()
            }
        }
    }

    // Single brand accent. Tinting the whole screen with the day colour (amber on Wednesday)
    // made it look like a different app every day.
    val accent = PrimaryLight

    val startTotal = startHour * 60 + startMinute
    val endTotal = endHour * 60 + endMinute
    val durationMinutes = ((endTotal - startTotal) % 1440 + 1440) % 1440
    val crossesMidnight = endTotal < startTotal
    val sameTime = durationMinutes == 0

    AuroraBackground(accent = PrimaryLight) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                QuietHeader(
                    title = if (scheduleId != null) "Edit class" else "New class",
                    subtitle = "Every ${selectedDay.fullName()}" +
                        if (sameTime) "" else " · ${formatDuration(durationMinutes)}",
                    onBack = onNavigateBack
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Q.Gutter),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Spacer(Modifier.height(2.dp))

                    // ---- Class name ----
                    QuietCard(Modifier.fillMaxWidth().enterAnimation(0)) {
                        SectionLabel("Class name")
                        QuietInputField(
                            value = label,
                            onValueChange = { label = it },
                            placeholder = "e.g. Mathematics, Physics Lab",
                            accent = accent,
                            leading = Icons.Outlined.Schedule
                        )
                    }

                    // ---- Day ----
                    QuietCard(Modifier.fillMaxWidth().enterAnimation(1)) {
                        SectionLabel("Day", trailing = {
                            AnimatedContent(
                                targetState = selectedDay,
                                transitionSpec = {
                                    val down = targetState.value > initialState.value
                                    (slideInVertically { if (down) it else -it } + fadeIn()) togetherWith
                                        (slideOutVertically { if (down) -it else it } + fadeOut()) using
                                        SizeTransform(clip = false)
                                },
                                label = "dayName"
                            ) { d ->
                                Text(
                                    d.fullName(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PrimaryLight,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        })
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DayOfWeek.values().forEach { day ->
                                DayBubble(
                                    day = day,
                                    selected = day == selectedDay,
                                    onClick = { selectedDay = day },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // ---- Time ----
                    QuietCard(Modifier.fillMaxWidth().enterAnimation(2)) {
                        SectionLabel("Time")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TimeTile(
                                caption = "Starts",
                                hour = startHour,
                                minute = startMinute,
                                active = !editingEnd,
                                accent = accent,
                                onClick = { editingEnd = false },
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Q.Muted,
                                modifier = Modifier.padding(horizontal = 8.dp).size(18.dp)
                            )
                            TimeTile(
                                caption = "Ends",
                                hour = endHour,
                                minute = endMinute,
                                active = editingEnd,
                                accent = accent,
                                onClick = { editingEnd = true },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedContent(
                                targetState = sameTime,
                                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() },
                                label = "durationChip"
                            ) { same ->
                                if (same) {
                                    Chip("Same start & end", ErrorDark, icon = Icons.Outlined.Warning)
                                } else {
                                    Chip(formatDuration(durationMinutes), accent, icon = Icons.Outlined.Schedule)
                                }
                            }
                            AnimatedVisibility(
                                visible = crossesMidnight,
                                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                                exit = fadeOut() + scaleOut(targetScale = 0.8f)
                            ) {
                                Chip("Overnight", SecondaryLight)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        AnimatedContent(
                            targetState = editingEnd,
                            transitionSpec = {
                                val forward = targetState
                                (slideInHorizontally(tween(Motion.MEDIUM)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(Motion.MEDIUM))) togetherWith
                                    (slideOutHorizontally(tween(Motion.SHORT)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(Motion.SHORT)))
                            },
                            label = "wheelSwap",
                            modifier = Modifier.fillMaxWidth()
                        ) { end ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (!end) {
                                    TimePickerWidget(
                                        hour = startHour,
                                        minute = startMinute,
                                        onHourChange = { startHour = it.coerceIn(0, 23) },
                                        onMinuteChange = { startMinute = it.coerceIn(0, 59) },
                                        accentColor = accent
                                    )
                                } else {
                                    TimePickerWidget(
                                        hour = endHour,
                                        minute = endMinute,
                                        onHourChange = { endHour = it.coerceIn(0, 23) },
                                        onMinuteChange = { endMinute = it.coerceIn(0, 59) },
                                        accentColor = accent
                                    )
                                }
                            }
                        }
                    }

                    // ---- Profile ----
                    QuietCard(Modifier.fillMaxWidth().enterAnimation(3)) {
                        SectionLabel("Sound profile")
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (profiles.isEmpty()) {
                                Text(
                                    "No profiles yet. Create one to decide how your phone behaves in this class.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Q.Muted
                                )
                            }
                            profiles.forEach { profile ->
                                ProfileOption(
                                    profile = profile,
                                    selected = selectedProfileId == profile.id,
                                    onClick = { selectedProfileId = profile.id }
                                )
                            }
                            NewProfileOption(accent = accent, onClick = { showAddProfileDialog = true })
                        }
                    }

                    // Room for the sticky save bar.
                    Spacer(Modifier.navigationBarsPadding().height(132.dp))
                }
            }

            // ---- Sticky save bar ----
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to SurfaceDark.copy(alpha = 0.85f),
                            1f to SurfaceDark
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = Q.Gutter, end = Q.Gutter, top = 28.dp, bottom = 16.dp)
            ) {
                AnimatedVisibility(
                    visible = selectedProfileId == null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        "Choose a sound profile to continue",
                        style = MaterialTheme.typography.labelMedium,
                        color = Q.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )
                }
                PrimaryButton(
                    text = if (scheduleId != null) "Save changes" else "Save class",
                    onClick = save,
                    enabled = selectedProfileId != null,
                    icon = Icons.Outlined.Check,
                    colors = listOf(accent, lerp(accent, PrimaryLight, 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showAddProfileDialog) {
        AddProfileDialog(
            onDismiss = { showAddProfileDialog = false },
            onSave = {
                viewModel.addProfile(it) { id -> selectedProfileId = id }
                showAddProfileDialog = false
            }
        )
    }

    if (showErrorDialog) {
        QuietDialog(
            title = "Can't save class",
            onDismiss = { showErrorDialog = false },
            confirmText = "OK",
            onConfirm = { showErrorDialog = false },
            dismissText = null,
            icon = Icons.Outlined.Warning
        ) {
            Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = Q.Muted)
        }
    }
}

private fun DayOfWeek.fullName(): String = getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun displayHourOf(hour: Int): Int = when {
    hour == 0 -> 12
    hour > 12 -> hour - 12
    else -> hour
}

// ---------------------------------------------------------------------------------------------
// Day bubble
// ---------------------------------------------------------------------------------------------

@Composable
private fun DayBubble(
    day: DayOfWeek,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = PrimaryLight
    val source = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dayScale"
    )
    val fill by animateColorAsState(
        if (selected) c else Color.White.copy(alpha = 0.05f),
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "dayFill"
    )
    val ink by animateColorAsState(if (selected) OnPrimaryInk else OnSurfaceVariantDark, tween(Motion.SHORT), label = "dayInk")
    val ring by animateColorAsState(if (selected) c.copy(alpha = 0f) else Color.White.copy(alpha = 0.08f), tween(Motion.SHORT), label = "dayRing")
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(Motion.MEDIUM), label = "dayGlow")

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .pressScale(source, 0.9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (glow > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(c.copy(alpha = 0.30f * glow), Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.85f
                        ),
                        radius = size.minDimension * 0.85f
                    )
                }
            }
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, ring, CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.name.take(1),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = ink
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Time summary tile
// ---------------------------------------------------------------------------------------------

@Composable
private fun TimeTile(
    caption: String,
    hour: Int,
    minute: Int,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val bg by animateColorAsState(if (active) accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f), tween(Motion.MEDIUM), label = "tileBg")
    val border by animateColorAsState(if (active) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f), tween(Motion.MEDIUM), label = "tileBorder")
    val captionColor by animateColorAsState(if (active) accent else Q.Muted, tween(Motion.MEDIUM), label = "tileCap")
    val timeText = String.format("%02d:%02d", displayHourOf(hour), minute)
    val amPm = if (hour >= 12) "PM" else "AM"

    Column(
        modifier = modifier
            .pressScale(source)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            caption.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(
                targetState = timeText,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "timeText"
            ) { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurfaceDark,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = amPm,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "amPm"
            ) { a ->
                Text(
                    a,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Q.Muted,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Profile options
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProfileOption(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val v = ringerVisual(profile.ringerMode)
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(if (selected) v.color.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f), tween(Motion.MEDIUM), label = "optBorder")
    val bg by animateColorAsState(if (selected) v.color.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f), tween(Motion.MEDIUM), label = "optBg")
    val borderWidth by animateDpAsState(if (selected) 1.5.dp else 1.dp, tween(Motion.SHORT), label = "optBw")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(v.icon, v.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnSurfaceDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (profile.ringerMode == 2) "${v.label} · ${profile.ringVolume}% ring" else "${v.label} · media ${profile.mediaVolume}%",
                style = MaterialTheme.typography.bodySmall,
                color = Q.Muted
            )
        }
        Spacer(Modifier.width(8.dp))
        SelectionDot(selected, v.color)
    }
}

@Composable
private fun SelectionDot(selected: Boolean, color: Color) {
    val ring by animateColorAsState(if (selected) color else Q.Muted.copy(alpha = 0.4f), tween(Motion.SHORT), label = "dotRing")
    val inner by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dotInner"
    )
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer { scaleX = inner; scaleY = inner; alpha = inner.coerceIn(0f, 1f) }
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Check, null, tint = OnPrimaryInk, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun NewProfileOption(accent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
            .drawBehind {
                val r = 18.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.45f),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = dash)
                )
            }
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Filled.Add, accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("New profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurfaceDark)
            Text("Create a custom sound profile", style = MaterialTheme.typography.bodySmall, color = Q.Muted)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Wheel time picker
// ---------------------------------------------------------------------------------------------

@Composable
private fun TimePickerWidget(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    accentColor: Color
) {
    val isPm = hour >= 12
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }

    fun to24Hour(disp: Int, pm: Boolean): Int {
        return if (pm) {
            if (disp == 12) 12 else disp + 12
        } else {
            if (disp == 12) 0 else disp
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Shared selection band behind all three wheels.
        Box(
            Modifier
                .matchParentSize()
                .wrapContentHeight(Alignment.CenterVertically)
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(accentColor.copy(alpha = 0.20f), accentColor.copy(alpha = 0.08f), accentColor.copy(alpha = 0.20f))
                    )
                )
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Hour wheel
            ScrollWheelPicker(
                items = (1..12).toList(),
                selectedItem = displayHour,
                onItemSelected = { newDisplayHour ->
                    onHourChange(to24Hour(newDisplayHour, isPm))
                },
                accentColor = accentColor,
                format = "%02d"
            )

            Text(
                ":",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                color = accentColor,
                modifier = Modifier.padding(horizontal = 2.dp).padding(bottom = 4.dp)
            )

            // Minute wheel
            ScrollWheelPicker(
                items = (0..59).toList(),
                selectedItem = minute,
                onItemSelected = { onMinuteChange(it) },
                accentColor = accentColor,
                format = "%02d"
            )

            Spacer(modifier = Modifier.width(6.dp))

            // AM/PM wheel
            ScrollWheelPicker(
                items = listOf(0, 1), // 0 = AM, 1 = PM
                selectedItem = if (isPm) 1 else 0,
                onItemSelected = { amPmValue ->
                    val newIsPm = amPmValue == 1
                    onHourChange(to24Hour(displayHour, newIsPm))
                },
                accentColor = accentColor,
                format = "AMPM",
                isAmPm = true
            )
        }
    }
}

private val WheelItemHeight = 48.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollWheelPicker(
    items: List<Int>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit,
    accentColor: Color,
    format: String,
    isAmPm: Boolean = false
) {
    val itemHeightDp = WheelItemHeight
    val visibleItems = 3 // Show 3 items: prev, current, next
    val totalHeightDp = itemHeightDp * visibleItems

    // Use a very large list to simulate infinite scrolling
    val infiniteMultiplier = 1000
    val totalInfiniteItems = items.size * infiniteMultiplier
    val initialIndex = items.indexOf(selectedItem).let { idx ->
        if (idx == -1) 0 else idx
    }
    // Start in the middle of the infinite list
    val startIndex = (infiniteMultiplier / 2) * items.size + initialIndex

    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeightDp.toPx() }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex - 1)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Index of the item currently sitting in the selection band.
    val centeredIndex by remember {
        derivedStateOf {
            if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) {
                listState.firstVisibleItemIndex + 2
            } else {
                listState.firstVisibleItemIndex + 1
            }
        }
    }

    // A light tick each time a new value passes through the band.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        snapshotFlow { centeredIndex }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Track when scrolling stops and update the selected value
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            // Find the centered item
            val firstVisible = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset

            // Calculate which item is closest to center
            val centered = if (firstVisibleOffset > itemHeightPx / 2) {
                firstVisible + 2
            } else {
                firstVisible + 1
            }

            val actualIndex = centered % items.size
            val newValue = items[actualIndex]
            if (newValue != selectedItem) {
                onItemSelected(newValue)
            }
        }
    }

    // Scroll to the correct position when selectedItem changes externally
    LaunchedEffect(selectedItem) {
        val currentCenteredIndex = listState.firstVisibleItemIndex + 1
        val currentActualIndex = currentCenteredIndex % items.size
        val currentValue = items[currentActualIndex]
        if (currentValue != selectedItem) {
            val targetIndex = items.indexOf(selectedItem)
            if (targetIndex != -1) {
                val newStartIndex = (infiniteMultiplier / 2) * items.size + targetIndex
                listState.scrollToItem(newStartIndex - 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(if (isAmPm) 58.dp else 66.dp)
            .height(totalHeightDp)
            // Soft fade at the top and bottom edges, independent of what's behind the wheel.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black,
                        0.7f to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            },
        flingBehavior = flingBehavior,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(totalInfiniteItems) { index ->
            val actualIndex = index % items.size
            val itemValue = items[actualIndex]
            val isSelected = index == centeredIndex
            val textColor by animateColorAsState(
                if (isSelected) OnSurfaceDark else Q.Muted,
                tween(Motion.SHORT),
                label = "wheelInk"
            )

            val displayText = if (isAmPm) {
                if (itemValue == 0) "AM" else "PM"
            } else {
                String.format(format, itemValue)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeightDp)
                    .graphicsLayer {
                        // Continuous distance (in items) from the centre band — drives the drum effect
                        // in the draw phase, so scrolling doesn't recompose every row.
                        val d = (index - listState.firstVisibleItemIndex - 1).toFloat() -
                            listState.firstVisibleItemScrollOffset / itemHeightPx
                        val ad = abs(d).coerceAtMost(1.6f)
                        val s = 1f - ad * 0.2f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - ad * 0.55f).coerceIn(0.1f, 1f)
                        rotationX = (-d * 30f).coerceIn(-60f, 60f)
                        cameraDistance = 10f * this.density
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = if (isAmPm) 22.sp else 28.sp
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
