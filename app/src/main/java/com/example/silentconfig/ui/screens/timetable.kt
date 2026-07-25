package com.example.aquiethours.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowForward
import com.example.aquiethours.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.theme.*
import java.time.DayOfWeek
import com.example.aquiethours.util.format12Hour
import com.example.aquiethours.util.ShareHelper
import com.example.aquiethours.util.ParsedScheduleEntry
import android.content.Intent
import com.example.aquiethours.util.ScheduleUtils
import com.airbnb.lottie.compose.*
import com.example.aquiethours.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToAddSchedule: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onEditSchedule: (Int) -> Unit = {}
) {
    val schedules by viewModel.schedules.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var importCode by remember { mutableStateOf("") }
    var parsedImportEntries by remember { mutableStateOf<List<ParsedScheduleEntry>?>(null) }
    var selectedProfileId by remember { mutableStateOf<Int?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    
    val isGlobalEnabled by viewModel.isGlobalEnabled.collectAsState()

    // Group schedules by day
    val groupedSchedules = schedules.groupBy { it.dayOfWeek }
        .toSortedMap(compareBy { it.value })

    val currentDayOfWeek = java.time.LocalDate.now().dayOfWeek
    var expandedDays by remember { 
        mutableStateOf(mapOf(currentDayOfWeek to true)) 
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            "My Timetable",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            "${schedules.size} schedule${if (schedules.size != 1) "s" else ""} active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Global App Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = if (isGlobalEnabled) "On" else "Off",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isGlobalEnabled) PrimaryLight else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = isGlobalEnabled,
                            onCheckedChange = { viewModel.toggleGlobalState(it) },
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    if (schedules.isNotEmpty()) {
                        IconButton(onClick = {
                            val encoded = ShareHelper.encodeSchedules(schedules)
                            val sendIntent: Intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, encoded)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, null)
                            context.startActivity(shareIntent)
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToProfiles) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = "Profiles",
                            tint = PrimaryLight
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SurfaceDark,
                    scrolledContainerColor = SurfaceContainerDark
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Import from code FAB
                SmallFloatingActionButton(
                    onClick = { showImportDialog = true },
                    containerColor = SecondaryContainer,
                    contentColor = SecondaryLight
                ) {
                    Icon(Icons.Outlined.Code, contentDescription = "Import from Code")
                }
                // Import from photo FAB
                SmallFloatingActionButton(
                    onClick = onNavigateToImport,
                    containerColor = SecondaryContainer,
                    contentColor = SecondaryLight
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Import Timetable")
                }
                // Add schedule FAB
                ExtendedFloatingActionButton(
                    onClick = onNavigateToAddSchedule,
                    containerColor = PrimaryDark,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        containerColor = SurfaceDark
    ) { paddingValues ->
        if (schedules.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    val lottieComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.empty_lottie))
                    val lottieProgress by animateLottieCompositionAsState(
                        composition = lottieComposition,
                        iterations = LottieConstants.IterateForever
                    )
                    
                    LottieAnimation(
                        composition = lottieComposition,
                        progress = { lottieProgress },
                        modifier = Modifier.size(150.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "No Schedules Yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Add your class timetable to automatically\nmanage your phone's volume",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = onNavigateToImport,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SecondaryLight
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(SecondaryDark, PrimaryDark)
                            )
                        )
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import from Photo")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PrimaryLight
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(PrimaryDark, GradientEnd)
                            )
                        )
                    ) {
                        Icon(
                            Icons.Outlined.Code,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import from Code")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp)
            ) {
                groupedSchedules.forEach { (day, daySchedules) ->
                    val isExpanded = expandedDays[day] == true
                    // Day header
                    item(key = "header_$day") {
                        DayHeader(
                            day = day, 
                            count = daySchedules.size,
                            isExpanded = isExpanded,
                            onClick = {
                                expandedDays = expandedDays.toMutableMap().apply {
                                    put(day, !isExpanded)
                                }
                            }
                        )
                    }
                    
                    if (isExpanded) {
                        // Schedule cards for this day
                        items(daySchedules, key = { "schedule_${it.id}" }) { schedule ->
                            val profile = profiles.find { it.id == schedule.profileId }
                            ScheduleCard(
                                schedule = schedule,
                                profileName = profile?.name ?: "Unknown",
                                ringerMode = profile?.ringerMode ?: 2,
                                onDelete = { viewModel.deleteSchedule(schedule) },
                                onEdit = { onEditSchedule(schedule.id) },
                                onToggle = { isActive -> viewModel.toggleScheduleStatus(schedule, isActive) },
                                onSkipNext = { skip -> viewModel.toggleSkipNext(schedule, skip) }
                            )
                        }
                    }
                    // Spacer between day groups
                    item(key = "spacer_$day") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importCode = ""
                importError = null
            },
            title = { Text("Import from Code") },
            text = {
                val clipboardManager = LocalClipboardManager.current
                val context = LocalContext.current
                val promptText = "Extract the timetable from the image and format it exactly as a single line string like this: day;startH;startM;endH;endM;label|day;startH;startM;endH;endM;label\n\nDays should be numbers from 1 (Monday) to 7 (Sunday). Hours should be 24-hour format. Do not add any markdown, markdown code blocks, or extra text. Output only the delimited string."

                Column {
                    Text("Don't have an OpenRouter key? Use ChatGPT or Gemini! Copy the prompt below, paste it with your timetable image into the AI, and paste the result here.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(promptText))
                            Toast.makeText(context, "AI Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy AI Prompt")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Paste the shared code below:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importCode,
                        onValueChange = { importCode = it; importError = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    if (importError != null) {
                        Text(
                            text = importError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val decoded = ShareHelper.decodeSchedules(importCode.trim())
                    if (decoded.isNullOrEmpty()) {
                        importError = "Invalid code. Please check and try again."
                    } else {
                        parsedImportEntries = decoded
                        showImportDialog = false
                        importCode = ""
                    }
                }) {
                    Text("Next")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; importCode = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (parsedImportEntries != null) {
        AlertDialog(
            onDismissRequest = {
                parsedImportEntries = null
                selectedProfileId = null
            },
            title = { Text("Assign Profile") },
            text = {
                Column {
                    Text("Found ${parsedImportEntries!!.size} schedules. Select a profile to assign them to:")
                    Spacer(modifier = Modifier.height(16.dp))
                    if (profiles.isEmpty()) {
                        Text("No profiles found. Create one first.", color = MaterialTheme.colorScheme.error)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(profiles) { profile ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedProfileId = profile.id }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedProfileId == profile.id,
                                        onClick = { selectedProfileId = profile.id }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(profile.name)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedProfileId?.let { profileId ->
                            val schedulesToSave = parsedImportEntries!!.map { entry ->
                                Schedule(
                                    dayOfWeek = entry.day,
                                    startHour = entry.startHour,
                                    startMinute = entry.startMinute,
                                    endHour = entry.endHour,
                                    endMinute = entry.endMinute,
                                    profileId = profileId,
                                    label = entry.label
                                )
                            }
                            
                            var hasAnyOverlap = false
                            val currentSchedules = schedules.toMutableList()
                            for (newSchedule in schedulesToSave) {
                                if (ScheduleUtils.hasOverlap(newSchedule, currentSchedules)) {
                                    hasAnyOverlap = true
                                    break
                                }
                                currentSchedules.add(newSchedule)
                            }
                            
                            if (hasAnyOverlap) {
                                importError = "Import failed: One or more imported schedules overlap with existing ones, or with each other. Please check your timetable."
                                // Hide the parsing dialog and return to the main import dialog to show the error
                                parsedImportEntries = null
                                selectedProfileId = null
                            } else {
                                viewModel.addSchedules(schedulesToSave)
                                parsedImportEntries = null
                                selectedProfileId = null
                                showImportDialog = false
                            }
                        }
                    },
                    enabled = selectedProfileId != null
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    parsedImportEntries = null
                    selectedProfileId = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DayHeader(day: DayOfWeek, count: Int, isExpanded: Boolean, onClick: () -> Unit) {
    val dayColor = getDayColor(day)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dayColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = day.name.lowercase()
                .replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            color = dayColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count class${if (count != 1) "es" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduleCard(
    schedule: Schedule,
    profileName: String,
    ringerMode: Int,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkipNext: (Boolean) -> Unit
) {
    val dayColor = getDayColor(schedule.dayOfWeek)
    val ringerEmoji = when (ringerMode) {
        0 -> "🔇"
        1 -> "📳"
        else -> "🔔"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onEdit
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceContainerDark
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(IntrinsicSize.Max)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(dayColor, dayColor.copy(alpha = 0.4f))
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (schedule.label.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = schedule.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (schedule.skipNext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                            if (schedule.skipNext) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = TertiaryDark.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "Skipping Next",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TertiaryLight,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    } else if (schedule.skipNext) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = TertiaryDark.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Skipping Next",
                                style = MaterialTheme.typography.labelSmall,
                                color = TertiaryLight,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (schedule.skipNext) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${format12Hour(schedule.startHour, schedule.startMinute)} – ${format12Hour(schedule.endHour, schedule.endMinute)}",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            ),
                            color = if (schedule.skipNext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // Profile chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (schedule.skipNext) PrimaryContainer.copy(alpha = 0.3f) else PrimaryContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ringerEmoji,
                                fontSize = 12.sp,
                                modifier = if (schedule.skipNext) Modifier.alpha(0.5f) else Modifier
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = profileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (schedule.skipNext) PrimaryLight.copy(alpha = 0.5f) else PrimaryLight
                            )
                        }
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Switch(
                        checked = schedule.isActive,
                        onCheckedChange = onToggle,
                        modifier = Modifier.scale(0.8f)
                    )
                    Row {
                        if (schedule.isActive) {
                            IconButton(onClick = { onSkipNext(!schedule.skipNext) }) {
                                Icon(
                                    if (schedule.skipNext) Icons.Default.Refresh else Icons.Default.ArrowForward,
                                    contentDescription = if (schedule.skipNext) "Restore" else "Skip Next",
                                    tint = if (schedule.skipNext) TertiaryLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = ErrorDark.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun getDayColor(day: DayOfWeek): Color {
    return when (day) {
        DayOfWeek.MONDAY -> DayMon
        DayOfWeek.TUESDAY -> DayTue
        DayOfWeek.WEDNESDAY -> DayWed
        DayOfWeek.THURSDAY -> DayThu
        DayOfWeek.FRIDAY -> DayFri
        DayOfWeek.SATURDAY -> DaySat
        DayOfWeek.SUNDAY -> DaySun
    }
}
