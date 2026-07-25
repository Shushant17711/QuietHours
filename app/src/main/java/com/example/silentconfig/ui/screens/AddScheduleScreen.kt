package com.example.aquiethours.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.theme.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import com.example.aquiethours.util.ScheduleUtils


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleScreen(
    viewModel: MainViewModel,
    scheduleId: Int?,
    onNavigateBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val schedules by viewModel.schedules.collectAsState()

    val initialDateTime = remember { LocalDateTime.now() }
    val initialEndDateTime = remember { initialDateTime.plusHours(1) }
    
    var selectedDay by remember { mutableStateOf(initialDateTime.dayOfWeek) }
    var startHour by remember { mutableIntStateOf(initialDateTime.hour) }
    var startMinute by remember { mutableIntStateOf(initialDateTime.minute) }
    var endHour by remember { mutableIntStateOf(initialEndDateTime.hour) }
    var endMinute by remember { mutableIntStateOf(initialEndDateTime.minute) }
    var selectedProfileId by remember { mutableStateOf<Int?>(null) }
    var label by remember { mutableStateOf("") }
    
    var isEditing by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (scheduleId != null) "Edit Schedule" else "Add Schedule",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark
                )
            )
        },
        containerColor = SurfaceDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Label field
            Text(
                "Class Name",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text("e.g. Mathematics, Physics Lab") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = OutlineDark,
                    focusedBorderColor = PrimaryDark,
                    cursorColor = PrimaryLight,
                    unfocusedContainerColor = SurfaceContainerDark,
                    focusedContainerColor = SurfaceContainerDark
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Day of Week
            Text(
                "Day of Week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DayOfWeek.values().forEach { day ->
                    val isSelected = selectedDay == day
                    val dayColor = getDayColor(day)
                    val bgColor by animateColorAsState(
                        if (isSelected) dayColor else SurfaceContainerDark,
                        animationSpec = tween(200)
                    )
                    val textColor by animateColorAsState(
                        if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200)
                    )

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedDay = day },
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor,
                        border = if (!isSelected) {
                            ButtonDefaults.outlinedButtonBorder
                        } else null
                    ) {
                        Text(
                            text = day.name.take(3),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = textColor,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Time pickers
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Start time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Start Time",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TimePickerWidget(
                        hour = startHour,
                        minute = startMinute,
                        onHourChange = { startHour = it.coerceIn(0, 23) },
                        onMinuteChange = { startMinute = it.coerceIn(0, 59) },
                        accentColor = PrimaryDark
                    )
                }

                // End time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "End Time",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TimePickerWidget(
                        hour = endHour,
                        minute = endMinute,
                        onHourChange = { endHour = it.coerceIn(0, 23) },
                        onMinuteChange = { endMinute = it.coerceIn(0, 59) },
                        accentColor = SecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Profile selector
            Text(
                "Select Profile",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (profiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = SurfaceContainerDark
                    )
                ) {
                    Text(
                        "No profiles yet. Create one in Profiles first.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { profile ->
                        val isSelected = selectedProfileId == profile.id
                        val ringerEmoji = when (profile.ringerMode) {
                            0 -> "🔇"
                            1 -> "📳"
                            else -> "🔔"
                        }
                        val ringerLabel = when (profile.ringerMode) {
                            0 -> "Silent"
                            1 -> "Vibrate"
                            else -> "Normal"
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedProfileId = profile.id }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            brush = Brush.linearGradient(
                                                colors = listOf(PrimaryDark, GradientEnd)
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                    } else Modifier
                                ),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    PrimaryContainer.copy(alpha = 0.4f)
                                else SurfaceContainerDark
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedProfileId = profile.id },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = PrimaryDark,
                                        unselectedColor = OutlineDark
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "$ringerEmoji $ringerLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save button
            Button(
                onClick = {
                    if (selectedProfileId != null) {
                        val scheduleToSave = Schedule(
                            id = scheduleId ?: 0,
                            dayOfWeek = selectedDay,
                            startHour = startHour,
                            startMinute = startMinute,
                            endHour = endHour,
                            endMinute = endMinute,
                            profileId = selectedProfileId!!,
                            label = label.trim()
                        )
                        
                        if (ScheduleUtils.hasOverlap(scheduleToSave, schedules, scheduleId)) {
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
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                enabled = selectedProfileId != null,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    disabledContainerColor = SurfaceContainerHighDark
                )
            ) {
                Text(
                    "Save Schedule",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("Overlap Detected") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceContainerDark)
            .padding(horizontal = 8.dp, vertical = 4.dp)
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

        // Colon separator
        Text(
            ":",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = accentColor,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        // Minute wheel
        ScrollWheelPicker(
            items = (0..59).toList(),
            selectedItem = minute,
            onItemSelected = { onMinuteChange(it) },
            accentColor = accentColor,
            format = "%02d"
        )

        Spacer(modifier = Modifier.width(8.dp))

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
    val itemHeightDp = 48.dp
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

    // Track when scrolling stops and update the selected value
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            // Find the centered item
            val firstVisible = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset

            // Calculate which item is closest to center
            val centeredIndex = if (firstVisibleOffset > itemHeightPx / 2) {
                firstVisible + 2
            } else {
                firstVisible + 1
            }

            val actualIndex = centeredIndex % items.size
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

    Box(
        modifier = Modifier
            .width(if (isAmPm) 56.dp else 64.dp)
            .height(totalHeightDp),
        contentAlignment = Alignment.Center
    ) {
        // Selection highlight — the bordered box in the center
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeightDp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.5.dp,
                    color = accentColor.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .background(accentColor.copy(alpha = 0.08f))
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeightDp),
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(totalInfiniteItems) { index ->
                val actualIndex = index % items.size
                val itemValue = items[actualIndex]

                // Determine if this item is the centered (selected) one
                val firstVisible = listState.firstVisibleItemIndex
                val firstVisibleOffset = listState.firstVisibleItemScrollOffset
                val centeredIndex = if (firstVisibleOffset > itemHeightPx / 2) {
                    firstVisible + 2
                } else {
                    firstVisible + 1
                }
                val isSelected = index == centeredIndex
                val distanceFromCenter = Math.abs(index - centeredIndex)

                val alpha = when (distanceFromCenter) {
                    0 -> 1f
                    1 -> 0.4f
                    else -> 0.15f
                }

                val textSize = when (distanceFromCenter) {
                    0 -> 28.sp
                    1 -> 20.sp
                    else -> 16.sp
                }

                val displayText = if (isAmPm) {
                    if (itemValue == 0) "AM" else "PM"
                } else {
                    String.format(format, itemValue)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeightDp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = textSize
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Top and bottom fade gradients
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeightDp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceContainerDark,
                            SurfaceContainerDark.copy(alpha = 0f)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeightDp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceContainerDark.copy(alpha = 0f),
                            SurfaceContainerDark
                        )
                    )
                )
        )
    }
}


