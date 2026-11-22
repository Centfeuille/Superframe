package com.example.inbetween

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Automatic anchors for vector lines.
 *  - [AutoAnchors.targets]: an open line gets its two outer endpoints plus detected curve tips; a closed line gets detected curve tips (or fallback opposite points). Open-line endpoint anchors are structural and marked [VectorPoint.auto],
 *    so [AutoAnchors.refresh] moves them to the outer ends after constituent strokes are merged. Curve-tip anchors are refreshed with the curve; inherited/manual correspondence anchors remain in their original order. Anchors set by hand,
 *    or inherited from a traced line, are never touched.
 *  - [AutoAnchors.inherit]: a line traced over a ghost takes the ghost line's anchor numbers, placed at the nearest points of the new line in the same order.
 *  - [LineGhost]: which line of the previous / next keyframe is the one to trace next.
 */
object AutoAnchors {
    fun bboxDiag(pts: List<VectorPoint>): Float {
        if (pts.isEmpty()) return 0f
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in pts) { l = min(l, p.x); r = max(r, p.x); t = min(t, p.y); b = max(b, p.y) }
        return hypot(r - l, b - t)
    }

    private fun pathLength(pts: List<VectorPoint>, closed: Boolean): Float {
        var len = 0f
        for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        if (closed && pts.size >= 3) len += hypot(pts.first().x - pts.last().x, pts.first().y - pts.last().y)
        return len
    }

    fun usedNumbers(strokes: List<VectorStroke>): Set<Int> {
        val out = HashSet<Int>()
        for (s in strokes) for (p in s.points) if (p.anchor > 0) out.add(p.anchor)
        return out
    }

    /** Anchor numbers used in the previous and the next keyframe with lines: a new line avoids them, so it never pairs with a line it was not meant to by accident. */
    fun neighbourNumbers(layer: Layer, idx: Int): Set<Int> {
        val out = HashSet<Int>()
        LineGhost.reference(layer, idx, false)?.let { out.addAll(usedNumbers(it.vectorStrokes())) }
        LineGhost.reference(layer, idx, true)?.let { out.addAll(usedNumbers(it.vectorStrokes())) }
        return out
    }

    /**
     * Indices of the points that get an automatic anchor. An open line gets its two outer endpoints plus up to [maxTips] sharp curve tips between them. A closed line gets up to [maxTips] sharp tips, or two opposite points when it has none. [sens] 0..1 controls tip sensitivity.
     */
    fun targets(pts: List<VectorPoint>, closed: Boolean, sens: Float, maxTips: Int = 6): List<Int> {
        val n = pts.size
        if (n < 2) return emptyList()
        // Both open and closed strokes detect curve tips. Only the two endpoints of
        // an open stroke are structural: after a merge they move to the outer ends of
        // the complete stroke. Interior curve-tip anchors remain automatic, while
        // inherited/manual correspondence anchors are preserved by refresh().
        val ring = closed && n >= 3
        val m = if (ring) n + 1 else n
        val xs = FloatArray(m) { pts[it % n].x }; val ys = FloatArray(m) { pts[it % n].y }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[m - 1]
        fun nearestVertex(s: Float): Int { var best = 0; var bd = Float.MAX_VALUE; for (j in 0 until n) { val dd = abs(cum[j] - s); if (dd < bd) { bd = dd; best = j } }; return best }
        if (total < 1e-3f) return if (ring) listOf(0) else listOf(0, n - 1)
        val cnt = max(8, (total / (total / 160f).coerceIn(1f, 6f)).toInt() + 1)
        val seg = total / (cnt - 1)
        val sx = FloatArray(cnt); val sy = FloatArray(cnt)
        var j = 0
        for (i in 0 until cnt) {
            val s = seg * i
            while (j < m - 2 && cum[j + 1] < s) j++
            val l = cum[j + 1] - cum[j]
            val t = if (l < 1e-6f) 0f else ((s - cum[j]) / l).coerceIn(0f, 1f)
            sx[i] = xs[j] + (xs[j + 1] - xs[j]) * t; sy[i] = ys[j] + (ys[j + 1] - ys[j]) * t
        }
        repeat(2) { // light smoothing, so small jitter is not a tip (the ends stay)
            var px = sx[0]; var py = sy[0]
            for (i in 1 until cnt - 1) { val cx = sx[i]; val cy = sy[i]; sx[i] = 0.25f * px + 0.5f * cx + 0.25f * sx[i + 1]; sy[i] = 0.25f * py + 0.5f * cy + 0.25f * sy[i + 1]; px = cx; py = cy }
        }
        val k = max(2, (((total * 0.06f).coerceIn(6f, 40f)) / seg).roundToInt())
        fun at(q: Int): Int = if (ring) ((q % (cnt - 1)) + (cnt - 1)) % (cnt - 1) else q
        val ang = FloatArray(cnt)
        val lo = if (ring) 0 else k
        val hi = if (ring) cnt else cnt - k
        for (i in lo until hi) {
            val a = at(i - k); val b = at(i); val c = at(i + k)
            val v1x = sx[b] - sx[a]; val v1y = sy[b] - sy[a]; val v2x = sx[c] - sx[b]; val v2y = sy[c] - sy[b]
            ang[i] = abs(Math.toDegrees(atan2((v1x * v2y - v1y * v2x).toDouble(), (v1x * v2x + v1y * v2y).toDouble()))).toFloat()
        }
        val thresh = 100f - 70f * sens.coerceIn(0f, 1f)
        val cands = (lo until hi).filter { i -> ang[i] >= thresh && (i - k..i + k).all { q -> val qq = if (ring) at(q) else q; qq !in 0 until cnt || ang[qq] <= ang[i] } }.sortedByDescending { ang[it] }
        val sep = max(k, (total * 0.12f / seg).roundToInt())
        val chosen = ArrayList<Int>()
        for (i in cands) {
            if (chosen.size >= maxTips) break
            if (!ring && (i < sep * 0.6f || cnt - 1 - i < sep * 0.6f)) continue
            if (chosen.any { c -> val dd = abs(c - i); (if (ring) min(dd, cnt - 1 - dd) else dd) < sep }) continue
            chosen.add(i)
        }
        val idx = sortedSetOf<Int>()
        if (!ring) { idx.add(0); idx.add(n - 1) }
        for (i in chosen) idx.add(nearestVertex(seg * i))
        if (ring) {
            if (idx.isEmpty()) { idx.add(0); idx.add(nearestVertex(total / 2f)) }
            else if (idx.size == 1) { val a = idx.first(); idx.add(nearestVertex((cum[a] + total / 2f) % total)) }
        }
        return idx.toList()
    }

    /** A new line: anchors at its ends and tips, numbered from the first number that is not in [taken] (marked automatic). Lines that are fills, or too short, stay as they are. */
    fun assignFresh(s: VectorStroke, taken: Set<Int>, sens: Float): VectorStroke {
        if (s.fill != 0 || s.points.size < 2) return s
        val idx = targets(s.points, s.isClosed, sens)
        if (idx.isEmpty()) return s
        val used = HashSet<Int>(taken)
        val pts = s.points.toMutableList()
        var next = 1
        for (i in idx) {
            if (pts[i].anchor > 0) continue
            while (next in used && next <= ANCHOR_MAX) next++
            if (next > ANCHOR_MAX) break
            used.add(next); pts[i] = pts[i].copy(anchor = next, auto = true); next++
        }
        return s.copy(points = pts)
    }

    /**
     * After lines changed shape (merged, points deleted): the automatic anchors of every line that has some are redone. Open-line endpoints and detected curve tips are targets. The numbers of points that are still ends or tips are
     * kept (matched by position), anchors that are no longer one are dropped, new ones get free numbers (not used in this keyframe, not in [avoid]). Returns [strokes] itself when nothing changed.
     */
    fun refresh(strokes: List<VectorStroke>, avoid: Set<Int>, sens: Float, only: Set<String>? = null): List<VectorStroke> {
        if (strokes.none { s -> s.points.any { it.auto && it.anchor > 0 } }) return strokes
        val count = IntArray(ANCHOR_MAX + 2)
        for (s in strokes) for (p in s.points) if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]++
        val out = ArrayList<VectorStroke>(strokes.size)
        var changed = false
        for (s in strokes) {
            if (s.points.none { it.auto && it.anchor > 0 } || (only != null && s.id !in only)) { out.add(s); continue } // a line that was not merged keeps its numbers, whatever happened around it
            val old = s.points.filter { it.auto && it.anchor > 0 }
            for (p in s.points) if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]--
            val cleared = s.points.map { if (it.auto) it.copy(anchor = 0, auto = false) else it }
            // Existing automatic anchors are correspondence identities, not fresh
            // allocations. A number may also exist in a neighbouring keyframe (that
            // is precisely why this line is paired with it), so [avoid] must not block
            // reuse. Shared numbers are prioritized below over unrelated fresh auto
            // anchors that may have arrived with another stroke during a merge.
            // Only anchors still used elsewhere in this cel, plus non-auto anchors on
            // this stroke, block reuse of an existing identity.
            val claimed = HashSet<Int>()
            for (q in 1..ANCHOR_MAX) if (count[q] > 0) claimed.add(q)
            for (p in cleared) if (p.anchor > 0) claimed.add(p.anchor)
            val tol = max(3f, pathLength(cleared, s.isClosed) * 0.03f)
            val pool = old.toMutableList()
            val pts = cleared.toMutableList()
            var next = 1
            val tg = targets(cleared, s.isClosed, sens)
            val unmatched = ArrayList<Int>()
            for (i in tg) { // 1. a target that lies where an automatic anchor was keeps its number
                if (pts[i].anchor > 0) continue
                // Prefer anchors shared with neighbouring keyframes: they are the
                // correspondence contract. A separately-created stroke may bring
                // its own fresh auto anchors to the merge; those must not displace
                // the ghost's endpoint identities merely because they sit closer.
                val candidates = pool.filter { it.anchor in avoid && it.anchor !in claimed }.ifEmpty { pool.filter { it.anchor !in claimed } }
                val near = candidates.minByOrNull { hypot(it.x - pts[i].x, it.y - pts[i].y) }?.takeIf { hypot(it.x - pts[i].x, it.y - pts[i].y) <= tol }
                if (near != null) { pool.remove(near); claimed.add(near.anchor); pts[i] = pts[i].copy(anchor = near.anchor, auto = true) } else unmatched.add(i)
            }
            for (i in unmatched) { // 2. a new end or tip takes over an old identity; correspondence anchors outrank unrelated fresh anchors
                val available = pool.filter { it.anchor !in claimed }
                val heirs = available.filter { it.anchor in avoid }.ifEmpty { available }
                val heir = heirs.minByOrNull { hypot(it.x - pts[i].x, it.y - pts[i].y) } ?: continue
                pool.remove(heir); claimed.add(heir.anchor); pts[i] = pts[i].copy(anchor = heir.anchor, auto = true)
            }
            for (i in unmatched) { // 3. what is left gets a free number
                if (pts[i].anchor > 0) continue
                while ((next in claimed || next in avoid) && next <= ANCHOR_MAX) next++
                if (next > ANCHOR_MAX) continue
                claimed.add(next); pts[i] = pts[i].copy(anchor = next, auto = true)
            }
            for (p in pts) if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]++
            if (pts == s.points) out.add(s) else { out.add(s.copy(points = pts)); changed = true }
        }
        return if (changed) out else strokes
    }

    /** True when both ends of an open line carry an anchor (closed lines and points have no ends). */
    fun endsAnchored(s: VectorStroke): Boolean = s.isClosed || s.points.size < 2 || (s.points.first().anchor > 0 && s.points.last().anchor > 0)

    /** The two end points of an open line always carry an anchor: a missing one is added on the exact end point, with a number not in [taken] (marked automatic). */
    fun withEnds(s: VectorStroke, taken: Set<Int>): VectorStroke {
        if (s.fill != 0 || s.isClosed || s.points.size < 2 || endsAnchored(s)) return s
        val used = HashSet<Int>(taken)
        for (p in s.points) if (p.anchor > 0) used.add(p.anchor)
        val pts = s.points.toMutableList()
        var next = 1
        for (i in intArrayOf(0, pts.size - 1)) {
            if (pts[i].anchor > 0) continue
            while (next in used && next <= ANCHOR_MAX) next++
            if (next > ANCHOR_MAX) break
            used.add(next); pts[i] = pts[i].copy(anchor = next, auto = true)
        }
        return s.copy(points = pts)
    }

    /** [withEnds] for the lines in [only] (all when null). Returns [strokes] itself when nothing changed. */
    fun ensureEnds(strokes: List<VectorStroke>, avoid: Set<Int>, only: Set<String>? = null): List<VectorStroke> {
        val used = HashSet<Int>(avoid); used.addAll(usedNumbers(strokes))
        var changed = false
        val out = strokes.map { s ->
            if ((only != null && s.id !in only) || endsAnchored(s)) s
            else withEnds(s, used).also { r -> if (r !== s) { changed = true; for (p in r.points) if (p.anchor > 0) used.add(p.anchor) } }
        }
        return if (changed) out else strokes
    }

    /** [refresh] for the cel [cel] of [layer] (its neighbours decide which numbers are avoided). */
    fun refreshFor(layer: Layer, cel: Cel, strokes: List<VectorStroke>, sens: Float): List<VectorStroke> {
        val idx = layer.cels.indexOfFirst { it === cel }
        return refresh(strokes, if (idx >= 0) neighbourNumbers(layer, idx) else emptySet(), sens)
    }

    private fun distToPoly(ax: Float, ay: Float, p: List<VectorPoint>): Float {
        var best = Float.MAX_VALUE
        for (j in 0 until p.size - 1) {
            val dx = p[j + 1].x - p[j].x; val dy = p[j + 1].y - p[j].y
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9f) 0f else (((ax - p[j].x) * dx + (ay - p[j].y) * dy) / l2).coerceIn(0f, 1f)
            best = min(best, hypot(ax - (p[j].x + dx * t), ay - (p[j].y + dy * t)))
        }
        return best
    }

    /**
     * Traces: [s] (a line just drawn) takes the anchor numbers of the ghost line [g] it was drawn over, except numbers in [blocked] (already used in this keyframe). Each number
     * is placed at the point of [s] nearest to the ghost's anchor, in the same order along the line (a line drawn the other way round is read backwards; the ends of the ghost
     * land on the ends of [s]); when the line has no point there, one is inserted. These anchors are not automatic: they say which point matches which, so they are never redone.
     * Null when the ghost carries no usable anchor.
     */
    fun inherit(s: VectorStroke, g: VectorStroke, blocked: Set<Int>): VectorStroke? {
        val n = s.points.size
        val gm = g.points.size
        if (s.fill != 0 || n < 2 || gm < 2) return null
        val ga = g.points.withIndex().filter { it.value.anchor > 0 && it.value.anchor !in blocked }
        if (ga.isEmpty()) return null
        val sRing = s.isClosed && n >= 3
        val gRing = g.isClosed && gm >= 3
        val mono = !sRing && !gRing
        val m = if (sRing) n + 1 else n
        val xs = FloatArray(m) { s.points[it % n].x }; val ys = FloatArray(m) { s.points[it % n].y }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[m - 1]
        if (total < 1e-3f) return null
        val gcum = FloatArray(gm)
        for (i in 1 until gm) gcum[i] = gcum[i - 1] + hypot(g.points[i].x - g.points[i - 1].x, g.points[i].y - g.points[i - 1].y)
        val gtotal = max(gcum[gm - 1], 1e-3f)
        var reversed = false
        if (mono) {
            val a0 = s.points.first(); val a1 = s.points.last(); val b0 = g.points.first(); val b1 = g.points.last()
            // direction from where each line runs (start -> end), not from where it lies, so a line drawn away from the ghost is still read the right way round
            val dot = (a1.x - a0.x) * (b1.x - b0.x) + (a1.y - a0.y) * (b1.y - b0.y)
            reversed = dot < 0f
        }
        val order = if (reversed) ga.reversed() else ga
        // Only the actual ends of the reference stroke are structural endpoints. If a
        // traced piece is later merged with another piece, refresh() can then move these
        // anchors to the outside ends of the resulting whole stroke. Every interior ghost
        // anchor (including automatic curve tips) is inherited as a fixed correspondence
        // anchor: its number and order survive later refreshes and merges.
        val structuralRanks = if (mono) ga.filter { it.index == 0 || it.index == gm - 1 }.map { it.value.anchor }.toSet() else emptySet()
        val diag = max(bboxDiag(s.points), bboxDiag(g.points))
        fun distToLine(ax: Float, ay: Float): Float = distToPoly(ax, ay, s.points)
        val gap = max(0.5f, total * 0.002f)
        // Three cases. (1) available ghost anchors lie near the new line: use their nearest points.
        // (2) the new line covers only part of the ghost: take only anchors near this part; the others stay for later strokes.
        // (3) the new line is elsewhere (the drawing moved): map every still-free ghost anchor by its relative path position.
        val nearTol = 0.15f * diag + 4f
        val allNear = ga.all { distToLine(it.value.x, it.value.y) <= nearTol }
        val partTol = 0.05f * diag + 8f
        val onGhost = s.points.all { distToPoly(it.x, it.y, g.points) <= partTol }
        // A ghost can already have some of its anchor numbers in use in this keyframe
        // (for example, an earlier piece of the same traced line). The remaining free
        // anchors still define exact correspondences. Do not require every ghost anchor
        // to be free before mapping those that remain by their relative position.
        val mode = when { !mono || allNear -> 1; onGhost -> 2; ga.isNotEmpty() -> 3; else -> return null }
        val useNearest = mode != 3
        val params = ArrayList<Pair<Float, Int>>()
        var last = -gap
        for (a in order) {
            val gi = a.index
            val atStart = !gRing && gi == 0
            val atEnd = !gRing && gi == gm - 1
            var p: Float
            if (mode == 2 && distToLine(a.value.x, a.value.y) > partTol) continue // this part of the ghost line is not under this stroke
            if (mono && (atStart || atEnd) && (mode != 2 || distToLine(a.value.x, a.value.y) <= partTol)) p = if ((atStart && !reversed) || (atEnd && reversed)) 0f else total
            else {
                val ax = a.value.x; val ay = a.value.y
                var best = Float.MAX_VALUE; var bp = -1f
                for (j in 0 until m - 1) {
                    if (mono && cum[j + 1] < last + gap * 0.5f) continue
                    val l = cum[j + 1] - cum[j]
                    val dx = xs[j + 1] - xs[j]; val dy = ys[j + 1] - ys[j]
                    val t = if (l < 1e-6f) 0f else (((ax - xs[j]) * dx + (ay - ys[j]) * dy) / (l * l)).coerceIn(0f, 1f)
                    var pp = cum[j] + t * l
                    if (mono && pp < last + gap) pp = last + gap
                    val tt = if (l < 1e-6f) 0f else ((pp - cum[j]) / l).coerceIn(0f, 1f)
                    val dd = hypot(ax - (xs[j] + dx * tt), ay - (ys[j] + dy * tt))
                    if (dd < best) { best = dd; bp = pp }
                }
                p = if (!useNearest || bp < 0f) { val u = gcum[gi] / gtotal; (if (reversed) 1f - u else u) * total } else bp
            }
            if (mono) { if (p < last + gap) p = last + gap; if (p > total) continue }
            else if (params.any { abs(it.first - p) < gap }) continue
            p = p.coerceIn(0f, total)
            params.add(p to a.value.anchor); last = p
        }
        if (params.isEmpty()) return null
        val vertexRank = HashMap<Int, Int>()
        val inserts = HashMap<Int, MutableList<Pair<Float, Int>>>()
        val tol = max(0.75f, total * 0.004f)
        for ((p, r) in params.sortedBy { it.first }) {
            var v = -1; var bd = Float.MAX_VALUE
            for (j in 0 until n) {
                var dd = abs(cum[j] - p)
                if (j == 0 && sRing) dd = min(dd, abs(total - p))
                if (dd < bd) { bd = dd; v = j }
            }
            if (bd <= tol && v !in vertexRank) vertexRank[v] = r
            else {
                var j = 0
                while (j < m - 2 && cum[j + 1] < p) j++
                inserts.getOrPut(j) { ArrayList() }.add(p to r)
            }
        }
        val out = ArrayList<VectorPoint>(n + inserts.size)
        for (j in 0 until n) {
            val base = s.points[j]
            out.add(vertexRank[j]?.let { base.copy(anchor = it, auto = it in structuralRanks) } ?: base)
            inserts[j]?.sortedBy { it.first }?.forEach { (p, r) ->
                val l = cum[j + 1] - cum[j]
                val t = if (l < 1e-6f) 0f else ((p - cum[j]) / l).coerceIn(0f, 1f)
                val a = s.points[j]; val b = s.points[(j + 1) % n]
                out.add(VectorPoint(x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t, pressure = a.pressure + (b.pressure - a.pressure) * t, elevationOffset = a.elevationOffset + (b.elevationOffset - a.elevationOffset) * t, anchor = r, auto = r in structuralRanks))
            }
        }
        return s.copy(points = out)
    }
}

/** The line order onion skin: the lines of the previous / next keyframe that have not been traced yet in the current one, in drawing order. */
object LineGhost {
    fun lines(c: Cel): List<VectorStroke> = c.vectorStrokes().filter { it.fill == 0 && it.points.size >= 2 }

    /** The nearest previous (or next) keyframe whose drawing differs from the current one and has vector strokes (clones of the current drawing and empty frames are skipped). */
    fun reference(layer: Layer, idx: Int, next: Boolean): Cel? {
        val cur = layer.cels.getOrNull(idx) ?: return null
        var i = if (next) idx + 1 else idx - 1
        while (i in layer.cels.indices) {
            val c = layer.cels[i]
            if (c.strokes !== cur.strokes && c.strokes.any { it.kind == 5 }) return c
            i += if (next) 1 else -1
        }
        return null
    }

    private fun endDist(a: VectorStroke, b: VectorStroke): Float {
        val a0 = a.points.first(); val a1 = a.points.last(); val b0 = b.points.first(); val b1 = b.points.last()
        if (a.isClosed || b.isClosed) {
            val ax = a.points.sumOf { it.x.toDouble() } / a.points.size; val ay = a.points.sumOf { it.y.toDouble() } / a.points.size
            val bx = b.points.sumOf { it.x.toDouble() } / b.points.size; val by = b.points.sumOf { it.y.toDouble() } / b.points.size
            return hypot(ax - bx, ay - by).toFloat()
        }
        val f = (hypot(a0.x - b0.x, a0.y - b0.y) + hypot(a1.x - b1.x, a1.y - b1.y)) / 2f
        val r = (hypot(a0.x - b1.x, a0.y - b1.y) + hypot(a1.x - b0.x, a1.y - b0.y)) / 2f
        return min(f, r)
    }

    /** Lines of [ref] nobody has traced yet: a line of [current] claims one when they share an anchor number or when its ends lie on that line's ends. [px] = document pixels per screen pixel. */
    fun unclaimed(ref: List<VectorStroke>, current: List<VectorStroke>, px: Float): List<VectorStroke> {
        val cur = current.filter { it.fill == 0 && it.points.size >= 2 }
        val nums = AutoAnchors.usedNumbers(cur)
        return ref.filter { r ->
            val byNumber = r.points.any { it.anchor > 0 && it.anchor in nums }
            val byShape = cur.any { c -> endDist(c, r) <= 0.25f * max(AutoAnchors.bboxDiag(c.points), AutoAnchors.bboxDiag(r.points)) + 6f * px }
            !(byNumber || byShape)
        }
    }

    fun key(layer: Layer, idx: Int): Long = layer.id.toLong() * 1_000_000L + idx

    /**
     * The line shown now: the pinned one while it exists on this keyframe (even after it has been traced, so a line can be drawn in several strokes), otherwise the first
     * untraced line. The pin moves only when the person skips ([skip]) or draws over another ghost.
     */
    fun shown(all: List<VectorStroke>, open: List<VectorStroke>, ed: Editor, key: Long): VectorStroke? {
        val pin = ed.vecGhostPin
        if (pin != null && ed.vecGhostPinKey == key) all.firstOrNull { it.id == pin }?.let { return it }
        return open.firstOrNull()
    }

    /** Skip to the next ([dir] = 1) or previous (-1) untraced line of the reference keyframe, counting round. Lines already traced are passed over. */
    fun skip(ed: Editor, layer: Layer, idx: Int, dir: Int) {
        val cur = layer.cels.getOrNull(idx) ?: return
        val ref = reference(layer, idx, ed.vecGhostNext) ?: return
        val all = lines(ref)
        if (all.isEmpty()) return
        val open = unclaimed(all, cur.vectorStrokes(), 1f / ed.vecDocZoom.coerceAtLeast(0.0001f))
        if (open.isEmpty()) return
        val pk = key(layer, idx)
        val now = shown(all, open, ed, pk)
        val start = all.indexOfFirst { it.id == now?.id }.let { if (it < 0) 0 else it }
        val openIds = open.map { it.id }.toSet()
        for (k in 1..all.size) {
            val j = (((start + dir * k) % all.size) + all.size) % all.size
            if (all[j].id in openIds) { ed.vecGhostPin = all[j].id; ed.vecGhostPinKey = pk; return }
        }
    }
}
