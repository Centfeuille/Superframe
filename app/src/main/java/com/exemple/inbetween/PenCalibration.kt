package com.example.inbetween

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.floor

/** Screen-space correction mesh for raw Android stylus coordinates. Errors are normalized to display size. */
object PenCalibration {
    const val GRID = 5
    private val dx = FloatArray(GRID * GRID)
    private val dy = FloatArray(GRID * GRID)
    private var gridLeft = 0.08f; private var gridRight = 0.92f
    private var gridTop = 0.08f; private var gridBottom = 0.92f
    var enabled by androidx.compose.runtime.mutableStateOf(false)
    var hasData by androidx.compose.runtime.mutableStateOf(false)

    fun load(o: org.json.JSONObject) {
        enabled = o.optBoolean("penCalibrationEnabled", false)
        val ax = o.optJSONArray("penCalibrationDx")
        val ay = o.optJSONArray("penCalibrationDy")
        hasData = o.optBoolean("penCalibrationHasData", false) && ax != null && ay != null && ax.length() == GRID * GRID && ay.length() == GRID * GRID
        if (hasData) {
            for (i in dx.indices) { dx[i] = ax!!.optDouble(i, 0.0).toFloat(); dy[i] = ay!!.optDouble(i, 0.0).toFloat() }
            gridLeft = o.optDouble("penCalibrationLeft", 0.08).toFloat(); gridRight = o.optDouble("penCalibrationRight", 0.92).toFloat()
            gridTop = o.optDouble("penCalibrationTop", 0.08).toFloat(); gridBottom = o.optDouble("penCalibrationBottom", 0.92).toFloat()
        }
        else { dx.fill(0f); dy.fill(0f); enabled = false }
    }

    fun write(o: org.json.JSONObject) {
        // Keep writes separate: Android's platform JSONObject overloads are not
        // consistently chainable across the API stubs used by Kotlin compilation.
        o.put("penCalibrationEnabled", enabled)
        o.put("penCalibrationHasData", hasData)
        val ax = org.json.JSONArray()
        val ay = org.json.JSONArray()
        for (i in dx.indices) {
            ax.put(dx[i].toDouble())
            ay.put(dy[i].toDouble())
        }
        o.put("penCalibrationDx", ax)
        o.put("penCalibrationDy", ay)
        o.put("penCalibrationLeft", gridLeft.toDouble())
        o.put("penCalibrationRight", gridRight.toDouble())
        o.put("penCalibrationTop", gridTop.toDouble())
        o.put("penCalibrationBottom", gridBottom.toDouble())
    }

    fun setSamples(errorsX: FloatArray, errorsY: FloatArray, targetX: FloatArray, targetY: FloatArray, screenWidth: Float, screenHeight: Float) {
        require(errorsX.size == GRID * GRID && errorsY.size == GRID * GRID)
        require(targetX.size == GRID * GRID && targetY.size == GRID * GRID)
        for (i in dx.indices) { dx[i] = errorsX[i]; dy[i] = errorsY[i] }
        gridLeft = (targetX[0] / screenWidth).coerceIn(0f, 1f); gridRight = (targetX[GRID - 1] / screenWidth).coerceIn(gridLeft + 0.01f, 1f)
        gridTop = (targetY[0] / screenHeight).coerceIn(0f, 1f); gridBottom = (targetY[(GRID - 1) * GRID] / screenHeight).coerceIn(gridTop + 0.01f, 1f)
        hasData = true; enabled = true
    }

    fun reset() { dx.fill(0f); dy.fill(0f); gridLeft = 0.08f; gridRight = 0.92f; gridTop = 0.08f; gridBottom = 0.92f; enabled = false; hasData = false }

    /** Smooth bicubic interpolation over the 5x5 normalized screen-space displacement mesh. */
    fun correction(rawX: Float, rawY: Float, screenWidth: Float, screenHeight: Float): Offset {
        if (!enabled || !hasData || screenWidth <= 0f || screenHeight <= 0f) return Offset.Zero
        val gx = ((rawX / screenWidth - gridLeft) / (gridRight - gridLeft).coerceAtLeast(0.01f)).coerceIn(0f, 1f) * (GRID - 1)
        val gy = ((rawY / screenHeight - gridTop) / (gridBottom - gridTop).coerceAtLeast(0.01f)).coerceIn(0f, 1f) * (GRID - 1)
        val x0 = floor(gx).toInt().coerceIn(0, GRID - 2); val y0 = floor(gy).toInt().coerceIn(0, GRID - 2)
        val tx = gx - x0; val ty = gy - y0
        fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t; val t3 = t2 * t
            return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
        }
        fun sample(a: FloatArray): Float {
            val rows = FloatArray(4)
            for (r in -1..2) {
                val yy = (y0 + r).coerceIn(0, GRID - 1)
                val p0 = a[yy * GRID + (x0 - 1).coerceIn(0, GRID - 1)]
                val p1 = a[yy * GRID + x0]
                val p2 = a[yy * GRID + x0 + 1]
                val p3 = a[yy * GRID + (x0 + 2).coerceIn(0, GRID - 1)]
                rows[r + 1] = catmull(p0, p1, p2, p3, tx)
            }
            return catmull(rows[0], rows[1], rows[2], rows[3], ty)
        }
        return Offset(sample(dx) * screenWidth, sample(dy) * screenHeight)
    }

    /** Makes a corrected copy; original MotionEvent ownership remains with Android. */
    fun correctedEvent(event: MotionEvent, screenWidth: Float, screenHeight: Float): MotionEvent? {
        if (!enabled || !hasData) return null
        val d = correction(event.rawX, event.rawY, screenWidth, screenHeight)
        if (d == Offset.Zero) return null
        return MotionEvent.obtain(event).also { it.setLocation(event.x + d.x, event.y + d.y) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PenCalibrationDialog(onClose: () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val samples = remember { mutableStateListOf<Offset>() }
    val targets = remember { mutableStateListOf<Offset>() }
    val total = PenCalibration.GRID * PenCalibration.GRID
    val active = samples.size.coerceAtMost(total - 1)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xEE17171B))) {
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            val marginX = w * 0.06f; val marginY = h * 0.07f
            val targetX = marginX + (w - 2f * marginX) * (active % PenCalibration.GRID) / (PenCalibration.GRID - 1)
            val targetY = marginY + (h - 2f * marginY) * (active / PenCalibration.GRID) / (PenCalibration.GRID - 1)
            Text("Pen calibration  •  ${samples.size + 1} / $total\nTap the target with the pen tip. Keep the tablet in its normal drawing orientation.", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            Box(
                Modifier.offset(x = with(density) { targetX.toDp() } - 25.dp, y = with(density) { targetY.toDp() } - 25.dp)
                    .size(50.dp).clip(CircleShape).background(Color(0x332196F3)).border(2.dp, Color(0xFF64B5F6), CircleShape)
                    .onGloballyPositioned { coords ->
                        val p = coords.positionInWindow() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                        val loc = IntArray(2); view.rootView.getLocationOnScreen(loc)
                        val screen = Offset(p.x + loc[0], p.y + loc[1])
                        if (targets.size <= active) { if (targets.size == active) targets.add(screen) else targets[active] = screen }
                    }
                    .pointerInteropFilter { e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.getToolType(0) in intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
                            samples.add(Offset(e.rawX, e.rawY))
                            if (samples.size == total && targets.size == total) {
                                val sw = view.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                                val sh = view.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
                                val ex = FloatArray(total); val ey = FloatArray(total)
                                val tx = FloatArray(total); val ty = FloatArray(total)
                                for (i in 0 until total) {
                                    ex[i] = (targets[i].x - samples[i].x) / sw; ey[i] = (targets[i].y - samples[i].y) / sh
                                    tx[i] = targets[i].x; ty[i] = targets[i].y
                                }
                                PenCalibration.setSamples(ex, ey, tx, ty, sw, sh); AppSettings.save(); onClose()
                            }
                        }
                        true
                    }
            )
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { Text("Cancel") }
        }
    }
}
