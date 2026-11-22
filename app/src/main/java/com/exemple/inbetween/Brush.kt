package com.example.inbetween

import org.json.JSONArray
import org.json.JSONObject

private var idCounter = System.currentTimeMillis()
fun nextId(): Long = ++idCounter

/** Curves are points sorted by x, three floats each: x, y (both 0..1) and the tangent at that point.
 *  The tangent is a slope (dy/dx) the user can drag, or TAN_AUTO ("medium": smooth, worked out from the neighbours),
 *  or TAN_LINEAR (the segments on both sides of the point are straight lines). */
const val TAN_AUTO = 9999f
const val TAN_LINEAR = -9999f
fun curvePoint(x: Float, y: Float, tan: Float = TAN_AUTO) = listOf(x, y, tan)
fun linearCurve(min: Float) = curvePoint(0f, min) + curvePoint(1f, 1f)
fun sCurve(): List<Float> = curvePoint(0f, 0f, 0f) + curvePoint(1f, 1f, 0f)                 // smooth step
fun easeInCurve(): List<Float> = curvePoint(0f, 0f, 0f) + curvePoint(1f, 1f, 2f)            // y = x^2
fun easeOutCurve(): List<Float> = curvePoint(0f, 0f, 2f) + curvePoint(1f, 1f, 0f)           // y = 1 - (1 - x)^2
fun curveSize(c: List<Float>) = c.size / 3
fun curveX(c: List<Float>, i: Int) = c[i * 3]
fun curveY(c: List<Float>, i: Int) = c[i * 3 + 1]
fun curveTan(c: List<Float>, i: Int) = c[i * 3 + 2]

/** Old files stored (x, y) pairs only: every point becomes an automatic-tangent point. */
fun curveFromPairs(p: List<Float>): List<Float> { val o = ArrayList<Float>(); for (i in 0 until p.size / 2) { o.add(p[i * 2]); o.add(p[i * 2 + 1]); o.add(TAN_AUTO) }; return o }
fun curveToPairs(c: List<Float>): List<Float> { val o = ArrayList<Float>(); for (i in 0 until curveSize(c)) { o.add(curveX(c, i)); o.add(curveY(c, i)) }; return o }

private fun chord(c: List<Float>, i: Int, j: Int): Float { val dx = curveX(c, j) - curveX(c, i); return if (dx > 1e-6f) (curveY(c, j) - curveY(c, i)) / dx else 0f }

/** Smooth ("medium") slope at point [k]: harmonic mean of the neighbouring segment slopes (0 at a turning point), so it never overshoots. */
private fun autoTangent(c: List<Float>, k: Int): Float {
    val n = curveSize(c)
    if (n < 2) return 0f
    if (k <= 0) return chord(c, 0, 1)
    if (k >= n - 1) return chord(c, n - 2, n - 1)
    val a = chord(c, k - 1, k); val b = chord(c, k, k + 1)
    return if (a * b <= 0f) 0f else 2f * a * b / (a + b)
}

/** The slope the curve actually has at point [k], on its right side (toward k + 1) or left side (toward k - 1). */
fun curveTangentAt(c: List<Float>, k: Int, rightSide: Boolean = true): Float {
    val n = curveSize(c)
    val t = curveTan(c, k)
    return when {
        t == TAN_AUTO -> autoTangent(c, k)
        t == TAN_LINEAR -> if (rightSide && k < n - 1) chord(c, k, k + 1) else if (k > 0) chord(c, k - 1, k) else chord(c, 0, 1)
        else -> t
    }
}

/** Smooth interpolation through the points (cubic Hermite with each point's tangent), not straight lines between them. Flat beyond the end points. */
fun curveEval(c: List<Float>, x: Float): Float {
    val n = curveSize(c)
    if (n == 0) return x
    if (n == 1 || x <= curveX(c, 0)) return curveY(c, 0)
    if (x >= curveX(c, n - 1)) return curveY(c, n - 1)
    var i = 1
    while (i < n - 1 && x > curveX(c, i)) i++
    val x0 = curveX(c, i - 1); val x1 = curveX(c, i); val y0 = curveY(c, i - 1); val y1 = curveY(c, i)
    val h = x1 - x0
    if (h <= 1e-6f) return y1
    val t = (x - x0) / h
    val m0 = curveTangentAt(c, i - 1, true) * h; val m1 = curveTangentAt(c, i, false) * h
    val t2 = t * t; val t3 = t2 * t
    return ((2f * t3 - 3f * t2 + 1f) * y0 + (t3 - 2f * t2 + t) * m0 + (-2f * t3 + 3f * t2) * y1 + (t3 - t2) * m1).coerceIn(0f, 1f)
}

/** All brush / eraser settings. Pressure maps through the two curves (size, opacity). */
data class Brush(
    val name: String, val erase: Boolean = false,
    val size: Float = 6f, val opacity: Float = 1f, val hardness: Float = 1f, val spacing: Float = 0.1f,
    val sizeCurve: List<Float> = linearCurve(0.2f), val opacityCurve: List<Float> = linearCurve(1f),
    val tilt: Float = 0f, val taperStart: Float = 0.3f, val taperEnd: Float = 0.5f, val texture: Float = 0f,
    val stabMethod: Int = 3, val stab: Int = 0, val id: Long = nextId(),
    val tip: Int = 0,            // 0 circle, 1 triangle, 2 square, 3 etched (TIP_NAMES)
    val textureId: Int = 0,      // selectable texture (TEXTURE_NAMES); `texture` above is the "Grain" slider
    val textureAmt: Float = 0.7f, // strength of the selected texture
    val aa: Float = 0.5f,         // anti-aliasing: 0 = aliased, 0.5 = normal, 1 = extra soft edge
    val velSize: Float = 0f,      // velocity taper: 0 = off, 1 = the fastest strokes are the thinnest (baked into the stroke's widths)
    val scatter: Float = 0f,      // dab jitter: random offset of every dab, as a fraction of its radius
    val sizeJitter: Float = 0f,   // dab jitter: random size change of every dab
    val angleJitter: Float = 0f,  // dab jitter: random rotation of every dab (1 = any angle; only shows on triangle / square / etched tips)
    val opacityJitter: Float = 0f, // dab jitter: random opacity loss of every dab
    val wet: Float = 0f,          // wet mix: 0 = off, 1 = the brush takes on the colour already under it
    val wetLength: Float = 0.5f,  // wet mix: how far the picked-up colour carries onto bare canvas before it fades back to the ink colour
    val flow: Float = 1f,         // paint laid down per dab (build-up rate), before pressure and opacity
    val capped: Boolean = false,  // true: opacity is a ceiling for the whole stroke (overlaps do not build up); false: classic build-up
    val wetPressure: Float = 0f,  // -1..1: how much pressure (through the pressure -> flow curve) scales the wet mix; negative = a light touch mixes more
    val tipImg: Int = 0,          // imported stamp used as the tip (BrushAssets id, 0 = built-in tip)
    val texImg: Int = 0,          // imported texture (BrushAssets id, 0 = built-in texture)
    val texScale: Float = 1f,     // texture size, 0.25..4
    val texInvert: Boolean = false, // texture dark <-> light
    val texMoving: Boolean = false, // texture is anchored to the start of the stroke instead of the page
    val tipAngle: Float = 0f,     // tip rotation in degrees
    val tipFollow: Boolean = false, // the tip also turns to the direction of the stroke
    val roundness: Float = 1f     // tip squash, 0.1..1
)

val STAB_NAMES = listOf("None", "Lazy follow", "One Euro (time-based)", "Moving average", "Spline (resampled)", "Spring (string)")

val DEFAULT_BRUSHES = listOf(
    Brush("Ink pen", size = 6f),
    Brush("Fine liner", size = 3f, sizeCurve = linearCurve(0.5f), taperStart = 0.2f, taperEnd = 0.3f),
    Brush("Pencil", size = 8f, opacity = 0.85f, hardness = 0.75f, spacing = 0.12f, sizeCurve = linearCurve(0.5f), opacityCurve = linearCurve(0.4f), texture = 0.6f, taperStart = 0.2f, taperEnd = 0.4f),
    Brush("Marker", size = 24f, opacity = 0.7f, spacing = 0.06f, sizeCurve = linearCurve(0.85f), taperStart = 0f, taperEnd = 0f),
    Brush("Soft brush", size = 40f, opacity = 0.5f, hardness = 0.2f, spacing = 0.08f, sizeCurve = linearCurve(0.4f), opacityCurve = linearCurve(0.5f), taperStart = 0f, taperEnd = 0f)
)

val DEFAULT_ERASERS = listOf(
    Brush("Hard eraser", erase = true, size = 30f, sizeCurve = linearCurve(0.8f), taperStart = 0f, taperEnd = 0f),
    Brush("Soft eraser", erase = true, size = 60f, opacity = 0.8f, hardness = 0.3f, spacing = 0.08f, sizeCurve = linearCurve(0.7f), taperStart = 0f, taperEnd = 0f),
    Brush("Pencil eraser", erase = true, size = 14f, hardness = 0.8f, texture = 0.4f, sizeCurve = linearCurve(0.6f), taperStart = 0f, taperEnd = 0f)
)

fun Brush.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("erase", erase)
    .put("size", size.toDouble()).put("opacity", opacity.toDouble()).put("hardness", hardness.toDouble()).put("spacing", spacing.toDouble())
    .put("sizeCurve", JSONArray(curveToPairs(sizeCurve).map { it.toDouble() })).put("opacityCurve", JSONArray(curveToPairs(opacityCurve).map { it.toDouble() })) // (x, y) pairs: what older versions read
    .put("sizeCurveT", JSONArray(sizeCurve.map { it.toDouble() })).put("opacityCurveT", JSONArray(opacityCurve.map { it.toDouble() })) // x, y, tangent triples
    .put("tilt", tilt.toDouble()).put("taperStart", taperStart.toDouble()).put("taperEnd", taperEnd.toDouble()).put("texture", texture.toDouble())
    .put("stabMethod", stabMethod).put("stab", stab).put("tip", tip).put("textureId", textureId).put("textureAmt", textureAmt.toDouble()).put("aa", aa.toDouble())
    .put("velSize", velSize.toDouble()).put("scatter", scatter.toDouble()).put("sizeJitter", sizeJitter.toDouble()).put("angleJitter", angleJitter.toDouble())
    .put("opacityJitter", opacityJitter.toDouble()).put("wet", wet.toDouble()).put("wetLength", wetLength.toDouble())
    .put("flow", flow.toDouble()).put("capped", capped).put("wetPressure", wetPressure.toDouble()).put("tipImg", tipImg).put("texImg", texImg)
    .put("texScale", texScale.toDouble()).put("texInvert", texInvert).put("texMoving", texMoving).put("tipAngle", tipAngle.toDouble()).put("tipFollow", tipFollow).put("roundness", roundness.toDouble())

fun brushFromJson(o: JSONObject, newId: Boolean = false): Brush {
    fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
    fun curve(k: String, d: List<Float>): List<Float> {
        o.optJSONArray(k + "T")?.let { a -> if (a.length() >= 6 && a.length() % 3 == 0) return List(a.length()) { a.getDouble(it).toFloat() } }
        val a = o.optJSONArray(k) ?: return d
        return curveFromPairs(List(a.length()) { a.getDouble(it).toFloat() })
    }
    return Brush(
        o.optString("name", "Brush"), o.optBoolean("erase", false), f("size", 6f), f("opacity", 1f), f("hardness", 1f), f("spacing", 0.1f),
        curve("sizeCurve", linearCurve(0.2f)), curve("opacityCurve", linearCurve(1f)), f("tilt", 0f), f("taperStart", 0.3f), f("taperEnd", 0.5f), f("texture", 0f),
        o.optInt("stabMethod", 3), o.optInt("stab", 0), if (newId || !o.has("id")) nextId() else o.getLong("id"),
        tip = o.optInt("tip", 0).coerceIn(0, TIP_NAMES.size - 1), textureId = o.optInt("textureId", 0).coerceIn(0, TEXTURE_NAMES.size - 1), textureAmt = f("textureAmt", 0.7f), aa = f("aa", 0.5f),
        velSize = f("velSize", 0f).coerceIn(0f, 1f), scatter = f("scatter", 0f).coerceIn(0f, 1f), sizeJitter = f("sizeJitter", 0f).coerceIn(0f, 1f),
        angleJitter = f("angleJitter", 0f).coerceIn(0f, 1f), opacityJitter = f("opacityJitter", 0f).coerceIn(0f, 1f),
        wet = f("wet", 0f).coerceIn(0f, 1f), wetLength = f("wetLength", 0.5f).coerceIn(0f, 1f),
        flow = f("flow", 1f).coerceIn(0.01f, 1f), capped = o.optBoolean("capped", false), wetPressure = f("wetPressure", 0f).coerceIn(-1f, 1f),
        tipImg = o.optInt("tipImg", 0), texImg = o.optInt("texImg", 0), texScale = f("texScale", 1f).coerceIn(0.25f, 4f),
        texInvert = o.optBoolean("texInvert", false), texMoving = o.optBoolean("texMoving", false), tipAngle = f("tipAngle", 0f).coerceIn(-180f, 180f),
        tipFollow = o.optBoolean("tipFollow", false), roundness = f("roundness", 1f).coerceIn(0.1f, 1f)
    )
}

/** .inbrush file = JSON: {"format":"inbetween-brush","version":1,"brush":{...}} */
fun brushToFile(b: Brush): String {
    val o = JSONObject().put("format", "inbetween-brush").put("version", 1).put("brush", b.toJson())
    // imported stamps and textures travel with the brush
    val assets = JSONArray()
    fun add(id: Int, kind: Int) {
        val png = if (id != 0) BrushAssets.png(id) else null
        if (png != null) assets.put(JSONObject().put("id", id).put("kind", kind).put("png", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)))
    }
    add(b.tipImg, BrushAssets.TIP); add(b.texImg, BrushAssets.TEXTURE)
    if (assets.length() > 0) o.put("assets", assets)
    return o.toString()
}
