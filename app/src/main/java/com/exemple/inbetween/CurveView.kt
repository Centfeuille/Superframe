package com.example.inbetween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Curve view of a Camera layer / Transformation folder: one coloured curve per channel (X, Y, scale, rotation), a
 * channel list on the right (eye, colour, key at the playhead, name, value) and the usual frame ruler on top.
 * Each channel is drawn in its own value range (fitted to its keys) because pixels, percent and degrees cannot share
 * one axis; the vertical axis shows the values of the ACTIVE channel (tap its row or one of its keys).
 * Keys use the same selection (Editor.selKeys) as the old key rows, so copy / delete / interpolation buttons still apply.
 */

private val CHAN_COLORS = longArrayOf(0xFFEF5350, 0xFF66BB6A, 0xFF42A5F5, 0xFFFFFFFF)
private val CHAN_NAMES = arrayOf("Position X", "Position Y", "Scale", "Rotation")

private fun chanColor(ci: Int) = Color(CHAN_COLORS[ci])

/** The value of channel [ci] of a Camera layer or Transformation folder at frame [t]. */
internal fun channelValue(cut: Cut, layer: Layer, ci: Int, t: Int): Float {
    val cam = layer.cam
    if (cam != null) return when (ci) { 0 -> cam.x(t, cut.w); 1 -> cam.y(t, cut.h); 2 -> cam.sc(t); else -> cam.rot(t) }
    val v = layer.transformAt(t)
    return when (ci) { 0 -> v.x; 1 -> v.y; 2 -> v.scale; else -> v.rotation }
}

private fun fmtValue(ci: Int, v: Float): String = when (ci) {
    2 -> "${(v * 100f).roundToInt()} %"
    3 -> "${(v * 10f).roundToInt() / 10f} °"
    else -> "${(v * 10f).roundToInt() / 10f} px"
}

private fun fmtAxis(ci: Int, v: Float): String = if (ci == 2) "${(v * 100f).roundToInt()}" else "${v.roundToInt()}"

/** [lo, hi] of the visible value range of a channel: its keys plus 15 % padding, never narrower than a sensible minimum. */
private fun chanRange(layer: Layer, ci: Int): FloatArray {
    val minSpan = when (ci) { 2 -> 0.2f; 3 -> 30f; else -> 100f }
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    layer.keyChan(ci)?.forEach { lo = min(lo, it.v); hi = max(hi, it.v) }
    if (lo > hi) { lo = 0f; hi = 0f }
    if (hi - lo < minSpan) { val m = (hi + lo) / 2f; lo = m - minSpan / 2f; hi = m + minSpan / 2f }
    val pad = (hi - lo) * 0.15f
    return floatArrayOf(lo - pad, hi + pad)
}

private class GraphMap(val w: Float, val h: Float, val dp: Float, val scrollX: Float, val cellPx: Float, layer: Layer) {
    private val padTop = 14f * dp
    private val padBot = 14f * dp
    val plotH = (h - padTop - padBot).coerceAtLeast(1f)
    val ranges = Array(4) { chanRange(layer, it) }
    fun xOf(t: Int) = (t + 0.5f) * cellPx - scrollX
    fun yOf(ci: Int, v: Float): Float { val r = ranges[ci]; return padTop + (1f - (v - r[0]) / (r[1] - r[0])) * plotH }
    fun unitsPerPx(ci: Int) = (ranges[ci][1] - ranges[ci][0]) / plotH
}

private fun hitKey(map: GraphMap, layer: Layer, visible: List<Boolean>, p: Offset, radius: Float): KeySel? {
    var best: KeySel? = null
    var bestD = radius * radius
    for (ci in 0..3) {
        if (!visible[ci]) continue
        val keys = layer.keyChan(ci) ?: continue
        for (k in keys) {
            val dx = map.xOf(k.t) - p.x
            if (abs(dx) > radius) continue
            val dy = map.yOf(ci, k.v) - p.y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = KeySel(layer.id, ci, k.t) }
        }
    }
    return best
}

private fun isSelected(ed: Editor, layer: Layer, ci: Int, t: Int) =
    ed.selKeys.any { it.layerId == layer.id && (it.ch == ci || it.ch < 0) && it.t == t }

/** Moves the VALUE of the selected keys (channel keys only; summary selections are skipped) by [delta] of their channel. */
private fun shiftValues(layer: Layer, sel: List<KeySel>, delta: (Int) -> Float) {
    for (s in sel) {
        if (s.ch < 0 || s.layerId != layer.id) continue
        val ch = layer.keyChan(s.ch) ?: continue
        val i = ch.indexOfFirst { it.t == s.t }
        if (i < 0) continue
        val k = ch[i]
        val nv = k.v + delta(s.ch)
        ch[i] = CamKey(k.t, if (s.ch == 2) nv.coerceIn(0.05f, 20f) else nv, k.interp, k.ease)
    }
    bumpTimelineRevision()
}

private fun DrawScope.diamond(cx: Float, cy: Float, r: Float, fill: Color, outline: Color) {
    val p = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
    drawPath(p, fill)
    drawPath(p, outline, style = Stroke(width = r * 0.28f))
}

@Composable
fun KeyCurveEditor(
    ed: Editor, cut: Cut, layer: Layer, cell: Dp, cellPx: Float, hs: ScrollState, rulerH: Dp,
    modifier: Modifier, onViewportWidth: (Int) -> Unit, onClose: () -> Unit
) {
    var active by remember(layer.id) { mutableIntStateOf((0..3).firstOrNull { layer.keyChan(it)?.isNotEmpty() == true } ?: 0) }
    val visible = remember(layer.id) { mutableStateListOf(true, true, true, true) }
    Row(modifier) {
        ChannelPanel(ed, cut, layer, rulerH, active, visible, { active = it }, onClose)
        Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onViewportWidth(it.width) }) {
            TimelineRuler(cut, cell, rulerH, hs, cellPx)
            CurveGraph(ed, cut, layer, cellPx, hs, active, visible, { active = it }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CurveGraph(
    ed: Editor, cut: Cut, layer: Layer, cellPx: Float, hs: ScrollState, active: Int, visible: List<Boolean>,
    setActive: (Int) -> Unit, modifier: Modifier
) {
    var dragMode by remember { mutableIntStateOf(0) }     // 0 none, 1 moving keys in time, 2 moving keys in value
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val curCellPx by rememberUpdatedState(cellPx)
    val curSetActive by rememberUpdatedState(setActive)
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() } }
    val strides = remember { intArrayOf(1, 2, 3, 4, 6, 8, 12, 24, 48, 96, 192, 384, 768) }

    Canvas(
        modifier.clipToBounds().background(Color(0xFF26262C)).pointerInput(layer.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val map = GraphMap(size.width.toFloat(), size.height.toFloat(), density, hs.value.toFloat(), curCellPx, layer)
                val hit = hitKey(map, layer, visible, down.position, 22f * density)
                if (hit != null) {
                    cut.playing = false
                    if (!isSelected(ed, layer, hit.ch, hit.t)) { ed.clearMulti(); ed.selKeys.add(hit) }
                    curSetActive(hit.ch)
                }
                var mode = 0 // 0 undecided, 1 time, 2 value, 3 pan the graph
                var last = down.position
                val slop = viewConfiguration.touchSlop * 2f
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) { // pinch / two-finger pan belongs to the timeline
                        dragMode = 0; dragDx = 0f; dragDy = 0f
                        return@awaitEachGesture
                    }
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.position - down.position
                    if (mode == 0 && (abs(d.x) > slop || abs(d.y) > slop)) mode = if (hit == null) 3 else if (abs(d.x) >= abs(d.y)) 1 else 2
                    when (mode) {
                        1 -> { dragMode = 1; dragDx = d.x; dragDy = 0f; ch.consume() }
                        2 -> { dragMode = 2; dragDy = d.y; dragDx = 0f; ch.consume() }
                        3 -> { hs.dispatchRawDelta(-(ch.position.x - last.x)); ch.consume() }
                    }
                    last = ch.position
                }
                val dx = dragDx
                val dy = dragDy
                dragMode = 0; dragDx = 0f; dragDy = 0f
                when {
                    mode == 0 && hit == null -> { // tap on empty graph: move the playhead, drop the key selection
                        cut.playing = false
                        cut.time = floor((down.position.x + map.scrollX) / map.cellPx).toInt().coerceIn(0, cut.maxTick())
                        ed.selKeys.clear()
                    }
                    mode == 1 -> {
                        val n = (dx / map.cellPx).roundToInt()
                        if (n != 0) cut.record {
                            val moved = moveKeysSel(cut, ed.selKeys.toList(), n)
                            ed.selKeys.clear(); ed.selKeys.addAll(moved)
                            Unit
                        }
                    }
                    mode == 2 -> if (abs(dy) > 0.5f) cut.record { shiftValues(layer, ed.selKeys.toList()) { ci -> -dy * map.unitsPerPx(ci) }; Unit }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val sx = hs.value.toFloat()
        val map = GraphMap(w, h, density, sx, cellPx, layer)
        val f0 = max(0, floor(sx / cellPx).toInt() - 1)
        val f1 = ceil((sx + w) / cellPx).toInt() + 1

        // vertical grid at frame boundaries (same x as the ruler ticks)
        val stride = strides.firstOrNull { it * cellPx >= 48f * density } ?: 1536
        var t = (f0 / stride) * stride
        while (t <= f1) { val x = t * cellPx - sx; drawLine(Color(0x1FFFFFFF), Offset(x, 0f), Offset(x, h), 1f); t += stride }

        // horizontal grid + axis labels of the active channel
        val r = map.ranges[active]
        labelPaint.textSize = 10f * density
        for (i in 0..4) {
            val v = r[0] + (r[1] - r[0]) * i / 4f
            val y = map.yOf(active, v)
            drawLine(Color(0x1FFFFFFF), Offset(0f, y), Offset(w, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(fmtAxis(active, v), 3f * density, y - 2f * density, labelPaint)
        }

        // playhead
        drawRect(Color(0x59FFA726), Offset(cut.time * cellPx - sx, 0f), Size(cellPx, h))

        // curves: inactive first, the active channel on top and thicker
        val sub = if (cellPx >= 10f) 4 else if (cellPx >= 4f) 2 else 1
        val order = (0..3).filter { it != active } + active
        for (ci in order) {
            if (!visible[ci]) continue
            val keys = layer.keyChan(ci) ?: continue
            if (keys.isEmpty()) continue
            val path = Path()
            var first = true
            var tf = f0.toFloat()
            while (tf <= f1) {
                val x = (tf + 0.5f) * cellPx - sx
                val y = map.yOf(ci, evalChanF(keys, tf))
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                tf += 1f / sub
            }
            drawPath(path, chanColor(ci), style = Stroke(width = (if (ci == active) 2.5f else 1.5f) * density))
        }

        // keys (selected ones follow the finger while dragging)
        val moveN = if (dragMode == 1) (dragDx / cellPx).roundToInt() else 0
        for (ci in order) {
            if (!visible[ci]) continue
            val keys = layer.keyChan(ci) ?: continue
            for (k in keys) {
                if (k.t < f0 || k.t > f1) continue
                val sel = isSelected(ed, layer, ci, k.t)
                val cx = map.xOf(k.t) + if (sel) moveN * cellPx else 0f
                val cy = map.yOf(ci, k.v) + if (sel && dragMode == 2) dragDy else 0f
                if (sel) diamond(cx, cy, 8f * density, Color(0xFFFFB74D), Color.White)
                else diamond(cx, cy, 6f * density, chanColor(ci), Color(0xFF26262C))
            }
        }
    }
}

@Composable
private fun ChannelPanel(
    ed: Editor, cut: Cut, layer: Layer, headerH: Dp, active: Int, visible: MutableList<Boolean>, setActive: (Int) -> Unit, onClose: () -> Unit
) {
    // Same width as the layer column, so the channel list sits where the layer names were.
    Column(Modifier.width(240.dp).fillMaxHeight().background(Color(0xFF2A2A31))) {
        Row(Modifier.fillMaxWidth().height(headerH).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.KeyboardArrowLeft, null, Modifier.clickable { onClose() }.padding(2.dp).size(28.dp))
            Text(layer.name, Modifier.padding(start = 4.dp), fontSize = 13.sp, maxLines = 1, color = Color(0xFFBDBDBD))
        }
        for (ci in 0..3) {
            val hasKey = layer.keyChan(ci)?.any { it.t == cut.time } == true
            Row(
                Modifier.fillMaxWidth().height(38.dp).background(if (ci == active) Color(0x24FFFFFF) else Color.Transparent)
                    .clickable { setActive(ci) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (visible[ci]) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null,
                    Modifier.clickable { visible[ci] = !visible[ci] }.padding(2.dp).size(22.dp))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(11.dp).clip(CircleShape).background(chanColor(ci)))
                Spacer(Modifier.width(2.dp))
                Text(if (hasKey) "◆" else "◇", Modifier.clickable {
                    cut.playing = false
                    cut.record {
                        val ch = layer.keyChan(ci)
                        if (ch != null) {
                            val i = ch.indexOfFirst { it.t == cut.time }
                            if (i >= 0) { ch.removeAt(i); bumpTimelineRevision() }
                            else setKey(ch, cut.time, channelValue(cut, layer, ci, cut.time), ed.interp, ed.ease)
                        }
                        Unit
                    }
                }.padding(horizontal = 4.dp), fontSize = 16.sp, color = if (hasKey) Color(0xFFFFB74D) else Color(0xFF9E9E9E))
                Text(CHAN_NAMES[ci], Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                Text(fmtValue(ci, channelValue(cut, layer, ci, cut.time)), fontSize = 11.sp, maxLines = 1, color = Color(0xFFFFB74D))
            }
        }
    }
}
