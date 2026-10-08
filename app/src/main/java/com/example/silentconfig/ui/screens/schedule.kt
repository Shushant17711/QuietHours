package com.example.aquiethours.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aquiethours.data.SettingsManager
import com.example.aquiethours.ui.icons.CheckCircle
import com.example.aquiethours.ui.icons.Key
import com.example.aquiethours.ui.icons.KeyboardArrowDown
import com.example.aquiethours.ui.icons.SmartToy
import com.example.aquiethours.ui.icons.Visibility
import com.example.aquiethours.ui.icons.VisibilityOff
import com.example.aquiethours.ui.icons.Warning
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.AiScheduleParser
import com.example.aquiethours.util.OemBackgroundHelper
import kotlinx.coroutines.launch

private val availableModels = listOf(
    "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
    "google/gemini-2.0-flash-001" to "Gemini 2.0 Flash",
    "openai/gpt-4o-mini" to "GPT-4o Mini",
    "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet",
    "meta-llama/llama-3.1-70b-instruct" to "Llama 3.1 70B"
)

@Composable
fun SettingsScreen(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settingsManager.getApiKey()) }
    var selectedModel by remember { mutableStateOf(settingsManager.getModel()) }
    var showApiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var modelsOpen by remember { mutableStateOf(false) }

    val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager }
    var isBatteryUnrestricted by remember { mutableStateOf(runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                isBatteryUnrestricted = runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val versionName = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (_: Exception) { null } ?: "1.0"
    }

    AuroraBackground(accent = SecondaryLight, secondary = PrimaryLight) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            QuietHeader(title = "Settings", subtitle = "Quiet Hours v$versionName")

            Column(
                Modifier.padding(horizontal = Q.Gutter).padding(bottom = Q.BottomBarSpace + 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ---------- Reliability ----------
                Column(Modifier.enterAnimation(0)) {
                    SectionLabel("Background reliability")
                    QuietCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                        StatusRow(
                            title = "Battery optimisation",
                            body = if (isBatteryUnrestricted) "Unrestricted, so Android won't pause schedules"
                                   else "Restricted: Android may delay or skip schedules",
                            ok = isBatteryUnrestricted,
                            action = if (isBatteryUnrestricted) null else "Fix",
                            onAction = { OemBackgroundHelper.openBatteryOptimizationSettings(context) }
                        )
                        if (OemBackgroundHelper.isAggressiveOem()) {
                            Divider()
                            StatusRow(
                                title = "${OemBackgroundHelper.getManufacturerLabel()} ${OemBackgroundHelper.getAutostartScreenLabel()}",
                                body = "Required on this phone, or the app is killed when swiped from recents",
                                ok = null,
                                action = "Open",
                                onAction = {
                                    val opened = OemBackgroundHelper.openAutostartSettings(context)
                                    if (!opened) {
                                        Toast.makeText(
                                            context,
                                            "Open your phone's Security / Phone Manager app and find " +
                                                "\"${OemBackgroundHelper.getAutostartScreenLabel()}\", then set QuietHours to Allowed.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                    Text(
                        "Tip: lock Quiet Hours in your recent apps (long-press its card) so a \"clear all\" doesn't stop it.",
                        color = Q.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )
                }

                // ---------- AI import ----------
                Column(Modifier.enterAnimation(1)) {
                    SectionLabel("AI timetable import")
                    QuietCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Outlined.SmartToy, SecondaryLight)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("OpenRouter", color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                                Text("Reads timetable photos and PDFs into classes", color = Q.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                settingsManager.setApiKey(it.trim())
                                testResult = null
                            },
                            label = { Text("API key") },
                            placeholder = { Text("sk-or-...") },
                            leadingIcon = { Icon(Icons.Outlined.Key, null, tint = Q.Muted) },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        if (showApiKey) "Hide key" else "Show key",
                                        tint = Q.Muted
                                    )
                                }
                            },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(Q.ControlRadius),
                            colors = quietFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        // Model picker: collapsible list with animated selection.
                        val modelName = availableModels.firstOrNull { it.first == selectedModel }?.second ?: selectedModel
                        val rot by animateFloatAsState(if (modelsOpen) 180f else 0f, tween(Motion.MEDIUM), label = "mrot")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Q.ControlRadius))
                                .background(Color.White.copy(alpha = 0.04f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(Q.ControlRadius))
                                .clickable { modelsOpen = !modelsOpen }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Model", color = Q.Muted, style = MaterialTheme.typography.labelMedium)
                                Text(modelName, color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                            }
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Q.Muted, modifier = Modifier.graphicsLayer { rotationZ = rot })
                        }
                        AnimatedVisibility(modelsOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(Modifier.padding(top = 6.dp)) {
                                availableModels.forEach { (id, name) ->
                                    val sel = id == selectedModel
                                    val c by animateColorAsState(if (sel) PrimaryLight else OnSurfaceDark, tween(Motion.SHORT), label = "mc")
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                selectedModel = id
                                                settingsManager.setModel(id)
                                                testResult = null
                                                modelsOpen = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(name, color = c, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                        PopIn(sel) { Icon(Icons.Outlined.CheckCircle, null, tint = PrimaryLight, modifier = Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            text = if (isTesting) "Testing…" else "Test connection",
                            loading = isTesting,
                            enabled = apiKey.isNotBlank(),
                            onClick = {
                                isTesting = true
                                testResult = null
                                scope.launch {
                                    testResult = AiScheduleParser.testConnection(apiKey.trim(), selectedModel)
                                    isTesting = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AnimatedVisibility(testResult != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            val ok = testResult == true
                            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Chip(
                                    if (ok) "Connected, AI import is ready" else "Couldn't connect. Check your key",
                                    if (ok) PrimaryLight else ErrorDark,
                                    icon = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning
                                )
                            }
                        }
                    }
                }

                // ---------- Support ----------
                Column(Modifier.enterAnimation(2)) {
                    SectionLabel("Support the developer")
                    QuietCard(Modifier.fillMaxWidth(), accent = DaySun) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Filled.Favorite, DaySun)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "If Quiet Hours saved you from an embarrassing ringtone in class, consider a small tip.",
                                color = Q.Muted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(
                            text = "Support via UPI",
                            icon = Icons.Filled.Favorite,
                            colors = listOf(DaySun, Color(0xFFFF9F7A)),
                            onClick = {
                                val upiUri = Uri.parse("upi://pay?pa=6239876635@ptyes&pn=QuietHours%20Developer&cu=INR")
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, upiUri))
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "No UPI app found on your phone!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ---------- About ----------
                Column(Modifier.enterAnimation(3)) {
                    SectionLabel("About")
                    QuietCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                        LinkRow(Icons.Filled.Info, "Terms and conditions") {
                            uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/TERMS_AND_CONDITIONS.md")
                        }
                        Divider()
                        LinkRow(Icons.Filled.Info, "Privacy policy") {
                            uriHandler.openUri("https://github.com/Shushant17711/QuietHours/blob/main/PRIVACY_POLICY.md")
                        }
                        Divider()
                        LinkRow(Icons.Filled.Info, "Source code on GitHub") {
                            uriHandler.openUri("https://github.com/Shushant17711/QuietHours")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
}

/** [ok]: true = green check, false = amber warning, null = neutral info. */
@Composable
private fun StatusRow(title: String, body: String, ok: Boolean?, action: String?, onAction: () -> Unit) {
    val color = when (ok) { true -> PrimaryLight; false -> TertiaryLight; null -> SecondaryLight }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = OnSurfaceDark, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(body, color = Q.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                action,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.14f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        } else if (ok == true) {
            Icon(Icons.Outlined.CheckCircle, null, tint = PrimaryLight, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Q.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = OnSurfaceDark, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Q.Muted, modifier = Modifier.size(18.dp))
    }
}
