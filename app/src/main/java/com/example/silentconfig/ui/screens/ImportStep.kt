package com.example.aquiethours.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import com.example.aquiethours.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aquiethours.data.Schedule
import com.example.aquiethours.data.SettingsManager
import com.example.aquiethours.ui.QuietStore
import com.example.aquiethours.ui.theme.*
import com.example.aquiethours.util.AiScheduleParser
import com.example.aquiethours.util.OcrHelper
import com.example.aquiethours.util.format12Hour
import com.example.aquiethours.util.ParsedScheduleEntry
import com.example.aquiethours.util.ScheduleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

private enum class ImportStep {
    SELECT_FILE,
    OCR_PROCESSING,
    OCR_RESULT,
    AI_PARSING,
    REVIEW_RESULTS,
    SAVING,
    DONE
}

private fun createTempImageUri(context: android.content.Context): Uri {
    val file = File(context.cacheDir, "camera_images").apply { mkdirs() }
    val tempFile = File.createTempFile("camera_", ".jpg", file)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )
}

/** Which of the four visible wizard stages a step belongs to. */
private fun ImportStep.stage(): Int = when (this) {
    ImportStep.SELECT_FILE -> 0
    ImportStep.OCR_PROCESSING, ImportStep.OCR_RESULT -> 1
    ImportStep.AI_PARSING, ImportStep.REVIEW_RESULTS -> 2
    ImportStep.SAVING, ImportStep.DONE -> 3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTimetableScreen(
    viewModel: QuietStore,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val profiles by viewModel.profiles.collectAsState()
    val existingSchedules by viewModel.schedules.collectAsState()

    var currentStep by remember { mutableStateOf(ImportStep.SELECT_FILE) }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var isPdf by rememberSaveable { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var parsedEntries by remember { mutableStateOf<List<ParsedScheduleEntry>>(emptyList()) }
    var selectedProfileId by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var removedIndices by remember { mutableStateOf(setOf<Int>()) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                selectedUri = uri
                isPdf = false
                errorMessage = null
                currentStep = ImportStep.OCR_PROCESSING

                scope.launch {
                    try {
                        ocrText = "Image captured successfully.\n\nThe AI will directly analyze the visual layout of your timetable for accurate extraction."
                        currentStep = ImportStep.OCR_RESULT
                    } catch (e: Exception) {
                        errorMessage = "Failed to process image: ${e.message}"
                        currentStep = ImportStep.SELECT_FILE
                    }
                }
            }
        }
    }

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

    val auroraAccent = when (currentStep) {
        ImportStep.OCR_PROCESSING, ImportStep.AI_PARSING, ImportStep.SAVING -> SecondaryLight
        else -> PrimaryLight
    }
    val auroraSecondary = when (currentStep) {
        ImportStep.OCR_PROCESSING, ImportStep.AI_PARSING, ImportStep.SAVING -> PrimaryLight
        ImportStep.REVIEW_RESULTS -> TertiaryLight
        else -> SecondaryLight
    }

    AuroraBackground(accent = auroraAccent, secondary = auroraSecondary) {
        Column(modifier = Modifier.fillMaxSize()) {
            QuietHeader(
                title = "Import timetable",
                subtitle = "Photo or PDF in, a full week of quiet out",
                onBack = onNavigateBack
            )

            // Progress indicator
            StepIndicator(currentStep = currentStep)

            AnimatedContent(
                targetState = currentStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(Motion.MEDIUM + 60, easing = Motion.Emphasized)) { w -> dir * w / 4 } +
                        fadeIn(tween(Motion.MEDIUM, delayMillis = 60)))
                        .togetherWith(
                            slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { w -> -dir * w / 4 } +
                                fadeOut(tween(Motion.SHORT))
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "importStep"
            ) { step ->
                when (step) {
                    ImportStep.SELECT_FILE -> {
                        SelectFileContent(
                            errorMessage = errorMessage,
                            isApiConfigured = settingsManager.isApiKeyConfigured(),
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickPdf = { pdfPickerLauncher.launch("application/pdf") },
                            onTakePicture = {
                                val uri = createTempImageUri(context)
                                cameraUri = uri
                                takePictureLauncher.launch(uri)
                            },
                            onNavigateBack = onNavigateBack
                        )
                    }

                    ImportStep.OCR_PROCESSING -> {
                        ProcessingContent(
                            message = "Running OCR...\nExtracting text from your ${if (isPdf) "PDF" else "image"}",
                            icon = Icons.Outlined.DocumentScanner,
                            tint = SecondaryLight,
                            tips = listOf(
                                "Finding text regions…",
                                "Reading line by line…",
                                "Almost there…"
                            )
                        )
                    }

                    ImportStep.OCR_RESULT -> {
                        OcrResultContent(
                            isPdf = isPdf,
                            ocrText = ocrText,
                            imageUri = if (isPdf) null else selectedUri,
                            profiles = profiles.map { it.id to it.name },
                            selectedProfileId = selectedProfileId,
                            onProfileSelected = { selectedProfileId = it },
                            onCreateProfile = { showAddProfileDialog = true },
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
                        ProcessingContent(
                            message = "AI is analyzing your timetable...\nThis may take a few seconds",
                            icon = Icons.Outlined.SmartToy,
                            tint = PrimaryLight,
                            tips = listOf(
                                "Reading the grid layout…",
                                "Matching days and periods…",
                                "Converting times…",
                                "Tidying up class names…"
                            )
                        )
                    }

                    ImportStep.REVIEW_RESULTS -> {
                        ReviewResultsContent(
                            entries = parsedEntries,
                            removedIndices = removedIndices,
                            existingSchedules = existingSchedules,
                            profileName = profiles.find { it.id == selectedProfileId }?.name ?: "Unknown",
                            onRemoveEntry = { index ->
                                removedIndices = removedIndices + index
                                errorMessage = null
                            },
                            onRestoreEntry = { index ->
                                removedIndices = removedIndices - index
                                errorMessage = null
                            },
                            errorMessage = errorMessage,
                            onSaveAll = {
                                val newSchedules = parsedEntries
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

                                var hasAnyOverlap = false
                                val cumulativeSchedules = existingSchedules.toMutableList()
                                for (newSchedule in newSchedules) {
                                    if (ScheduleUtils.hasOverlap(newSchedule, cumulativeSchedules)) {
                                        hasAnyOverlap = true
                                        break
                                    }
                                    cumulativeSchedules.add(newSchedule)
                                }

                                if (hasAnyOverlap) {
                                    errorMessage = "One or more imported schedules overlap with an existing schedule or with each other. Remove the conflicting entries and try again."
                                } else {
                                    currentStep = ImportStep.SAVING
                                    viewModel.addSchedules(newSchedules)
                                    currentStep = ImportStep.DONE
                                }
                            },
                            onBack = {
                                errorMessage = null
                                currentStep = ImportStep.OCR_RESULT
                            }
                        )
                    }

                    ImportStep.SAVING -> {
                        ProcessingContent(
                            message = "Saving schedules...",
                            icon = Icons.Outlined.Schedule,
                            tint = PrimaryLight
                        )
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

        if (showAddProfileDialog) {
            AddProfileDialog(
                onDismiss = { showAddProfileDialog = false },
                onSave = {
                    viewModel.addProfile(it) { id -> selectedProfileId = id }
                    showAddProfileDialog = false
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step indicator
// ---------------------------------------------------------------------------------------------

@Composable
private fun StepIndicator(currentStep: ImportStep) {
    val steps = listOf("Source", "Scan", "Review", "Done")
    val currentIndex = currentStep.stage()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Q.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        steps.forEachIndexed { index, label ->
            StepDot(
                index = index,
                label = label,
                done = index < currentIndex,
                current = index == currentIndex
            )
            if (index < steps.size - 1) {
                val fill by animateFloatAsState(
                    targetValue = if (index < currentIndex) 1f else 0f,
                    animationSpec = tween(520, delayMillis = if (index < currentIndex) 60 else 0, easing = Motion.Emphasized),
                    label = "connector$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 13.dp, start = 2.dp, end = 2.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(PrimaryLight, SecondaryLight)))
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDot(index: Int, label: String, done: Boolean, current: Boolean) {
    val active = done || current
    val bg by animateColorAsState(
        when {
            done -> PrimaryLight
            current -> PrimaryLight.copy(alpha = 0.16f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        tween(Motion.MEDIUM), label = "dotBg"
    )
    val ring by animateColorAsState(if (active) PrimaryLight else Color.White.copy(alpha = 0.12f), tween(Motion.MEDIUM), label = "dotRing")
    val labelColor by animateColorAsState(if (active) OnSurfaceDark else Q.Muted, tween(Motion.MEDIUM), label = "dotLabel")
    val scale by animateFloatAsState(
        if (current) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dotScale"
    )

    val halo = rememberInfiniteTransition(label = "halo")
    val haloP by halo.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "haloP"
    )

    Column(
        modifier = Modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Canvas(Modifier.size(28.dp)) {
                    drawCircle(
                        PrimaryLight.copy(alpha = (1f - haloP) * 0.45f),
                        radius = size.minDimension / 2f * (1f + haloP * 0.55f),
                        style = Stroke(1.5.dp.toPx())
                    )
                }
            }
            Box(
                Modifier
                    .size(28.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.5.dp, ring, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = done,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn())
                            .togetherWith(scaleOut() + fadeOut())
                    },
                    label = "dotContent"
                ) { isDone ->
                    if (isDone) {
                        Icon(Icons.Outlined.Check, null, tint = OnPrimaryInk, modifier = Modifier.size(16.dp))
                    } else {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (current) PrimaryLight else Q.Muted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

/** Bottom-anchored action row that sits above the gesture bar with a soft fade behind it. */
@Composable
private fun BottomActions(content: @Composable RowScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, SurfaceDark.copy(alpha = 0.92f), SurfaceDark)))
            .navigationBarsPadding()
            .padding(start = Q.Gutter, end = Q.Gutter, top = 18.dp, bottom = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** Error card that expands in/out; keeps the last message on screen while collapsing. */
@Composable
private fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    val last = remember { mutableStateOf("") }
    if (message != null && message != last.value) last.value = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = expandVertically(tween(Motion.MEDIUM)) + fadeIn(tween(Motion.MEDIUM)),
        exit = shrinkVertically(tween(Motion.MEDIUM)) + fadeOut(tween(Motion.SHORT))
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            QuietCard(
                modifier = Modifier.fillMaxWidth(),
                accent = ErrorDark,
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Warning, ErrorDark, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        last.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDark
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 1 — choose a source
// ---------------------------------------------------------------------------------------------

@Suppress("UNUSED_PARAMETER")
@Composable
private fun SelectFileContent(
    errorMessage: String?,
    isApiConfigured: Boolean,
    onPickImage: () -> Unit,
    onPickPdf: () -> Unit,
    onTakePicture: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var showManualInstructionsDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val promptText = "Extract the timetable from the image and format it exactly as a single line string like this: day;startH;startM;endH;endM;label|day;startH;startM;endH;endM;label\n\nDays should be numbers from 1 (Monday) to 7 (Sunday). Hours should be 24-hour format. Do not add any markdown, markdown code blocks, or extra text. Output only the delimited string."

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Q.Gutter)
            .navigationBarsPadding()
    ) {
        Spacer(Modifier.height(12.dp))

        // Hero
        Row(
            modifier = Modifier.enterAnimation(0),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScannerBadge()
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Snap it. We'll sort it.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = OnSurfaceDark
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick a photo or PDF of your timetable — AI turns it into schedules in seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Q.Muted
                )
            }
        }

        ErrorBanner(errorMessage)

        if (!isApiConfigured) {
            Spacer(Modifier.height(16.dp))
            QuietCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(1),
                accent = TertiaryLight,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Key, TertiaryLight)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AI key not set",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurfaceDark
                        )
                        Text(
                            "Set up your OpenRouter API key in Settings first to import automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Q.Muted
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Choose a source", modifier = Modifier.enterAnimation(2))
        Spacer(Modifier.height(10.dp))

        SourceCard(
            icon = Icons.Outlined.CameraAlt,
            title = "Take a photo",
            body = "Point your camera at a printed or on-screen timetable",
            tint = PrimaryLight,
            badge = "Quickest",
            onClick = onTakePicture,
            modifier = Modifier.enterAnimation(3)
        )
        Spacer(Modifier.height(12.dp))
        SourceCard(
            icon = Icons.Outlined.Image,
            title = "Choose an image",
            body = "Screenshot or photo from your gallery",
            tint = SecondaryLight,
            onClick = onPickImage,
            modifier = Modifier.enterAnimation(4)
        )
        Spacer(Modifier.height(12.dp))
        SourceCard(
            icon = Icons.Outlined.PictureAsPdf,
            title = "Import a PDF",
            body = "Text is extracted on-device, then parsed by AI",
            tint = TertiaryLight,
            onClick = onPickPdf,
            modifier = Modifier.enterAnimation(5)
        )

        Spacer(Modifier.height(20.dp))
        SecondaryButton(
            text = "No API key? Use a free chatbot",
            onClick = { showManualInstructionsDialog = true },
            icon = Icons.Outlined.SmartToy,
            modifier = Modifier
                .fillMaxWidth()
                .enterAnimation(6)
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showManualInstructionsDialog) {
        QuietDialog(
            title = "Manual AI parsing",
            onDismiss = { showManualInstructionsDialog = false },
            confirmText = "Copy prompt",
            onConfirm = {
                clipboardManager.setText(AnnotatedString(promptText))
                Toast.makeText(context, "AI Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                showManualInstructionsDialog = false
            },
            dismissText = "Close",
            icon = Icons.Outlined.SmartToy
        ) {
            Text(
                "You can still use QuietHours without an API key by using a free AI like ChatGPT or Gemini:",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDark
            )
            Spacer(Modifier.height(14.dp))
            listOf(
                "Tap Copy prompt below to copy the AI prompt.",
                "Send your timetable image and the copied prompt to any AI chatbot.",
                "Copy the exact text the AI generates and enter it manually into the app."
            ).forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PrimaryLight.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PrimaryLight)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Q.Muted)
                }
            }
        }
    }
}

/** Document badge with a scan line sweeping over it. */
@Composable
private fun ScannerBadge() {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scanY"
    )
    val density = LocalDensity.current
    val travel = with(density) { 56.dp.toPx() }
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .size(84.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(PrimaryLight.copy(alpha = 0.22f), SecondaryLight.copy(alpha = 0.10f))))
            .border(1.dp, PrimaryLight.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.DocumentScanner, null, tint = PrimaryLight, modifier = Modifier.size(40.dp))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .graphicsLayer { translationY = y * travel }
                .fillMaxWidth(0.72f)
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, PrimaryLight, Color.Transparent)))
        )
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    body: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    QuietCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        accent = if (badge != null) tint else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceDark
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Chip(badge, tint)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Q.Muted)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = tint.copy(alpha = 0.8f)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Processing
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProcessingContent(
    message: String,
    icon: ImageVector = Icons.Outlined.SmartToy,
    tint: Color = PrimaryLight,
    tips: List<String> = emptyList()
) {
    val title = message.substringBefore('\n')
    val body = message.substringAfter('\n', "")

    var tipIndex by remember { mutableIntStateOf(0) }
    if (tips.size > 1) {
        LaunchedEffect(tips) {
            while (true) {
                delay(2400)
                tipIndex = (tipIndex + 1) % tips.size
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = Q.Gutter),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CalmLoader(icon = icon, tint = tint)
            Spacer(Modifier.height(28.dp))
            Crossfade(targetState = title, animationSpec = tween(Motion.MEDIUM), label = "procTitle") { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark,
                    textAlign = TextAlign.Center
                )
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Q.Muted,
                    textAlign = TextAlign.Center
                )
            }
            if (tips.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                AnimatedContent(
                    targetState = tipIndex,
                    transitionSpec = {
                        (slideInVertically(tween(Motion.MEDIUM)) { it / 2 } + fadeIn(tween(Motion.MEDIUM)))
                            .togetherWith(slideOutVertically(tween(Motion.MEDIUM)) { -it / 2 } + fadeOut(tween(Motion.SHORT)))
                    },
                    label = "tip"
                ) { i ->
                    Chip(tips[i.coerceIn(0, tips.size - 1)], tint)
                }
            }
        }
    }
}

/** Pulsing rings + a rotating gradient arc around a breathing icon. */
@Composable
private fun CalmLoader(icon: ImageVector, tint: Color, size: Dp = 200.dp) {
    val t = rememberInfiniteTransition(label = "loader")
    val rotation by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "rot"
    )
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "pulse"
    )
    val breathe by t.animateFloat(
        0.94f, 1.05f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val minD = this.size.minDimension
            val base = minD * 0.24f
            val maxR = minD / 2f
            for (k in 0 until 3) {
                val p = (pulse + k / 3f) % 1f
                drawCircle(
                    color = tint.copy(alpha = (1f - p) * 0.32f),
                    radius = base + p * (maxR - base),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
            val arcR = minD * 0.33f
            val stroke = 4.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.06f), radius = arcR, style = Stroke(stroke))
            rotate(rotation) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, tint.copy(alpha = 0.25f), tint)),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcR, center.y - arcR),
                    size = Size(arcR * 2, arcR * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer { scaleX = breathe; scaleY = breathe }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.06f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(36.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 2 — preview + profile
// ---------------------------------------------------------------------------------------------

@Composable
private fun OcrResultContent(
    isPdf: Boolean,
    ocrText: String,
    imageUri: Uri?,
    profiles: List<Pair<Int, String>>,
    selectedProfileId: Int?,
    onProfileSelected: (Int) -> Unit,
    onCreateProfile: () -> Unit,
    onProceed: () -> Unit,
    onRetry: () -> Unit,
    errorMessage: String?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Q.Gutter)
        ) {
            Spacer(Modifier.height(10.dp))
            SectionLabel(
                if (isPdf) "Extracted text preview" else "Image ready",
                modifier = Modifier.enterAnimation(0),
                trailing = {
                    Chip(
                        if (isPdf) "PDF" else "Image",
                        if (isPdf) TertiaryLight else SecondaryLight,
                        icon = if (isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image
                    )
                }
            )
            Spacer(Modifier.height(10.dp))

            QuietCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(1),
                contentPadding = PaddingValues(if (imageUri != null) 8.dp else 14.dp)
            ) {
                if (imageUri != null) {
                    ImagePreview(imageUri)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = PrimaryLight, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "The AI will read the table layout directly from your image.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Q.Muted
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            ocrText,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDark.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Assign to profile", modifier = Modifier.enterAnimation(2))
            Spacer(Modifier.height(10.dp))

            if (profiles.isEmpty()) {
                QuietCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .enterAnimation(3),
                    accent = SecondaryLight,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Text(
                        "Imported classes need a profile that decides how your phone behaves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Q.Muted
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(
                        text = "Create a profile",
                        onClick = onCreateProfile,
                        icon = Icons.Default.Add,
                        tint = SecondaryLight,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .enterAnimation(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { (id, name) ->
                        ProfilePill(
                            text = name,
                            selected = selectedProfileId == id,
                            onClick = { onProfileSelected(id) }
                        )
                    }
                    ProfilePill(
                        text = "New",
                        selected = false,
                        icon = Icons.Default.Add,
                        onClick = onCreateProfile
                    )
                }
                AnimatedVisibility(
                    visible = selectedProfileId == null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        "Pick a profile to continue",
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Q.Muted
                    )
                }
            }

            ErrorBanner(errorMessage)
            Spacer(Modifier.height(16.dp))
        }

        BottomActions {
            SecondaryButton(
                text = "Start over",
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "Parse with AI",
                onClick = onProceed,
                icon = Icons.Outlined.SmartToy,
                enabled = selectedProfileId != null,
                modifier = Modifier.weight(1.6f)
            )
        }
    }
}

@Composable
private fun ImagePreview(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // ImageDecoder honours EXIF orientation, so camera shots appear upright.
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        var sample = 1
                        while (longest / sample > 1400) sample *= 2
                        decoder.setTargetSampleSize(sample)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.asImageBitmap()
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(Q.CardRadius - 6.dp)
    Crossfade(targetState = bitmap, animationSpec = tween(Motion.MEDIUM), label = "preview") { bmp ->
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Selected timetable",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        } else {
            val t = rememberInfiniteTransition(label = "shimmer")
            val a by t.animateFloat(
                0.04f, 0.10f,
                infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "shimmerA"
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = a)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Image, null, tint = Q.Muted, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun ProfilePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (selected) PrimaryLight else Color.White.copy(alpha = 0.06f),
        tween(Motion.SHORT), label = "pillBg"
    )
    val fg by animateColorAsState(if (selected) OnPrimaryInk else OnSurfaceDark, tween(Motion.SHORT), label = "pillFg")
    val border by animateColorAsState(
        if (selected) PrimaryLight else Color.White.copy(alpha = 0.10f),
        tween(Motion.SHORT), label = "pillBorder"
    )
    val padStart by animateDpAsState(if (selected) 12.dp else 16.dp, tween(Motion.SHORT), label = "pillPad")
    Row(
        Modifier
            .pressScale(source, 0.94f)
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = padStart, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = selected || icon != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Row {
                Icon(
                    if (selected) Icons.Outlined.Check else (icon ?: Icons.Outlined.Check),
                    null,
                    tint = fg,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------------------------
// Step 3 — review
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewResultsContent(
    entries: List<ParsedScheduleEntry>,
    removedIndices: Set<Int>,
    existingSchedules: List<Schedule>,
    profileName: String,
    onRemoveEntry: (Int) -> Unit,
    onRestoreEntry: (Int) -> Unit,
    onSaveAll: () -> Unit,
    onBack: () -> Unit,
    errorMessage: String? = null
) {
    val activeCount = entries.size - removedIndices.size

    // Group by day, ordered by start time inside each day. Indices stay the originals.
    val groups = remember(entries) {
        entries.withIndex()
            .groupBy { it.value.day }
            .toSortedMap()
            .mapValues { (_, list) -> list.sortedBy { it.value.startHour * 60 + it.value.startMinute } }
    }

    // Display-only: which active entries would collide with existing schedules or each other.
    val conflicts = remember(entries, removedIndices, existingSchedules) {
        val active = entries.withIndex().filter { it.index !in removedIndices }
        val asSchedules = active.associate { iv ->
            iv.index to Schedule(
                dayOfWeek = iv.value.day,
                startHour = iv.value.startHour,
                startMinute = iv.value.startMinute,
                endHour = iv.value.endHour,
                endMinute = iv.value.endMinute,
                profileId = 0,
                label = iv.value.label
            )
        }
        active.filter { iv ->
            val others = existingSchedules + asSchedules.filterKeys { it != iv.index }.values
            runCatching { ScheduleUtils.hasOverlap(asSchedules.getValue(iv.index), others) }.getOrDefault(false)
        }.map { it.index }.toSet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Q.Gutter, vertical = 8.dp)
                .enterAnimation(0),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimatedContent(
                targetState = activeCount,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn())
                        .togetherWith(slideOutVertically { if (up) -it else it } + fadeOut())
                        .using(SizeTransform(clip = false))
                },
                label = "count"
            ) { count ->
                Text(
                    "$count",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryLight
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                Text(
                    "schedule${if (activeCount != 1) "s" else ""} found",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurfaceDark
                )
                Text(
                    "Tap × to skip a class",
                    style = MaterialTheme.typography.labelSmall,
                    color = Q.Muted
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Chip(profileName, PrimaryLight, icon = Icons.Outlined.Person)
                PopIn(visible = conflicts.isNotEmpty()) {
                    Chip(
                        "${conflicts.size} overlap${if (conflicts.size != 1) "s" else ""}",
                        ErrorDark,
                        modifier = Modifier.padding(top = 6.dp),
                        icon = Icons.Outlined.Warning
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = Q.Gutter, end = Q.Gutter, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var position = 0
            groups.forEach { (day, list) ->
                val headerIndex = position++
                item(key = "day_${day.name}") {
                    val activeInDay = list.count { it.index !in removedIndices }
                    DayHeader(
                        day = day,
                        activeCount = activeInDay,
                        modifier = Modifier
                            .animateItemPlacement()
                            .enterAnimation(headerIndex)
                    )
                }
                items(list, key = { it.index }) { iv ->
                    val itemIndex = position
                    ReviewEntryRow(
                        entry = iv.value,
                        removed = iv.index in removedIndices,
                        conflict = iv.index in conflicts,
                        onRemove = { onRemoveEntry(iv.index) },
                        onRestore = { onRestoreEntry(iv.index) },
                        modifier = Modifier
                            .animateItemPlacement()
                            .enterAnimation(itemIndex)
                    )
                }
                position += list.size
            }
            item(key = "error") {
                ErrorBanner(errorMessage, modifier = Modifier.animateItemPlacement())
            }
        }

        BottomActions {
            SecondaryButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "Save $activeCount Schedule${if (activeCount != 1) "s" else ""}",
                onClick = onSaveAll,
                icon = Icons.Outlined.Check,
                enabled = activeCount > 0,
                modifier = Modifier.weight(2f)
            )
        }
    }
}

@Composable
private fun DayHeader(day: java.time.DayOfWeek, activeCount: Int, modifier: Modifier = Modifier) {
    val color = getDayColor(day)
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$activeCount class${if (activeCount != 1) "es" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = Q.Muted
        )
    }
}

@Composable
private fun ReviewEntryRow(
    entry: ParsedScheduleEntry,
    removed: Boolean,
    conflict: Boolean,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dayColor = getDayColor(entry.day)
    val alpha by animateFloatAsState(if (removed) 0.45f else 1f, tween(Motion.MEDIUM), label = "rowAlpha")
    val barColor by animateColorAsState(
        when {
            removed -> Q.Muted.copy(alpha = 0.4f)
            conflict -> ErrorDark
            else -> dayColor
        },
        tween(Motion.MEDIUM), label = "rowBar"
    )

    QuietCard(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        accent = if (conflict && !removed) ErrorDark else null,
        radius = 18.dp,
        contentPadding = PaddingValues(start = 0.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label.ifBlank { "Untitled class" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark,
                    textDecoration = if (removed) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = Q.Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${format12Hour(entry.startHour, entry.startMinute)} – ${format12Hour(entry.endHour, entry.endMinute)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Q.Muted,
                        maxLines = 1
                    )
                }
                AnimatedVisibility(
                    visible = conflict && !removed,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Chip(
                        "Overlaps another class",
                        ErrorDark,
                        modifier = Modifier.padding(top = 6.dp),
                        icon = Icons.Outlined.Warning
                    )
                }
                AnimatedVisibility(
                    visible = removed,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        "Skipped — tap undo to keep it",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryLight
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = removed,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn())
                        .togetherWith(scaleOut() + fadeOut())
                },
                label = "rowAction"
            ) { isRemoved ->
                if (isRemoved) {
                    CircleIconButton(
                        Icons.Outlined.Undo,
                        "Restore",
                        onRestore,
                        tint = SecondaryLight,
                        container = SecondaryLight.copy(alpha = 0.14f)
                    )
                } else {
                    CircleIconButton(
                        Icons.Filled.Close,
                        "Remove",
                        onRemove,
                        tint = ErrorDark.copy(alpha = 0.9f),
                        container = ErrorDark.copy(alpha = 0.10f)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 4 — done
// ---------------------------------------------------------------------------------------------

@Composable
private fun DoneContent(
    count: Int,
    onDone: () -> Unit
) {
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val textIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }
        launch {
            delay(90)
            burst.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
        delay(260)
        textIn.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.Emphasized))
    }
    val glowT = rememberInfiniteTransition(label = "doneGlow")
    val glow by glowT.animateFloat(
        0.18f, 0.34f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val confetti = remember { listOf(DayMon, DayTue, DayWed, DayThu, DayFri, DaySat, DaySun) }
    val rise = with(LocalDensity.current) { 18.dp.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Q.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = burst.value
                    val base = size.minDimension * 0.24f
                    val maxR = size.minDimension / 2f
                    // Soft standing glow
                    drawCircle(
                        Brush.radialGradient(
                            listOf(PrimaryLight.copy(alpha = glow * pop.value), Color.Transparent),
                            center = center,
                            radius = maxR * 0.8f
                        ),
                        radius = maxR * 0.8f
                    )
                    // Two expanding rings
                    for (k in 0 until 2) {
                        val p = (b - k * 0.18f).coerceIn(0f, 1f)
                        if (p > 0f && p < 1f) {
                            drawCircle(
                                PrimaryLight.copy(alpha = (1f - p) * 0.6f),
                                radius = base + p * (maxR - base),
                                style = Stroke(width = (3f - 2f * p).dp.toPx())
                            )
                        }
                    }
                    // Confetti burst
                    val n = 14
                    if (b < 1f) {
                        for (i in 0 until n) {
                            val angle = (i.toFloat() / n) * 2f * Math.PI.toFloat() + 0.2f
                            val dist = base * 0.9f + b * (maxR - base * 0.6f) * (if (i % 2 == 0) 1f else 0.78f)
                            drawCircle(
                                confetti[i % confetti.size].copy(alpha = (1f - b)),
                                radius = (if (i % 3 == 0) 4.5f else 3f).dp.toPx() * (1f - b * 0.5f),
                                center = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist)
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .size(112.dp)
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(PrimaryLight, Color(0xFF5B8FF0)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .graphicsLayer { rotationZ = (1f - pop.value) * -40f },
                        tint = OnPrimaryInk
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = textIn.value
                    translationY = (1f - textIn.value) * rise
                }
            ) {
                Text(
                    "Import complete!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurfaceDark
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$count schedule${if (count != 1) "s" else ""} imported successfully",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Q.Muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Chip("Your phone will go quiet on its own", PrimaryLight, icon = Icons.Outlined.NotificationsOff)
            }
        }

        BottomActions {
            PrimaryButton(
                text = "Done",
                onClick = onDone,
                icon = Icons.Outlined.Check,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
