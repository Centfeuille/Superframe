package com.example.inbetween

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Automatic anchors for vector lines.
 *  - [AutoAnchors.targets]: an open line gets its two outer endpoints plus detected curve-lobe extrema and sharp corners; a closed line gets detected features (or fallback opposite points). Open-line endpoint anchors are structural and marked [VectorPoint.endpoint],
 *    separately from the general [VectorPoint.auto] flag, so curve-tip identities cannot be reassigned to endpoints during [AutoAnchors.refresh]. Curve-tip anchors are refreshed with the curve; inherited/manual correspondence anchors remain in their original order. Anchors set by hand,
 *    or inherited from a traced line, are never touched.
 *  - [AutoAnchors.inherit]: a line traced over a ghost takes the ghost line's anchor numbers; automatic curve features map to corresponding extrema on the new line, while manual anchors use nearest-path projection.
 *  - [LineGhost]: which line of the previous / next keyframe is the one to trace next.
 */
object AutoAnchors {
    /** Hard safety ceiling; normal anchor count is determined by detected shape features. */
    const val MAX_AUTO_TIPS = 24

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
        for (s in strokes) for (p in s.points) {
            if (p.anchor > 0) out.add(p.anchor)
            out.addAll(p.endpointAliases.filter { it > 0 })
        }
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
     * Adaptive feature-aware anchor detection. Open strokes always include their two
     * structural endpoints. Interior anchors prefer the apex (maximum chord deviation)
     * of each curve lobe, with separate sharp-corner anchors. Inflections split S-curves
     * into lobes; they are not themselves treated as extrema.
     * The count is therefore driven by the shape, not a fixed per-line quota. [maxTips]
     * is only a safety ceiling (24 by default); [sens] 0..1 controls how subtle a feature
     * may be before it is retained.
     */
    fun targets(pts: List<VectorPoint>, closed: Boolean, sens: Float, maxTips: Int = MAX_AUTO_TIPS): List<Int> {
        val n = pts.size
        if (n < 2) return emptyList()
        val ring = closed && n >= 3
        val m = if (ring) n + 1 else n
        val xs = FloatArray(m) { pts[it % n].x }
        val ys = FloatArray(m) { pts[it % n].y }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[m - 1]
        if (total < 1e-3f) return if (ring) listOf(0) else listOf(0, n - 1)

        // Resample by distance so detection behaves consistently at different point
        // densities and stylus sampling rates. Cap work for exceptionally long strokes.
        val step = (total / 180f).coerceIn(2f, 6f)
        val cnt = (total / step).roundToInt().coerceIn(11, 512) + 1
        val seg = total / (cnt - 1)
        val sx = FloatArray(cnt)
        val sy = FloatArray(cnt)
        var j = 0
        for (i in 0 until cnt) {
            val distance = seg * i
            while (j < m - 2 && cum[j + 1] < distance) j++
            val length = cum[j + 1] - cum[j]
            val t = if (length < 1e-6f) 0f else ((distance - cum[j]) / length).coerceIn(0f, 1f)
            sx[i] = xs[j] + (xs[j + 1] - xs[j]) * t
            sy[i] = ys[j] + (ys[j + 1] - ys[j]) * t
        }
        // A light smoothing pass rejects hand jitter without erasing real corners.
        repeat(2) {
            var px = sx[0]; var py = sy[0]
            for (i in 1 until cnt - 1) {
                val cx = sx[i]; val cy = sy[i]
                sx[i] = 0.25f * px + 0.5f * cx + 0.25f * sx[i + 1]
                sy[i] = 0.25f * py + 0.5f * cy + 0.25f * sy[i + 1]
                px = cx; py = cy
            }
        }

        val maxInterior = maxTips.coerceIn(0, MAX_AUTO_TIPS)
        val idx = sortedSetOf<Int>()
        if (!ring) { idx.add(0); idx.add(n - 1) }
        if (maxInterior == 0) return idx.toList()

        // Measure signed turning over a short distance window. Absolute turning finds
        // corners and curvature peaks; the sign is also used to detect inflections.
        val window = (total * 0.035f).coerceIn(5f, 28f)
        val k = max(2, (window / seg).roundToInt())
        fun at(q: Int): Int = if (ring) ((q % (cnt - 1)) + (cnt - 1)) % (cnt - 1) else q
        val lo = if (ring) 0 else k
        val hi = if (ring) cnt - 1 else cnt - k
        val signed = FloatArray(cnt)
        val magnitude = FloatArray(cnt)
        for (i in lo until hi) {
            val a = at(i - k); val b = at(i); val c = at(i + k)
            val v1x = sx[b] - sx[a]; val v1y = sy[b] - sy[a]
            val v2x = sx[c] - sx[b]; val v2y = sy[c] - sy[b]
            val turn = Math.toDegrees(atan2(
                (v1x * v2y - v1y * v2x).toDouble(),
                (v1x * v2x + v1y * v2y).toDouble()
            )).toFloat()
            signed[i] = turn
            magnitude[i] = abs(turn)
        }

        val threshold = 12f - 10f * sens.coerceIn(0f, 1f)
        val features = HashMap<Int, Float>()
        val neighbourhood = max(2, k / 2)

        // A curve anchor belongs at the apex of a curve lobe (the point furthest from
        // the chord joining that lobe's boundaries), not merely at the strongest local
        // curvature sample. First find inflections to split S-curves into individual
        // lobes. Inflections are segmentation boundaries, not anchors themselves.
        val side = max(2, k * 2)
        val inflections = ArrayList<Int>()
        for (i in (lo + side) until (hi - side)) {
            val before = signed[i - side]
            val after = signed[i + side]
            if (before * after < 0f && abs(before) >= threshold * 0.18f && abs(after) >= threshold * 0.18f) {
                inflections.add(i)
            }
        }
        val boundaries = (listOf(lo) + inflections + hi).distinct().sorted()
        val apexProminence = max(1.5f, bboxDiag(pts) * 0.008f)
        for (bi in 0 until boundaries.lastIndex) {
            val first = boundaries[bi]
            val last = boundaries[bi + 1]
            if (last - first < max(5, k * 2)) continue
            val ax = sx[first]; val ay = sy[first]
            val dx = sx[last] - ax; val dy = sy[last] - ay
            val chord = hypot(dx, dy)
            if (chord < 1e-3f) continue
            var bestSample = -1
            var bestDeviation = 0f
            for (q in (first + 1) until last) {
                val deviation = abs((sx[q] - ax) * dy - (sy[q] - ay) * dx) / chord
                if (deviation > bestDeviation) { bestDeviation = deviation; bestSample = q }
            }
            if (bestSample >= 0 && bestDeviation >= apexProminence) {
                // Apexes outrank incidental curvature noise so each lobe gets its
                // semantically meaningful extreme point whenever the spacing allows.
                features[bestSample] = max(features[bestSample] ?: 0f, 100f + bestDeviation)
            }
        }

        // Keep genuine sharp corners as a separate feature class. Smooth curvature
        // maxima are deliberately not used: their position can slide along an arc as
        // the shape changes, whereas the lobe apex above is geometrically stable.
        val cornerThreshold = max(28f, threshold * 2.5f)
        for (i in lo until hi) {
            val value = magnitude[i]
            if (value < cornerThreshold) continue
            var localMax = value
            for (d in 1..neighbourhood) {
                localMax = max(localMax, max(magnitude[at(i - d)], magnitude[at(i + d)]))
            }
            if (value >= localMax - 0.25f) {
                features[i] = max(features[i] ?: 0f, 200f + value)
            }
        }

        if (features.isEmpty() && !ring) {
            // A simple circular arc has nearly constant curvature, so it has no local
            // curvature peak. If the whole stroke still turns meaningfully, give it a
            // single midpoint correspondence rather than leaving the curve unanchored.
            val peak = (lo until hi).maxOfOrNull { magnitude[it] } ?: 0f
            if (peak >= threshold * 0.75f) features[(lo + hi) / 2] = peak
        }
        if (features.isEmpty()) {
            if (ring) { idx.add(0); idx.add(n / 2) }
            return idx.toList()
        }

        // Separation scales with path length and the analysis window. This lets a
        // complex contour receive more anchors than a simple curve, up to the safety cap.
        val separation = max(k, (total * 0.045f / seg).roundToInt())
        val chosen = ArrayList<Int>()
        val ranked = features.entries.sortedByDescending { it.value }
        for ((sample, _) in ranked) {
            if (chosen.size >= maxInterior) break
            if (!ring && (sample <= separation * 0.55f || cnt - 1 - sample <= separation * 0.55f)) continue
            val spaced = chosen.none { other ->
                val distance = abs(other - sample)
                (if (ring) min(distance, cnt - 1 - distance) else distance) < separation
            }
            if (spaced) chosen.add(sample)
        }

        fun nearestVertex(distance: Float): Int {
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for (q in 0 until n) {
                val d = abs(cum[q] - distance)
                if (d < bestDistance) { bestDistance = d; best = q }
            }
            return best
        }
        for (sample in chosen) {
            val target = nearestVertex(seg * sample)
            if (ring || (target != 0 && target != n - 1)) idx.add(target)
        }
        if (ring) {
            if (idx.isEmpty()) { idx.add(0); idx.add(nearestVertex(total / 2f)) }
            else if (idx.size == 1) { val first = idx.first(); idx.add(nearestVertex((cum[first] + total / 2f) % total)) }
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
            used.add(next)
            val endpoint = !s.isClosed && (i == 0 || i == pts.lastIndex)
            pts[i] = pts[i].copy(anchor = next, auto = true, endpoint = endpoint, endpointAliases = emptyList())
            next++
        }
        return s.copy(points = pts)
    }

    /**
     * After lines changed shape (merged, points deleted): the automatic anchors of every line that has some are redone. Open-line endpoints and detected curve tips are targets. The numbers of points that are still ends or tips are
     * kept within their own class: endpoint identities only go to outer endpoints, and automatic feature identities only go to interior targets. New numbers avoid both this keyframe and [avoid]. Returns [strokes] itself when nothing changed.
     */
    fun refresh(strokes: List<VectorStroke>, avoid: Set<Int>, sens: Float, only: Set<String>? = null): List<VectorStroke> {
        if (strokes.none { s -> s.points.any { (it.auto && it.anchor > 0) || it.endpointAliases.isNotEmpty() } }) return strokes
        val count = IntArray(ANCHOR_MAX + 2)
        for (s in strokes) for (p in s.points) {
            if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]++
            for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) count[r]++
        }
        data class OldAnchor(val x: Float, val y: Float, val rank: Int, val endpoint: Boolean)
        val out = ArrayList<VectorStroke>(strokes.size)
        var changed = false
        for (s in strokes) {
            if (s.points.none { (it.auto && it.anchor > 0) || it.endpointAliases.isNotEmpty() } || (only != null && s.id !in only)) {
                out.add(s); continue
            }
            val old = ArrayList<OldAnchor>()
            for ((pi, p) in s.points.withIndex()) {
                if (p.auto && p.anchor in 1..ANCHOR_MAX) {
                    // Legacy auto anchors at the actual first/last point are structural
                    // endpoints even if they predate the explicit endpoint marker.
                    val isEndpoint = p.endpoint || (!s.isClosed && (pi == 0 || pi == s.points.lastIndex))
                    old.add(OldAnchor(p.x, p.y, p.anchor, isEndpoint))
                }
                for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) old.add(OldAnchor(p.x, p.y, r, true))
            }
            for (p in s.points) {
                if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]--
                for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) count[r]--
            }
            val cleared = s.points.map { p ->
                if (p.auto) p.copy(anchor = 0, auto = false, endpoint = false, endpointAliases = emptyList())
                else if (p.endpointAliases.isNotEmpty()) p.copy(endpointAliases = emptyList())
                else p
            }
            // Existing identities used elsewhere in this cel and non-auto anchors on
            // this line are unavailable. Neighbour-frame numbers are deliberately not
            // blockers when reusing an existing correspondence identity.
            val claimed = HashSet<Int>()
            for (q in 1..ANCHOR_MAX) if (count[q] > 0) claimed.add(q)
            for (p in cleared) if (p.anchor > 0) claimed.add(p.anchor)
            val pts = cleared.toMutableList()
            val targets = targets(cleared, s.isClosed, sens)
            // A correspondence anchor inherited from a ghost (or placed manually) is
            // already a deliberate feature match. Do not create a second automatic
            // anchor immediately beside it just because the local geometry also peaks.
            val protected = cleared.withIndex().filter { it.value.anchor > 0 && !it.value.auto }
            val featureSpacing = max(5f, pathLength(cleared, s.isClosed) * 0.025f)
            val endpointTargets = if (!s.isClosed && cleared.size >= 2) listOf(0, cleared.lastIndex).filter { pts[it].anchor == 0 } else emptyList()
            val endpointPool = old.filter { it.endpoint && it.rank !in claimed }.distinctBy { it.rank }
            val tipPool = old.filter { !it.endpoint && it.rank !in claimed }.distinctBy { it.rank }.toMutableList()

            // Structural endpoint identities can only be assigned to the two outer
            // endpoints. Prefer identities shared with the neighbouring keyframes;
            // freshly generated endpoints from an unrelated merged stroke must not
            // replace the ghost's endpoint pair. Choose the pair jointly so direction
            // reversals and asymmetric extensions do not swap the two identities.
            if (endpointTargets.size == 2 && endpointPool.size >= 2) {
                val a = endpointTargets[0]; val b = endpointTargets[1]
                var bestA: OldAnchor? = null; var bestB: OldAnchor? = null; var bestCost = Double.POSITIVE_INFINITY
                for (x in endpointPool) for (y in endpointPool) {
                    if (x.rank == y.rank) continue
                    val priority = (if (x.rank in avoid) 0.0 else 1_000_000.0) + (if (y.rank in avoid) 0.0 else 1_000_000.0)
                    val cost = priority + hypot(x.x - pts[a].x, x.y - pts[a].y).toDouble() + hypot(y.x - pts[b].x, y.y - pts[b].y).toDouble()
                    if (cost < bestCost) { bestCost = cost; bestA = x; bestB = y }
                }
                if (bestA != null && bestB != null) {
                    pts[a] = pts[a].copy(anchor = bestA.rank, auto = true, endpoint = true, endpointAliases = emptyList()); claimed.add(bestA.rank)
                    pts[b] = pts[b].copy(anchor = bestB.rank, auto = true, endpoint = true, endpointAliases = emptyList()); claimed.add(bestB.rank)
                }
            } else if (endpointTargets.isNotEmpty() && endpointPool.isNotEmpty()) {
                val target = endpointTargets.minByOrNull { ti -> endpointPool.minOf { (if (it.rank in avoid) 0f else 1_000_000f) + hypot(it.x - pts[ti].x, it.y - pts[ti].y) } }!!
                val pick = endpointPool.minByOrNull { (if (it.rank in avoid) 0f else 1_000_000f) + hypot(it.x - pts[target].x, it.y - pts[target].y) }
                if (pick != null) { pts[target] = pts[target].copy(anchor = pick.rank, auto = true, endpoint = true, endpointAliases = emptyList()); claimed.add(pick.rank) }
            }

            // Automatic feature identities are a separate pool: they can only populate
            // intermediate targets and can never take either endpoint's number.
            val interiorTargets = (if (s.isClosed) targets else targets.filter { it != 0 && it != pts.lastIndex })
                .filter { i -> pts[i].anchor == 0 && protected.none { q -> hypot(q.value.x - pts[i].x, q.value.y - pts[i].y) < featureSpacing } }
            val tol = max(3f, pathLength(cleared, s.isClosed) * 0.03f)
            val unmatched = ArrayList<Int>()
            for (i in interiorTargets) {
                if (pts[i].anchor > 0) continue
                val candidates = tipPool.filter { it.rank !in claimed }
                val preferred = candidates.filter { it.rank in avoid }.ifEmpty { candidates }
                val near = preferred.minByOrNull { hypot(it.x - pts[i].x, it.y - pts[i].y) }
                    ?.takeIf { hypot(it.x - pts[i].x, it.y - pts[i].y) <= tol }
                if (near != null) {
                    tipPool.remove(near); claimed.add(near.rank)
                    pts[i] = pts[i].copy(anchor = near.rank, auto = true, endpoint = false)
                } else unmatched.add(i)
            }
            for (i in unmatched) {
                val available = tipPool.filter { it.rank !in claimed }
                val heirs = available.filter { it.rank in avoid }.ifEmpty { available }
                val heir = heirs.minByOrNull { hypot(it.x - pts[i].x, it.y - pts[i].y) } ?: continue
                tipPool.remove(heir); claimed.add(heir.rank)
                pts[i] = pts[i].copy(anchor = heir.rank, auto = true, endpoint = false)
            }
            for (i in unmatched) {
                if (pts[i].anchor > 0) continue
                val next = nextFree(claimed, avoid)
                if (next > ANCHOR_MAX) continue
                claimed.add(next); pts[i] = pts[i].copy(anchor = next, auto = true, endpoint = false)
            }
            for (p in pts) {
                if (p.anchor in 1..ANCHOR_MAX) count[p.anchor]++
                for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) count[r]++
            }
            if (pts == s.points) out.add(s) else { out.add(s.copy(points = pts)); changed = true }
        }
        return if (changed) out else strokes
    }

    private fun nextFree(claimed: Set<Int>, avoid: Set<Int>): Int {
        var n = 1
        while (n <= ANCHOR_MAX && (n in claimed || n in avoid)) n++
        return n
    }

    /** True when both ends of an open line carry an anchor (closed lines and points have no ends). */
    fun endsAnchored(s: VectorStroke): Boolean = s.isClosed || s.points.size < 2 || (s.points.first().anchor > 0 && s.points.last().anchor > 0)

    /** The two end points of an open line always carry a structural endpoint anchor: a missing one is added on the exact end point, with a number not in [taken]. */
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
            used.add(next); pts[i] = pts[i].copy(anchor = next, auto = true, endpoint = true, endpointAliases = emptyList())
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
     * Traces: [s] (a line just drawn) takes the anchor numbers of the ghost line [g] it was drawn over, except numbers in [blocked] (already used in this keyframe). Automatically detected ghost extrema map to the corresponding detected extrema on [s] by ordered path progress; manual anchors use nearest-path projection. The same order is retained along the line (a line drawn the other way round is read backwards; the ends of the ghost
     * land on the ends of [s]); when the line has no point there, one is inserted. These anchors are not automatic: they say which point matches which, so they are never redone.
     * Null when the ghost carries no usable anchor.
     */
    fun inherit(s: VectorStroke, g: VectorStroke, blocked: Set<Int>, sens: Float = 0.5f): VectorStroke? {
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
        // Classify geometric overlap using every reference anchor, not only the
        // currently-unblocked subset. If earlier traced pieces have claimed anchors
        // outside this stroke, testing only [ga] can falsely classify a partial trace
        // as a complete, near-aligned trace and stretch the remaining anchors over
        // the whole new stroke by normalized path progress.
        val allGhostAnchors = g.points.withIndex().filter { it.value.anchor > 0 }
        val allNear = allGhostAnchors.isNotEmpty() && allGhostAnchors.all {
            distToLine(it.value.x, it.value.y) <= nearTol
        }
        // The ghost's automatically detected interior anchors represent curve-lobe
        // extrema. When tracing, map those identities to the corresponding extrema
        // detected on the new stroke by ordered path progress, rather than to whichever
        // sample happens to be spatially closest to the old ghost coordinate. This is
        // what keeps an apex at the apex even when the artist's trace is slightly offset.
        val tracedFeatureVertices = targets(s.points, s.isClosed, sens)
            .filter { sRing || (it != 0 && it != n - 1) }
        val tracedFeatureDistances = tracedFeatureVertices.map { cum[it] }.sorted()
        val claimedFeatureDistances = HashSet<Float>()
        val partTol = 0.05f * diag + 8f
        val onGhost = s.points.all { distToPoly(it.x, it.y, g.points) <= partTol }
        // A ghost can already have some of its anchor numbers in use in this keyframe
        // (for example, an earlier piece of the same traced line). The remaining free
        // anchors still define exact correspondences. Do not require every ghost anchor
        // to be free before mapping those that remain by their relative position.
        val mode = when { !mono || allNear -> 1; onGhost -> 2; ga.isNotEmpty() -> 3; else -> return null }
        val useNearest = mode != 3
        fun nearestProgress(ax: Float, ay: Float, lowerBound: Float): Float {
            var best = Float.MAX_VALUE; var bp = -1f
            for (j in 0 until m - 1) {
                if (mono && cum[j + 1] < lowerBound - gap * 0.5f) continue
                val l = cum[j + 1] - cum[j]
                val dx = xs[j + 1] - xs[j]; val dy = ys[j + 1] - ys[j]
                val t = if (l < 1e-6f) 0f else (((ax - xs[j]) * dx + (ay - ys[j]) * dy) / (l * l)).coerceIn(0f, 1f)
                var pp = cum[j] + t * l
                if (mono && pp < lowerBound) pp = lowerBound
                val tt = if (l < 1e-6f) 0f else ((pp - cum[j]) / l).coerceIn(0f, 1f)
                val dd = hypot(ax - (xs[j] + dx * tt), ay - (ys[j] + dy * tt))
                if (dd < best) { best = dd; bp = pp }
            }
            return bp
        }
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
                val isCurveFeature = a.value.auto && !a.value.endpoint && !atStart && !atEnd
                val ghostU = (gcum[gi] / gtotal).let { if (reversed) 1f - it else it }
                // In both spatially-aligned modes, project onto the new path. Mode 1
                // means the complete reference contour is near this trace; mode 2
                // means this is a partial trace of the reference. Using raw ghostU in
                // mode 1 assumes identical path lengths and point progress, which
                // shifts apex anchors whenever the artist's trace stretches/compresses
                // a curve even though it lies directly over the ghost.
                val projected = if (mode != 3) nearestProgress(a.value.x, a.value.y, last + gap) else -1f
                val featureU = if (projected >= 0f) projected / total else ghostU
                val featureMatch = if (isCurveFeature && tracedFeatureDistances.isNotEmpty()) {
                    tracedFeatureDistances.asSequence()
                        .filter { it !in claimedFeatureDistances && it >= last + gap }
                        .minByOrNull { abs(it / total - featureU) }
                } else null
                if (featureMatch != null) {
                    p = featureMatch
                    claimedFeatureDistances.add(featureMatch)
                } else {
                    val bp = if (projected >= 0f) projected else nearestProgress(a.value.x, a.value.y, last + gap)
                    p = if (!useNearest || bp < 0f) ghostU * total else bp
                }
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
            out.add(vertexRank[j]?.let { base.copy(anchor = it, auto = it in structuralRanks, endpoint = it in structuralRanks, endpointAliases = emptyList()) } ?: base)
            inserts[j]?.sortedBy { it.first }?.forEach { (p, r) ->
                val l = cum[j + 1] - cum[j]
                val t = if (l < 1e-6f) 0f else ((p - cum[j]) / l).coerceIn(0f, 1f)
                val a = s.points[j]; val b = s.points[(j + 1) % n]
                out.add(VectorPoint(x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t, pressure = a.pressure + (b.pressure - a.pressure) * t, elevationOffset = a.elevationOffset + (b.elevationOffset - a.elevationOffset) * t, anchor = r, auto = r in structuralRanks, endpoint = r in structuralRanks))
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
