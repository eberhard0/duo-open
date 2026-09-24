package com.duoopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.duoopen.fold.HingeAngleSource
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Tuning controls. A modal sheet is its own window, so it stays crisp while
 * the screen behind it folds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSheet(
    config: DuoConfig,
    hinge: HingeAngleSource,
    hingeAngle: Float,
    paneTilt: Float,
    simulate: Boolean,
    onSimulateChange: (Boolean) -> Unit,
    simulatedAngle: Float,
    onSimulatedAngleChange: (Float) -> Unit,
    onPickImage: () -> Unit,
    onDefaultImage: () -> Unit,
    onSetWallpaper: () -> Unit,
    overlayAvailable: Boolean,
    overlayEnabled: Boolean,
    onEnableOverlay: () -> Unit,
    onTestOverlay: () -> Unit,
    onTestWallpaper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val hasSensor = hinge.sensor != null
    // The sensor line isn't Compose state; poll it while the sheet is up.
    val sensorStatus by produceState(hinge.statusText(), hinge) {
        while (true) {
            delay(250)
            value = hinge.statusText()
        }
    }
    var showGuide by remember { mutableStateOf(false) }
    if (showGuide) {
        SetupGuide(
            onOpenAccessibility = { showGuide = false; onEnableOverlay() },
            onDismiss = { showGuide = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            if (overlayAvailable) {
                Text("Full-screen fold", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (overlayEnabled) {
                        "On — folds the whole screen (any wallpaper, icons, apps) as you open the phone."
                    } else {
                        "Off — only the live wallpaper folds. Turn on “Duo Open full-screen fold” under Accessibility to fold everything."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (overlayEnabled) {
                        Button(onClick = onTestOverlay, modifier = Modifier.weight(1f)) { Text("Test it now") }
                        OutlinedButton(onClick = onEnableOverlay, modifier = Modifier.weight(1f)) { Text("Accessibility") }
                    } else {
                        Button(onClick = onEnableOverlay, modifier = Modifier.weight(1f)) { Text("Turn on in Accessibility") }
                    }
                }
                if (!overlayEnabled) {
                    TextButton(onClick = { showGuide = true }) { Text("Toggle greyed out?") }
                }
            } else {
                Text("Lite edition", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wallpaper only: the fold plays on the home and lock screen wallpaper, never over apps. " +
                        "No accessibility service, so nothing for Play Protect to flag. The full edition adds the system-wide fold.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Unfold effect", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                sensorStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasSensor && hinge.isCoarse) {
                Text(
                    "This hinge sensor only reports 0°, 90° and 180° (the continuous one is locked to system apps on " +
                        "Galaxy Z Fold 7 and 8), so the fold plays as a short animation at each stop instead of " +
                        "tracking your hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                "Hinge ${if (hingeAngle.isNaN()) "—" else "${hingeAngle.roundToInt()}°"}  ·  pane tilt %.1f°".format(paneTilt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(hinge.report())) }) {
                Text("Copy sensor report")
            }
            if (!hasSensor) {
                Text(
                    "No hinge angle sensor is readable by apps on this phone (Samsung keeps its fold angle for system apps). " +
                        "The wallpaper still plays the fold each time the inner screen turns on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onTestWallpaper, modifier = Modifier.fillMaxWidth()) {
                Text("Play on wallpaper now")
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Simulate hinge", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = simulate,
                    onCheckedChange = onSimulateChange,
                    enabled = hasSensor,
                )
            }
            if (simulate) {
                LabeledSlider(
                    label = "Hinge angle",
                    valueText = "${simulatedAngle.roundToInt()}°",
                    value = simulatedAngle,
                    onValueChange = onSimulatedAngleChange,
                    range = 60f..180f,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Moving half", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "Left", 1 to "Right", 0 to "Both").forEach { (side, label) ->
                    FilterChip(
                        selected = config.movingSide == side,
                        onClick = { DuoSettings.update { it.copy(movingSide = side) } },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Cover screen frost from", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "Right", false to "Left").forEach { (fromRight, label) ->
                    FilterChip(
                        selected = config.coverFrostFromRight == fromRight,
                        onClick = { DuoSettings.update { it.copy(coverFrostFromRight = fromRight) } },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LabeledSlider(
                label = "Strength",
                valueText = "%.2f×".format(config.intensity),
                value = config.intensity,
                onValueChange = { v -> DuoSettings.update { it.copy(intensity = v) } },
                range = 0.5f..3f,
            )
            LabeledSlider(
                label = "Fold duration",
                valueText = "${config.foldDurationMs} ms",
                value = config.foldDurationMs.toFloat(),
                onValueChange = { v -> DuoSettings.update { it.copy(foldDurationMs = (v / 50f).roundToInt() * 50) } },
                range = 300f..2500f,
            )
            LabeledSlider(
                label = "Frost",
                valueText = "%.2f".format(config.blurSpread),
                value = config.blurSpread,
                onValueChange = { v -> DuoSettings.update { it.copy(blurSpread = v) } },
                range = 0.02f..0.3f,
            )
            LabeledSlider(
                label = "Darkening",
                valueText = "%.3f".format(config.darkening),
                value = config.darkening,
                onValueChange = { v -> DuoSettings.update { it.copy(darkening = v) } },
                range = 0f..0.04f,
            )
            LabeledSlider(
                label = "Eye distance",
                valueText = "${config.eyeDistanceMm.roundToInt()} mm",
                value = config.eyeDistanceMm,
                onValueChange = { v -> DuoSettings.update { it.copy(eyeDistanceMm = v) } },
                range = 200f..800f,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fold splits the ${if (config.foldSplitsLong) "long" else "short"} side",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { DuoSettings.update { it.copy(foldSplitsLong = !it.foldSplitsLong) } }) {
                    Text("Flip")
                }
            }
            TextButton(onClick = DuoSettings::resetTuning) { Text("Reset tuning") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                    Text("Choose image")
                }
                OutlinedButton(onClick = onDefaultImage, modifier = Modifier.weight(1f)) {
                    Text("Default image")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSetWallpaper, modifier = Modifier.fillMaxWidth()) {
                Text("Set as live wallpaper")
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
