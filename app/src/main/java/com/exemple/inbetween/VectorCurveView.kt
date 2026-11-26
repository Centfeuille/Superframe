package com.example.inbetween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Curves view of a VECTOR layer whose keyframes have interpolation settings turned on (the arrow beside the layer name appears then, like on Camera / Transformation
 * layers). Every keyframe with interpolation on draws the easing curve of its span (0 at the keyframe, 1 at the next keyframe) over the same frame ruler as the timeline;
 * the panel on the left sets the curve of the selected keyframes (Linear / Ease / In / Out and its strength), opens the full interpolation settings, or turns interpolation off.
 * Drag interpolation (the in-between placed by hand) is not part of this: it needs no settings and creates real frames.
 */

private val SPAN_COLOR = Color(0xFFD0BCFF)
private val SPAN_PICKED = Color(0xFFFFB74D)

private fun curveName(cfg: InterpolationConfig): String =
    if (cfg.customBezier != null) "Custom" else when (cfg.curveType) { 2 -> "Ease"; 3 -> "In"; 4 -> "Out"; else -> "Linear" }

@Composable
fun VectorCurveEditor(
    ed: Editor, cut: Cut, layer: Layer, cell: Dp, cellPx: Float, hs: ScrollState, rulerH: Dp,
    modifier: Modifier, onViewportWidth: (Int) -> Unit, onClose: () -> Unit
) {
    Row(modifier) {
        VectorCurvePanel(ed, cut, layer, rulerH, onClose)
        Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onViewportWidth(it.width) }) {
            TimelineRuler(cut, cell, rulerH, hs, cellPx)
            VectorCurveGraph(ed, cut, layer, cellPx, hs, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun VectorCurvePanel(ed: Editor, cut: Cut, layer: Layer, headerH: Dp, onClose: () -> Unit) {
    // the same keyframes the interpolation settings dialog works on: the selection, every keyframe after Select all, else the span at the playhead
    val targets = vecInterpTargets(cut, ed, layer)
    val on = targets.filter { it.interpolationOn() }
    val first = on.firstOrNull()?.vectorConfig()
    var strength by remember(on.firstOrNull()) { mutableStateOf(first?.ease ?: 0.33f) }
    fun setCurve(type: Int) { if (on.isNotEmpty()) cut.record { for (k in on) k.vecCfg = k.vectorConfig().copy(curveType = type, customBezier = null) } }
    val pad = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    Column(Modifier.width(240.dp).fillMaxHeight().background(Color(0xFF2A2A31)).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(headerH).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.KeyboardArrowLeft, null, Modifier.clickable { onClose() }.padding(2.dp).size(28.dp))
            Text(layer.name, Modifier.padding(start = 4.dp), fontSize = 13.sp, maxLines = 1, color = Color(0xFFBDBDBD))
        }
        Text(
            if (targets.isEmpty()) "No keyframe" else "Interpolation on for ${on.size} of ${targets.size} keyframe${if (targets.size == 1) "" else "s"}",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Color(0xFF9E9E9E)
        )
        Row(Modifier.padding(horizontal = 2.dp)) {
            listOf(1 to "Linear", 2 to "Ease", 3 to "In", 4 to "Out").forEach { (type, label) ->
                val picked = first != null && first.customBezier == null && first.curveType == type
                TextButton(onClick = { setCurve(type) }, enabled = on.isNotEmpty(), contentPadding = pad) {
                    Text(label, fontSize = 12.sp, color = if (picked) SPAN_COLOR else Color.White.copy(alpha = if (on.isNotEmpty()) 1f else 0.4f))
                }
            }
        }
        if (first != null && first.customBezier == null && first.curveType in 2..4) {
            Text("Strength ${(strength * 100).roundToInt()}%", Modifier.padding(horizontal = 10.dp), fontSize = 11.sp, color = Color(0xFF9E9E9E))
            Slider(
                value = strength, onValueChange = { strength = it },
                onValueChangeFinished = { if (on.isNotEmpty()) cut.record { for (k in on) k.vecCfg = k.vectorConfig().copy(ease = strength) } },
                valueRange = 0f..1f, modifier = Modifier.padding(horizontal = 8.dp).height(32.dp)
            )
        }
        Row(Modifier.padding(horizontal = 2.dp)) {
            TextButton(onClick = { ed.vecInterpOpen = true }, contentPadding = pad) { Text("Settings", fontSize = 12.sp) }
            TextButton(onClick = { vecInterpolationOff(cut, on) }, enabled = on.isNotEmpty(), contentPadding = pad) { Text("Off", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun VectorCurveGraph(ed: Editor, cut: Cut, layer: Layer, cellPx: Float, hs: ScrollState, modifier: Modifier) {
    val curCellPx by rememberUpdatedState(cellPx)
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() } }
    val strides = remember { intArrayOf(1, 2, 3, 4, 6, 8, 12, 24, 48, 96, 192, 384, 768) }
    val picked = vecInterpTargets(cut, ed, layer)

    Canvas(
        modifier.clipToBounds().background(Color(0xFF26262C)).pointerInput(layer.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var last = down.position
                var panned = false
                val slop = viewConfiguration.touchSlop * 2f
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) return@awaitEachGesture // pinch / two-finger pan belongs to the timeline
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!panned && (abs(ch.position.x - down.position.x) > slop || abs(ch.position.y - down.position.y) > slop)) panned = true
                    if (panned) { hs.dispatchRawDelta(-(ch.position.x - last.x)); ch.consume() }
                    last = ch.position
                }
                if (!panned) { // tap: the playhead goes to that frame and its keyframe is the one the panel edits
                    cut.playing = false
                    val t = floor((down.position.x + hs.value) / curCellPx).toInt().coerceIn(0, cut.maxTick())
                    cut.time = t
                    layer.cels.getOrNull(layer.indexAt(t))?.let { ed.clearMulti(); cut.selAll = -1; ed.selCels.add(it) }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val sx = hs.value.toFloat()
        val padV = 16f * density
        val plotH = (h - 2f * padV).coerceAtLeast(1f)
        fun yOf(v: Float) = padV + (1f - v) * plotH
        fun xOf(t: Int) = (t + 0.5f) * cellPx - sx
        val f0 = max(0, floor(sx / cellPx).toInt() - 1)
        val f1 = ceil((sx + w) / cellPx).toInt() + 1

        // vertical grid at frame boundaries (the same x as the ruler ticks) and the 0 / 50 / 100 % lines of the eased progress
        val stride = strides.firstOrNull { it * cellPx >= 48f * density } ?: 1536
        var t = (f0 / stride) * stride
        while (t <= f1) { val x = t * cellPx - sx; drawLine(Color(0x1FFFFFFF), Offset(x, 0f), Offset(x, h), 1f); t += stride }
        labelPaint.textSize = 10f * density
        for (i in 0..2) {
            val v = i / 2f
            drawLine(Color(0x1FFFFFFF), Offset(0f, yOf(v)), Offset(w, yOf(v)), 1f)
            drawContext.canvas.nativeCanvas.drawText("${(v * 100).roundToInt()}%", 3f * density, yOf(v) - 2f * density, labelPaint)
        }

        // playhead
        drawRect(Color(0x59FFA726), Offset(cut.time * cellPx - sx, 0f), Size(cellPx, h))

        // spans: the easing curve from each keyframe to the next; keyframes with interpolation off hold (a flat dim line)
        for (i in layer.cels.indices) {
            val a = layer.cels[i]
            val s0 = layer.startOf(i)
            val next = layer.cels.getOrNull(i + 1)
            val s1 = if (next != null) layer.startOf(i + 1) else s0 + a.dur
            val x1 = xOf(s0); val x2 = xOf(s1)
            if (x2 < -cellPx || x1 > w + cellPx) continue
            val isPicked = picked.any { it === a }
            if (next == null || !a.interpolationOn()) {
                drawLine(Color(0x33FFFFFF), Offset(x1, yOf(0f)), Offset(x2, yOf(0f)), 1.5f * density)
            } else {
                val cfg = a.vectorConfig()
                val col = if (isPicked) SPAN_PICKED else SPAN_COLOR
                val steps = ((x2 - x1) / (4f * density)).toInt().coerceIn(8, 96)
                val path = Path()
                for (k in 0..steps) {
                    val u = k / steps.toFloat()
                    val x = x1 + (x2 - x1) * u
                    val y = yOf(VectorInterpolation.curve(cfg, u).coerceIn(-0.25f, 1.25f))
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, col, style = Stroke(width = (if (isPicked) 2.5f else 1.6f) * density))
                drawCircle(col, 3f * density, Offset(x2, yOf(1f))) // where the span ends: the next keyframe
                if (x2 - x1 > 56f * density) drawContext.canvas.nativeCanvas.drawText(curveName(cfg), x1 + 6f * density, padV - 4f * density, labelPaint)
            }
            // the keyframe itself
            val picked1 = isPicked
            diamondAt(x1, yOf(0f), (if (picked1) 8f else 6f) * density, if (a.interpolationOn()) (if (picked1) SPAN_PICKED else SPAN_COLOR) else Color(0xFF8A8A98), Color(0xFF26262C))
        }
    }
}

private fun DrawScope.diamondAt(cx: Float, cy: Float, r: Float, fill: Color, outline: Color) {
    val p = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
    drawPath(p, fill)
    drawPath(p, outline, style = Stroke(width = r * 0.28f))
}
