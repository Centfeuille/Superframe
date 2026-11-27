package com.example.inbetween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Selection widget bar. It is laid out inside the drawing canvas and placed under (or above, or at the top when there is no room) the selection's
 * on-screen box, so it travels with the selection while it is moved, scaled, rotated or deformed. [anchor] returns the screen corners of that box.
 */

@Composable
fun SelectionBar(ed: Editor, cut: Cut, anchor: () -> List<Offset>?) {
    val x = ed.xform
    val ctx = LocalContext.current
    var radiusOpen by remember { mutableStateOf(false) } // Puppet Warp: the pin radius slider is shown
    Box(
        Modifier.zIndex(5f).layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
            val a = anchor() // read here: the bar is placed again whenever the selection, the view or the transform changes
            val margin = 8.dp.roundToPx(); val gap = 64.dp.roundToPx() // the rotate grab points sit about 48 px outside the corners
            var px = (c.maxWidth - p.width) / 2; var py = margin
            if (a != null && a.isNotEmpty()) {
                val cx = a.sumOf { it.x.toDouble() }.toFloat() / a.size
                val top = a.minOf { it.y }; val bottom = a.maxOf { it.y }
                px = (cx - p.width / 2f).roundToInt().coerceIn(margin, max(margin, c.maxWidth - p.width - margin))
                val below = bottom.roundToInt() + gap; val above = top.roundToInt() - gap - p.height
                py = if (below + p.height + margin <= c.maxHeight) below else if (above >= margin) above else margin
            }
            layout(c.maxWidth, c.maxHeight) { p.placeRelative(px, py) }
        }
    ) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (x == null) {
                    IconBtn(Icons.Filled.Add, ed.selOp == 1, tip = "Add to the selection (or hold Shift)") { ed.selOp = if (ed.selOp == 1) 0 else 1 }
                    IconBtn(Icons.Filled.Remove, ed.selOp == 2, tip = "Erase from the selection (or hold Alt / Ctrl)") { ed.selOp = if (ed.selOp == 2) 0 else 2 }
                    IconBtn(Icons.Filled.SwapVert, tip = "Invert the selection") { invertSelection(ed, cut) }
                    IconBtn(Icons.Filled.FormatColorFill, tip = "Fill the selection with the current colour") { fillSelectionNow(ed, cut) }
                    IconBtn(Icons.Filled.OpenWith, tip = "Transform the selection") { beginTransform(ed, cut) }
                    IconBtn(Icons.Filled.ContentCopy, tip = "Copy the selection (then paste it on this or any other layer or frame)") { toastMessage(ctx, SelectionClipboard.copy(cut, ed)) }
                    IconBtn(Icons.Filled.ContentPaste, SelectionClipboard.item != null, tip = "Paste the copied selection onto this layer and frame") { toastMessage(ctx, SelectionClipboard.paste(cut, ed)) }
                    IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio of the transform handles") { ed.xfLock = !ed.xfLock }
                    MagnetBtn(ed)
                    IconBtn(Icons.Filled.Check, tip = "Done") { ed.sel = null }
                } else {
                    IconBtn(Icons.Filled.Close, tip = "Cancel the transform") { cancelTransform(ed) }
                    IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio of the transform handles") { ed.xfLock = !ed.xfLock }
                    MagnetBtn(ed)
                    IconBtn(Icons.Filled.Grid4x4, x.mode == 1, tip = "Warp: divide the selection into a grid and nudge it piece by piece") { x.switchMode(if (x.mode == 1) 0 else 1) }
                    GlyphBtn(x.mode == 2, "Puppet Warp: pin the drawing and bend it around the pins", { x.switchMode(if (x.mode == 2) 0 else 2) }) { pinGlyph(it) }
                    IconBtn(Icons.Filled.Refresh, tip = "Reset the transform") { x.resetAll() }
                    IconBtn(Icons.Filled.Check, tip = "Apply") { applyTransform(ed, cut) }
                }
            }
            if (x != null && x.mode == 1) {
                SliderRow("Increments", x.warpN.toFloat(), 2f..16f, x.warpN.toString()) { v -> if (v.roundToInt() != x.warpN) x.setIncrements(v.roundToInt()) }
                SliderRow("Strength", x.strength, 0f..1f, "${(x.strength * 100f).roundToInt()}%") { v -> x.strength = v }
                Text("Drag a grid point. Strength sets how far the neighbouring points follow.", fontSize = 11.sp, color = Color(0xFFB8B8C4), modifier = Modifier.width(300.dp).padding(4.dp))
            }
            if (x != null && x.mode == 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PinRadiusBtn(radiusOpen, x.pinSel >= 0 && (x.pins.getOrNull(x.pinSel)?.r ?: 0f) > 0f || x.pinRadius > 0f) { radiusOpen = !radiusOpen }
                    TextButton({ x.clearPins() }) { Text("Clear", fontSize = 12.sp) }
                }
                if (radiusOpen) {
                    val pin = x.pins.getOrNull(x.pinSel)
                    val shown = pin?.r ?: x.pinRadius
                    SliderRow(if (pin != null) "Radius (pin ${x.pinSel + 1})" else "Radius (new pins)", shown, 0f..PIN_RADIUS_MAX, if (shown < 1f) "Off" else "${shown.roundToInt()} px") { v -> x.applyPinRadius((v / 5f).roundToInt() * 5f) }
                    Text("How far the selected pin reaches: the bend fades out that far from it (Off = the whole drawing follows). Tap a pin to select it.", fontSize = 11.sp, color = Color(0xFFB8B8C4), modifier = Modifier.width(300.dp).padding(4.dp))
                }
            }
        }
    }
}

@Composable
internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(78.dp).padding(4.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(shown, fontSize = 12.sp, modifier = Modifier.width(38.dp))
    }
}

/** Largest Puppet Warp pin radius the slider offers (document px). */
const val PIN_RADIUS_MAX = 600f

/** The Puppet Warp button that shows / hides the pin radius slider. */
@Composable
fun PinRadiusBtn(open: Boolean, limited: Boolean, onClick: () -> Unit) {
    IconBtn(Icons.Filled.Adjust, open || limited, tip = "Pin radius: how far the selected pin reaches (the bend fades out that far from it)") { onClick() }
}

/** An [IconBtn] with a drawn glyph instead of a vector icon. */
@Composable
internal fun GlyphBtn(on: Boolean, tip: String, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Tip(tip) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Canvas(Modifier.size(22.dp)) { draw(Color.White) } }
    }
}

internal fun DrawScope.pinGlyph(c: Color) {
    val s = size.minDimension
    drawLine(c, Offset(s * 0.5f, s * 0.45f), Offset(s * 0.5f, s * 0.95f), strokeWidth = s * 0.1f, cap = StrokeCap.Round)
    drawCircle(c, s * 0.26f, Offset(s * 0.5f, s * 0.3f))
}

/** Logo of a selection type: 0 rectangle, 1 lasso, 2 point-based, 3 magic wand. */
@Composable
fun SelGlyph(kind: Int, glyphSize: Dp = 20.dp, color: Color = Color(0xFFE6E6EC)) {
    Canvas(Modifier.size(glyphSize)) {
        val s = size.minDimension
        val sw = s * 0.09f
        when (kind) {
            0 -> drawRect(color, Offset(s * 0.12f, s * 0.2f), Size(s * 0.76f, s * 0.6f), style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.14f, s * 0.1f))))
            1 -> {
                val p = Path().apply {
                    moveTo(s * 0.2f, s * 0.55f)
                    cubicTo(s * 0.05f, s * 0.15f, s * 0.7f, s * 0.0f, s * 0.9f, s * 0.35f)
                    cubicTo(s * 1.0f, s * 0.6f, s * 0.6f, s * 0.8f, s * 0.4f, s * 0.7f)
                    cubicTo(s * 0.3f, s * 0.9f, s * 0.35f, s * 0.95f, s * 0.38f, s * 1.0f)
                }
                drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.14f, s * 0.08f))))
            }
            2 -> {
                val pts = listOf(Offset(s * 0.15f, s * 0.75f), Offset(s * 0.25f, s * 0.2f), Offset(s * 0.7f, s * 0.12f), Offset(s * 0.88f, s * 0.6f), Offset(s * 0.5f, s * 0.85f))
                for (i in pts.indices) drawLine(color, pts[i], pts[(i + 1) % pts.size], strokeWidth = sw * 0.8f)
                for (q in pts) drawCircle(color, s * 0.08f, q)
            }
            else -> {
                drawLine(color, Offset(s * 0.12f, s * 0.9f), Offset(s * 0.58f, s * 0.44f), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
                val c = Offset(s * 0.72f, s * 0.28f); val r = s * 0.2f
                drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
                drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.6f, c.y - r * 0.6f), Offset(c.x + r * 0.6f, c.y + r * 0.6f), strokeWidth = sw * 0.6f, cap = StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.6f, c.y + r * 0.6f), Offset(c.x + r * 0.6f, c.y - r * 0.6f), strokeWidth = sw * 0.6f, cap = StrokeCap.Round)
            }
        }
    }
}
