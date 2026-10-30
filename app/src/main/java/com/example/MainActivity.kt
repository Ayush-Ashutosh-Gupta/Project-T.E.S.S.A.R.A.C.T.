package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.example.tessaract.AcousticCalibrationEngine
import com.example.tessaract.CustomVoiceConfig
import com.example.tessaract.GuardianConfig
import com.example.tessaract.LocalComponentRouter
import com.example.tessaract.NebiusNemotronClient
import com.example.tessaract.RoutineCommandsConfig
import com.example.tessaract.TessaractAccessibilityService
import com.example.tessaract.TessaractMediaProjectionActivity
import com.example.tessaract.TessaractOverlayService
import com.example.tessaract.TessaractService
import com.example.tessaract.TessaractCircleToSearchActivity
import com.example.ui.CommandsAndAiScreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TessaractTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            TessaractTheme {
                MainAppScaffold()
            }
        }
    }
}

@Composable
fun MainAppScaffold() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val calibrationEngine = remember { AcousticCalibrationEngine(context) }

    var selectedTab by remember { mutableIntStateOf(0) }

    // Service & Protection States
    var isServiceRunning by remember { mutableStateOf(TessaractService.isRunning) }
    var daemonMode by remember { mutableStateOf(TessaractService.currentMode) }
    var isThrottled by remember { mutableStateOf(TessaractService.isThrottled) }
    var batteryLevel by remember { mutableIntStateOf(TessaractService.currentBatteryLevel) }
    var thermalStatus by remember { mutableStateOf(TessaractService.currentThermalStatus) }

    // Voice & Speech Recognition States
    var isListeningNow by remember { mutableStateOf(false) }
    var recognizedVoiceText by remember { mutableStateOf("") }
    var executionStatusText by remember { mutableStateOf("System Ready. Say 'Hey Tesseract' or tap Wake Up.") }
    val transcriptHistory = remember { mutableStateListOf<String>() }

    // Screen Scrape & Screenshot States
    var screenScrapeResult by remember { mutableStateOf<String?>(null) }
    var screenScrapeError by remember { mutableStateOf<String?>(null) }
    var screenScreenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var screenScreenshotError by remember { mutableStateOf<String?>(null) }
    var isCapturingFrame by remember { mutableStateOf(false) }

    // Calibration States
    var registeredAnchors by remember { mutableIntStateOf(calibrationEngine.anchorCount) }
    var calculatedConfidence by remember { mutableFloatStateOf(calibrationEngine.calculateDynamicConfidenceThreshold()) }
    var isCalibrating by remember { mutableStateOf(false) }
    var calibrationProgress by remember { mutableFloatStateOf(0f) }
    var calibrationDb by remember { mutableFloatStateOf(-60f) }
    var calibrationMessage by remember { mutableStateOf("Profile ready. Tap Calibrate Live to sample ambient noise.") }
    var ttsPitch by remember { mutableFloatStateOf(1.0f) }
    var ttsRate by remember { mutableFloatStateOf(1.0f) }

    // Disambiguation State
    var isDisambiguationOpen by remember { mutableStateOf(false) }
    var disambiguationQuery by remember { mutableStateOf("") }
    val disambiguationCandidates = remember { mutableStateListOf<LocalComponentRouter.ContactCandidate>() }

    // Battery Optimization Dialog State
    var showBatteryOptDialog by remember { mutableStateOf(false) }

    // Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted && !isServiceRunning) {
            val intent = Intent(context, TessaractService::class.java).apply {
                action = TessaractService.ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isServiceRunning = true
            daemonMode = TessaractService.DaemonMode.STANDBY
        }
    }

    LaunchedEffect(Unit) {
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && !TessaractService.isRunning) {
            val intent = Intent(context, TessaractService::class.java).apply {
                action = TessaractService.ACTION_START_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isServiceRunning = true
                daemonMode = TessaractService.DaemonMode.STANDBY
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed starting TessaractService: ${e.message}")
            }
        }
        val permissionsToAsk = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToAsk.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToAsk.toTypedArray())
    }

    // Connect Telemetry Callback from Daemon
    DisposableEffect(Unit) {
        TessaractService.statusCallback = { event, payload ->
            when (event) {
                "onServiceStateChanged" -> {
                    isServiceRunning = (payload["isRunning"] as? Boolean) ?: isServiceRunning
                    val modeStr = payload["mode"] as? String
                    if (modeStr == "ACTIVE_LISTENING") daemonMode = TessaractService.DaemonMode.ACTIVE_LISTENING
                    else if (modeStr == "STANDBY") daemonMode = TessaractService.DaemonMode.STANDBY
                }
                "onWakeTriggerReceived" -> {
                    daemonMode = TessaractService.DaemonMode.ACTIVE_LISTENING
                    executionStatusText = "Awakened: Listening for commands..."
                }
                "onSpeechPartial" -> {
                    val text = payload["text"] as? String ?: ""
                    if (text.isNotBlank()) {
                        recognizedVoiceText = text
                    }
                }
                "onSpeechFinal" -> {
                    val text = payload["text"] as? String ?: ""
                    if (text.isNotBlank()) {
                        recognizedVoiceText = text
                    }
                }
                "onThermalProtectionAlert" -> {
                    isThrottled = (payload["throttled"] as? Boolean) ?: false
                    batteryLevel = (payload["batteryLevel"] as? Int) ?: batteryLevel
                    thermalStatus = (payload["thermalStatus"] as? String) ?: thermalStatus
                }
                "onAcousticRmsSample" -> {
                    batteryLevel = (payload["battery"] as? Int) ?: batteryLevel
                    thermalStatus = (payload["thermal"] as? String) ?: thermalStatus
                    isThrottled = (payload["throttled"] as? Boolean) ?: isThrottled
                    val modeStr = payload["mode"] as? String
                    if (modeStr == "ACTIVE_LISTENING") daemonMode = TessaractService.DaemonMode.ACTIVE_LISTENING
                    else if (modeStr == "STANDBY") daemonMode = TessaractService.DaemonMode.STANDBY
                }
                "onCommandRouted" -> {
                    val cmdText = payload["text"] as? String ?: ""
                    val route = payload["route"] as? String ?: ""
                    transcriptHistory.add(0, "Command: $cmdText -> $route")
                }
            }
        }
        onDispose {
            TessaractService.statusCallback = null
            calibrationEngine.release()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = "Terminal") },
                    label = { Text("Terminal", maxLines = 1, softWrap = true, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Layers, contentDescription = "Commands & AI") },
                    label = { Text("Cmds & AI", maxLines = 1, softWrap = true, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Calibration") },
                    label = { Text("Acoustics", maxLines = 1, softWrap = true, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Security, contentDescription = "Daemon") },
                    label = { Text("Daemon", maxLines = 1, softWrap = true, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Visibility, contentDescription = "Plumbing") },
                    label = { Text("Plumbing", maxLines = 1, softWrap = true, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = DarkSurfaceVariant
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HudOverlayScreen(
                    isServiceRunning = isServiceRunning,
                    daemonMode = daemonMode,
                    isListening = isListeningNow || daemonMode == TessaractService.DaemonMode.ACTIVE_LISTENING,
                    recognizedText = recognizedVoiceText,
                    executionStatus = executionStatusText,
                    transcriptHistory = transcriptHistory,
                    onWakeAssistant = {
                        val intent = Intent(context, TessaractService::class.java).apply {
                            action = TessaractService.ACTION_TRIGGER_VOICE_PROMPT
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        isServiceRunning = true
                        daemonMode = TessaractService.DaemonMode.ACTIVE_LISTENING
                    },
                    onStandbyAssistant = {
                        val intent = Intent(context, TessaractService::class.java).apply {
                            action = TessaractService.ACTION_GO_TO_STANDBY
                        }
                        context.startService(intent)
                        daemonMode = TessaractService.DaemonMode.STANDBY
                    },
                    onToggleOverlay = { enable ->
                        if (enable) {
                            if (Settings.canDrawOverlays(context)) {
                                TessaractOverlayService.show(context, "Acoustic radar active")
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        } else {
                            TessaractOverlayService.hide(context)
                        }
                    },
                    isOverlayActive = TessaractOverlayService.isOverlayShowing
                )

                1 -> CommandsAndAiScreen(
                    context = context,
                    onTestWakePhrase = { phrase ->
                        val intent = Intent(context, TessaractService::class.java).apply {
                            action = TessaractService.ACTION_SIMULATE_TRIGGER
                            putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, phrase)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        daemonMode = TessaractService.DaemonMode.ACTIVE_LISTENING
                        isServiceRunning = true
                    },
                    onTestCommand = { cmdText ->
                        recognizedVoiceText = cmdText
                        val res = LocalComponentRouter.processVoiceInput(context, cmdText)
                        handleRouteResult(
                            result = res,
                            context = context,
                            calibrationEngine = calibrationEngine,
                            onUpdateStatus = { executionStatusText = it },
                            onOpenDisambiguation = { query, candidates ->
                                disambiguationQuery = query
                                disambiguationCandidates.clear()
                                disambiguationCandidates.addAll(candidates)
                                isDisambiguationOpen = true
                            },
                            onCloseDisambiguation = { isDisambiguationOpen = false },
                            onScreenshotCaptured = { bmp ->
                                screenScreenshotBitmap = bmp
                                screenScreenshotError = null
                            },
                            onScreenScraped = { json ->
                                screenScrapeResult = json
                                screenScrapeError = null
                            }
                        )
                    },
                    onTestRoutine = { routine ->
                        recognizedVoiceText = routine.triggerPhrase
                        val (summary, announcement) = RoutineCommandsConfig.executeRoutine(context, routine)
                        executionStatusText = "Routine Executed: ${routine.title} ($summary)"
                        calibrationEngine.speak(announcement)
                        if (routine.steps.any { it.actionType == RoutineCommandsConfig.RoutineActionType.STANDBY }) {
                            val intent = Intent(context, TessaractService::class.java).apply {
                                action = TessaractService.ACTION_GO_TO_STANDBY
                            }
                            context.startService(intent)
                            daemonMode = TessaractService.DaemonMode.STANDBY
                        }
                    }
                )

                2 -> AcousticCalibrationScreen(
                    registeredAnchors = registeredAnchors,
                    calculatedConfidence = calculatedConfidence,
                    isCalibrating = isCalibrating,
                    calibrationProgress = calibrationProgress,
                    calibrationDb = calibrationDb,
                    calibrationMessage = calibrationMessage,
                    noiseFloorDb = calibrationEngine.noiseFloorDb,
                    spectralClarity = calibrationEngine.spectralClarity,
                    wakeThresholdDb = calibrationEngine.getWakeThresholdDb(),
                    ttsPitch = ttsPitch,
                    ttsRate = ttsRate,
                    onPitchChange = { newPitch ->
                        ttsPitch = newPitch
                        calibrationEngine.updateTone(newPitch, ttsRate)
                    },
                    onRateChange = { newRate ->
                        ttsRate = newRate
                        calibrationEngine.updateTone(ttsPitch, newRate)
                    },
                    onResetDefaults = {
                        ttsPitch = 1.0f
                        ttsRate = 1.0f
                        calibrationEngine.updateTone(1.0f, 1.0f)
                    },
                    onTestVocalization = {
                        calibrationEngine.speak("T.E.S.S.A.R.A.C.T. acoustic synthesis operating at nominal pitch ${String.format(Locale.US, "%.2f", ttsPitch)} and rate ${String.format(Locale.US, "%.2f", ttsRate)}.")
                    },
                    onStartCalibration = {
                        coroutineScope.launch {
                            try {
                                TessaractService.instance?.pauseForCalibration()
                                isCalibrating = true
                                calibrationProgress = 0f
                                calibrationMessage = "Sampling live acoustics from microphone..."
                                val result = calibrationEngine.performLiveAcousticCalibration(durationMs = 2500L) { db, progress ->
                                    calibrationDb = db
                                    calibrationProgress = progress
                                }
                                registeredAnchors = calibrationEngine.anchorCount
                                calculatedConfidence = result.dynamicConfidenceThreshold
                                isCalibrating = false
                                calibrationMessage = "Calibrated! Floor: ${result.noiseFloorDb.toInt()} dB, Clarity: ${(result.spectralClarity * 100).toInt()}%, Wake Threshold: ${result.wakeThresholdDb.toInt()} dB"
                                calibrationEngine.speak("Acoustic calibration complete. Measured ambient noise floor at ${result.noiseFloorDb.toInt()} decibels.")
                            } catch (e: Exception) {
                                isCalibrating = false
                                calibrationMessage = "Calibration failed: ${e.message}"
                            } finally {
                                TessaractService.instance?.resumeAfterCalibration()
                            }
                        }
                    },
                    onResetCalibration = {
                        calibrationEngine.resetCalibration()
                        registeredAnchors = 0
                        calculatedConfidence = calibrationEngine.calculateDynamicConfidenceThreshold()
                        calibrationMessage = "Profile reset. Perform calibration to register real anchors."
                    }
                )

                3 -> BackgroundDaemonScreen(
                    isServiceRunning = isServiceRunning,
                    isThrottled = isThrottled,
                    batteryLevel = batteryLevel,
                    thermalStatus = thermalStatus,
                    onToggleService = { start ->
                        val intent = Intent(context, TessaractService::class.java).apply {
                            action = if (start) TessaractService.ACTION_START_SERVICE else TessaractService.ACTION_STOP_SERVICE
                        }
                        if (start) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            isServiceRunning = true
                        } else {
                            context.startService(intent)
                            isServiceRunning = false
                        }
                    },
                    onRequestBatteryOptimization = {
                        showBatteryOptDialog = true
                    },
                    onSimulateTrigger = {
                        val intent = Intent(context, TessaractService::class.java).apply {
                            action = TessaractService.ACTION_SIMULATE_TRIGGER
                            putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "Tessaract take a screenshot")
                        }
                        context.startService(intent)
                    }
                )

                4 -> ScreenPlumbingScreen(
                    onScrapeText = {
                        try {
                            val json = TessaractAccessibilityService.scrapeActiveScreenText()
                            screenScrapeResult = json
                            screenScrapeError = null
                        } catch (e: Exception) {
                            screenScrapeError = e.message ?: "Failed scraping screen text"
                            screenScrapeResult = null
                        }
                    },
                    onCaptureScreenshot = {
                        isCapturingFrame = true
                        triggerScreenshotCapture(
                            context = context,
                            onSuccess = { bmp ->
                                screenScreenshotBitmap = bmp
                                screenScreenshotError = null
                                isCapturingFrame = false
                                val path = TessaractAccessibilityService.saveBitmapToFile(context, bmp)
                                Toast.makeText(context, "Saved to ${File(path).name}", Toast.LENGTH_SHORT).show()
                            },
                            onError = { err ->
                                screenScreenshotError = err
                                isCapturingFrame = false
                            }
                        )
                    },
                    screenScrapeResult = screenScrapeResult,
                    screenScrapeError = screenScrapeError,
                    screenshotBitmap = screenScreenshotBitmap,
                    screenshotError = screenScreenshotError,
                    isCapturingFrame = isCapturingFrame,
                    isA11yConnected = TessaractAccessibilityService.isServiceConnected
                )
            }

            // Disambiguation Slide-up Panel
            AnimatedVisibility(
                visible = isDisambiguationOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                DisambiguationSlideUpPanel(
                    query = disambiguationQuery,
                    candidates = disambiguationCandidates,
                    onSelectCandidate = { candidate ->
                        LocalComponentRouter.executePhoneCall(context, candidate.phoneNumber)
                        calibrationEngine.speak("Connecting call to ${candidate.displayName}")
                        executionStatusText = "Dialing ${candidate.displayName} (${candidate.phoneNumber})"
                        isDisambiguationOpen = false
                        disambiguationCandidates.clear()
                    },
                    onDismiss = {
                        isDisambiguationOpen = false
                        disambiguationCandidates.clear()
                    }
                )
            }

            // Battery Optimization Dialog
            if (showBatteryOptDialog) {
                AlertDialog(
                    onDismissRequest = { showBatteryOptDialog = false },
                    title = { Text("Ignore Battery Optimizations", color = CyberCyan) },
                    text = {
                        Text(
                            "To ensure T.E.S.S.A.R.A.C.T. can listen for voice commands even after the app is cleared from recent apps, allow Android to exempt it from background battery killing.",
                            color = TextPrimary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showBatteryOptDialog = false
                                val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                if (powerMgr != null && !powerMgr.isIgnoringBatteryOptimizations(context.packageName)) {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Already exempt from battery optimizations!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground)
                        ) {
                            Text("Grant Exemption")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBatteryOptDialog = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    },
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

private fun triggerScreenshotCapture(
    context: Context,
    onSuccess: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    if (TessaractAccessibilityService.canTakeScreenshotDirectly()) {
        TessaractAccessibilityService.captureScreenshot(
            onSuccess = onSuccess,
            onError = {
                TessaractMediaProjectionActivity.requestScreenCapture(context, object : TessaractMediaProjectionActivity.FrameCaptureCallback {
                    override fun onSuccess(jpegBytes: ByteArray) {
                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bmp != null) onSuccess(bmp) else onError("Decoded bitmap was null")
                    }
                    override fun onError(errorCode: String, description: String) {
                        onError("[$errorCode] $description")
                    }
                })
            }
        )
    } else {
        TessaractMediaProjectionActivity.requestScreenCapture(context, object : TessaractMediaProjectionActivity.FrameCaptureCallback {
            override fun onSuccess(jpegBytes: ByteArray) {
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bmp != null) onSuccess(bmp) else onError("Decoded bitmap was null")
            }
            override fun onError(errorCode: String, description: String) {
                onError("[$errorCode] $description")
            }
        })
    }
}

private fun handleRouteResult(
    result: LocalComponentRouter.RouteResult,
    context: Context,
    calibrationEngine: AcousticCalibrationEngine,
    onUpdateStatus: (String) -> Unit,
    onOpenDisambiguation: (String, List<LocalComponentRouter.ContactCandidate>) -> Unit,
    onCloseDisambiguation: () -> Unit,
    onScreenshotCaptured: (Bitmap) -> Unit,
    onScreenScraped: (String) -> Unit
) {
    when (result) {
        is LocalComponentRouter.RouteResult.CircleToSearch -> {
            onCloseDisambiguation()
            onUpdateStatus("Launching Circle to Search...")
            calibrationEngine.speak("Circle any area on your screen.") {
                TessaractCircleToSearchActivity.launch(context)
            }
        }
        is LocalComponentRouter.RouteResult.ScreenCapture -> {
            onCloseDisambiguation()
            onUpdateStatus("Capturing display frame...")
            triggerScreenshotCapture(
                context = context,
                onSuccess = { bmp ->
                    onScreenshotCaptured(bmp)
                    val path = TessaractAccessibilityService.saveBitmapToFile(context, bmp)
                    val prompt = "Screenshot captured and saved to pictures."
                    calibrationEngine.speak(prompt)
                    onUpdateStatus("Screenshot captured: ${File(path).name}")
                },
                onError = { err ->
                    val prompt = "Screen capture failed: $err"
                    calibrationEngine.speak(prompt)
                    onUpdateStatus(prompt)
                }
            )
        }
        is LocalComponentRouter.RouteResult.ScreenScrape -> {
            onCloseDisambiguation()
            onUpdateStatus("Analyzing visible screen text...")
            if (TessaractAccessibilityService.isServiceConnected) {
                try {
                    val json = TessaractAccessibilityService.scrapeActiveScreenText()
                    onScreenScraped(json)
                    val array = JSONArray(json)
                    val textItems = mutableListOf<String>()
                    for (i in 0 until minOf(array.length(), 4)) {
                        val t = array.getJSONObject(i).optString("text")
                        if (t.isNotBlank()) textItems.add(t)
                    }
                    val summary = if (textItems.isNotEmpty()) {
                        "Screen contains: " + textItems.joinToString(", ")
                    } else {
                        "Screen analyzed. ${array.length()} UI nodes extracted."
                    }
                    calibrationEngine.speak(summary)
                    onUpdateStatus(summary)
                } catch (e: Exception) {
                    val msg = "Failed reading screen text: ${e.message}"
                    calibrationEngine.speak(msg)
                    onUpdateStatus(msg)
                }
            } else {
                val msg = "Accessibility service is not active. Enable T.E.S.S.A.R.A.C.T. in settings."
                calibrationEngine.speak(msg)
                onUpdateStatus(msg)
            }
        }
        is LocalComponentRouter.RouteResult.DisambiguateContacts -> {
            onOpenDisambiguation(result.query, result.candidates)
            val prompt = "Multiple contacts match ${result.query}. Say index number to dial."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Disambiguation required: ${result.candidates.size} matches found.")
        }
        is LocalComponentRouter.RouteResult.DirectCall -> {
            onCloseDisambiguation()
            val prompt = "Calling ${result.name}."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Direct call triggered: ${result.name} (${result.number})")
        }
        is LocalComponentRouter.RouteResult.AppLaunch -> {
            onCloseDisambiguation()
            val prompt = "Launching ${result.appName}."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Application opened: ${result.appName}")
        }
        is LocalComponentRouter.RouteResult.AppStoreFallback -> {
            onCloseDisambiguation()
            val prompt = "${result.appName} not found locally. Redirecting to Google Play Store."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Store fallback triggered for '${result.appName}'")
        }
        is LocalComponentRouter.RouteResult.BrowserSearch -> {
            onCloseDisambiguation()
            val prompt = "Opening browser search for ${result.query}."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Browser search executing: '${result.query}'")
        }
        is LocalComponentRouter.RouteResult.MessagingDispatch -> {
            onCloseDisambiguation()
            val prompt = "Dispatching message to ${result.recipient}."
            calibrationEngine.speak(prompt)
            onUpdateStatus("Message composed for ${result.recipient}: '${result.message}'")
        }
        is LocalComponentRouter.RouteResult.Standby -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.message)
            onUpdateStatus("Standby mode: Say 'Hey Tesseract' to awaken.")
            val intent = Intent(context, TessaractService::class.java).apply {
                action = TessaractService.ACTION_GO_TO_STANDBY
            }
            context.startService(intent)
        }
        is LocalComponentRouter.RouteResult.Flashlight -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.message)
            onUpdateStatus(result.message)
        }
        is LocalComponentRouter.RouteResult.Routine -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.spokenAnnouncement)
            onUpdateStatus("Routine: ${result.title} • ${result.logSummary}")
            if (result.enterStandby) {
                val intent = Intent(context, TessaractService::class.java).apply {
                    action = TessaractService.ACTION_GO_TO_STANDBY
                }
                context.startService(intent)
            }
        }
        is LocalComponentRouter.RouteResult.Handled -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.message)
            onUpdateStatus(result.message)
        }
        is LocalComponentRouter.RouteResult.MultiStepResult -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.summary)
            onUpdateStatus("Multi-step: ${result.summary}")
        }
        is LocalComponentRouter.RouteResult.MediaAction -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.message)
            onUpdateStatus("Media: ${result.message}")
        }
        is LocalComponentRouter.RouteResult.ConnectivityAction -> {
            onCloseDisambiguation()
            calibrationEngine.speak(result.message)
            onUpdateStatus("System: ${result.message}")
        }
        is LocalComponentRouter.RouteResult.Unrecognized -> {
            onCloseDisambiguation()
            val prompt = "Command not recognized: '${result.rawInput}'"
            calibrationEngine.speak(prompt)
            onUpdateStatus(prompt)
        }
    }
}

@Composable
fun PulsatingTesseractOrbVisualizer(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tesseract_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.25f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 750 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot_anim"
    )

    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath_anim"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant)
            .border(
                1.dp,
                if (isListening) NeonGreen.copy(alpha = 0.6f) else CyberCyan.copy(alpha = 0.3f),
                RoundedCornerShape(16.dp)
            )
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = minOf(cx, cy) * 0.75f * pulseScale

                val primaryColor = if (isListening) NeonGreen else CyberCyan
                val secondaryColor = if (isListening) CyberCyan else ElectricBlue

                // 1. Subtle, crisp neon halo (NOT diluted or washed out)
                val haloRadius = radius * (0.86f + 0.05f * kotlin.math.sin(breathPhase))
                drawCircle(
                    color = primaryColor.copy(alpha = if (isListening) 0.35f else 0.20f),
                    radius = haloRadius,
                    style = Stroke(width = 1.6.dp.toPx())
                )

                // 2. Compute 3D Isometric Projection with 4D Hypercube depth
                val yaw = rotationAngle
                val pitch = 0.36f // 20-degree elevation tilt for clear 3D perspective
                val cosYaw = kotlin.math.cos(yaw)
                val sinYaw = kotlin.math.sin(yaw)
                val cosPitch = kotlin.math.cos(pitch)
                val sinPitch = kotlin.math.sin(pitch)

                val outerSize = radius * 0.54f
                val innerSize = outerSize * (0.48f + 0.06f * kotlin.math.sin(breathPhase))

                val cubeNormals = arrayOf(
                    floatArrayOf(-1f, -1f, -1f),
                    floatArrayOf( 1f, -1f, -1f),
                    floatArrayOf( 1f,  1f, -1f),
                    floatArrayOf(-1f,  1f, -1f),
                    floatArrayOf(-1f, -1f,  1f),
                    floatArrayOf( 1f, -1f,  1f),
                    floatArrayOf( 1f,  1f,  1f),
                    floatArrayOf(-1f,  1f,  1f)
                )

                val outerPts = Array(8) { Offset.Zero }
                val outerZ = FloatArray(8)
                val innerPts = Array(8) { Offset.Zero }
                val innerZ = FloatArray(8)
                val d = 3.6f

                for (i in 0 until 8) {
                    val n = cubeNormals[i]
                    // Outer
                    val ox0 = n[0] * outerSize
                    val oy0 = n[1] * outerSize
                    val oz0 = n[2] * outerSize
                    val ox1 = ox0 * cosYaw - oz0 * sinYaw
                    val oz1 = ox0 * sinYaw + oz0 * cosYaw
                    val oy1 = oy0 * cosPitch - oz1 * sinPitch
                    val oz2 = oy0 * sinPitch + oz1 * cosPitch
                    val pOuter = d / (d - oz2 / (radius * 2.5f))
                    outerPts[i] = Offset(cx + ox1 * pOuter, cy + oy1 * pOuter)
                    outerZ[i] = oz2

                    // Inner
                    val ix0 = n[0] * innerSize
                    val iy0 = n[1] * innerSize
                    val iz0 = n[2] * innerSize
                    val ix1 = ix0 * cosYaw - iz0 * sinYaw
                    val iz1 = ix0 * sinYaw + iz0 * cosYaw
                    val iy1 = iy0 * cosPitch - iz1 * sinPitch
                    val iz2 = iy0 * sinPitch + iz1 * cosPitch
                    val pInner = d / (d - iz2 / (radius * 2.5f))
                    innerPts[i] = Offset(cx + ix1 * pInner, cy + iy1 * pInner)
                    innerZ[i] = iz2
                }

                // 3. Connecting Hypercube Struts (Outer corner -> Inner corner)
                for (i in 0 until 8) {
                    drawLine(
                        color = secondaryColor.copy(alpha = 0.50f),
                        start = outerPts[i],
                        end = innerPts[i],
                        strokeWidth = 1.2.dp.toPx()
                    )
                }

                // 4. Inner Cube Edges with Realistic Depth (Front vs Rear)
                val cubeEdges = arrayOf(
                    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0),
                    Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4),
                    Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7)
                )
                for (edge in cubeEdges) {
                    val zAvg = (innerZ[edge.first] + innerZ[edge.second]) / 2f
                    val alpha = if (zAvg >= 0) 0.95f else 0.35f
                    val stroke = if (zAvg >= 0) 2.2.dp.toPx() else 1.4.dp.toPx()
                    drawLine(
                        color = secondaryColor.copy(alpha = alpha),
                        start = innerPts[edge.first],
                        end = innerPts[edge.second],
                        strokeWidth = stroke
                    )
                }

                // 5. Outer Cube Edges with Realistic Depth (Front vs Rear)
                for (edge in cubeEdges) {
                    val zAvg = (outerZ[edge.first] + outerZ[edge.second]) / 2f
                    val alpha = if (zAvg >= 0) 1.0f else 0.40f
                    val stroke = if (zAvg >= 0) 2.8.dp.toPx() else 1.6.dp.toPx()
                    drawLine(
                        color = primaryColor.copy(alpha = alpha),
                        start = outerPts[edge.first],
                        end = outerPts[edge.second],
                        strokeWidth = stroke
                    )
                }

                // 6. Crystalline Corner Nodes (Front nodes highlighted)
                for (i in 0 until 8) {
                    if (outerZ[i] >= -outerSize * 0.2f) {
                        drawCircle(color = primaryColor, center = outerPts[i], radius = 3.5.dp.toPx())
                        drawCircle(color = androidx.compose.ui.graphics.Color.White, center = outerPts[i], radius = 1.5.dp.toPx())
                    }
                    if (innerZ[i] >= 0) {
                        drawCircle(color = secondaryColor, center = innerPts[i], radius = 2.4.dp.toPx())
                    }
                }

                // 7. Center Singularity Node
                drawCircle(color = primaryColor, center = Offset(cx, cy), radius = 4.dp.toPx())
                drawCircle(color = androidx.compose.ui.graphics.Color.White, center = Offset(cx, cy), radius = 2.dp.toPx())
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isListening) "4D TESSERACT HYPERCUBE - ACTIVELY LISTENING" else "TESSERACT ORB - ACOUSTIC STANDBY",
            color = if (isListening) NeonGreen else CyberCyan,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.05.sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (isListening) "Pulsating with speech • Say \"Stop listening\" to sleep" else "Sleeping in background • Say wake word to awaken",
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun HudOverlayScreen(
    isServiceRunning: Boolean,
    daemonMode: TessaractService.DaemonMode,
    isListening: Boolean,
    recognizedText: String,
    executionStatus: String,
    transcriptHistory: List<String>,
    onWakeAssistant: () -> Unit,
    onStandbyAssistant: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    isOverlayActive: Boolean
) {
    val context = LocalContext.current
    val hasOverlayPerm = remember(isOverlayActive) { Settings.canDrawOverlays(context) }
    val isA11yConnected = remember { TessaractAccessibilityService.isServiceConnected }
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = remember {
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Current Daemon State Card
        item {
            val isActiveSession = daemonMode == TessaractService.DaemonMode.ACTIVE_LISTENING
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_card"),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isActiveSession) NeonGreen else CyberCyan)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("T.E.S.S.A.R.A.C.T. CORE", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp, softWrap = true)
                            Text(
                                if (isActiveSession) "ACTIVE COMMAND MODE (Overlay Active)"
                                else "STANDBY MODE (Overlay Hidden)",
                                color = if (isActiveSession) NeonGreen else TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                softWrap = true
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (isActiveSession) NeonGreen else if (isServiceRunning) CyberCyan else DangerRed)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(executionStatus, color = TextPrimary, fontSize = 14.sp)
                    if (recognizedText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Live input: \"$recognizedText\"", color = CyberCyan, fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    val isNemotronConfigured = remember { NebiusNemotronClient.isConfigured(context) }
                    val activeRoutinesCount = remember { RoutineCommandsConfig.getRoutines(context).filter { it.isEnabled }.size }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isNemotronConfigured) "⚡ Nemotron AI: Online" else "🛡 Nemotron AI: BYOT Standby",
                            color = if (isNemotronConfigured) NeonGreen else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Macros: $activeRoutinesCount Active",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔋 Batt: ${TessaractService.currentBatteryLevel}% • 🌡️ ${TessaractService.currentThermalStatus}",
                            color = if (TessaractService.isThrottled) WarningAmber else NeonGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (TessaractService.isThrottled) "⚠️ THROTTLED" else "🎙️ Android Built-in (Offline)",
                            color = if (TessaractService.isThrottled) WarningAmber else CyberCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    // Pulsating 4D Tesseract Orb Component (scales and glows with speech)
                    PulsatingTesseractOrbVisualizer(isListening = isListening)

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isActiveSession) "• Continuously listening for commands. Say \"Stop listening\" or \"Go to sleep\" to return to standby."
                        else "• In low-power standby mode with HUD hidden. Say \"Hey Tesseract\" or tap Wake Up below to awaken.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 2. Primary Voice Wake & Standby Controls
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val isActiveSession = daemonMode == TessaractService.DaemonMode.ACTIVE_LISTENING
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onWakeAssistant,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActiveSession) NeonGreen else CyberCyan,
                                contentColor = DarkBackground
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wake_assistant_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Wake", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Wake Up", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = onStandbyAssistant,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkSurfaceVariant,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("standby_assistant_button")
                        ) {
                            Icon(Icons.Default.MicOff, contentDescription = "Sleep", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Standby", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Spoken wake word: \"Hey Tesseract\", \"Tesseract\", or \"Wake up\"",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        "Spoken sleep commands: \"Stop listening\", \"Go to sleep\", \"Standby\"",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("System Floating HUD (Over Other Apps)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Glassmorphic Siri / Bixby floating pill with pulsating 4D Tesseract icon", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isOverlayActive,
                        onCheckedChange = { onToggleOverlay(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberCyan,
                            checkedTrackColor = DarkSurfaceVariant
                        )
                    )
                }
            }
        }

        // 4. Permissions & System Plumbing Status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SYSTEM CAPABILITIES & PERMISSIONS", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))

                    // Overlay Permission row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Draw Over Other Apps", color = TextPrimary, fontSize = 13.sp)
                            Text(if (hasOverlayPerm) "Granted" else "Requires permission", color = if (hasOverlayPerm) NeonGreen else WarningAmber, fontSize = 11.sp)
                        }
                        if (!hasOverlayPerm) {
                            TextButton(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            }) {
                                Text("Grant", color = CyberCyan)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Accessibility Service row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Screen Scraping", color = TextPrimary, fontSize = 13.sp)
                            Text(if (isA11yConnected) "Service Connected" else "Enable in Android Settings", color = if (isA11yConnected) NeonGreen else WarningAmber, fontSize = 11.sp)
                        }
                        if (!isA11yConnected) {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }) {
                                Text("Open Settings", color = CyberCyan)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Battery Optimizations row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ignore Battery Optimizations", color = TextPrimary, fontSize = 13.sp)
                            Text(if (isIgnoringBattery) "Exempted (24/7 background active)" else "Recommended for background uptime", color = if (isIgnoringBattery) NeonGreen else WarningAmber, fontSize = 11.sp)
                        }
                        if (!isIgnoringBattery) {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }) {
                                Text("Exempt", color = CyberCyan)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("COMMAND TRANSCRIPT HISTORY", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        if (transcriptHistory.isEmpty()) {
            item {
                Text("No recent commands logged. Speak or tap the mic.", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            items(transcriptHistory) { itemText ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = itemText,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AcousticCalibrationScreen(
    registeredAnchors: Int,
    calculatedConfidence: Float,
    isCalibrating: Boolean,
    calibrationProgress: Float,
    calibrationDb: Float,
    calibrationMessage: String,
    noiseFloorDb: Float,
    spectralClarity: Float,
    wakeThresholdDb: Float,
    ttsPitch: Float,
    ttsRate: Float,
    onPitchChange: (Float) -> Unit,
    onRateChange: (Float) -> Unit,
    onResetDefaults: () -> Unit,
    onTestVocalization: () -> Unit,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LIVE ACOUSTIC CALIBRATION", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Samples real ambient acoustic noise through your microphone to set dynamic threshold margins. Zero demo data.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    if (isCalibrating) {
                        Text("Sampling live acoustics... ${calibrationDb.toInt()} dBFS", color = NeonGreen, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { calibrationProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = NeonGreen,
                            trackColor = DarkSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Text(calibrationMessage, color = TextPrimary, fontSize = 13.sp)

                    Spacer(Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartCalibration,
                            enabled = !isCalibrating,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f).testTag("calibrate_live_button")
                        ) {
                            Text(if (isCalibrating) "Calibrating..." else "Calibrate Live")
                        }
                        OutlinedButton(
                            onClick = onResetCalibration,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            modifier = Modifier.testTag("reset_profile_button")
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("MEASURED ACOUSTIC METRICS", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Real Calibrated Anchors:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$registeredAnchors", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ambient Noise Floor:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${noiseFloorDb.toInt()} dBFS", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Spectral Clarity:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(spectralClarity * 100).toInt()}%", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Wake Surge Threshold:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${wakeThresholdDb.toInt()} dBFS", color = NeonGreen, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dynamic Confidence Margin:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(calculatedConfidence * 100).toInt()}%", color = CyberCyan, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SPEECH SYNTHESIS TONE", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))

                    Text("Pitch: ${String.format(Locale.US, "%.2f", ttsPitch)}", color = TextPrimary, fontSize = 13.sp)
                    Slider(
                        value = ttsPitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan)
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("Rate: ${String.format(Locale.US, "%.2f", ttsRate)}", color = TextPrimary, fontSize = 13.sp)
                    Slider(
                        value = ttsRate,
                        onValueChange = onRateChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan)
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onTestVocalization,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Voice")
                        }
                        OutlinedButton(
                            onClick = onResetDefaults,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text("Defaults")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundDaemonScreen(
    isServiceRunning: Boolean,
    isThrottled: Boolean,
    batteryLevel: Int,
    thermalStatus: String,
    onToggleService: (Boolean) -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onSimulateTrigger: () -> Unit
) {
    val context = LocalContext.current
    var isGuardianEnabled by remember { mutableStateOf(GuardianConfig.isProtectionEnabled(context)) }
    var batteryThreshold by remember { mutableFloatStateOf(GuardianConfig.getBatteryThreshold(context).toFloat()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Background Daemon", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, softWrap = true)
                            Text("Continuous acoustic radar & command executor", color = TextSecondary, fontSize = 12.sp, softWrap = true)
                        }
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { onToggleService(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan,
                                checkedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Configured with stopWithTask=false and watchdog resurrection so it continues functioning when the app is removed from recent apps.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("OEM BATTERY PROTECTION", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Aggressive OEMs (Samsung, Xiaomi, Huawei, etc.) may kill continuous background tasks. Requesting battery optimization exemption ensures 24/7 responsiveness.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRequestBatteryOptimization,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = DarkBackground),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request Ignore Battery Optimizations")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("THERMAL & BATTERY GUARDIAN", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Automatic duty-cycling to prevent heating and drain", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isGuardianEnabled,
                            onCheckedChange = { checked ->
                                isGuardianEnabled = checked
                                GuardianConfig.setProtectionEnabled(context, checked)
                                TessaractService.instance?.recheckGuardianPolicy()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan,
                                checkedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Battery Level:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$batteryLevel%", color = if (batteryLevel <= batteryThreshold.toInt()) DangerRed else NeonGreen, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Thermal Status:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(thermalStatus, color = if (isThrottled) WarningAmber else NeonGreen, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Adaptive Throttle:", color = TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isThrottled) "THROTTLED (300ms)" else "NORMAL (80ms)", color = if (isThrottled) WarningAmber else NeonGreen, fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentWidth(Alignment.End), maxLines = 1)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Battery Throttle Threshold: ${batteryThreshold.toInt()}%",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = batteryThreshold,
                        onValueChange = { newVal ->
                            batteryThreshold = newVal
                            GuardianConfig.setBatteryThreshold(context, newVal.toInt())
                            TessaractService.instance?.recheckGuardianPolicy()
                        },
                        valueRange = 5f..50f,
                        steps = 8,
                        enabled = isGuardianEnabled,
                        colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SIMULATION & TESTING", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Test wake event injection into the background daemon pipeline.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onSimulateTrigger,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate Voice Trigger (\"Take a screenshot\")")
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenPlumbingScreen(
    onScrapeText: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    screenScrapeResult: String?,
    screenScrapeError: String?,
    screenshotBitmap: Bitmap?,
    screenshotError: String?,
    isCapturingFrame: Boolean,
    isA11yConnected: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ACCESSIBILITY SCREEN SCRAPER", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), softWrap = true)
                        Text(if (isA11yConnected) "CONNECTED" else "DISCONNECTED", color = if (isA11yConnected) NeonGreen else WarningAmber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Extracts real UI element text and interactive nodes from the active foreground window. Zero mock data.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onScrapeText,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                        modifier = Modifier.fillMaxWidth().testTag("scrape_screen_button")
                    ) {
                        Text("Scrape Active Screen Text")
                    }

                    if (screenScrapeError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(screenScrapeError, color = DangerRed, fontSize = 13.sp)
                    }

                    if (screenScrapeResult != null) {
                        Spacer(Modifier.height(12.dp))
                        Text("Extracted Nodes:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = screenScrapeResult,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DISPLAY FRAME CAPTURE", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Captures real screenshots via Accessibility API (Android 11+) with MediaProjection fallback. Also triggerable via voice: \"Take a screenshot\".",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = onCaptureScreenshot,
                        enabled = !isCapturingFrame,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = DarkBackground),
                        modifier = Modifier.fillMaxWidth().testTag("capture_screenshot_button")
                    ) {
                        Text(if (isCapturingFrame) "Capturing..." else "Capture Screenshot Now")
                    }

                    if (screenshotError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(screenshotError, color = DangerRed, fontSize = 13.sp)
                    }

                    if (screenshotBitmap != null) {
                        Spacer(Modifier.height(12.dp))
                        Text("Captured Frame Preview:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Image(
                            bitmap = screenshotBitmap.asImageBitmap(),
                            contentDescription = "Screen Capture Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, CyberCyan, RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DisambiguationSlideUpPanel(
    query: String,
    candidates: List<LocalComponentRouter.ContactCandidate>,
    onSelectCandidate: (LocalComponentRouter.ContactCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Contact for \"$query\"", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), softWrap = true)
                TextButton(onClick = onDismiss) {
                    Text("Close", color = TextSecondary)
                }
            }
            Text("Tap or say \"Option [Number]\" to connect call:", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            candidates.forEachIndexed { index, candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .clickable { onSelectCandidate(candidate) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}. ${candidate.displayName}",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = candidate.phoneNumber,
                        color = CyberCyan,
                        fontSize = 13.sp,
                        modifier = Modifier.wrapContentWidth(Alignment.End),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun CustomCommandsScreen(
    context: Context = LocalContext.current,
    onTestWakePhrase: (String) -> Unit,
    onTestCommand: (String) -> Unit,
    onTestRoutine: ((RoutineCommandsConfig.RoutineCommand) -> Unit)? = null
) {
    CommandsAndAiScreen(
        context = context,
        onTestWakePhrase = onTestWakePhrase,
        onTestCommand = onTestCommand,
        onTestRoutine = onTestRoutine ?: { routine ->
            val (_, announcement) = RoutineCommandsConfig.executeRoutine(context, routine)
            Toast.makeText(context, "Routine executed: ${routine.title}", Toast.LENGTH_SHORT).show()
        }
    )
}

@Suppress("UNUSED")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyCustomCommandsScreen(
    context: Context = LocalContext.current,
    onTestWakePhrase: (String) -> Unit,
    onTestCommand: (String) -> Unit
) {
    var wakeWords by remember { mutableStateOf(CustomVoiceConfig.getWakeWords(context)) }
    var newWakeWordText by remember { mutableStateOf("") }
    var wakeWordError by remember { mutableStateOf<String?>(null) }
    var wakeWordSuccess by remember { mutableStateOf<String?>(null) }

    var customCommands by remember { mutableStateOf(CustomVoiceConfig.getCustomCommands(context)) }
    var isAddingCommand by remember { mutableStateOf(false) }

    var triggerText by remember { mutableStateOf("") }
    var selectedActionType by remember { mutableStateOf(CustomVoiceConfig.ActionType.SPEAK_TEXT) }
    var payloadText by remember { mutableStateOf("") }
    var descriptionText by remember { mutableStateOf("") }
    var commandError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Custom Wake Words
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_wake_words_card"),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CUSTOM WAKE WORDS", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), softWrap = true)
                        Text("${wakeWords.size} ACTIVE", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Saying any of these words awakens T.E.S.S.A.R.A.C.T. and opens the glassmorphic assistant overlay.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        wakeWords.forEach { word ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("\"$word\"", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                if (wakeWords.size > 1) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $word",
                                        tint = DangerRed,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                CustomVoiceConfig.removeWakeWord(context, word)
                                                wakeWords = CustomVoiceConfig.getWakeWords(context)
                                                wakeWordSuccess = "Removed \"$word\""
                                                wakeWordError = null
                                            }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newWakeWordText,
                            onValueChange = { newWakeWordText = it },
                            placeholder = { Text("e.g. \"Friday\", \"Nova\", \"Galaxy\"", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("new_wake_word_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Button(
                            onClick = {
                                val clean = newWakeWordText.trim()
                                if (clean.length < 2) {
                                    wakeWordError = "Wake word must be at least 2 characters."
                                    wakeWordSuccess = null
                                } else {
                                    val added = CustomVoiceConfig.addWakeWord(context, clean)
                                    if (added) {
                                        wakeWords = CustomVoiceConfig.getWakeWords(context)
                                        newWakeWordText = ""
                                        wakeWordSuccess = "Added \"$clean\" as custom wake word!"
                                        wakeWordError = null
                                    } else {
                                        wakeWordError = "\"$clean\" already exists."
                                        wakeWordSuccess = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                            modifier = Modifier.testTag("add_wake_word_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }

                    if (wakeWordSuccess != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(wakeWordSuccess!!, color = NeonGreen, fontSize = 12.sp)
                    }
                    if (wakeWordError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(wakeWordError!!, color = DangerRed, fontSize = 12.sp)
                    }
                }
            }
        }

        // Section 2: Custom Voice Commands Header
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_commands_header_card"),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("CUSTOM VOICE COMMANDS", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp, softWrap = true)
                            Text("Offline deterministic intent routing", color = TextSecondary, fontSize = 11.sp, softWrap = true)
                        }
                        Button(
                            onClick = { isAddingCommand = !isAddingCommand },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAddingCommand) DarkSurfaceVariant else CyberCyan,
                                contentColor = if (isAddingCommand) TextPrimary else DarkBackground
                            ),
                            modifier = Modifier.testTag("toggle_create_command_button")
                        ) {
                            Text(if (isAddingCommand) "Cancel" else "+ New Command", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section 3: Creation Form (if expanded)
        if (isAddingCommand) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("CREATE NEW VOICE COMMAND", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))

                        Text("Spoken Trigger Phrase:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = triggerText,
                            onValueChange = { triggerText = it },
                            placeholder = { Text("e.g. \"system status\", \"night mode\", \"scan text\"", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("Action Type:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CustomVoiceConfig.ActionType.values()
                                .filter { it != CustomVoiceConfig.ActionType.SCREEN_CAPTURE && it != CustomVoiceConfig.ActionType.SCREEN_SCRAPE }
                                .forEach { type ->
                                    val isSelected = selectedActionType == type
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) CyberCyan else DarkSurface)
                                            .border(1.dp, if (isSelected) CyberCyan else DarkBorder, RoundedCornerShape(12.dp))
                                            .clickable { selectedActionType = type }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = type.displayName,
                                            color = if (isSelected) DarkBackground else TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                        }

                        Spacer(Modifier.height(12.dp))
                        val payloadPlaceholder = when (selectedActionType) {
                            CustomVoiceConfig.ActionType.SPEAK_TEXT -> "Text for assistant to speak"
                            CustomVoiceConfig.ActionType.APP_LAUNCH -> "Package name (e.g. com.google.android.youtube)"
                            CustomVoiceConfig.ActionType.OPEN_URL -> "URL (e.g. https://wikipedia.org)"
                            CustomVoiceConfig.ActionType.DIRECT_CALL -> "Phone number or contact name"
                            else -> "Leave empty (automatic local hardware action)"
                        }

                        Text("Action Payload / Value:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = payloadText,
                            onValueChange = { payloadText = it },
                            placeholder = { Text(payloadPlaceholder, color = TextMuted) },
                            singleLine = selectedActionType != CustomVoiceConfig.ActionType.SPEAK_TEXT,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("Description (Optional):", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = descriptionText,
                            onValueChange = { descriptionText = it },
                            placeholder = { Text("What this command does", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        if (commandError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(commandError!!, color = DangerRed, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val trigger = triggerText.trim()
                                if (trigger.length < 2) {
                                    commandError = "Trigger phrase must be at least 2 characters."
                                    return@Button
                                }
                                val cmd = CustomVoiceConfig.CustomCommand(
                                    triggerPhrase = trigger,
                                    actionType = selectedActionType,
                                    actionPayload = payloadText.trim(),
                                    description = descriptionText.trim()
                                )
                                CustomVoiceConfig.saveCustomCommand(context, cmd)
                                customCommands = CustomVoiceConfig.getCustomCommands(context)
                                triggerText = ""
                                payloadText = ""
                                descriptionText = ""
                                commandError = null
                                isAddingCommand = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkBackground),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Custom Command", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Section 4: Existing Custom Commands
        items(customCommands) { cmd ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "\"${cmd.triggerPhrase}\"",
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            softWrap = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { onTestCommand(cmd.triggerPhrase) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Test", tint = NeonGreen)
                            }
                            IconButton(
                                onClick = {
                                    CustomVoiceConfig.deleteCustomCommand(context, cmd.id)
                                    customCommands = CustomVoiceConfig.getCustomCommands(context)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cmd.actionType.displayName,
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberCyan)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        if (cmd.description.isNotBlank()) {
                            Text(
                                text = cmd.description,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (cmd.actionPayload.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Payload: ${cmd.actionPayload}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
