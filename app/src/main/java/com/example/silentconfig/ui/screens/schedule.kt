package com.example.aquiethours.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.aquiethours.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aquiethours.data.SettingsManager
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.AiScheduleParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settingsManager.getApiKey()) }
    var selectedModel by remember { mutableStateOf(settingsManager.getModel()) }
    var showApiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var showModelDropdown by remember { mutableStateOf(false) }

    val availableModels = listOf(
        "google/gemini-2.0-flash-001" to "Gemini 2.0 Flash",
        "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
        "openai/gpt-4o-mini" to "GPT-4o Mini",
        "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet",
        "meta-llama/llama-3.1-70b-instruct" to "Llama 3.1 70B"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
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

            // AI Setup Section Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(PrimaryDark, GradientEnd)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "AI Setup",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Configure OpenRouter for timetable import",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status indicator
            val isConfigured = apiKey.isNotBlank()
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConfigured)
                        SecondaryContainer.copy(alpha = 0.4f)
                    else
                        ErrorContainerDark.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = if (isConfigured) SecondaryLight else ErrorDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (isConfigured) "API key configured" else "API key not set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isConfigured) SecondaryLight else ErrorDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // API Key input
            Text(
                "OpenRouter API Key",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    settingsManager.setApiKey(it)
                    testResult = null
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                placeholder = { Text("sk-or-...") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = OutlineDark,
                    focusedBorderColor = PrimaryDark,
                    cursorColor = PrimaryLight,
                    unfocusedContainerColor = SurfaceContainerDark,
                    focusedContainerColor = SurfaceContainerDark
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Model selector
            Text(
                "AI Model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box {
                OutlinedTextField(
                    value = availableModels.find { it.first == selectedModel }?.second ?: selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelDropdown = true },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { showModelDropdown = true }) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select model"
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = OutlineDark,
                        focusedBorderColor = PrimaryDark,
                        disabledBorderColor = OutlineDark,
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedContainerColor = SurfaceContainerDark,
                        focusedContainerColor = SurfaceContainerDark,
                        disabledContainerColor = SurfaceContainerDark
                    ),
                    enabled = false
                )

                DropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false },
                    modifier = Modifier.background(SurfaceContainerHighDark)
                ) {
                    availableModels.forEach { (modelId, displayName) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (modelId == selectedModel) PrimaryLight
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedModel = modelId
                                settingsManager.setModel(modelId)
                                showModelDropdown = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Test Connection button
            Button(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        isTesting = true
                        testResult = null
                        scope.launch {
                            testResult = AiScheduleParser.testConnection(apiKey, selectedModel)
                            isTesting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = apiKey.isNotBlank() && !isTesting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceContainerHighDark,
                    contentColor = PrimaryLight,
                    disabledContainerColor = SurfaceContainerDark
                )
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PrimaryLight,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Text("Test Connection", fontWeight = FontWeight.SemiBold)
                }
            }

            // Test result
            testResult?.let { success ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (success)
                            SecondaryContainer.copy(alpha = 0.4f)
                        else
                            ErrorContainerDark.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = if (success) SecondaryLight else ErrorDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (success) "Connection successful! AI is ready." else "Connection failed. Check your API key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (success) SecondaryLight else ErrorDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // About section
            HorizontalDivider(color = OutlineVariantDark)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "AQuietHours v1.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Automatic volume management based on your class schedule",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
