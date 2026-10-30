@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tessaract.CustomVoiceConfig
import com.example.tessaract.NebiusNemotronClient
import com.example.tessaract.RoutineCommandsConfig
import com.example.tessaract.TavilySearchClient
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

/**
 * Commands & AI Management Screen
 * Provides 4 unified cybernetic modules:
 * 1. ROUTINES: Multi-action chained macros (e.g. "Movie Time" -> Volume 89%, Brightness 76%, Netflix, Bluetooth)
 * 2. NVIDIA NEMOTRON AI: Nebius Cloud / Token Factory BYOT integration and setup guide
 * 3. WAKE PHRASES: Custom wake words and sentences
 * 4. LOCAL COMMANDS: Single-action deterministic hardware controls
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandsAndAiScreen(
    context: Context = LocalContext.current,
    onTestWakePhrase: (String) -> Unit,
    onTestCommand: (String) -> Unit,
    onTestRoutine: (RoutineCommandsConfig.RoutineCommand) -> Unit
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Cybernetic Sub-Navigation Tabs with horizontal scrolling to prevent truncation
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab,
            edgePadding = 12.dp,
            containerColor = DarkSurface,
            contentColor = CyberCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                    color = CyberCyan,
                    height = 2.5.dp
                )
            },
            divider = { HorizontalDivider(color = DarkBorder, thickness = 1.dp) }
        ) {
            val tabs = listOf(
                Pair("Routines", Icons.Default.Layers),
                Pair("Nemotron & Search", Icons.Default.Psychology),
                Pair("Wake Words", Icons.Default.Mic),
                Pair("Local Cmds", Icons.Default.Terminal),
                Pair("Architecture", Icons.Default.HelpOutline)
            )

            tabs.forEachIndexed { index, pair ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    modifier = Modifier.testTag("subtab_$index"),
                    text = {
                        Text(
                            text = pair.first,
                            fontSize = 12.sp,
                            fontWeight = if (selectedSubTab == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedSubTab == index) CyberCyan else TextSecondary,
                            maxLines = 1,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = pair.second,
                            contentDescription = pair.first,
                            tint = if (selectedSubTab == index) CyberCyan else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        // Sub-Tab Content
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                0 -> RoutinesSubTab(context = context, onTestRoutine = onTestRoutine)
                1 -> NemotronAiSubTab(context = context)
                2 -> WakeWordsSubTab(context = context, onTestWakePhrase = onTestWakePhrase)
                3 -> LocalCommandsSubTab(context = context, onTestCommand = onTestCommand)
                4 -> ArchitectureSubTab(context = context)
            }
        }
    }
}

// =========================================================================
// SUB-TAB 0: ROUTINES (Multi-action Chained Commands)
// =========================================================================

@Composable
fun RoutinesSubTab(
    context: Context,
    onTestRoutine: (RoutineCommandsConfig.RoutineCommand) -> Unit
) {
    var routines by remember { mutableStateOf(RoutineCommandsConfig.getRoutines(context)) }
    var isCreatingRoutine by remember { mutableStateOf(false) }

    // Creation form state
    var newTitle by remember { mutableStateOf("") }
    var newTrigger by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    val newSteps = remember { mutableStateListOf<RoutineCommandsConfig.RoutineStep>() }
    var routineError by remember { mutableStateOf<String?>(null) }
    var routineSuccess by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("routines_header_card"),
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
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "ROUTINE COMMANDS",
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                "Chained multi-action voice macros executed all at once",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = {
                                isCreatingRoutine = !isCreatingRoutine
                                if (isCreatingRoutine && newSteps.isEmpty()) {
                                    // Default steps for new routine
                                    newSteps.add(
                                        RoutineCommandsConfig.RoutineStep(
                                            actionType = RoutineCommandsConfig.RoutineActionType.SET_VOLUME,
                                            payload = "80",
                                            description = "Set volume to 80%"
                                        )
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCreatingRoutine) DarkSurfaceVariant else CyberCyan,
                                contentColor = if (isCreatingRoutine) TextPrimary else DarkBackground
                            ),
                            modifier = Modifier.testTag("toggle_create_routine_button")
                        ) {
                            Text(if (isCreatingRoutine) "Cancel" else "+ New Routine", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Say any trigger (e.g. \"Movie Time\") to adjust volume, brightness, open streaming apps, and connect Bluetooth headphones all in one shot.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Create Routine Builder
        if (isCreatingRoutine) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("create_routine_card"),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("CREATE ROUTINE COMMAND", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))

                        Text("Routine Title:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            placeholder = { Text("e.g. \"Movie Time\", \"Gaming Mode\"", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("routine_title_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(Modifier.height(10.dp))
                        Text("Voice Trigger Phrase:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = newTrigger,
                            onValueChange = { newTrigger = it },
                            placeholder = { Text("e.g. \"movie time\", \"night mode\"", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("routine_trigger_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("CHAINED ACTIONS (${newSteps.size}):", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            TextButton(
                                onClick = {
                                    newSteps.add(
                                        RoutineCommandsConfig.RoutineStep(
                                            actionType = RoutineCommandsConfig.RoutineActionType.SPEAK_TEXT,
                                            payload = "Routine activated.",
                                            description = "Vocal confirmation"
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Step", tint = CyberCyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add Action Step", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Steps list
                        newSteps.forEachIndexed { index, step ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Step ${index + 1}: ${step.actionType.displayName}", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (newSteps.size > 1) {
                                            IconButton(
                                                onClick = { newSteps.removeAt(index) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = DangerRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }

                                    // Action type selector
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(
                                            RoutineCommandsConfig.RoutineActionType.SET_VOLUME,
                                            RoutineCommandsConfig.RoutineActionType.SET_BRIGHTNESS,
                                            RoutineCommandsConfig.RoutineActionType.LAUNCH_APP,
                                            RoutineCommandsConfig.RoutineActionType.CONNECT_BLUETOOTH,
                                            RoutineCommandsConfig.RoutineActionType.TOGGLE_WIFI,
                                            RoutineCommandsConfig.RoutineActionType.TOGGLE_BLUETOOTH,
                                            RoutineCommandsConfig.RoutineActionType.TAKE_PHOTO,
                                            RoutineCommandsConfig.RoutineActionType.RECORD_VIDEO,
                                            RoutineCommandsConfig.RoutineActionType.RECORD_AUDIO,
                                            RoutineCommandsConfig.RoutineActionType.FLASHLIGHT,
                                            RoutineCommandsConfig.RoutineActionType.SPEAK_TEXT
                                        ).forEach { aType ->
                                            val isSel = step.actionType == aType
                                            Text(
                                                text = aType.displayName.replace("Set ", "").replace("Connect ", ""),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) DarkBackground else TextSecondary,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSel) CyberCyan else DarkSurfaceVariant)
                                                    .clickable {
                                                        newSteps[index] = step.copy(
                                                            actionType = aType,
                                                            payload = when (aType) {
                                                                RoutineCommandsConfig.RoutineActionType.SET_VOLUME -> "89"
                                                                RoutineCommandsConfig.RoutineActionType.SET_BRIGHTNESS -> "76"
                                                                RoutineCommandsConfig.RoutineActionType.LAUNCH_APP -> "Netflix"
                                                                RoutineCommandsConfig.RoutineActionType.CONNECT_BLUETOOTH -> "Headphones"
                                                                RoutineCommandsConfig.RoutineActionType.TOGGLE_WIFI -> "ON"
                                                                RoutineCommandsConfig.RoutineActionType.TOGGLE_BLUETOOTH -> "ON"
                                                                RoutineCommandsConfig.RoutineActionType.TAKE_PHOTO -> "Camera"
                                                                RoutineCommandsConfig.RoutineActionType.RECORD_VIDEO -> "Video"
                                                                RoutineCommandsConfig.RoutineActionType.RECORD_AUDIO -> "Voice"
                                                                RoutineCommandsConfig.RoutineActionType.FLASHLIGHT -> "ON"
                                                                else -> ""
                                                            }
                                                        )
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = step.payload,
                                        onValueChange = { updatedPayload ->
                                            newSteps[index] = step.copy(payload = updatedPayload)
                                        },
                                        placeholder = { Text(step.actionType.payloadHint, color = TextMuted, fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyberCyan,
                                            unfocusedBorderColor = DarkBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )
                                }
                            }
                        }

                        if (routineError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(routineError!!, color = DangerRed, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val title = newTitle.trim()
                                val trigger = newTrigger.trim().lowercase()
                                if (title.isBlank()) {
                                    routineError = "Please enter a routine title."
                                    return@Button
                                }
                                if (trigger.length < 2) {
                                    routineError = "Trigger phrase must be at least 2 characters."
                                    return@Button
                                }
                                if (newSteps.isEmpty()) {
                                    routineError = "Please add at least one action step."
                                    return@Button
                                }

                                val routine = RoutineCommandsConfig.RoutineCommand(
                                    title = title,
                                    triggerPhrase = trigger,
                                    description = newSteps.joinToString(" • ") { "${it.actionType.displayName}: ${it.payload}" },
                                    steps = newSteps.toList()
                                )
                                RoutineCommandsConfig.saveRoutine(context, routine)
                                routines = RoutineCommandsConfig.getRoutines(context)
                                newTitle = ""
                                newTrigger = ""
                                newSteps.clear()
                                routineError = null
                                routineSuccess = "Routine \"$title\" created successfully!"
                                isCreatingRoutine = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkBackground),
                            modifier = Modifier.fillMaxWidth().testTag("save_routine_button")
                        ) {
                            Text("Save Routine Command", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (routineSuccess != null) {
            item {
                Text(routineSuccess!!, color = NeonGreen, fontSize = 12.sp)
            }
        }

        // List of Routine Commands
        items(routines) { routine ->
            Card(
                modifier = Modifier.fillMaxWidth().testTag("routine_card_${routine.id}"),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (routine.isEnabled) CyberCyan.copy(alpha = 0.5f) else DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = routine.title,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Voice trigger: \"${routine.triggerPhrase}\"",
                                color = CyberCyan,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Test Run Button
                            IconButton(
                                onClick = {
                                    val (logs, announcement) = RoutineCommandsConfig.executeRoutine(context, routine)
                                    onTestRoutine(routine)
                                    Toast.makeText(context, "Executed: $logs", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp).testTag("test_routine_${routine.id}")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Test Routine", tint = NeonGreen)
                            }

                            // Enable Toggle
                            Switch(
                                checked = routine.isEnabled,
                                onCheckedChange = { checked ->
                                    val updated = routine.copy(isEnabled = checked)
                                    RoutineCommandsConfig.saveRoutine(context, updated)
                                    routines = RoutineCommandsConfig.getRoutines(context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberCyan,
                                    checkedTrackColor = DarkSurfaceVariant,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DarkSurface
                                ),
                                modifier = Modifier.size(32.dp)
                            )

                            // Delete Button
                            if (routines.size > 1) {
                                IconButton(
                                    onClick = {
                                        RoutineCommandsConfig.deleteRoutine(context, routine.id)
                                        routines = RoutineCommandsConfig.getRoutines(context)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Step action badges
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        routine.steps.forEach { step ->
                            val (badgeText, badgeColor) = when (step.actionType) {
                                RoutineCommandsConfig.RoutineActionType.SET_VOLUME -> Pair("🔊 Vol: ${step.payload}%", NeonGreen)
                                RoutineCommandsConfig.RoutineActionType.SET_BRIGHTNESS -> Pair("☀️ Bright: ${step.payload}%", WarningAmber)
                                RoutineCommandsConfig.RoutineActionType.LAUNCH_APP -> Pair("📱 App: ${step.payload}", CyberCyan)
                                RoutineCommandsConfig.RoutineActionType.CONNECT_BLUETOOTH -> Pair("🎧 BT: ${step.payload}", ElectricBlue)
                                RoutineCommandsConfig.RoutineActionType.TOGGLE_WIFI -> Pair("📶 Wi-Fi: ${step.payload}", CyberCyan)
                                RoutineCommandsConfig.RoutineActionType.TOGGLE_BLUETOOTH -> Pair("ᛒ BT: ${step.payload}", ElectricBlue)
                                RoutineCommandsConfig.RoutineActionType.TAKE_PHOTO -> Pair("📷 Photo", NeonGreen)
                                RoutineCommandsConfig.RoutineActionType.RECORD_VIDEO -> Pair("🎥 Video", DangerRed)
                                RoutineCommandsConfig.RoutineActionType.RECORD_AUDIO -> Pair("🎙️ Audio", WarningAmber)
                                RoutineCommandsConfig.RoutineActionType.FLASHLIGHT -> Pair("🔦 Torch: ${step.payload}", WarningAmber)
                                RoutineCommandsConfig.RoutineActionType.SPEAK_TEXT -> Pair("🗣️ Vocal", TextSecondary)
                                RoutineCommandsConfig.RoutineActionType.HAPTIC_VIBRATE -> Pair("📳 Haptic", TextSecondary)
                                RoutineCommandsConfig.RoutineActionType.STANDBY -> Pair("💤 Sleep", TextMuted)
                            }

                            Text(
                                text = badgeText,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (routine.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = routine.description,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// SUB-TAB 1: NVIDIA NEMOTRON ON NEBIUS CLOUD / TOKEN FACTORY
// =========================================================================

@Composable
fun NemotronAiSubTab(context: Context) {
    var apiKey by remember { mutableStateOf(NebiusNemotronClient.getApiKey(context)) }
    var endpoint by remember { mutableStateOf(NebiusNemotronClient.getEndpoint(context)) }
    var selectedModel by remember { mutableStateOf(NebiusNemotronClient.getModel(context)) }
    var isEnabled by remember { mutableStateOf(NebiusNemotronClient.isAiEnabled(context)) }
    var systemPrompt by remember { mutableStateOf(NebiusNemotronClient.getSystemPrompt(context)) }
    var temperature by remember { mutableFloatStateOf(NebiusNemotronClient.getTemperature(context)) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultSuccess by remember { mutableStateOf<Boolean?>(null) }
    var isSetupGuideOpen by remember { mutableStateOf(false) }
    var saveSuccessMessage by remember { mutableStateOf<String?>(null) }

    // Tavily Search (BYOK) state
    var isTavilyEnabled by remember { mutableStateOf(TavilySearchClient.isSearchEnabled(context)) }
    var tavilyApiKey by remember { mutableStateOf(TavilySearchClient.getApiKey(context)) }
    var isTavilyPasswordVisible by remember { mutableStateOf(false) }
    var isTestingTavily by remember { mutableStateOf(false) }
    var tavilyTestResultText by remember { mutableStateOf<String?>(null) }
    var tavilyTestResultSuccess by remember { mutableStateOf<Boolean?>(null) }
    var tavilySaveMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Banner Card: Nebius Cloud + Nvidia Nemotron
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("nemotron_hero_card"),
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(CyberCyan.copy(alpha = 0.2f))
                                    .border(1.dp, CyberCyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Psychology, contentDescription = "AI", tint = CyberCyan, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("NVIDIA NEMOTRON", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Nebius Cloud / Token Factory", color = TextSecondary, fontSize = 11.sp)
                            }
                        }

                        // Live Status Badge
                        val isOnline = isEnabled && apiKey.isNotBlank()
                        Text(
                            text = if (isOnline) "ONLINE" else "SETUP NEEDED",
                            color = if (isOnline) NeonGreen else WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isOnline) NeonGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "T.E.S.S.A.R.A.C.T. connects to your Nvidia Nemotron deployment via OpenAI-compatible REST inference on Nebius Token Factory. Direct on-device BYOT (Bring-Your-Own-Token) keeps your models and credentials 100% private.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isSetupGuideOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f).testTag("open_setup_guide_button")
                        ) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "Guide", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Setup Guide & Hackathon Info", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Credentials Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("nemotron_config_card"),
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
                        Text("AI ASSISTANT ENABLED", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = {
                                isEnabled = it
                                NebiusNemotronClient.setAiEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan,
                                checkedTrackColor = DarkSurfaceVariant,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkSurface
                            )
                        )
                    }

                    HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // API Key Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Nebius Token Factory API Key:", color = TextSecondary, fontSize = 12.sp)
                        TextButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    if (clip.isNotBlank()) {
                                        apiKey = clip
                                        Toast.makeText(context, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {}
                            }
                        ) {
                            Text("Paste", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = { Text("token-... or your Nebius Cloud API secret", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("nebius_api_key_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(Modifier.height(14.dp))

                    // Endpoint Field
                    Text("Nebius API Endpoint:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("nebius_endpoint_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Token Factory",
                            fontSize = 10.sp,
                            color = if (endpoint == NebiusNemotronClient.DEFAULT_ENDPOINT) DarkBackground else CyberCyan,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (endpoint == NebiusNemotronClient.DEFAULT_ENDPOINT) CyberCyan else DarkSurfaceVariant)
                                .clickable { endpoint = NebiusNemotronClient.DEFAULT_ENDPOINT }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "Nebius Studio",
                            fontSize = 10.sp,
                            color = if (endpoint == NebiusNemotronClient.STUDIO_ENDPOINT) DarkBackground else CyberCyan,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (endpoint == NebiusNemotronClient.STUDIO_ENDPOINT) CyberCyan else DarkSurfaceVariant)
                                .clickable { endpoint = NebiusNemotronClient.STUDIO_ENDPOINT }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Model ID Selector
                    Text("Nvidia Model Identifier:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("nebius_model_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NebiusNemotronClient.AVAILABLE_MODELS.forEach { modelName ->
                            val isSel = selectedModel == modelName
                            val label = when (modelName) {
                                "nvidia/nemotron-4-340b-instruct" -> "340B Flagship"
                                "nvidia/nemotron-mini-4b-instruct" -> "Mini 4B Edge"
                                else -> "Nemotron 70B"
                            }
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                color = if (isSel) DarkBackground else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) NeonGreen else DarkSurfaceVariant)
                                    .clickable { selectedModel = modelName }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Buttons: Test Connection & Save
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTestingConnection = true
                                    testResultText = "Pinging Nebius Cloud..."
                                    testResultSuccess = null
                                    val result = NebiusNemotronClient.testConnection(
                                        apiKey = apiKey,
                                        endpoint = endpoint,
                                        model = selectedModel
                                    )
                                    isTestingConnection = false
                                    if (result.isSuccess) {
                                        val (resp, latency) = result.getOrThrow()
                                        testResultSuccess = true
                                        testResultText = "Connected! Latency: ${latency}ms • Reply: \"$resp\""
                                    } else {
                                        testResultSuccess = false
                                        testResultText = result.exceptionOrNull()?.message ?: "Connection failed"
                                    }
                                }
                            },
                            enabled = !isTestingConnection && apiKey.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("test_nebius_connection_button")
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Pinging...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Test", tint = CyberCyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Test Connection", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                NebiusNemotronClient.setApiKey(context, apiKey)
                                NebiusNemotronClient.setEndpoint(context, endpoint)
                                NebiusNemotronClient.setModel(context, selectedModel)
                                NebiusNemotronClient.setAiEnabled(context, isEnabled)
                                saveSuccessMessage = "Settings saved securely!"
                                Toast.makeText(context, "Nebius Nemotron configuration saved!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f).testTag("save_nebius_settings_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Config", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // Test Result Feedback Box
                    if (testResultText != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (testResultSuccess == true) NeonGreen.copy(alpha = 0.1f)
                                    else DangerRed.copy(alpha = 0.1f)
                                )
                                .border(
                                    1.dp,
                                    if (testResultSuccess == true) NeonGreen else DangerRed,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (testResultSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = "Result",
                                tint = if (testResultSuccess == true) NeonGreen else DangerRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = testResultText!!,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (saveSuccessMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(saveSuccessMessage!!, color = NeonGreen, fontSize = 12.sp)
                    }
                }
            }
        }

        // TAVILY AI WEB SEARCH (BYOK) CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("tavily_config_card"),
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(ElectricBlue.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Tavily Search", tint = ElectricBlue, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text("TAVILY AI SEARCH", color = ElectricBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Live Web Search & Fast Direct Answers", color = TextSecondary, fontSize = 10.sp)
                            }
                        }

                        // Status Badge
                        val isTavilyOnline = isTavilyEnabled && tavilyApiKey.isNotBlank()
                        Text(
                            text = if (isTavilyOnline) "ACTIVE" else "BYOK READY",
                            color = if (isTavilyOnline) NeonGreen else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTavilyOnline) NeonGreen.copy(alpha = 0.15f) else DarkSurfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tavily directly synthesizes factual web answers locally without burdening the LLM. If Tavily cannot answer or fails, T.E.S.S.A.R.A.C.T. automatically escalates to Nvidia Nemotron on Nebius Cloud.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ENABLE TAVILY SEARCH", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Switch(
                            checked = isTavilyEnabled,
                            onCheckedChange = {
                                isTavilyEnabled = it
                                TavilySearchClient.setSearchEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ElectricBlue,
                                checkedTrackColor = DarkSurfaceVariant,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkSurface
                            )
                        )
                    }

                    HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // Tavily API Key
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tavily API Key (tvly-...):", color = TextSecondary, fontSize = 12.sp)
                        TextButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    if (clip.isNotBlank()) {
                                        tavilyApiKey = clip
                                        Toast.makeText(context, "Tavily API Key pasted", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {}
                            }
                        ) {
                            Text("Paste", color = ElectricBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = tavilyApiKey,
                        onValueChange = { tavilyApiKey = it },
                        placeholder = { Text("tvly-...", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (isTavilyPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isTavilyPasswordVisible = !isTavilyPasswordVisible }) {
                                Icon(
                                    imageVector = if (isTavilyPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("tavily_api_key_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTestingTavily = true
                                    tavilyTestResultText = "Testing Tavily Search..."
                                    tavilyTestResultSuccess = null
                                    val result = TavilySearchClient.testConnection(tavilyApiKey)
                                    isTestingTavily = false
                                    if (result.isSuccess) {
                                        val (msg, latency) = result.getOrNull()!!
                                        tavilyTestResultText = "Connected! ($latency ms) - Direct Search Ready"
                                        tavilyTestResultSuccess = true
                                    } else {
                                        tavilyTestResultText = "Failed: ${result.exceptionOrNull()?.message?.take(70)}"
                                        tavilyTestResultSuccess = false
                                    }
                                }
                            },
                            enabled = !isTestingTavily && tavilyApiKey.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("test_tavily_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlue),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
                        ) {
                            if (isTestingTavily) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ElectricBlue, strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Testing...", fontSize = 12.sp)
                            } else {
                                Text("Test Tavily", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                TavilySearchClient.setApiKey(context, tavilyApiKey)
                                TavilySearchClient.setSearchEnabled(context, isTavilyEnabled)
                                tavilySaveMessage = "Tavily settings saved securely on-device."
                                Toast.makeText(context, "Tavily settings saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).testTag("save_tavily_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = DarkBackground)
                        ) {
                            Text("Save Tavily", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (tavilyTestResultText != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (tavilyTestResultSuccess == true) NeonGreen.copy(alpha = 0.1f)
                                    else DangerRed.copy(alpha = 0.1f)
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (tavilyTestResultSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = "Result",
                                tint = if (tavilyTestResultSuccess == true) NeonGreen else DangerRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = tavilyTestResultText!!,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (tavilySaveMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(tavilySaveMessage!!, color = NeonGreen, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tavily.com")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Get free Tavily API Key at tavily.com (1,000 requests/month)", color = CyberCyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    // Interactive Setup Guide Dialog
    if (isSetupGuideOpen) {
        AlertDialog(
            onDismissRequest = { isSetupGuideOpen = false },
            confirmButton = {
                Button(
                    onClick = { isSetupGuideOpen = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground)
                ) {
                    Text("Got It", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tokenfactory.nebius.ai")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                ) {
                    Text("Open Nebius Cloud", color = CyberCyan)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = "Security", tint = CyberCyan)
                    Text("Nebius Cloud Setup Guide", color = CyberCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "How to Deploy & Connect Nvidia Nemotron (Hackathon Ready):",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    val steps = listOf(
                        "1. Create Account: Sign in to Nebius Cloud or Token Factory (tokenfactory.nebius.ai).",
                        "2. Generate Key: In the dashboard, click 'API Keys' -> 'Create Token' and copy your secret key.",
                        "3. Select Nemotron: Choose nvidia/nemotron-4-340b-instruct or deploy custom edge mini 4B.",
                        "4. Paste & Test: Paste your key in TESSARACT Settings and tap 'Test Connection'.",
                        "5. Privacy by Design: Your token and requests flow directly from this device to your cloud tenant without intermediary servers."
                    )

                    steps.forEach { stepText ->
                        Text(stepText, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Submission Guarantee: Because keys are managed client-side, evaluators and users can immediately test the assistant with their own Nebius credentials with zero vendor risk.",
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// =========================================================================
// SUB-TAB 2: WAKE WORDS & SENTENCES
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordsSubTab(
    context: Context,
    onTestWakePhrase: (String) -> Unit
) {
    var wakeWords by remember { mutableStateOf(CustomVoiceConfig.getWakeWords(context)) }
    var newWakeWordText by remember { mutableStateOf("") }
    var wakeWordError by remember { mutableStateOf<String?>(null) }
    var wakeWordSuccess by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("wake_words_header_card"),
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
                        Text("CUSTOM WAKE WORDS & SENTENCES", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${wakeWords.size} ACTIVE", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Set single words (e.g. \"Friday\", \"Nova\") or entire conversational sentences (e.g. \"Hey Tesseract start movie time\") to awaken the assistant from standby.",
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
                                Text("\"$word\"", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                            placeholder = { Text("e.g. \"Hey Friday\", \"Activate system\"", color = TextMuted) },
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
                                    wakeWordError = "Wake word or sentence must be at least 2 characters."
                                    wakeWordSuccess = null
                                } else {
                                    val added = CustomVoiceConfig.addWakeWord(context, clean)
                                    if (added) {
                                        wakeWords = CustomVoiceConfig.getWakeWords(context)
                                        newWakeWordText = ""
                                        wakeWordSuccess = "Added \"$clean\" as custom wakeup phrase!"
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
    }
}

// =========================================================================
// SUB-TAB 3: LOCAL HARDWARE COMMANDS
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalCommandsSubTab(
    context: Context,
    onTestCommand: (String) -> Unit
) {
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("custom_commands_header_card"),
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
                            Text("LOCAL HARDWARE COMMANDS", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Deterministic offline actions without network delay", color = TextSecondary, fontSize = 11.sp)
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

        // New Command Form
        if (isAddingCommand) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("CREATE NEW LOCAL COMMAND", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))

                        Text("Spoken Trigger Phrase:", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = triggerText,
                            onValueChange = { triggerText = it },
                            placeholder = { Text("e.g. \"system status\", \"night light\", \"scan text\"", color = TextMuted) },
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
                                    Text(
                                        text = type.displayName,
                                        color = if (isSelected) DarkBackground else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) CyberCyan else DarkSurface)
                                            .border(1.dp, if (isSelected) CyberCyan else DarkBorder, RoundedCornerShape(12.dp))
                                            .clickable { selectedActionType = type }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
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

        // List of commands
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
                }
            }
        }
    }
}

// =========================================================================
// SUB-TAB 4: ARCHITECTURE & JUDGES (Local Router -> AI Fallback)
// =========================================================================

@Composable
fun ArchitectureSubTab(context: Context) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Executive Summary & Pitch
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("arch_header_card"),
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
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "PROJECT T.E.S.S.A.R.A.C.T.",
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Local-First AI Voice Assistant • Nvidia x Nebius Cloud",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(NeonGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "BYOT Ready",
                                color = NeonGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Core Philosophy: Simple on-device tasks (volume, brightness, app launch, calls, torch, routine macros) execute with 0ms server latency on-device. Only complex reasoning queries are routed to Nvidia Nemotron on Nebius Token Factory via a zero-trust BYOT model.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // 2. ASCII Architecture Diagram
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("arch_diagram_card"),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "PIPELINE ARCHITECTURE (LOCAL ROUTER -> AI FALLBACK)",
                        color = ElectricBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))

                    val asciiDiagram = """
    [Acoustic Audio Input]
              │
              ▼
    [Android SpeechRecognizer]
    • EXTRA_PREFER_OFFLINE = true
    • Zero extra downloads required
              │
              ▼
    [LocalComponentRouter]
    • Custom Wake Phrases / Sentences
    • Regex & Pattern Matcher
              │
    ┌─────────┴─────────┐
    ▼                   ▼
[Local Hardware]    [Unrecognized / AI]
• Routines (Movie)        │
• Volume / Brightness     ▼
• App Launch        [Nebius Cloud]
• Flashlight / Torch Token Factory / Studio
• Screen Scrape / OCR     │
    │                     ▼
    │               [Nvidia Nemotron]
    │               • 340B Flagship
    │               • 70B Instruct
    │               • Mini 4B Edge
    │                     │
    └─────────┬───────────┘
              ▼
    [Acoustic TTS Engine]
              │
              ▼
    [Glassmorphic 4D Hypercube HUD]
                    """.trimIndent()

                    Text(
                        text = asciiDiagram,
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground)
                            .padding(10.dp)
                    )
                }
            }
        }

        // 3. Speech Recognition Engine Transparency
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "SPEECH ENGINE: ANDROID BUILT-IN SPEECHRECOGNIZER",
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Why Android's Built-in Engine? It stays 100% on-device on devices equipped with offline speech packs, requires zero multi-gigabyte background downloads from users, and preserves battery.\n" +
                        "• EXTRA_PREFER_OFFLINE: Explicitly passed on all recognition intents to enforce local processing.\n" +
                        "• Natural Silence Watchdog: Adaptive timeouts (4.5s in standby, 1.8s in active mode) prevent cut-offs during natural pauses.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // 4. Privacy & Sensitive Screen Exclusion Shield
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = NeonGreen)
                        Text(
                            "PRIVACY SHIELD & SENSITIVE SCREEN EXCLUSION",
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Financial App Blacklist: Screen scraping, OCR, and HUD overlay are strictly blocked on payment/banking apps (Paytm, PhonePe, Google Pay, BHIM, Banking, Crypto Wallets, Password Managers).\n" +
                        "• System Settings Protection: com.android.settings and security centers are immediately excluded.\n" +
                        "• Lockscreen / Keyguard Auto-Hide: When device is locked, overlay automatically hides.\n" +
                        "• Zero Cloud Telemetry: Voice audio and system events are never sent to third-party tracking services.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // 5. Thermal & Battery Guardian
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "THERMAL & BATTERY GUARDIAN",
                        color = WarningAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Continuous background acoustic monitoring can consume CPU cycles. The Guardian dynamically measures battery % and Android PowerManager thermal status. When battery drops below user threshold (default 15%) or thermal state rises to SEVERE/CRITICAL, it duty-cycles acoustic sampling from 80ms to 300ms, tints the HUD amber, and preserves hardware integrity.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
