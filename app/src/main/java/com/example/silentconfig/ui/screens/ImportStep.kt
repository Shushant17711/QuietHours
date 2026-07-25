package com.example.aquiethours.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.data.SettingsManager
import com.example.aquiethours.ui.MainViewModel
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.AiScheduleParser
import com.example.aquiethours.util.OcrHelper
import com.example.aquiethours.util.ShareHelper
import com.example.aquiethours.util.format12Hour
import com.example.aquiethours.util.ParsedScheduleEntry
import kotlinx.coroutines.launch

private enum class ImportStep {
    SELECT_FILE,
    OCR_PROCESSING,
    OCR_RESULT,
    AI_PARSING,
    REVIEW_RESULTS,
    SAVING,
    DONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTimetableScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val profiles by viewModel.profiles.collectAsState()

    var currentStep by remember { mutableStateOf(ImportStep.SELECT_FILE) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isPdf by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var parsedEntries by remember { mutableStateOf<List<ParsedScheduleEntry>>(emptyList()) }
    var selectedProfileId by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var removedIndices by remember { mutableStateOf(setOf<Int>()) }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedUri = it
            isPdf = false
            errorMessage = null
            currentStep = ImportStep.OCR_PROCESSING

            scope.launch {
                try {
                    // We no longer use local ML Kit OCR for images, 
                    // because the vision AI model reads the table much better directly.
                    ocrText = "Image selected successfully.\n\nThe AI will directly analyze the visual layout of your timetable for accurate extraction."
                    currentStep = ImportStep.OCR_RESULT
                } catch (e: Exception) {
                    errorMessage = "Failed to process image: ${e.message}"
                    currentStep = ImportStep.SELECT_FILE
                }
            }
        }
    }

    // PDF picker
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedUri = it
            isPdf = true
            errorMessage = null
            currentStep = ImportStep.OCR_PROCESSING

            scope.launch {
                try {
                    ocrText = OcrHelper.recognizeTextFromPdf(context, it)
                    currentStep = if (ocrText.isBlank()) {
                        errorMessage = "No text found in the PDF. Try a different file."
                        ImportStep.SELECT_FILE
                    } else {
                        ImportStep.OCR_RESULT
                    }
                } catch (e: Exception) {
                    errorMessage = "PDF processing failed: ${e.message}"
                    currentStep = ImportStep.SELECT_FILE
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Import Timetable",
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
        ) {
            // Progress indicator
            StepIndicator(currentStep = currentStep)

            when (currentStep) {
                ImportStep.SELECT_FILE -> {
                    SelectFileContent(
                        errorMessage = errorMessage,
                        isApiConfigured = settingsManager.isApiKeyConfigured(),
                        onPickImage = { imagePickerLauncher.launch("image/*") },
                        onPickPdf = { pdfPickerLauncher.launch("application/pdf") },
                        onNavigateBack = onNavigateBack
                    )
                }

                ImportStep.OCR_PROCESSING -> {
                    ProcessingContent(message = "Running OCR...\nExtracting text from your ${if (isPdf) "PDF" else "image"}")
                }

                ImportStep.OCR_RESULT -> {
                    OcrResultContent(
                        isPdf = isPdf,
                        ocrText = ocrText,
                        profiles = profiles.map { it.id to it.name },
                        selectedProfileId = selectedProfileId,
                        onProfileSelected = { selectedProfileId = it },
                        onProceed = {
                            if (!settingsManager.isApiKeyConfigured()) {
                                errorMessage = "Please set your OpenRouter API key in Settings first."
                                currentStep = ImportStep.SELECT_FILE
                                return@OcrResultContent
                            }
                            if (selectedProfileId == null) {
                                return@OcrResultContent
                            }
                            currentStep = ImportStep.AI_PARSING
                            scope.launch {
                                try {
                                    val base64Image = if (!isPdf && selectedUri != null) {
                                        OcrHelper.getResizedBase64FromUri(context, selectedUri!!)
                                    } else null
                                    
                                    parsedEntries = AiScheduleParser.parseTimetable(
                                        ocrText = ocrText,
                                        profiles = profiles,
                                        apiKey = settingsManager.getApiKey(),
                                        model = settingsManager.getModel(),
                                        base64Image = base64Image
                                    )
                                    removedIndices = emptySet()
                                    currentStep = if (parsedEntries.isEmpty()) {
                                        errorMessage = "AI couldn't find any schedule entries. Check the OCR text."
                                        ImportStep.OCR_RESULT
                                    } else {
                                        ImportStep.REVIEW_RESULTS
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "AI parsing failed: ${e.message}"
                                    currentStep = ImportStep.OCR_RESULT
                                }
                            }
                        },
                        onRetry = {
                            currentStep = ImportStep.SELECT_FILE
                            errorMessage = null
                        },
                        errorMessage = errorMessage
                    )
                }

                ImportStep.AI_PARSING -> {
                    ProcessingContent(message = "AI is analyzing your timetable...\nThis may take a few seconds")
                }

                ImportStep.REVIEW_RESULTS -> {
                    ReviewResultsContent(
                        entries = parsedEntries,
                        removedIndices = removedIndices,
                        profileName = profiles.find { it.id == selectedProfileId }?.name ?: "Unknown",
                        onRemoveEntry = { index ->
                            removedIndices = removedIndices + index
                        },
                        onRestoreEntry = { index ->
                            removedIndices = removedIndices - index
                        },
                        onSaveAll = {
                            currentStep = ImportStep.SAVING
                            val schedules = parsedEntries
                                .filterIndexed { index, _ -> index !in removedIndices }
                                .map { entry ->
                                    Schedule(
                                        dayOfWeek = entry.day,
                                        startHour = entry.startHour,
                                        startMinute = entry.startMinute,
                                        endHour = entry.endHour,
                                        endMinute = entry.endMinute,
                                        profileId = selectedProfileId!!,
                                        label = entry.label
                                    )
                                }
                            viewModel.addSchedules(schedules)
                            currentStep = ImportStep.DONE
                        },
                        onBack = {
                            currentStep = ImportStep.OCR_RESULT
                        }
                    )
                }

                ImportStep.SAVING -> {
                    ProcessingContent(message = "Saving schedules...")
                }

                ImportStep.DONE -> {
                    DoneContent(
                        count = parsedEntries.size - removedIndices.size,
                        onDone = onNavigateBack
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: ImportStep) {
    val steps = listOf("Select", "OCR", "Review", "Done")
    val currentIndex = when (currentStep) {
        ImportStep.SELECT_FILE -> 0
        ImportStep.OCR_PROCESSING -> 1
        ImportStep.OCR_RESULT -> 1
        ImportStep.AI_PARSING -> 2
        ImportStep.REVIEW_RESULTS -> 2
        ImportStep.SAVING -> 3
        ImportStep.DONE -> 3
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, label ->
            val isActive = index <= currentIndex
            val isCurrent = index == currentIndex

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) Brush.linearGradient(
                                listOf(PrimaryDark, GradientEnd)
                            ) else Brush.linearGradient(
                                listOf(SurfaceContainerHighDark, SurfaceContainerHighDark)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (index < currentIndex) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (index < steps.size - 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            if (index < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (index < currentIndex) PrimaryDark
                            else OutlineVariantDark
                        )
                )
            }
        }
    }
}

@Composable
private fun SelectFileContent(
    errorMessage: String?,
    isApiConfigured: Boolean,
    onPickImage: () -> Unit,
    onPickPdf: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            PrimaryDark.copy(alpha = 0.2f),
                            GradientEnd.copy(alpha = 0.2f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.DocumentScanner,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = PrimaryLight
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "Import Your Timetable",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Take a photo or select a PDF of your timetable.\nAI will automatically create all your schedules.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isApiConfigured) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = ErrorContainerDark.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = ErrorDark,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Set up your OpenRouter API key in Settings first",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorDark
                    )
                }
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = ErrorContainerDark.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = ErrorDark,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val promptText = "Extract the timetable from the image and format it exactly as a single line string like this: day;startH;startM;endH;endM;label|day;startH;startM;endH;endM;label\n\nDays should be numbers from 1 (Monday) to 7 (Sunday). Hours should be 24-hour format. Do not add any markdown, markdown code blocks, or extra text. Output only the delimited string."
        
        OutlinedButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(promptText))
                Toast.makeText(context, "AI Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Don't have an API key? Copy AI Prompt")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Image button
        Button(
            onClick = onPickImage,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
        ) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Select Image",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PDF button
        OutlinedButton(
            onClick = onPickPdf,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = SecondaryLight
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.linearGradient(
                    colors = listOf(SecondaryDark, GradientTeal)
                )
            )
        ) {
            Icon(
                Icons.Outlined.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Select PDF",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ProcessingContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = PrimaryLight,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OcrResultContent(
    isPdf: Boolean,
    ocrText: String,
    profiles: List<Pair<Int, String>>,
    selectedProfileId: Int?,
    onProfileSelected: (Int) -> Unit,
    onProceed: () -> Unit,
    onRetry: () -> Unit,
    errorMessage: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            if (isPdf) "Extracted Text Preview" else "Image Ready",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        // OCR text preview
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = SurfaceContainerDark
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    ocrText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Profile selector
        Text(
            "Assign Profile",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (profiles.isEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ErrorContainerDark.copy(alpha = 0.5f))
            ) {
                Text(
                    "No profiles found. Create one in Profiles first.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorDark
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profiles.forEach { (id, name) ->
                    val isSelected = selectedProfileId == id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onProfileSelected(id) },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryDark,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceContainerHighDark,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorDark
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Retry")
            }
            Button(
                onClick = onProceed,
                modifier = Modifier.weight(2f).height(48.dp),
                enabled = selectedProfileId != null,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Parse with AI", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ReviewResultsContent(
    entries: List<ParsedScheduleEntry>,
    removedIndices: Set<Int>,
    profileName: String,
    onRemoveEntry: (Int) -> Unit,
    onRestoreEntry: (Int) -> Unit,
    onSaveAll: () -> Unit,
    onBack: () -> Unit
) {
    val activeCount = entries.size - removedIndices.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Found $activeCount Schedule${if (activeCount != 1) "s" else ""}",
                style = MaterialTheme.typography.titleMedium
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = PrimaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    profileName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryLight
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(entries) { index, entry ->
                val isRemoved = index in removedIndices
                val dayColor = getDayColor(entry.day)

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRemoved)
                            SurfaceContainerDark.copy(alpha = 0.4f)
                        else SurfaceContainerDark
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Day indicator
                        Box(
                            modifier = Modifier
                                .size(4.dp, 36.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isRemoved) OutlineDark else dayColor)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isRemoved)
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row {
                                Text(
                                    entry.day.name.take(3),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRemoved) OutlineDark else dayColor
                                )
                                Text(
                                    " · ${format12Hour(entry.startHour, entry.startMinute)} – ${format12Hour(entry.endHour, entry.endMinute)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRemoved)
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (isRemoved) onRestoreEntry(index) else onRemoveEntry(index)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isRemoved) Icons.Outlined.Undo else Icons.Filled.Close,
                                contentDescription = if (isRemoved) "Restore" else "Remove",
                                tint = if (isRemoved) SecondaryLight else ErrorDark.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Back")
            }
            Button(
                onClick = onSaveAll,
                modifier = Modifier.weight(2f).height(48.dp),
                enabled = activeCount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Text(
                    "Save $activeCount Schedule${if (activeCount != 1) "s" else ""}",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DoneContent(
    count: Int,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                SecondaryDark.copy(alpha = 0.2f),
                                GradientTeal.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = SecondaryLight
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Import Complete!",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "$count schedule${if (count != 1) "s" else ""} imported successfully",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
