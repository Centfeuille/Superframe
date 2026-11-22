package com.example.inbetween

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/** interp = how the segment starting at this key behaves: 0 hold, 1 linear, 2 curve (ease in-out), 3 smooth in, 4 smooth out. */
class CamKey(val t: Int, val v: Float, val interp: Int = 1, val ease: Float = 0.33f)

fun easeFn(type: Int, u: Float, k: Float): Float {
    val p = 1f + 3f * k
    return when (type) {
        0 -> 0f
        2 -> if (u < 0.5f) 0.5f * (2f * u).pow(p) else 1f - 0.5f * (2f * (1f - u)).pow(p)
        3 -> u.pow(p)
        4 -> 1f - (1f - u).pow(p)
        else -> u
    }
}

fun evalChan(keys: List<CamKey>, t: Int, def: Float): Float {
    val n = keys.size
    if (n == 0) return def
    val first = keys[0]; if (t < first.t) return first.v
    val last = keys[n - 1]; if (t >= last.t) return last.v
    var lo = 0; var hi = n - 1 // keys are sorted by time: keys[lo].t <= t < keys[hi].t; binary search keeps long (shake) channels cheap on every frame
    while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (keys[mid].t <= t) lo = mid else hi = mid }
    val a = keys[lo]; val b = keys[hi]
    val u = (t - a.t).toFloat() / (b.t - a.t)
    return a.v + (b.v - a.v) * easeFn(a.interp, u, a.ease)
}

/** Like [evalChan] at a fractional frame, so the curve editor can draw eased segments smoothly. */
fun evalChanF(keys: List<CamKey>, t: Float): Float {
    val n = keys.size
    if (n == 0) return 0f
    if (t <= keys[0].t) return keys[0].v
    if (t >= keys[n - 1].t) return keys[n - 1].v
    var lo = 0; var hi = n - 1
    while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (keys[mid].t <= t) lo = mid else hi = mid }
    val a = keys[lo]; val b = keys[hi]
    return a.v + (b.v - a.v) * easeFn(a.interp, (t - a.t) / (b.t - a.t), a.ease)
}

fun setKey(keys: SnapshotStateList<CamKey>, t: Int, v: Float, interp: Int = 1, ease: Float = 0.33f) {
    val i = keys.indexOfFirst { it.t == t }
    if (i >= 0) keys[i] = CamKey(t, v, keys[i].interp, keys[i].ease)
    else { val j = keys.indexOfFirst { it.t > t }; keys.add(if (j < 0) keys.size else j, CamKey(t, v, interp, ease)) }
    bumpTimelineRevision()
}

/** Adds a key on all four channels at frame t, holding the values the camera currently has there. */
fun keyCameraAt(cut: Cut, cam: Camera, t: Int, interp: Int, ease: Float) {
    val x = cam.x(t, cut.w); val y = cam.y(t, cut.h); val sc = cam.sc(t); val r = cam.rot(t)
    setKey(cam.xs, t, x, interp, ease); setKey(cam.ys, t, y, interp, ease)
    setKey(cam.ss, t, sc, interp, ease); setKey(cam.rs, t, r, interp, ease)
}

/** Adds a key on all four channels of a Transformation folder at frame t, holding its current values. */
fun keyFolderAt(folder: Layer, t: Int, interp: Int, ease: Float) {
    val v = folder.transformAt(t)
    folder.setTransformAt(t, v.x, v.y, v.scale, v.rotation, interp, ease)
}

/** Camera layer data: frame size (w x h at scale 1) and animated x / y / scale keys. Scale = frame size multiplier. */
class Camera(w: Int, h: Int) {
    var w by mutableIntStateOf(w)
    var h by mutableIntStateOf(h)
    var lockW by mutableStateOf(false)
    var lockH by mutableStateOf(false)
    var lockA by mutableStateOf(false)
    var aspect = w / h.toFloat()
    var stretchedFrom: Pair<Int, Int>? = null // frame size before the first on-canvas stretch (UI state, not saved); Reset restores it
    val xs = mutableStateListOf<CamKey>()
    val ys = mutableStateListOf<CamKey>()
    val ss = mutableStateListOf<CamKey>()
    val rs = mutableStateListOf<CamKey>() // rotation, degrees clockwise
    fun chan(i: Int) = when (i) { 0 -> xs; 1 -> ys; 2 -> ss; else -> rs }
    fun rot(t: Int) = evalChan(rs, t, 0f)
    fun x(t: Int, cw: Int) = evalChan(xs, t, cw / 2f)
    fun y(t: Int, ch: Int) = evalChan(ys, t, ch / 2f)
    fun sc(t: Int) = evalChan(ss, t, 1f)
    fun maxT() = maxOf(xs.lastOrNull()?.t ?: 0, ys.lastOrNull()?.t ?: 0, ss.lastOrNull()?.t ?: 0, rs.lastOrNull()?.t ?: 0)

    fun changeW(v: Int) {
        val n = v.coerceIn(16, 8192); if (lockW) return
        if (lockA) { if (lockH) return; w = n; h = (n / aspect).roundToInt().coerceAtLeast(16) } else w = n
    }
    fun changeH(v: Int) {
        val n = v.coerceIn(16, 8192); if (lockH) return
        if (lockA) { if (lockW) return; h = n; w = (n * aspect).roundToInt().coerceAtLeast(16) } else h = n
    }
    fun changeAspect(a: Float) {
        if (lockA || a < 0.1f || (lockW && lockH)) return
        if (lockW) h = (w / a).roundToInt().coerceAtLeast(16) else w = (h * a).roundToInt().coerceAtLeast(16)
    }
}

/** A vanishing point attached to one of the two perspective guides. [t] is its signed axis coordinate. */
class Vp(val kind: Int, val t: Float)

/** Independent controls for the horizon (0) and perpendicular (1) perspective guides. */
class PerspGuide {
    var rotation by mutableFloatStateOf(0f)
    var rotationUnlocked by mutableStateOf(false)
    /** When rotation is unlocked, constrain the drag to absolute 30-degree increments. */
    var rotationSnap30 by mutableStateOf(false)
    var lockVpDistance by mutableStateOf(false)
    var curvilinear by mutableStateOf(false)
    var curvatureStrength by mutableFloatStateOf(1f)
    /** Number of curved rays emitted from each vanishing point (even so both horizon directions stay exact). */
    var curvilinearRayCount by mutableIntStateOf(24)
}

data class PerspGuideSnapshot(val rotation: Float, val rotationUnlocked: Boolean, val rotationSnap30: Boolean, val lockVpDistance: Boolean, val curvilinear: Boolean, val curvatureStrength: Float, val curvilinearRayCount: Int)
data class PerspSnapshot(val horizonGuide: PerspGuideSnapshot, val perpendicularGuide: PerspGuideSnapshot, val focalLengthMm: Float = 18f, val vanishingPointMagnet: Boolean = true)

/** Per-stroke latch; -1 means no perspective path has been acquired yet. */
class PerspectiveMagnetState {
    var kind: Int = -1
    /** Index of the vanishing point that owns the latched ray, or -1 for a guide axis. */
    var vpIndex: Int = -1
    var curveIndex: Int = -1
    var latchedPoints: List<Offset>? = null
    var linePoint: Offset? = null
    var lineDirection: Offset? = null
    var blendProgress: Float = 0f
    var lastPoint: Offset? = null
    /** Geometry is immutable during a stroke; cache the sampled ray fan once. */
    var cachedArcs: Array<List<PerspArc>>? = null
}

data class PerspArc(val vpIndex: Int, val curveIndex: Int, val points: List<Offset>)

class Persp(horizon: Float, center: Float) {
    var horizon by mutableFloatStateOf(horizon)   // y coordinate of the guide intersection
    var center by mutableFloatStateOf(center)     // x coordinate of the guide intersection
    /** Physical focal length for a 36 mm full-frame sensor model. The FOV is derived from this value. */
    var focalLengthMm by mutableFloatStateOf(18f)
    /** When true, VP rays override the two guide axes for stroke magnetism. */
    var vanishingPointMagnet by mutableStateOf(true)
    val fovDegrees: Float get() = fovFromFocalLength(focalLengthMm)
    val horizonGuide = PerspGuide()
    val perpendicularGuide = PerspGuide()
    val vps = mutableStateListOf<Vp>()
    val origin get() = Offset(center, horizon)
    fun guide(kind: Int) = if (kind == 0) horizonGuide else perpendicularGuide
    fun axisAngle(kind: Int) = (if (kind == 0) 0f else 90f) + guide(kind).rotation
    fun axisDir(kind: Int): Offset { val a = Math.toRadians(axisAngle(kind).toDouble()); return Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat()) }
    fun axisNormal(kind: Int): Offset { val d = axisDir(kind); return Offset(-d.y, d.x) }
    private fun baseCoordinate(kind: Int) = if (kind == 0) center else horizon
    fun coordinate(kind: Int, point: Offset): Float { val d = axisDir(kind); val q = point - origin; return baseCoordinate(kind) + q.x * d.x + q.y * d.y }
    fun distanceToAxis(kind: Int, point: Offset): Float { val n = axisNormal(kind); val q = point - origin; return kotlin.math.abs(q.x * n.x + q.y * n.y) }
    fun pos(v: Vp): Offset = origin + axisDir(v.kind) * (v.t - baseCoordinate(v.kind))
    fun snapshot() = PerspSnapshot(
        PerspGuideSnapshot(horizonGuide.rotation, horizonGuide.rotationUnlocked, horizonGuide.rotationSnap30, horizonGuide.lockVpDistance, horizonGuide.curvilinear, horizonGuide.curvatureStrength, horizonGuide.curvilinearRayCount),
        PerspGuideSnapshot(perpendicularGuide.rotation, perpendicularGuide.rotationUnlocked, perpendicularGuide.rotationSnap30, perpendicularGuide.lockVpDistance, perpendicularGuide.curvilinear, perpendicularGuide.curvatureStrength, perpendicularGuide.curvilinearRayCount),
        focalLengthMm,
        vanishingPointMagnet
    )
    fun restore(s: PerspSnapshot) {
        fun apply(g: PerspGuide, v: PerspGuideSnapshot) { g.rotation = v.rotation; g.rotationUnlocked = v.rotationUnlocked; g.rotationSnap30 = v.rotationSnap30; g.lockVpDistance = v.lockVpDistance; g.curvilinear = v.curvilinear; g.curvatureStrength = v.curvatureStrength; g.curvilinearRayCount = v.curvilinearRayCount.coerceIn(8, 48) }
        apply(horizonGuide, s.horizonGuide); apply(perpendicularGuide, s.perpendicularGuide); focalLengthMm = s.focalLengthMm.coerceIn(1.57f, 500f); vanishingPointMagnet = s.vanishingPointMagnet
    }

    /**
     * Changing focal length changes the image-plane scale. Keep camera orientation and the
     * optical centre fixed, and scale every vanishing-point offset from that centre by fNew/fOld.
     * Longer lenses therefore push VPs farther away; shorter lenses pull them inward.
     */
    fun setFov(degrees: Float) = setFocalLength(focalLengthForFov(degrees))
    fun setFocalLength(mm: Float) {
        val next = mm.coerceIn(1.57f, 500f)
        val previous = focalLengthMm.coerceAtLeast(0.001f)
        val scale = next / previous
        if (kotlin.math.abs(scale - 1f) > 0.00001f) {
            for (i in vps.indices) {
                val vp = vps[i]
                val base = baseCoordinate(vp.kind)
                vps[i] = Vp(vp.kind, base + (vp.t - base) * scale)
            }
        }
        focalLengthMm = next
    }

    /** Rotate a point around the origin by [degrees]. */
    private fun rotatePoint(point: Offset, degrees: Float): Offset { val a = Math.toRadians(degrees.toDouble()); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat(); return Offset(point.x * c - point.y * s, point.x * s + point.y * c) }
    /** Horizontal camera-frustum edge directions, centred on the perpendicular (optical) axis. */
    fun fovEdgeDir(side: Float): Offset = rotatePoint(axisDir(1), side * fovDegrees * 0.5f)
    /** Prefer the selected vanishing point as the rotation pivot, then the first point on the guide, then the intersection. */
    fun pivot(kind: Int, selectedIndex: Int = -1): Offset = if (selectedIndex in vps.indices && vps[selectedIndex].kind == kind) pos(vps[selectedIndex]) else vps.firstOrNull { it.kind == kind }?.let(::pos) ?: origin
    /** Apply a guide rotation around its vanishing point; the shared intersection moves so both guides still meet. */
    fun rotateGuideFrom(kind: Int, pivot: Offset, origin0: Offset, rotation0: Float, vpPositions0: List<Pair<Int, Offset>>, degrees: Float) {
        val nextOrigin = pivot + rotatePoint(origin0 - pivot, degrees)
        guide(kind).rotation = ((rotation0 + degrees) % 360f + 360f) % 360f
        center = nextOrigin.x; horizon = nextOrigin.y
        for ((index, oldPosition) in vpPositions0) if (index in vps.indices && vps[index].kind == kind) {
            val nextPosition = pivot + rotatePoint(oldPosition - pivot, degrees)
            vps[index] = Vp(kind, coordinate(kind, nextPosition))
        }
    }
    fun rotateGuide(kind: Int, degrees: Float, selectedIndex: Int = -1) {
        val pivot = pivot(kind, selectedIndex); val origin0 = origin; val rotation0 = guide(kind).rotation
        val positions = vps.indices.filter { vps[it].kind == kind }.map { it to pos(vps[it]) }
        rotateGuideFrom(kind, pivot, origin0, rotation0, positions, degrees)
    }

    /**
     * A circular arc through two adjacent vanishing points. [sagitta] is the signed offset
     * of the arc midpoint from the chord midpoint. The construction is the same circle
     * geometry used by Krita's Curvilinear Perspective Assistant: the two VPs are exact
     * endpoints, rather than points displaced away from their assigned guide.
     */
    private fun circularArc(a: Offset, b: Offset, sagitta: Float, steps: Int = 48): List<Offset> {
        val chord = b - a
        val length = chord.getDistance()
        if (length < 0.001f || abs(sagitta) < 0.001f) return listOf(a, b)
        val half = length * 0.5f
        val dir = chord / length
        val normal = Offset(-dir.y, dir.x)
        val midpoint = (a + b) * 0.5f
        val h = sagitta
        val centerOffset = (half * half - h * h) / (2f * h)
        val circleCenter = midpoint - normal * centerOffset
        val startAngle = atan2(a.y - circleCenter.y, a.x - circleCenter.x)
        val endAngle = atan2(b.y - circleCenter.y, b.x - circleCenter.x)
        val midPoint = midpoint + normal * h
        val midAngle = atan2(midPoint.y - circleCenter.y, midPoint.x - circleCenter.x)
        val tau = (2.0 * Math.PI).toFloat()
        fun positive(v: Float) = ((v % tau) + tau) % tau
        val ccwSweep = positive(endAngle - startAngle)
        val ccwMid = positive(midAngle - startAngle)
        val sweep = if (ccwMid <= ccwSweep + 0.0001f) ccwSweep else ccwSweep - tau
        val radius = (a - circleCenter).getDistance()
        return (0..steps).map { i ->
            val angle = startAngle + sweep * (i.toFloat() / steps)
            Offset(circleCenter.x + cos(angle) * radius, circleCenter.y + sin(angle) * radius)
        }.toMutableList().apply { this[0] = a; this[lastIndex] = b }
    }

    /**
     * Curvilinear ray families join each vanishing point to its nearest neighbour on the
     * same guide. Every arc starts and ends on the actual VP centres. The two central rays
     * coincide with the straight horizon/guide; curvature grows quadratically with distance from
     * that middle ray. With a single VP, a radial fan is emitted to the visible extent.
     */
    fun curvilinearArcs(kind: Int, steps: Int = 48, span: Float = 4096f): List<PerspArc> {
        if (!guide(kind).curvilinear) return emptyList()
        val indices = vps.indices.filter { vps[it].kind == kind }.sortedBy { vps[it].t }
        if (indices.isEmpty()) return emptyList()
        val count = guide(kind).curvilinearRayCount.coerceIn(8, 48).let { if (it % 2 == 0) it else it + 1 }
        val strength = guide(kind).curvatureStrength.coerceIn(0f, 2f)
        val extent = span.coerceAtLeast(64f)
        val arcs = ArrayList<PerspArc>(maxOf(1, indices.size - 1) * count)
        if (indices.size >= 2) {
            for (pair in 0 until indices.lastIndex) {
                val leftIndex = indices[pair]
                val rightIndex = indices[pair + 1]
                val a = pos(vps[leftIndex]); val b = pos(vps[rightIndex])
                val chord = (b - a).getDistance()
                if (chord < 1f) continue
                val half = count / 2
                for (rayIndex in 0 until count) {
                    // Two central entries coincide with the flat horizon; each side then
                    // increases symmetrically from zero to maximum curvature.
                    val away = if (rayIndex < half) (half - 1 - rayIndex) / (half - 1).toFloat() else (rayIndex - half) / (half - 1).toFloat()
                    val side = if (rayIndex < half) -1f else 1f
                    val sagitta = side * chord * 0.22f * strength * away * away
                    arcs.add(PerspArc(leftIndex, rayIndex, circularArc(a, b, sagitta, steps)))
                }
            }
        } else {
            val vpIndex = indices.first()
            val start = pos(vps[vpIndex])
            val axis = axisDir(kind); val normal = axisNormal(kind)
            for (rayIndex in 0 until count) {
                val relativeAngle = Math.toRadians((rayIndex * 360f / count.toFloat()).toDouble()).toFloat()
                val direction = axis * cos(relativeAngle) + normal * sin(relativeAngle)
                val endpoint = start + direction * extent
                val vertical = sin(relativeAngle)
                val sagitta = extent * 0.16f * strength * vertical * abs(vertical)
                arcs.add(PerspArc(vpIndex, rayIndex, circularArc(start, endpoint, sagitta, steps)))
            }
        }
        return arcs
    }

    private fun projectToPolyline(points: List<Offset>, point: Offset): Offset {
        if (points.size < 2) return point
        var best = points.first(); var bestD = Float.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]; val d = b - a
            val den = d.getDistanceSquared()
            val t = if (den < 0.000001f) 0f else (((point - a).x * d.x + (point - a).y * d.y) / den).coerceIn(0f, 1f)
            val q = a + d * t; val ds = (q - point).getDistanceSquared()
            if (ds < bestD) { bestD = ds; best = q }
        }
        return best
    }

    fun guidePoints(kind: Int, span: Float, steps: Int = 64): List<Offset> {
        val d = axisDir(kind)
        return listOf(origin - d * span, origin + d * span)
    }

    /** The guide axis (including the horizon) remains straight even while its VP rays curve. */
    fun distanceToGuide(kind: Int, point: Offset): Float {
        val d = axisDir(kind); val q = point - origin
        val projected = origin + d * (q.x * d.x + q.y * d.y)
        return (projected - point).getDistance()
    }

    fun projectToGuide(kind: Int, anchor: Offset, point: Offset): Offset {
        val d = axisDir(kind); val q = point - anchor
        return anchor + d * (q.x * d.x + q.y * d.y)
    }

    /** Project onto an infinite line defined by an actual guide or vanishing point. */
    private fun projectToLine(point: Offset, linePoint: Offset, direction: Offset): Offset {
        val length = direction.getDistance()
        if (length < 0.0001f) return point
        val d = direction / length
        val delta = point - linePoint
        return linePoint + d * (delta.x * d.x + delta.y * d.y)
    }

    private fun nearestOnPolyline(points: List<Offset>, point: Offset): Pair<Int, Offset> {
        if (points.size < 2) return 0 to (points.firstOrNull() ?: point)
        var bestIndex = 0; var bestPoint = points.first(); var bestD = Float.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]; val d = b - a
            val den = d.getDistanceSquared()
            val t = if (den < 0.000001f) 0f else (((point - a).x * d.x + (point - a).y * d.y) / den).coerceIn(0f, 1f)
            val q = a + d * t; val ds = (q - point).getDistanceSquared()
            if (ds < bestD) { bestD = ds; bestIndex = i; bestPoint = q }
        }
        return bestIndex to bestPoint
    }

    /** Return the portion of the already selected curved ray between two raw line-tool endpoints. */
    fun magnetPathBetween(a: Offset, b: Offset, state: PerspectiveMagnetState): List<Offset>? {
        val points = state.latchedPoints ?: return null
        if (points.size < 2) return null
        val (ia, pa) = nearestOnPolyline(points, a)
        val (ib, pb) = nearestOnPolyline(points, b)
        return if (ia <= ib) buildList {
            add(pa); for (i in ia + 1..ib) add(points[i]); add(pb)
        } else buildList {
            add(pa); for (i in ia downTo ib + 1) add(points[i]); add(pb)
        }
    }

    /**
     * With VP magnetism enabled, vanishing-point rays are authoritative and the horizon /
     * perpendicular axes are not competing candidates. Turn it off to magnetize only to
     * those two guide lines. A stroke latches once and remains on the selected path.
     */
    fun magnet(
        anchor: Offset,
        point: Offset,
        radius: Float,
        state: PerspectiveMagnetState,
        raySpan: Float = 4096f,
        hoverDirection: Offset? = null
    ): Offset {
        fun normalized(v: Offset): Offset? {
            val length = v.getDistance()
            return if (length < 0.0001f) null else v / length
        }
        fun tangentAt(points: List<Offset>, near: Offset): Offset? {
            if (points.size < 2) return null
            var best = Float.MAX_VALUE; var tangent: Offset? = null
            for (i in 0 until points.lastIndex) {
                val a = points[i]; val b = points[i + 1]; val d = b - a
                val den = d.getDistanceSquared()
                val t = if (den < 0.000001f) 0f else (((near - a).x * d.x + (near - a).y * d.y) / den).coerceIn(0f, 1f)
                val q = a + d * t; val ds = (q - near).getDistanceSquared()
                if (ds < best) { best = ds; tangent = normalized(d) }
            }
            return tangent
        }
        val arcs = state.cachedArcs ?: arrayOf(
            curvilinearArcs(0, span = raySpan), curvilinearArcs(1, span = raySpan)
        ).also { state.cachedArcs = it }

        if (state.kind < 0) {
            val travelDirection = normalized(point - anchor)
            val hover = hoverDirection?.let { normalized(it) }
            val intent = when {
                travelDirection != null && hover != null -> normalized(travelDirection * 0.35f + hover * 0.65f)
                travelDirection != null -> travelDirection
                hover != null -> hover
                else -> null
            } ?: return point

            // Choose the perspective family from the user's intended stroke direction first.
            // This prevents a near-by perpendicular VP family from stealing horizontal / vertical
            // strokes merely because its ray happens to pass closer to the contact point.
            val preferredKind = (0..1).maxByOrNull { kind ->
                val axis = axisDir(kind)
                abs(intent.x * axis.x + intent.y * axis.y)
            } ?: 0
            var bestKind = preferredKind; var bestVp = -1; var bestCurve = -1
            var bestProjection = point; var bestScore = Float.MAX_VALUE
            var bestArc: List<Offset>? = null
            var bestLinePoint: Offset? = null; var bestLineDirection: Offset? = null
            val directionWeight = radius.coerceAtLeast(1f).let { it * it * 3.0f }

            fun consider(kind: Int, vpIndex: Int, curveIndex: Int, projected: Offset, tangent: Offset?, arc: List<Offset>? = null, linePoint: Offset? = null, lineDirection: Offset? = null) {
                val distance = (projected - point).getDistanceSquared()
                val t = tangent?.let(::normalized)
                val alignmentPenalty = if (t == null) directionWeight else {
                    val dot = abs(intent.x * t.x + intent.y * t.y).coerceIn(0f, 1f)
                    directionWeight * (1f - dot) * (1f - dot)
                }
                val score = distance + alignmentPenalty
                if (score < bestScore) {
                    bestScore = score; bestKind = kind; bestVp = vpIndex; bestCurve = curveIndex
                    bestProjection = projected; bestArc = arc; bestLinePoint = linePoint; bestLineDirection = lineDirection
                }
            }

            val anyVp = vps.isNotEmpty()
            val candidateKinds = if (vanishingPointMagnet && anyVp) listOf(preferredKind) else listOf(0, 1)
            for (kind in candidateKinds) {
                if (vanishingPointMagnet && anyVp) {
                    if (guide(kind).curvilinear) {
                        // Do not magnetize to one arbitrary neighbouring ray. Build the
                        // intermediate/average path between each adjacent pair of rays, then
                        // choose the average path that best matches the stroke's position and
                        // direction. This keeps curvilinear strokes parallel to the local fan.
                        val byFamily = arcs[kind].groupBy { it.vpIndex }
                        for ((vpIndex, family) in byFamily) {
                            val ordered = family.sortedBy { it.curveIndex }
                            for (pairIndex in 0 until ordered.lastIndex) {
                                val first = ordered[pairIndex].points
                                val second = ordered[pairIndex + 1].points
                                if (first.size < 2 || second.size != first.size) continue
                                val average = first.indices.map { i -> (first[i] + second[i]) * 0.5f }
                                val projected = projectToPolyline(average, point)
                                consider(kind, vpIndex, pairIndex, projected, tangentAt(average, projected), average)
                            }
                            // A single-ray family has no neighbour to average with.
                            if (ordered.size == 1) {
                                val path = ordered.first().points
                                val projected = projectToPolyline(path, point)
                                consider(kind, vpIndex, ordered.first().curveIndex, projected, tangentAt(path, projected), path)
                            }
                        }
                    } else {
                        for (i in vps.indices) if (vps[i].kind == kind) {
                            val vp = pos(vps[i])
                            val direction = normalized(anchor - vp) ?: intent
                            val projected = projectToLine(point, vp, direction)
                            consider(kind, i, -1, projected, direction, linePoint = vp, lineDirection = direction)
                        }
                    }
                } else if (!vanishingPointMagnet || !anyVp) {
                    val direction = axisDir(kind)
                    val projected = projectToLine(point, origin, direction)
                    consider(kind, -1, -1, projected, direction, linePoint = origin, lineDirection = direction)
                }
            }

            // A visible perspective layer must never leave a brush stroke as freehand. If VP
            // mode is selected but no VP has been placed, use the guide axes until one exists.
            if (bestScore == Float.MAX_VALUE) {
                val direction = axisDir(0)
                bestProjection = projectToLine(point, origin, direction)
                bestKind = 0; bestLinePoint = origin; bestLineDirection = direction
            }
            state.kind = bestKind; state.vpIndex = bestVp; state.curveIndex = bestCurve
            state.latchedPoints = bestArc; state.linePoint = bestLinePoint; state.lineDirection = bestLineDirection
            state.blendProgress = 1f; state.lastPoint = point
            return bestProjection
        }

        state.lastPoint = point
        return state.latchedPoints?.let { projectToPolyline(it, point) }
            ?: projectToLine(point, state.linePoint ?: origin, state.lineDirection ?: axisDir(state.kind))
    }

}

/** Convert horizontal FOV to focal length for a 36 mm full-frame sensor. */
fun focalLengthForFov(fovDegrees: Float): Float = (36f / (2f * tan(Math.toRadians(fovDegrees.coerceIn(4.1f, 170f) / 2.0)).toFloat())).coerceIn(1.57f, 500f)

/** Convert focal length in millimetres to horizontal FOV for a 36 mm sensor. */
fun fovFromFocalLength(focalLengthMm: Float): Float = Math.toDegrees(2.0 * atan(36.0 / (2.0 * focalLengthMm.coerceIn(1.57f, 500f)))).toFloat().coerceIn(4.1f, 170f)

/** Manual camera move / zoom: writes keys at time t for the channels that changed. */
fun camNudge(ed: Editor, cut: Cut, cam: Camera, t: Int, dx: Float, dy: Float, z: Float, dr: Float) {
    if (abs(dr) > 0.01f) setKey(cam.rs, t, cam.rot(t) + dr, ed.interp, ed.ease)
    if (abs(dx) > 1e-3f) setKey(cam.xs, t, cam.x(t, cut.w) + dx, ed.interp, ed.ease)
    if (abs(dy) > 1e-3f) setKey(cam.ys, t, cam.y(t, cut.h) + dy, ed.interp, ed.ease)
    if (abs(z - 1f) > 1e-4f) setKey(cam.ss, t, (cam.sc(t) * z).coerceIn(0.05f, 20f), ed.interp, ed.ease)
}

fun applyInterp(cam: Camera, t: Int, type: Int, ease: Float) {
    for (ch in listOf(cam.xs, cam.ys, cam.ss, cam.rs)) { val i = ch.indexOfLast { it.t <= t }; if (i >= 0) ch[i] = CamKey(ch[i].t, ch[i].v, type, ease) }
}

/** Bakes a decaying random shake into x / y keys over [t0, t0 + len]. axis: 0 both, 1 vertical only, 2 horizontal only. */
fun bakeShake(cut: Cut, cam: Camera, t0: Int, len: Int, amt: Float, axis: Int) {
    val rnd = java.util.Random(t0 * 31L + len)
    for (ci in 0..1) {
        if ((axis == 1 && ci == 0) || (axis == 2 && ci == 1)) continue
        val ch = cam.chan(ci); val def = if (ci == 0) cut.w / 2f else cut.h / 2f
        val base = (0..len).map { evalChan(ch, t0 + it, def) }
        ch.removeAll { it.t in t0..(t0 + len) }
        for (i in 0..len) {
            val decay = 1f - i.toFloat() / len
            setKey(ch, t0 + i, base[i] + if (i == len) 0f else (rnd.nextFloat() * 2f - 1f) * amt * decay)
        }
    }
}

/** A selected diamond key: ch 0..3 = X / Y / scale / rotation channel, -1 = the key time on all four channels (summary row). */
data class KeySel(val layerId: Int, val ch: Int, val t: Int)

/** Key channel i (0 X, 1 Y, 2 scale, 3 rotation) of a Camera layer or Transformation folder; null for other layers. */
fun Layer.keyChan(i: Int): SnapshotStateList<CamKey>? = cam?.chan(i) ?: if (isGroup && transformFolder) transformChan(i) else null

/** Every key time of a Camera layer / Transformation folder (union of the four channels), sorted. */
fun keyTimes(l: Layer): List<Int> = (0..3).flatMap { i -> l.keyChan(i)?.map { it.t } ?: emptyList() }.distinct().sorted()

/** Every key of every channel of the layer as a selection. */
fun selectAllKeys(l: Layer): List<KeySel> = (0..3).flatMap { i -> l.keyChan(i)?.map { KeySel(l.id, i, it.t) } ?: emptyList() }

/** The interpolation shared by all selected keys, or null when they differ (or none exist). */
fun selInterp(cut: Cut, sel: List<KeySel>): Int? {
    val found = HashSet<Int>()
    for (s in sel) {
        val l = cut.layers.firstOrNull { it.id == s.layerId } ?: continue
        for (ci in (if (s.ch < 0) 0..3 else s.ch..s.ch)) l.keyChan(ci)?.firstOrNull { it.t == s.t }?.let { found.add(it.interp) }
    }
    return if (found.size == 1) found.first() else null
}

/** Sets interpolation (type, or keeps it when null) and easing on the selected keys. */
fun applyInterpSel(cut: Cut, sel: List<KeySel>, type: Int?, ease: Float) {
    for (s in sel) {
        val l = cut.layers.firstOrNull { it.id == s.layerId } ?: continue
        for (ci in (if (s.ch < 0) 0..3 else s.ch..s.ch)) {
            val ch = l.keyChan(ci) ?: continue
            val i = ch.indexOfFirst { it.t == s.t }
            if (i >= 0) ch[i] = CamKey(ch[i].t, ch[i].v, type ?: ch[i].interp, ease)
        }
    }
}

/** Moves the selected keys together by dt frames (never below frame 0). A key already sitting on a landing frame is replaced. Returns the moved selection. */
fun moveKeysSel(cut: Cut, sel: List<KeySel>, dt: Int): List<KeySel> {
    if (sel.isEmpty()) return sel
    val d = max(dt, -sel.minOf { it.t })
    if (d == 0) return sel
    for ((lid, list) in sel.groupBy { it.layerId }) {
        val l = cut.layers.firstOrNull { it.id == lid } ?: continue
        for (ci in 0..3) {
            val ch = l.keyChan(ci) ?: continue
            val times = list.filter { it.ch < 0 || it.ch == ci }.map { it.t }.toSet()
            val moving = ch.filter { it.t in times }
            if (moving.isEmpty()) continue
            ch.removeAll { it.t in times }
            for (k in moving) { ch.removeAll { it.t == k.t + d }; setKey(ch, k.t + d, k.v, k.interp, k.ease) }
        }
    }
    return sel.map { KeySel(it.layerId, it.ch, it.t + d) }
}


/** Duplicate selected camera / Transformation keys by [delta] frames, preserving values and interpolation. */
fun duplicateKeysSel(cut: Cut, sel: List<KeySel>, delta: Int? = null): List<KeySel> {
    if (sel.isEmpty()) return emptyList()
    // Put the copied block immediately after the selected time span, preserving the originals.
    val offset = delta ?: ((sel.maxOf { it.t } - sel.minOf { it.t }) + 1).coerceAtLeast(1)
    val copies = ArrayList<KeySel>()
    for ((lid, selected) in sel.groupBy { it.layerId }) {
        val layer = cut.layers.firstOrNull { it.id == lid } ?: continue
        for (ci in 0..3) {
            val times = selected.filter { it.ch < 0 || it.ch == ci }.map { it.t }.toSet()
            val channel = layer.keyChan(ci) ?: continue
            val source = channel.filter { it.t in times }
            for (key in source) {
                val target = (key.t + offset).coerceAtLeast(0)
                val existing = channel.indexOfFirst { it.t == target }
                val copy = CamKey(target, key.v, key.interp, key.ease)
                if (existing >= 0) channel[existing] = copy
                else {
                    val at = channel.indexOfFirst { it.t > target }
                    channel.add(if (at < 0) channel.size else at, copy)
                }
                copies.add(KeySel(lid, ci, target))
            }
        }
    }
    return copies.distinct()
}

/** Delete only the selected channel keys; a summary-row selection (-1) covers all four channels. */
fun deleteKeysSel(cut: Cut, sel: List<KeySel>) {
    for ((lid, selected) in sel.groupBy { it.layerId }) {
        val layer = cut.layers.firstOrNull { it.id == lid } ?: continue
        for (ci in 0..3) {
            val times = selected.filter { it.ch < 0 || it.ch == ci }.map { it.t }.toSet()
            layer.keyChan(ci)?.removeAll { it.t in times }
        }
    }
}

/** True when some layer has two or more distinct selected key times (the minimum for a shake). */
fun shakeReady(sel: List<KeySel>): Boolean = sel.groupBy { it.layerId }.any { (_, v) -> v.map { it.t }.distinct().size >= 2 }

/**
 * Shake between selected keys. On every layer with two or more selected key times, new X / Y keys (linear) are created every
 * `density` frames strictly between the first and last selected time; each rides on the original curve plus a random offset of up to
 * `strength` px. A locked axis is left alone. Existing keys on those frames are offset instead of duplicated. Returns true if any key was written.
 */
fun shakeSelected(cut: Cut, sel: List<KeySel>, strength: Float, density: Int, lockH: Boolean, lockV: Boolean): Boolean {
    if (lockH && lockV) return false
    val step = max(1, density); val rnd = java.util.Random(); var did = false
    for ((lid, list) in sel.groupBy { it.layerId }) {
        val l = cut.layers.firstOrNull { it.id == lid } ?: continue
        val times = list.map { it.t }.distinct().sorted()
        if (times.size < 2) continue
        val ts = ArrayList<Int>(); var t = times.first() + step
        while (t < times.last()) { ts.add(t); t += step }
        if (ts.isEmpty()) continue
        for (ci in 0..1) {
            if ((ci == 0 && lockH) || (ci == 1 && lockV)) continue
            val ch = l.keyChan(ci) ?: continue
            val def = if (l.cam != null) (if (ci == 0) cut.w / 2f else cut.h / 2f) else 0f
            val base = ts.map { evalChan(ch, it, def) } // read the original curve first so new keys do not affect each other
            ts.forEachIndexed { i, tt ->
                val v = base[i] + (rnd.nextFloat() * 2f - 1f) * strength
                val k = ch.indexOfFirst { it.t == tt }
                if (k >= 0) ch[k] = CamKey(tt, v, ch[k].interp, ch[k].ease) else setKey(ch, tt, v, 1, 0.33f)
                did = true
            }
        }
    }
    return did
}

/**
 * Draws the camera movement as red rectangles, one per existing diamond key time (no new keys, no extra samples), on a new layer.
 * Matching corners of consecutive key rectangles (upper left, upper right, lower right, lower left) are joined by red lines.
 */
fun rasterizeCamera(cut: Cut, cl: Layer) {
    val cam = cl.cam ?: return
    val ticks = keyTimes(cl)
    if (ticks.isEmpty()) return
    val total = cut.total()
    val brush = Brush("", size = 4f, hardness = 1f, spacing = 0.1f)
    val thin = Brush("", size = 1.5f, hardness = 1f, spacing = 0.1f) // the joining lines are thinner than the frame rectangles
    val l = Layer(cut.nextLayer, "Camera path"); cut.nextLayer++
    val cel = l.cels[0]; cel.dur = total
    fun ink(pts: List<Offset>, b: Brush = brush): Ink {
        val p = FloatArray(pts.size * 4)
        pts.forEachIndexed { i, o -> p[i * 4] = o.x; p[i * 4 + 1] = o.y; p[i * 4 + 2] = 1f; p[i * 4 + 3] = 1f }
        return Ink(p, 0xFFE53935.toInt(), b)
    }
    /** A dotted line: short dashes (dash 10 px, gap 12 px) between a and b. */
    fun dotted(a: Offset, b: Offset) {
        val dx = b.x - a.x; val dy = b.y - a.y; val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val dash = 10f; val gap = 12f
        val step = dash + gap
        val n = minOf(((len + gap) / step).toInt() + 1, 600)
        for (k in 0 until n) {
            val s0 = k * step; if (s0 >= len) break
            val s1 = minOf(s0 + dash, len)
            cel.strokes.add(ink(shapePts(0, Offset(a.x + dx * s0 / len, a.y + dy * s0 / len), Offset(a.x + dx * s1 / len, a.y + dy * s1 / len)), thin))
        }
    }
    val corners = ArrayList<List<Offset>>() // per key: TL, TR, BR, BL in document space
    for (t in ticks) {
        val s = cam.sc(t); val fw = cam.w * s; val fh = cam.h * s; val x = cam.x(t, cut.w); val y = cam.y(t, cut.h)
        val ang = Math.toRadians(cam.rot(t).toDouble()); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        val local = listOf(Offset(-fw / 2, -fh / 2), Offset(fw / 2, -fh / 2), Offset(fw / 2, fh / 2), Offset(-fw / 2, fh / 2))
        val c = local.map { Offset(x + it.x * ca - it.y * sa, y + it.x * sa + it.y * ca) }
        corners.add(c)
        cel.strokes.add(ink(shapePts(1, local[0], local[2]).map { Offset(x + it.x * ca - it.y * sa, y + it.x * sa + it.y * ca) }))
    }
    for (i in 0 until corners.size - 1) for (k in 0..3) dotted(corners[i][k], corners[i + 1][k])
    cut.layers.add(cut.layers.indexOf(cl) + 1, l); cut.layerId = l.id; cut.invalidateStructureCaches()
}

/** Puts the camera back to the default framing at time t. */
fun resetCamera(cut: Cut, cam: Camera, t: Int) {
    setKey(cam.xs, t, cut.w / 2f); setKey(cam.ys, t, cut.h / 2f); setKey(cam.ss, t, 1f); setKey(cam.rs, t, 0f)
}

/** Apply the same segment interpolation/easing used by camera channels to a transformation folder. */
fun applyInterp(folder: Layer, t: Int, type: Int, ease: Float) {
    for (channel in folder.transformChannels()) {
        val i = channel.indexOfLast { it.t <= t }
        if (i >= 0) { val k = channel[i]; channel[i] = CamKey(k.t, k.v, type, ease) }
    }
}
