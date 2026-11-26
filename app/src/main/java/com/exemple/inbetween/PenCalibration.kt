package com.example.inbetween

import android.view.MotionEvent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.floor

/** Screen-space correction mesh for raw Android stylus coordinates. Errors are normalized to display size. */
object PenCalibration {
    const val GRID = 5
    // Residual mesh: what is left of each target's error after the global affine part (offset, rotation, scale, skew) is removed. Indexed on the target grid.
    private val dx = FloatArray(GRID * GRID)
    private val dy = FloatArray(GRID * GRID)
    // Global affine part, fitted by least squares over the 25 taps: error ~ a[0] + a[1] * x + a[2] * y, x / y = the raw pen position normalized to the display. Zero in older settings files.
    private val affX = FloatArray(3)
    private val affY = FloatArray(3)
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
            // older files have no affine part: the stored mesh is then the whole correction (affine = 0 keeps the old behaviour)
            val fx = o.optJSONArray("penCalibrationAffineX"); val fy = o.optJSONArray("penCalibrationAffineY")
            for (i in 0 until 3) { affX[i] = if (fx != null && fx.length() == 3) fx.optDouble(i, 0.0).toFloat() else 0f; affY[i] = if (fy != null && fy.length() == 3) fy.optDouble(i, 0.0).toFloat() else 0f }
            gridLeft = o.optDouble("penCalibrationLeft", 0.08).toFloat(); gridRight = o.optDouble("penCalibrationRight", 0.92).toFloat()
            gridTop = o.optDouble("penCalibrationTop", 0.08).toFloat(); gridBottom = o.optDouble("penCalibrationBottom", 0.92).toFloat()
        }
        else { dx.fill(0f); dy.fill(0f); affX.fill(0f); affY.fill(0f); enabled = false }
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
        val fx = org.json.JSONArray()
        val fy = org.json.JSONArray()
        for (i in 0 until 3) { fx.put(affX[i].toDouble()); fy.put(affY[i].toDouble()) }
        o.put("penCalibrationAffineX", fx)
        o.put("penCalibrationAffineY", fy)
        o.put("penCalibrationLeft", gridLeft.toDouble())
        o.put("penCalibrationRight", gridRight.toDouble())
        o.put("penCalibrationTop", gridTop.toDouble())
        o.put("penCalibrationBottom", gridBottom.toDouble())
    }

    /**
     * Stores a calibration. [errorsX] / [errorsY] = (target - tap) normalized to the display, [targetX] / [targetY] = the target centres in screen pixels.
     * The error field is split in two: a least-squares affine part (global offset, rotation, scale and skew, which also extends past the outer targets) and
     * the residual of each target, kept as a 5x5 mesh for the local warp. A single tap that is a little off therefore only bends its own neighbourhood.
     */
    fun setSamples(errorsX: FloatArray, errorsY: FloatArray, targetX: FloatArray, targetY: FloatArray, screenWidth: Float, screenHeight: Float) {
        require(errorsX.size == GRID * GRID && errorsY.size == GRID * GRID)
        require(targetX.size == GRID * GRID && targetY.size == GRID * GRID)
        val n = GRID * GRID
        // where the pen really reported each tap (normalized): the affine part is a function of the RAW position, as it is at run time
        val sx = FloatArray(n) { targetX[it] / screenWidth - errorsX[it] }
        val sy = FloatArray(n) { targetY[it] / screenHeight - errorsY[it] }
        val fitX = fitAffine(sx, sy, errorsX); val fitY = fitAffine(sx, sy, errorsY)
        for (i in 0 until 3) { affX[i] = fitX[i]; affY[i] = fitY[i] }
        for (i in 0 until n) {
            dx[i] = errorsX[i] - (affX[0] + affX[1] * sx[i] + affX[2] * sy[i])
            dy[i] = errorsY[i] - (affY[0] + affY[1] * sx[i] + affY[2] * sy[i])
        }
        gridLeft = (targetX[0] / screenWidth).coerceIn(0f, 1f); gridRight = (targetX[GRID - 1] / screenWidth).coerceIn(gridLeft + 0.01f, 1f)
        gridTop = (targetY[0] / screenHeight).coerceIn(0f, 1f); gridBottom = (targetY[(GRID - 1) * GRID] / screenHeight).coerceIn(gridTop + 0.01f, 1f)
        hasData = true; enabled = true
    }

    /** Least-squares fit of v ~ c0 + c1 * x + c2 * y (normal equations, 3x3). All zeros when the taps do not span the plane. */
    private fun fitAffine(x: FloatArray, y: FloatArray, v: FloatArray): FloatArray {
        val n = v.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sv = 0.0; var sxv = 0.0; var syv = 0.0
        for (i in 0 until n) {
            val xi = x[i].toDouble(); val yi = y[i].toDouble(); val vi = v[i].toDouble()
            sx += xi; sy += yi; sxx += xi * xi; sxy += xi * yi; syy += yi * yi; sv += vi; sxv += xi * vi; syv += yi * vi
        }
        // | n   sx  sy  | |c0|   | sv  |
        // | sx  sxx sxy | |c1| = | sxv |
        // | sy  sxy syy | |c2|   | syv |
        val det = n * (sxx * syy - sxy * sxy) - sx * (sx * syy - sxy * sy) + sy * (sx * sxy - sxx * sy)
        if (kotlin.math.abs(det) < 1e-9) return FloatArray(3)
        val c0 = (sv * (sxx * syy - sxy * sxy) - sx * (sxv * syy - sxy * syv) + sy * (sxv * sxy - sxx * syv)) / det
        val c1 = (n * (sxv * syy - sxy * syv) - sv * (sx * syy - sxy * sy) + sy * (sx * syv - sxv * sy)) / det
        val c2 = (n * (sxx * syv - sxv * sxy) - sx * (sx * syv - sxv * sy) + sv * (sx * sxy - sxx * sy)) / det
        return floatArrayOf(c0.toFloat(), c1.toFloat(), c2.toFloat())
    }

    fun reset() { dx.fill(0f); dy.fill(0f); affX.fill(0f); affY.fill(0f); gridLeft = 0.08f; gridRight = 0.92f; gridTop = 0.08f; gridBottom = 0.92f; enabled = false; hasData = false }

    /**
     * The correction (in pixels) for a raw pen position: the global affine part, plus the residual mesh read with smooth bicubic interpolation. The mesh sits on the
     * TARGET grid, so it is looked up at the affine-corrected position (close to where the pen is really aimed), not at the raw one. Outside the outer targets the
     * affine part keeps going and the residual holds its edge value.
     */
    fun correction(rawX: Float, rawY: Float, screenWidth: Float, screenHeight: Float): Offset {
        if (!enabled || !hasData || screenWidth <= 0f || screenHeight <= 0f) return Offset.Zero
        val nx = rawX / screenWidth; val ny = rawY / screenHeight
        val ex = affX[0] + affX[1] * nx + affX[2] * ny
        val ey = affY[0] + affY[1] * nx + affY[2] * ny
        val qx = nx + ex; val qy = ny + ey
        val gx = ((qx - gridLeft) / (gridRight - gridLeft).coerceAtLeast(0.01f)).coerceIn(0f, 1f) * (GRID - 1)
        val gy = ((qy - gridTop) / (gridBottom - gridTop).coerceAtLeast(0.01f)).coerceIn(0f, 1f) * (GRID - 1)
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
        return Offset((ex + sample(dx)) * screenWidth, (ey + sample(dy)) * screenHeight)
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
            // Keep calibration targets clear of the header (including Cancel). The target hit
            // area is modest, while the visible crosshair itself is deliberately much smaller.
            val marginX = with(density) { 36.dp.toPx() }.coerceAtLeast(w * 0.06f)
            val topSafe = with(density) { 84.dp.toPx() }
            val bottomSafe = with(density) { 40.dp.toPx() }
            val targetX = marginX + (w - 2f * marginX).coerceAtLeast(1f) * (active % PenCalibration.GRID) / (PenCalibration.GRID - 1)
            val targetY = topSafe + (h - topSafe - bottomSafe).coerceAtLeast(1f) * (active / PenCalibration.GRID) / (PenCalibration.GRID - 1)
            Text(
                "Pen calibration  •  ${samples.size + 1} / $total\nTap the crosshair with the pen tip. Keep the tablet in its normal drawing orientation.",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 24.dp)
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 16.dp)
            ) { Text("Cancel") }
            ComposeCanvas(
                modifier = Modifier
                    .offset(x = with(density) { targetX.toDp() } - 14.dp, y = with(density) { targetY.toDp() } - 14.dp)
                    .size(28.dp)
                    .onGloballyPositioned { coords ->
                        // MotionEvent.rawX/rawY are in physical screen coordinates. Use
                        // Compose's matching screen-space position directly; manually adding
                        // the dialog root's screen origin to window coordinates can introduce
                        // a vertical inset / window-origin error on tablet dialogs.
                        val screen = coords.positionOnScreen() + Offset(coords.size.width / 2f, coords.size.height / 2f)
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
            ) {
                val c = Color(0xFF64B5F6)
                val mid = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val arm = 7.dp.toPx()
                val stroke = 1.dp.toPx()
                drawLine(c, androidx.compose.ui.geometry.Offset(mid.x - arm, mid.y), androidx.compose.ui.geometry.Offset(mid.x + arm, mid.y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(c, androidx.compose.ui.geometry.Offset(mid.x, mid.y - arm), androidx.compose.ui.geometry.Offset(mid.x, mid.y + arm), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(c, radius = 1.25.dp.toPx(), center = mid)
            }
        }
    }
}
