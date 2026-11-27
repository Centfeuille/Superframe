package com.example.inbetween

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** One drawing (a held pose): its operations plus how many timeline cells it lasts. dur 0 only exists briefly while shifting a layer. */
data class TransformKey(val t: Int, val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val interp: Int = 1, val ease: Float = 0.33f)
data class GroupTransform(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f)
/** Session-only alignment transform for an onion-skin reference. It never changes source cels. */
data class ReferencePose(val x: Float = 0f, val y: Float = 0f, val scaleX: Float = 1f, val scaleY: Float = 1f, val rotation: Float = 0f, val flipX: Boolean = false, val flipY: Boolean = false)

/** Shared invalidation epoch for derived timeline data. Bumped only when timing-affecting data changes. */
private object TimelineRevision {
    private val counter = AtomicLong(0L)
    fun current(): Long = counter.get()
    fun bump() { counter.incrementAndGet() }
}
internal fun bumpTimelineRevision() = TimelineRevision.bump()

class Cel(dur: Int = 1, linkedStrokes: androidx.compose.runtime.snapshots.SnapshotStateList<Ink>? = null) {
    private var storedDuration by mutableIntStateOf(dur)
    var dur: Int
        get() = storedDuration
        set(value) {
            if (storedDuration != value) {
                storedDuration = value
                TimelineRevision.bump()
            }
        }
    init { TimelineRevision.bump() }
    /** A clone shares this very list with its original, so a change in either shows in both (and in the tile cache, keyed by the list). */
    val strokes: androidx.compose.runtime.snapshots.SnapshotStateList<Ink> = linkedStrokes ?: mutableStateListOf<Ink>()
    /** Timeline annotation: keep this drawing visible as a persistent canvas reference. */
    var persistent by mutableStateOf(false)
    /** Optional animation inbetween label, e.g. 1a, 1b. */
    var inbetweenName by mutableStateOf("")
    /** Vector layers only: how the span that starts at this keyframe is interpolated / baked (null = defaults). Saved in V24. */
    var vecCfg by mutableStateOf<InterpolationConfig?>(null)
    /** Bumped when the strokes are edited in place (point drag) so cached live-interpolated frames built from this key are rebuilt. */
    var vecRev = 0
    fun copy() = Cel(dur).also { it.strokes.addAll(strokes); it.persistent = persistent; it.inbetweenName = inbetweenName; it.vecCfg = vecCfg }
    fun emptyCopy() = Cel(dur).also { it.vecCfg = vecCfg }
    /** Non-unique copy: same drawing, own duration. */
    fun cloneLinked() = Cel(dur, strokes).also { it.persistent = persistent; it.inbetweenName = inbetweenName; it.vecCfg = vecCfg }
    /** True when [other] shows the same drawing (this cel or a clone of it). */
    fun sameDrawing(other: Cel) = strokes === other.strokes
}

/** A layer (own timing: a sequence of cels) or a group folder (isGroup). Layers of a group sit directly below its header. */
class Layer(val id: Int, name: String, val isGroup: Boolean = false, val kind: Int = 0, val cam: Camera? = null, val persp: Persp? = null) { // kind: 0 drawing, 2 camera, 3 perspective, 4 background, 5 reference image, 6 audio, 7 video
    var name by mutableStateOf(name)
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var opacity by mutableFloatStateOf(1f)
    var group by mutableIntStateOf(-1)
    var collapsed by mutableStateOf(false)
    /** A vector layer: a drawing layer (kind 0) whose cels are keyframes of vector strokes (Ink kind 5). Saved in V24. */
    var vector by mutableStateOf(false)
    var expanded by mutableStateOf(false) // camera: show x / y / scale key rows
    var transformFolder by mutableStateOf(false)
    /** Folders only: Uniform timing is on. The drawing / vector layers attached to it share one frame count and timing. Saved in V26. */
    var uniformTiming by mutableStateOf(false)
    /** Drawing / vector layers only: this layer follows the Uniform timing of the folder it is in (a layer put in the folder later starts detached). Saved in V26. */
    var uniformAttached by mutableStateOf(false)
    var imageRevision by mutableIntStateOf(0) // invalidates canvas drawing when a reference-image matrix changes
    /** Reference layer only, session state (not in .inb): the images are anchored to the screen ("relative position"). Their matrices are then in HOME-view coordinates, see DrawingCanvas.pinMatrix. */
    var refPinned by mutableStateOf(false)
    var mediaUri by mutableStateOf("")
    private var storedMediaDurationMs by mutableLongStateOf(0L)
    var mediaDurationMs: Long
        get() = storedMediaDurationMs
        set(value) { if (storedMediaDurationMs != value) { storedMediaDurationMs = value; TimelineRevision.bump() } }
    private var storedMediaStartFrame by mutableIntStateOf(0)
    var mediaStartFrame: Int
        get() = storedMediaStartFrame
        set(value) { if (storedMediaStartFrame != value) { storedMediaStartFrame = value; TimelineRevision.bump() } }
    private var storedMediaDurationFrames by mutableIntStateOf(1)
    var mediaDurationFrames: Int
        get() = storedMediaDurationFrames
        set(value) { if (storedMediaDurationFrames != value) { storedMediaDurationFrames = value; TimelineRevision.bump() } }
    // Independent transform for video reference footage, in cut/document coordinates.
    var mediaX by mutableFloatStateOf(0f)
    var mediaY by mutableFloatStateOf(0f)
    var mediaScale by mutableFloatStateOf(1f)
    var mediaRotation by mutableFloatStateOf(0f)
    fun mediaFrames(fps: Int): Int = (mediaDurationMs.coerceAtLeast(1L).toDouble() * fps.coerceAtLeast(1) / 1000.0)
        .toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    // Independent animation channels, matching Camera layer semantics.
    val transformX = mutableStateListOf<CamKey>()
    val transformY = mutableStateListOf<CamKey>()
    val transformScale = mutableStateListOf<CamKey>()
    val transformRotation = mutableStateListOf<CamKey>()
    fun transformChan(i: Int) = when (i) { 0 -> transformX; 1 -> transformY; 2 -> transformScale; else -> transformRotation }
    fun transformChannels() = listOf(transformX, transformY, transformScale, transformRotation)
    fun transformAt(t: Int): GroupTransform = GroupTransform(
        evalChan(transformX, t, 0f), evalChan(transformY, t, 0f),
        evalChan(transformScale, t, 1f), evalChan(transformRotation, t, 0f)
    )
    fun setTransformAt(t: Int, x: Float, y: Float, scale: Float, rotation: Float, interp: Int = 1, ease: Float = 0.33f) {
        val frame = t.coerceAtLeast(0)
        setKey(transformX, frame, x, interp, ease)
        setKey(transformY, frame, y, interp, ease)
        setKey(transformScale, frame, scale.coerceIn(0.05f, 20f), interp, ease)
        setKey(transformRotation, frame, rotation, interp, ease)
    }
    val cels = mutableStateListOf(Cel())
    // Prefix sums make startOf O(1) and indexAt O(log n). Duration and structural changes
    // invalidate this cache through TimelineRevision; cels.size also catches removals.
    private var prefixRevision = Long.MIN_VALUE
    private var prefixSize = -1
    private var durationPrefix = IntArray(0)

    private fun ensureDurationPrefix() {
        val revision = TimelineRevision.current()
        if (prefixRevision == revision && prefixSize == cels.size) return
        val prefix = IntArray(cels.size + 1)
        for (i in cels.indices) prefix[i + 1] = (prefix[i].toLong() + cels[i].dur).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        durationPrefix = prefix
        prefixSize = cels.size
        prefixRevision = revision
    }
    fun invalidateTimelineCache() { prefixRevision = Long.MIN_VALUE }
    init { if (isGroup || (kind != 0 && kind != 4 && kind != 5)) cels.clear() } // kind 4 = background: one drawing that lasts the whole cut
    fun total(): Int = when {
        cam != null -> cam.maxT() + 1
        isGroup && transformFolder -> (transformChannels().flatMap { it }.maxOfOrNull { it.t } ?: -1) + 1
        kind == 4 || kind == 5 -> 1
        kind == 6 || kind == 7 -> (mediaStartFrame.toLong() + mediaFrames(24)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else -> startOf(cels.size)
    }
    fun startOf(i: Int): Int {
        ensureDurationPrefix()
        return durationPrefix[i.coerceIn(0, cels.size)]
    }
    fun indexAt(t: Int): Int {
        if (kind == 4 || kind == 5 || kind == 6 || kind == 7) return if (cels.isEmpty()) -1 else 0
        ensureDurationPrefix()
        if (cels.isEmpty() || t >= durationPrefix[cels.size]) return -1
        // Find the first cel whose cumulative end is strictly greater than t.
        var low = 0
        var high = cels.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (durationPrefix[mid + 1] <= t) low = mid + 1 else high = mid
        }
        return low.takeIf { it < cels.size } ?: -1
    }
    fun celAt(t: Int): Cel? = cels.getOrNull(indexAt(t))
    /** The cel to DRAW at [t]: for a vector layer with interpolation set on a key, the in-between frames of its hold are interpolated live. */
    fun drawCel(t: Int): Cel? = if (vector) VectorInkPreview.wrap(this, VectorLive.celAt(this, t)) else celAt(t)
    /** Out of Peg: the previous (side < 0) or following (side > 0) drawing relative to the frame at [t]. */
    fun adjacentCel(t: Int, side: Int): Cel? {
        val idx = indexAt(t)
        return cels.getOrNull(if (side < 0) (if (idx < 0) cels.lastIndex else idx - 1) else if (idx < 0) -1 else idx + 1)
    }
}

/** Snapshot of the timeline structure (layers, frames, timing, camera keys...) — drawings are shared, not copied. */
fun Cut.transformFor(layer: Layer, t: Int): GroupTransform {
    val folder = layerById(layer.group)?.takeIf { it.isGroup && it.transformFolder }
    return folder?.transformAt(t) ?: GroupTransform()
}

class LSnap(
    val layer: Layer, val name: String, val visible: Boolean, val locked: Boolean, val opacity: Float, val group: Int, val collapsed: Boolean,
    val cels: List<Cel>, val durs: List<Int>, val persistent: List<Boolean>, val inbetweenNames: List<String>, val keys: List<List<CamKey>>?, val camW: Int, val camH: Int,
    val transformFolder: Boolean, val transformChannels: List<List<CamKey>>, val vps: List<Vp>?, val horizon: Float, val center: Float, val perspState: PerspSnapshot?,
    val mediaUri: String, val mediaDurationMs: Long, val mediaDurationFrames: Int, val mediaStartFrame: Int,
    val mediaX: Float, val mediaY: Float, val mediaScale: Float, val mediaRotation: Float,
    val vecCfgs: List<InterpolationConfig?> = emptyList(),
    val uniformTiming: Boolean = false, val uniformAttached: Boolean = false
)
class Snap(val layers: List<LSnap>, val layerId: Int, val time: Int)
private class StrokeAct(val cel: Cel, val ink: Ink, val backup: StrokeBackup? = null)
private class StructAct(val before: Snap, val after: Snap)
private class ClearAct(val strokes: androidx.compose.runtime.snapshots.SnapshotStateList<Ink>, val removed: List<Ink>)
private class ReferenceMatrixAct(val layer: Layer, val ink: Ink, val before: FloatArray, val after: FloatArray)
/** Several reference images changed together (two-finger transform of all images, flip, relative-position switch). */
private class ReferenceMatricesAct(val layer: Layer, val inks: List<Ink>, val before: List<FloatArray>, val after: List<FloatArray>, val pinBefore: Boolean, val pinAfter: Boolean)
/** One reference image removed from its layer's cel (index = where it sat, so undo restores the stacking order). */
private class ReferenceRemoveAct(val layer: Layer, val cel: Cel, val ink: Ink, val index: Int)

data class PreparedReferenceImage(val path: String, val width: Int, val height: Int, val matrix: FloatArray)

/** Read sampled dimensions and initial placement; only the provider URI is retained, never image bytes. */
fun prepareReferenceImage(location: String, cutW: Int, cutH: Int): PreparedReferenceImage? {
    if (location.isBlank()) return null
    val (iw, ih) = ReferenceBitmapCache.dimensions(location) ?: return null
    val scale = minOf(cutW.toFloat() / iw, cutH.toFloat() / ih, 1f).coerceAtLeast(0.01f)
    val x = (cutW - iw * scale) / 2f; val y = (cutH - ih * scale) / 2f
    return PreparedReferenceImage(location, iw, ih, floatArrayOf(scale, 0f, x, 0f, scale, y, 0f, 0f, 1f))
}

class Cut(name: String, fps: Int, w: Int, h: Int, dpi: Int, val id: Long = System.currentTimeMillis()) {
    var name by mutableStateOf(name) // renamable from the main menu (stored in the cut's own file)
    private var storedFps by mutableIntStateOf(fps)
    var fps: Int
        get() = storedFps
        set(value) { if (storedFps != value) { storedFps = value; TimelineRevision.bump(); totalRevision = Long.MIN_VALUE } }
    var w by mutableIntStateOf(w)
    var h by mutableIntStateOf(h)
    var dpi by mutableIntStateOf(dpi)
    val orig = intArrayOf(w, h, dpi, fps)
    var lockW by mutableStateOf(false); var lockH by mutableStateOf(false); var lockDpi by mutableStateOf(false)
    var lockFps by mutableStateOf(false); var lockA by mutableStateOf(false)
    var aspect = w / h.toFloat()

    val layers = mutableStateListOf(Layer(0, "A"), Layer(1, "B")) // bottom -> top; single-letter drawing-layer names follow their position (relabelLayers)
    var time by mutableIntStateOf(0)
    var layerId by mutableIntStateOf(0)
    var selAll by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var nextLayer = 2
    var winStart by mutableStateOf<Int?>(null) // playback window [ ... ]
    var winEnd by mutableStateOf<Int?>(null)
    private val undoStack = ArrayDeque<Any>()
    /** Counts every undoable change (stroke, structure, undo, redo). Crash-recovery snapshots are taken when it moves. Not observable state. */
    var edits = 0L
    private var structureRevision = 0L
    private var layerMapRevision = Long.MIN_VALUE
    private var cachedLayerMap: Map<Int, Layer> = emptyMap()
    private var totalRevision = Long.MIN_VALUE
    private var cachedTotal = 1

    fun invalidateStructureCaches() {
        structureRevision++
        relabelLayers()
        layerMapRevision = Long.MIN_VALUE
        totalRevision = Long.MIN_VALUE
        layers.forEach { it.invalidateTimelineCache() }
    }
    fun layerById(id: Int): Layer? {
        if (layerMapRevision != structureRevision) {
            cachedLayerMap = layers.associateBy { it.id }
            layerMapRevision = structureRevision
        }
        return cachedLayerMap[id]
    }
    private val redoStack = ArrayDeque<Any>()

    var thumb by mutableStateOf<ImageBitmap?>(null)
    fun refreshThumb() { thumb = runCatching { val (ow, oh) = outSize(this); renderBitmap(this, 0, 320, (320 * oh / ow).coerceAtLeast(1)).asImageBitmap() }.getOrNull() }

    val layer: Layer? get() = layerById(layerId)?.takeIf { !it.isGroup }
    val drawLayer: Layer? get() = layer?.takeIf { it.kind == 0 || it.kind == 4 }
    val frameLayer: Layer? get() = layer?.takeIf { it.kind == 0 }
    fun activeCamera(): Layer? = layers.firstOrNull { it.kind == 2 && it.visible }
    fun total(): Int {
        val revision = TimelineRevision.current()
        if (totalRevision == revision) return cachedTotal
        cachedTotal = maxOf(1, layers.maxOfOrNull {
            if (it.kind == 6 || it.kind == 7) (it.mediaStartFrame.toLong() + it.mediaFrames(fps)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else it.total()
        } ?: 1)
        totalRevision = revision
        return cachedTotal
    }

    // ----- playback window -----
    fun firstTick() = (winStart ?: 0).coerceIn(0, total() - 1)
    /** The end may intentionally extend beyond the current artwork for blank lead-out playback. */
    fun lastTick() = (winEnd ?: (total() - 1)).coerceAtLeast(firstTick()).coerceAtMost(maxTick())
    /** Furthest frame the playback window / playhead can reach: the blank tail may grow to a full minute. */
    fun maxTick() = maxOf(total() + 119, fps.coerceAtLeast(1) * 60 - 1)
    /** Blank cells shown after the artwork: at least 120, growing as the window end is dragged out (up to a full minute). */
    fun tail() = maxOf(120, minOf(maxTick() + 1 - total(), (winEnd ?: 0) + 121 - total()))
    /** Window = first to last visible drawn frame across all layers. */
    fun resetWindow() {
        var a = Int.MAX_VALUE; var b = -1
        for (l in layers) {
            if (!l.visible) continue
            if (l.cam != null || (l.isGroup && l.transformFolder)) {
                val times = keyTimes(l)
                if (times.isNotEmpty()) { a = minOf(a, times.first()); b = maxOf(b, times.last()) }
                continue
            }
            if (l.kind == 6 || l.kind == 7) { a = minOf(a, l.mediaStartFrame); b = maxOf(b, l.mediaStartFrame + l.mediaFrames(fps) - 1); continue }
            if (l.isGroup || l.kind == 2 || l.kind == 3 || l.kind == 5) continue
            var start = 0
            for (c in l.cels) {
                // A cel is a drawing key even when it is intentionally blank.
                a = minOf(a, start)
                b = maxOf(b, if (l.kind == 4) total() - 1 else start + c.dur - 1)
                start += c.dur
            }
        }
        winStart = if (b >= 0) a else null
        winEnd = if (b >= 0) b else null
    }

    // ----- cut settings (each value can be locked; aspect lock keeps the ratio) -----
    fun changeW(v: Int) { val n = v.coerceIn(16, 8192); if (lockW) return; if (lockA) { if (lockH) return; w = n; h = (n / aspect).roundToInt().coerceAtLeast(16) } else w = n }
    fun changeH(v: Int) { val n = v.coerceIn(16, 8192); if (lockH) return; if (lockA) { if (lockW) return; h = n; w = (n * aspect).roundToInt().coerceAtLeast(16) } else h = n }
    fun changeAspect(a: Float) { if (lockA || a < 0.1f || (lockW && lockH)) return; if (lockW) h = (w / a).roundToInt().coerceAtLeast(16) else w = (h * a).roundToInt().coerceAtLeast(16) }
    fun changeDpi(v: Int) { if (!lockDpi) dpi = v.coerceIn(1, 1200) }
    fun changeFps(v: Int) { if (!lockFps) fps = v.coerceIn(1, 120) }
    fun resetSettings() { if (!lockW) w = orig[0]; if (!lockH) h = orig[1]; if (!lockDpi) dpi = orig[2]; if (!lockFps) fps = orig[3]; aspect = w / h.toFloat() }

    // ----- undo / redo: strokes AND every timeline change, in order -----
    fun snap() = Snap(layers.map { l ->
        LSnap(l, l.name, l.visible, l.locked, l.opacity, l.group, l.collapsed, l.cels.toList(), l.cels.map { it.dur }, l.cels.map { it.persistent }, l.cels.map { it.inbetweenName },
            l.cam?.let { listOf(it.xs.toList(), it.ys.toList(), it.ss.toList(), it.rs.toList()) }, l.cam?.w ?: 0, l.cam?.h ?: 0,
            l.transformFolder, l.transformChannels().map { it.toList() }, l.persp?.vps?.toList(), l.persp?.horizon ?: 0f, l.persp?.center ?: 0f, l.persp?.snapshot(), l.mediaUri, l.mediaDurationMs, l.mediaDurationFrames, l.mediaStartFrame, l.mediaX, l.mediaY, l.mediaScale, l.mediaRotation, l.cels.map { it.vecCfg }, l.uniformTiming, l.uniformAttached)
    }, layerId, time)

    private fun restore(s: Snap) {
        // Structural snapshots share unchanged Cel objects. Keep their tile caches intact;
        // only cels that disappear in this restored structure need their cache evicted.
        val retained = java.util.Collections.newSetFromMap(IdentityHashMap<Cel, Boolean>())
        s.layers.forEach { retained.addAll(it.cels) }
        layers.forEach { layer -> layer.cels.forEach { cel ->
            if (!retained.contains(cel)) TileCache.invalidate(cel.strokes)
        } }
        layers.clear()
        for (ls in s.layers) {
            val l = ls.layer
            l.name = ls.name; l.visible = ls.visible; l.locked = ls.locked; l.opacity = ls.opacity; l.group = ls.group; l.collapsed = ls.collapsed
            l.cels.clear(); l.cels.addAll(ls.cels); ls.cels.forEachIndexed { i, c -> c.dur = ls.durs[i]; c.persistent = ls.persistent.getOrElse(i) { false }; c.inbetweenName = ls.inbetweenNames.getOrElse(i) { "" }; c.vecCfg = ls.vecCfgs.getOrNull(i) }
            l.transformFolder = ls.transformFolder; l.uniformTiming = ls.uniformTiming; l.uniformAttached = ls.uniformAttached; l.mediaUri = ls.mediaUri; l.mediaDurationMs = ls.mediaDurationMs; l.mediaDurationFrames = ls.mediaDurationFrames; l.mediaStartFrame = ls.mediaStartFrame; l.mediaX = ls.mediaX; l.mediaY = ls.mediaY; l.mediaScale = ls.mediaScale; l.mediaRotation = ls.mediaRotation; for (ci in 0..3) { l.transformChan(ci).clear(); l.transformChan(ci).addAll(ls.transformChannels[ci]) }
            l.cam?.let { c ->
                val k = ls.keys
                if (k != null) { c.xs.clear(); c.xs.addAll(k[0]); c.ys.clear(); c.ys.addAll(k[1]); c.ss.clear(); c.ss.addAll(k[2]); c.rs.clear(); c.rs.addAll(k[3]) }
                c.w = ls.camW; c.h = ls.camH
            }
            l.persp?.let { p -> p.horizon = ls.horizon; p.center = ls.center; p.vps.clear(); p.vps.addAll(ls.vps ?: emptyList()); ls.perspState?.let(p::restore) }
            layers.add(l)
        }
        invalidateStructureCaches()
        layerId = s.layerId; time = s.time.coerceIn(0, total() - 1); selAll = -1
    }
    /** Forgets every undo / redo step: after a Cut resize that moved or scaled the content, the old steps no longer fit the drawing. */
    fun clearHistory() { undoStack.clear(); redoStack.clear(); edits++ }
    fun pushStruct(before: Snap) { syncUniformTiming(before); undoStack.addLast(StructAct(before, snap())); redoStack.clear(); edits++; invalidateStructureCaches() }
    inline fun <T> record(block: () -> T): T { val b = snap(); invalidateStructureCaches(); val r = block(); pushStruct(b); return r }

    fun commit(cel: Cel, ink: Ink, backup: StrokeBackup? = null) { cel.strokes.add(ink); undoStack.addLast(StrokeAct(cel, ink, backup)); redoStack.clear(); edits++; backup?.let { UndoPixels.add(it) } } // tiles are updated by the caller (live stamp) or catch up lazily
    fun pushReferenceMatrix(layer: Layer, ink: Ink, before: FloatArray) {
        val after = ink.m?.copyOf() ?: return
        if (before.contentEquals(after)) return
        undoStack.addLast(ReferenceMatrixAct(layer, ink, before.copyOf(), after)); redoStack.clear(); edits++
        layer.imageRevision++
    }
    /** One undo step for several reference images whose matrices (and optionally the layer's relative-position flag) changed since [before]. */
    fun pushReferenceMatrices(layer: Layer, inks: List<Ink>, before: List<FloatArray>, pinBefore: Boolean = layer.refPinned) {
        if (inks.isEmpty() || inks.size != before.size) return
        val after = inks.map { it.m?.copyOf() ?: FloatArray(9) }
        val same = after.indices.all { before[it].contentEquals(after[it]) } && pinBefore == layer.refPinned
        if (same) return
        undoStack.addLast(ReferenceMatricesAct(layer, inks, before.map { it.copyOf() }, after, pinBefore, layer.refPinned)); redoStack.clear(); edits++
        layer.imageRevision++
    }
    /** The reference images of a reference layer (kind 3 inks of its cel), bottom to top. */
    fun referenceInks(layer: Layer): List<Ink> = layer.cels.firstOrNull()?.strokes?.filter { it.kind == 3 } ?: emptyList()
    /** Mirrors the given images around the common centre of their outlines (one image: its own centre). One undo step. */
    fun flipReferences(layer: Layer, inks: List<Ink>, horizontal: Boolean) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        for (ink in inks) {
            val d = ink.data?.toString(Charsets.UTF_8)?.let { ReferenceBitmapCache.dimensions(it) } ?: continue
            val m = ink.m ?: continue
            val pts = floatArrayOf(0f, 0f, d.first.toFloat(), 0f, d.first.toFloat(), d.second.toFloat(), 0f, d.second.toFloat())
            android.graphics.Matrix().apply { setValues(m) }.mapPoints(pts)
            for (i in 0 until 4) { minX = minOf(minX, pts[i * 2]); maxX = maxOf(maxX, pts[i * 2]); minY = minOf(minY, pts[i * 2 + 1]); maxY = maxOf(maxY, pts[i * 2 + 1]) }
            any = true
        }
        if (!any) return
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val flip = android.graphics.Matrix().apply { if (horizontal) setScale(-1f, 1f, cx, cy) else setScale(1f, -1f, cx, cy) }
        val before = inks.map { it.m?.copyOf() ?: FloatArray(9) }
        for (ink in inks) {
            val m = ink.m ?: continue
            android.graphics.Matrix().apply { setConcat(flip, android.graphics.Matrix().apply { setValues(m) }) }.getValues(m)
        }
        pushReferenceMatrices(layer, inks, before)
    }
    /** Removes one image. The last image takes its (then empty) layer with it, in one undo step. */
    fun removeReferenceImage(layer: Layer, ink: Ink, returnTo: Int) {
        val cel = layer.cels.firstOrNull() ?: return
        val idx = cel.strokes.indexOf(ink); if (idx < 0) return
        if (cel.strokes.count { it.kind == 3 } <= 1) {
            record { layers.remove(layer); layerId = (layerById(returnTo)?.takeIf { it.kind != 5 && !it.isGroup } ?: layers.lastOrNull { it.kind == 0 && !it.isGroup } ?: layers.last { !it.isGroup }).id; selAll = -1; time = time.coerceAtMost(total() - 1) }
            return
        }
        cel.strokes.removeAt(idx)
        undoStack.addLast(ReferenceRemoveAct(layer, cel, ink, idx)); redoStack.clear(); edits++
        layer.imageRevision++
    }
    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        edits++
        when (a) {
            is StrokeAct -> {
                // Fast path: the newest stroke of its cel and its backup is still around -> put the old pixels back instead of replaying the cel.
                val fast = a.backup != null && a.cel.strokes.lastOrNull() === a.ink
                a.cel.strokes.remove(a.ink) // otherwise the tile set notices count > size and rebuilds
                if (fast) TileCache.peek(a.cel.strokes)?.restoreFrom(a.backup!!, a.cel.strokes.size)
            }
            is ClearAct -> { a.strokes.addAll(a.removed); TileCache.invalidate(a.strokes) }
            is StructAct -> restore(a.before)
            is ReferenceMatrixAct -> { a.ink.m?.let { a.before.copyInto(it) }; a.layer.imageRevision++ }
            is ReferenceMatricesAct -> { a.inks.forEachIndexed { i, ink -> ink.m?.let { a.before[i].copyInto(it) } }; a.layer.refPinned = a.pinBefore; a.layer.imageRevision++ }
            is ReferenceRemoveAct -> { a.cel.strokes.add(a.index.coerceIn(0, a.cel.strokes.size), a.ink); a.layer.imageRevision++ }
        }
        redoStack.addLast(a)
    }
    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        edits++
        when (a) {
            is StrokeAct -> a.cel.strokes.add(a.ink) // the tile set replays the one new stroke
            is ClearAct -> { a.strokes.clear(); TileCache.invalidate(a.strokes) }
            is StructAct -> restore(a.after)
            is ReferenceMatrixAct -> { a.ink.m?.let { a.after.copyInto(it) }; a.layer.imageRevision++ }
            is ReferenceMatricesAct -> { a.inks.forEachIndexed { i, ink -> ink.m?.let { a.after[i].copyInto(it) } }; a.layer.refPinned = a.pinAfter; a.layer.imageRevision++ }
            is ReferenceRemoveAct -> { a.cel.strokes.remove(a.ink); a.layer.imageRevision++ }
        }
        undoStack.addLast(a)
    }

    fun ensureCel(l: Layer, t: Int = time): Cel {
        l.celAt(t)?.let { return it }
        val gap = t - l.total()
        if (gap > 0) l.cels.add(Cel(gap))
        return Cel(1).also { l.cels.add(it) }
    }

    // ----- Uniform timing (folder option): attached drawing / vector layers keep the same frame count and timing -----
    private fun folderOf(l: Layer): Layer? = if (l.group < 0) null else layers.firstOrNull { it.isGroup && it.id == l.group }

    /** Gives [target] exactly the timing [durs]: one cel per entry with that duration. Existing cels keep their drawings by position, missing ones are empty, extra ones are dropped. */
    private fun conformTiming(durs: List<Int>, target: Layer) {
        val old = target.cels.toList()
        val made = ArrayList<Cel>(durs.size)
        for (j in durs.indices) {
            val c = old.getOrNull(j) ?: Cel(durs[j])
            c.dur = durs[j]
            made.add(c)
        }
        target.cels.clear(); target.cels.addAll(made)
    }

    private fun sameTimeline(p: LSnap, l: Layer) = p.cels == l.cels.toList() && p.durs == l.cels.map { it.dur }

    /**
     * Called by [pushStruct]: when a layer that follows a Uniform-timing folder had its frames or timing changed by the step that began at
     * [before], the other attached layers of that folder follow, in the same undo step. Frames are matched by identity: a frame the layer lost
     * is removed at the same position in the others, a new frame is inserted there as an empty frame, a moved frame moves, a retimed frame is retimed.
     */
    private fun syncUniformTiming(before: Snap) {
        if (layers.none { it.uniformTiming || it.uniformAttached }) return
        val prior = HashMap<Int, LSnap>()
        before.layers.forEach { prior[it.layer.id] = it }
        // a layer that left its folder (or whose folder is gone or switched off) stops following
        for (l in layers) if (l.uniformAttached) {
            val f = folderOf(l)
            val p = prior[l.id]
            if (f == null || !f.uniformTiming || (p != null && p.group != l.group)) l.uniformAttached = false
        }
        for (f in layers) {
            if (!f.isGroup || !f.uniformTiming) continue
            val members = layers.filter { !it.isGroup && it.kind == 0 && it.group == f.id && it.uniformAttached }
            if (members.size < 2) continue
            var source: Layer? = null; var sourceBefore: LSnap? = null
            for (m in members) {
                val p = prior[m.id] ?: continue
                if (!p.uniformAttached) continue // attached in this very step: the operation itself aligned it
                if (!sameTimeline(p, m)) { source = m; sourceBefore = p; break }
            }
            val src: Layer = source ?: continue
            val srcBefore: LSnap = sourceBefore ?: continue
            val beforeIndex = IdentityHashMap<Cel, Int>()
            srcBefore.cels.forEachIndexed { i, c -> beforeIndex[c] = i }
            val srcCels = src.cels.toList()
            val durs = srcCels.map { it.dur }
            for (o in members) {
                if (o === src) continue
                val p = prior[o.id] ?: continue
                if (!p.uniformAttached || !sameTimeline(p, o)) continue // already changed by the operation itself
                val oldCels = o.cels.toList()
                if (oldCels.size != srcBefore.cels.size) { conformTiming(durs, o); continue }
                val made = ArrayList<Cel>(srcCels.size)
                for (c in srcCels) {
                    val bi = beforeIndex[c]
                    val oc = if (bi != null) oldCels[bi] else Cel(c.dur)
                    oc.dur = c.dur
                    made.add(oc)
                }
                o.cels.clear(); o.cels.addAll(made)
            }
        }
    }

    /** Folder three-dot "Uniform timing". On: every drawing / vector layer in the folder takes the timing of the one with the most frames and follows it from then on. Off: they are released. */
    fun setUniformTiming(folder: Layer, on: Boolean) {
        if (!folder.isGroup) return
        record {
            if (!on) {
                folder.uniformTiming = false
                layers.forEach { if (it.group == folder.id) it.uniformAttached = false }
                return@record
            }
            folder.uniformTiming = true
            val members = layers.filter { !it.isGroup && it.kind == 0 && it.group == folder.id }
            val ref = members.maxWithOrNull(compareBy<Layer>({ it.cels.size }, { it.total() })) ?: return@record
            val durs = ref.cels.map { it.dur }
            for (m in members) { m.uniformAttached = true; if (m !== ref) conformTiming(durs, m) }
        }
    }

    /** Layer three-dot "Detach timing": the layer keeps its frames and timing but no longer follows (or drives) the folder's Uniform timing. */
    fun detachTiming(l: Layer) {
        if (!l.uniformAttached) return
        record { l.uniformAttached = false }
    }

    /** Layer three-dot "Attach timing": the layer takes the timing of the folder's other attached layers and follows them from then on. */
    fun attachTiming(l: Layer) {
        if (l.isGroup || l.kind != 0 || l.uniformAttached) return
        val f = folderOf(l)?.takeIf { it.uniformTiming } ?: return
        record {
            val others = layers.filter { !it.isGroup && it.kind == 0 && it.group == f.id && it.uniformAttached && it !== l }
            val ref = others.maxWithOrNull(compareBy<Layer>({ it.cels.size }, { it.total() }))
            l.uniformAttached = true
            if (ref != null) conformTiming(ref.cels.map { it.dur }, l)
        }
    }

    /** [ensureCel] for drawing outside an undo step: a new frame made on a layer that follows Uniform timing is added to the other attached layers of its folder too. */
    fun ensureCelUniform(l: Layer, t: Int = time): Cel {
        val had = l.celAt(t) != null
        val c = ensureCel(l, t)
        if (!had && l.uniformAttached) {
            val f = folderOf(l)?.takeIf { it.uniformTiming }
            if (f != null) {
                val durs = l.cels.map { it.dur }
                for (o in layers) if (o !== l && !o.isGroup && o.kind == 0 && o.group == f.id && o.uniformAttached) conformTiming(durs, o)
                invalidateStructureCaches()
            }
        }
        return c
    }

    // ----- frames of the active layer -----
    /**
     * New frame: an empty frame right AFTER the playhead, and the playhead moves onto it. When the playhead is inside a hold (a frame that lasts several frames) and not on its last
     * frame, the hold is split after the playhead: the part before keeps the drawing, the new frame comes next, and the rest of the hold continues after it as a linked copy of the
     * same drawing, so everything after is only pushed one frame later (it used to be put after the whole hold, far from the playhead).
     */
    fun addFrame() {
        val l = frameLayer ?: return
        record {
            ensureCel(l)
            val i = l.indexAt(time)
            val c = l.cels[i]
            val k = time - l.startOf(i)
            if (k in 0 until c.dur - 1) {
                val rest = c.cloneLinked(); rest.dur = c.dur - k - 1
                c.dur = k + 1
                l.cels.add(i + 1, Cel(1)); l.cels.add(i + 2, rest)
            } else l.cels.add(i + 1, Cel(1))
            time = l.startOf(i + 1); selAll = -1
        }
    }
    /** Copy frame: regular (unique) copies of the [selected] frames, in order, placed after the last of them (three-dot "Select all": every frame). With nothing selected: the current frame. */
    fun duplicateFrame(selected: List<Cel> = emptyList()) {
        val l = frameLayer ?: return
        record {
            ensureCel(l)
            val src = selected.filter { c -> l.cels.any { it === c } }.sortedBy { l.cels.indexOf(it) }.ifEmpty { if (selAll == l.id) l.cels.toList() else listOfNotNull(l.celAt(time)) }
            if (src.isEmpty()) return@record
            val made = src.map { it.copy() }
            l.cels.addAll(l.cels.indexOf(src.last()) + 1, made)
            time = l.startOf(l.cels.indexOf(made.first())); selAll = -1
        }
    }
    /** Clone: a non-unique copy placed after the original (or after the last selected one). Edits to either show in both. */
    fun cloneFrames(selected: List<Cel>) {
        val l = frameLayer ?: return
        record {
            ensureCel(l)
            val src = selected.filter { l.cels.contains(it) }.sortedBy { l.cels.indexOf(it) }.ifEmpty { if (selAll == l.id) l.cels.toList() else listOfNotNull(l.celAt(time)) } // three-dot "Select all" (selAll) clones every frame, like selecting them by hand
            if (src.isEmpty()) return@record
            var at = l.cels.indexOf(src.last()) + 1
            val made = src.map { it.cloneLinked() }
            l.cels.addAll(at, made)
            time = l.startOf(l.cels.indexOf(made.first())); selAll = -1
        }
    }
    /** Empty frame: removes the drawing but keeps the cel (and so the timing). Undoable; a clone empties with its original. */
    fun emptyFrames(selected: List<Cel>) {
        val l = frameLayer ?: return
        val targets = when {
            selAll == l.id -> l.cels.toList()
            selected.isNotEmpty() -> selected.filter { l.cels.contains(it) }
            else -> listOfNotNull(l.celAt(time))
        }
        val done = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        for (c in targets) {
            if (c.strokes.isEmpty() || !done.add(c.strokes)) continue
            val removed = c.strokes.toList()
            c.strokes.clear(); TileCache.invalidate(c.strokes)
            undoStack.addLast(ClearAct(c.strokes, removed)); redoStack.clear(); edits++
        }
    }
    /** Splits [cel] at [at] frames from its start. fromAbove: both halves show the drawing (the right one a regular copy); otherwise the right half is empty. */
    fun splitFrame(l: Layer, cel: Cel, at: Int, fromAbove: Boolean) {
        val i = l.cels.indexOf(cel)
        if (i < 0 || at <= 0 || at >= cel.dur) return
        record {
            val rightDur = cel.dur - at
            cel.dur = at
            l.cels.add(i + 1, Cel(rightDur).also { if (fromAbove) it.strokes.addAll(cel.strokes) }) // a regular frame, not a clone
        }
    }
    fun deleteFrame() {
        val l = frameLayer ?: return
        record {
            if (selAll == l.id) { l.cels.forEach { TileCache.invalidate(it.strokes) }; l.cels.clear(); l.cels.add(Cel(1)); selAll = -1 }
            else { val i = l.indexAt(time); if (i >= 0) { if (l.cels.size > 1) l.cels.removeAt(i) else { l.cels[0].strokes.clear(); TileCache.invalidate(l.cels[0].strokes) } } }
            time = time.coerceAtMost(total() - 1)
        }
    }

    // ----- layers (names are numbered per type: first has no number, then 2, 3 ...) -----
    /** Letter of the [index]th drawing layer counted from the background (0 -> A). Null past Z. */
    private fun letterFor(index: Int): String? = if (index in 0..25) ('A' + index).toString() else null
    private fun isLetterName(n: String) = n.length == 1 && n[0].isLetter()
    /** Dynamic naming: drawing layers named by a single letter carry the letters A, B, C... in order from the background up.
     *  Layers with any other name are skipped: they neither get a letter nor take one in the ordering. */
    fun relabelLayers() {
        var i = 0
        for (l in layers) {
            if (l.isGroup || l.kind != 0 || !isLetterName(l.name)) continue
            val letter = letterFor(i)
            if (letter != null && l.name != letter) l.name = letter
            i++
        }
    }
    fun nextLetterName(): String = letterFor(layers.count { !it.isGroup && it.kind == 0 && isLetterName(it.name) }) ?: nameFor("Layer", 0)
    fun nameFor(base: String, kind: Int, group: Boolean = false): String {
        val n = layers.count { it.isGroup == group && (group || it.kind == kind) }
        return if (n == 0) base else "$base ${n + 1}"
    }
    fun addLayer() = record { val l = Layer(nextLayer, nextLetterName()); nextLayer++; layers.add(l); layerId = l.id }
    /** A vector (Grease Pencil style) layer: strokes stay editable points; its frames are keyframes that can be interpolated and baked. */
    fun addVector() = record {
        val n = layers.count { it.vector }
        val l = Layer(nextLayer, if (n == 0) "Vector" else "Vector ${n + 1}"); l.vector = true
        nextLayer++; layers.add(l); layerId = l.id
    }
    fun deleteLayer() {
        val cur = layer ?: return
        if (cur.kind == 0 && layers.count { it.kind == 0 && !it.isGroup } <= 1) return
        record {
            layers.remove(cur); layerId = (layers.lastOrNull { it.kind == 0 && !it.isGroup } ?: layers.last { !it.isGroup }).id; selAll = -1
            time = time.coerceAtMost(total() - 1)
        }
    }
    fun duplicateLayer(keep: Boolean) {
        val src = drawLayer ?: return
        record {
            val l = Layer(nextLayer, if (src.kind == 0 && src.name.length == 1 && src.name[0].isLetter()) src.name else src.name + if (keep) " copy" else " (empty)", false, src.kind); nextLayer++
            l.vector = src.vector
            l.cels.clear()
            if (keep) { // clones (frames sharing one drawing) stay clones of each other in the copy; only the first of each group is a regular copy
                val made = IdentityHashMap<Any, Cel>()
                src.cels.forEach { c -> val first = made[c.strokes]; l.cels.add(if (first == null) c.copy().also { made[c.strokes] = it } else first.cloneLinked().also { it.dur = c.dur; it.persistent = c.persistent; it.inbetweenName = c.inbetweenName; it.vecCfg = c.vecCfg }) }
            } else src.cels.forEach { l.cels.add(it.emptyCopy()) }
            l.opacity = src.opacity; l.visible = src.visible; l.group = src.group
            layers.add(layers.indexOf(src) + 1, l); layerId = l.id; selAll = -1
        }
    }
    /** Three-dot "Duplicate Empty Vector": a new empty vector layer with the timing (frame count and durations) of the selected drawing layer. */
    fun duplicateEmptyVector() {
        val src = drawLayer?.takeIf { it.kind == 0 } ?: return
        record {
            val n = layers.count { it.vector }
            val l = Layer(nextLayer, if (n == 0) "Vector" else "Vector ${n + 1}"); l.vector = true
            nextLayer++
            l.cels.clear(); src.cels.forEach { l.cels.add(Cel(it.dur)) }
            if (l.cels.isEmpty()) l.cels.add(Cel(1))
            l.opacity = src.opacity; l.visible = src.visible; l.group = src.group
            layers.add(layers.indexOf(src) + 1, l); layerId = l.id; selAll = -1
        }
    }
    fun addCamera() = record { val l = Layer(nextLayer, nameFor("Camera", 2), false, 2, Camera(w, h)); nextLayer++; layers.add(l); layerId = l.id }
    fun addPerspective() = record { val l = Layer(nextLayer, nameFor("Perspective", 3), false, 3, null, Persp(h / 2f, w / 2f)); nextLayer++; layers.add(l); layerId = l.id }
    fun addBackground() = record { val l = Layer(nextLayer, nameFor("Background", 4), false, 4); nextLayer++; layers.add(0, l); layerId = l.id }
    /** Adds a media clip whose source is already copied into app-owned storage. */
    fun addMediaLayer(kind: Int, uri: String, durationMs: Long, label: String) {
        require(kind == 6 || kind == 7)
        val base = if (kind == 6) "Audio" else "Video"
        val layer = Layer(nextLayer, label.ifBlank { nameFor(base, kind) }, false, kind)
        layer.mediaUri = uri
        layer.mediaDurationMs = durationMs.coerceAtLeast(1L)
        layer.mediaDurationFrames = layer.mediaFrames(fps)
        layer.mediaStartFrame = time.coerceAtLeast(0)
        nextLayer++
        record { layers.add(layer); layerId = layer.id }
    }
    /** Adds a reference layer holding every prepared image (cascaded so they do not hide each other). Only the external paths and the matrices are stored in the .inb. */
    fun addReferenceImages(prepared: List<PreparedReferenceImage>, label: String = ""): Layer? {
        if (prepared.isEmpty()) return null
        val layer = Layer(nextLayer, label.ifBlank { nameFor("Reference", 5) }, false, 5)
        nextLayer++
        record { layers.add(layer); layerId = layer.id }
        appendReferenceImages(layer, prepared)
        return layer
    }
    /** Adds images to an existing reference layer (the timeline's "Add reference"). Each image is its own undo step, like the first one always was. */
    fun appendReferenceImages(layer: Layer, prepared: List<PreparedReferenceImage>): List<Ink> {
        if (layer.kind != 5) return emptyList()
        val cel = layer.cels.firstOrNull() ?: Cel(1).also { layer.cels.add(it) }
        val step = 0.04f * minOf(w, h)
        val made = ArrayList<Ink>()
        prepared.forEach { p ->
            val n = cel.strokes.count { it.kind == 3 }
            val m = p.matrix.copyOf(); m[2] += step * n; m[5] += step * n
            val ink = Ink(FloatArray(0), -1, Brush(""), 3, null, m, p.path.toByteArray(Charsets.UTF_8))
            commit(cel, ink); made.add(ink)
        }
        layer.imageRevision++
        return made
    }
    fun addGroup() = record { val g = Layer(nextLayer, nameFor("Folder", 0, true), true); nextLayer++; layers.add(g) }
    fun addTransformFolder() = record {
        val n = layers.count { it.isGroup && it.transformFolder } // numbered per type: "Transformation", "Transformation 2" ...
        val g = Layer(nextLayer, if (n == 0) "Transformation" else "Transformation ${n + 1}", true).apply { transformFolder = true }; nextLayer++; layers.add(g); layerId = g.id
    }
    /** Bulk version of moveLayer: the layers in `ls` keep their relative order and land together next to `anchor`. */
    fun moveLayers(ls: List<Layer>, anchor: Layer, after: Boolean, group: Int) {
        if (ls.isEmpty() || ls.any { it === anchor }) return
        record {
            val tb = layers.reversed().toMutableList()
            val moving = tb.filter { m -> ls.any { it === m } }
            tb.removeAll { m -> ls.any { it === m } }
            val idx = (tb.indexOf(anchor) + if (after) 1 else 0).coerceIn(0, tb.size)
            moving.forEach { it.group = group }
            tb.addAll(idx, moving)
            layers.clear(); layers.addAll(tb.reversed())
        }
    }
    fun removeGroup(g: Layer) = record { layers.forEach { if (it.group == g.id) it.group = -1 }; layers.remove(g) }

    /** Moves layer `l` next to `anchor` (before/after it in the timeline's top-to-bottom order) and sets its group. z-order follows. */
    fun moveLayer(l: Layer, anchor: Layer, after: Boolean, group: Int) = record {
        val tb = layers.reversed().toMutableList()
        tb.remove(l)
        val idx = (tb.indexOf(anchor) + if (after) 1 else 0).coerceIn(0, tb.size)
        l.group = group
        tb.add(idx, l)
        layers.clear(); layers.addAll(tb.reversed())
    }
}

/**
 * Pending Cut settings shown in the Cut settings panel and as on-canvas handles. Nothing is written to the Cut until [apply] (Confirm).
 * The locks and the aspect lock behave exactly like Cut.changeW / changeH / changeAspect / changeDpi / changeFps.
 */
class CutDraft(val cut: Cut) {
    var w by mutableIntStateOf(cut.w)
    var h by mutableIntStateOf(cut.h)
    var dpi by mutableIntStateOf(cut.dpi)
    var fps by mutableIntStateOf(cut.fps)
    /** How far the page's left / top edge has been pulled outwards (px; negative = pushed inwards). The page origin stays at (0, 0) on screen until Confirm, so the pending frame is drawn from (-ox, -oy). */
    var ox by mutableIntStateOf(0)
    var oy by mutableIntStateOf(0)
    /** On: Confirm scales the whole drawing to the new size ([stretchCutContent]) instead of cropping / extending it. The left / top offsets are then not used. */
    var stretch by mutableStateOf(false)
    fun setStretching(on: Boolean) { stretch = on; if (on) { ox = 0; oy = 0 } }
    val aspect get() = w / h.toFloat()
    private var lockedRatio = cut.aspect
    fun changeW(v: Int) { val n = v.coerceIn(16, 8192); if (cut.lockW) return; if (cut.lockA) { if (cut.lockH) return; w = n; h = (n / lockedRatio).roundToInt().coerceIn(16, 8192) } else w = n }
    fun changeH(v: Int) { val n = v.coerceIn(16, 8192); if (cut.lockH) return; if (cut.lockA) { if (cut.lockW) return; h = n; w = (n * lockedRatio).roundToInt().coerceIn(16, 8192) } else h = n }
    fun changeAspect(a: Float) { if (cut.lockA || a < 0.1f || (cut.lockW && cut.lockH)) return; if (cut.lockW) h = (w / a).roundToInt().coerceIn(16, 8192) else w = (h * a).roundToInt().coerceIn(16, 8192) }
    fun changeDpi(v: Int) { if (!cut.lockDpi) dpi = v.coerceIn(1, 1200) }
    fun changeFps(v: Int) { if (!cut.lockFps) fps = v.coerceIn(1, 120) }
    fun reset() { if (!cut.lockW) { w = cut.orig[0]; ox = 0 }; if (!cut.lockH) { h = cut.orig[1]; oy = 0 }; if (!cut.lockDpi) dpi = cut.orig[2]; if (!cut.lockFps) fps = cut.orig[3] }
    /** True when Confirm will move or scale the drawing (and therefore clears Undo): Stretch with a changed size, or a left / top edge that was pulled. */
    val changesContent: Boolean get() = if (stretch) (w != cut.w || h != cut.h) else (ox != 0 || oy != 0)
    /**
     * Writes the pending values to the Cut. Stretch scales every layer to the new size; otherwise a moved left / top edge shifts every layer by (ox, oy)
     * so the picture stays where it is relative to the handle. Both need the OLD Cut size, so they run before the size is replaced.
     */
    fun apply() {
        val ow = cut.w; val oh = cut.h
        val moves = changesContent
        if (moves) { if (stretch) stretchCutContent(cut, ow, oh, w, h) else shiftCutContent(cut, ox, oy, ow, oh, w, h) }
        cut.w = w; cut.h = h; cut.dpi = dpi; cut.fps = fps; cut.aspect = w / h.toFloat()
        if (moves) cut.clearHistory()
    }
}

/** A named, saveable set of colors (palettes.json in the app files directory). */
class Palette(val id: Long, name: String, colors: List<Color> = emptyList()) {
    var name by mutableStateOf(name)
    val colors = mutableStateListOf<Color>().apply { addAll(colors) }
}

class Editor : ViewModel() {
    val cuts = mutableStateListOf<Cut>()
    val library = CutLibrary() // main-menu folders / layout (library.json)
    var open by mutableStateOf<Cut?>(null)

    /** 0 brush, 1 eraser, 2 select, 3 fill, 4 line, 5 eyedropper, 6 reshape (Shift / Smoothen / Distort) */
    private var storedTool by mutableIntStateOf(0)
    /** Choosing the Brush tool again (from another tool) means drawing: the vector Cutter / Interpolation drag modes end. */
    var tool: Int
        get() = storedTool
        set(value) { if (value == 0 && storedTool != 0) vecMode = 0; storedTool = value }
    val erase: Boolean get() = tool == 1
    var color by mutableStateOf(Color.Black)
    val brushes = mutableStateListOf<Brush>().apply { addAll(DEFAULT_BRUSHES) }
    val erasers = mutableStateListOf<Brush>().apply { addAll(DEFAULT_ERASERS) }
    var brushIdx by mutableIntStateOf(0)
    var eraserIdx by mutableIntStateOf(0)
    val brush: Brush get() = if (erase) erasers[eraserIdx] else brushes[brushIdx]
    fun update(forErase: Boolean, f: (Brush) -> Brush) {
        if (forErase) erasers[eraserIdx] = f(erasers[eraserIdx]) else brushes[brushIdx] = f(brushes[brushIdx])
    }

    var selMode by mutableIntStateOf(0)   // 0 rectangle, 1 lasso, 2 point-based (Magic Wand is the independent selWand toggle)
    var selWand by mutableStateOf(false) // independent Magic Wand toggle; preserves the chosen geometric selection tool
    var wandGap by mutableIntStateOf(0)   // Magic Wand gap closing (px), set by the horizontal drag; colour tolerance is fixed (WAND_FILL_TOL)
    var fillGap by mutableIntStateOf(0)   // Fill gap closing (px), set by the horizontal drag
    var fillGrow by mutableIntStateOf(1)
    var selOp by mutableIntStateOf(0)         // new selections: 0 replace, 1 add to the selection, 2 erase from the selection (Shift / Alt / Ctrl held on the keyboard do this too)
    var selSmart by mutableStateOf(true)      // rectangle / lasso / point selections follow the strokes (strokeAwareSel)
    var selAutoFill by mutableStateOf(false)  // a finished selection is filled with the current colour at once
    var paintInSel by mutableStateOf(true)    // brush, eraser, shapes and fill only change the pixels inside the selection
    var fillAuto by mutableStateOf(true)      // Fill closes the gaps of an imperfectly closed outline by itself (smartFlood)
    var refAll by mutableStateOf(true)    // wand / fill look at all visible layers instead of the active one
    var lineShape by mutableIntStateOf(0) // 0 line, 1 rectangle, 2 circle, 3 ellipse
    var sel by mutableStateOf<Sel?>(null)
    var xform by mutableStateOf<Xform?>(null)
    var xfLock by mutableStateOf(false)   // transform handles keep the aspect ratio (selection, camera)
    var refSel by mutableStateOf<Ink?>(null)   // the reference image being edited (session only); null = none picked, gestures then move every image of the layer
    var refReturnId by mutableIntStateOf(-1)   // layer to go back to when the pen taps outside every reference image
    /** Set by DrawingCanvas: home-view -> document matrix of the current view (see DrawingCanvas.pinMatrix). Not state. */
    var refPinProvider: (() -> android.graphics.Matrix)? = null
    /** "Relative position": anchors the images of [layer] to the screen (or releases them) without moving them on screen right now. One undo step. */
    fun toggleRefPinned(cut: Cut, layer: Layer) {
        val c = refPinProvider?.invoke() ?: return
        val inks = cut.referenceInks(layer); if (inks.isEmpty()) return
        val tr = if (layer.group >= 0) cut.transformFor(layer, cut.time) else GroupTransform()
        val g = android.graphics.Matrix().apply { // the folder transform the canvas applies to an unpinned layer (identity outside folders)
            postTranslate(-cut.w / 2f, -cut.h / 2f); postScale(tr.scale, tr.scale); postRotate(tr.rotation); postTranslate(cut.w / 2f + tr.x, cut.h / 2f + tr.y)
        }
        val pinBefore = layer.refPinned
        val conv = android.graphics.Matrix()
        if (!pinBefore) { // document -> home: C^-1 * G
            val ci = android.graphics.Matrix(); if (!c.invert(ci)) return
            conv.setConcat(ci, g)
        } else { // home -> document: G^-1 * C
            val gi = android.graphics.Matrix(); if (!g.invert(gi)) return
            conv.setConcat(gi, c)
        }
        val before = inks.map { it.m?.copyOf() ?: FloatArray(9) }
        for (ink in inks) {
            val m = ink.m ?: continue
            android.graphics.Matrix().apply { setConcat(conv, android.graphics.Matrix().apply { setValues(m) }) }.getValues(m)
        }
        layer.refPinned = !pinBefore
        cut.pushReferenceMatrices(layer, inks, before, pinBefore)
    }

    var gridOn by mutableStateOf(true)
    var gridFront by mutableStateOf(false) // false = behind artwork, true = in front
    var gridStep by mutableIntStateOf(30)   // document px
    private var flipHState by mutableStateOf(false)
    private var flipVState by mutableStateOf(false)
    /**
     * Flip the view. The view is mirrored around the centre of the screen (where the zoom and pan put the page), not around the centre of the page: the pan offset is mirrored
     * with it, so the part of the page that is in the middle of the screen stays there.
     */
    var flipH: Boolean
        get() = flipHState
        set(v) { if (v != flipHState) flipPan(true); flipHState = v }
    var flipV: Boolean
        get() = flipVState
        set(v) { if (v != flipVState) flipPan(false); flipVState = v }
    private fun flipPan(horizontal: Boolean) {
        val a = Math.toRadians(vRot.toDouble()); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        val o = vOff
        val ux = o.x * c + o.y * s; val uy = -o.x * s + o.y * c // the pan in the view's own (rotated) axes
        val mx = if (horizontal) -ux else ux; val my = if (horizontal) uy else -uy
        vOff = Offset(mx * c - my * s, mx * s + my * c)
    }
    var viewOpt by mutableIntStateOf(0)     // 0 none, 1 grid options, 2 invert options, 3 bleed options
    var bleedScope by mutableIntStateOf(1)  // "Delete bleed ink" target: 0 current frame, 1 current layer, 2 whole Cut
    var camEdit by mutableStateOf(false)    // move / scale the camera frame on the canvas
    var camPanel by mutableStateOf(false)   // "Set resolution" panel
    var camOpt by mutableIntStateOf(0)      // 0 none, 1 interpolation options, 2 shake options
    var interp by mutableIntStateOf(1)
    var ease by mutableFloatStateOf(0.33f)
    var shakeSel by mutableStateOf(false)
    var shakeAmt by mutableIntStateOf(14)
    var shakeLen by mutableIntStateOf(12)
    var shakeAxis by mutableIntStateOf(0)   // legacy playhead shake (bakeShake): 0 both, 1 vertical only, 2 horizontal only
    var shakeDensity by mutableIntStateOf(1) // shake between selected keys: one new key every N frames
    var shakeLockH by mutableStateOf(false)  // locked axis is not shaken
    var shakeLockV by mutableStateOf(false)
    // timeline multi-selection (double-touch + drag): frames, diamond keys, layers
    val selCels = mutableStateListOf<Cel>()
    val selKeys = mutableStateListOf<KeySel>()
    /** Open / closed state of the sections of the brush detail page (session only; the shape section starts open). */
    val brushSections = mutableStateMapOf<String, Boolean>()
    var audioScrub by mutableStateOf(true) // play a short grain of the audio layers while the playhead is moved by hand
    val selLayers = mutableStateListOf<Int>()
    fun clearMulti() { selCels.clear(); selKeys.clear(); selLayers.clear() }
    var vpSel by mutableIntStateOf(-1)
    var fullscreen by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    // canvas view (zoom / rotation / pan) lives here so keyboard shortcuts can drive it
    var vScale by mutableFloatStateOf(1f)
    var vRot by mutableFloatStateOf(0f)
    var vOff by mutableStateOf(Offset.Zero)
    fun resetView() { vScale = 1f; vRot = 0f; vOff = Offset.Zero }
    var newFrameArmed by mutableStateOf(false) // scrubbed past the last frame: the next stroke creates a new frame

    // ----- brush presets: working copy in the lists, last saved state in savedMap (persisted to brushes.json) -----
    val savedMap = mutableStateMapOf<Long, Brush>()
    private var dir: File? = null
    private fun lst(forE: Boolean) = if (forE) erasers else brushes
    private fun curIdx(forE: Boolean) = if (forE) eraserIdx else brushIdx
    private fun setIdx(forE: Boolean, i: Int) { if (forE) eraserIdx = i else brushIdx = i }
    /** Picks a preset and remembers the choice (brushes.json), so it survives the app being closed by the system. */
    fun selectBrush(forE: Boolean, i: Int) { setIdx(forE, i); persistBrushes() }
    fun loadBrushes(d: File) {
        dir = d
        runCatching {
            val o = JSONObject(File(d, "brushes.json").readText())
            val b = o.getJSONArray("brushes"); val e = o.getJSONArray("erasers")
            if (b.length() > 0) { brushes.clear(); for (i in 0 until b.length()) brushes.add(brushFromJson(b.getJSONObject(i))) }
            if (e.length() > 0) { erasers.clear(); for (i in 0 until e.length()) erasers.add(brushFromJson(e.getJSONObject(i))) }
            brushIdx = o.optInt("brushIdx", 0); eraserIdx = o.optInt("eraserIdx", 0)
            if (o.optInt("v", 1) < 2) { // old default One Euro -> new default moving average
                for (i in brushes.indices) if (brushes[i].stabMethod == 2) brushes[i] = brushes[i].copy(stabMethod = 3)
                for (i in erasers.indices) if (erasers[i].stabMethod == 2) erasers[i] = erasers[i].copy(stabMethod = 3)
            }
        }
        brushIdx = brushIdx.coerceIn(0, brushes.size - 1); eraserIdx = eraserIdx.coerceIn(0, erasers.size - 1)
        (brushes + erasers).forEach { savedMap[it.id] = it }
    }
    private fun persistBrushes() {
        val d = dir ?: return
        val o = JSONObject().put("v", 2).put("brushIdx", brushIdx).put("eraserIdx", eraserIdx)
        o.put("brushes", JSONArray().also { a -> brushes.forEach { a.put((savedMap[it.id] ?: it).toJson()) } })
        o.put("erasers", JSONArray().also { a -> erasers.forEach { a.put((savedMap[it.id] ?: it).toJson()) } })
        File(d, "brushes.json").writeText(o.toString())
    }
    fun isModified(b: Brush) = savedMap[b.id]?.let { it != b } ?: false
    /** Save the preset: overwrite its saved state, or keep the original and store the edit as a new brush. */
    fun saveBrush(forE: Boolean, asNew: Boolean) {
        val l = lst(forE); val i = curIdx(forE); val b = l[i]
        if (asNew) {
            val nb = b.copy(name = b.name + " copy", id = nextId())
            l[i] = savedMap[b.id] ?: b; l.add(i + 1, nb); savedMap[nb.id] = nb; setIdx(forE, i + 1)
        } else savedMap[b.id] = b
        persistBrushes()
    }
    fun renameBrush(forE: Boolean, b: Brush, n: String) {
        val l = lst(forE); val i = l.indexOfFirst { it.id == b.id }; if (i < 0 || n.isBlank()) return
        l[i] = l[i].copy(name = n.trim()); savedMap[b.id] = (savedMap[b.id] ?: b).copy(name = n.trim()); persistBrushes()
    }
    fun resetBrush(forE: Boolean) { val l = lst(forE); val i = curIdx(forE); savedMap[l[i].id]?.let { l[i] = it } }
    fun deleteBrush(forE: Boolean, b: Brush) {
        val l = lst(forE); if (l.size <= 1) return
        val i = l.indexOfFirst { it.id == b.id }; if (i < 0) return
        l.removeAt(i); savedMap.remove(b.id)
        setIdx(forE, curIdx(forE).coerceIn(0, l.size - 1)); persistBrushes()
    }
    fun copyBrush(forE: Boolean, b: Brush) {
        val l = lst(forE); val i = l.indexOfFirst { it.id == b.id }; if (i < 0) return
        val nb = (savedMap[b.id] ?: b).copy(name = b.name + " copy", id = nextId())
        l.add(i + 1, nb); savedMap[nb.id] = nb; persistBrushes()
    }
    fun importBrush(forE: Boolean, text: String): Boolean = runCatching {
        val o = JSONObject(text)
        o.optJSONArray("assets")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                BrushAssets.register(e.getInt("id"), e.optInt("kind", 0), android.util.Base64.decode(e.getString("png"), android.util.Base64.DEFAULT), persist = true)
            }
        }
        val nb = brushFromJson(if (o.has("brush")) o.getJSONObject("brush") else o, true).copy(erase = forE)
        lst(forE).add(nb); savedMap[nb.id] = nb; persistBrushes()
    }.isSuccess

    // ----- color palettes -----
    val palettes = mutableStateListOf<Palette>()
    var paletteIdx by mutableIntStateOf(0)
    fun loadPalettes(d: File) {
        val existed = File(d, "palettes.json").exists()
        runCatching {
            val o = JSONObject(File(d, "palettes.json").readText())
            val a = o.getJSONArray("palettes")
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i); val cs = p.getJSONArray("colors")
                palettes.add(Palette(p.optLong("id", nextId()), p.optString("name", "Palette"), List(cs.length()) { Color(cs.getInt(it)) }))
            }
            paletteIdx = o.optInt("active", 0)
        }
        if (palettes.isEmpty() && !existed) palettes.add(Palette(nextId(), "Palette 1", listOf(Color.Black, Color.White, Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835))))
        paletteIdx = paletteIdx.coerceIn(0, maxOf(0, palettes.size - 1))
    }
    fun persistPalettes() {
        val d = dir ?: return
        val o = JSONObject().put("active", paletteIdx)
        o.put("palettes", JSONArray().also { arr -> palettes.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("colors", JSONArray().also { c -> p.colors.forEach { c.put(it.toArgb()) } }))
        } })
        runCatching { File(d, "palettes.json").writeText(o.toString()) }
    }
    fun newPalette() { palettes.add(Palette(nextId(), "Palette ${palettes.size + 1}")); paletteIdx = palettes.lastIndex; persistPalettes() }
    fun renamePalette(p: Palette, n: String) { if (n.isNotBlank()) { p.name = n.trim(); persistPalettes() } }
    fun deletePalette(p: Palette) { palettes.remove(p); paletteIdx = paletteIdx.coerceIn(0, maxOf(0, palettes.size - 1)); persistPalettes() }
    fun addPaletteColor(p: Palette, c: Color) { if (!p.colors.contains(c)) { p.colors.add(c); persistPalettes() } }
    fun removePaletteColor(p: Palette, c: Color) { p.colors.remove(c); persistPalettes() }

    var onion by mutableStateOf(true)
    var onionOpt by mutableStateOf(false)         // onion skin detail pop-up (hold the onion button)
    var onionFade by mutableStateOf(false)        // after a scrub ends the onion skin shows, then fades out over half a second
    var onionOffScrub by mutableStateOf(false)    // the onion skin is hidden while scrubbing
    var onionPrevious by mutableIntStateOf(1)
    var onionFollowing by mutableIntStateOf(1)
    var onionAllLayers by mutableStateOf(true) // show neighbouring drawings from every visible drawing layer
    var outOfPeg by mutableStateOf(false)             // non-destructive onion-reference alignment mode
    // ----- vector layers (session state) -----
    var vecMode by mutableIntStateOf(0)                 // 0 draw, 2 cutter, 3 interpolation drag, 4 guided interpolation joints
    var vecPoints by mutableStateOf(true)               // Selection tool on a vector layer: true = pick and edit single points (tap / lasso, drag, brushes), false = select whole lines and transform them
    var vecMergeRadius by mutableFloatStateOf(14f)      // Merge reach, screen px: how close the start / end of a line must come to another line (or to its own other end) to join it
    var vecDragT by mutableFloatStateOf(0.5f)           // interpolation preview position between the previous and current keyframe
    var vecDragFixed by mutableStateOf(false)           // interpolation drag: the in-between keeps the first keyframe's overall position (its points still interpolate relative to each other)
    var vecPlaceAll by mutableStateOf(Aff.ID)           // the preview's manual transform (document plane); session only
    var vecPlaceLines by mutableStateOf<Map<String, Aff>>(emptyMap()) // extra transform of single preview lines (key = line id), applied after vecPlaceAll
    var vecPlaceSteps by mutableStateOf<List<PreviewWarp>>(emptyList()) // Transform / Warp / Puppet Warp steps made on the preview with the Selection tool, in order; session only
    var vecXfBegin: () -> Unit = {}                      // start / apply / cancel the vector transform session: set by DrawingCanvas (they need its live-edit state)
    var vecXfApply: () -> Unit = {}
    var vecXfCancel: () -> Unit = {}
    var vecXf by mutableStateOf<VecXform?>(null)        // Selection tool on a vector layer: the live Transform / Warp / Puppet Warp session (real lines or the interpolation preview)
    var vecPreSel by mutableStateOf<Set<String>>(emptySet()) // the preview lines picked with the Selection tool (ids); empty = the whole preview
    var vecSmoothAll by mutableFloatStateOf(0f)         // interpolation drag "Smooth": smoothness 0..1 of the whole preview (points are not added, see relaxStroke); session only
    var vecSmoothLines by mutableStateOf<Map<String, Float>>(emptyMap()) // smoothness of single preview lines (key = line id), overriding vecSmoothAll
    var vecDragTLines by mutableStateOf<Map<String, Float>>(emptyMap()) // interpolation position of single picked preview lines (key = line id); the others follow vecDragT
    /** The position the drag shows: the first picked line's own, else the whole preview's. */
    fun dragTShown(): Float = vecPreSel.firstOrNull()?.let { vecDragTLines[it] } ?: vecDragT
    /** Dragging the position works on the picked preview lines only; with none picked it moves the whole preview (and drops the lines' own positions). */
    fun setDragT(v: Float) {
        val picked = vecPreSel
        if (picked.isEmpty()) { vecDragT = v; vecDragTLines = emptyMap() } else vecDragTLines = vecDragTLines + picked.associateWith { v }
    }
    val vecFixedAll = FixedAt()                         // where Fixed pins the preview (see FixedAt): the lines dragged on their own / the rest
    val vecFixedLines = FixedAt()
    fun resetPlace() { vecFixedAll.clear(); vecFixedLines.clear(); vecPlaceAll = Aff.ID; vecPlaceLines = emptyMap(); vecPlaceSteps = emptyList(); vecSmoothAll = 0f; vecSmoothLines = emptyMap(); vecDragTLines = emptyMap() }
    fun inbetweenPlace(): InbetweenPlace? = if (vecDragFixed || !vecPlaceAll.isIdentity || vecPlaceLines.isNotEmpty() || vecPlaceSteps.isNotEmpty() || vecSmoothAll > 0.001f || vecSmoothLines.isNotEmpty() || vecDragTLines.isNotEmpty())
        InbetweenPlace(vecDragFixed, vecPlaceAll, vecPlaceLines, vecLockWeight, vecSmoothAll, vecSmoothLines, vecDragTLines, vecPlaceSteps, vecFixedAll, vecFixedLines) else null
    var vecGhostPick by mutableStateOf(false)           // "Select ghost line": the next pen tap on a vector layer picks the ghost line nearest to it (it is pinned as the shown one)
    var vecSelCut by mutableStateOf(false)              // selection tool "Cut": a stroke across lines slices them (new end anchors where cut)
    var vecDisplace by mutableStateOf(false)            // selection tool "Displace": joints warp the selection like guide joints warp an in-between
    var vecDisplaceJoints by mutableStateOf<List<VectorGuideJoint>>(emptyList())
    var vecDisplaceSelected by mutableIntStateOf(-1)
    var vecGuideSelected by mutableIntStateOf(-1)       // selected guide joint index on the active interpolation span
    var vecSel by mutableStateOf<Set<Long>>(emptySet()) // selected points (vecKey: stroke index, point index) across the cels of vecSelCels (see VecScope)
    var vecSelCels by mutableStateOf<List<Cel>>(emptyList()) // the keyframes the selection belongs to (observable, so the widget bar follows it)
    var vecFalloff by mutableFloatStateOf(0f)           // point edit: proportional falloff radius in document px (0 = selected points only)
    var vecRaise by mutableStateOf(false)               // point edit: a vertical drag raises / lowers the points (elevation) instead of moving them
    var vecLockWeight by mutableStateOf(false)          // Selection tool on a vector layer: transforming the selection keeps the line weight (session only)
    var vecMergeSharp by mutableFloatStateOf(0.7f)      // Merge corner sharpness 0..1 stamped on the lines it joins (0 = the usual rounded turns)
    var vecDocZoom = 1f                                 // screen px per document px of the vector canvas, kept up to date by the canvas (for the selection-tool Merge button)
    var vecErasePoints by mutableStateOf(false)         // vector eraser: erase only the points under the pen (the line stays joined) instead of the part up to the next crossings
    var vecMerge by mutableStateOf(false)               // vector draw: a new line whose end touches an existing line becomes attached to it (one stroke)
    var vecAutoAnchor by mutableStateOf(true)           // vector draw: a new line gets anchors at its ends and curve tips; merged lines keep theirs up to date
    var vecTipSens by mutableFloatStateOf(0.5f)         // how gentle a turn counts as a curve tip, 0..1
    var vecAnchorOpen by mutableStateOf(false)          // the auto anchor options panel
    var vecGhost by mutableStateOf(false)               // line order onion skin: the next line of the previous / next keyframe shimmers; a new line takes its anchors
    var vecGhostNext by mutableStateOf(false)           // the ghost comes from the next keyframe instead of the previous one
    var vecGhostPin by mutableStateOf<String?>(null)    // id of the ghost line that stays shown (it is not left when a line is drawn over it, so it can be drawn in several strokes); null = the first untraced line
    var vecGhostPinKey by mutableLongStateOf(-1L)       // layer + keyframe the pin belongs to (a pin is ignored on any other keyframe)
    var reshapeMode by mutableIntStateOf(0)             // Reshape tool (tool 6) sub-tool: 0 Shift (default), 1 Smoothen, 2 Distort
    var vecBrushRadius by mutableFloatStateOf(40f)      // Reshape tool size (radius), document px
    var vecBrushStrength by mutableFloatStateOf(0.5f)   // Reshape tool strength 0..1
    var vecFillLive by mutableStateOf(true)             // Fill tool on a vector layer: live fills (recomputable) instead of baked shapes
    var vecFillAcross by mutableStateOf(false)          // ... and the lines of every visible drawing layer count as walls
    var vecInkOpen by mutableStateOf(false)             // the Line weight bar (curvature, light and taper weight of vector lines)
    var inkAngleCut by mutableFloatStateOf(315f)       // light direction used by the Line weight bar (session; stored per line when applied)
    var vecWobbleOpen by mutableStateOf(false)          // the Wobble bar (per-keyframe boil of vector lines)
    var vecInterpOpen by mutableStateOf(false)          // the interpolation detail menu of the selected keyframes
    var vecPointView by mutableIntStateOf(0)            // Selection tool on a vector layer: which points are drawn, 0 = all, 1 = only the anchor points, 2 = none (the lines stay; hit tests are unchanged). Session only
    var outOfPegSide by mutableIntStateOf(0)          // 0 idle, -1 previous, +1 following
    val referencePoses = mutableStateMapOf<Cel, ReferencePose>()   // Out of Peg pose of one specific drawing (cel), session-only
    var rotationSnap by mutableStateOf(true)          // 30-degree transform magnet
    var radialToolsOpen by mutableStateOf(false)
    var drawOutsideFrame by mutableStateOf(false)     // adaptive overscan around the Cut; export remains cropped to the Cut
    fun referencePose(cel: Cel?) = (if (cel == null) null else referencePoses[cel]) ?: ReferencePose()
    fun setReferencePose(cel: Cel?, pose: ReferencePose) { if (cel != null) referencePoses[cel] = pose }
    fun resetReferencePose(cel: Cel?) { if (cel != null) referencePoses.remove(cel) }
    fun resetReferencePoses(cut: Cut) { cut.layers.forEach { l -> l.cels.forEach { referencePoses.remove(it) } } }
    var cutDraft by mutableStateOf<CutDraft?>(null) // Cut settings panel open (pending values); null = closed
    var miniTl by mutableStateOf(false)           // fullscreen: single-layer minimal timeline unfolded
    var miniLayers by mutableStateOf(false)       // fullscreen: layer list unfolded under the current-layer button
    var timelineH by mutableFloatStateOf(190f) // dp
    var timelineSelectMode by mutableStateOf(false) // tap / drag timeline frame and diamond-key selection mode
    var rippleRetime by mutableStateOf(false)     // timeline: retiming a frame moves the boundary with its neighbour instead of pushing everything (layer length stays)
    var tlPanRequest by mutableIntStateOf(0)      // bumped to ask the Timeline to scroll to tlPanFrame
    var tlPanFrame by mutableIntStateOf(0)
    var tlFitRequest by mutableIntStateOf(0)      // bumped to ask the Timeline to zoom / pan to the playback brackets
    var timelineOpen by mutableStateOf(true)

    private var loaded = false
    /** The "open the last Cut at startup" check has run in this process. */
    var autoOpenDone = false
    /** A Cut waiting for the user's answer to "Delete this Cut?" (Settings > Safety > Confirm destructive actions). */
    var pendingDelete by mutableStateOf<Cut?>(null)
    /** Cuts with a crash-recovery snapshot newer than their saved file; computed once per process, at the first load. */
    var recoveryPending: List<Recovery.Pending> = emptyList()
    var recoveryResolved by mutableStateOf(false)
    fun load(dir: File, context: Context? = null) {
        if (loaded) return
        loaded = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        BrushAssets.init(dir); loadBrushes(dir); loadPalettes(dir)
        AppSettings.load(dir); timelineH = AppSettings.timelineHeightDp // Settings.kt: default timeline height; the other options are read where they act
        val t1 = android.os.SystemClock.elapsedRealtime()
        cuts.addAll(Store.load(dir, context))
        library.load(dir)
        val t2 = android.os.SystemClock.elapsedRealtime()
        // Thumbnails replay strokes (18.7 s for 7 cuts in the report): done in the background, the list shows them as they arrive.
        val toThumb = cuts.toList()
        Thread({ toThumb.forEach { c -> runCatching { c.refreshThumb() } } }, "cut-thumbnails").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY }.start()
        val t3 = android.os.SystemClock.elapsedRealtime()
        recoveryPending = runCatching { Recovery.pending(dir) }.getOrDefault(emptyList())
        CrashDiagnostics.log("INFO", "Startup load: brushes/palettes ${t1 - t0} ms, ${cuts.size} cut(s) read ${t2 - t1} ms, thumbnails started (background) ${t3 - t2} ms, recovery check ${android.os.SystemClock.elapsedRealtime() - t3} ms")
    }
    /** Drops a pending selection transform, the selection and the vector point selection (their pixels / masks / points are in the old document coordinates). */
    fun dropPendingTransform() { xform?.let { it.entry.count = Int.MAX_VALUE; it.entry.pinned = false }; xform = null; sel = null; vecSel = emptySet(); vecSelCels = emptyList() }
    fun saveAll(dir: File) { cuts.forEach { Store.save(dir, it); Recovery.afterSave(dir, it) }; persistBrushes() }
    fun close(dir: File) {
        clearMulti(); cutDraft = null; miniTl = false; miniLayers = false
        xform?.let { it.entry.count = Int.MAX_VALUE; it.entry.pinned = false }; xform = null; sel = null // drop any pending transform (tiles rebuild)
        open?.let { it.playing = false; Store.save(dir, it); Recovery.afterSave(dir, it); it.refreshThumb() }; open = null
    }
    fun remove(c: Cut, dir: File) { cuts.remove(c); File(dir, "cut_${c.id}.inb").delete(); Recovery.clear(dir, c.id); library.forget(c.id, dir) }
}

/**
 * The number the timeline shows for the frame at [index]. Frames are numbered by drawing: a clone (a frame that shares its drawing with an earlier frame)
 * has the number of the frame it copies, and the next frame with a new drawing takes the next number, so a clone does not use one up.
 */
fun drawingNumber(layer: Layer, index: Int): Int {
    val cels = layer.cels
    if (index !in cels.indices) return index + 1
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val target = cels[index].strokes
    var n = 0
    for (i in 0..index) {
        val s = cels[i].strokes
        if (seen.add(s)) n++
        if (s === target) return n
    }
    return n
}

/** Bijective base-26 letters for an in-between: 1 -> a ... 26 -> z, 27 -> aa. */
private fun inbetweenLetters(n: Int): String { var q = n; var s = ""; while (q > 0) { q--; s = ('a'.code + q % 26).toChar() + s; q /= 26 }; return s }

/**
 * Names the frame at [idx] an in-between of the drawing before it (3a, 3b, ...). The number is that of the nearest earlier frame that is not itself an in-between
 * (frames for which [skip] is true are stepped over too, for several frames named together); the letter is the first one after those already used before this frame
 * that no other frame of the layer carries. This is the naming of the timeline's "Set inbetweens" button; the frames the interpolation drag makes use it as well.
 * Call it inside the undoable step that creates / changes the frame ([Cut.record]), so the name is part of the same undo snapshot.
 */
fun nameAsInbetween(layer: Layer, idx: Int, skip: (Cel) -> Boolean = { false }) {
    val cels = layer.cels
    if (idx !in cels.indices) return
    var base = idx - 1
    while (base >= 0 && (skip(cels[base]) || cels[base].inbetweenName.isNotBlank())) base--
    val baseNumber = if (base < 0) 1 else drawingNumber(layer, base)
    val mine = Regex("$baseNumber[a-z]+")
    val used = HashSet<String>()
    for ((i, c) in cels.withIndex()) if (i != idx && c.inbetweenName.isNotBlank()) used.add(c.inbetweenName)
    var n = cels.take(idx).count { mine.matches(it.inbetweenName) } + 1
    while ("$baseNumber${inbetweenLetters(n)}" in used) n++
    cels[idx].inbetweenName = "$baseNumber${inbetweenLetters(n)}"
}

/** The timeline label of the frame at [index]: its own name (an inbetween such as 3a) or its [drawingNumber]. */
fun frameLabel(layer: Layer, index: Int): String = layer.cels.getOrNull(index)?.inbetweenName?.ifBlank { null } ?: drawingNumber(layer, index).toString()
