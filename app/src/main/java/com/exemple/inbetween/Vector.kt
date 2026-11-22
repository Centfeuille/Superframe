package com.example.inbetween

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.acos
import kotlin.math.ceil

/*
 * Vector layers (Grease Pencil style). A vector layer is an ordinary drawing layer (Layer.vector = true) whose cels are the
 * keyframes: each cel holds its strokes as Ink kind 5. That keeps timeline, timing, onion skin, undo, export and thumbnails
 * working unchanged; TileSet.stamp / stampInk rasterize a kind-5 ink through VectorTessellator.
 *
 * Ink kind 5 layout: p = x, y, pressure, elevationOffset per point; color = ARGB; m = [baseWidth, closed (0 / 1), opacity];
 * data = UTF-8 "<stroke id>|<brush id>".
 */

data class VectorPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    /** Raises / lowers one point: added to the half-width of the ribbon at that point. */
    val elevationOffset: Float = 0f,
    /** Reference anchor number (0 = none; 1..ANCHOR_MAX, shown as a number, colour from [anchorColor]): points of the same number in the next keyframe are interpolated toward each other. */
    val anchor: Int = 0,
    /** Invisible point: the line has no width here (it tapers out toward it, and a stretch between two hidden points is not drawn). The point stays editable and keeps interpolating. */
    val hidden: Boolean = false,
    /** The anchor was put here automatically (an end or a tip of the line): it is redone when the line changes shape (see [AutoAnchors.refresh]). Anchors set by hand or inherited from a traced line are not automatic. */
    val auto: Boolean = false
)

/**
 * Preview colour of anchor number [rank] when the stroke has [count] anchors: a gradient through blue, red, yellow spread over the numbers, so with three anchors
 * they are the three primaries (blue, red, yellow) and more anchors fill in the colours between. The NUMBER is what pairs points between keyframes, not the colour.
 */
fun anchorColor(rank: Int, count: Int): Long {
    val stops = longArrayOf(0xFF2979FF, 0xFFE53935, 0xFFFDD835)
    val n = maxOf(count, rank, 1)
    if (n == 1) return stops[0]
    if (n == 2) return stops[(rank - 1).coerceIn(0, 1)]
    val t = ((rank - 1).coerceIn(0, n - 1).toFloat() / (n - 1)) * 2f
    val i = kotlin.math.floor(t).toInt().coerceIn(0, 1); val f = t - i
    fun ch(sh: Int) = (((stops[i] shr sh) and 255) * (1f - f) + ((stops[i + 1] shr sh) and 255) * f).toInt().coerceIn(0, 255).toLong()
    return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Anchor colors in rank order, by colour theory: the three primaries (blue first), the three secondaries, then the six tertiaries. */
val ANCHOR_COLORS: List<Long> = listOf(
    0xFF2979FF, 0xFFE53935, 0xFFFDD835,                                         // primaries: blue, red, yellow
    0xFF43A047, 0xFFFB8C00, 0xFF8E24AA,                                         // secondaries: green, orange, purple
    0xFFFF5722, 0xFFFFB300, 0xFFAEEA00, 0xFF00ACC1, 0xFF5C6BC0, 0xFFD81B60      // tertiaries: red-orange, yellow-orange, yellow-green, blue-green, blue-violet, red-violet
)
val ANCHOR_NAMES: List<String> = listOf("Blue", "Red", "Yellow", "Green", "Orange", "Purple", "Red-orange", "Yellow-orange", "Yellow-green", "Blue-green", "Blue-violet", "Red-violet")
const val ANCHOR_MAX = 99
/** In a stroke's sparse anchor list, an entry whose index is this much higher than a point's means "the anchor of that point is automatic" (older builds ignore such entries, they are out of range for them). */
const val AUTO_FLAG_OFFSET = 1_000_000

data class VectorStroke(
    /** Persistent id: the same line is matched across keyframes by it (and a cut gives the new half a new id). */
    val id: String,
    val points: List<VectorPoint>,
    val color: Int,
    val baseWidth: Float,
    val brushId: String = "",
    val isClosed: Boolean = false,
    val opacity: Float = 1f,
    /** Anti-aliasing of the brush the line was drawn with (Brush.aa): 0 = hard aliased edge, above 0.02 = anti-aliased. 1 = the previous behaviour (always anti-aliased). Stored only when not 1, in the data text. */
    val aa: Float = 1f,
    /** Per-keyframe "boil": amplitude in document px (0 = off), wavelength in px and noise seed. Applied only when the outline is drawn; the points are untouched. Interpolated in-betweens carry none. */
    val wobble: Float = 0f,
    val wobbleScale: Float = 60f,
    val wobbleSeed: Int = 0,
    /** Brush look carried by the line (see [withBrush]); the defaults are the plain hard, round, untextured line. */
    val hardness: Float = 1f,        // below 1 the edge is feathered (blur proportional to the line width)
    val texId: Int = 0, val texAmt: Float = 0.7f, val texScale: Float = 1f, val texInvert: Boolean = false, val texImg: Int = 0, // texture laid over the line, anchored to the page
    val tipShape: Int = 0,           // 0 circle, 1 triangle, 2 square, 3 etched (drawn as a circle): shapes the width of the line
    val roundness: Float = 1f,       // tip squash 0.1..1
    val tipAngle: Float = 0f,        // degrees
    val tipFollow: Boolean = false,  // the tip turns with the direction of the line
    /** 0 = a line; 1 = a baked fill (closed shape filled with its colour, no outline); 2 = a live fill: also remembers how to be recomputed from the lines (seed, gap, grow, across layers). */
    val fill: Int = 0,
    val fillGap: Int = 0, val fillGrow: Int = 1, val fillAcross: Boolean = false, val fillSeedX: Float = 0f, val fillSeedY: Float = 0f,
    /** Designed line weight (applied only when the outline is drawn; the points and the stored width are untouched, so interpolation stays clean).
     *  inkCurve 0..1: tight bends get heavier, straight stretches lighter. inkLight 0..1 with inkAngle (degrees, screen coordinates, the direction the light comes FROM):
     *  contours facing away from the light get heavier, lit ones lighter. taperIn / taperOut: 0..0.5 of the line length over which the width runs out at the start / end. */
    val inkCurve: Float = 0f, val inkLight: Float = 0f, val inkAngle: Float = 315f, val taperIn: Float = 0f, val taperOut: Float = 0f,
    /** Irregular weight, independent of the shape: inkIrr 0..3 (0..300%) is how much the width varies, inkIrrScale (px) how long each swell is. Seeded by the stroke id and measured along the line. */
    val inkIrr: Float = 0f, val inkIrrScale: Float = 40f,
    /** Extra edge softness in px on top of anti-aliasing (0 = the crisp anti-aliased edge). */
    val aaSoft: Float = 0f,
    /** Corner sharpness 0..1 (0 = the usual softly rounded turns). Set by Merge: above 0 the turns of the line are not filleted and bends past about 30-55 degrees stay crisp corners instead of being smoothed into a curve. */
    val joinSharp: Float = 0f
) {
    fun hasInk() = inkCurve > 0.001f || inkLight > 0.001f || taperIn > 0.001f || taperOut > 0.001f || inkIrr > 0.001f
    fun hasStyle() = hasInk() || aaSoft > 0.001f || joinSharp > 0.001f || fill != 0 || hardness < 0.999f || texId != 0 || texImg != 0 || tipShape != 0 || roundness < 0.999f || tipAngle != 0f || tipFollow
    fun tipActive() = tipShape == 1 || tipShape == 2 || roundness < 0.99f
    /** Takes the brush's look (anti-aliasing, hardness, texture, tip shape / roundness / angle). */
    fun withBrush(b: Brush) = copy(aa = b.aa, hardness = b.hardness.coerceIn(0f, 1f), texId = b.textureId, texAmt = b.textureAmt, texScale = b.texScale, texInvert = b.texInvert, texImg = b.texImg,
        tipShape = b.tip, roundness = b.roundness.coerceIn(0.1f, 1f), tipAngle = b.tipAngle, tipFollow = b.tipFollow)
    /** The look (not the wobble) of [o]: used for lines built by interpolation. */
    fun styleFrom(o: VectorStroke) = copy(fill = o.fill, fillGap = o.fillGap, fillGrow = o.fillGrow, fillAcross = o.fillAcross, fillSeedX = o.fillSeedX, fillSeedY = o.fillSeedY, aa = o.aa, hardness = o.hardness, texId = o.texId, texAmt = o.texAmt, texScale = o.texScale, texInvert = o.texInvert, texImg = o.texImg,
        tipShape = o.tipShape, roundness = o.roundness, tipAngle = o.tipAngle, tipFollow = o.tipFollow,
        inkCurve = o.inkCurve, inkLight = o.inkLight, inkAngle = o.inkAngle, taperIn = o.taperIn, taperOut = o.taperOut, inkIrr = o.inkIrr, inkIrrScale = o.inkIrrScale, aaSoft = o.aaSoft, joinSharp = o.joinSharp)
}

/** The strokes of one keyframe (a cel) with the interpolation set on that key. */
data class VectorKeyframe(val frameIndex: Int, val strokes: List<VectorStroke>, val interpolationConfig: InterpolationConfig? = null)

enum class InterpolationMode(val label: String) {
    NONE("Hold"),
    SAME_STROKE("Same line"),
    DIFFERENT_STROKES("Different lines")
}

/**
 * How the span that starts at this key is interpolated and baked. [curveType] uses the Camera / Transformation easing codes
 * (0 hold, 1 linear, 2 ease in-out, 3 smooth in, 4 smooth out; see easeFn) with [ease] as the strength.
 * [customBezier] = x1, y1, x2, y2 of a cubic Bezier easing; when set it replaces [curveType].
 */
data class InterpolationConfig(
    val mode: InterpolationMode = InterpolationMode.SAME_STROKE,
    val curveType: Int = 1,
    val ease: Float = 0.33f,
    val customBezier: FloatArray? = null,
    val bakeStep: Int = 1
) {
    override fun equals(other: Any?): Boolean =
        other is InterpolationConfig && mode == other.mode && curveType == other.curveType && ease == other.ease &&
            bakeStep == other.bakeStep && (customBezier?.toList() == other.customBezier?.toList())
    override fun hashCode(): Int = ((mode.ordinal * 31 + curveType) * 31 + ease.hashCode()) * 31 + bakeStep
}

fun newStrokeId(): String = java.util.UUID.randomUUID().toString()

private val VECTOR_BRUSH = Brush("vector")

// ---------- Ink <-> VectorStroke ----------

fun VectorStroke.toInk(): Ink {
    val p = FloatArray(points.size * 4)
    points.forEachIndexed { i, pt -> p[i * 4] = pt.x; p[i * 4 + 1] = pt.y; p[i * 4 + 2] = pt.pressure; p[i * 4 + 3] = pt.elevationOffset }
    val m = ArrayList<Float>(3 + 2 * points.size)
    m.add(baseWidth); m.add(if (isClosed) 1f else 0f); m.add(opacity)
    points.forEachIndexed { i, pt -> if (pt.anchor > 0 || pt.hidden) { m.add(i.toFloat()); m.add((pt.anchor + if (pt.hidden) 128 else 0).toFloat()) } } // sparse anchors: point index, rank (+128 = hidden point)
    points.forEachIndexed { i, pt -> if (pt.auto && pt.anchor > 0) { m.add((AUTO_FLAG_OFFSET + i).toFloat()); m.add(1f) } } // automatic anchors: AUTO_FLAG_OFFSET + point index
    return Ink(p, color, VECTOR_BRUSH, 5, null, m.toFloatArray(), (if (hasStyle()) "$id|$brushId|$aa|$wobble|$wobbleScale|$wobbleSeed|$hardness|$texId|$texAmt|$texScale|${if (texInvert) 1 else 0}|$texImg|$tipShape|$roundness|$tipAngle|${if (tipFollow) 1 else 0}|$fill|$fillGap|$fillGrow|${if (fillAcross) 1 else 0}|$fillSeedX|$fillSeedY|$inkCurve|$inkLight|$inkAngle|$taperIn|$taperOut|$inkIrr|$inkIrrScale|$aaSoft|$joinSharp"
        else if (wobble > 0.01f) "$id|$brushId|$aa|$wobble|$wobbleScale|$wobbleSeed" else if (aa < 0.999f) "$id|$brushId|$aa" else "$id|$brushId").toByteArray(Charsets.UTF_8))
}

fun Ink.toVectorStroke(): VectorStroke? {
    if (kind != 5) return null
    val n = p.size / 4
    val pts = ArrayList<VectorPoint>(n)
    for (i in 0 until n) pts.add(VectorPoint(p[i * 4], p[i * 4 + 1], p[i * 4 + 2], p[i * 4 + 3]))
    val head = data?.toString(Charsets.UTF_8) ?: ""
    val bar = head.indexOf('|')
    val sid = (if (bar >= 0) head.substring(0, bar) else head).ifBlank { newStrokeId() }
    val rest = if (bar >= 0) head.substring(bar + 1) else ""
    val parts = rest.split('|')
    val bid = parts[0]
    val saa = (parts.getOrNull(1)?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f) // strokes saved before this have no value: anti-aliased as always
    val swb = (parts.getOrNull(2)?.toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
    val swsc = (parts.getOrNull(3)?.toFloatOrNull() ?: 60f).coerceIn(2f, 1000f)
    val swseed = parts.getOrNull(4)?.toIntOrNull() ?: 0
    fun pf(i: Int, d: Float) = parts.getOrNull(i)?.toFloatOrNull() ?: d
    fun pi(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
    val mm = m ?: floatArrayOf(4f, 0f, 1f)
    var k = 3
    while (k + 1 < mm.size) { val i = mm[k].toInt(); val r = mm[k + 1].toInt(); if (i in pts.indices) pts[i] = pts[i].copy(anchor = (r and 127).coerceIn(0, ANCHOR_MAX), hidden = r >= 128) else if (i >= AUTO_FLAG_OFFSET && i - AUTO_FLAG_OFFSET in pts.indices) pts[i - AUTO_FLAG_OFFSET] = pts[i - AUTO_FLAG_OFFSET].copy(auto = true); k += 2 }
    return VectorStroke(sid, pts, color, mm.getOrElse(0) { 4f }, bid, mm.getOrElse(1) { 0f } > 0.5f, mm.getOrElse(2) { 1f }, saa, swb, swsc, swseed,
        hardness = pf(5, 1f).coerceIn(0f, 1f), texId = pi(6).coerceIn(0, TEXTURE_NAMES.size - 1), texAmt = pf(7, 0.7f).coerceIn(0f, 1f), texScale = pf(8, 1f).coerceIn(0.25f, 4f),
        texInvert = pi(9) == 1, texImg = pi(10), tipShape = pi(11).coerceIn(0, TIP_NAMES.size - 1), roundness = pf(12, 1f).coerceIn(0.1f, 1f), tipAngle = pf(13, 0f), tipFollow = pi(14) == 1,
        fill = pi(15).coerceIn(0, 2), fillGap = pi(16), fillGrow = pi(17), fillAcross = pi(18) == 1, fillSeedX = pf(19, 0f), fillSeedY = pf(20, 0f),
        inkCurve = pf(21, 0f).coerceIn(0f, 1f), inkLight = pf(22, 0f).coerceIn(0f, 1f), inkAngle = pf(23, 315f), taperIn = pf(24, 0f).coerceIn(0f, 0.5f), taperOut = pf(25, 0f).coerceIn(0f, 0.5f),
        inkIrr = pf(26, 0f).coerceIn(0f, 3f), inkIrrScale = pf(27, 40f).coerceIn(0.5f, 400f), aaSoft = pf(28, 0f).coerceIn(0f, 4f), joinSharp = pf(29, 0f).coerceIn(0f, 1f))
}

fun Cel.vectorStrokes(): List<VectorStroke> = strokes.mapNotNull { it.toVectorStroke() }

/** Stroke key used by the point selection: stroke index in the cel and point index. */
fun vecKey(stroke: Int, point: Int): Long = (stroke.toLong() shl 32) or (point.toLong() and 0xFFFFFFFFL)
fun vecKeyStroke(k: Long): Int = (k shr 32).toInt()
fun vecKeyPoint(k: Long): Int = k.toInt()

// ---------- Ribbon tessellation ----------

object VectorTessellator {
    const val CAP_SEGMENTS = 6
    /** Below this miter alignment (m . n) a sharp corner gets a bevel instead of an ever longer miter spike. */
    const val MITER_DOT_LIMIT = 0.25f
    private const val MIN_HALF = 0.02f

    /** r_i = baseWidth / 2 * pressure + elevationOffset (never below a hairline). */
    fun halfWidth(s: VectorStroke, i: Int): Float {
        val p = s.points[i]
        if (p.hidden) return 0f
        return (s.baseWidth * 0.5f * p.pressure + p.elevationOffset).coerceAtLeast(MIN_HALF)
    }

    /** Left / right outline of the stroke as pairs (x, y interleaved) plus the half width at each pair. */
    class Ribbon(val left: FloatArray, val right: FloatArray, val half: FloatArray, val pairs: Int, val closed: Boolean,
                 val endCap: FloatArray?, val startCap: FloatArray?)

    // ----- hand-drawn warmth: the outline follows a smooth curve through the control points instead of straight chords -----
    /** A turn sharper than this (cosine of the angle between the incoming and outgoing direction, ~55 degrees) stays a corner. */
    private const val CORNER_COS = 0.57f
    /** A turn this close to a full reversal (cosine below this, ~170 degrees) cannot be filleted; it stays a corner and gets a round join in [ribbon]. */
    private const val REVERSAL_COS = -0.985f
    /** Largest distance the drawn curve may be from a straight chord of the flattened outline, in document pixels. */
    private const val FLAT_TOL = 0.12f
    private const val MAX_SUB = 16

    private class Dense(val xs: FloatArray, val ys: FloatArray, val rs: FloatArray, val m: Int)

    /** Centripetal Catmull-Rom point at [u] (0..1) of the span P1->P2; [p] = x0,y0,x1,y1,x2,y2,x3,y3, result into [out]. */
    private fun crAt(p: FloatArray, u: Float, out: FloatArray) {
        fun knot(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.sqrt(max(hypot(bx - ax, by - ay), 1e-4f))
        val k0 = 0f
        val k1 = k0 + knot(p[0], p[1], p[2], p[3])
        val k2 = k1 + knot(p[2], p[3], p[4], p[5])
        val k3 = k2 + knot(p[4], p[5], p[6], p[7])
        val t = k1 + (k2 - k1) * u
        for (c in 0..1) {
            val q0 = p[c]; val q1 = p[2 + c]; val q2 = p[4 + c]; val q3 = p[6 + c]
            val a1 = (k1 - t) / (k1 - k0) * q0 + (t - k0) / (k1 - k0) * q1
            val a2 = (k2 - t) / (k2 - k1) * q1 + (t - k1) / (k2 - k1) * q2
            val a3 = (k3 - t) / (k3 - k2) * q2 + (t - k2) / (k3 - k2) * q3
            val b1 = (k2 - t) / (k2 - k0) * a1 + (t - k0) / (k2 - k0) * a2
            val b2 = (k3 - t) / (k3 - k1) * a2 + (t - k1) / (k3 - k1) * a3
            out[c] = (k2 - t) / (k2 - k1) * b1 + (t - k1) / (k2 - k1) * b2
        }
    }

    /**
     * Densifies the m control points into a polyline that follows a centripetal Catmull-Rom curve through them (so every control point,
     * and the small wobble of the hand between them, is kept). Pieces per span follow how far the curve bows from its chord: a straight
     * stretch gets none, a gentle arc a few. Sharp turns stay corners. Width is interpolated linearly.
     */
    /**
     * Rounds every sharp turn (more than ~55 degrees, less than ~170) with a small circular arc whose radius follows the line width
     * (never more than 45% of the neighbouring segments), the way a pen turns instead of a ruler. Returns the input unchanged when no turn needed it.
     */
    private fun fillet(xs: FloatArray, ys: FloatArray, rs: FloatArray, m: Int, closed: Boolean, sharp: Float = 0f): Dense {
        if (sharp > 0.95f) return Dense(xs, ys, rs, m) // fully sharp: no rounding of the turns at all
        var ox = FloatArray(m + 16); var oy = FloatArray(m + 16); var orr = FloatArray(m + 16)
        var c = 0; var any = false
        fun add(x: Float, y: Float, r: Float) {
            if (c >= ox.size) { val n = ox.size * 2; ox = ox.copyOf(n); oy = oy.copyOf(n); orr = orr.copyOf(n) }
            ox[c] = x; oy[c] = y; orr[c] = r; c++
        }
        for (i in 0 until m) {
            if (!closed && (i == 0 || i == m - 1)) { add(xs[i], ys[i], rs[i]); continue }
            val a = (i - 1 + m) % m; val b = (i + 1) % m
            val ix = xs[i] - xs[a]; val iy = ys[i] - ys[a]; val tx = xs[b] - xs[i]; val ty = ys[b] - ys[i]
            val il = hypot(ix, iy); val tl = hypot(tx, ty)
            if (il < 1e-6f || tl < 1e-6f) { add(xs[i], ys[i], rs[i]); continue }
            val cs = (ix * tx + iy * ty) / (il * tl)
            if (cs >= CORNER_COS || cs <= REVERSAL_COS) { add(xs[i], ys[i], rs[i]); continue }
            val th = acos(cs.coerceIn(-1f, 1f)) // deflection angle, 55..170 degrees
            val half = tan(th / 2f)
            val d = min(max(1.2f, 1.6f * rs[i]) * (1f - sharp) * half, 0.45f * min(il, tl))
            val rho = d / half
            val ux = ix / il; val uy = iy / il
            val s = if (ux * ty - uy * tx >= 0f) 1f else -1f
            val ax = xs[i] - ux * d; val ay = ys[i] - uy * d
            val cx = ax + (-uy * s) * rho; val cy = ay + (ux * s) * rho // centre of the arc, on the side the line turns to
            val vx = ax - cx; val vy = ay - cy
            val steps = ceil(th / (Math.PI.toFloat() / 10f)).toInt().coerceIn(2, 10)
            for (q in 0..steps) {
                val ang = s * th * q / steps
                val co = cos(ang); val si = sin(ang)
                add(cx + vx * co - vy * si, cy + vx * si + vy * co, rs[i])
            }
            any = true
        }
        return if (any) Dense(ox, oy, orr, c) else Dense(xs, ys, rs, m)
    }

    private fun smoothThrough(xs0: FloatArray, ys0: FloatArray, rs0: FloatArray, m0: Int, closed: Boolean, sharp: Float = 0f): Dense {
        if (m0 < 3) return Dense(xs0, ys0, rs0, m0)
        val f = fillet(xs0, ys0, rs0, m0, closed, sharp)
        val cornerCos = REVERSAL_COS + (0.85f - REVERSAL_COS) * sharp // sharp > 0: bends of more than about 30 degrees (at 1) stay corners of the curve
        val xs = f.xs; val ys = f.ys; val rs = f.rs; val m = f.m
        val corner = BooleanArray(m)
        for (i in 0 until m) {
            if (!closed && (i == 0 || i == m - 1)) { corner[i] = true; continue }
            val a = (i - 1 + m) % m; val b = (i + 1) % m
            val ix = xs[i] - xs[a]; val iy = ys[i] - ys[a]; val ox = xs[b] - xs[i]; val oy = ys[b] - ys[i]
            val il = hypot(ix, iy); val ol = hypot(ox, oy)
            corner[i] = il < 1e-6f || ol < 1e-6f || (ix * ox + iy * oy) / (il * ol) < cornerCos
        }
        val segs = if (closed) m else m - 1
        val ctrl = FloatArray(8); val tmp = FloatArray(2)
        fun load(k: Int) {
            val i1 = k; val i2 = (k + 1) % m
            ctrl[2] = xs[i1]; ctrl[3] = ys[i1]; ctrl[4] = xs[i2]; ctrl[5] = ys[i2]
            if (corner[i1]) { ctrl[0] = 2 * ctrl[2] - ctrl[4]; ctrl[1] = 2 * ctrl[3] - ctrl[5] }
            else { val i0 = (i1 - 1 + m) % m; ctrl[0] = xs[i0]; ctrl[1] = ys[i0] }
            if (corner[i2]) { ctrl[6] = 2 * ctrl[4] - ctrl[2]; ctrl[7] = 2 * ctrl[5] - ctrl[3] }
            else { val i3 = (i2 + 1) % m; ctrl[6] = xs[i3]; ctrl[7] = ys[i3] }
        }
        // pass 1: how many pieces each span needs
        val pieces = IntArray(segs)
        var total = 0
        for (k in 0 until segs) {
            load(k)
            val cx = ctrl[4] - ctrl[2]; val cy = ctrl[5] - ctrl[3]
            val cl = hypot(cx, cy)
            var dev = 0f
            if (cl > 1e-6f) for (u in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                crAt(ctrl, u, tmp)
                dev = max(dev, abs((tmp[0] - ctrl[2]) * cy - (tmp[1] - ctrl[3]) * cx) / cl)
            }
            pieces[k] = if (dev < 0.03f) 1 else kotlin.math.ceil(kotlin.math.sqrt(dev / FLAT_TOL)).toInt().coerceIn(1, MAX_SUB)
            total += pieces[k]
        }
        if (total == segs) return Dense(xs, ys, rs, m) // nothing bends: keep the points as they are
        val n = total + if (closed) 0 else 1
        val ox = FloatArray(n); val oy = FloatArray(n); val orr = FloatArray(n)
        var c = 0
        for (k in 0 until segs) {
            load(k)
            val j = (k + 1) % m
            val np = pieces[k]
            for (q in 0 until np) {
                if (q == 0) { ox[c] = xs[k]; oy[c] = ys[k]; orr[c] = rs[k] }
                else {
                    val u = q.toFloat() / np
                    crAt(ctrl, u, tmp)
                    ox[c] = tmp[0]; oy[c] = tmp[1]; orr[c] = rs[k] + (rs[j] - rs[k]) * u
                }
                c++
            }
        }
        if (!closed) { ox[c] = xs[m - 1]; oy[c] = ys[m - 1]; orr[c] = rs[m - 1]; c++ }
        return Dense(ox, oy, orr, c)
    }

    private fun wHash(seed: Int, k: Int): Float {
        var h = seed * 374761393 + k * 668265263
        h = (h xor (h ushr 13)) * 1274126177.toInt()
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /** Smooth value noise in -1..1; [cells] > 0 makes it periodic (a closed line meets itself). */
    private fun wNoise(seed: Int, t: Float, cells: Int): Float {
        val k = kotlin.math.floor(t).toInt(); val f = t - k; val u = f * f * (3f - 2f * f)
        fun h(i: Int) = wHash(seed, if (cells > 0) ((i % cells) + cells) % cells else i)
        return h(k) + (h(k + 1) - h(k)) * u
    }

    /**
     * "Boil": the drawn curve is cut into pieces shorter than the wavelength and every piece point is pushed by smooth noise along the line
     * (different noise for x and y), so each keyframe wobbles differently while the stored points stay clean.
     */
    private fun wobbleDense(d: Dense, closed: Boolean, amount: Float, scale: Float, seed: Int): Dense {
        if (d.m < 2) return d
        val sc = max(2f, scale)
        val segs = if (closed) d.m else d.m - 1
        var total = 0f
        for (k in 0 until segs) { val j = (k + 1) % d.m; total += hypot(d.xs[j] - d.xs[k], d.ys[j] - d.ys[k]) }
        if (total / (sc * 0.4f) > 20000f) return d
        var ox = FloatArray(d.m * 2 + 16); var oy = FloatArray(ox.size); var orr = FloatArray(ox.size); var oa = FloatArray(ox.size)
        var c = 0; var acc = 0f
        fun add(x: Float, y: Float, r: Float, a: Float) {
            if (c >= ox.size) { val n = ox.size * 2; ox = ox.copyOf(n); oy = oy.copyOf(n); orr = orr.copyOf(n); oa = oa.copyOf(n) }
            ox[c] = x; oy[c] = y; orr[c] = r; oa[c] = a; c++
        }
        for (k in 0 until segs) {
            val j = (k + 1) % d.m
            val l = hypot(d.xs[j] - d.xs[k], d.ys[j] - d.ys[k])
            val np = ceil(l / (sc * 0.4f)).toInt().coerceIn(1, 48)
            for (q in 0 until np) {
                val u = q.toFloat() / np
                add(d.xs[k] + (d.xs[j] - d.xs[k]) * u, d.ys[k] + (d.ys[j] - d.ys[k]) * u, d.rs[k] + (d.rs[j] - d.rs[k]) * u, acc + l * u)
            }
            acc += l
        }
        if (!closed) add(d.xs[d.m - 1], d.ys[d.m - 1], d.rs[d.m - 1], acc)
        val cells = if (closed) max(1, (total / sc).roundToInt()) else 0
        val seedY = seed * 31 + 7
        for (i in 0 until c) {
            val t = if (closed) oa[i] / max(total, 1e-3f) * cells else oa[i] / sc
            ox[i] += amount * wNoise(seed, t, cells); oy[i] += amount * wNoise(seedY, t, cells)
        }
        return Dense(ox, oy, orr, c)
    }

    /** Point at [u] (0..1) of the span from control point [i] to the next one, on the smooth curve the line is drawn along (x, y into [out]); neighbours at an open end are mirrored. */
    fun curveAt(pts: List<VectorPoint>, closed: Boolean, i: Int, u: Float, out: FloatArray) {
        val n = pts.size
        fun at(k: Int): VectorPoint = if (closed) pts[((k % n) + n) % n] else pts[k.coerceIn(0, n - 1)]
        val p1 = at(i); val p2 = at(i + 1)
        var p0 = if (!closed && i <= 0) VectorPoint(2 * p1.x - p2.x, 2 * p1.y - p2.y) else at(i - 1)
        var p3 = if (!closed && i + 2 > n - 1) VectorPoint(2 * p2.x - p1.x, 2 * p2.y - p1.y) else at(i + 2)
        crAt(floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y), u, out)
    }

    /** The curve the control points are drawn along, as x, y pairs: what [ribbon] builds the outline around (used to fit points to a drawn line). */
    fun centerline(points: List<VectorPoint>, closed: Boolean): FloatArray {
        val m = points.size
        val d = smoothThrough(FloatArray(m) { points[it].x }, FloatArray(m) { points[it].y }, FloatArray(m), m, closed)
        val out = FloatArray(d.m * 2)
        for (i in 0 until d.m) { out[i * 2] = d.xs[i]; out[i * 2 + 1] = d.ys[i] }
        return out
    }

    /** Returns null for fewer than two distinct points. */
    /** Half-width factor of the tip seen along the unit normal (nx, ny) of the line: the tip's extent across the line. */
    private fun tipFactor(s: VectorStroke, nx: Float, ny: Float): Float {
        val axis = Math.toRadians(s.tipAngle.toDouble()).toFloat() + if (s.tipFollow) atan2(-nx, ny) else 0f
        val c = cos(axis); val sn = sin(axis)
        val px = nx * c + ny * sn; val py = -nx * sn + ny * c // the normal in the tip's own axes
        val ro = s.roundness.coerceIn(0.1f, 1f)
        val f = when (s.tipShape) {
            2 -> 0.9f * abs(px) + 0.9f * ro * abs(py)
            1 -> {
                fun sup(x: Float, y: Float) = max(x, max(-0.5f * x + 0.866f * ro * y, -0.5f * x - 0.866f * ro * y))
                (sup(px, py) + sup(-px, -py)) / 2f
            }
            else -> kotlin.math.sqrt(px * px + ro * ro * py * py)
        }
        return f.coerceIn(0.05f, 1.6f)
    }

    private const val INK_MAX = 2.5f

    private fun lattice(seed: Int, k: Int): Float {
        var h = seed xor (k * -1640531535)
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }
    /** Smooth value noise in -1..1; [period] > 0 repeats every that many lattice cells (so a closed line has no seam). */
    private fun vnoise(seed: Int, x: Float, period: Int): Float {
        val x0 = Math.floor(x.toDouble()).toInt(); val f = x - x0
        fun v(k: Int) = lattice(seed, if (period > 0) Math.floorMod(k, period) else k)
        val t = f * f * (3f - 2f * f)
        return v(x0) * (1f - t) + v(x0 + 1) * t
    }

    /**
     * Designed line weight on the smoothed outline: multiplies every half width [rs] by a curvature term, a light-direction term and the end tapers.
     * Curvature and facing are smoothed along the line (about 8 px) so the width changes gently. "Outward" is away from the centre of the line's points,
     * which makes the light term work the same on open and closed lines (a contour facing away from the light is the shadow side).
     */
    private fun inkWeight(s: VectorStroke, xs: FloatArray, ys: FloatArray, rs: FloatArray, dx: FloatArray, dy: FloatArray, len: FloatArray, m: Int, closed: Boolean) {
        val curv = FloatArray(m); val face = FloatArray(m)
        var cx = 0f; var cy = 0f
        for (i in 0 until m) { cx += xs[i]; cy += ys[i] }
        cx /= m; cy /= m
        val la = Math.toRadians(s.inkAngle.toDouble()).toFloat()
        val lx = cos(la); val ly = sin(la)
        var total = 0f
        for (k in len.indices) total += len[k]
        for (i in 0 until m) {
            val a = if (closed) (i - 1 + m) % m else if (i == 0) 0 else i - 1
            val b = if (closed) i else if (i == m - 1) m - 2 else i
            val turn = atan2(dx[a] * dy[b] - dy[a] * dx[b], dx[a] * dx[b] + dy[a] * dy[b])
            curv[i] = abs(turn) / ((len[a] + len[b]) * 0.5f).coerceAtLeast(1e-3f)
            var nx = -(dy[a] + dy[b]); var ny = dx[a] + dx[b]
            val nl = hypot(nx, ny)
            if (nl < 1e-4f) { nx = -dy[a]; ny = dx[a] } else { nx /= nl; ny /= nl }
            val side = if ((xs[i] - cx) * nx + (ys[i] - cy) * ny >= 0f) 1f else -1f
            face[i] = side * (nx * lx + ny * ly)
        }
        val spacing = (total / m).coerceAtLeast(0.05f)
        val passes = (128f / (spacing * spacing)).toInt().coerceIn(2, 60)
        fun smooth(v: FloatArray) {
            if (m < 3) return
            val t = FloatArray(m)
            repeat(passes) {
                for (i in 0 until m) {
                    val p = if (i == 0) (if (closed) m - 1 else 0) else i - 1
                    val q = if (i == m - 1) (if (closed) 0 else m - 1) else i + 1
                    t[i] = 0.25f * v[p] + 0.5f * v[i] + 0.25f * v[q]
                }
                System.arraycopy(t, 0, v, 0, m)
            }
        }
        if (s.inkCurve > 0.001f) smooth(curv)
        if (s.inkLight > 0.001f) smooth(face)
        val irr = s.inkIrr
        val irrSeed = s.id.hashCode()
        val irrScale = s.inkIrrScale.coerceIn(0.5f, 400f)
        val period = if (closed) max(2, (total / irrScale).roundToInt()) else 0
        var run = 0f
        for (i in 0 until m) {
            var f = 1f
            if (irr > 0.001f) { // width swells along the line regardless of its shape; two octaves, seeded by the stroke id so the same line looks the same on every frame
                val x = if (closed) run / total.coerceAtLeast(1e-3f) * period else run / irrScale
                val n = if (closed) 0.7f * vnoise(irrSeed, x, period) + 0.3f * vnoise(irrSeed + 1, x * 2f, period * 2)
                        else 0.7f * vnoise(irrSeed, x, 0) + 0.3f * vnoise(irrSeed + 1, x * 2.3f, 0)
                f *= 1f + irr * 0.75f * n
            }
            if (s.inkCurve > 0.001f) f *= 1f + s.inkCurve * (min(1f, curv[i] * 30f) - 0.3f) // radius 30 px or tighter = full bend
            if (s.inkLight > 0.001f) f *= 1f - s.inkLight * 0.6f * face[i].coerceIn(-1f, 1f) // facing the light: thinner; facing away: heavier
            if (!closed && total > 1e-3f) {
                val u = (run / total).coerceIn(0f, 1f)
                if (s.taperIn > 0.001f && u < s.taperIn) { val x = u / s.taperIn; f *= 0.12f + 0.88f * x * x * (3f - 2f * x) }
                if (s.taperOut > 0.001f && u > 1f - s.taperOut) { val x = (1f - u) / s.taperOut; f *= 0.12f + 0.88f * x * x * (3f - 2f * x) }
            }
            rs[i] *= f.coerceIn(0.08f, INK_MAX)
            if (i < len.size) run += len[i]
        }
    }

    fun ribbon(s: VectorStroke): Ribbon? {
        val n0 = s.points.size
        if (n0 < 2) return null
        // drop repeated points
        var xs = FloatArray(n0); var ys = FloatArray(n0); var rs = FloatArray(n0)
        var m = 0
        for (i in 0 until n0) {
            val p = s.points[i]
            if (m > 0 && hypot(p.x - xs[m - 1], p.y - ys[m - 1]) < 1e-3f) { rs[m - 1] = max(rs[m - 1], halfWidth(s, i)); continue }
            xs[m] = p.x; ys[m] = p.y; rs[m] = halfWidth(s, i); m++
        }
        var closed = s.isClosed
        if (closed && m >= 3 && hypot(xs[0] - xs[m - 1], ys[0] - ys[m - 1]) < 1e-3f) m-- // last point repeats the first
        if (m < 2) return null
        if (m < 3) closed = false
        smoothThrough(xs, ys, rs, m, closed, s.joinSharp).let { d0 -> val d = if (s.wobble > 0.01f) wobbleDense(d0, closed, s.wobble, s.wobbleScale, s.wobbleSeed) else d0; xs = d.xs; ys = d.ys; rs = d.rs; m = d.m }
        val segs = if (closed) m else m - 1
        val dx = FloatArray(segs); val dy = FloatArray(segs); val len = FloatArray(segs)
        for (k in 0 until segs) {
            val j = (k + 1) % m
            val ex = xs[j] - xs[k]; val ey = ys[j] - ys[k]
            val l = hypot(ex, ey).coerceAtLeast(1e-6f)
            dx[k] = ex / l; dy[k] = ey / l; len[k] = l
        }
        if (s.hasInk() && s.fill == 0) inkWeight(s, xs, ys, rs, dx, dy, len, m, closed)
        if (s.tipActive()) { // the width follows the tip: an ellipse / triangle / rectangle whose axis is fixed (angle) or turns with the line (follow)
            for (i in 0 until m) {
                val a = if (closed) (i - 1 + m) % m else if (i == 0) 0 else i - 1
                val b = if (closed) i else if (i == m - 1) m - 2 else i
                var nx = -(dy[a] + dy[b]); var ny = dx[a] + dx[b]
                val nl = hypot(nx, ny)
                if (nl < 1e-4f) { nx = -dy[a]; ny = dx[a] } else { nx /= nl; ny /= nl }
                rs[i] *= tipFactor(s, nx, ny)
            }
        }
        val cap = m * 2 + 2
        var left = FloatArray(cap * 2); var right = FloatArray(cap * 2); var half = FloatArray(cap)
        var c = 0
        fun put(lx: Float, ly: Float, rx: Float, ry: Float, r: Float) {
            if (c >= half.size) { val n = half.size * 2; left = left.copyOf(n * 2); right = right.copyOf(n * 2); half = half.copyOf(n) }
            left[c * 2] = lx; left[c * 2 + 1] = ly; right[c * 2] = rx; right[c * 2 + 1] = ry; half[c] = r; c++
        }
        for (i in 0 until m) {
            val a: Int; val b: Int
            if (closed) { a = (i - 1 + m) % m; b = i } else { a = if (i == 0) 0 else i - 1; b = if (i == m - 1) m - 2 else i }
            val r = rs[i]
            val n0x = -dy[a]; val n0y = dx[a]; val n1x = -dy[b]; val n1y = dx[b]
            var sx = n0x + n1x; var sy = n0y + n1y
            val sl = hypot(sx, sy)
            val reversed = sl < 1e-4f
            if (reversed) { sx = n1x; sy = n1y } else { sx /= sl; sy /= sl }
            val dot = if (reversed) 0f else sx * n1x + sy * n1y
            if (dot >= MITER_DOT_LIMIT) {
                val l = r / dot
                put(xs[i] + sx * l, ys[i] + sy * l, xs[i] - sx * l, ys[i] - sy * l, r)
            } else {
                // Sharp turn (only near reversals and closed shapes are left after the fillet): the outer side goes round the point in a small arc
                // (a round join), the inner side gets one clamped miter point.
                val cross = dx[a] * dy[b] - dy[a] * dx[b] // > 0: the path turns toward its left, so the left side is the inner side
                val inner = min(r / max(dot, MITER_DOT_LIMIT), max(r, min(len[a], len[b])))
                var ang = if (reversed) -Math.PI.toFloat() else atan2(n0x * n1y - n0y * n1x, n0x * n1x + n0y * n1y)
                val steps = ceil(abs(ang) / (Math.PI.toFloat() / 8f)).toInt().coerceIn(1, 8)
                if (cross > 0f) {
                    val ix = xs[i] + sx * inner; val iy = ys[i] + sy * inner
                    for (q in 0..steps) {
                        val an = ang * q / steps; val co = cos(an); val si = sin(an)
                        put(ix, iy, xs[i] - (n0x * co - n0y * si) * r, ys[i] - (n0x * si + n0y * co) * r, r)
                    }
                } else {
                    val ix = xs[i] - sx * inner; val iy = ys[i] - sy * inner
                    for (q in 0..steps) {
                        val an = ang * q / steps; val co = cos(an); val si = sin(an)
                        put(xs[i] + (n0x * co - n0y * si) * r, ys[i] + (n0x * si + n0y * co) * r, ix, iy, r)
                    }
                }
            }
        }
        var endCap: FloatArray? = null; var startCap: FloatArray? = null
        if (!closed) {
            // End cap: from the left edge around the forward direction to the right edge. Start cap: from the right edge around the backward direction to the left edge.
            val e = m - 1
            endCap = FloatArray((CAP_SEGMENTS - 1) * 2)
            startCap = FloatArray((CAP_SEGMENTS - 1) * 2)
            val enx = -dy[segs - 1]; val eny = dx[segs - 1]; val edx = dx[segs - 1]; val edy = dy[segs - 1]
            val snx = -dy[0]; val sny = dx[0]; val sdx = dx[0]; val sdy = dy[0]
            for (j in 1 until CAP_SEGMENTS) {
                val th = Math.PI.toFloat() * j / CAP_SEGMENTS
                val cs = cos(th); val sn = sin(th)
                endCap[(j - 1) * 2] = xs[e] + rs[e] * (enx * cs + edx * sn); endCap[(j - 1) * 2 + 1] = ys[e] + rs[e] * (eny * cs + edy * sn)
                startCap[(j - 1) * 2] = xs[0] + rs[0] * (-snx * cs - sdx * sn); startCap[(j - 1) * 2 + 1] = ys[0] + rs[0] * (-sny * cs - sdy * sn)
            }
        }
        return Ribbon(left, right, half, c, closed, endCap, startCap)
    }

    /**
     * GL_TRIANGLE_STRIP vertices, stride 4: x, y, signed cross-stroke distance, half width. [fringe] pads the quad for shader anti-aliasing.
     * Open strokes are one strip; the ends get no fan here (the CPU path draws round caps; a GL pass would add 4-6 segment fans).
     */
    fun strip(s: VectorStroke, fringe: Float = 0f): FloatArray {
        val rb = ribbon(s) ?: return FloatArray(0)
        val extra = if (rb.closed) 2 else 0
        val out = FloatArray((rb.pairs * 2 + extra) * 4)
        var o = 0
        fun v(x: Float, y: Float, cross: Float, r: Float) { out[o++] = x; out[o++] = y; out[o++] = cross; out[o++] = r }
        for (i in 0 until rb.pairs) {
            val r = rb.half[i]
            v(rb.left[i * 2], rb.left[i * 2 + 1], r + fringe, r)
            v(rb.right[i * 2], rb.right[i * 2 + 1], -(r + fringe), r)
        }
        if (rb.closed) { // stitch the last pair back to the first
            val r = rb.half[0]
            v(rb.left[0], rb.left[1], r + fringe, r); v(rb.right[0], rb.right[1], -(r + fringe), r)
        }
        return out
    }

    /** A fill: the closed smooth curve through its points, filled (even-odd), no outline width. Null with fewer than 3 points. */
    fun fillPath(s: VectorStroke): Path? {
        val n = s.points.size
        if (n < 3) return null
        val xs = FloatArray(n) { s.points[it].x }; val ys = FloatArray(n) { s.points[it].y }; val rs = FloatArray(n)
        val d = smoothThrough(xs, ys, rs, n, true, 1f) // sharp: no rounding of the corners, so the fill reaches into small corners under the lines
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.moveTo(d.xs[0], d.ys[0])
        for (i in 1 until d.m) path.lineTo(d.xs[i], d.ys[i])
        path.close()
        return path
    }

    /** Outline of the stroke: miter / bevel joins and round caps for an open stroke, an even-odd ring for a closed one. Null for a single point. */
    fun path(s: VectorStroke): Path? {
        if (s.fill != 0) return fillPath(s)
        val rb = ribbon(s) ?: return null
        val path = Path()
        if (rb.closed) {
            path.fillType = Path.FillType.EVEN_ODD
            path.moveTo(rb.left[0], rb.left[1])
            for (i in 1 until rb.pairs) path.lineTo(rb.left[i * 2], rb.left[i * 2 + 1])
            path.close()
            path.moveTo(rb.right[0], rb.right[1])
            for (i in 1 until rb.pairs) path.lineTo(rb.right[i * 2], rb.right[i * 2 + 1])
            path.close()
        } else {
            path.fillType = Path.FillType.WINDING
            path.moveTo(rb.left[0], rb.left[1])
            for (i in 1 until rb.pairs) path.lineTo(rb.left[i * 2], rb.left[i * 2 + 1])
            rb.endCap?.let { cp -> var j = 0; while (j < cp.size) { path.lineTo(cp[j], cp[j + 1]); j += 2 } }
            for (i in rb.pairs - 1 downTo 0) path.lineTo(rb.right[i * 2], rb.right[i * 2 + 1])
            rb.startCap?.let { cp -> var j = 0; while (j < cp.size) { path.lineTo(cp[j], cp[j + 1]); j += 2 } }
            path.close()
        }
        return path
    }

    /** Document-space bounds (left, top, right, bottom), grown by the widest radius and a pixel of anti-aliasing. */
    fun bounds(s: VectorStroke): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var w = 0f
        for (i in s.points.indices) {
            val p = s.points[i]
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            w = max(w, halfWidth(s, i))
        }
        if (s.hasInk()) w *= INK_MAX
        if (s.points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val g = w * 2f + 3f + (if (s.fill != 0) 8f else 0f) + s.wobble * 1.5f + s.aaSoft * 3f + (1f - s.hardness.coerceIn(0f, 1f)) * max(w, 1f) * 2.5f // miter spikes are bounded by the 0.25 limit (<= 4 r); 2 r is enough for bevelled joins
        return floatArrayOf(l - g, t - g, r + g, b + g)
    }

    /** Rasterizes one stroke on [cv] (document coordinates). */
    fun draw(cv: Canvas, s: VectorStroke, paint: Paint) {
        if (s.points.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = s.aa > 0.02f
        val a = (((s.color ushr 24) and 255) * s.opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        paint.color = (s.color and 0x00FFFFFF) or (a shl 24)
        val path = path(s)
        if (path == null && s.fill != 0) return
        if (path == null) { // a single point (or all points equal): a dot
            val p = s.points[0]
            cv.drawCircle(p.x, p.y, halfWidth(s, 0), paint)
        } else cv.drawPath(path, paint)
    }

    private fun textureShader(s: VectorStroke): android.graphics.BitmapShader? {
        val bmp = (if (s.texImg != 0) BrushAssets.textureTile(s.texImg, s.texAmt, s.texInvert) else Textures.tile(s.texId, s.texAmt, s.texInvert)) ?: return null
        return android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT).also { sh ->
            if (abs(s.texScale - 1f) > 0.01f) sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(s.texScale, s.texScale) })
        }
    }

    /**
     * Rasterizes a stroke with its brush look: a feathered edge (hardness < 1: blur of [1 - hardness] x the widest half width) and a texture
     * that multiplies the line's alpha (page-anchored, like the raster brush's texture). Meant for a software (tile) canvas, where blur masks
     * and layers work; the live preview of the stroke being drawn uses [draw] and shows neither.
     */
    fun render(cv: Canvas, s: VectorStroke, path: Path?, bounds: FloatArray, paint: Paint) {
        val soft = 1f - s.hardness.coerceIn(0f, 1f)
        val shader = if (s.texId != 0 || s.texImg != 0) textureShader(s) else null
        val feather = s.aaSoft.coerceIn(0f, 4f)
        if (soft < 0.02f && shader == null && feather < 0.05f) { drawPath(cv, s, path, paint); return }
        var w = 0f
        for (i in s.points.indices) w = max(w, halfWidth(s, i))
        val blur = max(if (soft >= 0.02f) max(0.3f, soft * max(w, 1f)) else 0f, feather) // hardness feathering and the extra anti-alias softness share one blur: the larger wins
        paint.maskFilter = if (blur >= 0.05f) android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL) else null
        if (shader != null) {
            val rect = android.graphics.RectF(bounds[0], bounds[1], bounds[2], bounds[3])
            val saved = cv.saveLayer(rect, null)
            drawPath(cv, s, path, paint)
            cv.drawRect(rect, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN); this.shader = shader })
            cv.restoreToCount(saved)
        } else drawPath(cv, s, path, paint)
        paint.maskFilter = null
    }

    /** Like [draw] with an outline built once by [path] (a stroke that spans several tiles is tessellated once, not once per tile). */
    fun drawPath(cv: Canvas, s: VectorStroke, path: Path?, paint: Paint) {
        if (path == null) { draw(cv, s, paint); return }
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = s.aa > 0.02f
        val a = (((s.color ushr 24) and 255) * s.opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        paint.color = (s.color and 0x00FFFFFF) or (a shl 24)
        cv.drawPath(path, paint)
    }
}

// ---------- Drawing input: Ramer-Douglas-Peucker ----------

object VectorSimplify {
    /** Ramer-Douglas-Peucker. Keeps the first and last point, plus every point farther than [epsilon] from the simplified line. */
    fun rdp(pts: List<VectorPoint>, epsilon: Float, widthScale: Float = 0f): List<VectorPoint> {
        val n = pts.size
        if (n < 3) return pts
        val keep = rdpKeep(pts, epsilon, widthScale)
        val out = ArrayList<VectorPoint>()
        for (i in 0 until n) if (keep[i]) out.add(pts[i])
        return out
    }

    /**
     * Fits points to the curve they will be DRAWN along (a smooth centripetal Catmull-Rom curve through them, see VectorTessellator.centerline)
     * rather than to straight chords. It starts from a coarse Ramer-Douglas-Peucker pass and adds a point only in a span where the drawn curve
     * is farther than [epsilon] from the pen samples, so gentle curves keep few points and still look hand drawn, with no flat facets.
     */
    fun fit(pts: List<VectorPoint>, epsilon: Float, widthScale: Float = 0f): List<VectorPoint> {
        val n = pts.size
        if (n < 4) return rdp(pts, epsilon, widthScale)
        val keep = rdpKeep(pts, epsilon * 1.8f, widthScale)
        var round = 0
        while (round++ < 6) {
            val idx = ArrayList<Int>()
            for (i in 0 until n) if (keep[i]) idx.add(i)
            if (idx.size >= n) break
            val line = VectorTessellator.centerline(idx.map { pts[it] }, false)
            if (line.size < 4) break
            var added = false
            for (k in 0 until idx.size - 1) {
                val a = idx[k]; val b = idx[k + 1]
                var worst = epsilon; var wi = -1
                for (i in a + 1 until b) {
                    val d = distToLine(line, pts[i].x, pts[i].y)
                    if (d > worst) { worst = d; wi = i }
                }
                if (wi >= 0) { keep[wi] = true; added = true }
            }
            if (!added) break
        }
        val out = ArrayList<VectorPoint>()
        for (i in 0 until n) if (keep[i]) out.add(pts[i])
        return out
    }

    private fun distToLine(line: FloatArray, px: Float, py: Float): Float {
        var best = Float.MAX_VALUE
        var i = 0
        while (i + 3 < line.size) {
            val d = segDist(px, py, line[i], line[i + 1], line[i + 2], line[i + 3])
            if (d < best) best = d
            i += 2
        }
        return best
    }

    private fun rdpKeep(pts: List<VectorPoint>, epsilon: Float, widthScale: Float): BooleanArray {
        val n = pts.size
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1f; var idx = -1
            val ax = pts[a].x; val ay = pts[a].y; val bx = pts[b].x; val by = pts[b].y
            val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            for (i in a + 1 until b) {
                var d = segDist(pts[i].x, pts[i].y, ax, ay, bx, by)
                if (widthScale > 0f) { // keep points where the width (pressure) departs from the straight blend of the two ends
                    val t = if (l2 < 1e-9f) 0.5f else (((pts[i].x - ax) * (bx - ax) + (pts[i].y - ay) * (by - ay)) / l2).coerceIn(0f, 1f)
                    val pv = pts[a].pressure + (pts[b].pressure - pts[a].pressure) * t
                    d = max(d, abs(pts[i].pressure - pv) * widthScale * 0.5f)
                }
                if (d > worst) { worst = d; idx = i }
            }
            if (idx >= 0 && worst > epsilon) { keep[idx] = true; stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b)) }
        }
        return keep
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }
}

// ---------- Post-stroke editing ----------

object VectorEdit {
    /** w(d) = 1 - 3 (d/R)^2 + 2 (d/R)^3 for d < R, else 0. */
    fun falloff(d: Float, radius: Float): Float {
        if (radius <= 0f) return 0f
        val x = (d / radius).coerceIn(0f, 1f)
        return 1f - 3f * x * x + 2f * x * x * x
    }

    /** The point closest to (x, y) within [radius] document px, as a [vecKey], or null. */
    fun hitPoint(strokes: List<VectorStroke>, x: Float, y: Float, radius: Float): Long? {
        var best = radius; var key: Long? = null
        for (si in strokes.indices) for (pi in strokes[si].points.indices) {
            val p = strokes[si].points[pi]
            val d = hypot(p.x - x, p.y - y)
            if (d <= best) { best = d; key = vecKey(si, pi) }
        }
        return key
    }

    /** Every point inside the closed polygon [poly] (even-odd). */
    fun pointsInPolygon(strokes: List<VectorStroke>, poly: List<Offset>): Set<Long> {
        val out = HashSet<Long>()
        if (poly.size < 3) return out
        for (si in strokes.indices) for (pi in strokes[si].points.indices) {
            val p = strokes[si].points[pi]
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[j]
                if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            if (inside) out.add(vecKey(si, pi))
        }
        return out
    }

    private fun weights(strokes: List<VectorStroke>, selected: Set<Long>, radius: Float): Array<FloatArray> {
        val sel = selected.mapNotNull { k -> strokes.getOrNull(vecKeyStroke(k))?.points?.getOrNull(vecKeyPoint(k)) }
        return Array(strokes.size) { si ->
            FloatArray(strokes[si].points.size) { pi ->
                if (selected.contains(vecKey(si, pi))) 1f
                else if (radius <= 0f) 0f
                else {
                    val p = strokes[si].points[pi]
                    var w = 0f
                    for (q in sel) { val f = falloff(hypot(p.x - q.x, p.y - q.y), radius); if (f > w) w = f }
                    w
                }
            }
        }
    }

    /** Moves the selected points by (dx, dy); neighbours within [radius] follow with the smoothstep weight. */
    fun move(strokes: List<VectorStroke>, selected: Set<Long>, dx: Float, dy: Float, radius: Float): List<VectorStroke> {
        if (selected.isEmpty()) return strokes
        val w = weights(strokes, selected, radius)
        return strokes.mapIndexed { si, s ->
            val ws = w[si]
            if (ws.all { it <= 0f }) s
            else s.copy(points = s.points.mapIndexed { pi, p -> val f = ws[pi]; if (f > 0f) p.copy(x = p.x + dx * f, y = p.y + dy * f) else p })
        }
    }

    /** Every point of every stroke that has at least one point inside [poly] (selection tool: whole strokes). */
    fun strokesTouching(strokes: List<VectorStroke>, poly: List<Offset>): Set<Long> {
        val hit = pointsInPolygon(strokes, poly)
        val out = HashSet<Long>()
        for (si in hit.map { vecKeyStroke(it) }.distinct()) for (pi in strokes[si].points.indices) out.add(vecKey(si, pi))
        return out
    }

    /** All points of the stroke nearest to (x, y) when it is within [radius] (plus half its width), else empty (magic wand on a vector layer). */
    fun insideFill(s: VectorStroke, x: Float, y: Float): Boolean = s.points.size >= 3 && insidePolygon(s.points, x, y)

    /** True when [a] (an existing fill) and [b] (a new one) cover about the same area: one contains the other's centre and their bounding boxes nearly match. */
    fun sameRegion(a: VectorStroke, b: VectorStroke): Boolean {
        if (a.points.size < 3 || b.points.size < 3) return false
        fun box(s: VectorStroke): FloatArray { var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
            for (p in s.points) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); bt = max(bt, p.y) }; return floatArrayOf(l, t, r, bt) }
        val x = box(a); val y = box(b)
        val tol = 6f
        return abs(x[0] - y[0]) < tol && abs(x[1] - y[1]) < tol && abs(x[2] - y[2]) < tol && abs(x[3] - y[3]) < tol
    }

    private fun insidePolygon(pts: List<VectorPoint>, x: Float, y: Float): Boolean {
        var inside = false; var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun strokeAt(strokes: List<VectorStroke>, x: Float, y: Float, radius: Float): Set<Long> {
        var best = Float.MAX_VALUE; var bi = -1
        for (si in strokes.indices) {
            val pts = strokes[si].points
            if (strokes[si].fill != 0 && pts.size >= 3 && insidePolygon(pts, x, y)) { if (0f < best) { best = 0f; bi = si }; continue } // inside a fill: the fill, unless a line is nearer
            for (i in pts.indices) {
                val a = pts[i]; val b = if (i + 1 < pts.size) pts[i + 1] else a
                val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
                val t = if (l2 < 1e-9f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
                val d = hypot(x - (a.x + dx * t), y - (a.y + dy * t)) - strokes[si].baseWidth * 0.5f
                if (d < best) { best = d; bi = si }
            }
        }
        if (bi < 0 || best > radius) return emptySet()
        return strokes[bi].points.indices.map { vecKey(bi, it) }.toSet()
    }

    fun allPoints(strokes: List<VectorStroke>): Set<Long> {
        val out = HashSet<Long>()
        for (si in strokes.indices) for (pi in strokes[si].points.indices) out.add(vecKey(si, pi))
        return out
    }

    /** Bounds (l, t, r, b) of the selected points, or null. */
    fun selectionBounds(strokes: List<VectorStroke>, selected: Set<Long>): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (k in selected) {
            val p = strokes.getOrNull(vecKeyStroke(k))?.points?.getOrNull(vecKeyPoint(k)) ?: continue
            any = true; l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        if (!any) return null
        if (r - l < 12f) { val c = (l + r) / 2f; l = c - 6f; r = c + 6f }
        if (b - t < 12f) { val c = (t + b) / 2f; t = c - 6f; b = c + 6f }
        return floatArrayOf(l, t, r, b)
    }

    /** Carries the selected points through [m]; a stroke whose points are ALL selected also scales its width by the matrix's area factor, unless [lockWeight] is on (then line weight never changes). */
    fun transform(strokes: List<VectorStroke>, selected: Set<Long>, m: android.graphics.Matrix, lockWeight: Boolean = false): List<VectorStroke> {
        if (selected.isEmpty()) return strokes
        val v = FloatArray(9); m.getValues(v)
        val area = if (lockWeight) 1f else kotlin.math.sqrt(abs(v[0] * v[4] - v[1] * v[3])).coerceIn(0.02f, 50f)
        val xy = FloatArray(2)
        return strokes.mapIndexed { si, s ->
            var count = 0
            for (pi in s.points.indices) if (selected.contains(vecKey(si, pi))) count++
            if (count == 0) return@mapIndexed s // untouched strokes keep their instance (the live editor skips them)
            val all = count == s.points.size
            val pts = s.points.mapIndexed { pi, p ->
                if (!selected.contains(vecKey(si, pi))) p
                else { xy[0] = p.x; xy[1] = p.y; m.mapPoints(xy); p.copy(x = xy[0], y = xy[1], elevationOffset = if (all) p.elevationOffset * area else p.elevationOffset) }
            }
            if (all) s.copy(points = pts, baseWidth = s.baseWidth * area) else s.copy(points = pts)
        }
    }

    /** Removes the selected points; a stroke left with fewer than two points disappears (a stroke split by a gap becomes two). */
    fun delete(strokes: List<VectorStroke>, selected: Set<Long>): List<VectorStroke> {
        val out = ArrayList<VectorStroke>()
        for ((si, s) in strokes.withIndex()) {
            val kept = ArrayList<VectorPoint>(); var first = true
            fun flush() { if (kept.size >= 2 || (kept.size == 1 && s.points.size == 1)) { out.add(s.copy(id = if (first) s.id else newStrokeId(), points = ArrayList(kept), isClosed = false)); first = false }; kept.clear() }
            if (s.points.indices.none { selected.contains(vecKey(si, it)) }) { out.add(s); continue }
            for ((pi, p) in s.points.withIndex()) if (selected.contains(vecKey(si, pi))) flush() else kept.add(p)
            flush()
        }
        return out
    }

    /** Raises (+) or lowers (-) the selected points: elevationOffset changes by [delta] document px (weighted for neighbours). */
    fun elevate(strokes: List<VectorStroke>, selected: Set<Long>, delta: Float, radius: Float): List<VectorStroke> {
        if (selected.isEmpty()) return strokes
        val w = weights(strokes, selected, radius)
        return strokes.mapIndexed { si, s ->
            val ws = w[si]
            if (ws.all { it <= 0f }) s
            else s.copy(points = s.points.mapIndexed { pi, p -> val f = ws[pi]; if (f > 0f) p.copy(elevationOffset = (p.elevationOffset + delta * f).coerceIn(-200f, 400f)) else p })
        }
    }

    /**
     * Slices every stroke that segment AB crosses at each crossing point. The first piece keeps the stroke id, every further piece gets a
     * new id so each can be moved or animated alone. Returns null when AB crosses nothing.
     */
    fun cut(strokes: List<VectorStroke>, ax: Float, ay: Float, bx: Float, by: Float): List<VectorStroke>? {
        var any = false
        val out = ArrayList<VectorStroke>()
        for (s in strokes) {
            val pieces = cutStroke(s, ax, ay, bx, by)
            if (pieces == null) out.add(s) else { any = true; out.addAll(pieces) }
        }
        return if (any) out else null
    }

    private fun cutStroke(s: VectorStroke, ax: Float, ay: Float, bx: Float, by: Float): List<VectorStroke>? {
        val src = if (s.isClosed && s.points.size >= 3) s.points + s.points.first() else s.points
        if (src.size < 2) return null
        val rx = bx - ax; val ry = by - ay
        val pieces = ArrayList<MutableList<VectorPoint>>()
        var cur = ArrayList<VectorPoint>()
        cur.add(src[0])
        var cuts = 0
        for (i in 0 until src.size - 1) {
            val p = src[i]; val q = src[i + 1]
            val sx = q.x - p.x; val sy = q.y - p.y
            val den = sx * ry - sy * rx // s x r
            if (abs(den) > 1e-9f) {
                val wx = ax - p.x; val wy = ay - p.y
                val t = (wx * ry - wy * rx) / den // position along the stroke segment
                val u = (wx * sy - wy * sx) / den // position along the cutting segment
                if (t >= 0f && t < 1f && u >= 0f && u <= 1f) {
                    val ip = VectorPoint(p.x + sx * t, p.y + sy * t, p.pressure + (q.pressure - p.pressure) * t, p.elevationOffset + (q.elevationOffset - p.elevationOffset) * t)
                    cur.add(ip); pieces.add(cur)
                    cur = ArrayList(); cur.add(ip)
                    cuts++
                }
            }
            cur.add(q)
        }
        if (cuts == 0) return null
        pieces.add(cur)
        var list: MutableList<MutableList<VectorPoint>> = pieces
        if (s.isClosed && list.size >= 2) { // the ring was opened at its first point: the last piece continues into the first
            val merged = ArrayList<VectorPoint>(list.last()); merged.addAll(list.first().drop(1))
            list = (listOf<MutableList<VectorPoint>>(merged) + list.subList(1, list.size - 1)).toMutableList()
        }
        val res = ArrayList<VectorStroke>()
        var first = true
        for (pts in list) {
            if (pts.size < 2) continue
            res.add(s.copy(id = if (first) s.id else newStrokeId(), points = pts, isClosed = false)); first = false
        }
        return if (res.isEmpty()) emptyList() else res
    }
}

// ---------- Interpolation and baking ----------

object VectorInterpolation {
    /** Eased time t = evaluateCurve(u). Uses the Camera / Transformation easing, or the custom Bezier when one is set. */
    fun curve(cfg: InterpolationConfig, u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        val bz = cfg.customBezier
        if (bz != null && bz.size >= 4) return bezierEase(bz[0], bz[1], bz[2], bz[3], x)
        return easeFn(cfg.curveType, x, cfg.ease)
    }

    private fun bezierEase(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        fun bx(t: Float) = 3f * (1 - t) * (1 - t) * t * x1 + 3f * (1 - t) * t * t * x2 + t * t * t
        fun by(t: Float) = 3f * (1 - t) * (1 - t) * t * y1 + 3f * (1 - t) * t * t * y2 + t * t * t
        var lo = 0f; var hi = 1f
        repeat(24) { val mid = (lo + hi) / 2f; if (bx(mid) < x) lo = mid else hi = mid }
        return by((lo + hi) / 2f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a ushr s) and 255) + (((b ushr s) and 255) - ((a ushr s) and 255)) * t).roundToInt().coerceIn(0, 255)) shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** [points] resampled by arc length to [count] evenly spaced points (closed strokes wrap around). */
    fun resample(points: List<VectorPoint>, count: Int, closed: Boolean): List<VectorPoint> {
        if (points.isEmpty()) return points
        val src = if (closed && points.size >= 2) points + points.first() else points
        val cum = FloatArray(src.size)
        for (i in 1 until src.size) cum[i] = cum[i - 1] + hypot(src[i].x - src[i - 1].x, src[i].y - src[i - 1].y)
        val total = cum.last()
        val m = max(count, 2)
        if (total < 1e-6f) return List(m) { src[0] }
        val out = ArrayList<VectorPoint>(m)
        var seg = 0
        for (j in 0 until m) {
            val target = if (closed) total * j / m else total * j / (m - 1)
            while (seg < src.size - 2 && cum[seg + 1] < target) seg++
            val l = cum[seg + 1] - cum[seg]
            val t = if (l < 1e-9f) 0f else ((target - cum[seg]) / l).coerceIn(0f, 1f)
            val a = src[seg]; val b = src[seg + 1]
            out.add(VectorPoint(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.pressure, b.pressure, t), lerp(a.elevationOffset, b.elevationOffset, t)))
        }
        return out
    }

    fun anchorRanks(s: VectorStroke): Set<Int> = s.points.filter { it.anchor > 0 }.map { it.anchor }.toSet()

    /** Anchors present in both strokes (same rank): index pairs, increasing in both strokes (conflicting order is dropped). */
    private fun anchorPairs(pa: List<VectorPoint>, pb: List<VectorPoint>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (r in 1..ANCHOR_MAX) {
            val i = pa.indexOfFirst { it.anchor == r }; val j = pb.indexOfFirst { it.anchor == r }
            if (i >= 0 && j >= 0) out.add(i to j)
        }
        out.sortBy { it.first }
        val kept = ArrayList<Pair<Int, Int>>()
        for (p in out) if (kept.isEmpty() || p.second > kept.last().second) kept.add(p)
        return kept
    }

    private fun signedArea(p: List<VectorPoint>): Float {
        var s = 0f
        for (i in p.indices) { val q = p[(i + 1) % p.size]; s += p[i].x * q.y - q.x * p[i].y }
        return s / 2f
    }

    private class Aligned(val pa: List<VectorPoint>, val pb: List<VectorPoint>, val pairs: List<Pair<Int, Int>>)

    /**
     * Lines the two strokes up on their shared anchors. B is tried in both directions (a stroke drawn clockwise and one drawn counter-clockwise
     * can carry the same anchors in opposite point order) and the direction that keeps more anchors wins; on a tie closed rings follow their winding.
     * Closed rings are then rotated to start at the first anchor.
     */
    private fun align(pa0: List<VectorPoint>, pb0: List<VectorPoint>, closed: Boolean): Aligned? {
        fun attempt(b: List<VectorPoint>): Aligned? {
            var x = pa0; var y = b
            var pairs = anchorPairs(x, y)
            if (pairs.isEmpty()) return null
            if (closed) {
                val sa = pairs[0].first; val sb = pairs[0].second
                x = x.drop(sa) + x.take(sa); y = y.drop(sb) + y.take(sb)
                pairs = anchorPairs(x, y)
                if (pairs.isEmpty()) return null
            }
            return Aligned(x, y, pairs)
        }
        val fwd = attempt(pb0); val rev = attempt(pb0.reversed())
        if (fwd == null) return rev
        if (rev == null) return fwd
        if (rev.pairs.size != fwd.pairs.size) return if (rev.pairs.size > fwd.pairs.size) rev else fwd
        return if (closed && signedArea(pa0) * signedArea(pb0) < 0f) rev else fwd
    }

    /** Piecewise blend: the segments between matching anchors are resampled against each other, so each anchor lands exactly on its partner. Null without common anchors. */
    private fun anchoredBlend(a: VectorStroke, b: VectorStroke, t: Float): VectorStroke? {
        val closed = a.isClosed && b.isClosed
        val al = align(a.points, b.points, closed) ?: return null
        var pa = al.pa; var pb = al.pb
        val pairs = al.pairs
        if (closed) { pa = pa + pa.first(); pb = pb + pb.first() }
        val ba = ArrayList<Int>(); val bb = ArrayList<Int>(); val rank = ArrayList<Int>()
        ba.add(0); bb.add(0); rank.add(if (pairs[0].first == 0 && pairs[0].second == 0) pa[0].anchor else 0)
        for ((i, j) in pairs) if (i > ba.last() || j > bb.last()) { ba.add(i); bb.add(j); rank.add(pa[i].anchor) }
        ba.add(pa.size - 1); bb.add(pb.size - 1); rank.add(0)
        val out = ArrayList<VectorPoint>()
        for (k in 0 until ba.size - 1) {
            val sa = pa.subList(ba[k], ba[k + 1] + 1); val sb = pb.subList(bb[k], bb[k + 1] + 1)
            val m = max(2, max(sa.size, sb.size))
            val ra = resample(sa, m, false); val rb = resample(sb, m, false)
            for (q in 0 until m) {
                if (k > 0 && q == 0) continue // the joint was already added by the previous segment
                val p = ra[q]; val r = rb[q]
                val ar = if (q == m - 1) rank[k + 1] else if (k == 0 && q == 0) rank[0] else 0
                out.add(VectorPoint(lerp(p.x, r.x, t), lerp(p.y, r.y, t), lerp(p.pressure, r.pressure, t), lerp(p.elevationOffset, r.elevationOffset, t), ar))
            }
        }
        // collapse coincident neighbours (an anchor at an end of the stroke leaves a zero-length segment), keeping the anchored point
        val clean = ArrayList<VectorPoint>()
        for (p in out) {
            val last = clean.lastOrNull()
            if (last != null && hypot(p.x - last.x, p.y - last.y) < 1e-4f) { if (p.anchor > 0 && last.anchor == 0) clean[clean.size - 1] = p } else clean.add(p)
        }
        if (closed && clean.size > 1) clean.removeAt(clean.size - 1)
        if (clean.size < 2) return null
        return VectorStroke(a.id, clean, lerpArgb(a.color, b.color, t), lerp(a.baseWidth, b.baseWidth, t), a.brushId, closed, lerp(a.opacity, b.opacity, t), a.aa).styleFrom(a)
    }

    private fun blend(a: VectorStroke, b: VectorStroke, t: Float): VectorStroke {
        anchoredBlend(a, b, t)?.let { return it }
        val closed = a.isClosed && b.isClosed
        val bPts = if (closed && a.points.size >= 3 && b.points.size >= 3 && signedArea(a.points) * signedArea(b.points) < 0f) b.points.reversed() else b.points // match winding
        val pa: List<VectorPoint>; val pb: List<VectorPoint>
        if (a.points.size == bPts.size && a.isClosed == b.isClosed) { pa = a.points; pb = bPts }
        else { val m = max(a.points.size, bPts.size); pa = resample(a.points, m, closed); pb = resample(bPts, m, closed) }
        val pts = List(min(pa.size, pb.size)) { i ->
            val p = pa[i]; val q = pb[i]
            VectorPoint(lerp(p.x, q.x, t), lerp(p.y, q.y, t), lerp(p.pressure, q.pressure, t), lerp(p.elevationOffset, q.elevationOffset, t))
        }
        return VectorStroke(a.id, pts, lerpArgb(a.color, b.color, t), lerp(a.baseWidth, b.baseWidth, t), a.brushId, closed, lerp(a.opacity, b.opacity, t), a.aa).styleFrom(a)
    }

    private fun faded(s: VectorStroke, keep: Float): VectorStroke =
        s.copy(baseWidth = s.baseWidth * keep, opacity = s.opacity * keep, points = s.points.map { it.copy(elevationOffset = it.elevationOffset * keep) })

    private class Box(val cx: Float, val cy: Float, val l: Float, val t: Float, val r: Float, val b: Float)
    private fun box(s: VectorStroke): Box {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var sx = 0f; var sy = 0f
        for (p in s.points) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y); sx += p.x; sy += p.y }
        val n = max(1, s.points.size)
        return Box(sx / n, sy / n, l, t, r, b)
    }

    /** Different lines: greedy bipartite matching on centroid distance, bounding-box overlap and drawing order. Returns (index in A, index in B) pairs. */
    fun pairDifferent(a: List<VectorStroke>, b: List<VectorStroke>): List<Pair<Int, Int>> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val ba = a.map { box(it) }; val bb = b.map { box(it) }
        var diag = 1f
        for (x in ba + bb) diag = max(diag, hypot(x.r - x.l, x.b - x.t))
        class Cand(val i: Int, val j: Int, val cost: Float)
        val cands = ArrayList<Cand>()
        for (i in a.indices) for (j in b.indices) {
            val p = ba[i]; val q = bb[j]
            val dist = hypot(p.cx - q.cx, p.cy - q.cy) / diag
            val iw = max(0f, min(p.r, q.r) - max(p.l, q.l)); val ih = max(0f, min(p.b, q.b) - max(p.t, q.t))
            val inter = iw * ih
            val union = (p.r - p.l) * (p.b - p.t) + (q.r - q.l) * (q.b - q.t) - inter
            val overlap = if (union > 1e-6f) inter / union else if (dist < 1e-3f) 1f else 0f
            val order = abs(i.toFloat() / a.size - j.toFloat() / b.size)
            val shared = anchorRanks(a[i]).count { it in anchorRanks(b[j]) } // the same anchor color in both strokes: strongly prefer this pair
            cands.add(Cand(i, j, dist + (1f - overlap) + 0.25f * order - 2f * shared))
        }
        cands.sortBy { it.cost }
        val usedA = BooleanArray(a.size); val usedB = BooleanArray(b.size)
        val out = ArrayList<Pair<Int, Int>>()
        for (c in cands) if (!usedA[c.i] && !usedB[c.j]) { usedA[c.i] = true; usedB[c.j] = true; out.add(c.i to c.j) }
        return out.sortedBy { it.first }
    }

    /** Same line: strokes with the same id. */
    fun pairSame(a: List<VectorStroke>, b: List<VectorStroke>): List<Pair<Int, Int>> {
        val used = BooleanArray(b.size)
        val out = ArrayList<Pair<Int, Int>>()
        for (i in a.indices) {
            val j = b.indices.firstOrNull { !used[it] && b[it].id == a[i].id } ?: continue
            used[j] = true; out.add(i to j)
        }
        return out
    }

    /**
     * Automatic pairing, no mode to choose: (1) strokes that carry the same anchor colour are partners (the most shared colours first),
     * (2) strokes with the same id, (3) what is left is matched by position, overlap and drawing order. A stroke that still has no partner
     * shrinks and fades.
     */
    fun pairAuto(a: List<VectorStroke>, b: List<VectorStroke>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val usedA = BooleanArray(a.size); val usedB = BooleanArray(b.size)
        class Cand(val i: Int, val j: Int, val shared: Int, val dist: Float)
        val cands = ArrayList<Cand>()
        for (i in a.indices) { val ra = anchorRanks(a[i]); if (ra.isEmpty()) continue
            for (j in b.indices) {
                val sh = anchorRanks(b[j]).count { it in ra }
                if (sh > 0) { val ca = box(a[i]); val cb = box(b[j]); cands.add(Cand(i, j, sh, hypot(ca.cx - cb.cx, ca.cy - cb.cy))) }
            }
        }
        cands.sortWith(compareByDescending<Cand> { it.shared }.thenBy { it.dist })
        for (c in cands) if (!usedA[c.i] && !usedB[c.j]) { usedA[c.i] = true; usedB[c.j] = true; out.add(c.i to c.j) }
        for (i in a.indices) {
            if (usedA[i]) continue
            val j = b.indices.firstOrNull { !usedB[it] && b[it].id == a[i].id } ?: continue
            usedA[i] = true; usedB[j] = true; out.add(i to j)
        }
        val ra = a.indices.filter { !usedA[it] }; val rb = b.indices.filter { !usedB[it] }
        for ((x, y) in pairDifferent(ra.map { a[it] }, rb.map { b[it] })) out.add(ra[x] to rb[y])
        return out.sortedBy { it.first }
    }

    /**
     * Strokes at eased time [t] (0 = [a], 1 = [b]); [pairAuto] decides which strokes are the same line and which are different lines. Paired
     * strokes keep the id of [a] and blend their points, colour, line weight and opacity; strokes without a partner shrink and fade out
     * (from [a]) or in (from [b]).
     */
    private fun visibleStroke(s: VectorStroke): Boolean =
        s.points.isNotEmpty() && (s.fill != 0 || s.baseWidth > 0.02f || s.points.any { p -> p.elevationOffset > 0f }) && s.opacity > 0.004f

    /**
     * Partners in [a] (the first reference frame) for the chosen lines of [b] (the last frame; [chosen] = indices into [b]). Only the chosen lines look for a partner:
     * (1) the line of [a] that shares the most anchor numbers with it (nearest centroid breaks ties), else (2) the line of [a] with the same id. No geometric guessing:
     * a chosen line without a partner is simply not paired, and lines of [a] that are nobody's partner are not used at all (they never fade in or out).
     * Returns (index in [a], index in [b]) pairs.
     */
    fun pairSelected(a: List<VectorStroke>, b: List<VectorStroke>, chosen: Set<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val usedA = BooleanArray(a.size); val usedB = HashSet<Int>()
        class Cand(val i: Int, val j: Int, val shared: Int, val dist: Float)
        val cands = ArrayList<Cand>()
        val js = chosen.filter { it in b.indices }.sorted()
        for (j in js) {
            val rb = anchorRanks(b[j]); if (rb.isEmpty()) continue
            val cb = box(b[j])
            for (i in a.indices) {
                val sh = anchorRanks(a[i]).count { it in rb }
                if (sh > 0) { val ca = box(a[i]); cands.add(Cand(i, j, sh, hypot(ca.cx - cb.cx, ca.cy - cb.cy))) }
            }
        }
        cands.sortWith(compareByDescending<Cand> { it.shared }.thenBy { it.dist })
        for (c in cands) if (!usedA[c.i] && c.j !in usedB) { usedA[c.i] = true; usedB.add(c.j); out.add(c.i to c.j) }
        for (j in js) {
            if (j in usedB) continue
            val i = a.indices.firstOrNull { !usedA[it] && a[it].id == b[j].id } ?: continue
            usedA[i] = true; usedB.add(j); out.add(i to j)
        }
        return out.sortedBy { it.second }
    }

    /**
     * Interpolation limited to chosen lines of [b] ([chosen] = indices into [b]; null = no selection, everything is interpolated as usual by [interpolate]). Each chosen line is blended
     * with its [pairSelected] partner in [a]; nothing else of [a] takes part and nothing fades. With [keepRest] the result is [b] itself with those lines replaced in place (stacking order
     * kept; unselected lines and chosen lines without a partner stay as they are); without it only the blended lines are returned (live preview: the rest is already on screen).
     */
    fun interpolateChosen(a: List<VectorStroke>, b: List<VectorStroke>, chosen: Set<Int>?, mode: InterpolationMode, t: Float, keepRest: Boolean): List<VectorStroke> {
        if (chosen == null) return interpolate(a, b, mode, t)
        val pairs = pairSelected(a, b, chosen)
        val moved = HashMap<Int, VectorStroke>()
        for ((i, j) in pairs) blend(a[i], b[j], t).takeIf { visibleStroke(it) }?.let { moved[j] = it }
        val paired = pairs.map { it.second }.toSet()
        return if (keepRest) b.mapIndexedNotNull { j, st -> if (j in paired) moved[j] else st } else paired.sorted().mapNotNull { moved[it] }
    }

    /** Interpolation drag with a selection (two frames): [sel] = indices into [b], the keyframe being dragged toward. An empty [sel] interpolates everything. */
    fun interpolateSelected(a: List<VectorStroke>, b: List<VectorStroke>, sel: Set<Int>, mode: InterpolationMode, t: Float, keepRest: Boolean): List<VectorStroke> =
        interpolateChosen(a, b, sel.filter { it in b.indices }.toSet().takeIf { it.isNotEmpty() }, mode, t, keepRest)

    /**
     * The new content of the middle frame [m] (three frames): the chosen lines of [b] (null = all lines) are replaced by the interpolation with their partners in [a]; the other lines of [m]
     * stay. A line of [m] is replaced when it has the id of the partner in [a] or of the chosen line in [b].
     */
    fun interpolateIntoMiddle(a: List<VectorStroke>, m: List<VectorStroke>, b: List<VectorStroke>, chosen: Set<Int>?, mode: InterpolationMode, t: Float): List<VectorStroke> {
        if (chosen == null) return interpolate(a, b, mode, t)
        val pairs = pairSelected(a, b, chosen)
        val ids = HashSet<String>()
        val moved = ArrayList<VectorStroke>()
        for ((i, j) in pairs) { ids.add(a[i].id); ids.add(b[j].id); blend(a[i], b[j], t).takeIf { visibleStroke(it) }?.let { moved.add(it) } }
        val rest = m.filter { it.id !in ids }
        return rest.filter { it.fill != 0 } + moved + rest.filter { it.fill == 0 }
    }

    fun interpolate(a: List<VectorStroke>, b: List<VectorStroke>, mode: InterpolationMode, t: Float): List<VectorStroke> {
        if (mode == InterpolationMode.NONE) return a
        val pairs = pairAuto(a, b)
        val usedA = BooleanArray(a.size); val usedB = BooleanArray(b.size)
        val out = ArrayList<VectorStroke>()
        val paired = HashMap<Int, VectorStroke>()
        for ((i, j) in pairs) { usedA[i] = true; usedB[j] = true; paired[i] = blend(a[i], b[j], t) }
        for (i in a.indices) {
            if (usedA[i]) out.add(paired.getValue(i)) else out.add(faded(a[i], (1f - t).coerceIn(0f, 1f)))
        }
        for (j in b.indices) if (!usedB[j]) out.add(faded(b[j], t.coerceIn(0f, 1f)))
        return out.filter { it.points.isNotEmpty() && (it.fill != 0 || it.baseWidth > 0.02f || it.points.any { p -> p.elevationOffset > 0f }) && it.opacity > 0.004f }
    }
}

/** The key's own interpolation settings, or the default (same line, linear, on 1s). */
fun Cel.vectorConfig(): InterpolationConfig = vecCfg ?: InterpolationConfig()

/**
 * Replaces the strokes of the vector cel at [index] with [strokes] as ONE undoable step. A cel is swapped for a new object (the undo
 * snapshots keep the old one); clones that share the drawing are swapped together so they stay the same drawing.
 */
fun replaceVectorCel(cut: Cut, layer: Layer, index: Int, strokes: List<VectorStroke>) {
    val old = layer.cels.getOrNull(index) ?: return
    cut.record { swapVectorCel(layer, old, strokes) }
}

/** Not recorded: the caller wraps it in cut.record / a snapshot of its own. */
fun swapVectorCel(layer: Layer, old: Cel, strokes: List<VectorStroke>) {
    var shared: Cel? = null
    for (i in layer.cels.indices) {
        val c = layer.cels[i]
        if (!c.sameDrawing(old)) continue
        // The fresh cel's tiles are built here, before it reaches the timeline: the canvas draws a cel only when its tile set exists, so without this the whole layer blinked off until a background rebuild finished.
        val n = shared?.let { s -> s.cloneLinked().also { it.dur = c.dur } } ?: Cel(c.dur).also { fresh -> strokes.forEach { fresh.strokes.add(it.toInk()) }; TileCache.get(fresh.strokes); shared = fresh }
        n.persistent = c.persistent; n.inbetweenName = c.inbetweenName; n.vecCfg = c.vecCfg
        layer.cels[i] = n
    }
}

/**
 * Bakes the interpolation of each selected key to the next key into real keyframes "on N". For every pair (key, next cel) with the key's
 * mode not Hold and a hold longer than the step: the key is shortened to the step, then one new keyframe per step is inserted, ending
 * where the next key starts. [selected] is the set of keys; with one key it bakes toward its next neighbour, with several only between
 * neighbours that are both selected. [stepOverride] replaces each key's own step. Returns the number of new keyframes.
 */
fun bakeVectorKeys(cut: Cut, layer: Layer, selected: List<Cel>, stepOverride: Int? = null): Int {
    val idx = selected.mapNotNull { c -> layer.cels.indexOfFirst { it === c }.takeIf { it >= 0 } }.distinct().sorted()
    if (idx.isEmpty()) return 0
    class Job(val i: Int, val cfg: InterpolationConfig, val step: Int)
    val jobs = ArrayList<Job>()
    for (i in idx) {
        if (i + 1 >= layer.cels.size || (idx.size > 1 && (i + 1) !in idx)) continue
        val cfg = layer.cels[i].vectorConfig()
        if (cfg.mode == InterpolationMode.NONE) continue
        val step = (stepOverride ?: cfg.bakeStep).coerceAtLeast(1)
        if (layer.startOf(i + 1) - layer.startOf(i) <= step) continue
        jobs.add(Job(i, cfg, step))
    }
    if (jobs.isEmpty()) return 0
    var created = 0
    cut.record {
        for (job in jobs.sortedByDescending { it.i }) { // from the end, so earlier indices stay valid
            val i = job.i; val cfg = job.cfg; val step = job.step
            val a = layer.cels[i]; val b = layer.cels[i + 1]
            val f1 = layer.startOf(i); val f2 = layer.startOf(i + 1)
            val sa = a.vectorStrokes(); val sb = b.vectorStrokes()
            val fresh = ArrayList<Cel>()
            var f = f1 + step
            while (f < f2) {
                val len = min(step, f2 - f)
                val t = VectorInterpolation.curve(cfg, (f - f1).toFloat() / (f2 - f1))
                val cel = Cel(len)
                VectorInterpolation.interpolate(sa, sb, cfg.mode, t).forEach { cel.strokes.add(it.toInk()) }
                fresh.add(cel)
                f += step
            }
            a.dur = step
            a.vecCfg = cfg.copy(mode = InterpolationMode.NONE) // baked keys are plain holds (no live interpolation on top)
            layer.cels.addAll(i + 1, fresh)
            created += fresh.size
        }
    }
    return created
}


/** Live (not baked) interpolation: transient cels for the in-between frames of a key's hold, cached so the tile / GL pipelines see a stable stroke list. */
object VectorLive {
    /** [prev]: the transient cel this one replaced, kept (with its tiles) so the canvas has a finished picture to show while the new one is rasterized. */
    private class Entry(val sig: List<Any?>, val cel: Cel, val prev: Cel? = null)
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= 96) return false
            TileCache.invalidate(eldest.value.cel.strokes)
            eldest.value.prev?.let { TileCache.invalidate(it.strokes) }
            return true
        }
    }

    /** Read by draw code that had to fall back to the held key; bumped when a transient cel has been built, so that draw runs again. */
    private var ready by mutableIntStateOf(0)
    private val pending = HashSet<String>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The cel to render at [t]. A transient cel owns a SnapshotStateList, which may NOT be filled inside a read-only snapshot (composition
     * layout / draw): doing so crashed with "Cannot modify a state object in a read-only snapshot". From such a snapshot a missing cel is
     * therefore built on the main handler (global snapshot) and the held key is returned until [ready] triggers the next draw.
     */
    @Synchronized
    fun celAt(layer: Layer, t: Int): Cel? {
        val i = layer.indexAt(t)
        val a = layer.cels.getOrNull(i) ?: return null
        val cfg = a.vecCfg ?: return a // interpolation is opt-in: a key without settings simply holds
        if (cfg.mode == InterpolationMode.NONE) return a
        val b = layer.cels.getOrNull(i + 1) ?: return a
        val f1 = layer.startOf(i); val f2 = layer.startOf(i + 1)
        if (t <= f1 || f2 - f1 < 2) return a
        val key = "${System.identityHashCode(a)}:${System.identityHashCode(b)}:$t"
        val sig = listOf(cfg, a.vecRev, b.vecRev, a.strokes.size, b.strokes.size, f2 - f1)
        val readOnly = Snapshot.current.readOnly
        if (readOnly) { ready; OnionWarmup.ready.intValue } // subscribe: redraw once the transient cel exists and once its tiles are built
        val hit = cache[key]
        if (hit != null && hit.sig == sig) {
            if (!readOnly) return hit.cel
            // Draw / layout: a cel whose tile set does not exist yet would draw nothing (the layer blinked off). Ask for the tiles in the
            // background and keep showing the previous picture of this frame (or the held key) until they are there.
            if (TileCache.peekReady(hit.cel.strokes) != null) return hit.cel
            OnionWarmup.request(hit.cel.strokes, urgent = true)
            return hit.prev?.takeIf { TileCache.peekReady(it.strokes) != null } ?: a
        }
        if (!readOnly) return build(key, sig, a, b, cfg, t, f1, f2)
        if (pending.add(key)) main.post {
            synchronized(this) {
                try { build(key, sig, a, b, cfg, t, f1, f2) } catch (_: Throwable) { } finally { pending.remove(key) }
            }
            ready += 1
        }
        // not rebuilt yet: the stale interpolated picture (when its tiles exist) is closer to the right frame than the held key, which made the canvas jump to the key pose after every stroke
        return hit?.cel?.takeIf { TileCache.peekReady(it.strokes) != null } ?: a
    }

    private fun build(key: String, sig: List<Any?>, a: Cel, b: Cel, cfg: InterpolationConfig, t: Int, f1: Int, f2: Int): Cel {
        val old = cache[key]
        if (old != null && old.sig == sig) return old.cel
        old?.prev?.let { TileCache.invalidate(it.strokes) } // one generation back is enough
        val u = (t - f1).toFloat() / (f2 - f1)
        val cel = Cel(1)
        VectorInterpolation.interpolate(a.vectorStrokes(), b.vectorStrokes(), cfg.mode, VectorInterpolation.curve(cfg, u)).forEach { cel.strokes.add(it.toInk()) }
        cache[key] = Entry(sig, cel, old?.cel)
        return cel
    }
}


/**
 * Interpolation drag: writes the in-between of the previous keyframe and the one at [time] as a new one-frame keyframe just before it, at position
 * [t] (0 = the previous, 1 = the current). The previous key's lines setting pairs the strokes (Hold counts as Same line). The new frame takes its time from
 * the previous keyframe when that lasts more than one frame (the current keyframe and everything after it stay put). Otherwise [keepLength] (the timeline's
 * retime toggle) takes it from the current keyframe when that lasts more than one frame; failing that the current keyframe and everything after it are pushed
 * one frame later. False when there is no previous keyframe.
 */
fun createDraggedInbetween(cut: Cut, layer: Layer, time: Int, t: Float, keepLength: Boolean, sel: Set<Int> = emptySet()): Boolean {
    val i = layer.indexAt(time)
    if (i < 1) return false
    val p = layer.cels[i - 1]; val c = layer.cels[i]
    val mode = (p.vecCfg ?: InterpolationConfig()).mode.let { if (it == InterpolationMode.NONE) InterpolationMode.SAME_STROKE else it }
    val strokes = VectorInterpolation.interpolateSelected(p.vectorStrokes(), c.vectorStrokes(), sel, mode, t.coerceIn(0f, 1f), true)
    cut.record {
        val cel = Cel(1)
        strokes.forEach { cel.strokes.add(it.toInk()) }
        // Encroach on the previous keyframe when it lasts more than one frame: the new frame takes its last frame, so the current keyframe stays where it is.
        // Only when the previous one is a single frame does the old rule apply (take from the current one with the retime toggle on, else push everything later).
        if (p.dur > 1) p.dur -= 1
        else if (keepLength && c.dur > 1) c.dur -= 1
        layer.cels.add(i, cel)
    }
    return true
}


/** The frames of [layer] that are selected in the timeline (ed.selCels), in timeline order. Exactly three switch the interpolation drag to "three frames" mode. */
fun vecSelectedFrames(layer: Layer, ed: Editor): List<Cel> = layer.cels.filter { c -> ed.selCels.any { it === c } }

/** Ids of the lines that have a selected point, over the whole selection scope (they identify the same lines in other keyframes). */
fun vecSelectedLineIds(cut: Cut, ed: Editor): Set<String> {
    if (ed.vecSel.isEmpty()) return emptySet()
    val sc = vecScope(cut, ed.refAll) ?: return emptySet()
    if (!sc.sameCels(ed.vecSelCels)) return emptySet()
    val out = HashSet<String>()
    for (c in sc.cels.indices) for (k in sc.localKeys(ed.vecSel, c)) sc.perCel[c].getOrNull(vecKeyStroke(k))?.let { out.add(it.id) }
    return out
}

/**
 * Interpolation drag in "three frames" mode: [a] and [b] (the two outer selected frames) are the references, and the interpolation at [t] is written INTO the
 * middle frame [m] (no frame is created). Only the selected lines of the last frame [b] ([chosen], see [vecSelectedLinesIn]) with their anchor / id partners in [a]; every line when null. [m] is replaced by a fresh frame of its own, so a clone of another
 * frame is not changed along with it. False when [m] is not on the layer.
 */
fun interpolateIntoFrame(cut: Cut, ed: Editor, layer: Layer, a: Cel, m: Cel, b: Cel, t: Float, chosen: Set<Int>?): Boolean {
    val mi = layer.cels.indexOfFirst { it === m }
    if (mi < 0) return false
    val mode = (a.vecCfg ?: InterpolationConfig()).mode.let { if (it == InterpolationMode.NONE) InterpolationMode.SAME_STROKE else it }
    val strokes = VectorInterpolation.interpolateIntoMiddle(a.vectorStrokes(), m.vectorStrokes(), b.vectorStrokes(), chosen, mode, t.coerceIn(0f, 1f))
    var fresh: Cel? = null
    cut.record {
        val f = Cel(m.dur)
        strokes.forEach { f.strokes.add(it.toInk()) }
        TileCache.get(f.strokes)
        f.persistent = m.persistent; f.inbetweenName = m.inbetweenName; f.vecCfg = m.vecCfg
        layer.cels[mi] = f
        fresh = f
    }
    val f = fresh ?: return false
    val k = ed.selCels.indexOfFirst { it === m }
    if (k >= 0) ed.selCels[k] = f
    if (ed.vecSelCels.any { it === m }) { ed.vecSel = emptySet(); ed.vecSelCels = ed.vecSelCels.map { if (it === m) f else it } } // the middle frame has new lines: its old selection no longer fits
    return true
}

/**
 * Three-frame mode: the lines of [b] (the last selected frame) that the point selection stands for, wherever it was made (the selection keeps its own frames in
 * [Editor.vecSelCels], so moving the playhead does not lose it). On [b] itself by index, on another frame by line id. Null = nothing is selected (all lines);
 * an empty set = something is selected but no line of [b] matches (then nothing is interpolated).
 */
fun vecSelectedLinesIn(ed: Editor, b: Cel): Set<Int>? {
    if (ed.vecSel.isEmpty() || ed.vecSelCels.isEmpty()) return null
    val sc = VecScope(emptyList(), ed.vecSelCels)
    val bs = b.vectorStrokes()
    val out = HashSet<Int>()
    for (c in sc.cels.indices) for (k in sc.localKeys(ed.vecSel, c)) {
        val si = vecKeyStroke(k)
        val st = sc.perCel[c].getOrNull(si) ?: continue
        if (sc.cels[c] === b) out.add(si) else bs.indexOfFirst { it.id == st.id }.takeIf { it >= 0 }?.let { out.add(it) }
    }
    return out
}

/** Indices (into [cel]'s strokes) of the lines that have a selected point, for the current vector selection; empty when nothing is selected on that keyframe. */
fun vecSelectedLineIndices(cut: Cut, ed: Editor, cel: Cel?): Set<Int> {
    if (cel == null || ed.vecSel.isEmpty()) return emptySet()
    val sc = vecScope(cut, ed.refAll) ?: return emptySet()
    if (!sc.sameCels(ed.vecSelCels)) return emptySet()
    val c = sc.cels.indexOfFirst { it === cel }
    if (c < 0) return emptySet()
    return sc.localKeys(ed.vecSel, c).map { vecKeyStroke(it) }.toSet()
}

/**
 * The strokes a vector selection works on at the playhead: the active vector layer, or every visible, unlocked vector layer when "All visible
 * drawing layers" ([Editor.refAll]) is on. Their strokes are concatenated, so the point keys (stroke index, point index) span the whole scope.
 */
class VecScope(val layers: List<Layer>, val cels: List<Cel>) {
    val perCel: List<List<VectorStroke>> = cels.map { it.vectorStrokes() }
    val offsets = IntArray(cels.size + 1).also { o -> for (i in cels.indices) o[i + 1] = o[i] + perCel[i].size }
    val strokes: List<VectorStroke> = perCel.flatten()

    /** Splits an edit that kept the number of strokes (move, raise, transform, anchor) back into one list per cel. */
    fun split(edited: List<VectorStroke>): List<List<VectorStroke>> = cels.indices.map { edited.subList(offsets[it], offsets[it + 1]) }

    /** The part of the scope-wide key set [sel] that falls in cel [c], with stroke indices local to that cel. */
    fun localKeys(sel: Set<Long>, c: Int): Set<Long> {
        val out = HashSet<Long>()
        for (k in sel) { val si = vecKeyStroke(k); if (si >= offsets[c] && si < offsets[c + 1]) out.add(vecKey(si - offsets[c], vecKeyPoint(k))) }
        return out
    }

    fun sameCels(other: List<Cel>): Boolean = other.size == cels.size && cels.indices.all { cels[it] === other[it] }
}

fun vecScope(cut: Cut, refAll: Boolean): VecScope? {
    val active = cut.drawLayer?.takeIf { it.vector && it.visible && !it.locked } ?: return null
    val ls = if (refAll) cut.layers.filter { it.vector && it.visible && !it.locked } else listOf(active)
    val pairs = ls.mapNotNull { l -> l.celAt(cut.time)?.let { l to it } }
    if (pairs.isEmpty()) return null
    return VecScope(pairs.map { it.first }, pairs.map { it.second })
}

/** Writes new strokes for the cels of [sc] as ONE undoable step. [parts] has one list per cel; a list that is the very same object as before is left alone. */
fun applyScope(cut: Cut, sc: VecScope, parts: List<List<VectorStroke>>) {
    if (sc.cels.indices.none { parts[it] !== sc.perCel[it] }) return
    cut.record { for (i in sc.cels.indices) if (parts[i] !== sc.perCel[i]) swapVectorCel(sc.layers[i], sc.cels[i], parts[i]) }
}

/**
 * Where separate lines touch or cross without being one joined line (the selection tool shimmers these places): crossings of the centre lines of two different
 * lines, and an end of an open line that lies within [reach] of another line. Returns x, y pairs (document pixels), nearby ones merged, at most 160.
 */
object VectorGaps {
    fun find(strokes: List<VectorStroke>, reach: Float): FloatArray {
        val ls = strokes.filter { it.fill == 0 && it.points.size >= 2 }
        if (ls.size < 2 || ls.sumOf { it.points.size } > 5000) return FloatArray(0)
        val bb = ls.map { s -> var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in s.points) { l = min(l, p.x); r = max(r, p.x); t = min(t, p.y); b = max(b, p.y) }
            val pad = reach + s.baseWidth; floatArrayOf(l - pad, t - pad, r + pad, b + pad) }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun add(x: Float, y: Float) {
            if (xs.size >= 160) return
            val r2 = (reach * 0.8f).coerceAtLeast(2f).let { it * it }
            for (i in xs.indices) { val dx = xs[i] - x; val dy = ys[i] - y; if (dx * dx + dy * dy < r2) return }
            xs.add(x); ys.add(y)
        }
        fun segs(s: VectorStroke) = if (s.isClosed && s.points.size > 2) s.points.size else s.points.size - 1
        for (a in ls.indices) for (b in a + 1 until ls.size) {
            val u = bb[a]; val v = bb[b]
            if (u[2] < v[0] || v[2] < u[0] || u[3] < v[1] || v[3] < u[1]) continue
            val sa = ls[a]; val sb = ls[b]
            // crossings of the two centre lines
            for (i in 0 until segs(sa)) {
                val p0 = sa.points[i]; val p1 = sa.points[(i + 1) % sa.points.size]
                for (j in 0 until segs(sb)) {
                    val q0 = sb.points[j]; val q1 = sb.points[(j + 1) % sb.points.size]
                    val rx = p1.x - p0.x; val ry = p1.y - p0.y; val sx = q1.x - q0.x; val sy = q1.y - q0.y
                    val den = rx * sy - ry * sx
                    if (abs(den) < 1e-9f) continue
                    val tt = ((q0.x - p0.x) * sy - (q0.y - p0.y) * sx) / den
                    val uu = ((q0.x - p0.x) * ry - (q0.y - p0.y) * rx) / den
                    if (tt in 0f..1f && uu in 0f..1f) {
                        val x = p0.x + rx * tt; val y = p0.y + ry * tt
                        // a crossing where both lines have a vertex (Merge puts one there) is merged: no shimmer
                        if (!(sa.points.any { hypot(it.x - x, it.y - y) < 0.6f } && sb.points.any { hypot(it.x - x, it.y - y) < 0.6f })) add(x, y)
                    }
                }
            }
            // an end of one line near the other line
            fun ends(s: VectorStroke, o: VectorStroke) {
                if (s.isClosed) return
                for (e in listOf(s.points.first(), s.points.last())) {
                    var best = Float.MAX_VALUE
                    for (j in 0 until segs(o)) {
                        val q0 = o.points[j]; val q1 = o.points[(j + 1) % o.points.size]
                        val dx = q1.x - q0.x; val dy = q1.y - q0.y; val l2 = dx * dx + dy * dy
                        val k = if (l2 < 1e-9f) 0f else (((e.x - q0.x) * dx + (e.y - q0.y) * dy) / l2).coerceIn(0f, 1f)
                        best = min(best, hypot(e.x - (q0.x + dx * k), e.y - (q0.y + dy * k)))
                    }
                    if (best <= reach) {
                        // an end that sits on a vertex in the BODY of the other line (what Merge does) is merged; an end that meets an end of another line is still two lines
                        val onEnd = listOf(o.points.first(), o.points.last()).any { hypot(it.x - e.x, it.y - e.y) <= reach }
                        val onVertex = o.points.indices.any { k -> k != 0 && k != o.points.size - 1 && hypot(o.points[k].x - e.x, o.points[k].y - e.y) < 0.6f }
                        if (onEnd || !onVertex) add(e.x, e.y)
                    }
                }
            }
            ends(sa, sb); ends(sb, sa)
        }
        val out = FloatArray(xs.size * 2)
        for (i in xs.indices) { out[i * 2] = xs[i]; out[i * 2 + 1] = ys[i] }
        return out
    }
}

/** Smoothing by adding points where the line bends (centripetal Catmull-Rom through the existing points). Straight stretches get none. */
object VectorSmooth {
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Result of [refineMapped]: the new stroke, the new index of every old point, and the new indices of the points that were added. */
    class Refined(val stroke: VectorStroke, val newIndex: IntArray, val added: List<Int>)

    /** [selected]: point indices of [s]; when given, points are only added on segments whose two end points are both selected (null = the whole line). */
    fun refine(s: VectorStroke, strength: Float, selected: Set<Int>? = null): VectorStroke = refineMapped(s, strength, selected).stroke

    fun refineMapped(s: VectorStroke, strength: Float, selected: Set<Int>? = null): Refined {
        val p = s.points; val n = p.size
        if (n < 3 || strength <= 0f) return Refined(s, IntArray(n) { it }, emptyList())
        val closed = s.isClosed && n >= 3
        fun at(i: Int): VectorPoint = when {
            closed -> p[((i % n) + n) % n]
            i < 0 -> p[0].copy(x = 2 * p[0].x - p[1].x, y = 2 * p[0].y - p[1].y)
            i >= n -> p[n - 1].copy(x = 2 * p[n - 1].x - p[n - 2].x, y = 2 * p[n - 1].y - p[n - 2].y)
            else -> p[i]
        }
        fun turn(i: Int): Float {
            if (!closed && (i <= 0 || i >= n - 1)) return 0f
            val a = at(i - 1); val b = at(i); val c = at(i + 1)
            val ux = b.x - a.x; val uy = b.y - a.y; val vx = c.x - b.x; val vy = c.y - b.y
            return abs(atan2(ux * vy - uy * vx, ux * vx + uy * vy))
        }
        val segs = if (closed) n else n - 1
        val out = ArrayList<VectorPoint>(n * 2)
        val newIndex = IntArray(n)
        val added = ArrayList<Int>()
        for (i in 0 until segs) {
            newIndex[i] = out.size
            out.add(p[i])
            if (selected != null && (i !in selected || ((i + 1) % n) !in selected)) continue // only between selected points
            val p1 = at(i); val p2 = at(i + 1)
            val len = hypot(p2.x - p1.x, p2.y - p1.y)
            val bend = (max(turn(i), turn(i + 1)) - 0.03f).coerceAtLeast(0f) // under ~2 degrees counts as straight
            var ins = (strength * 8f * min(bend / 0.5f, 1.5f)).roundToInt()
            ins = min(ins, (len / 1.5f).toInt())
            if (ins <= 0) continue
            val p0 = at(i - 1); val p3 = at(i + 2)
            fun d(a: VectorPoint, b: VectorPoint) = kotlin.math.sqrt(max(hypot(b.x - a.x, b.y - a.y), 1e-3f))
            val t0 = 0f; val t1 = t0 + d(p0, p1); val t2 = t1 + d(p1, p2); val t3 = t2 + d(p2, p3)
            for (j in 1..ins) {
                val u = j.toFloat() / (ins + 1)
                val t = lerp(t1, t2, u)
                fun mix(a: Float, b: Float, ta: Float, tb: Float, tt: Float) = a * (tb - tt) / (tb - ta) + b * (tt - ta) / (tb - ta)
                fun cr(f: (VectorPoint) -> Float): Float {
                    val a1 = mix(f(p0), f(p1), t0, t1, t); val a2 = mix(f(p1), f(p2), t1, t2, t); val a3 = mix(f(p2), f(p3), t2, t3, t)
                    val b1 = mix(a1, a2, t0, t2, t); val b2 = mix(a2, a3, t1, t3, t)
                    return mix(b1, b2, t1, t2, t)
                }
                added.add(out.size)
                out.add(VectorPoint(cr { it.x }, cr { it.y }, lerp(p1.pressure, p2.pressure, u), lerp(p1.elevationOffset, p2.elevationOffset, u)))
            }
        }
        if (!closed) { newIndex[n - 1] = out.size; out.add(p[n - 1]) }
        return Refined(s.copy(points = out), newIndex, added)
    }
}


/** Per-stroke state of a drawn vector line: the selected brush's stabilizer, smoothed pressure and start taper (mirrors what [Session] does for pixel brushes). */
class VecPen(val brush: Brush) {
    val stab = Stabilizer(brush.stabMethod, brush.stab)
    var ps = 1f; var dist = 0f; var started = false; var lx = 0f; var ly = 0f
    var rx = 0f; var ry = 0f; var rp = 1f; var raw = 0

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Width factor (0..1) for a filtered point: pressure -> size curve, with the start taper. */
    fun width(x: Float, y: Float, p: Float): Float {
        ps += (p - ps) * 0.5f
        if (started) dist += hypot(x - lx, y - ly)
        lx = x; ly = y; started = true
        val ts = if (brush.taperStart > 0f) 0.1f + 0.9f * smooth(dist / (brush.size * brush.taperStart * 3f + 1f)) else 1f
        return (curveEval(brush.sizeCurve, ps) * ts).coerceIn(0.03f, 1f)
    }

    /** End taper on the dense points of a finished stroke (before simplification). */
    fun taperEnd(pts: List<VectorPoint>): List<VectorPoint> {
        val len = brush.size * brush.taperEnd * 4f
        if (len <= 0.5f || pts.size < 3) return pts
        val out = ArrayList(pts)
        var d = 0f
        for (i in pts.size - 2 downTo 0) {
            d += hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
            if (d >= len) break
            val k = 0.15f + 0.85f * smooth(d / len)
            out[i] = pts[i].copy(pressure = (pts[i].pressure * k).coerceAtLeast(0.03f))
        }
        out[pts.size - 1] = pts.last().copy(pressure = (pts.last().pressure * 0.15f).coerceAtLeast(0.03f))
        return out
    }
}

/**
 * Merge: a freshly drawn line whose end touches an existing line becomes attached to it. End to end: the two (or three) lines become ONE
 * stroke (the older stroke's id / colour / width are kept; touching ends of one chain close it into a ring). End on the body of another
 * line: the end snaps onto that line and the junction point is added to it, so both lines share the vertex.
 * Returns null when nothing touched (the caller then adds the stroke normally).
 */
object VectorMerge {
    private fun d(a: VectorPoint, b: VectorPoint) = hypot(a.x - b.x, a.y - b.y)
    private fun mid(a: VectorPoint, b: VectorPoint) = VectorPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.pressure + b.pressure) / 2f, (a.elevationOffset + b.elevationOffset) / 2f, max(a.anchor, b.anchor), a.hidden && b.hidden, if (a.anchor >= b.anchor) a.auto else b.auto)
    private fun length(p: List<VectorPoint>): Float { var l = 0f; for (i in 1 until p.size) l += d(p[i - 1], p[i]); return l }
    private fun join(a: List<VectorPoint>, b: List<VectorPoint>): List<VectorPoint> = a.dropLast(1) + mid(a.last(), b.first()) + b.drop(1)

    /**
     * Where a line end at (x, y) would snap, by the same rules as [attach]: the nearest end of an open line first, else the nearest point on the body
     * of a line (not at its very ends). Null when nothing is within reach. Used for the live ring that shows what Merge will do.
     */
    fun snapTarget(strokes: List<VectorStroke>, x: Float, y: Float, tol: Float): FloatArray? {
        var bx = 0f; var by = 0f; var bd = Float.MAX_VALUE; var found = false
        for (o in strokes) {
            if (o.isClosed || o.points.size < 2) continue
            val lim = max(tol, o.baseWidth * 0.5f)
            for (e in listOf(o.points.first(), o.points.last())) {
                val dd = hypot(e.x - x, e.y - y)
                if (dd <= lim && dd < bd) { bd = dd; bx = e.x; by = e.y; found = true }
            }
        }
        if (found) return floatArrayOf(bx, by)
        for (o in strokes) {
            val src = if (o.isClosed && o.points.size >= 3) o.points + o.points.first() else o.points
            if (src.size < 2) continue
            val lim = max(tol, o.baseWidth * 0.5f)
            for (i in 0 until src.size - 1) {
                val a = src[i]; val b = src[i + 1]
                val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
                if (l2 < 1e-9f) continue
                val t = (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
                val qx = a.x + dx * t; val qy = a.y + dy * t
                val dd = hypot(x - qx, y - qy)
                if (dd <= lim && dd < bd && t > 0.02f && t < 0.98f) { bd = dd; bx = qx; by = qy; found = true }
            }
        }
        return if (found) floatArrayOf(bx, by) else null
    }

    /**
     * The Merge button of the point selection bar. Works on the selected points, wherever they are on a line:
     * - Two selected points of different lines (or the two ends of one line) are merged together whatever the distance: end + end join the lines
     *   (the two ends of one line close it into a ring), end + a point of another line puts the end ON that point, point + point meet in the middle.
     * - A selected point with no selected partner merges with the nearest other line within [tol]: an end joins a nearby end, otherwise it snaps onto the BODY
     *   of the line (the line gets a vertex there); a point inside a line snaps onto the nearest line the same way.
     * - A line whose points are all selected (selected as a whole line) only offers its two ends, and only within [tol] (it is not collapsed onto one spot).
     * Joined lines get corner sharpness [sharp]. Null when nothing could be merged.
     */
    fun mergePoints(strokes: List<VectorStroke>, sel: Set<Long>, tol: Float, sharp: Float): List<VectorStroke>? {
        val bySt = HashMap<Int, MutableSet<Int>>()
        for (k in sel) { val si = vecKeyStroke(k); val pi = vecKeyPoint(k); if (si in strokes.indices && strokes[si].fill == 0 && pi in strokes[si].points.indices) bySt.getOrPut(si) { HashSet() }.add(pi) }
        if (bySt.isEmpty()) return null
        class P(val si: Int, val pi: Int, val whole: Boolean) {
            val pt get() = strokes[si].points[pi]
            val isEnd get() = !strokes[si].isClosed && (pi == 0 || pi == strokes[si].points.size - 1)
        }
        val el = ArrayList<P>()
        for ((si, set) in bySt.toSortedMap()) {
            val s = strokes[si]; val n = s.points.size; val whole = set.size >= n
            if (whole) { if (!s.isClosed) { el.add(P(si, 0, true)); if (n > 1) el.add(P(si, n - 1, true)) } }
            else for (pi in set.sorted()) el.add(P(si, pi, false))
        }
        class Join(val aId: String, val ax: Float, val ay: Float, val bId: String, val bx: Float, val by: Float)
        class Close(val id: String)
        class Move(val si: Int, val pi: Int, val x: Float, val y: Float)
        class Ins(val si: Int, val seg: Int, val t: Float, val pt: VectorPoint)
        val joins = ArrayList<Join>(); val closes = ArrayList<Close>(); val moves = ArrayList<Move>(); val ins = ArrayList<Ins>()
        val used = BooleanArray(el.size)
        fun width(si: Int) = strokes[si].baseWidth * 0.5f
        for (i in el.indices) {
            if (used[i]) continue
            val p = el[i]; val pp = p.pt
            // 1. a selected partner
            var bj = -1; var bd = Float.MAX_VALUE
            for (j in el.indices) {
                if (j == i || used[j]) continue
                val q = el[j]; val qp = q.pt
                if (q.si == p.si && !(p.isEnd && q.isEnd && strokes[p.si].points.size >= 3)) continue
                val dd = hypot(pp.x - qp.x, pp.y - qp.y)
                val lim = if (p.whole || q.whole) max(tol, max(width(p.si), width(q.si))) else Float.MAX_VALUE
                if (dd <= lim && dd < bd) { bd = dd; bj = j }
            }
            if (bj >= 0) {
                val q = el[bj]; val qp = q.pt
                if (q.si == p.si) { // the two ends of one line: close it (a short scribble does not count)
                    if (p.whole && length(strokes[p.si].points) <= 4f * tol) continue
                    closes.add(Close(strokes[p.si].id))
                } else if (p.isEnd && q.isEnd) joins.add(Join(strokes[p.si].id, pp.x, pp.y, strokes[q.si].id, qp.x, qp.y))
                else if (p.isEnd) moves.add(Move(p.si, p.pi, qp.x, qp.y))
                else if (q.isEnd) moves.add(Move(q.si, q.pi, pp.x, pp.y))
                else { val mx = (pp.x + qp.x) / 2f; val my = (pp.y + qp.y) / 2f; moves.add(Move(p.si, p.pi, mx, my)); moves.add(Move(q.si, q.pi, mx, my)) }
                used[i] = true; used[bj] = true
                continue
            }
            // 2. the nearest other line within reach: an end first, else a point on the body
            var endSi = -1; var endAt = 0; var endD = Float.MAX_VALUE
            for ((oi, o) in strokes.withIndex()) {
                if (oi == p.si || o.fill != 0 || o.isClosed || o.points.size < 2) continue
                val lim = max(tol, o.baseWidth * 0.5f)
                for (at in listOf(0, o.points.size - 1)) { val e = o.points[at]; val dd = hypot(pp.x - e.x, pp.y - e.y); if (dd <= lim && dd < endD) { endD = dd; endSi = oi; endAt = at } }
            }
            if (endSi >= 0) {
                val e = strokes[endSi].points[endAt]
                if (p.isEnd) joins.add(Join(strokes[p.si].id, pp.x, pp.y, strokes[endSi].id, e.x, e.y)) else moves.add(Move(p.si, p.pi, e.x, e.y))
                used[i] = true; continue
            }
            var bsi = -1; var bseg = 0; var bt = 0f; var bdd = Float.MAX_VALUE
            for ((oi, o) in strokes.withIndex()) {
                if (oi == p.si || o.fill != 0) continue
                val src = if (o.isClosed && o.points.size >= 3) o.points + o.points.first() else o.points
                val lim = max(tol, o.baseWidth * 0.5f)
                for (k in 0 until src.size - 1) {
                    val a = src[k]; val b = src[k + 1]
                    val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
                    if (l2 < 1e-9f) continue
                    val t = (((pp.x - a.x) * dx + (pp.y - a.y) * dy) / l2).coerceIn(0f, 1f)
                    val dd = hypot(pp.x - (a.x + dx * t), pp.y - (a.y + dy * t))
                    if (dd <= lim && dd < bdd && t > 0.02f && t < 0.98f) { bdd = dd; bsi = oi; bseg = k; bt = t }
                }
            }
            if (bsi >= 0) {
                val o = strokes[bsi]; val src = if (o.isClosed && o.points.size >= 3) o.points + o.points.first() else o.points
                val a = src[bseg]; val b = src[bseg + 1]
                val q = VectorPoint(a.x + (b.x - a.x) * bt, a.y + (b.y - a.y) * bt, a.pressure + (b.pressure - a.pressure) * bt, a.elevationOffset + (b.elevationOffset - a.elevationOffset) * bt)
                moves.add(Move(p.si, p.pi, q.x, q.y)); ins.add(Ins(bsi, bseg, bt, q)); used[i] = true
            }
        }
        if (moves.isEmpty() && ins.isEmpty() && joins.isEmpty() && closes.isEmpty()) return null
        val st = strokes.toMutableList()
        for (m in moves) { val s = st[m.si]; val pts = s.points.toMutableList(); pts[m.pi] = pts[m.pi].copy(x = m.x, y = m.y); st[m.si] = s.copy(points = pts) }
        for ((si, list) in ins.groupBy { it.si }) {
            val pts = st[si].points.toMutableList()
            for (q in list.sortedWith(compareByDescending<Ins> { it.seg }.thenByDescending { it.t })) pts.add((q.seg + 1).coerceAtMost(pts.size), q.pt)
            st[si] = st[si].copy(points = pts)
        }
        val alias = HashMap<String, String>()
        fun find(id: String): Int { var k = id; while (alias.containsKey(k)) k = alias.getValue(k); return st.indexOfFirst { it.id == k } }
        fun closeRing(si: Int) {
            val s = st[si]; if (s.isClosed || s.points.size < 3) return
            val pts = s.points.toMutableList(); pts[0] = mid(pts.first(), pts.last()); pts.removeAt(pts.size - 1)
            st[si] = s.copy(points = pts, isClosed = true, joinSharp = max(s.joinSharp, sharp))
        }
        for (j in joins) {
            val ia = find(j.aId); val ib = find(j.bId)
            if (ia < 0 || ib < 0) continue
            val sa = st[ia]; val sb = st[ib]
            val aLast = hypot(sa.points.last().x - j.ax, sa.points.last().y - j.ay) <= hypot(sa.points.first().x - j.ax, sa.points.first().y - j.ay)
            val bLast = hypot(sb.points.last().x - j.bx, sb.points.last().y - j.by) <= hypot(sb.points.first().x - j.bx, sb.points.first().y - j.by)
            if (ia == ib) { if (aLast != bLast) closeRing(ia); continue }
            val pts = when {
                aLast && !bLast -> join(sa.points, sb.points)
                aLast && bLast -> join(sa.points, sb.points.reversed())
                !aLast && bLast -> join(sb.points, sa.points)
                else -> join(sb.points.reversed(), sa.points)
            }
            val keep = min(ia, ib); val drop = max(ia, ib)
            val base = st[keep] // the older line's identity and look win
            st[keep] = base.copy(points = pts, joinSharp = max(base.joinSharp, sharp))
            alias[st[drop].id] = base.id
            st.removeAt(drop)
        }
        for (c in closes) { val i = find(c.id); if (i >= 0) closeRing(i) }
        return st
    }

    /** Merges the freshly drawn [n] into [strokes]: end to end, closing a ring, an end onto the body of another line, and shared vertices where the lines cross. Null when nothing merged. */
    fun attach(strokes: List<VectorStroke>, n: VectorStroke, tol: Float, sharp: Float = 0f): List<VectorStroke>? {
        if (n.points.size < 2) return null
        var cur = n
        val rest = ArrayList(strokes)
        var changed = false
        var joined = false
        var guard = 0
        while (guard++ < 4 && !cur.isClosed) {
            var bi = -1; var bm = 0; var bd = Float.MAX_VALUE
            val c0 = cur.points.first(); val c1 = cur.points.last()
            for ((i, o) in rest.withIndex()) {
                if (o.isClosed || o.points.size < 2) continue
                val lim = max(tol, o.baseWidth * 0.5f)
                val o0 = o.points.first(); val o1 = o.points.last()
                val cands = listOf(d(c1, o0) to 1, d(c1, o1) to 2, d(c0, o1) to 3, d(c0, o0) to 4)
                for ((dd, m) in cands) if (dd <= lim && dd < bd) { bd = dd; bi = i; bm = m }
            }
            if (bi < 0) break
            val o = rest.removeAt(bi)
            val pts = when (bm) {
                1 -> join(cur.points, o.points)
                2 -> join(cur.points, o.points.reversed())
                3 -> join(o.points, cur.points)
                else -> join(o.points.reversed(), cur.points)
            }
            cur = o.copy(points = pts) // the older stroke's identity and look win
            changed = true; joined = true
        }
        // a line whose end comes back to its own start closes into a ring (also when it joined nothing else); a short scribble does not count
        if (cur.points.size >= 4 && d(cur.points.first(), cur.points.last()) <= tol && length(cur.points) > 4f * tol) { cur = cur.copy(points = cur.points.dropLast(1), isClosed = true); changed = true; joined = true }
        // T junctions: an end that lies on the body of another line snaps onto it and the line gets the same vertex
        if (!cur.isClosed) {
            for (end in 0..1) {
                val pts = cur.points
                val e = if (end == 0) pts.first() else pts.last()
                var bo = -1; var bi = -1; var bt = 0f; var bd = Float.MAX_VALUE
                for ((oi, o) in rest.withIndex()) {
                    val src = if (o.isClosed && o.points.size >= 3) o.points + o.points.first() else o.points
                    val lim = max(tol, o.baseWidth * 0.5f)
                    for (i in 0 until src.size - 1) {
                        val a = src[i]; val b = src[i + 1]
                        val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
                        if (l2 < 1e-9f) continue
                        val t = (((e.x - a.x) * dx + (e.y - a.y) * dy) / l2).coerceIn(0f, 1f)
                        val dd = hypot(e.x - (a.x + dx * t), e.y - (a.y + dy * t))
                        if (dd <= lim && dd < bd && t > 0.02f && t < 0.98f) { bd = dd; bo = oi; bi = i; bt = t }
                    }
                }
                if (bo < 0) continue
                val o = rest[bo]
                val src = if (o.isClosed && o.points.size >= 3) o.points + o.points.first() else o.points
                val a = src[bi]; val b = src[bi + 1]
                val q = VectorPoint(a.x + (b.x - a.x) * bt, a.y + (b.y - a.y) * bt, a.pressure + (b.pressure - a.pressure) * bt, a.elevationOffset + (b.elevationOffset - a.elevationOffset) * bt)
                val ins = o.points.toMutableList(); ins.add((bi + 1).coerceAtMost(ins.size), q)
                rest[bo] = o.copy(points = ins)
                val np = pts.toMutableList(); np[if (end == 0) 0 else np.size - 1] = q.copy(pressure = e.pressure)
                cur = cur.copy(points = np)
                changed = true
            }
        }
        // crossings: where the body of this line crosses the body of another, both lines get the crossing as a shared vertex, so lines merge anywhere along them, not only at their ends
        if (cur.points.size >= 2) {
            class Ins(val seg: Int, val t: Float, val pt: VectorPoint)
            fun segsOf(s: VectorStroke) = if (s.isClosed && s.points.size > 2) s.points.size else s.points.size - 1
            fun near(s: VectorStroke, x: Float, y: Float) = s.points.any { hypot(it.x - x, it.y - y) < 0.6f }
            fun applyIns(s: VectorStroke, list: List<Ins>): VectorStroke {
                if (list.isEmpty()) return s
                val pts = s.points.toMutableList()
                for (q in list.sortedWith(compareByDescending<Ins> { it.seg }.thenByDescending { it.t })) pts.add((q.seg + 1).coerceAtMost(pts.size), q.pt)
                return s.copy(points = pts)
            }
            val curIns = ArrayList<Ins>(); val restIns = HashMap<Int, ArrayList<Ins>>()
            val cp = cur.points
            for ((oi, o) in rest.withIndex()) {
                if (o.fill != 0 || o.points.size < 2) continue
                var found = 0
                val op = o.points
                for (i in 0 until segsOf(cur)) {
                    val p0 = cp[i]; val p1 = cp[(i + 1) % cp.size]
                    for (j in 0 until segsOf(o)) {
                        if (found >= 12) break
                        val q0 = op[j]; val q1 = op[(j + 1) % op.size]
                        val rx = p1.x - p0.x; val ry = p1.y - p0.y; val sx = q1.x - q0.x; val sy = q1.y - q0.y
                        val den = rx * sy - ry * sx
                        if (abs(den) < 1e-9f) continue
                        val tt = ((q0.x - p0.x) * sy - (q0.y - p0.y) * sx) / den
                        val uu = ((q0.x - p0.x) * ry - (q0.y - p0.y) * rx) / den
                        if (tt <= 0.001f || tt >= 0.999f || uu <= 0.001f || uu >= 0.999f) continue
                        val x = p0.x + rx * tt; val y = p0.y + ry * tt
                        val nc = near(cur, x, y); val no = near(o, x, y)
                        if (nc && no) continue
                        if (!nc) curIns.add(Ins(i, tt, VectorPoint(x, y, p0.pressure + (p1.pressure - p0.pressure) * tt, p0.elevationOffset + (p1.elevationOffset - p0.elevationOffset) * tt)))
                        if (!no) restIns.getOrPut(oi) { ArrayList() }.add(Ins(j, uu, VectorPoint(x, y, q0.pressure + (q1.pressure - q0.pressure) * uu, q0.elevationOffset + (q1.elevationOffset - q0.elevationOffset) * uu)))
                        found++
                    }
                }
            }
            if (curIns.isNotEmpty() || restIns.isNotEmpty()) {
                cur = applyIns(cur, curIns)
                for ((oi, list) in restIns) rest[oi] = applyIns(rest[oi], list)
                changed = true
            }
        }
        if (!changed) return null
        if (joined && sharp > 0.001f) cur = cur.copy(joinSharp = max(cur.joinSharp, sharp)) // the joint (and the other turns of the line) stay crisp instead of being rounded
        rest.add(cur)
        return rest
    }
}

/**
 * Point-edit brushes. They act on every point under the brush circle (smooth falloff to its edge): Shift drags points along with the pen,
 * Smoothen pulls each point toward the middle of its neighbours (a wobbly line relaxes), Distort pushes points sideways by a smooth,
 * time-varying wobble. Existing control points are moved (the line is drawn as a smooth curve through them); a point is added only where the brush touches a stretch with none inside its radius.
 * The stroke count never changes (indices of strokes stay valid).
 */
object VectorBrush {
    private fun segDist(px: Float, py: Float, a: VectorPoint, b: VectorPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * dx + (py - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (a.x + dx * t), py - (a.y + dy * t))
    }

    /**
     * The lines are drawn as smooth curves through their control points, so the brushes only MOVE the points that are already there. A point is added
     * only when the brush touches a stretch of line with no control point inside its radius at all (otherwise there would be nothing to move); it is
     * placed on the drawn curve, nearest to the brush centre, so the line does not change shape by being given a handle.
     */
    private fun ensureHandle(s: VectorStroke, cx: Float, cy: Float, r: Float): VectorStroke {
        val src = s.points; val n = src.size
        if (n < 2 || n > 6000) return s
        for (p in src) if (hypot(p.x - cx, p.y - cy) < r) return s
        val closed = s.isClosed && n >= 3
        val segs = if (closed) n else n - 1
        var bestD = Float.MAX_VALUE; var bi = -1; var bu = 0f
        val tmp = FloatArray(2)
        for (i in 0 until segs) {
            val a = src[i]; val b = src[(i + 1) % n]
            if (segDist(cx, cy, a, b) > r + hypot(b.x - a.x, b.y - a.y) * 0.5f) continue // the curve bows only so far from its chord
            for (q in 1..7) {
                val u = q / 8f
                VectorTessellator.curveAt(src, closed, i, u, tmp)
                val d = hypot(tmp[0] - cx, tmp[1] - cy)
                if (d < bestD) { bestD = d; bi = i; bu = u }
            }
        }
        if (bi < 0 || bestD > r) return s
        VectorTessellator.curveAt(src, closed, bi, bu, tmp)
        val a = src[bi]; val b = src[(bi + 1) % n]
        val np = VectorPoint(tmp[0], tmp[1], a.pressure + (b.pressure - a.pressure) * bu, a.elevationOffset + (b.elevationOffset - a.elevationOffset) * bu)
        val out = ArrayList<VectorPoint>(n + 1)
        for (i in 0 until n) { out.add(src[i]); if (i == bi) out.add(np) }
        return s.copy(points = out)
    }

    /** mode 1 Shift, 2 Smoothen, 3 Distort. (dx, dy) = pen movement since the last call, [phase] advances every call. Returns [strokes] itself when nothing changed. */
    fun apply(strokes: List<VectorStroke>, mode: Int, cx: Float, cy: Float, r: Float, strength: Float, dx: Float, dy: Float, phase: Int): List<VectorStroke> {
        if (r <= 0.5f || strength <= 0f) return strokes
        var any = false
        val out = strokes.map { s0 ->
            var near = false
            for (i in 0 until s0.points.size - 1) if (segDist(cx, cy, s0.points[i], s0.points[i + 1]) <= r) { near = true; break }
            if (!near && s0.points.size == 1) near = hypot(s0.points[0].x - cx, s0.points[0].y - cy) <= r
            if (!near && s0.isClosed && s0.points.size >= 3 && segDist(cx, cy, s0.points.last(), s0.points.first()) <= r) near = true
            if (!near) return@map s0
            val s = if (mode == 1 && hypot(dx, dy) < 1e-4f) s0 else ensureHandle(s0, cx, cy, r)
            val pts = s.points; val n = pts.size
            val closed = s.isClosed && n >= 3
            val w = FloatArray(n) { VectorEdit.falloff(hypot(pts[it].x - cx, pts[it].y - cy), r) }
            if (w.all { it <= 0f }) return@map s
            val res = ArrayList<VectorPoint>(n)
            // arc length (for the wobble) and the sideways direction at every point
            val arc = FloatArray(n); for (i in 1 until n) arc[i] = arc[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            val ph1 = phase * 1.7f; val ph2 = phase * 2.9f
            for (i in 0 until n) {
                val p = pts[i]; val f = w[i]
                if (f <= 0f) { res.add(p); continue }
                val prev = if (i > 0) pts[i - 1] else if (closed) pts[n - 1] else null
                val next = if (i < n - 1) pts[i + 1] else if (closed) pts[0] else null
                when (mode) {
                    1 -> res.add(p.copy(x = p.x + dx * f * strength, y = p.y + dy * f * strength))
                    2 -> {
                        if (prev == null || next == null) { res.add(p); continue } // open ends stay where they are
                        val k = (f * strength * 0.6f).coerceIn(0f, 1f)
                        res.add(p.copy(x = p.x + ((prev.x + next.x) / 2f - p.x) * k, y = p.y + ((prev.y + next.y) / 2f - p.y) * k))
                    }
                    else -> {
                        val a = prev ?: p; val b = next ?: p
                        var tx = b.x - a.x; var ty = b.y - a.y
                        val tl = hypot(tx, ty); if (tl < 1e-6f) { res.add(p); continue }
                        tx /= tl; ty /= tl
                        val wob = sin(arc[i] * 0.11f + ph1) * 0.65f + sin(arc[i] * 0.27f + ph2) * 0.35f
                        val amp = r * 0.05f * strength * f * wob
                        res.add(p.copy(x = p.x - ty * amp, y = p.y + tx * amp))
                    }
                }
            }
            any = true
            s.copy(points = res)
        }
        return if (any) out else strokes
    }
}

/**
 * Vector eraser: erasing at a spot removes the part of the line under the pen, from the nearest crossing with another line on one side to
 * the nearest crossing on the other (or to the end of the line when there is no crossing). The rest of the line stays, cut exactly at the crossings.
 */
object VectorEraser {
    private fun ring(s: VectorStroke): List<VectorPoint> = if (s.isClosed && s.points.size >= 3) s.points + s.points.first() else s.points

    private fun pointAt(src: List<VectorPoint>, segs: Int, closed: Boolean, pos: Float): VectorPoint {
        var i = kotlin.math.floor(pos).toInt(); var t = pos - i
        if (closed) i = ((i % segs) + segs) % segs else if (i >= segs) { i = segs - 1; t = 1f } else if (i < 0) { i = 0; t = 0f }
        val a = src[i]; val b = src[i + 1]
        return VectorPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure + (b.pressure - a.pressure) * t, a.elevationOffset + (b.elevationOffset - a.elevationOffset) * t, if (t < 0.5f) a.anchor else b.anchor, if (t < 0.5f) a.hidden else b.hidden, if (t < 0.5f) a.auto else b.auto)
    }

    private fun slice(src: List<VectorPoint>, segs: Int, closed: Boolean, a: Float, b: Float): List<VectorPoint> {
        val out = ArrayList<VectorPoint>()
        out.add(pointAt(src, segs, closed, a))
        var k = kotlin.math.floor(a).toInt() + 1
        while (k < b - 1e-4f) { out.add(if (closed) src[((k % segs) + segs) % segs] else src[k.coerceIn(0, segs)]); k++ }
        out.add(pointAt(src, segs, closed, b))
        val clean = ArrayList<VectorPoint>()
        for (p in out) if (clean.isEmpty() || hypot(p.x - clean.last().x, p.y - clean.last().y) > 1e-3f) clean.add(p)
        return clean
    }

    /** Erases at (x, y); null when no line is within [radius] of that spot. */
    fun eraseAt(strokes: List<VectorStroke>, x: Float, y: Float, radius: Float): List<VectorStroke>? {
        var best = Float.MAX_VALUE; var bs = -1; var bpos = 0f
        for ((si, s) in strokes.withIndex()) {
            val src = ring(s)
            if (src.size == 1) { val dd = hypot(src[0].x - x, src[0].y - y); if (dd <= radius + s.baseWidth * 0.5f && dd < best) { best = dd; bs = si; bpos = 0f }; continue }
            val reach = radius + s.baseWidth * 0.5f
            for (i in 0 until src.size - 1) {
                val a = src[i]; val b = src[i + 1]
                val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
                val t = if (l2 < 1e-9f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
                val dd = hypot(x - (a.x + dx * t), y - (a.y + dy * t))
                if (dd <= reach && dd < best) { best = dd; bs = si; bpos = i + t }
            }
        }
        if (bs < 0) return null
        val s = strokes[bs]
        val src = ring(s)
        val out = ArrayList<VectorStroke>(strokes)
        if (src.size < 2) { out.removeAt(bs); return out }
        val segs = src.size - 1
        val closed = s.isClosed && s.points.size >= 3
        val xs = ArrayList<Float>()
        for ((oi, o) in strokes.withIndex()) {
            if (oi == bs) continue
            val os = ring(o)
            for (j in 0 until os.size - 1) {
                val a = os[j]; val b = os[j + 1]
                val sx = b.x - a.x; val sy = b.y - a.y
                for (i in 0 until segs) {
                    val p = src[i]; val q = src[i + 1]
                    val rx = q.x - p.x; val ry = q.y - p.y
                    val den = rx * sy - ry * sx
                    if (abs(den) < 1e-9f) continue
                    val t = ((a.x - p.x) * sy - (a.y - p.y) * sx) / den
                    val u = ((a.x - p.x) * ry - (a.y - p.y) * rx) / den
                    if (t in 0f..1f && u in 0f..1f) xs.add(i + t)
                }
            }
        }
        xs.sort()
        val lo = xs.lastOrNull { it < bpos - 1e-4f }
        val hi = xs.firstOrNull { it > bpos + 1e-4f }
        val pieces = ArrayList<List<VectorPoint>>()
        if (closed) {
            if (xs.size >= 2) {
                val l = lo ?: (xs.last() - segs); val h = hi ?: (xs.first() + segs)
                if (h < l + segs - 1e-4f) pieces.add(slice(src, segs, true, h, l + segs))
            }
        } else {
            if (lo != null) pieces.add(slice(src, segs, false, 0f, lo))
            if (hi != null) pieces.add(slice(src, segs, false, hi, segs.toFloat()))
        }
        val res = ArrayList<VectorStroke>()
        var first = true
        for (pts in pieces) {
            if (pts.size < 2) continue
            var len = 0f; for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            if (len < 0.5f) continue
            res.add(s.copy(id = if (first) s.id else newStrokeId(), points = pts, isClosed = false)); first = false
        }
        out.removeAt(bs); out.addAll(bs, res)
        return out
    }
}


/**
 * Sets (amount > 0) or clears (amount == 0) the wobble of every line of the [targets] keyframes of [layer], one undo step. Every keyframe gets
 * its own noise seed (kept when it already has one, unless [reseed]), and every line in it a seed of its own, so no two look alike.
 * Returns the keyframes that now are on the timeline in place of [targets] (same positions).
 */
fun setVectorWobble(cut: Cut, layer: Layer, targets: List<Cel>, amount: Float, scale: Float, reseed: Boolean): List<Cel> {
    val idxs = targets.map { t -> layer.cels.indexOfFirst { it === t } }.filter { it >= 0 }.distinct()
    if (idxs.isEmpty()) return targets
    val rnd = java.util.Random()
    cut.record {
        for (i in idxs) {
            val cel = layer.cels.getOrNull(i) ?: continue
            val strokes = cel.vectorStrokes()
            if (strokes.isEmpty()) continue
            val base = strokes.firstOrNull { it.wobbleSeed != 0 }?.wobbleSeed?.takeIf { !reseed } ?: rnd.nextInt()
            val out = strokes.mapIndexed { si, st -> if (amount <= 0.01f) st.copy(wobble = 0f) else st.copy(wobble = amount, wobbleScale = scale, wobbleSeed = base + si * 7919) }
            swapVectorCel(layer, cel, out)
        }
    }
    return idxs.mapNotNull { layer.cels.getOrNull(it) }
}

/** Everything the Line weight bar sets. [aa] null = leave the anti-aliasing of the lines alone; otherwise 0 (hard, aliased edge) or 1 (anti-aliased) with [soft] extra edge softness in px. */
data class InkParams(
    val curve: Float = 0f, val light: Float = 0f, val angle: Float = 315f, val tIn: Float = 0f, val tOut: Float = 0f,
    val irr: Float = 0f, val irrScale: Float = 40f, val aa: Float? = null, val soft: Float = 0f
) {
    /** [s] with these settings (fills are left alone). */
    fun applyTo(s: VectorStroke): VectorStroke {
        if (s.fill != 0) return s
        val w = s.copy(inkCurve = curve, inkLight = light, inkAngle = angle, taperIn = tIn, taperOut = tOut, inkIrr = irr, inkIrrScale = irrScale)
        return if (aa != null) w.copy(aa = aa, aaSoft = soft) else w
    }
}

/**
 * Sets the designed line weight and anti-aliasing (see [InkParams]) on every line of the [targets] keyframes of [layer], one undo step.
 * Fills are left alone. Returns the keyframes that now are on the timeline in place of [targets].
 */
fun setVectorInk(cut: Cut, layer: Layer, targets: List<Cel>, p: InkParams): List<Cel> {
    val idxs = targets.map { t -> layer.cels.indexOfFirst { it === t } }.filter { it >= 0 }.distinct()
    if (idxs.isEmpty()) return targets
    cut.record {
        for (i in idxs) {
            val cel = layer.cels.getOrNull(i) ?: continue
            val strokes = cel.vectorStrokes()
            if (strokes.none { it.fill == 0 }) continue
            swapVectorCel(layer, cel, strokes.map { p.applyTo(it) })
        }
    }
    return idxs.mapNotNull { layer.cels.getOrNull(it) }
}

/**
 * Live preview of the Line weight bar: while it is on, the keyframes it targets are drawn from a transient copy whose lines carry the bar's current
 * settings. Nothing in the document changes (no undo step, nothing saved) and it is cleared when the bar closes. Only main-thread drawing sees it,
 * so exports and background renders always use the real lines. Follows the same transient-cel rules as [VectorLive].
 */
object VectorInkPreview {
    private class Entry(val sig: List<Any?>, val cel: Cel, val prev: Cel?)
    private val cache = object : LinkedHashMap<Int, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean {
            if (size <= 24) return false
            TileCache.invalidate(eldest.value.cel.strokes)
            eldest.value.prev?.let { TileCache.invalidate(it.strokes) }
            return true
        }
    }
    private var params by androidx.compose.runtime.mutableStateOf<InkParams?>(null)
    private var layerId = -1
    private var targets: List<Cel> = emptyList()
    private var ready by mutableIntStateOf(0)
    private val pending = HashSet<Int>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Turns the preview on (or updates it) for [targets] of layer [id]. No-op when nothing changed. */
    @Synchronized
    fun set(id: Int, targets: List<Cel>, p: InkParams) {
        val same = params == p && layerId == id && this.targets.size == targets.size && targets.indices.all { this.targets[it] === targets[it] }
        if (same) return
        layerId = id; this.targets = targets.toList(); params = p
    }

    @Synchronized
    fun clear() {
        if (params == null && cache.isEmpty()) return
        cache.values.forEach { TileCache.invalidate(it.cel.strokes); it.prev?.let { o -> TileCache.invalidate(o.strokes) } }
        cache.clear(); targets = emptyList(); params = null
    }

    /** The cel to draw instead of [cel] (the held or interpolated cel the renderer picked for [layer]). */
    @Synchronized
    fun wrap(layer: Layer, cel: Cel?): Cel? {
        val p = params ?: return cel // reading the state makes the canvas redraw when the preview starts, changes or ends
        if (cel == null || layer.id != layerId || Looper.myLooper() != Looper.getMainLooper()) return cel
        if (targets.none { it === cel }) return cel
        val key = System.identityHashCode(cel)
        val sig = listOf(p, cel.vecRev, cel.strokes.size)
        val readOnly = Snapshot.current.readOnly
        if (readOnly) { ready; OnionWarmup.ready.intValue }
        val hit = cache[key]
        if (hit != null && hit.sig == sig) {
            if (!readOnly) return hit.cel
            if (TileCache.peekReady(hit.cel.strokes) != null) return hit.cel
            OnionWarmup.request(hit.cel.strokes, urgent = true)
            return hit.prev?.takeIf { TileCache.peekReady(it.strokes) != null } ?: cel
        }
        if (!readOnly) return build(key, sig, cel, p)
        if (pending.add(key)) main.post {
            synchronized(this) { try { build(key, sig, cel, p) } catch (_: Throwable) { } finally { pending.remove(key) } }
            ready += 1
        }
        return hit?.cel?.takeIf { TileCache.peekReady(it.strokes) != null } ?: cel
    }

    private fun build(key: Int, sig: List<Any?>, cel: Cel, p: InkParams): Cel {
        val old = cache[key]
        if (old != null && old.sig == sig) return old.cel
        old?.prev?.let { TileCache.invalidate(it.strokes) }
        val c = Cel(1)
        cel.vectorStrokes().forEach { c.strokes.add(p.applyTo(it).toInk()) }
        cache[key] = Entry(sig, c, old?.cel)
        return c
    }
}

/**
 * Incremental editing of one vector cel while a drag is running (transform, point move, point brushes). The first time strokes move, a base set
 * is rasterized from the strokes that never move; after that every change only resets the tiles the moving strokes touch (old and new place)
 * to the base and draws the moving strokes there. The stroke list of the cel is kept current (only changed strokes are re-encoded) so
 * interpolation and undo see the real data. [finish] unpins and, when a moved stroke was originally below a static one, rebuilds the tiles once in the right order.
 */
class VecLiveCel(val cel: Cel, orig: List<VectorStroke>) {
    private var cur: List<VectorStroke> = orig
    private val moved = BooleanArray(orig.size)
    private var base: TileSet? = null
    private var baseStale = true
    private var broken = false

    private fun full(edited: List<VectorStroke>) { // the number of strokes changed: the plain path (clear, refill, rebuild)
        cel.strokes.clear(); cel.vecRev++
        edited.forEach { cel.strokes.add(it.toInk()) }
        TileCache.invalidate(cel.strokes); TileCache.get(cel.strokes)
        cur = edited
    }

    fun apply(edited: List<VectorStroke>) {
        if (broken || edited.size != cur.size || cel.strokes.size != cur.size) { broken = true; full(edited); return }
        val set = TileCache.peek(cel.strokes)
        if (set == null || set.count != cel.strokes.size) { broken = true; full(edited); return }
        val changed = ArrayList<Int>()
        for (j in edited.indices) if (edited[j] !== cur[j]) changed.add(j)
        if (changed.isEmpty()) return
        val rects = ArrayList<FloatArray>()
        for (j in changed) {
            rects.add(VectorTessellator.bounds(cur[j]))
            if (!moved[j]) { moved[j] = true; baseStale = true }
        }
        set.pinned = true
        if (baseStale) {
            base?.clear()
            val b = TileSet()
            for (j in edited.indices) if (!moved[j]) b.stampStroke(cur[j])
            base = b; baseStale = false
        }
        val moving = ArrayList<VectorStroke>()
        for (j in edited.indices) if (moved[j]) { moving.add(edited[j]); rects.add(VectorTessellator.bounds(edited[j])) }
        set.liveVector(base, moving, rects)
        cel.vecRev++
        for (j in changed) cel.strokes[j] = edited[j].toInk()
        cur = edited
    }

    fun finish() {
        base?.clear(); base = null
        val set = TileCache.peek(cel.strokes)
        set?.pinned = false
        if (broken) return
        val firstMoved = moved.indexOfFirst { it }
        if (firstMoved >= 0 && (firstMoved until moved.size).any { !moved[it] }) { // a moved stroke was below a static one: redraw in the right order
            TileCache.invalidate(cel.strokes); TileCache.get(cel.strokes)
        }
    }
}
