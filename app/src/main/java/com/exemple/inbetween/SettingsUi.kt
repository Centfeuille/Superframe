package com.example.inbetween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Settings screen (opened from the editor's top bar). Every control writes straight into [AppSettings] and saves it, so the editor behind the
 * dialog shows the effect at once (e.g. mirroring the panels). Add a new option: a property in AppSettings (+ JSON key, + resetAll), a row here,
 * and the check where the behaviour happens.
 */
@Composable
fun SettingsDialog(onKeyboard: () -> Unit, onCalibratePen: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onKeyboard() }.padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Keyboard, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Keyboard shortcuts")
                        Text("View and rebind keys for playback, tools, layers and the view", fontSize = 12.sp, color = Color.Gray)
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.size(24.dp))
                }

                SectionTitle("Layout")
                SwitchRow("Left-handed layout", "Tool panel, tool options, the fullscreen tool puck, the Cut and camera panels and the vector bars move to the other side. The timeline is not mirrored.", AppSettings.leftHanded) { AppSettings.leftHanded = it; AppSettings.save() }
                SwitchRow("Open Cuts in fullscreen", "Panels start hidden; the fullscreen button brings them back.", AppSettings.startFullscreen) { AppSettings.startFullscreen = it; AppSettings.save() }
                SliderRow("Timeline height", "${AppSettings.timelineHeightDp.roundToInt()} dp", AppSettings.timelineHeightDp, 96f..520f, 0, "Applies the next time the app starts; dragging the timeline's handle still resizes it for the session.") { AppSettings.timelineHeightDp = it.roundToInt().toFloat() }

                SectionTitle("Pen input")
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Pen calibration", fontSize = 14.sp)
                        Text(if (PenCalibration.hasData) "5 × 5 correction mesh ${if (PenCalibration.enabled) "· enabled" else "· disabled"}" else "Correct pen offset, rotation, skew and position-dependent screen distortion.", fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = onCalibratePen) { Text(if (PenCalibration.hasData) "Recalibrate" else "Calibrate") }
                }
                if (PenCalibration.hasData) SwitchRow("Enable pen calibration", "Apply the saved screen-space correction to stylus and eraser input.", PenCalibration.enabled) { PenCalibration.enabled = it; AppSettings.save() }
                if (PenCalibration.hasData) TextButton(onClick = { PenCalibration.reset(); AppSettings.save() }) { Text("Reset pen calibration") }

                SectionTitle("Finger gestures")
                ChoiceRow("Two-finger tap", listOf("Nothing", "Undo", "Redo"), AppSettings.twoFingerTap) { AppSettings.twoFingerTap = it; AppSettings.save() }
                SwitchRow("Three-finger scrub", "Drag three fingers to step through drawings (or keys on a Camera / Transformation layer).", AppSettings.threeFingerScrub) { AppSettings.threeFingerScrub = it; AppSettings.save() }
                SwitchRow("Pinch together to reset the view", "Bring two fingers close together and lift to reset zoom, rotation and position.", AppSettings.pinchReset) { AppSettings.pinchReset = it; AppSettings.save() }
                SwitchRow("One-finger pan", "Off: one finger no longer moves the canvas (use two fingers to pan and zoom).", AppSettings.onePan) { AppSettings.onePan = it; AppSettings.save() }

                SectionTitle("Timings")
                SliderRow("Frame drag hold", "${AppSettings.celHoldMs} ms", AppSettings.celHoldMs.toFloat(), 80f..800f, 0, "How long to hold a frame, key or layer before dragging it.") { AppSettings.celHoldMs = (it / 10f).roundToInt() * 10 }
                SliderRow("Double-touch window", "${AppSettings.doubleTouchMs} ms", AppSettings.doubleTouchMs.toFloat(), 150f..800f, 0, "Time allowed between the tap and the second touch that starts a timeline selection rectangle.") { AppSettings.doubleTouchMs = (it / 10f).roundToInt() * 10 }
                SliderRow("Tap tolerance", "${AppSettings.tapSlopPx.roundToInt()} px", AppSettings.tapSlopPx, 8f..120f, 0, "How far a pen tap on a reference layer may move and still count as a tap.") { AppSettings.tapSlopPx = it.roundToInt().toFloat() }

                SectionTitle("Visual cues")
                SwitchRow("Touch rings", "A ring under every finger on the canvas.", AppSettings.cueTouchRings) { AppSettings.cueTouchRings = it; AppSettings.save() }
                SwitchRow("Scrub edge gradient", "The purple-to-white glow at the canvas edge while scrubbing.", AppSettings.cueEdgeGlow) { AppSettings.cueEdgeGlow = it; AppSettings.save() }
                SwitchRow("New-frame pull cue", "The growing purple pull past the last frame. Off: it still arms; the cue is only hidden.", AppSettings.cueNewFramePull) { AppSettings.cueNewFramePull = it; AppSettings.save() }
                SwitchRow("Pen hover cursor", "The brush-size circle that follows the pen above the screen.", AppSettings.cueHoverCursor) { AppSettings.cueHoverCursor = it; AppSettings.save() }

                SectionTitle("Appearance")
                Text("Workspace colour around the page", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0xFF1E1E22, 0xFF000000, 0xFF3A3A42, 0xFF2A3A4A, 0xFF8A8A92, 0xFFD0D0D6).forEach { argb ->
                        val c = argb.toInt()
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                .border(if (AppSettings.workspaceColor == c) 3.dp else 1.dp, if (AppSettings.workspaceColor == c) MaterialTheme.colorScheme.primary else Color(0x88FFFFFF), CircleShape)
                                .clickable { AppSettings.workspaceColor = c; AppSettings.save() }
                        )
                    }
                }

                SectionTitle("Startup")
                SwitchRow("Open the last Cut", "Skip the Cut list and go straight back into the Cut you were editing.", AppSettings.openLastCut) { AppSettings.openLastCut = it; AppSettings.save() }
                SwitchRow("Show What's new after an update", null, AppSettings.showWhatsNew) { AppSettings.showWhatsNew = it; AppSettings.save() }

                SectionTitle("Safety")
                SwitchRow("Confirm destructive actions", "Ask before deleting a Cut and before a Cut resize / Stretch that clears Undo.", AppSettings.confirmDestructive) { AppSettings.confirmDestructive = it; AppSettings.save() }

                TextButton({ AppSettings.resetAll() }, Modifier.padding(top = 12.dp)) { Text("Reset all settings to defaults") }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, sub: String?, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = Color.Gray)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = { AppSettings.save() })
        if (sub != null) Text(sub, fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontSize = 14.sp)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label -> FilterChip(selected == i, { onSelect(i) }, { Text(label) }) }
        }
    }
}
