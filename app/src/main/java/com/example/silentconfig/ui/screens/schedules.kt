package com.example.aquiethours.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aquiethours.data.Profile
import com.example.aquiethours.ui.QuietStore
import com.example.aquiethours.ui.icons.*
import com.example.aquiethours.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    viewModel: QuietStore,
    onNavigateBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var profileBeingEdited by remember { mutableStateOf<Profile?>(null) }
    var profilePendingDelete by remember { mutableStateOf<Profile?>(null) }
    val schedules by viewModel.schedules.collectAsState()

    AuroraBackground {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                QuietHeader(
                    title = "Sound profiles",
                    subtitle = if (profiles.isEmpty()) "How your phone behaves in class"
                    else "${profiles.size} profile${if (profiles.size != 1) "s" else ""}"
                )

                if (profiles.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.VolumeUp,
                        title = "No sound profiles yet",
                        body = "Create a sound profile to assign to your class schedules — silent, vibrate, or a gentle ring.",
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = Q.BottomBarSpace)
                    ) {
                        PrimaryButton(
                            text = "Create profile",
                            onClick = { showDialog = true },
                            icon = Icons.Filled.Add
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = Q.Gutter,
                            end = Q.Gutter,
                            top = 4.dp,
                            bottom = Q.BottomBarSpace + 84.dp
                        )
                    ) {
                        itemsIndexed(profiles, key = { _, it -> it.id }) { index, profile ->
                            Box(modifier = Modifier.animateItemPlacement().enterAnimation(index)) {
                                ProfileCard(
                                    profile = profile,
                                    usedBy = schedules.count { it.profileId == profile.id },
                                    index = index,
                                    onEdit = { profileBeingEdited = profile },
                                    onDelete = { profilePendingDelete = profile }
                                )
                            }
                        }
                    }
                }
            }

            // "New profile" pill floating above the bottom navigation bar.
            PopIn(
                visible = profiles.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Q.Gutter, bottom = Q.BottomBarSpace)
            ) {
                PrimaryButton(
                    text = "New profile",
                    onClick = { showDialog = true },
                    icon = Icons.Filled.Add
                )
            }
        }
    }

    profilePendingDelete?.let { doomed ->
        val affected = schedules.count { it.profileId == doomed.id }
        QuietDialog(
            title = "Delete \"${doomed.name}\"?",
            onDismiss = { profilePendingDelete = null },
            confirmText = "Delete",
            onConfirm = { viewModel.deleteProfile(doomed); profilePendingDelete = null },
            destructive = true,
            icon = Icons.Outlined.Delete
        ) {
            Text(
                if (affected > 0) "This also deletes the $affected schedule${if (affected != 1) "s" else ""} that use this profile."
                else "No schedules use this profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = Q.Muted
            )
        }
    }

    if (showDialog || profileBeingEdited != null) {
        AddProfileDialog(
            initialProfile = profileBeingEdited,
            onDismiss = {
                showDialog = false
                profileBeingEdited = null
            },
            onSave = { updatedProfile ->
                if (profileBeingEdited != null) {
                    viewModel.updateProfile(updatedProfile.copy(id = profileBeingEdited!!.id))
                } else {
                    viewModel.addProfile(updatedProfile)
                }
                showDialog = false
                profileBeingEdited = null
            }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    usedBy: Int,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val v = ringerVisual(profile.ringerMode)
    QuietCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(v.icon, v.color, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(v.label, v.color, icon = v.icon)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (usedBy == 0) "Unused" else "$usedBy class${if (usedBy != 1) "es" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Q.Muted
                    )
                }
            }
            CircleIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "Delete",
                onClick = onDelete,
                // Quiet until needed: a red circle on every card read as an alarm.
                tint = OnSurfaceVariantDark,
                container = Color.Transparent
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val ringOn = profile.ringerMode == 2
            VolumeMeter(
                label = "Ring",
                value = if (ringOn) profile.ringVolume else 0,
                color = PrimaryLight,
                valueText = if (ringOn) "${profile.ringVolume}%" else "Off",
                delayMs = index.coerceAtMost(6) * 60 + 150,
                modifier = Modifier.weight(1f)
            )
            VolumeMeter(
                label = "Media",
                value = profile.mediaVolume,
                color = SecondaryLight,
                valueText = "${profile.mediaVolume}%",
                delayMs = index.coerceAtMost(6) * 60 + 230,
                modifier = Modifier.weight(1f)
            )
            VolumeMeter(
                label = "Alarm",
                value = profile.silentVolume,
                color = TertiaryLight,
                valueText = "${profile.silentVolume}%",
                delayMs = index.coerceAtMost(6) * 60 + 310,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Compact labelled meter; the fill sweeps in the first time it's shown, then follows value changes. */
@Composable
private fun VolumeMeter(
    label: String,
    value: Int,
    color: Color,
    valueText: String,
    delayMs: Int,
    modifier: Modifier = Modifier
) {
    val target = (value / 100f).coerceIn(0f, 1f)
    var played by rememberSaveable { mutableStateOf(false) }
    val fill = remember { Animatable(if (played) target else 0f) }
    LaunchedEffect(target) {
        if (!played) {
            delay(delayMs.toLong())
            played = true
            fill.animateTo(target, tween(900, easing = Motion.Emphasized))
        } else {
            fill.animateTo(target, tween(Motion.MEDIUM, easing = Motion.Emphasized))
        }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Q.Muted, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (value > 0) color else Q.Muted
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.value)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color)))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileDialog(
    initialProfile: Profile? = null,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var ringerMode by remember { mutableIntStateOf(initialProfile?.ringerMode ?: 2) }
    var ringVolume by remember { mutableStateOf(initialProfile?.ringVolume?.toFloat() ?: 50f) }
    var mediaVolume by remember { mutableStateOf(initialProfile?.mediaVolume?.toFloat() ?: 50f) }
    var silentVolume by remember { mutableStateOf(initialProfile?.silentVolume?.toFloat() ?: 50f) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    val visual = ringerVisual(ringerMode)
    val accent by animateColorAsState(visual.color, tween(Motion.MEDIUM), label = "sheetAccent")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceContainerDark,
        contentColor = OnSurfaceDark,
        shape = RoundedCornerShape(topStart = Q.HeroRadius, topEnd = Q.HeroRadius),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Q.Gutter)
                .padding(top = 8.dp, bottom = 20.dp)
        ) {
            // Header with an icon that morphs with the ringer mode.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = ringerMode,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "sheetIcon"
                ) { mode ->
                    val rv = ringerVisual(mode)
                    IconBadge(rv.icon, rv.color, size = 48.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (initialProfile != null) "Edit sound profile" else "New sound profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnSurfaceDark
                    )
                    AnimatedContent(
                        targetState = ringerMode,
                        transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                        label = "sheetBlurb"
                    ) { mode ->
                        Text(
                            when (mode) {
                                0 -> "Phone stays completely silent"
                                1 -> "Calls buzz quietly, no ringtone"
                                else -> "Rings at the volume you choose"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Q.Muted
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Name")
            QuietInputField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Class, Library, Gym",
                accent = accent
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel("Ringer mode")
            SegmentedSelector(
                segments = listOf(0, 1, 2).map { m ->
                    val rv = ringerVisual(m)
                    Segment(rv.label, rv.icon, rv.color)
                },
                selectedIndex = ringerMode,
                onSelect = { ringerMode = it },
                tall = true
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel("Volumes")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedVisibility(
                    visible = ringerMode == 2,
                    enter = expandVertically(tween(Motion.MEDIUM)) + fadeIn(tween(Motion.MEDIUM)),
                    exit = shrinkVertically(tween(Motion.MEDIUM)) + fadeOut(tween(Motion.SHORT))
                ) {
                    VolumeSlider(
                        label = "Ring volume",
                        icon = Icons.Outlined.VolumeUp,
                        value = if (ringVolume < 1f) 1f else ringVolume,
                        onValueChange = { ringVolume = it },
                        color = PrimaryLight,
                        valueRange = 1f..100f
                    )
                }
                VolumeSlider(
                    label = "Media volume",
                    icon = Icons.Outlined.VolumeUp,
                    value = mediaVolume,
                    onValueChange = { mediaVolume = it },
                    color = SecondaryLight
                )
                VolumeSlider(
                    label = "Alarm volume",
                    icon = Icons.Outlined.Schedule,
                    value = silentVolume,
                    onValueChange = { silentVolume = it },
                    color = TertiaryLight
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "Cancel",
                    onClick = { hideThen(onDismiss) },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = if (initialProfile != null) "Save" else "Create",
                    onClick = {
                        val profile = Profile(
                            name = name,
                            ringerMode = ringerMode,
                            ringVolume = if (ringerMode == 2) ringVolume.coerceAtLeast(1f).toInt() else ringVolume.toInt(),
                            mediaVolume = mediaVolume.toInt(),
                            silentVolume = silentVolume.toInt()
                        )
                        hideThen { onSave(profile) }
                    },
                    enabled = name.isNotBlank(),
                    icon = Icons.Outlined.Check,
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    }
}

@Composable
private fun VolumeSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Q.ControlRadius))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(Q.ControlRadius))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceDark,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    "${value.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Color.White.copy(alpha = 0.08f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

/** Filled text field in the Quiet style: tinted surface, animated focus ring, clear button. */
@Composable
internal fun QuietInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Q.ControlRadius)
    val border by animateColorAsState(if (focused) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.08f), tween(Motion.SHORT), label = "fieldBorder")
    val bg by animateColorAsState(if (focused) accent.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.04f), tween(Motion.SHORT), label = "fieldBg")
    val iconTint by animateColorAsState(if (focused) accent else Q.Muted, tween(Motion.SHORT), label = "fieldIcon")

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        interactionSource = source,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = OnSurfaceDark, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = iconTint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.titleMedium,
                            color = Q.Muted.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
                AnimatedVisibility(
                    visible = value.isNotEmpty(),
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f)
                ) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onValueChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, "Clear", tint = Q.Muted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    )
}
