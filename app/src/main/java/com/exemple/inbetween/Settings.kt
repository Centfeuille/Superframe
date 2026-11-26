package com.example.inbetween

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

/** Space the tool panel takes on its side (right normally, left when left-handed). */
fun Modifier.toolSidePadding(w: Dp): Modifier = padding(start = if (AppSettings.leftHanded) w else 0.dp, end = if (AppSettings.leftHanded) 0.dp else w)

/**
 * App-wide user options (Settings screen). Every property is Compose state, so UI reading it updates at once; `save()` writes `settings.json`
 * in the app files directory (same pattern as keybinds.json). A missing file or key = the app default, which equals the behaviour the app had
 * before the option existed. Defaults must never be changed silently: they ARE the previous behaviour.
 *
 * Gesture / timing / cue options are read where the behaviour happens (`Canvas.kt`, `Panels.kt`), by plain property access, so they are cheap
 * to check. Keep the JSON keys stable: they are part of the settings file.
 */
object AppSettings {
    private const val FILE = "settings.json"
    private var dir: File? = null

    // ----- Layout -----
    /** Mirrors the side panels and every hand-placed overlay (tool panel, tool options, radial puck, Cut / camera panels, vector bars). */
    var leftHanded by mutableStateOf(false)
    /** Open the editor with the panels hidden (fullscreen). */
    var startFullscreen by mutableStateOf(false)
    /** Height (dp) the timeline has when the editor opens. */
    var timelineHeightDp by mutableFloatStateOf(DEFAULT_TIMELINE_DP)

    // ----- Gestures (finger) -----
    /** What a two-finger tap does: 0 nothing, 1 undo (default), 2 redo. */
    var twoFingerTap by mutableIntStateOf(1)
    var threeFingerScrub by mutableStateOf(true)
    var pinchReset by mutableStateOf(true)
    var onePan by mutableStateOf(true)

    // ----- Timings -----
    /** Hold time (ms) before a timeline frame / key / layer drag starts. */
    var celHoldMs by mutableIntStateOf(CEL_HOLD_MS.toInt())
    /** A second touch must land within this time (ms) of the first one lifting to start a timeline marquee selection. */
    var doubleTouchMs by mutableIntStateOf(350)
    /** Tap tolerance (screen px) of pen taps on reference layers: more movement than this and the touch is a drag. */
    var tapSlopPx by mutableFloatStateOf(TAP_SLOP_PX)

    // ----- Visual cues -----
    var cueTouchRings by mutableStateOf(true)
    var cueEdgeGlow by mutableStateOf(true)
    var cueNewFramePull by mutableStateOf(true)
    var cueHoverCursor by mutableStateOf(true)

    // ----- Appearance -----
    /** The workspace colour around the page (ARGB). */
    var workspaceColor by mutableIntStateOf(DEFAULT_WORKSPACE)
    val workspace: Color get() = Color(workspaceColor)

    // ----- Startup -----
    /** Open the Cut that was open last instead of the Cut list. */
    var openLastCut by mutableStateOf(false)
    var showWhatsNew by mutableStateOf(true)
    /** Id of the Cut that was open last (written by the app, not shown in the Settings screen). */
    var lastCutId by mutableStateOf(0L)

    // ----- Safety -----
    /** Ask before actions that cannot be undone: deleting a Cut, and a Cut resize / Stretch that clears Undo. */
    var confirmDestructive by mutableStateOf(false)

    const val DEFAULT_TIMELINE_DP = 190f
    const val DEFAULT_WORKSPACE = 0xFF1E1E22.toInt()

    fun load(d: File) {
        dir = d
        runCatching {
            val f = File(d, FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            leftHanded = o.optBoolean("leftHanded", leftHanded)
            startFullscreen = o.optBoolean("startFullscreen", startFullscreen)
            timelineHeightDp = o.optDouble("timelineHeightDp", timelineHeightDp.toDouble()).toFloat().coerceIn(96f, 520f)
            twoFingerTap = o.optInt("twoFingerTap", twoFingerTap).coerceIn(0, 2)
            threeFingerScrub = o.optBoolean("threeFingerScrub", threeFingerScrub)
            pinchReset = o.optBoolean("pinchReset", pinchReset)
            onePan = o.optBoolean("onePan", onePan)
            celHoldMs = o.optInt("celHoldMs", celHoldMs).coerceIn(80, 800)
            doubleTouchMs = o.optInt("doubleTouchMs", doubleTouchMs).coerceIn(150, 800)
            tapSlopPx = o.optDouble("tapSlopPx", tapSlopPx.toDouble()).toFloat().coerceIn(8f, 120f)
            cueTouchRings = o.optBoolean("cueTouchRings", cueTouchRings)
            cueEdgeGlow = o.optBoolean("cueEdgeGlow", cueEdgeGlow)
            cueNewFramePull = o.optBoolean("cueNewFramePull", cueNewFramePull)
            cueHoverCursor = o.optBoolean("cueHoverCursor", cueHoverCursor)
            workspaceColor = o.optInt("workspaceColor", workspaceColor)
            openLastCut = o.optBoolean("openLastCut", openLastCut)
            showWhatsNew = o.optBoolean("showWhatsNew", showWhatsNew)
            lastCutId = o.optLong("lastCutId", lastCutId)
            confirmDestructive = o.optBoolean("confirmDestructive", confirmDestructive)
            PenCalibration.load(o)
        }
    }

    fun save() {
        val d = dir ?: return
        runCatching {
            val o = JSONObject()
                .put("v", 1)
                .put("leftHanded", leftHanded).put("startFullscreen", startFullscreen).put("timelineHeightDp", timelineHeightDp.toDouble())
                .put("twoFingerTap", twoFingerTap).put("threeFingerScrub", threeFingerScrub).put("pinchReset", pinchReset).put("onePan", onePan)
                .put("celHoldMs", celHoldMs).put("doubleTouchMs", doubleTouchMs).put("tapSlopPx", tapSlopPx.toDouble())
                .put("cueTouchRings", cueTouchRings).put("cueEdgeGlow", cueEdgeGlow).put("cueNewFramePull", cueNewFramePull).put("cueHoverCursor", cueHoverCursor)
                .put("workspaceColor", workspaceColor)
                .put("openLastCut", openLastCut).put("showWhatsNew", showWhatsNew).put("lastCutId", lastCutId)
                .put("confirmDestructive", confirmDestructive)
            PenCalibration.write(o)
            val tmp = File(d, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(d, FILE))) { File(d, FILE).writeText(o.toString()); tmp.delete() }
        }
    }

    /** Restores every option to the app default (the lastCutId bookkeeping value is kept). */
    fun resetAll() {
        leftHanded = false; startFullscreen = false; timelineHeightDp = DEFAULT_TIMELINE_DP
        twoFingerTap = 1; threeFingerScrub = true; pinchReset = true; onePan = true
        celHoldMs = CEL_HOLD_MS.toInt(); doubleTouchMs = 350; tapSlopPx = TAP_SLOP_PX
        cueTouchRings = true; cueEdgeGlow = true; cueNewFramePull = true; cueHoverCursor = true
        workspaceColor = DEFAULT_WORKSPACE
        openLastCut = false; showWhatsNew = true
        confirmDestructive = false
        PenCalibration.reset()
        save()
    }
}
