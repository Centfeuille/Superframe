package com.example.inbetween

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A small text button for the vector widget bar (named options such as Fixed, Shift, Joints, Displace). */
@Composable
private fun BarChip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) Color(0xFFD0BCFF) else Color(0x33FFFFFF))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) { Text(label, fontSize = 12.sp, color = if (on) Color(0xFF21005D) else Color.White.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/**
 * One of the two bars shown side by side while the interpolation preview is up (the selection bar and the drag bar). With [both] the active bar is outlined
 * and the other one is dimmed: a tap anywhere on the dimmed bar calls [onActivate] (it does not press the button under the finger) and makes it the active function.
 * Without [both] the content is shown unchanged.
 */
@Composable
private fun BarSwitch(both: Boolean, active: Boolean, onActivate: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(if (both) Modifier.clip(shape).border(1.5.dp, if (active) Color(0xFFD0BCFF) else Color.Transparent, shape).graphicsLayer { alpha = if (active) 1f else 0.55f } else Modifier) {
        content()
        if (both && !active) Box(Modifier.matchParentSize().clickable { onActivate() })
    }
}

/**
 * The vector buttons on the timeline bar (shown while a vector layer is selected): Interpolation drag and Cutter. Vector drawing is the regular Brush tool and
 * point selection / editing is the regular Selection tool; their options are in [VectorWidgetBar].
 */
@Composable
fun VectorToolStrip(cut: Cut, ed: Editor, buttonSize: Dp, @Suppress("UNUSED_PARAMETER") targets: List<Cel>) {
    if (cut.layer?.vector != true) return
    // Drawing and point selection live on the regular Brush and Selection tools (their vector options are in the widget bar at the bottom of the canvas).
    IconBtn(Icons.Filled.SwapHoriz, size = buttonSize, on = ed.vecMode == 3, tip = "Interpolation drag: drag to place an in-between of the previous and the current keyframe. Its bar has Fixed, Smooth and the create button. Switch to the Selection tool to pick, transform, warp or displace the preview lines. Tap again to go back to drawing") {
        val here = ed.vecMode == 3 && ed.tool == 0 // from the Selection tool this returns to the drag tool instead of ending the mode
        ed.vecXfCancel(); ed.tool = 0; ed.vecMode = if (here) 0 else 3; ed.vecGuideSelected = -1
    }
    IconBtn(Icons.Filled.LinearScale, size = buttonSize, on = ed.vecInterpOpen, tip = "Interpolation settings of the selected keyframes (every keyframe after Select all): lines, curve, bake, and Off") { ed.vecInterpOpen = true }
    IconBtn(Icons.Filled.Waves, size = buttonSize, on = ed.vecWobbleOpen, tip = "Wobble: a different organic boil on each selected keyframe (or the current one)") { ed.vecWobbleOpen = !ed.vecWobbleOpen; if (ed.vecWobbleOpen) ed.vecInkOpen = false }
    IconBtn(Icons.Filled.Edit, size = buttonSize, on = ed.vecInkOpen, tip = "Line weight: curvature, light direction and tapers for the selected keyframes (or the current one)") { ed.vecInkOpen = !ed.vecInkOpen; if (ed.vecInkOpen) ed.vecWobbleOpen = false }
    IconBtn(Icons.Filled.ContentCut, size = buttonSize, on = ed.vecMode == 2 && ed.tool == 0, tip = "Cutter: draw across strokes to slice them. Tap again to go back to drawing") {
        val on = ed.vecMode == 2 && ed.tool == 0; ed.tool = 0; ed.vecMode = if (on) 0 else 2
    }
}

/**
 * The vector widget bar, bottom centre of the canvas. It follows the active vector tool: Point edit (and the Selection tool) get raise / lower,
 * falloff, select all / deselect / delete and the reference anchor; Interpolation drag gets confirm / reset and the interpolation settings.
 */
@Composable
fun VectorWidgetBar(cut: Cut, ed: Editor, modifier: Modifier = Modifier) {
    val layer = cut.layer?.takeIf { it.vector && it.visible } ?: return
    val selActive = ed.tool == 2
    val bothBars = ed.vecMode == 3 && (ed.tool == 0 || ed.tool == 2) // the interpolation preview is up: the selection bar and the drag bar are both shown; the active one is outlined and a tap on the other one makes it the active function
    val pointBar = selActive || bothBars
    val pointsMode = pointBar && ed.vecPoints && ed.vecMode != 3 // Selection tool, Points on: single points (the old Point edit); off: whole lines
    val dragBar = ed.vecMode == 3 && ed.tool == 0 // Interpolation drag
    val prevBar = pointBar && ed.vecMode == 3 // Selection tool while the interpolation preview is up: it picks and edits the PREVIEW lines
    val sess = pointBar && ed.vecXf != null // a Transform / Warp / Puppet Warp session is running
    val drawBar = ed.vecMode == 0 && ed.tool == 0
    val lineBar = ed.tool == 4 // Line / shapes tool
    val eraseBar = ed.tool == 1 // Eraser
    if (!pointBar && !dragBar && !drawBar && !lineBar && !eraseBar) return
    val ctx = LocalContext.current
    var falloffOpen by remember { mutableStateOf(false) }
    var smoothBarOpen by remember { mutableStateOf(false) } // interpolation drag: the smoothness slider is shown
    var anchorOpen by remember { mutableStateOf(false) }
    var smoothOpen by remember { mutableStateOf(false) }
    val idx = layer.indexAt(cut.time)
    val cel = layer.cels.getOrNull(idx)
    // read the selection state first and unconditionally: the bar must follow it (a short-circuited read left Anchor disabled until another state changed)
    val sel = ed.vecSel; val selCels = ed.vecSelCels
    val sc = vecScope(cut, ed.refAll)
    val has = sc != null && sc.sameCels(selCels) && sel.isNotEmpty()
    // Brush / Merge sliders flush with the left edge of the canvas, Auto anchors flush with the right edge, the button bar in the middle
    Box(modifier.fillMaxWidth()) {
    Column(Modifier.align(if (AppSettings.leftHanded) Alignment.BottomEnd else Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (((drawBar || lineBar) && ed.vecMerge) || (pointBar && ed.vecMode != 3)) Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(horizontal = 12.dp, vertical = 4.dp).width(250.dp)) { // Merge sliders: Selection tool with Points on or off (not on the interpolation preview)
        Text("Merge", fontSize = 11.sp, color = Color(0xFFB8B8C4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Radius", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(62.dp))
            Slider(value = ed.vecMergeRadius, onValueChange = { ed.vecMergeRadius = it.roundToInt().toFloat() }, valueRange = 4f..80f, modifier = Modifier.weight(1f).height(32.dp))
            Text("${ed.vecMergeRadius.roundToInt()} px", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(52.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sharp joins", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(62.dp))
            Slider(value = ed.vecMergeSharp, onValueChange = { ed.vecMergeSharp = (it * 20f).roundToInt() / 20f }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(32.dp))
            Text(if (ed.vecMergeSharp < 0.01f) "Round" else "${(ed.vecMergeSharp * 100).roundToInt()}%", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(52.dp))
        }
    }
    }
    Column(Modifier.align(if (AppSettings.leftHanded) Alignment.BottomStart else Alignment.BottomEnd)) {
    if ((drawBar || lineBar) && ed.vecAnchorOpen) Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(horizontal = 12.dp, vertical = 4.dp).width(250.dp)) {
        Text("Auto anchors", fontSize = 11.sp, color = Color(0xFFB8B8C4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Curve tips", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(62.dp))
            Slider(value = ed.vecTipSens, onValueChange = { ed.vecTipSens = (it * 20f).roundToInt() / 20f }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(32.dp))
            Text(if (ed.vecTipSens < 0.01f) "Sharp only" else "${(ed.vecTipSens * 100).roundToInt()}%", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(52.dp))
        }
    }
    }
    Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
    BarSwitch(bothBars, selActive, { ed.tool = 2 }) { // a tap on the dimmed selection bar makes the Selection tool the active function
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
        if (dragBar && !bothBars && smoothBarOpen) InterpSmoothRow(ed)
        if (sess) VecXformRows(ed)
        if (prevBar && !sess && ed.vecDisplace) Row(verticalAlignment = Alignment.CenterVertically) {
            // Displace on the preview: guide joints on the in-between's lines (stored with the first keyframe's interpolation, so they are kept when it is made)
            val jointSource = if (vecRefSelected(layer, ed, cut.time).isNotEmpty()) vecDragRefs(layer, ed, cut.time)?.a else layer.cels.getOrNull(idx - 1)
            val jointCfg = jointSource?.vecCfg
            val jointCount = jointCfg?.guideJoints?.size ?: 0
            Text(if (jointCount == 0) "Displace · tap a preview line to add a joint, then drag it" else "Displace · $jointCount joint${if (jointCount == 1) "" else "s"} (tap a selected joint again to delete it)", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            IconBtn(Icons.Filled.Delete, on = jointCount > 0, tip = "Delete the guide joints of the picked preview lines (of every line when none is picked)") {
                if (jointSource != null && jointCfg != null && jointCfg.guideJoints.isNotEmpty()) {
                    val picked = ed.vecPreSel
                    cut.record { jointSource.vecCfg = jointCfg.copy(guideJoints = if (picked.isEmpty()) emptyList() else jointCfg.guideJoints.filter { it.strokeId !in picked }); ed.vecGuideSelected = -1 }
                }
            }
        }
        if (pointBar && !sess && !prevBar && ed.vecDisplace) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (!has) "Displace · select lines (or points) first" else if (ed.vecDisplaceJoints.isEmpty()) "Displace · tap a selected line to add a joint, then drag it" else "Displace · ${ed.vecDisplaceJoints.size} joint${if (ed.vecDisplaceJoints.size == 1) "" else "s"}", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            IconBtn(Icons.Filled.Delete, on = ed.vecDisplaceJoints.isNotEmpty(), tip = "Delete all joints of the selected lines (tap a selected joint a second time to delete just that one)") {
                val pickedIds = if (sc != null) sel.map { vecKeyStroke(it) }.toSet().mapNotNull { sc.strokes.getOrNull(it)?.id }.toSet() else emptySet()
                ed.vecDisplaceJoints = ed.vecDisplaceJoints.filter { it.strokeId !in pickedIds }; ed.vecDisplaceSelected = -1
            }
            IconBtn(Icons.Filled.Refresh, tip = "Remove all joints (the selection goes back to how it was)") { ed.vecDisplaceJoints = emptyList(); ed.vecDisplaceSelected = -1 }
            IconBtn(Icons.Filled.Check, tip = "Apply the displacement to the selection (one undo step)") {
                if (sc != null && has && ed.vecDisplaceJoints.isNotEmpty()) {
                    val edited = displaceSelection(sc.strokes, sel, ed.vecDisplaceJoints, !ed.vecPoints)
                    applyScope(cut, sc, sc.split(edited))
                    val pickedLines = sel.map { vecKeyStroke(it) }.toSet()
                    ed.vecSel = if (ed.vecPoints) sel else edited.indices.filter { it in pickedLines }.flatMap { si -> edited[si].points.indices.map { vecKey(si, it) } }.toSet() // a warped line has more points: keep the whole line selected
                    ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
                    ed.vecDisplaceJoints = emptyList(); ed.vecDisplaceSelected = -1
                }
            }
        }
        if (pointBar && !sess) Row(verticalAlignment = Alignment.CenterVertically) {
            // the regular selection widgets (as in SelectionBar), working on the vector selection (or, while the interpolation preview is up, on the preview lines)
            IconBtn(Icons.Filled.Add, ed.selOp == 1, tip = "Add to the selection (or hold Shift)") { ed.selOp = if (ed.selOp == 1) 0 else 1 }
            IconBtn(Icons.Filled.Remove, ed.selOp == 2, tip = "Erase from the selection (or hold Alt / Ctrl)") { ed.selOp = if (ed.selOp == 2) 0 else 2 }
            if (!prevBar) IconBtn(Icons.Filled.InvertColors, tip = "Invert the selection") {
                if (sc != null) { ed.vecSelCels = sc.cels; ed.vecSel = VectorEdit.allPoints(sc.strokes) - (if (has) sel else emptySet()) }
            }
            if (!prevBar) IconBtn(Icons.Filled.ContentCopy, tip = "Copy the selected lines (in Points mode the selected stretches), then paste them on this or any other vector layer or frame") { toastMessage(ctx, SelectionClipboard.copy(cut, ed)) }
            if (!prevBar) IconBtn(Icons.Filled.ContentPaste, SelectionClipboard.item != null, tip = "Paste the copied selection onto this layer and keyframe") { toastMessage(ctx, SelectionClipboard.paste(cut, ed)) }
            IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio of the transform handles") { ed.xfLock = !ed.xfLock }
            MagnetBtn(ed)
            if (!prevBar) IconBtn(Icons.Filled.OpenWith, tip = "Transform the selected lines (or points): box handles, Warp and Puppet Warp, then Apply") { ed.vecXfBegin() }
            if (prevBar) IconBtn(Icons.Filled.OpenWith, tip = "Transform the preview (or the picked preview lines): box handles, Warp and Puppet Warp, then Apply") { ed.vecXfBegin() }
            IconBtn(Icons.Filled.Check, tip = if (prevBar) "Done: pick nothing (the whole preview)" else "Done: clear the selection") { if (prevBar) ed.vecPreSel = emptySet() else ed.vecSel = emptySet() }
        }
        if (!sess) Row(verticalAlignment = Alignment.CenterVertically) {
            if (drawBar || lineBar) {
                VIconBtn(Icons.Filled.MergeType, on = ed.vecMerge, tip = if (ed.vecMerge) "Merge: on. Lines you draw join other lines within the Merge radius (tap to turn off)" else "Merge: off. Tap to join lines you draw to other lines within the Merge radius") { ed.vecMerge = !ed.vecMerge }
                VIconBtn(Icons.Filled.Anchor, on = ed.vecAutoAnchor, tip = if (ed.vecAutoAnchor) "Auto anchors: on. A new line gets numbered anchors at its ends and curve tips, and keeps them up to date when lines merge (tap to turn off)" else "Auto anchors: off. Tap to give new lines anchors at their ends and curve tips") { ed.vecAutoAnchor = !ed.vecAutoAnchor }
                VIconBtn(GhostFadeIcon, on = ed.vecGhost, tip = if (ed.vecGhost) "Ghost: on. The next line of the ${if (ed.vecGhostNext) "next" else "previous"} keyframe shimmers; draw over it and your line takes its anchors, so the two match (tap to turn off)" else "Ghost: off. Tap to show the next line to trace from the previous or next keyframe") { ed.vecGhost = !ed.vecGhost; ed.vecGhostPin = null }
                if (ed.vecGhost) {
                    VIconBtn(if (ed.vecGhostNext) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious, tip = if (ed.vecGhostNext) "Ghost from the NEXT keyframe (tap for the previous one)" else "Ghost from the PREVIOUS keyframe (tap for the next one)") { ed.vecGhostNext = !ed.vecGhostNext; ed.vecGhostPin = null }
                    VIconBtn(Icons.Filled.NavigateBefore, tip = "Previous ghost line, traced ones included (they are yellow-orange) so you can go back and extend one (shortcut in Settings > Keyboard shortcuts)") { cut.layer?.let { l -> LineGhost.skip(ed, l, l.indexAt(cut.time), -1) } }
                    VIconBtn(Icons.Filled.NavigateNext, tip = "Next ghost line. The ghost stays on a line after you draw it, so a line can be drawn in several strokes; skip when it is done. Traced lines stay on the ghost in yellow-orange (shortcut in Settings > Keyboard shortcuts)") { cut.layer?.let { l -> LineGhost.skip(ed, l, l.indexAt(cut.time), 1) } }
                    VIconBtn(Icons.Filled.LinkOff, tip = "Unclaim the ghost line now shown: if it is yellow-orange (already traced) by mistake, its anchor numbers are taken off the lines that carry them so the next stroke traces it again (one undo step)") {
                        val l = cut.layer
                        val done = l != null && LineGhost.unclaim(ed, cut, l)
                        android.widget.Toast.makeText(ctx, if (done) "Ghost line unclaimed" else "Nothing claims this ghost line", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    VIconBtn(Icons.Filled.TouchApp, on = ed.vecGhostPick, tip = if (ed.vecGhostPick) "Select ghost line: on. Tap the ghost line you want with the pen (tap here again to cancel)" else "Select ghost line: tap it on the canvas with the pen and it becomes the ghost line that is shown and traced, traced ones included") { ed.vecGhostPick = !ed.vecGhostPick }
                }
                VIconBtn(Icons.Filled.Tune, on = ed.vecAnchorOpen, tip = "Auto anchor options: how gentle a turn counts as a curve tip") { ed.vecAnchorOpen = !ed.vecAnchorOpen }
            } else if (eraseBar) {
                VIconBtn(Icons.Filled.ScatterPlot, on = ed.vecErasePoints, tip = if (ed.vecErasePoints) "Erase points: on. The eraser deletes only the points under the pen and the line stays joined (tap for the normal eraser)" else "Erase points: off. The eraser removes the part of a line up to the next crossings (tap to erase only points)") { ed.vecErasePoints = !ed.vecErasePoints }
            } else if (prevBar) {
                // Selection tool on the interpolation preview: the preview lines are picked with the pen (tap a line, or drag a rectangle over several)
                Text(if (ed.vecPreSel.isEmpty()) "Preview · all lines (tap or drag over lines to pick some)" else "Preview · ${ed.vecPreSel.size} line${if (ed.vecPreSel.size == 1) "" else "s"} picked", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                VIconBtn(Icons.Filled.LineWeight, on = ed.vecLockWeight, tip = if (ed.vecLockWeight) "Lock weight: on. Transforming keeps the line weight" else "Lock weight: off. Transforming scales the line weight too") { ed.vecLockWeight = !ed.vecLockWeight }
                IconBtn(Icons.Filled.Refresh, tip = "Undo every transform / warp made on the preview and pick nothing") { ed.vecPlaceAll = Aff.ID; ed.vecPlaceLines = emptyMap(); ed.vecPlaceSteps = emptyList(); ed.vecPreSel = emptySet() }
            } else if (pointBar) {
                VIconBtn(Icons.Filled.ScatterPlot, on = ed.vecPoints, tip = if (ed.vecPoints) "Points: on. Pick and edit single points (tap to select whole lines instead)" else "Points: off. Whole lines are selected (tap to pick single points)") { ed.vecPoints = !ed.vecPoints }
                // One button, three views of the points: all of them -> only the anchor points -> none (the lines stay; selecting and editing work as before)
                VIconBtn(when (ed.vecPointView) { 1 -> Icons.Filled.Anchor; 2 -> Icons.Filled.VisibilityOff; else -> Icons.Filled.Visibility }, on = ed.vecPointView != 0,
                    tip = when (ed.vecPointView) {
                        1 -> "Points view: only the anchor points are shown (tap to hide all points)"
                        2 -> "Points view: no points are shown (tap to show all points)"
                        else -> "Points view: all points are shown (tap to show only the anchor points)"
                    }) { ed.vecPointView = (ed.vecPointView + 1) % 3 }
                VIconBtn(Icons.Filled.Layers, on = ed.refAll, tip = if (ed.refAll) "All layers: on. The selection works on every visible, unlocked vector layer" else "All layers: off. The selection works on the active vector layer only") { ed.refAll = !ed.refAll }
                VIconBtn(Icons.Filled.ContentCut, on = ed.vecSelCut, tip = if (ed.vecSelCut) "Cut: on. Draw across lines to slice them; each cut makes new end anchors, the other ends keep theirs" else "Cut: draw across lines to slice them (new end anchors where cut)") { ed.vecSelCut = !ed.vecSelCut; if (ed.vecSelCut) { ed.vecDisplace = false; ed.vecDisplaceJoints = emptyList(); ed.vecDisplaceSelected = -1 } }
                VIconBtn(Icons.Filled.LineWeight, on = ed.vecLockWeight, tip = if (ed.vecLockWeight) "Lock weight: on. Transforming keeps the line weight" else "Lock weight: off. Transforming scales the line weight too") { ed.vecLockWeight = !ed.vecLockWeight }
                if (pointsMode) {
                    IconBtn(Icons.Filled.SwapVert, on = ed.vecRaise, tip = "Raise / lower points: drag up or down") { ed.vecRaise = !ed.vecRaise }
                    VIconBtn(Icons.Filled.Adjust, on = ed.vecFalloff >= 1f, tip = if (ed.vecFalloff < 1f) "Proportional falloff: off (tap to set the radius)" else "Proportional falloff: ${ed.vecFalloff.roundToInt()} px (tap to change)") { falloffOpen = true }
                }
                VIconBtn(Icons.Filled.SelectAll, tip = "Select all") { if (sc != null) { ed.vecSelCels = sc.cels; ed.vecSel = VectorEdit.allPoints(sc.strokes) } }
                VIconBtn(Icons.Filled.Deselect, enabled = has, tip = "Deselect") { ed.vecSel = emptySet() }
                VIconBtn(Icons.Filled.Delete, enabled = has, tip = "Delete the selected points") {
                    if (sc != null) {
                        val parts = sc.perCel.indices.map { c -> val lk = sc.localKeys(sel, c); if (lk.isEmpty()) sc.perCel[c] else VectorEdit.delete(sc.perCel[c], lk).let { if (ed.vecAutoAnchor) AutoAnchors.refreshFor(sc.layers[c], sc.cels[c], it, ed.vecTipSens, ed.vecGhostNext, ed) else VectorMerge.joinLinkedEnds(it).first } }
                        ed.vecSel = emptySet()
                        applyScope(cut, sc, parts)
                        ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
                    }
                }

                VIconBtn(Icons.Filled.Link, enabled = has, tip = if (pointsMode) "Merge the selected points: two points join whatever the distance, one point close to another line snaps onto it (within the Merge radius)" else "Merge the selected lines: an end close to another line's end joins it, an end close to the body of a line snaps onto it (within the Merge radius)") {
                    if (sc != null) {
                        val tol = ed.vecMergeRadius / ed.vecDocZoom.coerceAtLeast(0.0001f)
                        val parts = sc.perCel.indices.map { c ->
                            val lk = sc.localKeys(sel, c)
                            if (lk.isEmpty()) sc.perCel[c] else (VectorMerge.mergePoints(sc.perCel[c], lk, tol, ed.vecMergeSharp) ?: sc.perCel[c]).let { if (ed.vecAutoAnchor) AutoAnchors.refreshFor(sc.layers[c], sc.cels[c], it, ed.vecTipSens, ed.vecGhostNext, ed) else it }
                        }
                        if (parts.indices.any { parts[it] !== sc.perCel[it] }) {
                            ed.vecSel = emptySet()
                            applyScope(cut, sc, parts)
                            ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
                        } else android.widget.Toast.makeText(ctx, if (pointsMode) "Nothing to merge: select two points to merge them, or one point close to another line (within the Merge radius)" else "Nothing to merge: no end of the selected lines is within the Merge radius of another line", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                if (pointsMode) {
                    val allHidden = sc != null && has && sel.all { k -> sc.strokes.getOrNull(vecKeyStroke(k))?.points?.getOrNull(vecKeyPoint(k))?.hidden == true }
                    VIconBtn(if (allHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, enabled = has, tip = if (allHidden) "Show the selected points" else "Hide the selected points (the line has no width there; they stay editable)") {
                        if (sc != null) {
                            val target = !allHidden
                            val parts = sc.perCel.indices.map { c ->
                                val lk = sc.localKeys(sel, c)
                                if (lk.isEmpty()) sc.perCel[c] else sc.perCel[c].mapIndexed { si, s ->
                                    if (s.points.indices.none { lk.contains(vecKey(si, it)) && s.points[it].hidden != target }) s
                                    else s.copy(points = s.points.mapIndexed { pi, p -> if (lk.contains(vecKey(si, pi)) && p.hidden != target) p.copy(hidden = target) else p })
                                }
                            }
                            applyScope(cut, sc, parts)
                            ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
                        }
                    }

                }
                VIconBtn(Icons.Filled.AutoFixHigh, enabled = has, tip = "Smooth the selected points") { smoothOpen = true }
                VIconBtn(Icons.Filled.Anchor, enabled = has, tint = Color(anchorColor(1, 3)), tip = "Anchor: number the selected points so the same numbers in the next keyframe are interpolated toward each other") { if (sc != null && !autoAnchor(cut, ed, sc)) anchorOpen = true }
            } else {
                InterpDragContent(cut, ed, layer, idx, cel, ctx, smoothBarOpen) { smoothBarOpen = !smoothBarOpen }
            }
        }
    }
    }
    if (prevBar && !sess) BarSwitch(bothBars, !selActive, { ed.vecXfCancel(); ed.tool = 0; ed.vecMode = 3 }) { // `tool = 0` from another tool ends the vector modes (Editor.tool setter), so Interpolation drag (vecMode 3) is set again afterwards; the drag controls stay next to the selection bar; a tap on the dimmed drag bar makes Interpolation drag the active function
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
        if (smoothBarOpen) InterpSmoothRow(ed)
        Row(verticalAlignment = Alignment.CenterVertically) { InterpDragContent(cut, ed, layer, idx, cel, ctx, smoothBarOpen) { smoothBarOpen = !smoothBarOpen } }
    }
    }
    }
    }
    if (falloffOpen) AlertDialog(
        onDismissRequest = { falloffOpen = false },
        title = { Text("Proportional falloff") },
        text = {
            Column {
                Text("Points within this radius of a selected point follow it with a smooth falloff (0 = only the selected points).", fontSize = 12.sp)
                Slider(value = ed.vecFalloff, onValueChange = { ed.vecFalloff = it.roundToInt().toFloat() }, valueRange = 0f..200f)
                Text("${ed.vecFalloff.roundToInt()} px", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ falloffOpen = false }) { Text("Done") } }
    )
    if (anchorOpen) VectorAnchorDialog(cut, ed) { anchorOpen = false }
    if (smoothOpen) VectorSmoothDialog(cut, ed) { smoothOpen = false }
}

/** The interpolation drag controls (In-between %, Back to the middle, Fixed, Smooth, Reset, settings, Apply): the drag bar, and the second bar next to the Selection bar while the preview is up. */
@Composable
private fun InterpDragContent(cut: Cut, ed: Editor, layer: Layer, idx: Int, cel: Cel?, ctx: android.content.Context, smoothBarOpen: Boolean, onSmooth: () -> Unit) {
    // Reference frames: the frames picked in the timeline OTHER than the current one (which needs no selection). One or two are the ends of the interpolation and the
    // result lands in the current frame; none = the previous and the current keyframe (a new frame is created).
    val refPicked = vecRefSelected(layer, ed, cut.time)
    val refs = vecDragRefs(layer, ed, cut.time)
    val byRefs = refPicked.isNotEmpty()
    val badFrames = refPicked.size > 2
    val ready = if (byRefs) refs != null else idx >= 1 && cel != null
    val lineSel = if (refs != null) vecSelectedLinesIn(ed, refs.b) else null
    val selLines = if (byRefs) (lineSel?.size ?: 0) else if (ready) vecSelectedLineIndices(cut, ed, cel).size else 0
    val linesNote = if (selLines > 0) " · $selLines selected line${if (selLines == 1) "" else "s"}" else if (byRefs && lineSel != null) " · selected line not found in the last reference frame" else ""
    Text(
        if (byRefs && refs != null) "In-between ${(ed.dragTShown() * 100).roundToInt()}% → current frame (${refPicked.size} reference frame${if (refPicked.size == 1) "" else "s"})" + linesNote
        else if (badFrames) "Pick one or two reference frames (the current frame needs no selection), or none"
        else if (byRefs) "A reference frame needs a keyframe on the other side of the current frame"
        else if (ready) "In-between ${(ed.dragTShown() * 100).roundToInt()}%" + linesNote + (if (ed.vecPreSel.isNotEmpty()) " · ${ed.vecPreSel.size} preview line${if (ed.vecPreSel.size == 1) "" else "s"} refPicked" else "") else "Needs a previous keyframe",
        fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
    IconBtn(Icons.Filled.Refresh, tip = "Back to the middle (and the preview back to where it was)") { ed.vecDragT = 0.5f; ed.resetPlace(); ed.vecPreSel = emptySet() }
    BarChip("Fixed", ed.vecDragFixed) { ed.vecDragFixed = !ed.vecDragFixed; ed.vecFixedAll.clear(); ed.vecFixedLines.clear() } // Fixed pins the preview where it is now
    VIconBtn(Icons.Filled.LineWeight, on = ed.vecLockWeight, tip = if (ed.vecLockWeight) "Lock weight: on. Transforming the preview (Selection tool) keeps the line weight" else "Lock weight: off. Scaling the preview scales the line weight too") { ed.vecLockWeight = !ed.vecLockWeight }
    BarChip("Smooth", smoothBarOpen || ed.vecSmoothAll > 0.001f || ed.vecSmoothLines.isNotEmpty()) { onSmooth() }
    if (!ed.vecPlaceAll.isIdentity || ed.vecPlaceLines.isNotEmpty() || ed.vecPlaceSteps.isNotEmpty() || ed.vecSmoothAll > 0.001f || ed.vecSmoothLines.isNotEmpty() || ed.vecDragTLines.isNotEmpty()) {
        // Reset: the picked lines go back to what the interpolation makes (position, smoothness, transforms and warps); with nothing picked, the whole preview does
        BarChip("Reset", false) {
            val picked = ed.vecPreSel
            if (picked.isEmpty()) ed.resetPlace()
            else {
                ed.vecPlaceLines = ed.vecPlaceLines - picked; ed.vecSmoothLines = ed.vecSmoothLines - picked; ed.vecDragTLines = ed.vecDragTLines - picked
                ed.vecPlaceSteps = ed.vecPlaceSteps.filter { st -> st.ids?.none { it in picked } ?: true }
            }
        }
    }
    IconBtn(Icons.Filled.LinearScale, on = ed.vecInterpOpen, tip = "Interpolation settings: lines, curve, bake, and Off") { ed.vecInterpOpen = true }
    IconBtn(Icons.Filled.Check, tip = if (byRefs) "Apply: the interpolation of the reference frames replaces the current frame (no frame is created)" else if (ed.rippleRetime) "Create the frame: it takes its time from the previous frame, else from the current frame (layer length kept)" else "Create the frame: it takes its time from the previous frame, else the current frame and the ones after it are pushed later") {
        if (byRefs && refs != null) {
            if (interpolateIntoFrame(cut, ed, layer, refs.a, refs.m, refs.b, ed.vecDragT, lineSel)) { ed.vecDragT = 0.5f; ed.resetPlace(); ed.vecPreSel = emptySet() }
        } else if (badFrames || byRefs) android.widget.Toast.makeText(ctx, "Pick one or two reference frames (a single one needs a keyframe on the other side of the current frame); the current frame receives the in-between", android.widget.Toast.LENGTH_SHORT).show()
        else if (createDraggedInbetween(cut, layer, cut.time, ed.vecDragT, ed.rippleRetime, vecSelectedLineIndices(cut, ed, cel), ed.inbetweenPlace())) { ed.vecDragT = 0.5f; ed.resetPlace(); ed.vecPreSel = emptySet() }
        else android.widget.Toast.makeText(ctx, "Move to a keyframe that has a previous keyframe", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The smoothness slider of the interpolation drag bar. */
@Composable
private fun InterpSmoothRow(ed: Editor) {
    Row(verticalAlignment = Alignment.CenterVertically) {
            // Smoothness of the picked preview lines (every line when none is picked): evens out the curves between the points without adding any
            val pickedNow = ed.vecPreSel
            val shownValue = if (pickedNow.isEmpty()) ed.vecSmoothAll else (pickedNow.firstOrNull()?.let { ed.vecSmoothLines[it] } ?: ed.vecSmoothAll)
            Text(if (pickedNow.isEmpty()) "Smoothness · all lines" else "Smoothness · ${pickedNow.size} picked line${if (pickedNow.size == 1) "" else "s"}", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            Slider(value = shownValue, onValueChange = { v ->
                if (pickedNow.isEmpty()) { ed.vecSmoothAll = v; ed.vecSmoothLines = emptyMap() }
                else ed.vecSmoothLines = ed.vecSmoothLines + pickedNow.associateWith { v }
            }, valueRange = 0f..1f, modifier = Modifier.width(200.dp).height(30.dp))
            Text("${(shownValue * 100).roundToInt()}%  ·  no points added", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** The interpolation dialog, opened by the timeline button or the drag bar. Composed from the canvas so it works with any vector tool (the widget bar is not always shown). */
@Composable
fun VectorInterpolationHost(cut: Cut, ed: Editor) {
    if (!ed.vecInterpOpen) return
    val layer = cut.layer?.takeIf { it.vector } ?: return
    VectorInterpolationDialog(cut, ed, layer, vecInterpTargets(cut, ed, layer)) // the selected keyframes, every keyframe after Select all, else the span at the playhead
}

/** The Wobble bar (bottom centre): sets the boil of the selected keyframes, or of the current keyframe when none is selected. Each keyframe gets its own noise. */
@Composable
fun VectorWobbleBar(cut: Cut, ed: Editor, modifier: Modifier = Modifier) {
    val layer = cut.layer?.takeIf { it.vector && it.visible } ?: return
    if (!ed.vecWobbleOpen) return
    val idx = layer.indexAt(cut.time)
    val picked = ed.selCels.filter { c -> layer.cels.any { it === c } }
    val targets = picked.ifEmpty { listOfNotNull(layer.cels.getOrNull(idx)) }
    val cur = targets.firstOrNull()?.vectorStrokes()?.firstOrNull { it.wobble > 0f }
    // the sliders are quadratic (fine steps at the low end): amount 0.05..12 px, scale 2..200 px
    val aMin = 0.05f; val aMax = 12f; val sMin = 2f; val sMax = 200f
    var amount by remember(targets.firstOrNull()) { mutableStateOf((cur?.wobble ?: 3f).coerceIn(aMin, aMax)) }
    var scale by remember(targets.firstOrNull()) { mutableStateOf((cur?.wobbleScale ?: 60f).coerceIn(sMin, sMax)) }
    fun run(a: Float, reseed: Boolean) {
        val now = setVectorWobble(cut, layer, targets, a, scale, reseed)
        if (picked.isNotEmpty()) { ed.selCels.clear(); ed.selCels.addAll(now) }
    }
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(horizontal = 12.dp, vertical = 6.dp).widthIn(min = 300.dp, max = 380.dp)) {
        Text("Wobble · ${targets.size} keyframe${if (targets.size == 1) "" else "s"}${if (cur != null) " (on)" else ""}", fontSize = 11.sp, color = Color(0xFFB8B8C4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Amount", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(62.dp))
            Slider(value = kotlin.math.sqrt((amount - aMin) / (aMax - aMin)), onValueChange = { amount = ((aMin + (aMax - aMin) * it * it) * 100f).roundToInt() / 100f }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(32.dp))
            Text(if (amount < 1f) "%.2f px".format(amount) else "%.1f px".format(amount), fontSize = 11.sp, color = Color.White, modifier = Modifier.width(52.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scale", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(62.dp))
            Slider(value = kotlin.math.sqrt((scale - sMin) / (sMax - sMin)), onValueChange = { scale = (sMin + (sMax - sMin) * it * it).roundToInt().toFloat() }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(32.dp))
            Text("${scale.roundToInt()} px", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(52.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { run(amount, false) }, enabled = targets.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Apply", fontSize = 12.sp) }
            TextButton(onClick = { run(amount, true) }, enabled = targets.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("New look", fontSize = 12.sp) }
            TextButton(onClick = { run(0f, false) }, enabled = cur != null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Off", fontSize = 12.sp) }
            TextButton(onClick = { ed.vecWobbleOpen = false }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Close", fontSize = 12.sp) }
        }
    }
}

/** A circle with a line and an arrow: the direction the light comes FROM. Tap or drag anywhere on it; snaps to 15 degrees. 0 = right, 90 = below, 180 = left, 270 = above. */
@Composable
private fun LightDial(angle: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ring = Color(0xFF8A8A98)
    fun pick(size: androidx.compose.ui.unit.IntSize, o: androidx.compose.ui.geometry.Offset) {
        var a = Math.toDegrees(kotlin.math.atan2(o.y - size.height / 2f, o.x - size.width / 2f).toDouble()).toFloat()
        if (a < 0f) a += 360f
        onChange(((a / 15f).roundToInt() * 15f) % 360f)
    }
    androidx.compose.foundation.Canvas(
        modifier.size(84.dp)
            .pointerInput(Unit) { detectTapGestures { pick(size, it) } }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { pick(size, it) }, onDrag = { ch, _ -> pick(size, ch.position) }) }
    ) {
        val c = center
        val r = size.minDimension / 2f - 3.dp.toPx()
        drawCircle(Color(0x22FFFFFF), radius = r)
        drawCircle(ring, radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        for (k in 0 until 8) { // ticks every 45 degrees
            val t = Math.toRadians(k * 45.0).toFloat()
            val major = k % 2 == 0
            val r0 = r - (if (major) 7.dp.toPx() else 4.dp.toPx())
            drawLine(ring, androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(t) * r0, c.y + kotlin.math.sin(t) * r0), androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(t) * r, c.y + kotlin.math.sin(t) * r), strokeWidth = 1.dp.toPx())
        }
        val t = Math.toRadians(angle.toDouble()).toFloat()
        val dx = kotlin.math.cos(t); val dy = kotlin.math.sin(t)
        val tip = androidx.compose.ui.geometry.Offset(c.x + dx * (r - 4.dp.toPx()), c.y + dy * (r - 4.dp.toPx()))
        drawLine(accent, c, tip, strokeWidth = 2.5.dp.toPx())
        val h = 9.dp.toPx() // arrow head, two short strokes swept back from the tip
        for (side in intArrayOf(-1, 1)) {
            val ba = t + Math.PI.toFloat() + side * 0.5f
            drawLine(accent, tip, androidx.compose.ui.geometry.Offset(tip.x + kotlin.math.cos(ba) * h, tip.y + kotlin.math.sin(ba) * h), strokeWidth = 2.5.dp.toPx())
        }
        drawCircle(accent, radius = 3.dp.toPx(), center = c)
    }
}

/** Slider position (0..1) of the Line weight bar's anti-aliasing slider -> (aa, extra softness px): the left part is off (hard, aliased edge), the middle is the standard anti-aliased edge, the right part adds a soft feather up to 3 px. */
private fun aaFromSlider(v: Float): Pair<Float, Float> = if (v < 0.17f) 0f to 0f else if (v < 0.5f) 1f to 0f else 1f to ((v - 0.5f) / 0.5f * 3f * 10f).roundToInt() / 10f
private fun aaToSlider(aa: Float, soft: Float): Float = if (aa <= 0.02f) 0.05f else if (soft > 0.001f) 0.5f + (soft / 3f).coerceIn(0f, 1f) * 0.5f else 0.33f
private fun aaLabel(v: Float): String = aaFromSlider(v).let { (aa, soft) -> if (aa == 0f) "Off" else if (soft < 0.05f) "Standard" else "+%.1f px".format(soft) }

/**
 * The Line weight bar (bottom left, drawn at 75% size): designed anime-style weight of vector lines, stored per line and applied when the outline is drawn (live,
 * non-destructive). Preview shows the current settings on the canvas without changing the drawing; Apply / All frames write them (one undo step).
 */
@Composable
fun VectorInkBar(cut: Cut, ed: Editor, modifier: Modifier = Modifier) {
    val layer = cut.layer?.takeIf { it.vector && it.visible } ?: return
    if (!ed.vecInkOpen) return
    val ctx = LocalContext.current
    val idx = layer.indexAt(cut.time)
    val picked = ed.selCels.filter { c -> layer.cels.any { it === c } }
    val targets = picked.ifEmpty { listOfNotNull(layer.cels.getOrNull(idx)) }
    val cur = targets.firstOrNull()?.vectorStrokes()?.firstOrNull { it.fill == 0 && it.hasInk() }
    val first = targets.firstOrNull()?.vectorStrokes()?.firstOrNull { it.fill == 0 }
    var curve by remember(targets.firstOrNull()) { mutableStateOf(cur?.inkCurve ?: 0.5f) }
    var light by remember(targets.firstOrNull()) { mutableStateOf(cur?.inkLight ?: 0f) }
    var tIn by remember(targets.firstOrNull()) { mutableStateOf(cur?.taperIn ?: 0.15f) }
    var tOut by remember(targets.firstOrNull()) { mutableStateOf(cur?.taperOut ?: 0.25f) }
    var irr by remember(targets.firstOrNull()) { mutableStateOf(cur?.inkIrr ?: 0f) }
    var irrScale by remember(targets.firstOrNull()) { mutableStateOf(cur?.inkIrrScale ?: 40f) }
    var aaV by remember(targets.firstOrNull()) { mutableStateOf(aaToSlider(first?.aa ?: 1f, first?.aaSoft ?: 0f)) }
    var aaTouched by remember(targets.firstOrNull()) { mutableStateOf(false) }
    var angle by remember { mutableStateOf(ed.inkAngleCut) }
    var preview by remember { mutableStateOf(false) }
    fun params(): InkParams { val (aa, soft) = aaFromSlider(aaV); return InkParams(curve, light, angle, tIn, tOut, irr, irrScale, if (aaTouched) aa else null, soft) }
    // The preview follows every change; it only draws, nothing is written to the document until Apply.
    androidx.compose.runtime.SideEffect { if (preview && !cut.playing) VectorInkPreview.set(layer.id, targets, params()) else VectorInkPreview.clear() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { VectorInkPreview.clear() } }
    fun run(p: InkParams, all: Boolean) {
        ed.inkAngleCut = angle
        val set = if (all) layer.cels.toList() else targets
        VectorInkPreview.clear() // the real lines take over; the preview is switched on again by the effect while Preview stays on
        val now = setVectorInk(cut, layer, set, p)
        if (!all && picked.isNotEmpty()) { ed.selCels.clear(); ed.selCels.addAll(now) }
        if (all) android.widget.Toast.makeText(ctx, "Line weight set on ${now.size} keyframes", android.widget.Toast.LENGTH_SHORT).show()
    }
    @Composable
    fun row(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, set: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = Color.White, modifier = Modifier.width(78.dp))
            Slider(value = value, onValueChange = set, valueRange = range, modifier = Modifier.weight(1f).height(32.dp))
            Text(text, fontSize = 11.sp, color = Color.White, modifier = Modifier.width(56.dp))
        }
    }
    Column(
        modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.75f; transformOrigin = TransformOrigin(0f, 1f) } // 25% smaller, anchored at its bottom-left corner
            .clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(horizontal = 12.dp, vertical = 6.dp).widthIn(min = 300.dp, max = 400.dp)
            .heightIn(max = 420.dp).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Line weight · ${targets.size} keyframe${if (targets.size == 1) "" else "s"}${if (cur != null) " (on)" else ""}", fontSize = 11.sp, color = Color(0xFFB8B8C4), modifier = Modifier.weight(1f))
            ToggleChip("Preview", preview) { preview = !preview }
        }
        row("Curvature", curve, 0f..1f, "${(curve * 100).roundToInt()}%") { curve = it }
        row("Irregular", irr, 0f..3f, "${(irr * 100).roundToInt()}%") { irr = it }
        row("Irreg. scale", kotlin.math.sqrt((irrScale - 0.5f) / 199.5f), 0f..1f, if (irrScale < 10f) "${"%.1f".format(irrScale)} px" else "${irrScale.roundToInt()} px") { irrScale = ((0.5f + 199.5f * it * it) * 10f).roundToInt() / 10f }
        row("Taper in", tIn, 0f..0.5f, "${(tIn * 100).roundToInt()}%") { tIn = it }
        row("Taper out", tOut, 0f..0.5f, "${(tOut * 100).roundToInt()}%") { tOut = it }
        row("Anti-alias", aaV, 0f..1f, aaLabel(aaV)) { aaV = it; aaTouched = true }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightDial(angle, { angle = it }, Modifier.padding(vertical = 4.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Light from ${angle.roundToInt()}°", fontSize = 11.sp, color = Color.White)
                Slider(value = light, onValueChange = { light = it }, valueRange = 0f..1f, modifier = Modifier.height(32.dp))
                Text("Light strength ${(light * 100).roundToInt()}%. Lines facing away from the arrow get heavier.", fontSize = 10.sp, color = Color(0xFF9A9AA8))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { run(params(), false) }, enabled = targets.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Apply", fontSize = 12.sp) }
            TextButton(onClick = { run(params(), true) }, enabled = layer.cels.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("All frames", fontSize = 12.sp) }
            TextButton(onClick = { run(InkParams(angle = angle), false) }, enabled = cur != null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Off", fontSize = 12.sp) }
            TextButton(onClick = { ed.vecInkOpen = false }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Close", fontSize = 12.sp) }
        }
    }
}

/** An on / off switch whose state is unmistakable: filled when on, outlined grey when off. */
/** An icon button for the vector widget bar: [on] highlights it, a disabled one is dimmed and does nothing, [tint] colours the icon. */
@Composable
private fun VIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean = false, enabled: Boolean = true, tint: Color? = null, tip: String, onClick: () -> Unit) {
    Tip(tip) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                .background(if (on) androidx.compose.material3.MaterialTheme.colorScheme.primary else Color.Transparent)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val base = tint ?: if (on) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else Color(0xFFE6E6EC)
            androidx.compose.material3.Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) base else base.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(if (on) "$label: On" else "$label: Off", fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White) }
}

@Composable
private fun Choice(label: String, picked: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(if (picked) "● $label" else label, fontSize = 12.sp, color = if (picked) MaterialTheme.colorScheme.primary else Color.White)
    }
}

/**
 * The interpolation detail menu: mode (hold / same line / different lines), the easing curve (the Camera / Transformation curve set),
 * its strength, and the bake step (on 1s, 2s, 3s... or a custom number). Apply stores it on the selected keyframes; Bake also writes
 * the in-between keyframes.
 */
@Composable
fun VectorInterpolationDialog(cut: Cut, ed: Editor, layer: Layer, targets: List<Cel>) {
    val ctx = LocalContext.current
    val keys = targets.filter { t -> layer.cels.any { it === t } }
    var draft by remember { mutableStateOf(keys.firstOrNull()?.vectorConfig() ?: InterpolationConfig()) }
    var customStep by remember { mutableStateOf(false) }
    fun apply() { if (keys.isNotEmpty()) cut.record { keys.forEach { it.vecCfg = draft.copy(guideJoints = it.vecCfg?.guideJoints.orEmpty()) } } } // each keyframe keeps its OWN guide joints (they belong to its lines)
    AlertDialog(
        onDismissRequest = { ed.vecInterpOpen = false },
        title = { Text(if (keys.size <= 1) "Interpolation" else "Interpolation of ${keys.size} keyframes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (keys.isEmpty()) Text("Select a keyframe first (tap a frame on the vector layer, or use Select all).", fontSize = 12.sp)
                else Text(if (keys.any { it.interpolationOn() }) "Interpolation is on for ${keys.count { it.interpolationOn() }} of ${keys.size} keyframe${if (keys.size == 1) "" else "s"}" else "Interpolation is off (the keyframes hold). Apply turns it on.", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                Text("Mode (same or different lines are detected automatically)", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                Row {
                    Choice("Interpolate", draft.mode != InterpolationMode.NONE) { draft = draft.copy(mode = InterpolationMode.SAME_STROKE) }
                    Choice("Hold", draft.mode == InterpolationMode.NONE) { draft = draft.copy(mode = InterpolationMode.NONE) }
                }
                Text("Curve", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                Row {
                    Choice("Linear", draft.customBezier == null && draft.curveType == 1) { draft = draft.copy(curveType = 1, customBezier = null) }
                    Choice("Ease", draft.customBezier == null && draft.curveType == 2) { draft = draft.copy(curveType = 2, customBezier = null) }
                    Choice("In", draft.customBezier == null && draft.curveType == 3) { draft = draft.copy(curveType = 3, customBezier = null) }
                    Choice("Out", draft.customBezier == null && draft.curveType == 4) { draft = draft.copy(curveType = 4, customBezier = null) }
                }
                if (draft.customBezier == null && draft.curveType in 2..4) {
                    Text("Strength ${(draft.ease * 100).roundToInt()}%", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                    Slider(value = draft.ease, onValueChange = { draft = draft.copy(ease = it) }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                }
                Text("Bake on", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                Row {
                    listOf(1, 2, 3, 4).forEach { n -> Choice("${n}s", draft.bakeStep == n) { draft = draft.copy(bakeStep = n) } }
                    Choice(if (draft.bakeStep in 1..4) "Custom" else "${draft.bakeStep}s", draft.bakeStep !in 1..4) { customStep = true }
                }
                Text(
                    if (draft.mode == InterpolationMode.NONE) "Hold: this keyframe stays as drawn; nothing is baked."
                    else "Bake turns the span from each selected keyframe to the next one into real keyframes every ${draft.bakeStep} frame${if (draft.bakeStep == 1) "" else "s"}.",
                    fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Row {
                // Off: the keyframes go back to plain holds (no live in-betweens, nothing to bake). Guide joints are kept (as a Hold setting) so they are not lost.
                TextButton(onClick = { vecInterpolationOff(cut, keys); ed.vecInterpOpen = false }, enabled = keys.any { it.interpolationOn() }) { Text("Off") }
                TextButton(onClick = { apply() }, enabled = keys.isNotEmpty()) { Text("Apply") }
                TextButton(onClick = {
                    apply()
                    val n = bakeVectorKeys(cut, layer, keys)
                    android.widget.Toast.makeText(ctx, if (n == 0) "Nothing to bake (needs a following keyframe and a longer hold than the step)" else "Baked $n keyframe${if (n == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show()
                    if (n > 0) ed.selCels.clear()
                    ed.vecInterpOpen = false
                }, enabled = keys.isNotEmpty() && draft.mode != InterpolationMode.NONE) { Text("Bake") }
            }
        },
        dismissButton = { TextButton({ ed.vecInterpOpen = false }) { Text("Close") } }
    )
    if (customStep) NumpadDialog("Bake on every … frames", draft.bakeStep, { n -> draft = draft.copy(bakeStep = n.coerceIn(1, 240)) }, { customStep = false })
}


/**
 * Reference anchors: the selected points get anchor NUMBERS. In the next keyframe, the point with the same number is the
 * partner it is interpolated toward; more anchors pin more of the stroke. The preview colour comes from [anchorColor] and changes with the number of anchors. A number appears once per stroke. Works on every layer of the selection scope.
 */
@Composable
fun VectorAnchorDialog(cut: Cut, ed: Editor, onClose: () -> Unit) {
    fun apply(rank: Int, numbered: Boolean) {
        val sc = vecScope(cut, ed.refAll) ?: return
        if (!sc.sameCels(ed.vecSelCels)) return
        val parts = sc.perCel.indices.map { c ->
            val local = sc.localKeys(ed.vecSel, c)
            if (local.isEmpty()) return@map sc.perCel[c]
            sc.perCel[c].mapIndexed { si, s ->
                val picked = s.points.indices.filter { local.contains(vecKey(si, it)) }
                if (picked.isEmpty()) return@mapIndexed s
                var pts = s.points
                if (rank == 0) pts = pts.mapIndexed { pi, p -> if (pi in picked) p.copy(anchor = 0) else p }
                else if (numbered) {
                    val ranks = picked.take(ANCHOR_MAX).mapIndexed { n, pi -> pi to n + 1 }.toMap()
                    pts = pts.mapIndexed { pi, p -> val r = ranks[pi]; if (r != null) p.copy(anchor = r) else if (p.anchor in ranks.values) p.copy(anchor = 0) else p }
                } else {
                    val target = picked.first()
                    pts = pts.mapIndexed { pi, p -> if (pi == target) p.copy(anchor = rank) else if (p.anchor == rank) p.copy(anchor = 0) else p }
                }
                s.copy(points = pts)
            }
        }
        applyScope(cut, sc, parts)
        ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList() // the cels were swapped for new ones: keep the selection on them
    }
    var numberOpen by remember { mutableStateOf(false) }
    if (numberOpen) NumpadDialog("Anchor number for the first selected point", 1, { n -> apply(n.coerceIn(1, ANCHOR_MAX), false); numberOpen = false; onClose() }, { numberOpen = false })
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Reference anchor") },
        text = {
            Column {
                Text("Anchors are numbers. The point with the same number in the next keyframe is its partner, so the interpolation follows them. The Anchor button numbers new points in order; the colour of each number is only a preview and shifts as more anchors are added (3 anchors: blue, red, yellow).", fontSize = 12.sp)
                TextButton(onClick = { apply(1, true); onClose() }) { Text("Number the selected points 1, 2, 3 ... in order", fontSize = 12.sp) }
                TextButton(onClick = { numberOpen = true }) { Text("Set a number for the first selected point", fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { apply(0, false); onClose() }) { Text("Clear anchor") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}


/**
 * The Anchor button: selected points that are not anchors yet get the next free numbers (1, 2, 3, ...),
 * one number per point in stroke order, separately in each keyframe of the selection scope. Returns false when every selected point already
 * is an anchor: the caller then opens the number menu.
 */
fun autoAnchor(cut: Cut, ed: Editor, sc: VecScope): Boolean {
    var anyPlain = false; var changed = false
    val parts = sc.perCel.indices.map { c ->
        val strokes = sc.perCel[c]; val local = sc.localKeys(ed.vecSel, c)
        val plain = ArrayList<Long>()
        for ((si, s) in strokes.withIndex()) for ((pi, p) in s.points.withIndex()) if (local.contains(vecKey(si, pi)) && p.anchor == 0) plain.add(vecKey(si, pi))
        if (plain.isEmpty()) return@map strokes
        anyPlain = true
        val used = strokes.flatMap { s -> s.points.map { it.anchor } }.filter { it > 0 }.toSet()
        val free = (1..ANCHOR_MAX).filter { it !in used }
        val give = HashMap<Long, Int>()
        plain.forEachIndexed { n, k -> free.getOrNull(n)?.let { give[k] = it } }
        if (give.isEmpty()) return@map strokes // every number is in use in this keyframe
        changed = true
        strokes.mapIndexed { si, s -> s.copy(points = s.points.mapIndexed { pi, p -> give[vecKey(si, pi)]?.let { p.copy(anchor = it) } ?: p }) }
    }
    if (!anyPlain) return false
    if (changed) { applyScope(cut, sc, parts); ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList() }
    return true
}

/** Clears the reference anchor of every selected point (same effect as "Clear anchor" in the anchor dialog); used by the keyboard shortcut. Returns false when nothing could be cleared. */
fun clearAnchors(cut: Cut, ed: Editor): Boolean {
    val sc = vecScope(cut, ed.refAll) ?: return false
    if (!sc.sameCels(ed.vecSelCels) || ed.vecSel.isEmpty()) return false
    var changed = false
    val parts = sc.perCel.indices.map { c ->
        val local = sc.localKeys(ed.vecSel, c)
        if (local.isEmpty()) return@map sc.perCel[c]
        sc.perCel[c].mapIndexed { si, s ->
            if (s.points.indices.none { local.contains(vecKey(si, it)) && s.points[it].anchor != 0 }) s
            else { changed = true; s.copy(points = s.points.mapIndexed { pi, p -> if (local.contains(vecKey(si, pi))) p.copy(anchor = 0) else p }) }
        }
    }
    if (!changed) return false
    applyScope(cut, sc, parts)
    ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
    return true
}

/** Smoothing slider: adds points between the SELECTED points of the selected strokes where the line bends (the harder the bend, the more), none on straight stretches. Unselected parts of a line are left alone. */
@Composable
fun VectorSmoothDialog(cut: Cut, ed: Editor, onClose: () -> Unit) {
    var amount by remember { mutableStateOf(0.5f) }
    val sc = vecScope(cut, ed.refAll)
    val ok = sc != null && sc.sameCels(ed.vecSelCels)
    // per cel: the selected point indices of every stroke that has at least one selected point
    fun picked(c: Int): Map<Int, Set<Int>> {
        val out = HashMap<Int, HashSet<Int>>()
        for (k in sc!!.localKeys(ed.vecSel, c)) out.getOrPut(vecKeyStroke(k)) { HashSet() }.add(vecKeyPoint(k))
        return out
    }
    val added = if (!ok) 0 else sc!!.perCel.indices.sumOf { c -> picked(c).entries.sumOf { (si, pts) -> val st = sc.perCel[c][si]; VectorSmooth.refine(st, amount, pts).points.size - st.points.size } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Smooth") },
        text = {
            Column {
                Text("Adds points between the selected points of the lines and rounds them through the points they have. Points go where the line bends, more where it bends harder; straight stretches and unselected parts get none.", fontSize = 12.sp)
                Slider(value = amount, onValueChange = { amount = it }, valueRange = 0f..1f)
                Text("${(amount * 100).roundToInt()}%  ·  adds ${added} point${if (added == 1) "" else "s"}", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ok && sc != null && amount > 0f) {
                    val pickedAll = sc.perCel.indices.map { picked(it) }
                    val mapped = HashMap<Int, HashMap<Int, VectorSmooth.Refined>>()
                    val parts = sc.perCel.indices.map { c ->
                        val pc = pickedAll[c]
                        if (pc.isEmpty()) sc.perCel[c] else sc.perCel[c].mapIndexed { si, s ->
                            val pts = pc[si]
                            if (pts == null) s else {
                                val r = VectorSmooth.refineMapped(s, amount, pts)
                                mapped.getOrPut(c) { HashMap() }[si] = r
                                r.stroke
                            }
                        }
                    }
                    applyScope(cut, sc, parts)
                    val ns = vecScope(cut, ed.refAll)
                    if (ns != null) {
                        // keep the selection on what was selected, plus the points that were just added between them
                        val keys = HashSet<Long>()
                        for ((c, strokes) in mapped) for ((si, r) in strokes) {
                            val sel = pickedAll[c][si] ?: emptySet()
                            for (pi in sel) r.newIndex.getOrNull(pi)?.let { keys.add(vecKey(ns.offsets[c] + si, it)) }
                            for (ai in r.added) keys.add(vecKey(ns.offsets[c] + si, ai))
                        }
                        ed.vecSelCels = ns.cels; ed.vecSel = keys
                    }
                }
                onClose()
            }, enabled = ok && amount > 0f) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}


/** Options of the Fill tool on a vector layer (bottom centre): live or baked fills, lines of all layers as walls, and recomputing live fills over frames. */
@Composable
fun VectorFillBar(cut: Cut, ed: Editor, modifier: Modifier = Modifier) {
    val layer = cut.layer?.takeIf { it.vector && it.visible } ?: return
    if (ed.tool != 3) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val idx = layer.indexAt(cut.time)
    val picked = ed.selCels.filter { c -> layer.cels.any { it === c } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    var progress by androidx.compose.runtime.remember { mutableStateOf("") }
    // Refill runs as a coroutine that yields between fills (see refillLiveFills), so a long layer never blocks the UI thread.
    fun refill(targets: List<Cel>, where: String, verb: String) {
        if (busy) return
        busy = true; progress = "0%"
        scope.launch {
            try {
                val n = refillLiveFills(cut, layer, targets, ed.fillAuto) { d, t -> progress = "${100 * d / t.coerceAtLeast(1)}%" }
                android.widget.Toast.makeText(ctx, if (n == 0) "No live fill $where" else "$verb $n", android.widget.Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Live fill", ed.vecFillLive) { ed.vecFillLive = !ed.vecFillLive }
            ToggleChip("Across layers", ed.vecFillAcross) { ed.vecFillAcross = !ed.vecFillAcross }
            TextButton(enabled = !busy, onClick = {
                val targets = picked.ifEmpty { listOfNotNull(layer.cels.getOrNull(idx)) }
                refill(targets, "here", "Refilled")
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (busy) progress else "Refill", fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = {
                refill(layer.cels.toList(), "in this layer", "Refilled")
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("All frames", fontSize = 12.sp) }
        }
    }
}


/**
 * Selection tool: a shimmer (two expanding rings and a pulsing dot) wherever separate lines cross or touch without being joined; [gaps] are x, y pairs in
 * document pixels. It is its own layer on top of the canvas so the animation only redraws this, not the drawing. Does not catch touches.
 */
@Composable
fun VecGapShimmer(gaps: FloatArray, base: DrawTransform.() -> Unit, px: Float, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "gapShimmer")
    val phase by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "gapPhase")
    androidx.compose.foundation.Canvas(modifier) {
        withTransform({ base() }) {
            for (i in 0 until gaps.size / 2) {
                val c = Offset(gaps[i * 2], gaps[i * 2 + 1])
                val off = (i * 0.37f) % 1f // each place shimmers out of step with its neighbours
                for (k in 0..1) {
                    val ph = (phase + off + k * 0.5f) % 1f
                    drawCircle(Color(0xFFFFD54F).copy(alpha = (1f - ph) * 0.9f), (4f + 14f * ph) * px, c, style = Stroke(2f * px))
                }
                val pulse = 0.5f + 0.5f * kotlin.math.sin((phase + off) * 6.2831855f * 2f)
                drawCircle(Color.White.copy(alpha = 0.35f + 0.5f * pulse), 3.2f * px, c)
                drawCircle(Color(0xFFFFB300).copy(alpha = 0.9f), 3.2f * px, c, style = Stroke(1.2f * px))
            }
        }
    }
}


/**
 * The Transform / Warp / Puppet Warp session bar of the Selection tool on a vector layer (real lines, or the interpolation preview): the same buttons as the
 * raster [SelectionBar]. Apply / Cancel are implemented by the canvas (Editor.vecXfApply / vecXfCancel).
 */
@Composable
fun VecXformRows(ed: Editor) {
    val x = ed.vecXf ?: return
    var radiusOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Filled.Close, tip = "Cancel the transform") { ed.vecXfCancel() }
        IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio of the transform handles") { ed.xfLock = !ed.xfLock }
        MagnetBtn(ed)
        IconBtn(Icons.Filled.Grid4x4, x.mode == 1, tip = "Warp: divide the lines' box into a grid and nudge it piece by piece") { x.switchMode(if (x.mode == 1) 0 else 1) }
        GlyphBtn(x.mode == 2, "Puppet Warp: pin the lines and bend them around the pins", { x.switchMode(if (x.mode == 2) 0 else 2) }) { pinGlyph(it) }
        VIconBtn(Icons.Filled.LineWeight, on = ed.vecLockWeight, tip = if (ed.vecLockWeight) "Lock weight: on. The transform keeps the line weight" else "Lock weight: off. Scaling also scales the line weight") { ed.vecLockWeight = !ed.vecLockWeight }
        IconBtn(Icons.Filled.Refresh, tip = "Reset the transform") { x.resetAll() }
        IconBtn(Icons.Filled.Check, tip = if (x.onPreview) "Apply to the preview (the in-between is made with it)" else "Apply (one undo step)") { ed.vecXfApply() }
    }
    if (x.mode == 1) {
        SliderRow("Increments", x.warpN.toFloat(), 2f..16f, x.warpN.toString()) { v -> if (v.roundToInt() != x.warpN) x.setIncrements(v.roundToInt()) }
        SliderRow("Strength", x.strength, 0f..1f, "${(x.strength * 100f).roundToInt()}%") { v -> x.strength = v }
        Text("Drag a grid point. Strength sets how far the neighbouring points follow.", fontSize = 11.sp, color = Color(0xFFB8B8C4), modifier = Modifier.width(300.dp).padding(4.dp))
    }
    if (x.mode == 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinRadiusBtn(radiusOpen, (x.pins.getOrNull(x.pinSel)?.r ?: 0f) > 0f || x.pinRadius > 0f) { radiusOpen = !radiusOpen }
            TextButton({ x.clearPins() }) { Text("Clear", fontSize = 12.sp) }
        }
        if (radiusOpen) {
            val pin = x.pins.getOrNull(x.pinSel)
            val shown = pin?.r ?: x.pinRadius
            SliderRow(if (pin != null) "Radius (pin ${x.pinSel + 1})" else "Radius (new pins)", shown, 0f..PIN_RADIUS_MAX, if (shown < 1f) "Off" else "${shown.roundToInt()} px") { v -> x.applyPinRadius((v / 5f).roundToInt() * 5f) }
            Text("How far the selected pin reaches: the bend fades out that far from it (Off = every line follows). Tap a pin to select it.", fontSize = 11.sp, color = Color(0xFFB8B8C4), modifier = Modifier.width(300.dp).padding(4.dp))
        }
    }
}

/**
 * Shape flip bar, top right of the canvas (Selection tool, vector layers): shown while the selection holds lines with end anchors. The arrows flip through the other
 * keyframes of the layer that have lines with the same end anchor numbers (earlier ones to the left, later ones to the right of "Current"); the canvas previews the shapes
 * of that keyframe on the selected lines. The group is not moved or scaled as a whole, only the lines' shapes and their positions to each other change (see [VectorShapeFlip]).
 * Apply keeps what is shown (one undo step); Reset goes back to the current shapes. Selecting something else, another frame or another tool also drops the preview.
 */
@Composable
fun VectorShapeBar(cut: Cut, ed: Editor, modifier: Modifier = Modifier) {
    androidx.compose.runtime.LaunchedEffect(ed.vecSel, ed.vecSelCels, cut.time, cut.drawLayer) { ed.vecShapeCel = null }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ed.vecShapeCel = null } }
    if (ed.tool != 2 || ed.vecMode == 3 || ed.vecXf != null) return
    val androidCtx = LocalContext.current
    val shape = VectorShapeFlip.context(cut, ed) ?: return
    val celsKey = shape.layer.cels.toList()
    val cands = remember(celsKey, ed.vecSel, shape.idx) { VectorShapeFlip.candidates(shape) }
    val seq: List<VectorShapeFlip.Cand?> = cands.filter { it.index < shape.idx } + listOf<VectorShapeFlip.Cand?>(null) + cands.filter { it.index > shape.idx }
    val here = seq.indexOf(null)
    val pos = seq.indexOfFirst { it?.cel === ed.vecShapeCel }.let { if (it < 0) here else it }
    fun go(to: Int) { ed.vecShapeCel = seq.getOrNull(to.coerceIn(0, seq.size - 1))?.cel }
    val label = when {
        pos < here -> "Previous ${here - pos} of $here"
        pos > here -> "Next ${pos - here} of ${seq.size - 1 - here}"
        else -> "Current shapes"
    }
    val n = shape.chosen.size
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.widthIn(min = 128.dp).padding(horizontal = 8.dp)) {
            Text("Shapes · $n line${if (n == 1) "" else "s"}", fontSize = 11.sp, color = Color(0xFFB8B8C4))
            Text(if (cands.isEmpty()) "No other keyframe has these end anchors" else label, fontSize = 12.sp, color = Color.White)
        }
        VIconBtn(Icons.Filled.NavigateBefore, enabled = pos > 0, tip = "Flip to the shapes of the previous keyframe that has lines with the same end anchors (the group keeps its place and size)") { go(pos - 1) }
        VIconBtn(Icons.Filled.NavigateNext, enabled = pos < seq.size - 1, tip = "Flip to the shapes of the next keyframe that has lines with the same end anchors (the group keeps its place and size)") { go(pos + 1) }
        VIconBtn(Icons.Filled.Refresh, enabled = ed.vecShapeCel != null, tip = "Reset: back to the current shapes") { ed.vecShapeCel = null }
        VIconBtn(Icons.Filled.Check, enabled = ed.vecShapeCel != null, tip = "Apply the shown shapes to the selected lines (one undo step)") {
            val cel = ed.vecShapeCel
            if (cel != null && !VectorShapeFlip.apply(cut, ed, cel)) toastMessage(androidCtx, "Nothing to apply: that keyframe has no line with the same end anchors any more")
        }
    }
}
