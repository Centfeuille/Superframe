package com.example.inbetween

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Automatic anchors for vector lines (rewritten 2026-11-22).
 *
 * ONE MODEL. Every automatic anchor is one of two roles, and both are DYNAMIC (redone whenever the line changes shape):
 *   - an END: the first and the last point of an open line (marked [VectorPoint.auto] + [VectorPoint.endpoint]);
 *   - a TIP: the apex of a bend of the line (marked [VectorPoint.auto] only). Closed lines have tips only.
 * Anchors placed by hand (auto = false) are never touched and keep automatic tips away from them.
 *
 * Every layout goes through [layout], which is driven by a [Plan]: which number the first end gets, which the last end gets, and an ordered list of numbered
 * tip positions (as 0..1 progress along the line) that the detected apexes are paired with, in order. Three ways to get a plan:
 *   - a new line: no numbers yet, so fresh ones are allocated in path order ([assignFresh]);
 *   - a line traced over a ghost line ([inherit]): the two ends take the ghost's end numbers, the start of the new line decides which one is which,
 *     and the apexes take the ghost's in-between numbers by position (progress and distance from the nearest end) and bend (side and sharpness) with
 *     an ordered alignment ([matchDescs]); a ghost number with no acceptable apex is dropped, and an apex no ghost number fits gets a new number;
 *   - a line that changed shape (merged, points deleted; [refresh]): its numbers are kept. A traced line keeps following its ghost: the end where the line
 *     started stays put, the other end moves to wherever the line now ends, and the apexes are detected again on the whole line.
 */
object AutoAnchors {
    /** Hard safety ceiling for the number of tips on one line. */
    const val MAX_AUTO_TIPS = 24

    /**
     * A closed line that KEPT both of its end points: a line whose end came back to its own start and was closed by Merge ([VectorMerge]) no longer collapses the two ends into one
     * point or moves them; its first and last point stay where they were drawn, close to each other, still carrying their end anchors. Such a line is numbered, traced and followed
     * exactly like an open line whose ends happen to meet: [layout], [selfPlan] and [ghostPlan] work on an open copy of it and close it again.
     */
    fun seamEnds(s: VectorStroke): Boolean {
        if (!s.isClosed || s.fill != 0 || s.points.size < 3) return false
        val a = s.points.first(); val b = s.points.last()
        return a.endpoint && b.endpoint && a.anchor in 1..ANCHOR_MAX && b.anchor in 1..ANCHOR_MAX
    }
    private fun asOpen(s: VectorStroke): VectorStroke = if (seamEnds(s)) s.copy(isClosed = false) else s

    fun bboxDiag(pts: List<VectorPoint>): Float {
        if (pts.isEmpty()) return 0f
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in pts) { l = min(l, p.x); r = max(r, p.x); t = min(t, p.y); b = max(b, p.y) }
        return hypot(r - l, b - t)
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

    /** The lines of the previous and the next keyframe (the one on the [nextFirst] side first): the candidates a traced line can still be following. */
    fun referenceLines(layer: Layer, idx: Int, nextFirst: Boolean, ed: Editor? = null): List<VectorStroke> {
        val out = ArrayList<VectorStroke>()
        LineGhost.reference(layer, idx, nextFirst, ed)?.let { out.addAll(LineGhost.lines(it)) }
        LineGhost.reference(layer, idx, !nextFirst, ed)?.let { out.addAll(LineGhost.lines(it)) }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------
    // Geometry: the line as it is DRAWN (the smooth curve through its points), with arc-length progress.
    // ---------------------------------------------------------------------------------------------------------------------------------------------

    private class Dense(val xs: FloatArray, val ys: FloatArray, val cum: FloatArray, val total: Float, val ring: Boolean)

    private fun denseOf(pts: List<VectorPoint>, ring: Boolean): Dense? {
        if (pts.size < 2) return null
        var xs = FloatArray(pts.size) { pts[it].x }
        var ys = FloatArray(pts.size) { pts[it].y }
        if (pts.size >= 3) {
            val l = VectorTessellator.centerline(pts, ring)
            var cnt = l.size / 2
            if (cnt >= 2) {
                if (ring && cnt >= 3 && hypot(l[0] - l[(cnt - 1) * 2], l[1] - l[(cnt - 1) * 2 + 1]) < 1e-3f) cnt--
                xs = FloatArray(cnt) { l[it * 2] }
                ys = FloatArray(cnt) { l[it * 2 + 1] }
            }
        }
        val n = xs.size
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val closing = if (ring && n >= 3) hypot(xs[0] - xs[n - 1], ys[0] - ys[n - 1]) else 0f
        val total = cum[n - 1] + closing
        if (total < 1e-3f) return null
        return Dense(xs, ys, cum, total, ring && n >= 3)
    }

    /** The point at progress [u] (0..1 of the length) of [d], written to out[0], out[1]. */
    private fun pointAt(d: Dense, u: Float, out: FloatArray) {
        val n = d.xs.size
        val arc = u.coerceIn(0f, 1f) * d.total
        val lastCum = d.cum[n - 1]
        if (arc > lastCum && d.ring) {
            val l = d.total - lastCum
            val t = if (l < 1e-6f) 0f else ((arc - lastCum) / l).coerceIn(0f, 1f)
            out[0] = d.xs[n - 1] + (d.xs[0] - d.xs[n - 1]) * t
            out[1] = d.ys[n - 1] + (d.ys[0] - d.ys[n - 1]) * t
            return
        }
        var j = 0
        while (j < n - 2 && d.cum[j + 1] < arc) j++
        val l = d.cum[j + 1] - d.cum[j]
        val t = if (l < 1e-6f) 0f else ((arc - d.cum[j]) / l).coerceIn(0f, 1f)
        out[0] = d.xs[j] + (d.xs[j + 1] - d.xs[j]) * t
        out[1] = d.ys[j] + (d.ys[j + 1] - d.ys[j]) * t
    }

    /** The nearest point of [d] to (x, y): out[0] = its progress (0..1), out[1] = the distance. */
    private fun project(d: Dense, x: Float, y: Float, out: FloatArray) {
        val n = d.xs.size
        var best = Float.MAX_VALUE
        var bestArc = 0f
        val segs = if (d.ring) n else n - 1
        for (j in 0 until segs) {
            val k = (j + 1) % n
            val dx = d.xs[k] - d.xs[j]; val dy = d.ys[k] - d.ys[j]
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9f) 0f else (((x - d.xs[j]) * dx + (y - d.ys[j]) * dy) / l2).coerceIn(0f, 1f)
            val dist = hypot(x - (d.xs[j] + dx * t), y - (d.ys[j] + dy * t))
            if (dist < best) { best = dist; bestArc = d.cum[j] + t * sqrt(l2) }
        }
        out[0] = (bestArc / d.total).coerceIn(0f, 1f)
        out[1] = best
    }

    /**
     * The apexes of the bends of a line, as progress values (0..1, ascending), without the two end points of an open line. A bend's anchor sits at the point
     * furthest from the chord that joins the bend's boundaries (inflections split S-curves into bends); sharp corners are a separate kind. [sens] 0..1: how gentle
     * a bend may be and still count (0 = sharp corners only). The count follows the shape (a straight line has none), up to [MAX_AUTO_TIPS].
     */
    private fun features(d: Dense, ring: Boolean, sens: Float): FloatArray {
        val n = d.xs.size
        if (n < 4) return FloatArray(0)
        val s01 = sens.coerceIn(0f, 1f)
        val m = if (ring) n + 1 else n
        val xs = FloatArray(m) { d.xs[it % n] }
        val ys = FloatArray(m) { d.ys[it % n] }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[m - 1]
        if (total < 1e-3f) return FloatArray(0)

        // Resample by distance so detection behaves the same at any point density.
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
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until cnt) { minX = min(minX, sx[i]); maxX = max(maxX, sx[i]); minY = min(minY, sy[i]); maxY = max(maxY, sy[i]) }
        val diag = hypot(maxX - minX, maxY - minY)

        val window = (total * 0.035f).coerceIn(5f, 28f)
        val k = max(2, (window / seg).roundToInt())
        fun at(q: Int): Int = if (ring) ((q % (cnt - 1)) + (cnt - 1)) % (cnt - 1) else q
        val lo = if (ring) 0 else k
        val hi = if (ring) cnt - 1 else cnt - k
        if (hi - lo < 4) return FloatArray(0)
        val signed = FloatArray(cnt)
        val magnitude = FloatArray(cnt)
        for (i in lo until hi) {
            val a = at(i - k); val b = at(i); val c = at(i + k)
            val v1x = sx[b] - sx[a]; val v1y = sy[b] - sy[a]
            val v2x = sx[c] - sx[b]; val v2y = sy[c] - sy[b]
            val turn = Math.toDegrees(atan2((v1x * v2y - v1y * v2x).toDouble(), (v1x * v2x + v1y * v2y).toDouble())).toFloat()
            signed[i] = turn
            magnitude[i] = abs(turn)
        }

        val threshold = 12f - 10f * s01
        val found = HashMap<Int, Float>()
        val neighbourhood = max(2, k / 2)

        // Bend apexes. Inflections cut an S-curve into single bends; a bend's apex is its point of maximum deviation from the bend's chord.
        if (s01 >= 0.01f) {
            val side = max(2, k * 2)
            val inflections = ArrayList<Int>()
            for (i in (lo + side) until (hi - side)) {
                val before = signed[i - side]
                val after = signed[i + side]
                if (before * after < 0f && abs(before) >= threshold * 0.18f && abs(after) >= threshold * 0.18f) inflections.add(i)
            }
            val boundaries = (listOf(lo) + inflections + hi).distinct().sorted()
            val prominence = max(1.5f, diag * (0.014f - 0.012f * s01))
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
                if (bestSample >= 0 && bestDeviation >= prominence) found[bestSample] = max(found[bestSample] ?: 0f, 100f + bestDeviation)
            }
        }
        // Sharp corners: local maxima of the turning angle above a threshold.
        val cornerThreshold = max(28f, threshold * 2.5f)
        for (i in lo until hi) {
            val value = magnitude[i]
            if (value < cornerThreshold) continue
            var localMax = value
            for (dd in 1..neighbourhood) localMax = max(localMax, max(magnitude[at(i - dd)], magnitude[at(i + dd)]))
            if (value >= localMax - 0.25f) found[i] = max(found[i] ?: 0f, 200f + value)
        }
        if (found.isEmpty()) return FloatArray(0)

        // Spacing scales with length and the analysis window, so a complex contour gets more tips than a simple curve.
        val separation = max(k, (total * 0.045f / seg).roundToInt())
        val chosen = ArrayList<Int>()
        for ((sample, _) in found.entries.sortedByDescending { it.value }) {
            if (chosen.size >= MAX_AUTO_TIPS) break
            if (!ring && (sample <= separation * 0.55f || cnt - 1 - sample <= separation * 0.55f)) continue
            val spaced = chosen.none { other ->
                val distance = abs(other - sample)
                (if (ring) min(distance, cnt - 1 - distance) else distance) < separation
            }
            if (spaced) chosen.add(sample)
        }
        chosen.sort()
        return FloatArray(chosen.size) { chosen[it].toFloat() / (cnt - 1) }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------
    // Pairing detected apexes with numbered positions, keeping the order along the line.
    // ---------------------------------------------------------------------------------------------------------------------------------------------

    /** For every element of [a] (ascending), the index of the element of [b] (ascending) it is paired with, or -1. Equal counts pair in order; otherwise an ordered alignment of least total distance. */
    private fun matchOrdered(a: FloatArray, b: FloatArray): IntArray {
        val out = IntArray(a.size) { -1 }
        if (a.isEmpty() || b.isEmpty()) return out
        if (a.size == b.size) { for (i in a.indices) out[i] = i; return out }
        val skip = 0.15f
        val na = a.size; val nb = b.size
        val cost = Array(na + 1) { FloatArray(nb + 1) }
        for (i in 1..na) cost[i][0] = i * skip
        for (j in 1..nb) cost[0][j] = j * skip
        for (i in 1..na) for (j in 1..nb) {
            val both = cost[i - 1][j - 1] + abs(a[i - 1] - b[j - 1])
            cost[i][j] = min(both, min(cost[i - 1][j], cost[i][j - 1]) + skip)
        }
        var i = na; var j = nb
        while (i > 0 && j > 0) {
            val both = cost[i - 1][j - 1] + abs(a[i - 1] - b[j - 1])
            if (cost[i][j] == both) { out[i - 1] = j - 1; i--; j-- }
            else if (cost[i][j] == cost[i - 1][j] + skip) i--
            else j--
        }
        return out
    }

    /** An apex / ghost tip for [matchDescs]: [u] = position (0..1, along the line in the direction of the new line), [turn] = signed bend in degrees (0 = none / unknown). */
    private class Desc(val u: Float, val turn: Float)

    /** Smallest bend (degrees) whose direction counts as known. */
    private const val BEND_MIN = 6f
    /** A pair costing more than this is refused (an opposite bend alone costs 0.5, so a left bend never takes a right bend's number). */
    private const val PAIR_MAX = 0.45f
    /** Cost of leaving one apex or one ghost tip unpaired (two skips = 0.5 > [PAIR_MAX], so any allowed pair beats skipping both). */
    private const val PAIR_SKIP = 0.25f

    /**
     * Signed bend (degrees) of the dense curve [d] at progress [u]: the turn between the chord arriving from a short way before and the chord leaving to a short way after
     * (the same window the apex detection uses). 0 when there is no room on either side (an end of an open line).
     */
    private fun bendAt(d: Dense, u: Float): Float {
        if (d.total < 1e-3f) return 0f
        val w = (d.total * 0.035f).coerceIn(5f, 28f) / d.total
        val ua: Float; val ub: Float
        if (d.ring) { ua = (((u - w) % 1f) + 1f) % 1f; ub = (((u + w) % 1f) + 1f) % 1f }
        else { ua = (u - w).coerceAtLeast(0f); ub = (u + w).coerceAtMost(1f); if (u - ua < 1e-4f || ub - u < 1e-4f) return 0f }
        val a = FloatArray(2); val b = FloatArray(2); val c = FloatArray(2)
        pointAt(d, ua, a); pointAt(d, u, b); pointAt(d, ub, c)
        val v1x = b[0] - a[0]; val v1y = b[1] - a[1]
        val v2x = c[0] - b[0]; val v2y = c[1] - b[1]
        if (hypot(v1x, v1y) < 1e-3f || hypot(v2x, v2y) < 1e-3f) return 0f
        return Math.toDegrees(atan2((v1x * v2y - v1y * v2x).toDouble(), (v1x * v2x + v1y * v2y).toDouble())).toFloat()
    }

    /**
     * Cost of letting apex [a] take the number of ghost tip [b] (both positions in the same 0..1 space). Position: half the plain difference of progress, half the difference of the
     * distance from the NEAREST END (a bend close to one end stays close to that end when the middle of the line is stretched; a bend on the other half of the line only gets the plain
     * difference). Shape: when both bends are clear, opposite directions add 0.5 (refused), otherwise up to 0.1 for a different sharpness.
     */
    private fun pairCost(a: Desc, b: Desc): Float {
        val du = abs(a.u - b.u)
        val sameHalf = (a.u < 0.5f) == (b.u < 0.5f)
        val de = abs(min(a.u, 1f - a.u) - min(b.u, 1f - b.u))
        var c = 0.5f * du + 0.5f * (if (sameHalf) de else du)
        if (abs(a.turn) >= BEND_MIN && abs(b.turn) >= BEND_MIN) {
            c += if ((a.turn > 0f) != (b.turn > 0f)) 0.5f else 0.1f * min(abs(abs(a.turn) - abs(b.turn)) / 90f, 1f)
        }
        return c
    }

    /**
     * Ordered pairing of detected apexes [a] with ghost tips [b] (both ascending by position): the alignment of least total cost, where a pair costs [pairCost] and is only allowed
     * up to [PAIR_MAX], and an unpaired apex or tip costs [PAIR_SKIP]. Unlike [matchOrdered], equal counts are not paired blindly. For each apex the index of its tip, or -1.
     */
    private fun matchDescs(a: List<Desc>, b: List<Desc>): IntArray {
        val na = a.size; val nb = b.size
        val out = IntArray(na) { -1 }
        if (na == 0 || nb == 0) return out
        val cost = Array(na + 1) { FloatArray(nb + 1) }
        val from = Array(na + 1) { ByteArray(nb + 1) } // 1 = pair, 2 = skip an apex, 3 = skip a tip
        for (i in 1..na) { cost[i][0] = i * PAIR_SKIP; from[i][0] = 2 }
        for (j in 1..nb) { cost[0][j] = j * PAIR_SKIP; from[0][j] = 3 }
        for (i in 1..na) for (j in 1..nb) {
            var best = cost[i - 1][j] + PAIR_SKIP; var how: Byte = 2
            val tip = cost[i][j - 1] + PAIR_SKIP
            if (tip < best) { best = tip; how = 3 }
            val pc = pairCost(a[i - 1], b[j - 1])
            if (pc <= PAIR_MAX) { val both = cost[i - 1][j - 1] + pc; if (both <= best) { best = both; how = 1 } }
            cost[i][j] = best; from[i][j] = how
        }
        var i = na; var j = nb
        while (i > 0 || j > 0) {
            when (from[i][j].toInt()) {
                1 -> { out[i - 1] = j - 1; i--; j-- }
                2 -> i--
                else -> j--
            }
        }
        return out
    }

    /** [matchOrdered] for closed lines, where progress wraps round: the best turn of [b] onto [a], then nearest unique pairs. */
    private fun matchCyclic(a: FloatArray, b: FloatArray): IntArray {
        val out = IntArray(a.size) { -1 }
        if (a.isEmpty() || b.isEmpty()) return out
        fun cyc(x: Float, y: Float): Float { val dd = abs(x - y) % 1f; return min(dd, 1f - dd) }
        var bestOff = 0f
        var bestCost = Float.MAX_VALUE
        for (i in a.indices) for (j in b.indices) {
            val off = a[i] - b[j]
            var c = 0f
            for (q in b.indices) {
                var mn = Float.MAX_VALUE
                for (r in a.indices) mn = min(mn, cyc(a[r], b[q] + off))
                c += mn
            }
            if (c < bestCost) { bestCost = c; bestOff = off }
        }
        class Cand(val i: Int, val j: Int, val d: Float)
        val cands = ArrayList<Cand>()
        for (i in a.indices) for (j in b.indices) cands.add(Cand(i, j, cyc(a[i], b[j] + bestOff)))
        cands.sortBy { it.d }
        val usedA = BooleanArray(a.size)
        val usedB = BooleanArray(b.size)
        for (c in cands) {
            if (usedA[c.i] || usedB[c.j] || c.d > 0.25f) continue
            usedA[c.i] = true; usedB[c.j] = true; out[c.i] = c.j
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------
    // Plans and the layout they drive.
    // ---------------------------------------------------------------------------------------------------------------------------------------------

    /** [turn]: signed bend (degrees) of the ghost at this anchor, in the direction the NEW line runs; 0 = no clear bend or unknown (then only the position is compared). */
    private class PlanTip(val num: Int, val u: Float, val turn: Float = 0f)

    /**
     * [start] / [end]: numbers for the first / last point of an open line (0 = none). [tips]: numbered progress positions, ascending. [fresh]: detected apexes with no
     * number to take get a new one (false for a traced line: only the ghost's numbers are used). [toPlanU]: maps a detected apex (x, y, own progress) into the plan's
     * progress space; null = own progress (used when the line lies over the ghost, so a partial trace pairs with the right part of the ghost).
     */
    private class Plan(
        val start: Int, val end: Int, val tips: List<PlanTip>, val fresh: Boolean, val toPlanU: ((Float, Float, Float) -> Float)? = null,
        /** Pair apexes with the tips by position AND bend ([matchDescs]); a ghost tip with no acceptable apex is dropped (no number, the interpolation falls back to geometry). */
        val smart: Boolean = false,
        /** Apexes that no ghost tip took get new numbers (ends do not: only [fresh] numbers ends). The ghost's own numbers are never reused for them. */
        val freshTips: Boolean = false
    )

    private class Ins(val t: Float, val p: VectorPoint)

    /**
     * Redoes the automatic anchors of [s] after [plan]: every automatic anchor is cleared (manual ones stay), the ends get the plan's numbers, the detected apexes are
     * paired with the plan's tips in order, and apexes with no partner get a fresh number when the plan allows. [taken] = numbers that fresh ones must avoid; it is updated.
     * An anchor goes on the nearest point of the line when one lies at the apex, otherwise a point is inserted exactly on the drawn curve.
     */
    private fun layout(s: VectorStroke, plan: Plan, sens: Float, taken: MutableSet<Int>): VectorStroke {
        val n = s.points.size
        if (s.fill != 0 || n < 2) return s
        if (seamEnds(s)) { // a closed line that kept its two ends: numbered as the open line it is, then closed again (the same object comes back when nothing changed)
            val open = s.copy(isClosed = false)
            val r = layout(open, plan, sens, taken)
            return if (r === open) s else r.copy(isClosed = true)
        }
        val ring = s.isClosed && n >= 3
        val base = ArrayList<VectorPoint>(n)
        for (p in s.points) base.add(
            if (p.auto) p.copy(anchor = 0, auto = false, endpoint = false, endpointAliases = emptyList())
            else if (p.endpointAliases.isNotEmpty()) p.copy(endpointAliases = emptyList())
            else p
        )
        for (p in base) if (p.anchor in 1..ANCHOR_MAX) taken.add(p.anchor)
        val firstManual = !ring && base.first().anchor > 0
        val lastManual = !ring && base.last().anchor > 0

        // detected apexes
        val d = denseOf(base, ring)
        val tipU = ArrayList<Float>()
        val tipX = ArrayList<Float>()
        val tipY = ArrayList<Float>()
        if (d != null && n >= 3) {
            val tmp = FloatArray(2)
            val spacing = max(5f, d.total * 0.025f)
            for (u in features(d, ring, sens)) {
                pointAt(d, u, tmp)
                var nearManual = false
                for (p in base) if (p.anchor > 0 && hypot(p.x - tmp[0], p.y - tmp[1]) < spacing) { nearManual = true; break }
                if (!nearManual) { tipU.add(u); tipX.add(tmp[0]); tipY.add(tmp[1]) }
            }
            if (ring && tipU.isEmpty() && plan.fresh && plan.tips.isEmpty()) {
                // a round outline with no bends: two opposite points still give a pairing
                for (u in floatArrayOf(0f, 0.5f)) { pointAt(d, u, tmp); tipU.add(u); tipX.add(tmp[0]); tipY.add(tmp[1]) }
            }
        }
        val tk = tipU.size

        // pair them with the plan's tips, in order
        val tipNum = IntArray(tk)
        if (tk > 0 && plan.tips.isNotEmpty()) {
            fun space(i: Int): Float = plan.toPlanU?.invoke(tipX[i], tipY[i], tipU[i]) ?: tipU[i]
            val order = (0 until tk).sortedBy { space(it) }
            val mt: IntArray
            if (plan.smart && !ring && d != null) {
                val da = List(tk) { q -> val i = order[q]; Desc(space(i), bendAt(d, tipU[i])) }
                val db = plan.tips.map { Desc(it.u, it.turn) }
                mt = matchDescs(da, db)
            } else {
                val au = FloatArray(tk) { space(order[it]) }
                val bu = FloatArray(plan.tips.size) { plan.tips[it].u }
                mt = if (ring) matchCyclic(au, bu) else matchOrdered(au, bu)
            }
            for (q in 0 until tk) { val j = mt[q]; if (j >= 0) tipNum[order[q]] = plan.tips[j].num }
        }
        var firstNum = if (!ring && !firstManual) plan.start else 0
        var lastNum = if (!ring && !lastManual) plan.end else 0
        if (firstNum > 0) taken.add(firstNum)
        if (lastNum > 0) taken.add(lastNum)
        for (q in 0 until tk) if (tipNum[q] > 0) taken.add(tipNum[q])

        // fresh numbers, in path order
        if (plan.fresh || plan.freshTips) {
            // the ghost's own numbers stay out of reach even when no apex took them (a ghost tip that was dropped must not get a wrong partner through a fresh number)
            if (plan.freshTips) {
                for (t in plan.tips) taken.add(t.num)
                if (plan.start > 0) taken.add(plan.start)
                if (plan.end > 0) taken.add(plan.end)
            }
            fun freshNum(): Int {
                var c = 1
                while (c <= ANCHOR_MAX && c in taken) c++
                if (c > ANCHOR_MAX) return 0
                taken.add(c)
                return c
            }
            if (plan.fresh && !ring && !firstManual && firstNum == 0) firstNum = freshNum()
            for (q in 0 until tk) if (tipNum[q] == 0) tipNum[q] = freshNum()
            if (plan.fresh && !ring && !lastManual && lastNum == 0) lastNum = freshNum()
        }

        // put the anchors on the line
        val pts = ArrayList<VectorPoint>(base)
        if (!ring) {
            if (firstNum > 0) pts[0] = pts[0].copy(anchor = firstNum, auto = true, endpoint = true)
            if (lastNum > 0) pts[n - 1] = pts[n - 1].copy(anchor = lastNum, auto = true, endpoint = true)
        }
        val inserts = HashMap<Int, MutableList<Ins>>()
        val snapTol = if (d != null) max(1f, d.total * 0.01f) else 1f
        val segs = if (ring) n else n - 1
        for (q in 0 until tk) {
            val num = tipNum[q]
            if (num <= 0) continue
            val x = tipX[q]; val y = tipY[q]
            var bv = -1
            var bd = Float.MAX_VALUE
            val vlo = if (ring) 0 else 1
            val vhi = if (ring) n - 1 else n - 2
            for (v in vlo..vhi) {
                if (pts[v].anchor > 0) continue
                val dd = hypot(pts[v].x - x, pts[v].y - y)
                if (dd < bd) { bd = dd; bv = v }
            }
            if (bv >= 0 && bd <= snapTol) { pts[bv] = pts[bv].copy(anchor = num, auto = true, endpoint = false); continue }
            var bs = -1
            var bt = 0f
            var bsd = Float.MAX_VALUE
            for (j in 0 until segs) {
                val a = pts[j]; val b = pts[(j + 1) % n]
                val dx = b.x - a.x; val dy = b.y - a.y
                val l2 = dx * dx + dy * dy
                if (l2 < 1e-9f) continue
                val t = (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
                val dd = hypot(x - (a.x + dx * t), y - (a.y + dy * t))
                if (dd < bsd) { bsd = dd; bs = j; bt = t }
            }
            if (bs < 0) continue
            val a = pts[bs]; val b = pts[(bs + 1) % n]
            inserts.getOrPut(bs) { ArrayList() }.add(Ins(bt, VectorPoint(
                x = x, y = y,
                pressure = a.pressure + (b.pressure - a.pressure) * bt,
                elevationOffset = a.elevationOffset + (b.elevationOffset - a.elevationOffset) * bt,
                anchor = num, auto = true
            )))
        }
        val out = ArrayList<VectorPoint>(n + inserts.size)
        for (j in 0 until n) {
            out.add(pts[j])
            inserts[j]?.sortedBy { it.t }?.forEach { out.add(it.p) }
        }
        return if (out == s.points) s else s.copy(points = out)
    }

    /** The plan of a line that keeps its own numbers: its ends and tips as they are now (used after a merge or a deletion, and for a line with no ghost). */
    private fun selfPlan(s: VectorStroke, avoid: Set<Int>): Plan {
        if (seamEnds(s)) return selfPlan(s.copy(isClosed = false), avoid)
        val pts = s.points
        val n = pts.size
        val ring = s.isClosed && n >= 3
        val d = denseOf(pts, ring)
        val tmp = FloatArray(2)
        class Held(val num: Int, val x: Float, val y: Float)
        val ends = ArrayList<Held>()
        val tips = ArrayList<PlanTip>()
        for ((i, p) in pts.withIndex()) {
            val outer = !ring && (i == 0 || i == n - 1)
            if (p.auto && p.anchor in 1..ANCHOR_MAX) {
                if (!ring && (p.endpoint || outer)) ends.add(Held(p.anchor, p.x, p.y))
                else if (d != null) { project(d, p.x, p.y, tmp); tips.add(PlanTip(p.anchor, tmp[0])) }
            }
            for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) {
                if (!ring) ends.add(Held(r, p.x, p.y))
                else if (d != null) { project(d, p.x, p.y, tmp); tips.add(PlanTip(r, tmp[0])) }
            }
        }
        tips.sortBy { it.u }
        var a = 0
        var b = 0
        val pool = ends.distinctBy { it.num }
        if (!ring && n >= 2 && pool.isNotEmpty()) {
            // The end that already carries an end number keeps it (the end where the line started does not move); a number shared with a neighbouring
            // keyframe beats one that is not; a free end takes the number that was left over nearest to it.
            val f = pts.first(); val l = pts.last()
            val kA = if (f.auto && f.anchor > 0) f.anchor else 0
            val kB = if (l.auto && l.anchor > 0) l.anchor else 0
            val opts: List<Held?> = pool + listOf<Held?>(null)
            var best = Double.POSITIVE_INFINITY
            for (x in opts) for (y in opts) {
                if (x != null && y != null && x.num == y.num) continue
                var c = 0.0
                if (x == null) c += 2e6 else {
                    if (x.num !in avoid) c += 1e6
                    if (kA > 0 && x.num != kA) c += 1000.0
                    c += hypot(x.x - f.x, x.y - f.y).toDouble()
                }
                if (y == null) c += 2e6 else {
                    if (y.num !in avoid) c += 1e6
                    if (kB > 0 && y.num != kB) c += 1000.0
                    c += hypot(y.x - l.x, y.y - l.y).toDouble()
                }
                if (c < best) { best = c; a = x?.num ?: 0; b = y?.num ?: 0 }
            }
        }
        return Plan(a, b, tips, true)
    }

    /**
     * Which ghost end the line takes first when it does not lie over the ghost: ALWAYS drawing order. The end where the line starts takes the ghost's LOWEST end number and the end
     * where it finishes the other one. "First" is by number, not by point order: a ghost that was extended from its first end runs 6..1 along its points but is still numbered 1 to 6.
     * How far the line is from the ghost, or which ghost end it happens to be near, plays no part (keyframes can sit anywhere and look nothing alike).
     * True = the line runs along the ghost's point order (it starts at the ghost's first point), which is the case when that end carries the lower number.
     */
    private fun startsAtGhostStart(s: VectorStroke, g: VectorStroke): Boolean = g.points.first().anchor <= g.points.last().anchor

    /**
     * The direction of [s] along the open ghost line [g] when [s] lies over it (a whole trace or a piece of one) and covers a clear stretch of it: true = the same way
     * as the ghost. Null when [s] is elsewhere or the stretch is too short to tell; the start of the line decides then.
     */
    private fun orientationOverGhost(s0: VectorStroke, g0: VectorStroke): Boolean? {
        val s = asOpen(s0); val g = asOpen(g0)
        if (g.isClosed || s.isClosed || s.points.size < 2 || g.points.size < 2) return null
        val gd = denseOf(g.points, false) ?: return null
        val tmp = FloatArray(2)
        val tol = 0.05f * bboxDiag(g.points) + 6f
        var near = 0
        for (p in s.points) { project(gd, p.x, p.y, tmp); if (tmp[1] <= tol) near++ }
        if (near < 0.85f * s.points.size) return null
        project(gd, s.points.first().x, s.points.first().y, tmp)
        val u0 = tmp[0]
        project(gd, s.points.last().x, s.points.last().y, tmp)
        val u1 = tmp[0]
        return if (abs(u1 - u0) < 0.15f) null else u0 < u1
    }

    /**
     * An OPEN line traced over a CLOSED ghost (the ring is opened up): the ring's numbers are read round the ring from its lowest number in the direction in which the numbers grow
     * (the seam is where they wrap, i.e. where the ring was closed). The first number is the new line's first end, the last number its last end, the ones between are tips placed by their
     * progress round the ring from the first number to the last. Distance to the ghost and shape play no part.
     */
    private fun planOpenOverRing(g: VectorStroke, blocked: Set<Int>): Plan? {
        val gd = denseOf(g.points, true) ?: return null
        val tmp = FloatArray(2)
        class At(val num: Int, val u: Float)
        val found = ArrayList<At>()
        for (p in g.points) {
            val nums = ArrayList<Int>()
            if (p.anchor in 1..ANCHOR_MAX) nums.add(p.anchor)
            for (r in p.endpointAliases) if (r in 1..ANCHOR_MAX) nums.add(r)
            if (nums.isEmpty()) continue
            project(gd, p.x, p.y, tmp)
            for (n in nums) found.add(At(n, tmp[0]))
        }
        val ring = found.distinctBy { it.num }.sortedBy { it.u }
        val m = ring.size
        if (m < 2) return null
        var descents = 0; var ascents = 0
        for (i in 0 until m) { val a = ring[i].num; val b = ring[(i + 1) % m].num; if (a > b) descents++ else if (a < b) ascents++ }
        val ascending = descents <= ascents // the numbers grow along the ring's own direction (one wrap) rather than against it
        val startIdx = ring.indices.minByOrNull { ring[it].num } ?: return null
        val order = (0 until m).map { k -> if (ascending) ring[(startIdx + k) % m] else ring[(((startIdx - k) % m) + m) % m] }
        fun progress(a: At): Float { val d = if (ascending) a.u - order[0].u else order[0].u - a.u; return ((d % 1f) + 1f) % 1f }
        val span = progress(order.last())
        if (span < 1e-3f) return null
        var start = order.first().num; var end = order.last().num
        if (start in blocked) start = 0
        if (end in blocked) end = 0
        val tips = (1 until m - 1).map { order[it] }.filter { it.num !in blocked }.map { PlanTip(it.num, (progress(it) / span).coerceIn(0f, 1f)) }.sortedBy { it.u }
        if (start == 0 && end == 0 && tips.isEmpty()) return null
        return Plan(start, end, tips, false)
    }

    /**
     * A CLOSED line traced over an OPEN ghost (the ghost is closed into a ring): a ring has no ends, so every number of the ghost, its two end numbers included, becomes a tip,
     * read in the direction in which the ghost's numbers grow. The two end numbers would land on the same spot of a ring, so the ghost's progress is squeezed to leave a gap for the seam.
     */
    private fun planRingOverOpen(g: VectorStroke, blocked: Set<Int>): Plan? {
        val gn = g.points.size
        val gd = denseOf(g.points, false) ?: return null
        val a = g.points.first().anchor; val b = g.points.last().anchor
        if (a !in 1..ANCHOR_MAX || b !in 1..ANCHOR_MAX) return null
        val fwd = a <= b
        val tmp = FloatArray(2)
        val raw = ArrayList<PlanTip>()
        fun add(num: Int, u: Float) { if (num !in blocked) raw.add(PlanTip(num, if (fwd) u else 1f - u)) }
        add(a, 0f); add(b, 1f)
        for (i in 1 until gn - 1) {
            val p = g.points[i]
            if (p.anchor !in 1..ANCHOR_MAX) continue
            project(gd, p.x, p.y, tmp)
            add(p.anchor, tmp[0])
        }
        if (raw.isEmpty()) return null
        val squeeze = 1f - 1f / max(raw.size, 2)
        return Plan(0, 0, raw.map { PlanTip(it.num, it.u * squeeze) }.sortedBy { it.u }, false)
    }

    /**
     * The plan of a line [s] that follows the ghost line [g]. Open: the ends take the ghost's end numbers (which one first depends on [forward], or on where [s] starts
     * when it is null) and the ghost's in-between anchors become the tips to pair the apexes with. Closed: all of the ghost's anchors are tips, read in the direction
     * of the outline. Numbers in [blocked] are left out. Null when the ghost cannot be followed (open / closed mismatch, or no anchors).
     */
    private fun ghostPlan(s0: VectorStroke, g0: VectorStroke, forward: Boolean?, blocked: Set<Int>): Plan? {
        val s = asOpen(s0); val g = asOpen(g0) // a closed line that kept its two ends (see [seamEnds]) is an open line here: its end numbers go to the ends of the trace
        val sn = s.points.size; val gn = g.points.size
        if (sn < 2 || gn < 2 || g.fill != 0) return null
        val sRing = s.isClosed && sn >= 3
        val gRing = g.isClosed && gn >= 3
        if (sRing != gRing) return if (gRing) planOpenOverRing(g, blocked) else planRingOverOpen(g, blocked) // the trace is opened up / closed where the ghost is not
        val gd = denseOf(g.points, gRing) ?: return null
        val tmp = FloatArray(2)
        val fwd: Boolean
        val tips = ArrayList<PlanTip>()
        var start = 0
        var end = 0
        if (!gRing) {
            val a = g.points.first().anchor; val b = g.points.last().anchor
            if (a !in 1..ANCHOR_MAX || b !in 1..ANCHOR_MAX) return null
            fwd = forward ?: orientationOverGhost(s, g) ?: startsAtGhostStart(s, g)
            start = if (fwd) a else b
            end = if (fwd) b else a
            if (start in blocked) start = 0
            if (end in blocked) end = 0
            for (i in 1 until gn - 1) {
                val p = g.points[i]
                if (p.anchor !in 1..ANCHOR_MAX || p.anchor in blocked) continue
                project(gd, p.x, p.y, tmp)
                val bend = bendAt(gd, tmp[0]) // reading the ghost the other way round flips the side of a bend
                tips.add(PlanTip(p.anchor, if (fwd) tmp[0] else 1f - tmp[0], if (fwd) bend else -bend))
            }
        } else {
            fun area(p: List<VectorPoint>): Float { var a = 0f; for (i in p.indices) { val q = p[(i + 1) % p.size]; a += p[i].x * q.y - q.x * p[i].y }; return a }
            fwd = area(s.points) * area(g.points) >= 0f
            for (p in g.points) {
                if (p.anchor !in 1..ANCHOR_MAX || p.anchor in blocked) continue
                project(gd, p.x, p.y, tmp)
                tips.add(PlanTip(p.anchor, if (fwd) tmp[0] else 1f - tmp[0]))
            }
        }
        if (start == 0 && end == 0 && tips.isEmpty()) return null
        tips.sortBy { it.u }
        // When the line lies over the ghost (a whole trace or a piece of one) its apexes are paired by where they lie ON the ghost, so a piece pairs with the right part.
        var toPlanU: ((Float, Float, Float) -> Float)? = null
        if (!gRing && tips.isNotEmpty()) {
            val tol = 0.05f * bboxDiag(g.points) + 6f
            var near = 0
            for (p in s.points) { project(gd, p.x, p.y, tmp); if (tmp[1] <= tol) near++ }
            if (near >= 0.85f * sn) {
                toPlanU = { x, y, _ ->
                    val q = FloatArray(2)
                    project(gd, x, y, q)
                    if (fwd) q[0] else 1f - q[0]
                }
            }
        }
        return Plan(start, end, tips, false, toPlanU, smart = !gRing, freshTips = !gRing)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------
    // Public entry points.
    // ---------------------------------------------------------------------------------------------------------------------------------------------

    /** A new line: anchors at its ends and at the apex of each bend, numbered in path order from the first number that is not in [taken] (automatic). Fills and single points stay as they are. */
    fun assignFresh(s: VectorStroke, taken: Set<Int>, sens: Float): VectorStroke {
        if (s.fill != 0 || s.points.size < 2) return s
        return layout(s, Plan(0, 0, emptyList(), true), sens, HashSet(taken))
    }

    /**
     * Traces: [s] (a line just drawn) follows the ghost line [g]. The two ends take the ghost's end numbers, the end where [s] starts taking the ghost end it starts nearest to;
     * the apexes of [s] take the ghost's in-between numbers in order. All of them stay automatic, so merging pieces of one trace later moves them to the new ends and apexes
     * (see [refresh]). Numbers in [blocked] (used in this keyframe already) are never taken. Null when nothing can be inherited.
     */
    fun inherit(s: VectorStroke, g: VectorStroke, blocked: Set<Int>, sens: Float = 0.5f): VectorStroke? {
        if (s.fill != 0 || s.points.size < 2) return null
        val plan = ghostPlan(s, g, null, blocked) ?: return null
        val r = layout(s, plan, sens, HashSet(blocked))
        return if (r === s) null else r
    }

    /** True when both ends of an open line carry an anchor (closed lines and points have no ends). */
    fun endsAnchored(s: VectorStroke): Boolean = s.isClosed || s.points.size < 2 || (s.points.first().anchor > 0 && s.points.last().anchor > 0)

    /**
     * The ghost line [g] (a line of the reference keyframe) ready to be followed: with no anchors it gets ends and tips; with automatic anchors only (ends from an older version,
     * or lost tips) it is laid out again, which keeps its numbers and adds the tips it lacks. Anchors set by hand are never moved. Returns [g] itself when nothing changes.
     */
    fun completeGhost(g: VectorStroke, taken: Set<Int>, sens: Float): VectorStroke {
        if (g.fill != 0 || g.points.size < 2) return g
        if (g.points.none { it.anchor > 0 }) return withEnds(assignFresh(g, taken, sens), taken)
        if (g.points.any { it.anchor > 0 && !it.auto }) return withEnds(g, taken)
        val r = layout(g, selfPlan(g, emptySet()), sens, HashSet(taken))
        return withEnds(r, taken)
    }

    /** The two end points of an open line always carry an end anchor: a missing one is added on the exact end point, with a number not in [taken]. */
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

    /** The ghost line (of [refs]) a line is following: one of whose end numbers (or, for a closed ghost, any number) the line carries. Null when it follows none. */
    private fun ghostFor(s: VectorStroke, refs: List<VectorStroke>): VectorStroke? {
        for (g in refs) {
            if (g.fill != 0 || g.points.size < 2) continue
            val nums = if (g.isClosed && !seamEnds(g)) g.points.map { it.anchor }.filter { it > 0 } else listOf(g.points.first().anchor, g.points.last().anchor).filter { it > 0 }
            if (nums.isEmpty()) continue
            if (s.points.any { it.anchor in nums || it.endpointAliases.any { r -> r in nums } }) return g
        }
        return null
    }

    /**
     * After lines changed shape (merged, points deleted): the automatic anchors of every line that has some are redone on the whole new line. A line with no anchors at all
     * that is listed in [only] gets a complete set. A line that is following a ghost line of [refs] (it carries the ghost's end numbers) keeps following it: the end where it
     * started keeps its number and stays put, the other end takes the ghost's other end number wherever the line now ends, and the apexes are detected again and paired with
     * the ghost's in-between numbers. Other lines keep their own numbers (ends first, then tips in order); new apexes get new numbers avoiding this keyframe and [avoid].
     * Returns [strokes] itself when nothing changed.
     */
    fun refresh(strokes: List<VectorStroke>, avoid: Set<Int>, sens: Float, only: Set<String>? = null, refs: List<VectorStroke> = emptyList()): List<VectorStroke> {
        fun hasAuto(s: VectorStroke) = s.points.any { (it.auto && it.anchor > 0) || it.endpointAliases.isNotEmpty() }
        fun hasAny(s: VectorStroke) = s.points.any { it.anchor > 0 || it.endpointAliases.isNotEmpty() }
        fun eligible(s: VectorStroke): Boolean {
            if (s.fill != 0 || s.points.size < 2) return false
            return if (only == null) hasAuto(s) else s.id in only && (hasAuto(s) || !hasAny(s))
        }
        if (strokes.none { eligible(it) }) return strokes
        val cur = strokes.toMutableList()
        var changed = false
        for (si in cur.indices) {
            val s = cur[si]
            if (!eligible(s)) continue
            val others = HashSet<Int>()
            for ((oi, o) in cur.withIndex()) {
                if (oi == si) continue
                for (p in o.points) { if (p.anchor > 0) others.add(p.anchor); for (r in p.endpointAliases) if (r > 0) others.add(r) }
            }
            var plan: Plan? = null
            val g = ghostFor(s, refs)
            if (g != null) {
                val hint: Boolean? = if (g.isClosed && !seamEnds(g)) null else {
                    val f = s.points.first(); val l = s.points.last()
                    val ga = g.points.first().anchor; val gb = g.points.last().anchor
                    // where the line lies on the ghost is the surest direction; otherwise the numbers its ends carry say which end started where
                    orientationOverGhost(s, g) ?: when {
                        f.anchor == ga || l.anchor == gb -> true
                        f.anchor == gb || l.anchor == ga -> false
                        else -> null
                    }
                }
                plan = ghostPlan(s, g, hint, others)
            }
            if (plan == null) plan = selfPlan(s, avoid)
            val taken = HashSet<Int>(others); taken.addAll(avoid)
            val r = layout(s, plan, sens, taken)
            if (r !== s) { cur[si] = r; changed = true }
        }
        return if (changed) cur else strokes
    }

    /** [refresh] for the cel [cel] of [layer] (its neighbours decide which numbers are avoided and which ghost lines can be followed; [nextFirst] = the next keyframe's lines are preferred). */
    fun refreshFor(layer: Layer, cel: Cel, strokes: List<VectorStroke>, sens: Float, nextFirst: Boolean = false): List<VectorStroke> {
        val idx = layer.cels.indexOfFirst { it === cel }
        if (idx < 0) return refresh(strokes, emptySet(), sens)
        return refresh(strokes, neighbourNumbers(layer, idx), sens, null, referenceLines(layer, idx, nextFirst))
    }
}

/** The line order onion skin: the lines of the previous / next keyframe that have not been traced yet in the current one, in drawing order. */
object LineGhost {
    fun lines(c: Cel): List<VectorStroke> = c.vectorStrokes().filter { it.fill == 0 && it.points.size >= 2 }

    /**
     * The nearest previous (or next) keyframe whose drawing differs from the current one and has vector strokes (clones of the current drawing and empty frames are skipped).
     * With three frames selected in the timeline and the playhead on the middle one (the interpolation-drag "three frames" setup), the references are the two OUTER selected
     * frames instead: the first for the previous ghost, the last for the next ghost ([ed] given; an outer frame that shows the same drawing or is empty falls back to the search).
     */
    fun reference(layer: Layer, idx: Int, next: Boolean, ed: Editor? = null): Cel? {
        val cur = layer.cels.getOrNull(idx) ?: return null
        if (ed != null) {
            val fs = vecSelectedFrames(layer, ed)
            if (fs.size == 3 && fs[1] === cur) {
                val edge = if (next) fs[2] else fs[0]
                if (edge.strokes !== cur.strokes && edge.strokes.any { it.kind == 5 }) return edge
            }
        }
        var i = if (next) idx + 1 else idx - 1
        while (i in layer.cels.indices) {
            val c = layer.cels[i]
            if (c.strokes !== cur.strokes && c.strokes.any { it.kind == 5 }) return c
            i += if (next) 1 else -1
        }
        return null
    }

    /**
     * Whether a line of the current keyframe has TAKEN the first or last anchor number of the ghost line [r] ([nums] = every number used in the current keyframe). Only the ends count
     * (anchor numbers and the end aliases a merge leaves on them): not the distance or shape of the lines, and not the numbers of the bends in between. A closed ghost line has no ends,
     * so any of its numbers counts.
     */
    fun isClaimed(r: VectorStroke, nums: Set<Int>): Boolean {
        if (r.points.isEmpty()) return false
        if (r.isClosed && !AutoAnchors.seamEnds(r)) return r.points.any { p -> (p.anchor > 0 && p.anchor in nums) || p.endpointAliases.any { it in nums } } // a closed line that kept its two ends counts by its ends, like an open one
        return listOf(r.points.first(), r.points.last()).any { p -> (p.anchor > 0 && p.anchor in nums) || p.endpointAliases.any { it in nums } }
    }

    /** Lines of [ref] nobody has traced yet (see [isClaimed]); the traced ones stay on the ghost, drawn in another colour, and can still be referred to. [px] is kept for the callers. */
    @Suppress("UNUSED_PARAMETER")
    fun unclaimed(ref: List<VectorStroke>, current: List<VectorStroke>, px: Float): List<VectorStroke> {
        val nums = AutoAnchors.usedNumbers(current.filter { it.fill == 0 && it.points.size >= 2 })
        return ref.filter { !isClaimed(it, nums) }
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

    /**
     * Skip to the next ([dir] = 1) or previous (-1) line of the reference keyframe in drawing order, counting round. Lines already traced are included: they stay on the ghost
     * (drawn in yellow-orange) so a line can be gone back to and extended; the line that is pinned shimmers and is the one a new stroke follows.
     */
    fun skip(ed: Editor, layer: Layer, idx: Int, dir: Int) {
        val cur = layer.cels.getOrNull(idx) ?: return
        val ref = reference(layer, idx, ed.vecGhostNext, ed) ?: return
        val all = lines(ref)
        if (all.isEmpty()) return
        val open = unclaimed(all, cur.vectorStrokes(), 1f / ed.vecDocZoom.coerceAtLeast(0.0001f))
        val pk = key(layer, idx)
        val now = shown(all, open, ed, pk)
        val start = all.indexOfFirst { it.id == now?.id }.let { if (it < 0) 0 else it }
        val j = (((start + dir) % all.size) + all.size) % all.size
        ed.vecGhostPin = all[j].id; ed.vecGhostPinKey = pk
    }
}
