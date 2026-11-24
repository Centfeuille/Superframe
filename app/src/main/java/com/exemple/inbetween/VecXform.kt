package com.example.inbetween

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/*
 * Transform / Warp / Puppet Warp of VECTOR lines: the vector counterpart of the raster selection transform (Xform in Tools.kt).
 *
 * The deformation is the same as the raster one: a WarpMesh over the bounding rectangle of what is transformed (grid warp = nudged nodes, Puppet Warp =
 * pins), followed by a matrix (the box handles). A vector line is not a bitmap, so instead of drawing a mesh the points of the line are carried through it
 * (VecDeform.map): the mesh is sampled at each point's place in the rectangle. A whole line is densified first so that it bends smoothly, and thinned again
 * with the curve-aware fitter when the deformation is applied for good.
 *
 * VecXform is the live session (state the bar and the canvas edit); VecDeform is a frozen copy of it. The same VecDeform is used for the real lines of the
 * selection (applied when the session is applied) and for the interpolation preview (kept as a PreviewWarp step of the InbetweenPlace until the in-between
 * is created).
 */

/** Cells per side of the Puppet Warp mesh on vector lines (finer than the raster 24: a line crosses cells at any angle and pin radii can be small). */
const val VEC_PUPPET_CELLS = 48

/** A frozen deformation of the document plane: the mesh over [rect] (null = none), then the matrix. */
class VecDeform(rect: RectF, val mesh: WarpMesh?, val mat: Aff) {
    val rect = RectF(rect)
    private val rw = max(this.rect.width(), 1e-3f)
    private val rh = max(this.rect.height(), 1e-3f)

    val isIdentity: Boolean get() {
        if (!mat.isIdentity) return false
        val m = mesh ?: return true
        val g = WarpMesh.grid(rect.left, rect.top, rw, rh, m.cols, m.rows)
        for (i in m.verts.indices) if (abs(m.verts[i] - g.verts[i]) > 1e-3f) return false
        return true
    }

    /** Where document point (x, y) goes; written to out[0], out[1]. */
    fun map(x: Float, y: Float, out: FloatArray) {
        var px = x; var py = y
        val m = mesh
        if (m != null) {
            m.sample(((x - rect.left) / rw).coerceIn(0f, 1f), ((y - rect.top) / rh).coerceIn(0f, 1f), out)
            px = out[0]; py = out[1]
        }
        val o = mat.map(px, py)
        out[0] = o.x; out[1] = o.y
    }
}

/**
 * The live Transform / Warp / Puppet session of vector lines. [rect] is the box of what is transformed (document space); [sel] the point keys
 * (real lines of the Selection tool, scope-wide); [ids] the picked line ids of the interpolation preview (empty = the whole preview), or null for real lines.
 * Warp and Puppet Warp work exactly as in the raster [Xform]; the mesh is in document space BEFORE [mat].
 */
class VecXform(rect: RectF, val sel: Set<Long>, val ids: Set<String>?) {
    val rect = RectF(rect)
    val onPreview: Boolean get() = ids != null

    /** 0 = handles, 1 = grid warp, 2 = Puppet Warp. */
    var mode by mutableIntStateOf(0)
    val mat = Matrix()
    var mesh: WarpMesh? = null
    var warpN by mutableIntStateOf(4)
    var strength by mutableFloatStateOf(0.5f)
    val pins = ArrayList<Pin>()
    var pinSel by mutableIntStateOf(-1)
    var pinRadius by mutableFloatStateOf(0f)
    var puppetBase: WarpMesh? = null
    var rev by mutableIntStateOf(0)

    private fun grid(cells: Int) = WarpMesh.grid(rect.left, rect.top, rect.width(), rect.height(), cells, cells)
    fun meshOrGrid(cells: Int): WarpMesh = mesh ?: grid(cells)

    fun switchMode(m: Int) {
        if (m == mode) return
        mode = m; pins.clear(); pinSel = -1; puppetBase = null
        when (m) {
            1 -> { val cur = mesh; if (cur == null) mesh = meshOrGrid(warpN) else warpN = cur.cols.coerceIn(2, 16) }
            2 -> { val base = meshOrGrid(VEC_PUPPET_CELLS).resampled(VEC_PUPPET_CELLS, VEC_PUPPET_CELLS); puppetBase = base; mesh = base.copy() }
        }
        rev++
    }

    fun setIncrements(n: Int) {
        warpN = n.coerceIn(2, 16)
        mesh = (mesh ?: meshOrGrid(warpN)).resampled(warpN, warpN)
        rev++
    }

    fun applyPins() { puppetBase?.let { mesh = puppetMesh(it, pins) }; rev++ }
    fun clearPins() { pins.clear(); pinSel = -1; applyPins() }
    fun applyPinRadius(r: Float) { pinRadius = r; pins.getOrNull(pinSel)?.r = r; applyPins() }

    /** Back to the untouched lines: no transform, no deformation, no pins. */
    fun resetAll() {
        mat.reset(); pins.clear(); pinSel = -1; puppetBase = null
        mesh = when (mode) {
            1 -> grid(warpN)
            2 -> grid(VEC_PUPPET_CELLS).also { puppetBase = it.copy() }
            else -> null
        }
        rev++
    }

    val isIdentity: Boolean get() = frozen().isIdentity

    fun frozen(): VecDeform = VecDeform(rect, mesh?.copy(), Aff.of(mat))
}

/** The line [s] with extra points so that it bends smoothly through a deformation (the original points stay, with their anchors and flags). */
private fun densified(s: VectorStroke): List<VectorPoint> {
    val src = s.points
    val closed = s.isClosed && src.size > 2
    var total = 0f
    for (i in 1 until src.size) total += hypot(src[i].x - src[i - 1].x, src[i].y - src[i - 1].y)
    if (closed) total += hypot(src.last().x - src.first().x, src.last().y - src.first().y)
    val maxGap = max(total / 150f, 1f)
    val out = ArrayList<VectorPoint>(src.size + 150)
    val pieces = if (closed) src.size else src.size - 1
    for (i in 0 until pieces) {
        val a = src[i]; val b = src[(i + 1) % src.size]
        out.add(a)
        val extra = (hypot(b.x - a.x, b.y - a.y) / maxGap).toInt()
        for (k in 1..extra) {
            val f = k.toFloat() / (extra + 1)
            out.add(VectorPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.pressure + (b.pressure - a.pressure) * f, a.elevationOffset + (b.elevationOffset - a.elevationOffset) * f))
        }
    }
    if (!closed) out.add(src.last())
    return out
}

/**
 * Carries the [sel]ected points of [strokes] (keys as in [VectorEdit]) through [d]. A line whose points are all selected also scales its width with the
 * matrix (unless [lockWeight]) and, when [d] has a mesh, is densified first so that it bends smoothly; with [thin] it is fitted back to few points afterwards
 * (curve-aware, 0.5 px; anchors, ends and hidden points are kept), otherwise the dense line is kept (for a live preview). Lines with only some points
 * selected move just those points. Untouched lines keep their instance.
 */
fun vecDeform(strokes: List<VectorStroke>, sel: Set<Long>, d: VecDeform, lockWeight: Boolean, thin: Boolean): List<VectorStroke> {
    if (sel.isEmpty() || d.isIdentity) return strokes
    val area = if (lockWeight) 1f else d.mat.area.coerceIn(0.02f, 50f)
    val xy = FloatArray(2)
    return strokes.mapIndexed { si, s ->
        var count = 0
        for (pi in s.points.indices) if (sel.contains(vecKey(si, pi))) count++
        if (count == 0) return@mapIndexed s
        val whole = count == s.points.size
        if (!whole || d.mesh == null || s.points.size < 2) {
            val pts = s.points.mapIndexed { pi, p ->
                if (!sel.contains(vecKey(si, pi))) p
                else { d.map(p.x, p.y, xy); p.copy(x = xy[0], y = xy[1], elevationOffset = if (whole) p.elevationOffset * area else p.elevationOffset) }
            }
            return@mapIndexed if (whole) s.copy(points = pts, baseWidth = s.baseWidth * area) else s.copy(points = pts)
        }
        val mapped = densified(s).map { p -> d.map(p.x, p.y, xy); p.copy(x = xy[0], y = xy[1], elevationOffset = p.elevationOffset * area) }
        val closed = s.isClosed && mapped.size > 2
        var out = mapped
        if (thin && !closed) {
            val protect = HashSet<Int>()
            for (i in mapped.indices) { val q = mapped[i]; if (i == 0 || i == mapped.lastIndex || q.anchor > 0 || q.endpoint || q.endpointAliases.isNotEmpty() || q.hidden) protect.add(i) }
            val keep = VectorSimplify.fitIndices(mapped, WARP_FIT_EPS, s.baseWidth * area, protect)
            out = keep.map { mapped[it] }
        }
        s.copy(points = out, baseWidth = s.baseWidth * area)
    }
}

/** The keys of every point of every line of [strokes]. */
fun vecAllKeys(strokes: List<VectorStroke>): Set<Long> {
    val out = HashSet<Long>()
    for ((si, s) in strokes.withIndex()) for (pi in s.points.indices) out.add(vecKey(si, pi))
    return out
}

/**
 * One applied Transform / Warp / Puppet step on the interpolation preview. The steps of an [InbetweenPlace] are applied in order after everything else, so what
 * is on screen when a step is applied is what the in-between is made with. [ids] = the preview lines it works on (null = every line).
 */
class PreviewWarp(val ids: Set<String>?, val deform: VecDeform, val lockWeight: Boolean) {
    fun applyTo(s: VectorStroke): VectorStroke {
        if (ids != null && s.id !in ids) return s
        return vecDeform(listOf(s), vecAllKeys(listOf(s)), deform, lockWeight, true).first()
    }
}
