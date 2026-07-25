package com.example.aquiethours.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import com.example.aquiethours.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aquiethours.data.Profile
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.theme.*
import com.airbnb.lottie.compose.*
import com.example.aquiethours.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Sound Profiles",
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showDialog = true },
                containerColor = PrimaryDark,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("+ New Profile", fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = SurfaceDark
    ) { paddingValues ->
        if (profiles.isEmpty()) {
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
                    val lottieComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.walkthrough))
                    val lottieProgress by animateLottieCompositionAsState(
                        composition = lottieComposition,
                        iterations = LottieConstants.IterateForever
                    )
                    
                    LottieAnimation(
                        composition = lottieComposition,
                        progress = { lottieProgress },
                        modifier = Modifier.size(150.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Profiles Yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create a sound profile to assign\nto your class schedules",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
            ) {
                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        onDelete = { viewModel.deleteProfile(profile) }
                    )
                }
            }
        }

        if (showDialog) {
            AddProfileDialog(
                onDismiss = { showDialog = false },
                onAdd = {
                    viewModel.addProfile(it)
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    onDelete: () -> Unit
) {
    val ringerIcon: ImageVector
    val ringerLabel: String
    val ringerColor: Color

    when (profile.ringerMode) {
        0 -> {
            ringerIcon = Icons.Outlined.NotificationsOff
            ringerLabel = "Silent"
            ringerColor = ErrorDark
        }
        1 -> {
            ringerIcon = Icons.Outlined.Vibration
            ringerLabel = "Vibrate"
            ringerColor = TertiaryDark
        }
        else -> {
            ringerIcon = Icons.Outlined.VolumeUp
            ringerLabel = "Normal"
            ringerColor = SecondaryDark
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceContainerDark
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Ringer mode icon
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = ringerColor.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                ringerIcon,
                                contentDescription = ringerLabel,
                                tint = ringerColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            ringerLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = ringerColor
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = ErrorDark.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (profile.ringerMode == 2) {
                Spacer(modifier = Modifier.height(12.dp))
                // Volume bars
                VolumeBar(label = "Ring", value = profile.ringVolume, color = PrimaryLight)
            }
            Spacer(modifier = Modifier.height(6.dp))
            VolumeBar(label = "Media", value = profile.mediaVolume, color = SecondaryLight)
            Spacer(modifier = Modifier.height(6.dp))
            VolumeBar(label = "Alarm", value = profile.silentVolume, color = TertiaryLight)
        }
    }
}

@Composable
private fun VolumeBar(label: String, value: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(OutlineVariantDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = value / 100f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(color.copy(alpha = 0.6f), color)
                        )
                    )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onAdd: (Profile) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ringerMode by remember { mutableIntStateOf(2) } // Normal by default
    var ringVolume by remember { mutableStateOf(50f) }
    var mediaVolume by remember { mutableStateOf(50f) }
    var silentVolume by remember { mutableStateOf(50f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerDark,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "New Sound Profile",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    placeholder = { Text("e.g. Class, Library, Gym") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = OutlineDark,
                        focusedBorderColor = PrimaryDark,
                        cursorColor = PrimaryLight,
                        unfocusedContainerColor = SurfaceVariantDark.copy(alpha = 0.3f),
                        focusedContainerColor = SurfaceVariantDark.copy(alpha = 0.3f)
                    )
                )
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "Ringer Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Ringer mode toggle cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RingerModeCard(
                        icon = Icons.Outlined.NotificationsOff,
                        label = "Silent",
                        selected = ringerMode == 0,
                        color = ErrorDark,
                        onClick = { ringerMode = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    RingerModeCard(
                        icon = Icons.Outlined.Vibration,
                        label = "Vibrate",
                        selected = ringerMode == 1,
                        color = TertiaryDark,
                        onClick = { ringerMode = 1 },
                        modifier = Modifier.weight(1f)
                    )
                    RingerModeCard(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Normal",
                        selected = ringerMode == 2,
                        color = SecondaryDark,
                        onClick = { ringerMode = 2 },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (ringerMode == 2) {
                    VolumeSlider(
                        label = "Ring Volume",
                        value = if (ringVolume < 1f) 1f else ringVolume,
                        onValueChange = { ringVolume = it },
                        color = PrimaryLight,
                        valueRange = 1f..100f
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                VolumeSlider(
                    label = "Media Volume",
                    value = mediaVolume,
                    onValueChange = { mediaVolume = it },
                    color = SecondaryLight
                )
                Spacer(modifier = Modifier.height(12.dp))

                VolumeSlider(
                    label = "Alarm Volume",
                    value = silentVolume,
                    onValueChange = { silentVolume = it },
                    color = TertiaryLight
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(
                        Profile(
                            name = name,
                            ringerMode = ringerMode,
                            ringVolume = ringVolume.toInt(),
                            mediaVolume = mediaVolume.toInt(),
                            silentVolume = silentVolume.toInt()
                        )
                    )
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Text("Create", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun RingerModeCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (selected) color.copy(alpha = 0.2f) else SurfaceVariantDark.copy(alpha = 0.3f),
        animationSpec = tween(200)
    )
    val borderColor by animateColorAsState(
        if (selected) color else OutlineDark,
        animationSpec = tween(200)
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun VolumeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${value.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.15f)
            )
        )
    }
}
