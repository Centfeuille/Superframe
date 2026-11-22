package com.example.inbetween

import android.graphics.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/*
 * Sparse tiled raster cache: the single source of truth for what a cel looks like on screen.
 * Ink stays in unbounded document coordinates; every cel gets a TileSet of 512x512 bitmaps that exist only where ink exists.
 * - Live strokes stamp straight into the tiles they touch (a few MB uploaded per frame instead of one full-page bitmap).
 * - The canvas draws only the tiles in view; Bleed on/off only changes whether the Cut rectangle clips them.
 * - CelCache (Cut-sized bitmaps used by export, thumbnails, flatten, gizmo bounds) is derived from the tiles, so exports can
 *   never disagree with what the canvas shows inside the Cut.
 */

class Tile(val tx: Int, val ty: Int) {
    val bmp: Bitmap = Bitmap.createBitmap(TileCache.T, TileCache.T, Bitmap.Config.ARGB_8888)
    /** Draws in document coordinates: the tile origin is pre-translated. */
    val cv: Canvas = Canvas(bmp).apply { translate(-tx.toFloat() * TileCache.T, -ty.toFloat() * TileCache.T) }
}

/** Receives brush dabs from a PathStamper and forwards each one to every tile it overlaps. */
/** [under]: the set the wet mix reads its colour from when the dabs go somewhere else (a capped stroke is stamped into its own set). */
class TileSink(private val set: TileSet, private val erase: Boolean, private val under: TileSet? = null, private val clip: Path? = null) : DabSink {
    /** Tiles whose canvas currently carries the selection clip of this stroke (draw / paint inside the selection only). */
    private val clipped = ArrayList<Tile>(8)
    private fun clipTile(t: Tile) {
        if (clip == null) return
        for (c in clipped) if (c === t) return
        t.cv.save(); t.cv.clipPath(clip); clipped.add(t)
    }
    /** Removes the clip from every tile canvas it was put on. Call once the stroke is complete (or abandoned). */
    fun endClip() {
        if (clipped.isEmpty()) return
        synchronized(set) { for (t in clipped) t.cv.restore(); clipped.clear() }
    }
    override fun sample(x: Float, y: Float, r: Float): Int {
        val t = TileCache.T
        val src = under ?: set
        var wa = 0f; var wr = 0f; var wg = 0f; var wb = 0f
        synchronized(src) {
            val d = r * 0.5f
            for (k in 0 until 5) {
                val ix = floor(x + (if (k == 1) d else if (k == 2) -d else 0f)).toInt()
                val iy = floor(y + (if (k == 3) d else if (k == 4) -d else 0f)).toInt()
                val tile = src.tile(Math.floorDiv(ix, t), Math.floorDiv(iy, t), false) ?: continue
                val c = tile.bmp.getPixel(Math.floorMod(ix, t), Math.floorMod(iy, t))
                val a = (c ushr 24).toFloat()
                wa += a; wr += ((c shr 16) and 255) * a; wg += ((c shr 8) and 255) * a; wb += (c and 255) * a
            }
        }
        if (wa <= 0f) return 0
        return ((wa / 5f).toInt().coerceIn(0, 255) shl 24) or ((wr / wa).toInt().coerceIn(0, 255) shl 16) or ((wg / wa).toInt().coerceIn(0, 255) shl 8) or (wb / wa).toInt().coerceIn(0, 255)
    }
    override fun circle(x: Float, y: Float, r: Float, paint: Paint) {
        synchronized(set) {
            val e = r + 1.5f
            set.eachTileF(x - e, y - e, x + e, y + e, !erase) { t -> set.noteWrite(t); clipTile(t); t.cv.drawCircle(x, y, r, paint) }
            set.touch()
        }
    }
    override fun tip(bmp: Bitmap, rect: RectF, paint: Paint) {
        synchronized(set) {
            set.eachTileF(rect.left - 1.5f, rect.top - 1.5f, rect.right + 1.5f, rect.bottom + 1.5f, !erase) { t -> set.noteWrite(t); clipTile(t); t.cv.drawBitmap(bmp, null, rect, paint) }
            set.touch()
        }
    }
}

class TileSet {
    private val tiles = HashMap<Long, Tile>()
    /** Number of strokes of the cel already applied (same contract as the old CelCache count: Int.MAX_VALUE forces a rebuild). */
    @Volatile var count = 0
    /** Bumped on every pixel change; derived caches compare it. Globally unique, so a rebuilt set never repeats a value. */
    @Volatile var version = TileCache.nextVersion()
    /** A set being drawn into live (stroke or selection transform) must not be evicted or rebuilt. */
    @Volatile var pinned = false
    /** While a stroke is drawn into this set: remembers the previous pixels of every tile the stroke touches (see StrokeBackup). */
    @Volatile var recorder: StrokeBackup? = null
    fun noteWrite(t: Tile) { recorder?.saveBefore(t) }

    fun touch() { version = TileCache.nextVersion() }

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    fun tile(tx: Int, ty: Int, create: Boolean): Tile? {
        val k = key(tx, ty)
        tiles[k]?.let { return it }
        if (!create) return null
        if (abs(tx) > TileCache.MAXI || abs(ty) > TileCache.MAXI) return null // far-away ink is kept in the document but not rasterized
        val t = try { Tile(tx, ty) } catch (_: OutOfMemoryError) { return null }
        tiles[k] = t
        TileCache.total.addAndGet(TileCache.TILE_BYTES)
        recorder?.saveAbsent(tx, ty) // a tile that did not exist before the stroke is dropped again when the stroke is undone
        return t
    }

    inline fun eachTile(x0: Int, y0: Int, x1: Int, y1: Int, create: Boolean, block: (Tile) -> Unit) {
        val size = TileCache.T; val m = TileCache.MAXI
        val tx0 = max(-m, Math.floorDiv(x0, size)); val tx1 = min(m, Math.floorDiv(x1 - 1, size))
        val ty0 = max(-m, Math.floorDiv(y0, size)); val ty1 = min(m, Math.floorDiv(y1 - 1, size))
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val t = tile(tx, ty, create) ?: continue
            block(t)
        }
    }

    inline fun eachTileF(x0: Float, y0: Float, x1: Float, y1: Float, create: Boolean, block: (Tile) -> Unit) {
        val lim = 1e7f
        eachTile(floor(x0.coerceIn(-lim, lim)).toInt(), floor(y0.coerceIn(-lim, lim)).toInt(),
            ceil(x1.coerceIn(-lim, lim)).toInt(), ceil(y1.coerceIn(-lim, lim)).toInt(), create, block)
    }

    /** True while [sync] replays strokes. The set lock is held for the whole replay, so the UI thread must not touch (draw) a building set. */
    @Volatile var building = false

    /** Brings the tiles up to date with the cel's strokes (replays only strokes added since last time). */
    fun sync(list: SnapshotStateList<Ink>) {
        synchronized(this) {
            val n = list.size
            if (count > n) clear()
            if (count < n) {
                building = true
                try {
                    for (i in count until n) stamp(list[i])
                    count = n
                } finally { building = false }
            }
        }
    }

    /**
     * Undo of the newest stroke without replaying the whole cel: puts back the pixels the stroke overwrote (tiles that did not exist
     * before it are dropped). Only valid while this set shows exactly the strokes up to and including that one; otherwise false and
     * the set rebuilds lazily as before.
     */
    fun restoreFrom(b: StrokeBackup, strokesLeft: Int): Boolean = synchronized(this) {
        if (!b.valid || pinned || count != strokesLeft + 1) false
        else {
            val size = TileCache.T
            b.forEachSaved { tx, ty, bmp ->
                if (bmp == null) { if (tiles.remove(key(tx, ty)) != null) TileCache.total.addAndGet(-TileCache.TILE_BYTES) }
                else {
                    val t = tile(tx, ty, true)
                    if (t != null) { t.bmp.eraseColor(0); t.cv.drawBitmap(bmp, (tx * size).toFloat(), (ty * size).toFloat(), null) }
                }
            }
            count = strokesLeft
            touch()
            true
        }
    }

    fun clear() {
        synchronized(this) {
            TileCache.total.addAndGet(-tiles.size.toLong() * TileCache.TILE_BYTES)
            tiles.clear(); count = 0; touch()
        }
    }

    val bytes: Long get() = synchronized(this) { tiles.size.toLong() * TileCache.TILE_BYTES }

    /** Visits every rasterized tile (tile x, tile y, bitmap) under the set lock. Used by the GPU compositor to upload textures. */
    fun forEachTile(block: (Int, Int, Bitmap) -> Unit) {
        synchronized(this) { for (t in tiles.values) block(t.tx, t.ty, t.bmp) }
    }

    /** Tile-granular bounds of everything rasterized so far, or null when empty. */
    fun tileBounds(): Rect? = synchronized(this) {
        if (tiles.isEmpty()) return@synchronized null
        var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
        for (t in tiles.values) { a = min(a, t.tx); b = min(b, t.ty); c = max(c, t.tx); d = max(d, t.ty) }
        val size = TileCache.T
        Rect(a * size, b * size, (c + 1) * size, (d + 1) * size)
    }

    // ---------- stamping ----------

    fun stamp(ink: Ink) {
        synchronized(this) {
            when (ink.kind) {
                1 -> stampRuns(ink)
                2 -> { val runs = ink.runs; val m = ink.m; if (runs != null && m != null) liftRuns(runs)?.let { drawLiftTiles(it, m) } }
                3 -> stampImage(ink)
                5 -> stampVector(ink)
                4 -> { val runs = ink.runs; val mesh = WarpMesh.fromData(ink.m); if (runs != null && mesh != null) liftRuns(runs)?.let { drawMeshTiles(it, mesh) } }
                else -> {
                    // A capped stroke (opacity = ceiling for the whole stroke) is stamped into its own set, then laid on this one at its opacity.
                    // A stroke drawn inside a selection carries the selection runs (Ink.runs) and only paints there.
                    val capped = ink.b.capped && !ink.b.erase
                    val clip = ink.runs?.takeIf { it.isNotEmpty() }?.let { clipPathOf(it) }
                    val target = if (capped) TileSet() else this
                    val sink = if (capped) TileSink(target, false, this) else TileSink(this, ink.b.erase, null, clip)
                    val s = PathStamper(sink, ink.b, ink.color, ink.seed)
                    var i = 0
                    while (i < ink.p.size) { s.add(ink.p[i], ink.p[i + 1], ink.p[i + 2], ink.p[i + 3]); i += 4 }
                    s.finish()
                    sink.endClip()
                    if (capped) { compositeFrom(target, ink.b.opacity, clip); target.clear() }
                }
            }
            touch()
        }
    }

    /** Draws every tile of [src] onto this set at [alpha]: a capped stroke is stamped into its own set at full strength, then laid on the cel once. */
    fun compositeFrom(src: TileSet, alpha: Float, clip: Path? = null) {
        synchronized(this) {
            val a255 = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
            val p = Paint().apply { setAlpha(a255) }
            val size = TileCache.T
            src.forEachTile { tx, ty, bmp ->
                val dst = tile(tx, ty, true)
                if (dst != null) {
                    noteWrite(dst)
                    if (clip != null) { dst.cv.save(); dst.cv.clipPath(clip) }
                    dst.cv.drawBitmap(bmp, (tx * size).toFloat(), (ty * size).toFloat(), p)
                    if (clip != null) dst.cv.restore()
                }
            }
            touch()
        }
    }

    private fun stampRuns(ink: Ink) {
        val r = ink.runs ?: return
        if (r.isEmpty()) return
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var rr = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in r.indices step 3) { t = min(t, r[i]); b = max(b, r[i] + 1); l = min(l, r[i + 1]); rr = max(rr, r[i + 2]) }
        val paint = Paint().apply { color = ink.color }
        val size = TileCache.T
        eachTile(l, t, rr, b, true) { tile ->
            val top = tile.ty * size; val bottom = top + size
            var i = 0
            while (i < r.size) {
                val y = r[i]
                if (y in top until bottom) tile.cv.drawRect(r[i + 1].toFloat(), y.toFloat(), r[i + 2].toFloat(), y + 1f, paint)
                i += 3
            }
        }
    }

    /** A vector stroke (ink kind 5): the ribbon outline is filled into every tile it overlaps. */
    private fun stampVector(ink: Ink) { stampStroke(ink.toVectorStroke() ?: return) }

    /** Rasterizes a vector stroke straight from its model (no encode / decode round trip). */
    fun stampStroke(s: VectorStroke) {
        val b = VectorTessellator.bounds(s)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outline = VectorTessellator.path(s) // tessellated once for all the tiles the stroke overlaps
        eachTileF(b[0], b[1], b[2], b[3], true) { tile -> VectorTessellator.render(tile.cv, s, outline, b, paint) }
    }

    /**
     * Live vector edit (dragging / transforming points): puts back the pixels of [base] (the cel without the strokes being edited) on every tile the
     * [rects] touch, then draws the [moving] strokes there. Everything else stays as it is, so a drag costs the moving strokes only, not the whole cel.
     * Every moving stroke must lie inside [rects]. [base] null = nothing static below.
     */
    fun liveVector(base: TileSet?, moving: List<VectorStroke>, rects: List<FloatArray>) {
        synchronized(this) {
            val size = TileCache.T
            val keys = HashSet<Long>()
            for (r in rects) eachTileF(r[0], r[1], r[2], r[3], false) { t -> keys.add(key(t.tx, t.ty)) }
            for (k in keys) {
                val t = tiles[k] ?: continue
                t.bmp.eraseColor(0)
                val bt = base?.tile(t.tx, t.ty, false)
                if (bt != null) t.cv.drawBitmap(bt.bmp, (t.tx * size).toFloat(), (t.ty * size).toFloat(), null)
            }
            for (s in moving) stampStroke(s)
            touch()
        }
    }

    private fun stampImage(ink: Ink) {
        val data = ink.data ?: return
        // Imported image-sequence frames and flattened layers carry their own PNG / JPEG bytes; an external reference image carries its location.
        val img = (if (isEncodedImage(data)) InkImageCache.get(ink) else ReferenceBitmapCache.get(data.toString(Charsets.UTF_8))) ?: return
        val values = ink.m ?: return
        val mt = Matrix(); mt.setValues(values)
        val rf = RectF(0f, 0f, img.width.toFloat(), img.height.toFloat()); mt.mapRect(rf)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        eachTileF(rf.left, rf.top, rf.right, rf.bottom, true) { tile -> tile.cv.drawBitmap(img, mt, paint) }
    }

    /** Cuts the masked pixels (runs = y, x0, x1 triples in document space) out of the tiles and returns them. */
    fun liftRuns(runs: IntArray): Lift? = synchronized(this) {
        if (runs.isEmpty()) return@synchronized null
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in runs.indices step 3) { t = min(t, runs[i]); b = max(b, runs[i] + 1); l = min(l, runs[i + 1]); r = max(r, runs[i + 2]) }
        if (r <= l || b <= t) return@synchronized null
        val tmp = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        var buf = IntArray(0)
        val size = TileCache.T
        for (i in runs.indices step 3) {
            val y = runs[i]; var x = runs[i + 1]; val xe = runs[i + 2]
            val ty = Math.floorDiv(y, size); val ly = y - ty * size
            while (x < xe) {
                val tx = Math.floorDiv(x, size); val lx = x - tx * size
                val len = min(xe - x, size - lx)
                val tile = tiles[key(tx, ty)]
                if (tile != null) {
                    if (buf.size < len) buf = IntArray(len)
                    tile.bmp.getPixels(buf, 0, len, lx, ly, len, 1)
                    tmp.setPixels(buf, 0, len, x - l, y - t, len, 1)
                    java.util.Arrays.fill(buf, 0, len, 0)
                    tile.bmp.setPixels(buf, 0, len, lx, ly, len, 1)
                }
                x += len
            }
        }
        touch()
        Lift(tmp, l, t)
    }

    /** Like [liftRuns] but leaves every pixel where it is: a copy of the masked pixels, or null when the selection holds nothing drawn. */
    fun copyRuns(runs: IntArray): Lift? = synchronized(this) {
        if (runs.isEmpty()) return@synchronized null
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in runs.indices step 3) { t = min(t, runs[i]); b = max(b, runs[i] + 1); l = min(l, runs[i + 1]); r = max(r, runs[i + 2]) }
        if (r <= l || b <= t) return@synchronized null
        val tmp = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        var buf = IntArray(0)
        var any = false
        val size = TileCache.T
        for (i in runs.indices step 3) {
            val y = runs[i]; var x = runs[i + 1]; val xe = runs[i + 2]
            val ty = Math.floorDiv(y, size); val ly = y - ty * size
            while (x < xe) {
                val tx = Math.floorDiv(x, size); val lx = x - tx * size
                val len = min(xe - x, size - lx)
                val tile = tiles[key(tx, ty)]
                if (tile != null) {
                    if (buf.size < len) buf = IntArray(len)
                    tile.bmp.getPixels(buf, 0, len, lx, ly, len, 1)
                    if (!any) for (k in 0 until len) if ((buf[k] ushr 24) != 0) { any = true; break }
                    tmp.setPixels(buf, 0, len, x - l, y - t, len, 1)
                }
                x += len
            }
        }
        if (!any) { tmp.recycle(); return@synchronized null }
        Lift(tmp, l, t)
    }

    /** Draws lifted pixels back through a deformation mesh (document-space vertices): the committed Warp / Puppet Warp. */
    fun drawMeshTiles(lf: Lift, mesh: WarpMesh) {
        synchronized(this) {
            val b = mesh.bounds()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            eachTileF(b.left - 2f, b.top - 2f, b.right + 2f, b.bottom + 2f, true) { tile -> tile.cv.drawBitmapMesh(lf.bmp, mesh.cols, mesh.rows, mesh.verts, 0, null, 0, paint) }
            touch()
        }
    }

    /** Draws lifted pixels back through a document-space matrix (same convention as drawLift). */
    fun drawLiftTiles(lf: Lift, m: FloatArray) {
        synchronized(this) {
            val mt = Matrix(); mt.setValues(m)
            val rf = RectF(lf.left.toFloat(), lf.top.toFloat(), (lf.left + lf.bmp.width).toFloat(), (lf.top + lf.bmp.height).toFloat())
            mt.mapRect(rf)
            eachTileF(rf.left - 1f, rf.top - 1f, rf.right + 1f, rf.bottom + 1f, true) { tile -> drawLift(tile.cv, lf, m) }
            touch()
        }
    }

    // ---------- reading ----------

    /** Draws the tiles that intersect `clip` (document-space integer rect) with the canvas' current matrix. */
    fun draw(nc: Canvas, clip: Rect, paint: Paint?) {
        synchronized(this) {
            val size = TileCache.T
            for (t in tiles.values) {
                val l = t.tx * size; val tp = t.ty * size
                if (l >= clip.right || l + size <= clip.left || tp >= clip.bottom || tp + size <= clip.top) continue
                nc.drawBitmap(t.bmp, l.toFloat(), tp.toFloat(), paint)
            }
        }
    }

    /** Copies the (0,0,w,h) crop into a Cut-sized bitmap's canvas. */
    fun drawCrop(dst: Canvas, w: Int, h: Int) {
        synchronized(this) {
            val size = TileCache.T
            for (t in tiles.values) {
                val l = t.tx * size; val tp = t.ty * size
                if (l >= w || l + size <= 0 || tp >= h || tp + size <= 0) continue
                dst.drawBitmap(t.bmp, l.toFloat(), tp.toFloat(), null)
            }
        }
    }

    /** Reads an arbitrary document-space rectangle (transparent where there are no tiles). `out` needs w*h entries. */
    fun readRect(x0: Int, y0: Int, w: Int, h: Int, out: IntArray) {
        synchronized(this) {
            java.util.Arrays.fill(out, 0, w * h, 0)
            val size = TileCache.T
            eachTile(x0, y0, x0 + w, y0 + h, false) { tile ->
                val gx = tile.tx * size; val gy = tile.ty * size
                val ix0 = max(x0, gx); val iy0 = max(y0, gy)
                val ix1 = min(x0 + w, gx + size); val iy1 = min(y0 + h, gy + size)
                val cw = ix1 - ix0; val ch = iy1 - iy0
                if (cw > 0 && ch > 0) tile.bmp.getPixels(out, (iy0 - y0) * w + (ix0 - x0), w, ix0 - gx, iy0 - gy, cw, ch)
            }
        }
    }
}

object TileCache {
    const val T = 512
    const val MAXI = 80 // tiles are only rasterized within +-40960 document px of the origin
    const val TILE_BYTES = 512L * 512L * 4L
    val total = AtomicLong(0)
    private val ver = AtomicLong(0)
    fun nextVersion(): Long = ver.incrementAndGet()
    @Volatile var budget = 160L shl 20
    /** Lowest budget the editor uses (set from the device's RAM at start). The old 128 MB was smaller than the working set of a dense frame plus onion
     *  skins: sets evicted each other and were rebuilt over and over (report: 124-145 MB of 128 MB, a rebuild every ~50 ms). */
    @Volatile var floorBytes = 0L
        set(v) { field = v; budget = maxOf(budget, v) }

    private class Id(val l: Any) {
        override fun equals(other: Any?) = other is Id && other.l === l
        override fun hashCode() = System.identityHashCode(l)
    }
    private val map = LinkedHashMap<Id, TileSet>(32, 0.75f, true)

    /** The up-to-date tile set of a cel's stroke list (created on first use). */
    fun get(list: SnapshotStateList<Ink>): TileSet {
        val s = synchronized(this) {
            val x = map[Id(list)] ?: TileSet().also { map[Id(list)] = it }
            trim(x)
            x
        }
        if (!Perf.active) { s.sync(list); return s }
        val t0 = android.os.SystemClock.elapsedRealtime(); val before = s.count
        s.sync(list)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        if (ms >= 8L && s.count != before) // a rebuild: shows how often and how long the UI thread waits for one (eviction thrash vs. a single big cel)
            Perf.event("tiles", if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) "rebuild-ui" else "rebuild-bg", "$ms ms; strokes $before->${list.size}; tiles ${total.get() shr 20} MB of ${budget shr 20} MB")
        return s
    }

    /**
     * UI-thread variant of [get]: never replays strokes. Returns the set to draw right now, or null when there is nothing safe to draw
     * yet. A missing or outdated set is (re)built on a background thread ([OnionWarmup], urgent queue) and the canvas redraws when it is
     * ready (it reads [OnionWarmup.ready]). A set that only lacks the newest strokes is drawn as it is; a set that must be cleared and
     * replayed, or is being built, is skipped. Scrubbing used to wait here for a full replay (up to ~57 s for a 50-stroke cel in the report).
     */
    fun getForDraw(list: SnapshotStateList<Ink>): TileSet? {
        val s = synchronized(this) { map[Id(list)]?.also { trim(it) } }
        if (s != null && !s.building && s.count == list.size) return s
        OnionWarmup.request(list, urgent = true)
        return if (s != null && !s.building && s.count < list.size) s else null
    }

    /** The existing set without creating or syncing it. */
    fun peek(list: SnapshotStateList<Ink>): TileSet? = synchronized(this) { map[Id(list)] }

    /** The set only if it already exists and is up to date, so the caller never waits for a rebuild (onion skin, reference drawings). */
    fun peekReady(list: SnapshotStateList<Ink>): TileSet? {
        val s = synchronized(this) { map[Id(list)] } ?: return null
        return if (s.count == list.size) s else null
    }

    fun invalidate(list: SnapshotStateList<Ink>) {
        val s = synchronized(this) { map.remove(Id(list)) }
        s?.clear()
    }

    private fun trim(keep: TileSet) {
        if (total.get() <= budget) return
        val it = map.entries.iterator()
        while (total.get() > budget && it.hasNext()) {
            val s = it.next().value
            if (s === keep || s.pinned) continue
            s.clear(); it.remove()
        }
    }
}

// ---------- windows for fill / wand / selection beyond the frame ----------

const val WINDOW_PIXEL_CAP = 8_000_000L

/**
 * Rectangle that fill / wand masks cover. With Bleed off it is the Cut (unchanged behaviour). With Bleed on it grows to the Cut,
 * the cel's rasterized content and the visible area; if that is too large for a mask it shrinks to a 3200x2400 window around (ax, ay).
 */
fun workWindow(cut: Cut, bleed: Boolean, set: TileSet?, view: RectF?, ax: Float, ay: Float): Rect {
    val base = Rect(0, 0, cut.w, cut.h)
    if (!bleed) return base
    val r = Rect(base)
    set?.tileBounds()?.let { r.union(it) }
    view?.let { r.union(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }
    if (r.width().toLong() * r.height().toLong() > WINDOW_PIXEL_CAP) {
        val cx = ax.toInt(); val cy = ay.toInt()
        val c = Rect(cx - 1600, cy - 1200, cx + 1600, cy + 1200)
        if (!r.intersect(c)) return base
    }
    return r
}

/** All visible layers of a tick composited on white into a document-space window (fill / wand "all layers" source). */
fun renderWindow(cut: Cut, tick: Int, x0: Int, y0: Int, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(android.graphics.Color.WHITE)
    cv.translate(-x0.toFloat(), -y0.toFloat())
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (l in cut.layers) {
        if (!l.visible || l.kind == 5 || l.isGroup) continue
        val list = l.drawCel(tick)?.strokes ?: continue
        if (list.isEmpty()) continue
        paint.alpha = (l.opacity * 255).toInt()
        val tr = cut.transformFor(l, tick)
        val transformed = l.group >= 0 && cut.layers.any { it.isGroup && it.id == l.group && it.transformFolder }
        if (transformed) {
            cv.save(); cv.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); cv.rotate(tr.rotation); cv.scale(tr.scale, tr.scale); cv.translate(-cut.w / 2f, -cut.h / 2f)
        }
        TileCache.get(list).draw(cv, cv.clipBounds, paint)
        if (transformed) cv.restore()
    }
    return bmp
}


// ---------- undo without a full rebuild ----------

/**
 * The pixels a brush stroke overwrote: a copy of every tile it touched (null = the tile did not exist). Undoing the stroke puts them
 * back ([TileSet.restoreFrom]) instead of clearing the cel and replaying every stroke. Kept by [UndoPixels] within a memory budget;
 * when one has been dropped, undo falls back to the rebuild.
 */
class StrokeBackup {
    private val saved = HashMap<Long, Bitmap?>()
    @Volatile var valid = true
    @Volatile var owned = false // true once the stroke is committed: the undo stack keeps it
    @Volatile var size = 0L

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    fun saveAbsent(tx: Int, ty: Int) { synchronized(this) { val k = key(tx, ty); if (!saved.containsKey(k)) saved[k] = null } }

    fun saveBefore(t: Tile) {
        synchronized(this) {
            val k = key(t.tx, t.ty)
            if (saved.containsKey(k)) return
            val copy = try { t.bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: OutOfMemoryError) { null }
            if (copy == null) valid = false else saved[k] = copy
        }
    }

    fun bytes(): Long = synchronized(this) { saved.values.count { it != null } * TileCache.TILE_BYTES }

    fun forEachSaved(block: (Int, Int, Bitmap?) -> Unit) {
        synchronized(this) { for ((k, v) in saved) block((k shr 32).toInt(), k.toInt(), v) }
    }

    fun release() { synchronized(this) { for (b in saved.values) b?.recycle(); saved.clear(); valid = false } }
}

/** Memory budget of the stroke backups behind the undo stack; the oldest are dropped first (their undo then rebuilds the cel). */
object UndoPixels {
    private const val BUDGET = 80L shl 20
    private val order = ArrayDeque<StrokeBackup>()
    private var total = 0L

    @Synchronized fun add(b: StrokeBackup) {
        b.owned = true
        b.size = b.bytes()
        order.addLast(b); total += b.size
        while (total > BUDGET && order.size > 1) { val o = order.removeFirst(); total -= o.size; o.release() }
    }
}

// ---------- images carried inside an ink ----------

private fun isEncodedImage(d: ByteArray): Boolean {
    if (d.size < 12) return false
    val a = d[0].toInt() and 255; val b = d[1].toInt() and 255
    return (a == 0x89 && b == 0x50) || (a == 0xFF && b == 0xD8) || (a == 'G'.code && b == 'I'.code) || (a == 'R'.code && b == 'I'.code) // PNG, JPEG, GIF, WEBP
}

/** Decoded pixels of imported / flattened images (kind-3 inks that carry their own PNG / JPEG bytes): identity-keyed LRU with a byte budget. */
object InkImageCache {
    private val map = LinkedHashMap<Ink, Bitmap>(8, 0.75f, true)
    private var bytes = 0L
    private const val BUDGET = 96L shl 20

    @Synchronized fun get(ink: Ink): Bitmap? {
        map[ink]?.let { return it }
        val d = ink.data ?: return null
        val bmp = BitmapFactory.decodeByteArray(d, 0, d.size) ?: return null
        map[ink] = bmp; bytes += bmp.byteCount.toLong()
        val it = map.entries.iterator()
        while (bytes > BUDGET && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.value !== bmp) { bytes -= e.value.byteCount.toLong(); it.remove() }
        }
        return bmp
    }
}

// ---------- background preparation of onion-skin / reference cels ----------

/** Rebuilds the tile sets of neighbouring drawings off the UI thread, so showing onion skins never stalls a frame. */
object OnionWarmup {
    private fun pool(name: String, prio: Int) = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, name).also { it.isDaemon = true; it.priority = prio } }
    private val pool = pool("onion-warmup", Thread.MIN_PRIORITY)
    /** Cels of the frame being shown (layers, scrubbing target): must not wait behind a queue of onion neighbours. */
    private val urgentPool = pool("tile-build", Thread.NORM_PRIORITY - 1)
    private val pending = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    /** Bumped whenever a set became ready; the canvas reads it so it redraws. */
    val ready = androidx.compose.runtime.mutableIntStateOf(0)

    private val doneAt = java.util.IdentityHashMap<Any, Long>()

    /** [urgent]: the cel is on screen now, so the 1.5 s cool-down does not apply (a set that merely lacks the newest strokes must catch up at once). */
    fun request(list: SnapshotStateList<Ink>, urgent: Boolean = false) {
        synchronized(pending) {
            val last = doneAt[list]
            if (!urgent && last != null && android.os.SystemClock.uptimeMillis() - last < 1500L) return // just prepared (and maybe evicted again by the memory budget): do not loop
            if (!pending.add(list)) return
        }
        (if (urgent) urgentPool else pool).execute {
            try { TileCache.get(list) } catch (_: Throwable) { }
            finally { synchronized(pending) { pending.remove(list); doneAt[list] = android.os.SystemClock.uptimeMillis(); if (doneAt.size > 64) doneAt.clear() }; ready.intValue++ }
        }
    }
}
