package com.example.inbetween

import android.graphics.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/** A finished stroke. p = repeated (x, y, widthScale, alphaScale) in document space. */
/** kind 0 = stroke (runs = the selection it was drawn inside, if any), 1 = fill, 2 = region transform, 3 = external reference image path + matrix m, 4 = region warp (runs + m = [cols, rows, mesh vertices...]). */
class Ink(val p: FloatArray, val color: Int, val b: Brush, val kind: Int = 0, val runs: IntArray? = null, val m: FloatArray? = null, val data: ByteArray? = null,
          /** Seed of the dab jitter (scatter, size, rotation, opacity): stored with the stroke so it is drawn the same every time it is rebuilt. */
          val seed: Int = 0)

/** A fresh jitter seed for a new stroke of brush [b] (0 when the brush has no dab jitter). */
fun newInkSeed(b: Brush): Int = if (b.scatter > 0f || b.sizeJitter > 0f || b.angleJitter > 0f || b.opacityJitter > 0f) java.util.Random().nextInt() else 0

private fun lerpRgb(a: Int, b: Int, t: Float): Int {
    val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
    val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
    return ((ar + (br - ar) * t).roundToInt().coerceIn(0, 255) shl 16) or ((ag + (bg - ag) * t).roundToInt().coerceIn(0, 255) shl 8) or (ab + (bb - ab) * t).roundToInt().coerceIn(0, 255)
}

/** Dab speed (document px per second) that counts as "fastest" for the velocity taper. */
private const val VEL_MAX = 2500f

private fun smooth(t: Float) = t * t * (3f - 2f * t)
private fun hash(x: Float, y: Float): Float { val h = sin(x * 73.1f + y * 19.7f) * 43758.547f; return h - floor(h) }

// ---------- dab tips ----------
/** Tip shapes: 0 circle, 1 triangle, 2 square, 3 etched (rough, speckled edge). */
val TIP_NAMES = listOf("Circle", "Triangle", "Square", "Etched")
private object Tips {
    private val m = HashMap<Int, Bitmap>()
    /** Bitmap tips: the soft circle (shape 0) and the etched tip (shape 3). Triangle and square are drawn as vectors per dab (see PathStamper). */
    @Synchronized fun get(shape: Int, h: Float): Bitmap = m.getOrPut(shape * 100000 + (h * 50).roundToInt()) { build(shape, h) }
    private fun build(shape: Int, h: Float): Bitmap {
        val n = 128
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x + 0.5f - n / 2f; val dy = y + 0.5f - n / 2f
            val r = hypot(dx, dy) / (n / 2f)
            val a = if (shape == 3) {
                // etched: the rim wanders with the angle, and the band along it is eaten away speckle by speckle
                val ang = atan2(dy, dx)
                val edge = 0.80f + 0.12f * sin(ang * 7f) + 0.06f * sin(ang * 17f + 1.3f)
                val band = 0.16f
                if (r >= edge + 0.04f) 0f else if (r <= edge - band) 1f else if (hash(x.toFloat(), y.toFloat()) > (r - (edge - band)) / (band + 0.04f)) 1f else 0f
            } else if (r >= 1f) 0f else if (r <= h) 1f else 1f - smooth((r - h) / (1f - h))
            px[y * n + x] = ((a * 255).toInt() shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
}

// ---------- brush textures: seamless 128 px tiles anchored to the document (paper tooth stays put under the pen) ----------
val TEXTURE_NAMES = listOf("None", "Paper", "Canvas", "Stipple", "Hatch")
object Textures {
    private const val N = 128
    private fun lattice(i: Int, j: Int, period: Int): Float = hash((Math.floorMod(i, period)).toFloat() * 1.7f + 3f, (Math.floorMod(j, period)).toFloat() * 2.3f + 5f)
    private fun noise(x: Float, y: Float, cell: Int): Float {
        val period = N / cell
        val gx = x / cell; val gy = y / cell
        val i = floor(gx).toInt(); val j = floor(gy).toInt()
        val fx = smooth(gx - i); val fy = smooth(gy - j)
        val a = lattice(i, j, period); val b = lattice(i + 1, j, period); val c = lattice(i, j + 1, period); val d = lattice(i + 1, j + 1, period)
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
    }
    /** 1 = full ink, lower = less ink at that pixel. */
    private fun ink(id: Int, x: Int, y: Int): Float = when (id) {
        1 -> { val v = noise(x.toFloat(), y.toFloat(), 4) * 0.65f + noise(x.toFloat(), y.toFloat(), 2) * 0.35f; (0.12f + 0.88f * smooth(((v - 0.25f) / 0.4f).coerceIn(0f, 1f))) }
        2 -> { val u = abs(sin(PI.toFloat() * x / 4f)); val w = abs(sin(PI.toFloat() * y / 4f)); 0.35f + 0.65f * (if (((x / 4) + (y / 4)) % 2 == 0) u else w) }
        3 -> if (hash(x.toFloat() + 0.5f, y.toFloat() + 0.5f) > 0.45f) 1f else 0.08f
        4 -> if ((x + y) % 8 < 4) 1f else 0.12f
        else -> 1f
    }
    private val tiles = HashMap<Int, Bitmap>()
    /** Seamless ALPHA_8 tile for texture [id] at strength [amt] (0..1), or null for "None". Alpha 255 = full ink. */
    @Synchronized fun tile(id: Int, amt: Float, invert: Boolean = false): Bitmap? {
        if (id <= 0 || id >= TEXTURE_NAMES.size) return null
        val q = (amt.coerceIn(0f, 1f) * 20f).roundToInt()
        return tiles.getOrPut(id * 100 + q + (if (invert) 1_000_000 else 0)) {
            val strength = q / 20f
            val bytes = ByteArray(N * N)
            for (y in 0 until N) for (x in 0 until N) { val v = ink(id, x, y); bytes[y * N + x] = ((1f - strength * (1f - (if (invert) 1f - v else v))).coerceIn(0f, 1f) * 255f).toInt().toByte() }
            val bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            bmp
        }
    }
}

/** Where brush dabs end up: one bitmap canvas, or the tiles of an unbounded cel (see Tiles.kt). */
interface DabSink {
    fun circle(x: Float, y: Float, r: Float, paint: Paint)
    fun tip(bmp: Bitmap, rect: RectF, paint: Paint)
    /** The colour already under (x, y): alpha-weighted average ARGB (not premultiplied) of a few samples within [r], or 0 when there is nothing. For wet mixing; sinks that cannot read back return 0. */
    fun sample(x: Float, y: Float, r: Float): Int = 0
}

class CanvasSink(private val cv: Canvas) : DabSink {
    override fun circle(x: Float, y: Float, r: Float, paint: Paint) { cv.drawCircle(x, y, r, paint) }
    override fun tip(bmp: Bitmap, rect: RectF, paint: Paint) { cv.drawBitmap(bmp, null, rect, paint) }
}

/** Stamps dabs along a Catmull-Rom curve through the points it is fed. */
class PathStamper(private val sink: DabSink, private val b: Brush, private val argb: Int, seed: Int = 0) {
    constructor(cv: Canvas, b: Brush, argb: Int, seed: Int = 0) : this(CanvasSink(cv), b, argb, seed)
    private val q = FloatArray(16)
    private var cnt = 0; private var begun = false; private var carry = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isAntiAlias = b.aa > 0.02f
        val c = if (b.erase) Color.BLACK else argb
        color = c; colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
        if (b.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    // Paint per dab. Classic brushes: opacity x flow builds up where dabs overlap. Capped brushes: only flow does, and opacity is applied
    // once to the whole stroke (the stroke is stamped into its own tile set and laid on the cel at b.opacity: see Session / TileSet.stamp).
    private val flow = 1f - (1f - (if (b.capped && !b.erase) 1f else b.opacity.coerceIn(0f, 1f)) * b.flow.coerceIn(0f, 1f)).pow(b.spacing.coerceIn(0.02f, 1f))
    private val imgTip: Bitmap? = if (b.tipImg != 0) BrushAssets.mask(b.tipImg) else null
    // Anti-aliasing slider: 0 = hard, aliased edges. Above that the edges are anti-aliased, and the extra feather grows slowly at first
    // (cubic: 0.5 -> 0.4 px, 0.75 -> 1.3 px, 1 -> 3 px), so the low end of the slider is easy to control.
    private val aaBlur = if (b.aa > 0.02f) 3f * b.aa * b.aa * b.aa else 0f
    private val tileShader = (if (b.texImg != 0) BrushAssets.textureTile(b.texImg, b.textureAmt, b.texInvert) else Textures.tile(b.textureId, b.textureAmt, b.texInvert))
        ?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val shapePaint = Paint().apply { color = Color.WHITE; isAntiAlias = b.aa > 0.02f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); shader = tileShader }
    private val blurs = HashMap<Int, BlurMaskFilter>()
    private val tmpMatrix = Matrix()
    private val tri = Path()
    // dab jitter: every dab draws the same number of random values from this seeded generator
    private val jit = b.scatter > 0f || b.sizeJitter > 0f || b.angleJitter > 0f || b.opacityJitter > 0f
    private val rng = java.util.Random(seed.toLong() * 2654435761L + 12345L)
    // wet mix: the colour picked up from under the brush, and how far the brush has moved since it last touched paint
    private val wet = b.wet > 0.01f && !b.erase
    private val wetLen = 30f + 800f * b.wetLength.coerceIn(0f, 1f)
    private var carryOn = false; private var carryRgb = 0; private var wetDist = 0f; private var wetLast = 0
    private var lastDabX = Float.NaN; private var lastDabY = 0f
    // stroke direction (smoothed unit vector) for tips that follow the stroke, and the stroke start for textures that move with it
    private var dirX = 1f; private var dirY = 0f; private var startX = 0f; private var startY = 0f

    private fun wetColor(x: Float, y: Float, r: Float, press: Float) {
        if (!lastDabX.isNaN()) wetDist += hypot(x - lastDabX, y - lastDabY)
        lastDabX = x; lastDabY = y
        val under = sink.sample(x, y, r)
        val ua = under ushr 24
        if (ua > 12) { // over paint: the carried colour moves toward it, faster where the paint is denser
            carryRgb = if (carryOn) lerpRgb(carryRgb, under and 0xFFFFFF, (0.35f * ua / 255f).coerceAtMost(0.6f)) else under and 0xFFFFFF
            carryOn = true; wetDist = 0f
        }
        // pressure -> wetness: positive = a heavy press mixes more (uses the pressure -> flow curve); negative = a light touch mixes more
        val wp = b.wetPressure
        val pf = if (wp >= 0f) 1f - wp * (1f - press) else 1f + wp * press
        val sigma = if (carryOn) (b.wet * pf * exp(-wetDist / wetLen)).coerceIn(0f, 1f) else 0f // fades back to the ink colour with distance
        val c = (argb and 0xFF000000.toInt()) or (lerpRgb(argb and 0xFFFFFF, carryRgb, sigma) and 0xFFFFFF)
        if (c != wetLast) { wetLast = c; paint.color = c; paint.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN) }
    }

    private fun set(i: Int, x: Float, y: Float, w: Float, a: Float) { q[i * 4] = x; q[i * 4 + 1] = y; q[i * 4 + 2] = w; q[i * 4 + 3] = a }

    fun add(x: Float, y: Float, w: Float, a: Float) {
        if (cnt == 0) { for (i in 0 until 4) set(i, x, y, w, a); cnt = 1; startX = x; startY = y; return }
        System.arraycopy(q, 4, q, 0, 12); set(3, x, y, w, a); cnt++
        segment()
    }

    fun finish() {
        if (cnt == 1) dab(q[12], q[13], q[14], q[15]) else if (cnt > 1) add(q[12], q[13], q[14], q[15])
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float) =
        0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)

    private fun segment() {
        val len = hypot(q[8] - q[4], q[9] - q[5])
        if (!begun) {
            if (len > 0.01f) { dirX = (q[8] - q[4]) / len; dirY = (q[9] - q[5]) / len } // the first dab already faces the way the stroke starts
            dab(q[4], q[5], q[6], q[7]); begun = true
        }
        if (len < 0.01f) return
        val m = max(1, ceil(len / 1.5f).toInt())
        var lx = q[4]; var ly = q[5]
        for (k in 1..m) {
            val t = k / m.toFloat()
            val x = cr(q[0], q[4], q[8], q[12], t); val y = cr(q[1], q[5], q[9], q[13], t)
            val w = q[6] + (q[10] - q[6]) * t; val a = q[7] + (q[11] - q[7]) * t
            val sx = x - lx; val sy = y - ly; val sl = hypot(sx, sy)
            if (sl > 1e-4f) {
                dirX += (sx / sl - dirX) * 0.3f; dirY += (sy / sl - dirY) * 0.3f
                val dl = hypot(dirX, dirY)
                if (dl > 1e-4f) { dirX /= dl; dirY /= dl }
            }
            carry += sl; lx = x; ly = y
            val step = max(0.6f, b.spacing * b.size * w)
            while (carry >= step) { carry -= step; dab(x, y, w, a) }
        }
    }

    private fun dab(x0: Float, y0: Float, w: Float, a: Float) {
        // Dab jitter: the same number of random draws for every dab, before anything can return early, so rebuilding the
        // stroke (same seed, same points) places the same dabs.
        var jx = 0f; var jy = 0f; var js = 0f; var ja = 0f; var jo = 0f
        if (jit) { jx = rng.nextFloat() * 2f - 1f; jy = rng.nextFloat() * 2f - 1f; js = rng.nextFloat() * 2f - 1f; ja = rng.nextFloat() * 2f - 1f; jo = rng.nextFloat() }
        val r = b.size * w / 2f * (1f + b.sizeJitter * js).coerceAtLeast(0.2f)
        if (r < 0.25f) return
        val press = a.coerceIn(0f, 1f)
        var al = flow * press * (1f - b.opacityJitter * jo)
        var x = x0 + jx * r * b.scatter; var y = y0 + jy * r * b.scatter
        val squash = b.roundness.coerceIn(0.1f, 1f)
        val shaped = b.tip != 0 || imgTip != null || squash < 0.99f // a full round tip looks the same at any angle
        val angle = if (shaped) b.tipAngle + (if (b.tipFollow) atan2(dirY, dirX) * 57.29578f else 0f) + b.angleJitter * ja * 180f else 0f
        val rotated = shaped && (abs(angle) > 0.01f || squash < 0.99f)
        if (b.texture > 0f) {
            al *= 1f - b.texture * hash(x0, y0)
            x += (hash(y0, x0) - 0.5f) * r * b.texture * 0.6f
            y += (hash(x0 + 7f, y0 + 3f) - 0.5f) * r * b.texture * 0.6f
        }
        if (wet) wetColor(x, y, r, press)
        paint.alpha = (al * 255).toInt().coerceIn(1, 255)
        val soft = b.hardness < 0.99f
        val direct = tileShader == null && !rotated && (imgTip != null || b.tip == 3 || (b.tip == 0 && (soft || aaBlur < 0.3f)))
        if (direct) {
            if (imgTip != null) sink.tip(imgTip, RectF(x - r, y - r, x + r, y + r), paint)
            else if (b.tip == 0 && !soft) sink.circle(x, y, r, paint)
            else sink.tip(Tips.get(b.tip, b.hardness), RectF(x - r, y - r, x + r, y + r), paint)
            return
        }
        // Shaped / textured / feathered / rotated dab: drawn at its real size into a small scratch bitmap (vector edges, so no scaled-bitmap aliasing),
        // multiplied by the texture, then stamped 1:1.
        val blur = (if (imgTip == null && (b.tip == 1 || b.tip == 2)) (1f - b.hardness) * r * 0.5f else 0f) + aaBlur
        val margin = ceil(blur * 2f).toInt() + 2
        val ext = if (rotated) r * 1.45f else r // a rotated square reaches further than its radius (a squash only makes it smaller)
        val ox = floor(x - ext).toInt() - margin; val oy = floor(y - ext).toInt() - margin
        val n = ((ceil(2f * ext).toInt() + 2 * margin + 2 + 7) / 8) * 8
        val sc = ScratchCache.get(n)
        sc.first.eraseColor(0)
        val cv = sc.second
        val cx = x - ox; val cy = y - oy
        shapePaint.maskFilter = if (blur > 0.3f) blurs.getOrPut((blur * 4f).roundToInt()) { BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) } else null
        if (rotated) { cv.save(); cv.rotate(angle, cx, cy); cv.scale(1f, squash, cx, cy) }
        when {
            imgTip != null -> cv.drawBitmap(imgTip, null, RectF(cx - r, cy - r, cx + r, cy + r), bitmapPaint)
            b.tip == 1 -> { tri.rewind(); tri.moveTo(cx, cy - r); tri.lineTo(cx + r, cy + r * 0.85f); tri.lineTo(cx - r, cy + r * 0.85f); tri.close(); cv.drawPath(tri, shapePaint) }
            b.tip == 2 -> cv.drawRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, shapePaint)
            b.tip == 3 -> cv.drawBitmap(Tips.get(3, b.hardness), null, RectF(cx - r, cy - r, cx + r, cy + r), bitmapPaint)
            else -> if (soft) cv.drawBitmap(Tips.get(0, b.hardness), null, RectF(cx - r, cy - r, cx + r, cy + r), bitmapPaint) else cv.drawCircle(cx, cy, r, shapePaint)
        }
        if (rotated) cv.restore()
        tileShader?.let {
            val ts = b.texScale.coerceIn(0.25f, 4f)
            tmpMatrix.setScale(ts, ts)
            if (b.texMoving) tmpMatrix.postTranslate(startX - ox, startY - oy) else tmpMatrix.postTranslate(-ox.toFloat(), -oy.toFloat())
            it.setLocalMatrix(tmpMatrix)
            cv.drawRect(0f, 0f, n.toFloat(), n.toFloat(), multPaint)
        }
        sink.tip(sc.first, RectF(ox.toFloat(), oy.toFloat(), (ox + n).toFloat(), (oy + n).toFloat()), paint)
    }
}

/** Per-thread scratch bitmaps for shaped / textured dabs, keyed by their (multiple-of-8) size. */
private object ScratchCache {
    private val local = ThreadLocal<HashMap<Int, Pair<Bitmap, Canvas>>>()
    fun get(n: Int): Pair<Bitmap, Canvas> {
        val m = local.get() ?: HashMap<Int, Pair<Bitmap, Canvas>>().also { local.set(it) }
        if (m.size > 48) m.clear()
        return m.getOrPut(n) { Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).let { it to Canvas(it) } }
    }
}

fun stampInk(cv: Canvas, bmp: Bitmap, k: Ink) {
    when (k.kind) {
        1 -> {
            val p = Paint().apply { color = k.color }; val r = k.runs ?: return
            var i = 0
            while (i < r.size) { cv.drawRect(r[i + 1].toFloat(), r[i].toFloat(), r[i + 2].toFloat(), r[i] + 1f, p); i += 3 }
        }
        2 -> lift(bmp, k.runs ?: return)?.let { drawLift(cv, it, k.m ?: return) }
        3 -> {
            val path = k.data?.toString(Charsets.UTF_8) ?: return
            val img = ReferenceBitmapCache.get(path) ?: return
            val mt = Matrix(); mt.setValues(k.m ?: return)
            cv.drawBitmap(img, mt, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        5 -> k.toVectorStroke()?.let { VectorTessellator.draw(cv, it, Paint(Paint.ANTI_ALIAS_FLAG)) }
        else -> {
            val s = PathStamper(cv, k.b, k.color, k.seed)
            var i = 0
            while (i < k.p.size) { s.add(k.p[i], k.p[i + 1], k.p[i + 2], k.p[i + 3]); i += 4 }
            s.finish()
        }
    }
}

// ---------- external reference-image bitmap cache (LRU) ----------
object ReferenceBitmapCache {
    @Volatile var resolver: android.content.ContentResolver? = null
    private val map = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private val dimensionsMap = HashMap<String, Pair<Int, Int>>()
    private var bytes = 0L
    private const val BUDGET = 96L shl 20

    private fun openLocation(location: String): java.io.InputStream? = try {
        val uri = android.net.Uri.parse(location)
        when (uri.scheme) {
            "content", "file" -> resolver?.openInputStream(uri)
            else -> java.io.FileInputStream(java.io.File(location))
        }
    } catch (_: Exception) { null }

    fun exists(location: String): Boolean = runCatching { openLocation(location)?.use { true } ?: false }.getOrDefault(false)

    private fun sampleFor(w: Int, h: Int): Int {
        var sample = 1
        while (w / sample > 4096 || h / sample > 4096 || (w.toLong() / sample) * (h.toLong() / sample) > 12_000_000L) sample *= 2
        return sample
    }

    @Synchronized fun dimensions(location: String): Pair<Int, Int>? {
        dimensionsMap[location]?.let { return it }
        map[location]?.let { return (it.width to it.height).also { size -> dimensionsMap[location] = size } }
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = openLocation(location) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, opt) }
        if (opt.outWidth <= 0 || opt.outHeight <= 0) return null
        val sample = sampleFor(opt.outWidth, opt.outHeight)
        return ((opt.outWidth / sample).coerceAtLeast(1) to (opt.outHeight / sample).coerceAtLeast(1)).also { dimensionsMap[location] = it }
    }

    @Synchronized fun peek(location: String): Bitmap? = map[location]
    @Synchronized fun get(location: String): Bitmap? {
        map[location]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = openLocation(location) ?: return null
        first.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleFor(bounds.outWidth, bounds.outHeight)
        val second = openLocation(location) ?: return null
        val bmp = second.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        map[location] = bmp; dimensionsMap[location] = bmp.width to bmp.height; bytes += bmp.byteCount.toLong()
        val it = map.entries.iterator()
        while (bytes > BUDGET && map.size > 1 && it.hasNext()) {
            val e = it.next(); if (e.value === bmp) continue
            bytes -= e.value.byteCount.toLong(); it.remove()
        }
        return bmp
    }

    @Synchronized fun removeMissing() {
        val missing = dimensionsMap.keys.filterNot { exists(it) }
        for (key in missing) {
            map.remove(key)?.let { bytes -= it.byteCount.toLong() }
            dimensionsMap.remove(key)
        }
    }
}

// ---------- per-cel Cut-sized bitmap cache (LRU), derived from the tiles ----------
/** Cut-cropped view of a cel for export, thumbnails, flatten and gizmo bounds. The tiles (Tiles.kt) are the source of truth,
 *  so an export always matches what the canvas shows inside the Cut. Entries redraw only when the tiles changed. */
object CelCache {
    class Entry(val bmp: Bitmap) { @Volatile var version = -1L }
    private class Id(val l: Any) {
        override fun equals(other: Any?) = other is Id && other.l === l
        override fun hashCode() = System.identityHashCode(l)
    }
    private val map = LinkedHashMap<Id, Entry>(32, 0.75f, true)
    private var bytes = 0L
    /** Memory budget; the tile cache (what the canvas and playback draw from) follows it with a little headroom. */
    @Volatile var budget = 192L shl 20
        set(v) { field = v; TileCache.budget = maxOf(v + (32L shl 20), TileCache.floorBytes) }

    fun get(list: SnapshotStateList<Ink>, w: Int, h: Int): Entry {
        val ts = TileCache.get(list)
        val e = synchronized(this) {
            val key = Id(list)
            var x = map[key]
            if (x != null && (x.bmp.width != w || x.bmp.height != h)) { map.remove(key); bytes -= x.bmp.byteCount; x = null }
            if (x == null) {
                val n = Entry(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)); map[key] = n; bytes += n.bmp.byteCount
                val iter = map.entries.iterator()
                while (bytes > budget && iter.hasNext()) {
                    val en = iter.next()
                    if (en.value === n) continue
                    bytes -= en.value.bmp.byteCount; iter.remove()
                }
                n
            } else x
        }
        synchronized(e) {
            val v = ts.version
            if (e.version != v) { e.bmp.eraseColor(0); ts.drawCrop(Canvas(e.bmp), w, h); e.version = v }
        }
        return e
    }

    /** Prepare the current frame and upcoming frames off the UI thread (builds the tile sets the canvas draws from). */
    fun prefetch(cut: Cut, from: Int, ahead: Int) {
        val total = cut.total().coerceAtLeast(1)
        fun estimatedBytes(t: Int): Long {
            val seen = java.util.IdentityHashMap<Any, Boolean>()
            var sum = 0L
            for (layer in cut.layers) {
                if (!layer.visible || layer.kind == 2 || layer.kind == 3 || layer.kind == 5 || layer.isGroup) continue
                val cel = layer.drawCel(t) ?: continue
                if (cel.strokes.isNotEmpty() && seen.put(cel.strokes, true) == null) sum += TileCache.peek(cel.strokes)?.bytes ?: (cut.w.toLong() * cut.h.toLong() * 4L)
            }
            return sum
        }
        val cap = TileCache.budget
        // If one frame cannot fit as a whole, don't churn the LRU cache; the canvas renders its layers in order.
        if (estimatedBytes(from) > cap) return
        // Prepare only a short rolling window; current frame is processed last and remains hottest in the cache.
        for (k in ahead.coerceIn(0, 2) downTo 0) {
            if (estimatedBytes((from + k) % total) > cap) continue
            val t = (from + k) % total
            for (l in cut.layers) {
                if (!l.visible || l.kind == 2 || l.kind == 3 || l.isGroup) continue
                val c = l.drawCel(t) ?: continue
                if (c.strokes.isNotEmpty() && l.kind != 5) TileCache.get(c.strokes)
            }
        }
    }
    /** True when all visible raster cels for this tick are ready. Oversized frames are rendered on demand, still in order. */
    fun frameReady(cut: Cut, t: Int): Boolean {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        var needed = 0L
        for (layer in cut.layers) {
            if (!layer.visible || layer.kind == 2 || layer.kind == 3 || layer.kind == 5 || layer.isGroup) continue
            val cel = layer.drawCel(t) ?: continue
            if (cel.strokes.isNotEmpty() && seen.put(cel.strokes, true) == null) needed += TileCache.peek(cel.strokes)?.bytes ?: (cut.w.toLong() * cut.h.toLong() * 4L)
        }
        if (needed > TileCache.budget) return true // Can't retain the whole frame: canvas will draw each layer sequentially.
        return cut.layers.all { l ->
            if (!l.visible || l.kind == 2 || l.kind == 3 || l.isGroup || l.kind == 5) true
            else l.drawCel(t)?.let { c -> c.strokes.isEmpty() || TileCache.peek(c.strokes)?.let { it.count >= c.strokes.size } == true } ?: true
        }
    }
}

// ---------- stabilizers ----------
/** method: 0 none, 1 lazy follow, 2 One Euro (time-based), 3 moving average, 4 spline (distance-resampled), 5 spring (mass-spring-damper string). */
class Stabilizer(method: Int, strength: Int) {
    private val m = if (strength == 0) 0 else method
    private val s = strength / 100f
    var ox = 0f; var oy = 0f
    private var init = false; private var lt = 0L; private var prx = 0f; private var pry = 0f
    private var vx = 0f; private var vy = 0f
    private val e = s.pow(0.6f) // steep start: even low strengths already smooth clearly
    private val k = ((1 - e) * (1 - e) * (1 - e)).coerceAtLeast(0.003f)
    private val minCut = 0.02f + 25f * (1 - e) * (1 - e) * (1 - e) * (1 - e)
    private val n = 1 + (e * 80).toInt()
    private val bx = ArrayDeque<Float>(); private val by = ArrayDeque<Float>()
    private val d = 1.5f + e * 120f
    // method 5: the nib follows the pen through a spring-damper; the pen can move freely inside the string length
    private val slack = 0.5f + 30f * e * e
    private val omega = 6f + 44f * (1f - e) * (1f - e)
    private var nvx = 0f; private var nvy = 0f

    private fun spring(tx: Float, ty: Float, dt: Float) {
        val steps = ceil(dt / 0.004f).toInt().coerceAtLeast(1) // small fixed steps: stable, and independent of the touch sampling rate
        val h = dt / steps
        val w2 = omega * omega; val cd = 2f * 0.9f * omega
        repeat(steps) {
            nvx += (w2 * (tx - ox) - cd * nvx) * h; nvy += (w2 * (ty - oy) - cd * nvy) * h
            ox += nvx * h; oy += nvy * h
        }
    }

    /** One 16 ms step toward the pen's last position (no string slack) when the pen has lifted. True while the nib is still moving. */
    fun settleStep(tx: Float, ty: Float): Boolean {
        if (m != 5) return false
        spring(tx, ty, 0.016f)
        return hypot(tx - ox, ty - oy) > 0.4f || hypot(nvx, nvy) > 4f
    }

    private fun alpha(fc: Float, dt: Float): Float { val a = 2f * PI.toFloat() * fc * dt; return a / (a + 1f) }

    /** Feeds a raw sample; returns true when a new filtered point is available in (ox, oy). */
    fun push(x: Float, y: Float, t: Long): Boolean {
        if (!init) { init = true; ox = x; oy = y; lt = t; prx = x; pry = y; bx.addLast(x); by.addLast(y); return true }
        when (m) {
            1 -> { ox += (x - ox) * k; oy += (y - oy) * k }
            2 -> {
                val dt = max(0.001f, (t - lt) / 1000f); lt = t
                val ad = alpha(1f, dt)
                vx += ((x - prx) / dt - vx) * ad; vy += ((y - pry) / dt - vy) * ad
                prx = x; pry = y
                val a = alpha(minCut + 0.02f * hypot(vx, vy), dt)
                ox += (x - ox) * a; oy += (y - oy) * a
            }
            3 -> {
                bx.addLast(x); by.addLast(y)
                if (bx.size > n) { bx.removeFirst(); by.removeFirst() }
                ox = bx.sum() / bx.size; oy = by.sum() / by.size
            }
            4 -> { if (hypot(x - ox, y - oy) < d) return false; ox = x; oy = y }
            5 -> {
                val dt = ((t - lt) / 1000f).coerceIn(0.001f, 0.05f); lt = t
                val dx = x - ox; val dy = y - oy; val dist = hypot(dx, dy)
                val pull = if (dist <= slack) 0f else (dist - slack) / dist // the string only pulls once it is taut
                spring(ox + dx * pull, oy + dy * pull, dt)
            }
            else -> { ox = x; oy = y }
        }
        return true
    }
}

/** Ramer-Douglas-Peucker in (x, y, radius) space so widths survive simplification. */
private fun simplify(p: FloatArray, size: Float, eps: Float = 0.4f): FloatArray {
    val n = p.size / 4
    if (n < 3) return p
    val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
    val st = ArrayList<IntArray>(); st.add(intArrayOf(0, n - 1))
    while (st.isNotEmpty()) {
        val s = st.removeAt(st.size - 1); val a = s[0]; val b = s[1]
        if (b - a < 2) continue
        val ax = p[a * 4]; val ay = p[a * 4 + 1]; val ar = p[a * 4 + 2] * size / 2
        val dx = p[b * 4] - ax; val dy = p[b * 4 + 1] - ay; val dr = p[b * 4 + 2] * size / 2 - ar
        val l2 = dx * dx + dy * dy + dr * dr
        var md = 0f; var mi = -1
        for (i in a + 1 until b) {
            val x = p[i * 4] - ax; val y = p[i * 4 + 1] - ay; val r = p[i * 4 + 2] * size / 2 - ar
            val t = if (l2 > 0f) ((x * dx + y * dy + r * dr) / l2).coerceIn(0f, 1f) else 0f
            val ex = x - t * dx; val ey = y - t * dy; val er = r - t * dr
            val dd = ex * ex + ey * ey + er * er
            if (dd > md) { md = dd; mi = i }
        }
        if (mi >= 0 && md > eps * eps) { keep[mi] = true; st.add(intArrayOf(a, mi)); st.add(intArrayOf(mi, b)) }
    }
    val out = FloatArray(keep.count { it } * 4); var o = 0
    for (i in 0 until n) if (keep[i]) { System.arraycopy(p, i * 4, out, o, 4); o += 4 }
    return out
}

// ---------- one live pen stroke ----------
/** Stabilizes input, computes width/alpha per point (pressure, tilt, tapers) and stamps live into the cel bitmap. */
/** The single thread that stamps live strokes (see Session.stampLater). */
private object StrokeWorker {
    val exec: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "stroke-stamp").also { it.isDaemon = true } }
}

/** [clipRuns]: the selection (y, x0, x1 triples) the stroke is limited to, or null. It is stored in the stroke (Ink.runs) so a replay paints the same pixels. */
class Session(val cel: Cel, val entry: TileSet, val b: Brush, val color: Int, val clipRuns: IntArray? = null, private val magnet: ((Offset, Offset) -> Offset)? = null) {
    val list: SnapshotStateList<Ink> get() = cel.strokes
    val line = b.stab >= 100 // 100 = straight line only; previewed, stamped on pen lift
    private val st = Stabilizer(b.stabMethod, b.stab)
    private val seed = newInkSeed(b) // stored with the stroke: the dab jitter is drawn the same every time the stroke is rebuilt
    /** Dabs go straight into the tiles they touch, anywhere in the document: Bleed needs no separate live bitmap. */
    /** A capped stroke (opacity = ceiling for the whole stroke) is stamped here at full strength; the canvas shows it at b.opacity and `finish` lays it on the cel. */
    val scratch: TileSet? = if (b.capped && !b.erase) TileSet() else null
    private val clipPath: android.graphics.Path? = clipRuns?.takeIf { it.isNotEmpty() }?.let { clipPathOf(it) }
    private val sink = if (scratch != null) TileSink(scratch, false, entry) else TileSink(entry, b.erase, null, clipPath)
    private val stamper = PathStamper(sink, b, color, seed)
    /** The pixels this stroke overwrites, so undoing it does not have to replay the whole cel (committed with the stroke, see Cut.commit). */
    val backup = StrokeBackup()
    init { entry.pinned = true; entry.recorder = backup } // the set must not be evicted or rebuilt while this stroke is live
    /** Always called when the pen goes up or the gesture is cancelled. */
    fun release() { onWorker { sink.endClip() }; entry.pinned = false; entry.recorder = null; scratch?.clear(); if (!backup.owned) backup.release() } // the queue is empty first: no dab may land after the backup is gone
    private var buf = FloatArray(256); private var n = 0
    var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
    private var started = false; private var dist = 0f; private var lx = 0f; private var ly = 0f
    private var ps = 0f; private var psum = 0f; private var raw = 0
    private var rx = 0f; private var ry = 0f; private var rp = 0f; private var rt = 0f
    private var vel = 0f; private var velT = 0L; private var velX = 0f; private var velY = 0f; private var wvS = 1f // pen speed (document px / s), smoothed

    /**
     * Dabs are stamped on one background thread (a big soft brush costs milliseconds per dab, which used to run inside the pen-event
     * handler: input waited up to ~2 s). The pen thread only smooths and queues points; [finish] / [release] wait for the queue.
     * [onStamped] (set by the canvas) asks for a redraw when stamped pixels are ready.
     */
    @Volatile var onStamped: (() -> Unit)? = null
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastPost = 0L // worker thread only

    private fun stampLater(x: Float, y: Float, w: Float, a: Float) {
        queued.incrementAndGet()
        StrokeWorker.exec.execute {
            try { stamper.add(x, y, w, a) } catch (_: Exception) { }
            val left = queued.decrementAndGet()
            val now = android.os.SystemClock.uptimeMillis()
            if (left == 0 || now - lastPost > 33L) { lastPost = now; onStamped?.invoke() }
        }
    }

    /** Runs [block] on the worker after everything queued so far and waits for it. */
    private fun onWorker(block: () -> Unit) {
        try { StrokeWorker.exec.submit(Runnable { block() }).get() } catch (_: Exception) { }
    }

    private fun add(x: Float, y: Float, w: Float, a: Float) {
        if (n + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[n] = x; buf[n + 1] = y; buf[n + 2] = w; buf[n + 3] = a; n += 4
        stampLater(x, y, w, a)
    }

    private fun emit(x: Float, y: Float, p: Float, tilt: Float) {
        ps += (p - ps) * 0.5f
        if (started) dist += hypot(x - lx, y - ly)
        val ts = if (b.taperStart > 0f) 0.1f + 0.9f * smooth(min(1f, dist / (b.size * b.taperStart * 3f + 1f))) else 1f
        val wp = curveEval(b.sizeCurve, ps)
        val wt = 1f + b.tilt * 2f * (tilt / 1.5708f).coerceIn(0f, 1f)
        // velocity taper: R = R_base * ((1 - w) + w * (1 - v_norm)), v_norm on a log scale so a fast flick does not jump (never thinner than 12 %)
        val wv = if (b.velSize > 0f) {
            val vn = (ln(1f + vel) / ln(1f + VEL_MAX)).coerceIn(0f, 1f)
            wvS += (((1f - b.velSize) + b.velSize * (1f - vn)).coerceAtLeast(0.12f) - wvS) * 0.35f
            wvS
        } else 1f
        add(x, y, (wp * wt * ts * wv).coerceAtLeast(0.03f), curveEval(b.opacityCurve, ps).coerceIn(0f, 1f))
        lx = x; ly = y; started = true
    }

    /** Apply perspective attraction to the stabilized nib position, immediately before its live dab is queued. */
    private fun emitMapped(x: Float, y: Float, p: Float, tilt: Float) {
        val q = magnet?.invoke(Offset(ax, ay), Offset(x, y)) ?: Offset(x, y)
        emit(q.x, q.y, p, tilt)
    }

    fun feed(x: Float, y: Float, p: Float, tilt: Float, t: Long) {
        if (raw == 0) { ax = x; ay = y; ps = p; velX = x; velY = y; velT = t }
        else if (t > velT) { vel += (hypot(x - velX, y - velY) / (t - velT) * 1000f - vel) * 0.25f; velX = x; velY = y; velT = t }
        raw++; rx = x; ry = y; rp = p; rt = tilt; psum += p; bx = x; by = y
        if (!line && st.push(x, y, t)) emitMapped(st.ox, st.oy, p, tilt)
    }

    private fun tail() {
        val i = n - 4
        val x1 = buf[i]; val y1 = buf[i + 1]; val w1 = buf[i + 2]; val a1 = buf[i + 3]
        var dx = x1 - buf[i - 4]; var dy = y1 - buf[i - 3]
        val d = hypot(dx, dy); if (d < 0.01f) return
        dx /= d; dy /= d
        val len = b.size * w1 * b.taperEnd * 4f
        for (k in 1..8) { val t = k / 8f; val q = magnet?.invoke(Offset(ax, ay), Offset(x1 + dx * len * t, y1 + dy * len * t)) ?: Offset(x1 + dx * len * t, y1 + dy * len * t); add(q.x, q.y, max(0.03f, w1 * (1f - smooth(t))), a1) }
    }

    fun finish(): Ink? {
        if (raw == 0) return null
        if (line) {
            ps = psum / raw
            val steps = max(1, (hypot(bx - ax, by - ay) / 6f).toInt())
            for (i in 0..steps) emitMapped(ax + (bx - ax) * i / steps, ay + (by - ay) * i / steps, ps, rt)
        } else {
            if (b.stabMethod == 5 && b.stab > 0) { var g = 0; while (g++ < 120 && st.settleStep(rx, ry)) emitMapped(st.ox, st.oy, rp, rt) } // the spring finishes its swing to the pen
            if (b.stabMethod != 0 && b.stab > 0 && (abs(rx - lx) > 0.5f || abs(ry - ly) > 0.5f)) emitMapped(rx, ry, rp, rt) // catch up to the pen
        }
        if (b.taperEnd > 0f && n >= 8) tail()
        onWorker { stamper.finish(); sink.endClip(); scratch?.let { entry.compositeFrom(it, b.opacity, clipPath) } }
        return Ink(simplify(buf.copyOf(n), b.size), color, b, 0, clipRuns?.takeIf { it.isNotEmpty() }, null, null, seed)
    }
}
