package com.example.inbetween

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shape flip (Selection tool, vector layers): the selected lines that carry anchors can take the SHAPES of the lines that have the same end anchor numbers in the
 * other keyframes of the layer, one keyframe at a time (the bar at the top right of the canvas flips back and forth, [VectorShapeBar]).
 *
 * What changes and what does not: the lines of the other keyframe (the "partners", found by shared END anchor numbers, one to one) bring their own point lists, so the
 * lines get that keyframe's shapes and their positions relative to each other. The group is NOT moved or scaled as a whole: all partners get ONE translation + uniform scale
 * (no rotation, no per-line change) that puts the centre of their joint bounding box on the centre of the selected lines' joint bounding box and gives it the same diagonal.
 * Colour, width, brush and id of the current lines stay; the points (with their anchor numbers, hidden flags, pressure) are the partner's. Junction numbers of the borrowed
 * points are cleared (they belong to the other keyframe) and [VectorJunctions.normalize] drops the links that no longer join two lines.
 *
 * Vector line rules (AI_WORKING_AGREEMENT section 4): the borrowed lines already are fitted curves, so no point is added or thinned; anchors, end points and hidden points
 * come with the partner unchanged; preview and Apply use the same [shapes] result; Apply is one undo step.
 */
object VectorShapeFlip {
    /** Everything the bar and the preview need about the current selection. [chosen] = indices (into [strokes]) of the selected lines that have end anchors. */
    class Ctx(val layer: Layer, val idx: Int, val sc: VecScope, val c: Int, val strokes: List<VectorStroke>, val chosen: Set<Int>)

    /** [cur] = index of the selected line, [other] = index of its partner in the other keyframe, [reversed] = the partner runs the other way (its end anchors are swapped). */
    class Match(val cur: Int, val other: Int, val reversed: Boolean)

    /** One other keyframe that has partners for at least one selected line. */
    class Cand(val cel: Cel, val index: Int, val strokes: List<VectorStroke>, val matches: List<Match>)

    /** The anchor numbers that tie a line to its partners: the numbers on the two ends of an open line, every number of a closed line (it has no ends). */
    fun endRanks(s: VectorStroke): Set<Int> {
        val pts = s.points
        if (pts.isEmpty()) return emptySet()
        return if (s.isClosed) pts.filter { it.anchor > 0 }.map { it.anchor }.toSet()
        else listOf(pts.first().anchor, pts.last().anchor).filter { it > 0 }.toSet()
    }

    /** The selection on the layer being edited, or null when there is no selected line with end anchors (or the layer is not editable). */
    fun context(cut: Cut, ed: Editor): Ctx? {
        val layer = cut.drawLayer?.takeIf { it.vector && it.visible && !it.locked } ?: return null
        val sc = vecScope(cut, ed.refAll) ?: return null
        if (!sc.sameCels(ed.vecSelCels) || ed.vecSel.isEmpty()) return null
        val c = sc.layers.indexOf(layer)
        if (c < 0) return null
        val strokes = sc.perCel[c]
        val chosen = sc.localKeys(ed.vecSel, c).map { vecKeyStroke(it) }.filter { si -> si in strokes.indices && strokes[si].fill == 0 && strokes[si].points.size >= 2 && endRanks(strokes[si]).isNotEmpty() }.toSet()
        if (chosen.isEmpty()) return null
        val idx = layer.indexAt(cut.time)
        if (layer.cels.getOrNull(idx) == null) return null
        return Ctx(layer, idx, sc, c, strokes, chosen)
    }

    private fun centroid(s: VectorStroke): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (p in s.points) { sx += p.x; sy += p.y }
        val n = max(1, s.points.size)
        return Pair(sx / n, sy / n)
    }

    /** Partners of the [chosen] lines of [cur] among [other]: the line that shares the most end anchor numbers (nearest centroid breaks ties), one to one. No geometric guessing. */
    fun match(cur: List<VectorStroke>, chosen: Set<Int>, other: List<VectorStroke>): List<Match> {
        class C(val i: Int, val j: Int, val shared: Int, val dist: Float)
        val cands = ArrayList<C>()
        for (i in chosen.sorted()) {
            if (i !in cur.indices) continue
            val c = cur[i]
            val rc = endRanks(c)
            if (rc.isEmpty()) continue
            val cc = centroid(c)
            for (j in other.indices) {
                val o = other[j]
                if (o.fill != 0 || o.points.size < 2 || o.isClosed != c.isClosed) continue
                val sh = endRanks(o).count { it in rc }
                if (sh == 0) continue
                val co = centroid(o)
                cands.add(C(i, j, sh, hypot(cc.first - co.first, cc.second - co.second)))
            }
        }
        cands.sortWith(compareByDescending<C> { it.shared }.thenBy { it.dist })
        val usedCur = HashSet<Int>(); val usedOther = HashSet<Int>()
        val out = ArrayList<Match>()
        for (k in cands) {
            if (k.i in usedCur || k.j in usedOther) continue
            usedCur.add(k.i); usedOther.add(k.j)
            val c = cur[k.i]; val o = other[k.j]
            val fwd = (if (c.points.first().anchor > 0 && c.points.first().anchor == o.points.first().anchor) 1 else 0) +
                (if (c.points.last().anchor > 0 && c.points.last().anchor == o.points.last().anchor) 1 else 0)
            val bwd = (if (c.points.first().anchor > 0 && c.points.first().anchor == o.points.last().anchor) 1 else 0) +
                (if (c.points.last().anchor > 0 && c.points.last().anchor == o.points.first().anchor) 1 else 0)
            out.add(Match(k.i, k.j, !c.isClosed && bwd > fwd))
        }
        return out.sortedBy { it.cur }
    }

    /** Every other keyframe (a drawing shown by several cels counts once; the current drawing is left out) that has a partner for a selected line, in timeline order. */
    fun candidates(ctx: Ctx): List<Cand> {
        val here = ctx.layer.cels.getOrNull(ctx.idx) ?: return emptyList()
        val seen = ArrayList<Cel>()
        val out = ArrayList<Cand>()
        for ((i, cel) in ctx.layer.cels.withIndex()) {
            if (cel.sameDrawing(here) || seen.any { it.sameDrawing(cel) }) continue
            seen.add(cel)
            val st = cel.vectorStrokes()
            val ms = match(ctx.strokes, ctx.chosen, st)
            if (ms.isNotEmpty()) out.add(Cand(cel, i, st, ms))
        }
        return out
    }

    /** [cel] as a candidate for the current selection (null when it has no partner for any selected line). */
    fun candidateFor(ctx: Ctx, cel: Cel): Cand? {
        val st = cel.vectorStrokes()
        val ms = match(ctx.strokes, ctx.chosen, st)
        if (ms.isEmpty()) return null
        return Cand(cel, ctx.layer.cels.indexOfFirst { it === cel }, st, ms)
    }

    /**
     * The new lines for the selected lines (index in [cur] -> line): partner points, placed with ONE translation + uniform scale for the whole group so that the group keeps
     * its centre and its size (bounding box diagonal); see the class comment. Identity, colour, width and brush are the current line's.
     */
    fun shapes(cur: List<VectorStroke>, other: List<VectorStroke>, ms: List<Match>): Map<Int, VectorStroke> {
        if (ms.isEmpty()) return emptyMap()
        fun bounds(pts: List<VectorPoint>): FloatArray {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in pts) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
            return floatArrayOf(l, t, r, b)
        }
        val cb = bounds(ms.flatMap { cur[it.cur].points })
        val ob = bounds(ms.flatMap { other[it.other].points })
        val dc = hypot(cb[2] - cb[0], cb[3] - cb[1]); val dob = hypot(ob[2] - ob[0], ob[3] - ob[1])
        val s = if (dc < 1e-3f || dob < 1e-3f) 1f else dc / dob
        val ccx = (cb[0] + cb[2]) / 2f; val ccy = (cb[1] + cb[3]) / 2f
        val ocx = (ob[0] + ob[2]) / 2f; val ocy = (ob[1] + ob[3]) / 2f
        val out = LinkedHashMap<Int, VectorStroke>()
        for (m in ms) {
            val src = other[m.other].points.let { if (m.reversed) it.reversed() else it }
            val pts = src.map { p -> p.copy(x = ccx + (p.x - ocx) * s, y = ccy + (p.y - ocy) * s, junction = 0) }
            out[m.cur] = cur[m.cur].copy(points = pts)
        }
        return out
    }

    /** What the canvas draws over the selection while [cel]'s shapes are previewed (the same lines [apply] commits). */
    fun preview(cut: Cut, ed: Editor, cel: Cel): List<VectorStroke> {
        val ctx = context(cut, ed) ?: return emptyList()
        val cand = candidateFor(ctx, cel) ?: return emptyList()
        return shapes(ctx.strokes, cand.strokes, cand.matches).values.toList()
    }

    /** Commits [cel]'s shapes to the selected lines: one undo step, the changed lines stay selected (whole lines: their point counts changed). Returns false when nothing could be done. */
    fun apply(cut: Cut, ed: Editor, cel: Cel): Boolean {
        val ctx = context(cut, ed) ?: return false
        val cand = candidateFor(ctx, cel) ?: return false
        val repl = shapes(ctx.strokes, cand.strokes, cand.matches)
        if (repl.isEmpty()) return false
        val strokes = VectorJunctions.normalize(ctx.strokes.mapIndexed { i, s -> repl[i] ?: s })
        val off = ctx.sc.offsets[ctx.c]; val end = ctx.sc.offsets[ctx.c + 1]
        val keep = ed.vecSel.filter { k -> val si = vecKeyStroke(k); !(si >= off && si < end && (si - off) in repl.keys) }
        val added = repl.keys.flatMap { li -> strokes[li].points.indices.map { vecKey(li + off, it) } }
        ed.vecShapeCel = null
        replaceVectorCel(cut, ctx.layer, ctx.idx, strokes)
        ed.vecSel = (keep + added).toSet()
        ed.vecSelCels = vecScope(cut, ed.refAll)?.cels ?: emptyList()
        return true
    }
}
