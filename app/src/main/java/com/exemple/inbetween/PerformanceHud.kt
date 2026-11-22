package com.example.inbetween

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Keeps the Android frame-metrics listener attached only while the HUD is shown or a recording runs. Call once from the editor. */
@Composable
fun PerformanceEffects() {
    val view = LocalView.current
    val on = Perf.hudVisible || Perf.recording
    DisposableEffect(on) {
        val window = view.context.findActivity()?.window
        if (on && window != null) Perf.attachWindow(window, view.display?.refreshRate ?: 60f)
        onDispose { Perf.detachWindow() }
    }
}

/** Small persistent indicator while detailed recording runs, whether or not the HUD is visible. */
@Composable
fun PerformanceRecordingIndicator(modifier: Modifier = Modifier) {
    if (!Perf.recording) return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Perf.recording) { while (isActive && Perf.recording) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val s = ((now - Perf.recordingStartedElapsedMs) / 1000).coerceAtLeast(0)
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).clickable { Perf.setHudVisibility(true) }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        Text("  REC ${s / 60}:${(s % 60).toString().padStart(2, '0')}", fontSize = 11.sp, color = Color.White)
    }
}

/** Live performance HUD: numbers, recording controls, scenario, reset, export. It refreshes itself a few times a second, so the editor does not recompose with it. */
@Composable
fun PerformanceHud(modifier: Modifier = Modifier) {
    if (!Perf.hudVisible) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Perf.hudVisible) {
        while (isActive && Perf.hudVisible) {
            withContext(Dispatchers.Default) { Perf.buildSnapshot() }
            delay(400)
        }
    }
    val exportPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            // built and written on a background thread: never during playback on the UI thread
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = CrashDiagnostics.makeReport()
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Android could not open the selected destination")
                }
            }
            Perf.lastExportNote = if (result.isSuccess) "Report exported." else "Export failed: ${result.exceptionOrNull()?.message}"
            Toast.makeText(ctx, Perf.lastExportNote, Toast.LENGTH_LONG).show()
        }
    }
    val s = Perf.snapshot
    val pad = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    fun f(v: Float) = String.format(Locale.US, "%.1f", v)
    fun avgMax(st: Stats) = if (st.count == 0) "-" else "${f(st.avg)}/${f(st.max)}"
    val mono = FontFamily.Monospace
    var scenarioMenu by remember { mutableStateOf(false) }

    Column(
        modifier.width(300.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD14141A)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        @Composable fun line(t: String, c: Color = Color(0xFFE6E6EE)) = Text(t, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = mono, color = c)
        val jankColor = if (s.jankPct > 10f) Color(0xFFFF8A80) else if (s.jankPct > 3f) Color(0xFFFFD180) else Color(0xFFB9F6CA)
        line("FPS ${f(s.fps)}   frame ${f(s.frames.avg)} ms   p95 ${f(s.frames.p95)}   max ${f(s.frames.max)}")
        line("Jank ${f(s.jankPct)} %  (> ${f(s.budgetMs)} ms @ ${if (Perf.budgetHz > 0) Perf.budgetHz else s.refreshHz.toInt()} Hz)   dropped ${s.droppedFrames}", jankColor)
        line("CPU ${if (s.cpuPct < 0) "-" else f(s.cpuPct)} % of 1 core (${s.cpuCores} cores)")
        line("RAM java ${f(s.javaMb)}  native ${f(s.nativeMb)}  PSS ${if (s.pssMb < 0) "-" else f(s.pssMb)} MB")
        line("GC ${s.gcCount} (+${s.gcCountDelta})  ${s.gcMs} ms total")
        line("GL prep ${avgMax(s.glPrep)}  cmd ${avgMax(s.glCommands)} ms")
        line("GL explicit wait ${avgMax(s.glFinish)}  swap ${avgMax(s.glSwap)} ms")
        line("GL frames ${s.glFrames}  skipped ${s.glSkipped}  failed ${s.glFailed}  err ${s.glErrors}")
        line("Audio underruns ${s.audioUnderruns} (${s.audioPlayers} players)", if (s.audioUnderruns > 0) Color(0xFFFF8A80) else Color(0xFFE6E6EE))
        line("Video dropped ${s.videoDropped} (${s.videoPlayers} players)  errors ${s.mediaErrors}", if (s.videoDropped > 0) Color(0xFFFFD180) else Color(0xFFE6E6EE))
        line("Tick ${avgMax(s.tickProcessing)} ms  clock skipped ${s.tickMissedFrames} fr  drift p95 ${if (s.driftMs.count == 0) "-" else f(s.driftMs.p95)} ms")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (Perf.recording) Perf.stopRecording() else Perf.startRecording() }, contentPadding = pad) {
                Text(if (Perf.recording) "■ Stop" else "● Record", fontSize = 12.sp, color = if (Perf.recording) Color(0xFFFF8A80) else Color(0xFFE6E6EE))
            }
            TextButton({ Perf.reset() }, contentPadding = pad) { Text("Reset", fontSize = 12.sp) }
            TextButton({ exportPick.launch("superframe-performance-${System.currentTimeMillis() / 1000}.zip") }, contentPadding = pad) { Text("Export", fontSize = 12.sp) }
            TextButton({ Perf.setHudVisibility(false) }, contentPadding = pad) { Text("Hide", fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton({ scenarioMenu = true }, contentPadding = pad) { Text("Scenario: ${Perf.scenario.take(1)} ▾", fontSize = 12.sp) }
                DropdownMenu(scenarioMenu, { scenarioMenu = false }) {
                    PERF_SCENARIOS.forEach { name -> DropdownMenuItem({ Text(name) }, { Perf.scenario = name; scenarioMenu = false }) }
                }
            }
            Text("Budget", fontSize = 11.sp, color = Color(0xFFB0B0BA))
            listOf(0 to "Auto", 60 to "60", 90 to "90", 120 to "120").forEach { (hz, label) ->
                TextButton({ Perf.budgetHz = hz }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(label, fontSize = 12.sp, color = if (Perf.budgetHz == hz) Color(0xFFD0BCFF) else Color(0xFF9A9AA6))
                }
            }
        }
    }
}
