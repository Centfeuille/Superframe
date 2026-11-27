package com.example.inbetween

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun rot(o: Offset, deg: Float): Offset {
    val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
    return Offset(o.x * c - o.y * s, o.x * s + o.y * c)
}

private fun DrawScope.drawPoly(pts: List<Offset>, closed: Boolean, color: Color, w: Float) {
    if (pts.size < 2) return
    val p = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); if (closed) close() }
    drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun PerspectiveIconButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onHoverText: (String) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val hoverCallback by rememberUpdatedState(onHoverText)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp).pointerInput(description) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Enter -> hoverCallback(description)
                        PointerEventType.Exit -> hoverCallback("")
                    }
                }
            }
        }
    ) {
        Box(Modifier.size(24.dp)) {
            Icon(icon, contentDescription = description, tint = if (active) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(19.dp).align(Alignment.Center))
            if (description.contains("30 degrees")) Text("30°", color = Color.White, fontSize = 7.sp, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xDD202124), androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 1.dp))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawingCanvas(ed: Editor, cut: Cut, modifier: Modifier = Modifier) {
    val dw = cut.w.toFloat(); val dh = cut.h.toFloat()
    var tick by remember { mutableIntStateOf(0) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    val sess = remember { arrayOfNulls<Session>(1) }
    val st = remember { PenState() }
    val gz = remember { GzState() }
    var preSel by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var preShape by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // vector layers: live stroke, point-edit drag / lasso, cutter path
    val vecPts = remember { ArrayList<VectorPoint>() }
    // Merge feedback: rings where the start / current end of the line being drawn would join another line (same reach as the commit)
    var vecMergeStart by remember { mutableStateOf<Offset?>(null) }
    var vecMergeEnd by remember { mutableStateOf<Offset?>(null) }
    val vecMergeLines = remember { arrayOf<List<VectorStroke>>(emptyList()) }
    val vecMergeLen = remember { floatArrayOf(0f) } // length of the line being drawn (a line can only join its own start once it has gone some way)
    var vecLive by remember { mutableIntStateOf(0) } // bumped whenever vecPts changes: redraws the live vector stroke
    var vecLiveCels by remember { mutableStateOf<List<VecLiveCel>?>(null) } // incremental editors of the cels being dragged
    var vecLastApply by remember { mutableLongStateOf(0L) }                    // drag throttle: time of the last applied edit
    var vecPending by remember { mutableStateOf<(() -> Unit)?>(null) }          // the newest edit skipped by the throttle, applied on release
    var vecOrig by remember { mutableStateOf<List<VectorStroke>?>(null) } // strokes when a point drag began
    var vecLives by remember { mutableStateOf<List<Cel>?>(null) }           // the cels being edited in place during a drag (one per cel of the scope)
    var vecSc by remember { mutableStateOf<VecScope?>(null) }                // the scope a drag started on
    var vecBefore by remember { mutableStateOf<Snap?>(null) }
    var vecGuideChanged by remember { mutableStateOf(false) }
    var vecJointTapDel by remember { mutableStateOf(-1) }                    // guide joint gesture started on the already selected joint: a tap without drag deletes it (second tap)
    var vecDispTapDel by remember { mutableStateOf(-1) }                     // same for a Displace joint
    var vecJointDragged by remember { mutableStateOf(false) }                // a joint gesture has left the small joint tap slop (JOINT_TAP_SLOP_PX): it is a drag, not a tap
    var vecPlHandle by remember { mutableIntStateOf(H_NONE) }                // interpolation drag, Shift: handle being dragged (move when none is hit)
    var vecPlBox by remember { mutableStateOf<List<Offset>>(emptyList()) }   // its box at drag start (document space)
    var vecPlD0 by remember { mutableStateOf(Offset.Zero) }
    var vecPlBaseAll by remember { mutableStateOf(Aff.ID) }                  // the preview's transform at drag start
    var vecPlBaseLines by remember { mutableStateOf<Map<String, Aff>>(emptyMap()) }
    var vecPlIds by remember { mutableStateOf<Set<String>>(emptySet()) }     // lines the drag acts on (empty = the whole preview)
    var vecJointInv by remember { mutableStateOf(Aff.ID) }                   // guide joint drag: canvas -> joint space (undoes the preview's manual transform)
    val vecJointBaseRef = remember { arrayOfNulls<List<VectorStroke>>(1) }    // guide joint drag: the unmoved interpolation the joint's offset is measured from
    val vecPreviewRef = remember { arrayOfNulls<List<VectorStroke>>(1) }      // the interpolation preview as drawn (set while composing, read by gestures)
    val vecDisplaceRef = remember { arrayOfNulls<List<Warped>>(1) }           // Displace: the warped selected lines as drawn, with their arc positions
    var vecDragX0 by remember { mutableFloatStateOf(0f) }                    // interpolation drag: pen x and in-between position at the start
    var vecDragT0 by remember { mutableFloatStateOf(0.5f) }
    var vecDrag by remember { mutableIntStateOf(0) }                         // point edit: 1 dragging points, 2 lasso
    var vecLasso by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var vecCutPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val vecCtx = androidx.compose.ui.platform.LocalContext.current
    var vecPen by remember { mutableStateOf<VecPen?>(null) }                 // the drawn line's brush state: stabilizer, smoothed pressure, tapers
    var vecBrushCur by remember { mutableStateOf<List<VectorStroke>?>(null) } // point-edit brush: the strokes as the brush has left them
    var vecBrushLast by remember { mutableStateOf(Offset.Zero) }
    var vecBrushPhase by remember { mutableIntStateOf(0) }
    var vecXfHandle by remember { mutableIntStateOf(H_NONE) }                // selection tool on a vector layer: handle being dragged
    var vecXfBox by remember { mutableStateOf<List<Offset>>(emptyList()) }   // its box at drag start (document space)
    var vecXfD0 by remember { mutableStateOf(Offset.Zero) }
    var vecXfOrig by remember { mutableStateOf<List<VectorStroke>?>(null) }  // Transform / Warp / Puppet session on real lines: the strokes when it began
    var vecXfSc by remember { mutableStateOf<VecScope?>(null) }              // ... and the scope it works on
    var vecXfPrev by remember { mutableStateOf<List<VectorStroke>?>(null) }  // session on the interpolation preview: the preview lines when it began
    val vecFixedCtx = remember { arrayOfNulls<Any>(1) }                      // frame and layer the Fixed pin was taken on
    val vecXfCtx = remember { arrayOfNulls<Any>(2) }                         // frame and layer the session began on (it ends when either changes)
    var liveOn by remember { mutableStateOf(false) }                  // wand / fill preview overlay (st.bmp)
    var thr by remember { mutableIntStateOf(-1) }
    var bez by remember { mutableStateOf<Bez?>(null) }                   // Bezier curve: end points set, handles follow the pen until taps
    var pend by remember { mutableStateOf<Ell?>(null) }               // ellipse: major axis set, minor follows the pen until a tap
    var pendP by remember { mutableFloatStateOf(1f) }
    var glow by remember { mutableIntStateOf(0) } // finger scrubbing edge glow: -1 left, 1 right
    var keyBump by remember { mutableIntStateOf(0) }
    var bumpDir by remember { mutableIntStateOf(1) }      // side of the last scrub bump: -1 left, 1 right
    var newFramePull by remember { mutableFloatStateOf(0f) } // 0..1 progress of the pull past the last frame that arms a new frame
    val keyBumpAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var fingerTouches by remember { mutableStateOf(emptyList<Offset>()) }
    var resetCue by remember { mutableStateOf(false) }
    var perspectiveHover by remember { mutableStateOf("") }
    var activePerspectiveGuide by remember { mutableIntStateOf(0) }
    var fovDialog by remember { mutableIntStateOf(-1) }
    var fovValue by remember { mutableFloatStateOf(90f) }
    var curvatureDialog by remember { mutableIntStateOf(-1) }
    var curvatureStrengthValue by remember { mutableFloatStateOf(1f) }
    var raysDialog by remember { mutableIntStateOf(-1) }
    var rayCountValue by remember { mutableFloatStateOf(24f) }
    var shapePerspective by remember { mutableStateOf<Persp?>(null) }
    var shapeMagnetState by remember { mutableStateOf<PerspectiveMagnetState?>(null) }
    var shapeMagnetAnchor by remember { mutableStateOf<Offset?>(null) }
    var shapeHoverDirection by remember { mutableStateOf<Offset?>(null) }
    var hoverLastPosition by remember { mutableStateOf<Offset?>(null) }
    var hoverDirection by remember { mutableStateOf<Offset?>(null) }
    var hoverDirectionAt by remember { mutableStateOf(0L) }
    var fovText by remember { mutableStateOf("90") }
    var focalText by remember { mutableStateOf("18") }
    var focalValue by remember { mutableFloatStateOf(18f) }
    val purple = MaterialTheme.colorScheme.primary
    LaunchedEffect(keyBump) {
        if (keyBump > 0) {
            keyBumpAnim.snapTo(1f)
            keyBumpAnim.animateTo(0f, androidx.compose.animation.core.tween(220))
        }
    }
    val poly = remember { mutableStateListOf<Offset>() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val videoLayers by remember(cut) {
        derivedStateOf { cut.layers.filter { it.kind == 7 && it.visible && it.mediaUri.isNotBlank() } }
    }
    // cut.time changes on every playback tick. derivedStateOf only invalidates this composable
    // when crossing a video clip's active/inactive boundary, avoiding a full recomposition per frame.
    val videoActive by remember(cut) {
        derivedStateOf {
            val t = cut.time
            cut.layers.any { it.kind == 7 && it.visible && it.mediaUri.isNotBlank() &&
                t >= it.mediaStartFrame && t < it.mediaStartFrame + it.mediaFrames(cut.fps) }
        }
    }
    // GPU layer compositor (GlLayers.kt): layers [0, glK) are drawn by OpenGL in a plane below this Canvas. Compose only skips
    // what the plane really shows (glSkip / glSkipOnion), so a transition can double-draw a layer for a frame but never lose one.
    val glK = gpuPlan(cut, ed)
    val glSkip = if (glK > 0) minOf(glK, GpuLayers.shownK).coerceAtLeast(0) else 0
    val glSkipOnion = glK >= 0 && GpuLayers.shownOnion
    // Page and Behind grid live in their own planes below the video / GPU layers whenever either is in use.
    val planes = videoActive || glK >= 0 || GpuLayers.shownK >= 0
    val selPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val textPaint = remember { Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 38f; isFakeBoldText = true; setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt()) } }
    val red = remember { PorterDuffColorFilter(0xFFE53935.toInt(), PorterDuff.Mode.SRC_IN) }
    val green = remember { PorterDuffColorFilter(0xFF43A047.toInt(), PorterDuff.Mode.SRC_IN) }
    val delPainter = rememberVectorPainter(Icons.Filled.Delete)
    val sgx = if (ed.flipH) -1f else 1f; val sgy = if (ed.flipV) -1f else 1f
    val fit = if (box.width == 0) 1f else minOf(box.width / dw, box.height / dh)
    val cx = box.width / 2f; val cy = box.height / 2f

    // Onion skin strength. Any frame change counts as scrubbing; ~0.22 s without a change ends the scrub.
    //  neither option: always 1 | "Turn off when scrubbing": 0 while scrubbing, 1 once it stops
    //  "Fade out": 1 while scrubbing, fades to 0 over 0.5 s once it stops | both: 0 while scrubbing, then 1 and fades to 0
    val onionK = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(ed.onionFade, ed.onionOffScrub) {
        // Observe the playhead outside composition. A frame tick restarts only this small
        // effect instead of recomposing DrawingCanvas and rebuilding its local helpers.
        snapshotFlow { cut.time }.collectLatest {
            if (!ed.onionFade && !ed.onionOffScrub) { onionK.snapTo(1f); return@collectLatest }
            onionK.snapTo(if (ed.onionOffScrub) 0f else 1f)
            kotlinx.coroutines.delay(220)
            onionK.snapTo(1f)
            if (ed.onionFade) onionK.animateTo(0f, androidx.compose.animation.core.tween(500))
        }
    }

    LaunchedEffect(ed.tool, ed.selMode, ed.selWand, ed.lineShape) { preSel = emptyList(); preShape = emptyList(); poly.clear(); pend = null; bez = null; liveOn = false; thr = -1 }

    /** "Relative position": a pinned reference layer lives in HOME-view coordinates (view reset, no flips), i.e. it is anchored to the screen. */
    fun homeScr(p: Offset): Offset = (p - Offset(dw / 2, dh / 2)) * fit + Offset(cx, cy)
    fun homeToDoc(x: Float, y: Float): Offset = Offset((x - cx) / fit + dw / 2, (y - cy) / fit + dh / 2)
    fun pinnedActive(): Boolean = cut.layer?.let { it.kind == 5 && it.refPinned } == true
    /** Screen -> document through the CURRENT view only (no layer group). */
    fun viewToDoc(x: Float, y: Float): Offset {
        val q = rot(Offset(x - cx, y - cy) - ed.vOff, -ed.vRot)
        return Offset(q.x * sgx, q.y * sgy) / (fit * ed.vScale) + Offset(dw / 2, dh / 2)
    }
    /** Home-view coordinates -> document coordinates of the current view; drawing with it makes pinned images stay put on screen. */
    fun pinMatrix(): Matrix {
        val src = floatArrayOf(0f, 0f, dw, 0f, 0f, dh)
        val dst = FloatArray(6)
        val pts = arrayOf(Offset(0f, 0f), Offset(dw, 0f), Offset(0f, dh))
        for (i in 0..2) { val sc = homeScr(pts[i]); val back = viewToDoc(sc.x, sc.y); dst[i * 2] = back.x; dst[i * 2 + 1] = back.y }
        return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 3) }
    }
    SideEffect { ed.refPinProvider = { pinMatrix() } }
    DisposableEffect(ed) { onDispose { ed.refPinProvider = null } }
    /** A screen point in the coordinates the images of [layer] are stored in. */
    fun refLocal(layer: Layer, sp: Offset): Offset {
        if (layer.refPinned) return homeToDoc(sp.x, sp.y)
        val doc = viewToDoc(sp.x, sp.y)
        if (layer.group < 0) return doc
        val tr = cut.transformFor(layer, cut.time); val center = Offset(cut.w / 2f, cut.h / 2f)
        return rot(doc - center - Offset(tr.x, tr.y), -tr.rotation) / tr.scale + center
    }
    /** The topmost image of a reference layer under a screen point. */
    fun refInkAt(layer: Layer, sp: Offset): Ink? {
        val local = refLocal(layer, sp)
        val inks = layer.cels.firstOrNull()?.strokes ?: return null
        for (i in inks.indices.reversed()) {
            val ink = inks[i]; if (ink.kind != 3) continue
            val path = ink.data?.toString(Charsets.UTF_8) ?: continue
            val values = ink.m ?: continue
            val dims = ReferenceBitmapCache.dimensions(path) ?: continue
            val inv = Matrix()
            if (!Matrix().apply { setValues(values) }.invert(inv)) continue
            val pt = floatArrayOf(local.x, local.y); inv.mapPoints(pt)
            if (pt[0] >= 0f && pt[1] >= 0f && pt[0] <= dims.first && pt[1] <= dims.second) return ink
        }
        return null
    }
    /** Pen tap outside every image: the edit is done; go back to the layer the reference layer was entered from. */
    fun exitReference() {
        ed.refSel = null
        val back = cut.layerById(ed.refReturnId)?.takeIf { it.kind != 5 && !it.isGroup } ?: cut.layers.lastOrNull { it.kind == 0 && !it.isGroup }
        if (back != null) cut.layerId = back.id
        tick++
    }

    fun toDoc(x: Float, y: Float, applyLayerGroup: Boolean = true): Offset {
        if (applyLayerGroup && ed.cutDraft == null && pinnedActive()) return homeToDoc(x, y)
        val q = rot(Offset(x - cx, y - cy) - ed.vOff, -ed.vRot)
        var p = Offset(q.x * sgx, q.y * sgy) / (fit * ed.vScale) + Offset(dw / 2, dh / 2)
        val active = cut.layer?.takeIf { it.kind == 0 || it.kind == 4 || it.kind == 5 || it.kind == 2 || it.kind == 3 }
        if (applyLayerGroup && ed.cutDraft == null && active != null && active.group >= 0) {
            val tr = cut.transformFor(active, cut.time); val center = Offset(dw / 2, dh / 2)
            p = rot(p - center - Offset(tr.x, tr.y), -tr.rotation) / tr.scale + center
        }
        return p
    }
    fun toScr(p: Offset): Offset {
        if (ed.cutDraft == null && pinnedActive()) return homeScr(p)
        var doc = p
        val active = cut.layer?.takeIf { it.kind == 0 || it.kind == 4 || it.kind == 5 || it.kind == 2 || it.kind == 3 }
        if (ed.cutDraft == null && active != null && active.group >= 0) {
            val tr = cut.transformFor(active, cut.time); val center = Offset(dw / 2, dh / 2)
            doc = rot((p - center) * tr.scale, tr.rotation) + center + Offset(tr.x, tr.y)
        }
        val q = Offset((doc.x - dw / 2) * sgx, (doc.y - dh / 2) * sgy) * (fit * ed.vScale)
        return rot(q, ed.vRot) + Offset(cx, cy) + ed.vOff
    }
    fun persp(): Persp? = cut.layers.firstOrNull { it.kind == 3 && it.visible }?.persp
    /** The visible part of the page in document coordinates (bounding box of the screen corners). */
    fun viewRect(): RectF {
        val bw = box.width.toFloat(); val bh = box.height.toFloat()
        val pts = listOf(toDoc(0f, 0f, false), toDoc(bw, 0f, false), toDoc(0f, bh, false), toDoc(bw, bh, false))
        return RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    // ----- transform handles: selection / camera / Transformation folder -----
    val folderSel: Layer? = cut.layers.firstOrNull { it.isGroup && it.id == cut.layerId && it.transformFolder }
    val selRect = remember(ed.sel) { ed.sel?.let { maskBounds(it) } }
    // Folder content bounds: scanned once per cel on a background thread and cached (the old code rescanned a full bitmap on every scrubbed frame).
    val scope = rememberCoroutineScope()
    val celBounds = remember { java.util.IdentityHashMap<Any, Pair<Int, RectF?>>() }
    val celBusy = remember { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()) }
    val lastFolderRect = remember { arrayOfNulls<RectF>(1) }
    LaunchedEffect(cut.w, cut.h) { celBounds.clear(); lastFolderRect[0] = null }
    fun folderRectNow(f: Layer): RectF {
        var u: RectF? = null; var pending = false
        for (l in cut.layers) {
            if (l.group != f.id || l.isGroup || !l.visible || (l.kind != 0 && l.kind != 4 && l.kind != 5)) continue
            val cel = l.drawCel(cut.time) ?: continue
            val sk = cel.strokes
            if (sk.isEmpty()) continue
            val e = celBounds[sk]
            if (e != null && e.first == sk.size) { e.second?.let { r -> if (u == null) u = RectF(r) else u!!.union(r) } }
            else {
                pending = true
                if (celBusy.add(sk)) {
                    val n = sk.size
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val r = runCatching { celContentBounds(sk, cut.w, cut.h) }.getOrNull()
                        withContext(kotlinx.coroutines.Dispatchers.Main) { celBounds[sk] = n to r; celBusy.remove(sk); tick++ }
                    }
                }
            }
        }
        if (pending) return lastFolderRect[0] ?: RectF(0f, 0f, cut.w.toFloat(), cut.h.toFloat())
        val res = u ?: RectF(0f, 0f, cut.w.toFloat(), cut.h.toFloat())
        lastFolderRect[0] = res
        return res
    }

    /** 0 selection / live transform, 1 camera, 2 Transformation folder, -1 none. */
    fun gCtx(): Int {
        if (cut.playing) return -1
        if (ed.cutDraft != null) return 3
        if (ed.xform != null) return 0
        if (ed.vecXf != null && ed.tool == 2) return 7 // vector Transform / Warp / Puppet session (box handles)
        if (cut.layer?.kind == 2 && cut.layer?.cam != null) return 1
        if (folderSel != null) return 2
        if (cut.layer?.kind == 5 && cut.layer?.visible == true && cut.layer?.locked == false) return 5
        if (cut.layer?.kind == 7 && cut.layer?.visible == true && cut.layer?.locked == false) return 6
        if (ed.outOfPeg && ed.outOfPegSide != 0 && cut.layer?.kind == 0) {
            if (cut.layer!!.adjacentCel(cut.time, ed.outOfPegSide) != null) return 4
        }
        if (ed.sel != null && ed.tool == 2 && cut.drawLayer != null) return 0
        return -1
    }
    fun gBox(ctx: Int): List<Offset>? = when (ctx) {
        0 -> ed.xform?.let { x -> rectBox(RectF(x.lift.left.toFloat(), x.lift.top.toFloat(), (x.lift.left + x.lift.bmp.width).toFloat(), (x.lift.top + x.lift.bmp.height).toFloat()), x.mat) }
            ?: selRect?.let { rectBox(it) }
        7 -> ed.vecXf?.let { rectBox(it.rect, it.mat) }
        1 -> cut.layer?.cam?.let { camBox(it, cut, cut.time) }
        2 -> if (folderSel != null) folderBox(cut, folderSel, cut.time, folderRectNow(folderSel)) else null
        3 -> ed.cutDraft?.let { rectBox(RectF(-it.ox.toFloat(), -it.oy.toFloat(), (it.w - it.ox).toFloat(), (it.h - it.oy).toFloat())) } // the pending frame; a pulled left / top edge sits left of / above the page origin
        6 -> run {
            val layer = cut.layer ?: return@run null
            val tr = cut.transformFor(layer, cut.time)
            val c = Offset(cut.w / 2f, cut.h / 2f)
            val sx = tr.scale * layer.mediaScale
            val a = tr.rotation + layer.mediaRotation
            rectBox(RectF(0f, 0f, cut.w.toFloat(), cut.h.toFloat())).map { point ->
                c + rot(Offset((point.x - c.x) * sx, (point.y - c.y) * sx), a) + Offset(tr.x + layer.mediaX, tr.y + layer.mediaY)
            }
        }
        5 -> run {
            val layer = cut.layer
            val picked = ed.refSel
            val ink = if (picked != null && layer?.cels?.firstOrNull()?.strokes?.any { it === picked } == true) picked else null // handles only on the picked image
            val path = ink?.data?.toString(Charsets.UTF_8)
            if (ink == null || path == null) null else {
                val dimensions = ReferenceBitmapCache.dimensions(path) ?: return@run null
                val m = android.graphics.Matrix().apply { setValues(ink.m ?: FloatArray(9)) }
                rectBox(RectF(0f, 0f, dimensions.first.toFloat(), dimensions.second.toFloat()), m)
            }
        }
        4 -> {
            val layer = cut.layer
            if (layer == null) null else {
                val adjacentCel = layer.adjacentCel(cut.time, ed.outOfPegSide)
                if (adjacentCel == null) null else {
                    val pose = ed.referencePose(adjacentCel)
                    val c = Offset(cut.w / 2f, cut.h / 2f)
                    rectBox(RectF(0f, 0f, cut.w.toFloat(), cut.h.toFloat())).map { p ->
                        val sx = pose.scaleX * if (pose.flipX) -1f else 1f
                        val sy = pose.scaleY * if (pose.flipY) -1f else 1f
                        c + gRot(Offset((p.x - c.x) * sx, (p.y - c.y) * sy), pose.rotation) + Offset(pose.x, pose.y)
                    }
                }
            }
        }
        else -> null
    }

    var selModHeld = false // Shift / Alt / Ctrl held on a keyboard while the pen goes down: the selection tools then add / erase instead of grabbing the old handles

    /** Runs of the selection a new stroke / shape is limited to (the same array is shared by consecutive strokes of one selection). */
    val clipCache = remember { arrayOfNulls<Any>(2) }
    fun paintClip(): IntArray? {
        val s = ed.sel ?: return null
        if (!ed.paintInSel || ed.xform != null) return null
        if (clipCache[0] !== s) { clipCache[0] = s; clipCache[1] = selRuns(s) }
        return (clipCache[1] as IntArray).takeIf { it.isNotEmpty() }
    }

    /** Starts a handle drag when the point lies on a handle of the active box. Tapping a reference image auto-selects its own layer. */
    fun gDown(sp: Offset, slop: Float, allowMove: Boolean): Boolean {
        if (!cut.playing && ed.cutDraft == null) {
            // A handle of the picked image wins over any other image below the finger; otherwise the topmost image under the finger is picked
            // (and its layer selected), whichever reference layer it belongs to.
            var keepPicked = false
            val cl0 = cut.layer; val picked0 = ed.refSel
            if (cl0 != null && cl0.kind == 5 && cl0.visible && !cl0.locked && picked0 != null && cl0.cels.firstOrNull()?.strokes?.any { it === picked0 } == true) {
                val bx = gBox(5)
                if (bx != null) { val hh = hitHandle(bx.map { toScr(it) }, sp, slop, slop + 60f); keepPicked = hh != H_NONE && hh != H_MOVE }
            }
            if (!keepPicked) {
                for (layer in cut.layers.asReversed()) {
                    if (layer.kind != 5 || !layer.visible || layer.locked) continue
                    val k = refInkAt(layer, sp)
                    if (k != null) { cut.layerId = layer.id; ed.refSel = k; break }
                }
            }
        }
        val ctx = gCtx(); if (ctx < 0) return false
        if (ctx == 0 && ed.xform == null && (ed.selOp != 0 || selModHeld)) return false // adding to / erasing from the selection: a new outline may start inside the old box
        val box = gBox(ctx) ?: return false
        val handleSlop = if (ctx == 3) max(slop, 38f) else slop
        val h = hitHandle(box.map { toScr(it) }, sp, handleSlop, handleSlop + 60f)
        if (h == H_NONE) return false
        if (h == H_MOVE && !(ctx == 0 || ctx == 7 || ctx == 5 || (ctx == 4 && allowMove) || (ctx == 2 && allowMove) || (ctx == 6 && allowMove))) return false // selection and Out of Peg move directly; Camera has its own nudge path
        if (ctx == 3 && h !in 0..7) return false // Cut: every corner and edge handle resizes it (the page origin stays at the top-left)
        if (ctx == 0 && ed.xform == null) { beginTransform(ed, cut); if (ed.xform == null) return false }
        val b0 = gBox(ctx) ?: return false
        gz.ctx = ctx; gz.h = h; gz.d0 = toDoc(sp.x, sp.y, applyLayerGroup = ctx != 3); gz.box0 = b0; gz.snap = null
        val t = cut.time
        when (ctx) {
            3 -> ed.cutDraft?.let { gz.w0 = it.w; gz.h0 = it.h; gz.ox0 = it.ox; gz.oy0 = it.oy }
            0 -> gz.m0 = Matrix(ed.xform!!.mat)
            7 -> gz.m0 = Matrix(ed.vecXf!!.mat)
            1 -> cut.layer?.cam?.let { c -> gz.x0 = c.x(t, cut.w); gz.y0 = c.y(t, cut.h); gz.s0 = c.sc(t); gz.r0 = c.rot(t); gz.w0 = c.w; gz.h0 = c.h; gz.snap = cut.snap() }
            2 -> folderSel?.let { f -> val tr = f.transformAt(t); gz.x0 = tr.x; gz.y0 = tr.y; gz.s0 = tr.scale; gz.r0 = tr.rotation; gz.folder = f; gz.snap = cut.snap() }
            4 -> cut.layer?.let { l -> gz.referenceCel = l.adjacentCel(cut.time, ed.outOfPegSide); gz.reference0 = ed.referencePose(gz.referenceCel) }
            6 -> cut.layer?.let { l ->
                gz.x0 = l.mediaX; gz.y0 = l.mediaY; gz.s0 = l.mediaScale; gz.r0 = l.mediaRotation
                gz.folder = l
                gz.snap = cut.snap()
            }
            5 -> cut.layer?.let { l ->
                gz.referenceLayer = l
                val picked = ed.refSel
                val ink = if (picked != null && l.cels.firstOrNull()?.strokes?.any { it === picked } == true) picked else null
                gz.referenceInk = ink
                gz.referenceMatrix0 = ink?.m?.copyOf()
            }
        }
        return true
    }

    fun gMove(sp: Offset) {
        if (gz.h == H_NONE) return
        val d = toDoc(sp.x, sp.y, applyLayerGroup = gz.ctx != 3); val t = cut.time
        when (gz.ctx) {
            3 -> ed.cutDraft?.let { dr ->
                // Which side the handle is on, read from where it sits on the box (corners 0..3 clockwise from the top-left, edges 4..7 between them): -1 left / top, +1 right / bottom, 0 = this axis is not touched.
                val bx = gz.box0
                val hp = if (gz.h < 4) bx[gz.h] else Offset((bx[gz.h - 4].x + bx[(gz.h - 3) % 4].x) / 2f, (bx[gz.h - 4].y + bx[(gz.h - 3) % 4].y) / 2f)
                val mx = (bx[0].x + bx[2].x) / 2f; val my = (bx[0].y + bx[2].y) / 2f
                val sideX = if (hp.x > mx + 1f) 1 else if (hp.x < mx - 1f) -1 else 0
                val sideY = if (hp.y > my + 1f) 1 else if (hp.y < my - 1f) -1 else 0
                // Dragging a handle outwards makes the Cut bigger whichever side it is on. A left / top handle also moves the page's own left / top edge (dr.ox / dr.oy),
                // so the content is shifted by that amount when the settings are confirmed; the right / bottom edge stays where it was.
                val dx = (d.x - gz.d0.x) * sideX; val dy = (d.y - gz.d0.y) * sideY
                val resizeX = sideX != 0
                val resizeY = sideY != 0
                if (cut.lockA && resizeX && resizeY) {
                    when {
                        cut.lockW && !cut.lockH -> dr.changeH(gz.h0 + dy.roundToInt())
                        cut.lockH && !cut.lockW -> dr.changeW(gz.w0 + dx.roundToInt())
                        abs(dx) >= abs(dy) -> dr.changeW(gz.w0 + dx.roundToInt())
                        else -> dr.changeH(gz.h0 + dy.roundToInt())
                    }
                } else {
                    if (resizeX) dr.changeW(gz.w0 + dx.roundToInt())
                    if (resizeY) dr.changeH(gz.h0 + dy.roundToInt())
                }
                // The edge that moved is the one the handle sits on: the offset follows the size that was actually applied (locks and limits may have refused part of the drag).
                // With Stretch on the whole drawing is scaled instead, so nothing is offset.
                if (!dr.stretch) {
                    if (sideX < 0) dr.ox = gz.ox0 + (dr.w - gz.w0)
                    if (sideY < 0) dr.oy = gz.oy0 + (dr.h - gz.h0)
                }
            }
            6 -> {
                val layer = gz.folder ?: return
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, true)
                val c = Offset(cut.w / 2f, cut.h / 2f)
                val mapped = op.m.mapOf(c + Offset(gz.x0, gz.y0))
                val tr = cut.transformFor(layer, t)
                // Media translation is expressed in document axes. The renderer applies
                // the folder transform to the page geometry, but keeps the layer offset
                // as a document-space translation, so invert only that translation here.
                layer.mediaX = mapped.x - c.x - tr.x
                layer.mediaY = mapped.y - c.y - tr.y
                if (gz.h != H_MOVE) layer.mediaScale = (gz.s0 * op.sx).coerceIn(0.05f, 20f)
                if (gz.h == H_ROT) { val rot = gz.r0 + op.dRot; layer.mediaRotation = if (ed.rotationSnap) (rot / 30f).roundToInt() * 30f else rot } // magnet: absolute 30-degree steps, like every other transform
            }
            5 -> {
                val ink = gz.referenceInk; val base = gz.referenceMatrix0
                if (ink != null && base != null) {
                    val op = gizmoOp(gz.h, gz.box0, gz.d0, d, ed.xfLock)
                    val original = android.graphics.Matrix().apply { setValues(base) }
                    val result = android.graphics.Matrix().apply { setConcat(op.m, original) }
                    if (gz.h == H_ROT && ed.rotationSnap) {
                        val values = FloatArray(9); result.getValues(values)
                        val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
                        val snapped = (angle / 30f).roundToInt() * 30f
                        val center = (gz.box0[0] + gz.box0[2]) / 2f
                        result.postRotate(snapped - angle, center.x, center.y)
                    }
                    val target = ink.m ?: return
                    result.getValues(target)
                    gz.referenceLayer?.let { it.imageRevision++ }
                    tick++
                }
            }
            4 -> gz.referenceCel?.let { refCel ->
                val p0 = gz.reference0
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, ed.xfLock)
                val c = Offset(cut.w / 2f, cut.h / 2f)
                val moved = op.m.mapOf(c + Offset(p0.x, p0.y)) - c
                val sx = (p0.scaleX * op.sx).coerceIn(0.05f, 20f)
                val sy = (p0.scaleY * op.sy).coerceIn(0.05f, 20f)
                val rot = p0.rotation + op.dRot
                ed.setReferencePose(refCel, p0.copy(x = moved.x, y = moved.y, scaleX = sx, scaleY = sy, rotation = if (ed.rotationSnap) (rot / 30f).roundToInt() * 30f else rot))
            }
            0 -> ed.xform?.let { x ->
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, ed.xfLock)
                val m = Matrix(gz.m0); m.postConcat(op.m)
                if (gz.h == H_ROT && ed.rotationSnap) {
                    val values = FloatArray(9); m.getValues(values)
                    val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
                    val snapped = (angle / 30f).roundToInt() * 30f
                    val center = (gz.box0[0] + gz.box0[2]) / 2f
                    m.postRotate(snapped - angle, center.x, center.y)
                }
                x.mat.set(m); x.rev++
            }
            7 -> ed.vecXf?.let { x ->
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, ed.xfLock)
                val m = Matrix(gz.m0); m.postConcat(op.m)
                if (gz.h == H_ROT && ed.rotationSnap) {
                    val values = FloatArray(9); m.getValues(values)
                    val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
                    val center = (gz.box0[0] + gz.box0[2]) / 2f
                    m.postRotate((angle / 30f).roundToInt() * 30f - angle, center.x, center.y)
                }
                x.mat.set(m); x.rev++
            }
            1 -> cut.layer?.cam?.let { cam ->
                val uni = ed.xfLock || cam.lockA
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, uni, cam.lockW, cam.lockH)
                val c1 = op.m.mapOf(Offset(gz.x0, gz.y0))
                if (gz.h == H_ROT) {
                    val angle = gz.r0 + op.dRot
                    setKey(cam.rs, t, if (ed.rotationSnap) (angle / 30f).roundToInt() * 30f else angle, ed.interp, ed.ease)
                }
                else if (gz.h != H_MOVE) {
                    if (abs(op.sx - op.sy) < 1e-4f) { // proportional: the frame scale key changes, the frame size stays
                        if (cam.w != gz.w0) cam.w = gz.w0; if (cam.h != gz.h0) cam.h = gz.h0
                        setKey(cam.ss, t, (gz.s0 * op.sx).coerceIn(0.05f, 20f), ed.interp, ed.ease)
                    } else { // stretch: the frame's own width / height change (same fields as the Camera frame panel)
                        if (cam.stretchedFrom == null) cam.stretchedFrom = gz.w0 to gz.h0
                        cam.w = (gz.w0 * op.sx).roundToInt().coerceIn(16, 8192); cam.h = (gz.h0 * op.sy).roundToInt().coerceIn(16, 8192)
                    }
                }
                setKey(cam.xs, t, c1.x, ed.interp, ed.ease); setKey(cam.ys, t, c1.y, ed.interp, ed.ease)
            }
            2 -> gz.folder?.let { f ->
                val op = gizmoOp(gz.h, gz.box0, gz.d0, d, true) // folder scale is uniform
                val c = Offset(cut.w / 2f, cut.h / 2f)
                val p = op.m.mapOf(c + Offset(gz.x0, gz.y0)) - c
                setKey(f.transformX, t, p.x, ed.interp, ed.ease); setKey(f.transformY, t, p.y, ed.interp, ed.ease)
                if (gz.h == H_ROT) {
                    val angle = gz.r0 + op.dRot
                    setKey(f.transformRotation, t, if (ed.rotationSnap) (angle / 30f).roundToInt() * 30f else angle, ed.interp, ed.ease)
                }
                else if (gz.h != H_MOVE) setKey(f.transformScale, t, (gz.s0 * op.sx).coerceIn(0.05f, 20f), ed.interp, ed.ease)
            }
        }
        tick++
    }

    fun gUp() {
        if (gz.ctx == 1 || gz.ctx == 2 || gz.ctx == 6) gz.snap?.let { cut.pushStruct(it) }
        if (gz.ctx == 5) { val ink = gz.referenceInk; val before = gz.referenceMatrix0; if (ink != null && before != null) gz.referenceLayer?.let { cut.pushReferenceMatrix(it, ink, before) } }
        gz.h = H_NONE; gz.snap = null; gz.folder = null; gz.referenceInk = null; gz.referenceLayer = null; gz.referenceMatrix0 = null; tick++
    }

    fun feedEv(ev: MotionEvent, s: Session) {
        for (h in 0 until ev.historySize) {
            val d = toDoc(ev.getHistoricalX(h), ev.getHistoricalY(h))
            s.feed(d.x, d.y, ev.getHistoricalPressure(h), ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h), ev.getHistoricalEventTime(h))
        }
        val d = toDoc(ev.x, ev.y)
        s.feed(d.x, d.y, ev.pressure, ev.getAxisValue(MotionEvent.AXIS_TILT), ev.eventTime)
    }

    fun commitOp(l: Layer, ink: Ink) {
        val cel = cut.ensureCelUniform(l); val ts = TileCache.get(cel.strokes)
        ts.stamp(ink); cut.commit(cel, ink); ts.count = cel.strokes.size
    }

    /** Screen px per document px of the vector canvas (declared before its first use: local functions must precede their callers). */
    fun vecZoom(): Float { val z = (fit * ed.vScale).coerceAtLeast(0.0001f); ed.vecDocZoom = z; return z }

    /**
     * Anchors for a line just drawn on [l]. With the ghost on and the line lying on an untraced ghost line it takes that line's anchor numbers (the ghost gets automatic anchors
     * first, as a separate undo step, when it has none); otherwise it gets automatic anchors at its ends and tips, with numbers that none of the neighbouring keyframes uses.
     */
    val anchorDeferred = remember { BooleanArray(1) }
    // A line traced from a ghost must never fall back to fresh endpoint numbering:
    // the ghost's free anchor numbers are the correspondence contract for this stroke.
    val anchorGhostTrace = remember { BooleanArray(1) }
    // The ghost line (made ready, with its key) that the stroke being committed could not follow while it was still open: a closed ghost and a trace that only becomes a ring
    // when Merge closes its ends. vecFreshAfterMerge lets the closed trace follow it then.
    val anchorGhostRef = remember { arrayOfNulls<Pair<VectorStroke, Long>>(1) }
    /** Fresh automatic anchors for a stroke that is not traced over a ghost line. */
    fun vecFreshAnchors(l: Layer, stroke: VectorStroke): VectorStroke {
        if (!ed.vecAutoAnchor || stroke.fill != 0) return stroke
        val idx = l.indexAt(cut.time)
        val cel = l.cels.getOrNull(idx)
        val blocked = AutoAnchors.usedNumbers(cel?.vectorStrokes() ?: emptyList())
        return AutoAnchors.assignFresh(stroke, blocked + (if (cel != null) AutoAnchors.neighbourNumbers(l, idx) else emptySet()), ed.vecTipSens)
    }
    /** After [VectorMerge.attach]: the part of the new stroke that stayed a line of its own (crossing / T junction) gets its anchors now; a stroke that joined a line gets none, so the line keeps its own numbers. */
    fun vecFreshAfterMerge(l: Layer, merged: List<VectorStroke>, newId: String, wasClosed: Boolean = true): List<VectorStroke> {
        if (!ed.vecAutoAnchor) return merged
        val idx = l.indexAt(cut.time)
        val used = AutoAnchors.usedNumbers(merged) + AutoAnchors.neighbourNumbers(l, idx)
        return merged.map { s ->
            if (!anchorGhostTrace[0] && s.id == newId && s.fill == 0 && s.points.none { it.anchor > 0 }) {
                // [wasClosed] false and now closed: the new line closed onto its own start (self-merge). It keeps both end points where they were drawn, so it is numbered and traced as the
                // open line it was (ends first, then tips) and closed again; a ring that was a closed shape from the start still goes through the ring rules.
                val selfClosed = s.isClosed && !wasClosed
                val base = if (selfClosed) s.copy(isClosed = false) else s
                // A trace that Merge closed into a ring follows a closed ghost line like any other trace
                val gh = anchorGhostRef[0]
                val ringTrace = if (base.isClosed && gh != null) { // a closed ghost, or an open one that the trace closes
                    val blocked = AutoAnchors.usedNumbers(merged.filter { it.id != newId })
                    val gp = gh.first
                    val ghostNums = if (gp.isClosed && !AutoAnchors.seamEnds(gp)) gp.points.map { it.anchor }.filter { it > 0 } else listOf(gp.points.first().anchor, gp.points.last().anchor).filter { it > 0 }
                    if (ghostNums.isNotEmpty() && ghostNums.none { it in blocked }) AutoAnchors.inherit(base, gp, blocked, ed.vecTipSens) else null
                } else null
                val numbered = if (ringTrace != null) { anchorGhostTrace[0] = true; ed.vecGhostPin = gh!!.first.id; ed.vecGhostPinKey = gh.second; ringTrace }
                    else AutoAnchors.assignFresh(base, used, ed.vecTipSens)
                if (selfClosed) numbered.copy(isClosed = true) else numbered
            } else s
        }
    }
    /** [deferFresh]: the caller will try to merge the stroke into other lines; it then gets no anchors of its own here (see [anchorDeferred]) so a line drawn in several strokes keeps its first numbers. */
    fun vecAnchorNew(l: Layer, stroke: VectorStroke, deferFresh: Boolean = false): VectorStroke {
        anchorDeferred[0] = false
        anchorGhostTrace[0] = false
        anchorGhostRef[0] = null
        if (!ed.vecAutoAnchor || stroke.fill != 0) return stroke
        val idx = l.indexAt(cut.time)
        val cel = l.cels.getOrNull(idx)
        val current = cel?.vectorStrokes() ?: emptyList()
        val blocked = AutoAnchors.usedNumbers(current)
        if (ed.vecGhost && cel != null) {
            val refCel = LineGhost.reference(l, idx, ed.vecGhostNext, ed)
            if (refCel != null) {
                val px = 1f / vecZoom()
                val allGhost = LineGhost.lines(refCel)
                val open = LineGhost.unclaimed(allGhost, current, px)
                val gKey = LineGhost.key(l, idx)
                var ghost = LineGhost.shown(allGhost, open, ed, gKey) // the shimmering line IS the one being traced, wherever the new line is drawn; it stays shown afterwards
                if (ghost != null) {
                    // the ghost line is made ready to be followed: ends and apex tips (a ghost with only automatic ends from an older version gets its tips)
                    val refStrokes = refCel.vectorStrokes()
                    val ri = l.cels.indexOfFirst { it === refCel }
                    val gTaken = AutoAnchors.usedNumbers(refStrokes) + blocked + (if (ri >= 0) AutoAnchors.neighbourNumbers(l, ri) else emptySet())
                    val anchored = AutoAnchors.completeGhost(ghost, gTaken, ed.vecTipSens)
                    if (anchored !== ghost && ri >= 0) { val gid = ghost.id; replaceVectorCel(cut, l, ri, refStrokes.map { if (it.id == gid) anchored else it }); ghost = anchored }
                    anchorGhostRef[0] = ghost to gKey
                    val gPts = ghost.points; val gClosed = ghost.isClosed && !AutoAnchors.seamEnds(ghost) // a closed line that kept its two ends counts by its ends, like an open one
                    val ghostNums = gPts.withIndex().filter { it.value.anchor > 0 && (gClosed || it.index == 0 || it.index == gPts.lastIndex) }.map { it.value.anchor } // the ghost's end numbers (a closed ghost: all its numbers)
                    if (ghostNums.isNotEmpty() && ghostNums.any { it in blocked }) {
                        anchorGhostTrace[0] = true
                        ed.vecGhostPin = ghost.id; ed.vecGhostPinKey = gKey
                        return stroke // this ghost line's correspondence anchors already belong to an earlier traced piece; do not invent 3/4-style replacements
                    }
                    val traced = AutoAnchors.inherit(stroke, ghost, blocked, ed.vecTipSens)
                    if (traced != null) {
                        anchorGhostTrace[0] = true
                        ed.vecGhostPin = ghost.id; ed.vecGhostPinKey = gKey
                        // Ghost correspondence points are hard fit boundaries: reuse the ordinary
                        // vector-brush curve fitter between them, never simplify through an anchor.
                        val eps = (0.8f / vecZoom()).coerceIn(0.25f, 3f)
                        return VectorSimplify.fitAnchored(traced, eps, traced.baseWidth)
                    }
                }
            }
        }
        if (deferFresh) { anchorDeferred[0] = true; return stroke }
        return vecFreshAnchors(l, stroke)
    }

    /** After a line was merged into others: its automatic anchors follow the new shape. */
    fun vecRefreshAnchors(l: Layer, idx: Int, strokes: List<VectorStroke>): List<VectorStroke> {
        if (!ed.vecAutoAnchor) return strokes
        // Only lines that really changed (a new line, or an old line whose end moved because another line was joined to it) are renumbered. Lines that merely cross or touch
        // another one only got a shared vertex: their numbers are not touched. Every open line keeps an anchor on each of its two end points.
        val before = l.cels.getOrNull(idx)?.vectorStrokes() ?: emptyList()
        val old = before.associateBy { it.id }
        val ids = strokes.filter { s ->
            val o = old[s.id]
            o == null || o.isClosed != s.isClosed || o.points.size < 2 || s.points.size < 2 ||
                o.points.first().x != s.points.first().x || o.points.first().y != s.points.first().y ||
                o.points.last().x != s.points.last().x || o.points.last().y != s.points.last().y
        }.map { it.id }.toSet()
        val avoid = AutoAnchors.neighbourNumbers(l, idx)
        return AutoAnchors.ensureEnds(AutoAnchors.refresh(strokes, avoid, ed.vecTipSens, ids, AutoAnchors.referenceLines(l, idx, ed.vecGhostNext, ed)), avoid, ids)
    }
    /** A stroke that is not merged keeps the anchors it has and gets the missing ones on its two end points. */
    fun vecWithEnds(l: Layer, stroke: VectorStroke): VectorStroke {
        if (!ed.vecAutoAnchor) return stroke
        val idx = l.indexAt(cut.time)
        val cel = l.cels.getOrNull(idx)
        return AutoAnchors.withEnds(stroke, AutoAnchors.usedNumbers(cel?.vectorStrokes() ?: emptyList()) + (if (cel != null) AutoAnchors.neighbourNumbers(l, idx) else emptySet()))
    }

    /** Line / circle / ellipse: pressure goes through the selected brush's size and opacity curves. */
    fun commitShape(pts: List<Offset>, pAvg: Float) {
        val l = cut.drawLayer?.takeIf { !it.locked && it.visible } ?: return
        val br = ed.brushes[ed.brushIdx]
        val w = curveEval(br.sizeCurve, pAvg).coerceAtLeast(0.03f); val a = curveEval(br.opacityCurve, pAvg).coerceIn(0f, 1f)
        if (l.vector) { // vector layer: the shape becomes an editable vector stroke (closed when it ends where it began)
            if (pts.size < 2) return
            var vp = pts.map { VectorPoint(it.x, it.y, w.coerceIn(0.1f, 1f)) }
            val closed = vp.size > 3 && hypot(vp.first().x - vp.last().x, vp.first().y - vp.last().y) < 1e-3f
            if (closed) vp = vp.dropLast(1)
            val src = if (closed) vp + vp.first() else vp
            var simple = VectorSimplify.fit(src, 0.35f)
            if (closed) simple = simple.dropLast(1)
            var shape = vecAnchorNew(l, VectorStroke(newStrokeId(), simple, ed.color.toArgb() or (0xFF shl 24), br.size, br.id.toString(), closed, a.coerceIn(0.05f, 1f), br.aa).withBrush(br), deferFresh = ed.vecMerge && !closed)
            val shapeDeferred = anchorDeferred[0]
            if (ed.vecMerge && !closed) { // Line / shapes tool with Merge on: an end that touches another line joins it (same rules and sharp joins as the Brush tool)
                val idx = l.indexAt(cut.time)
                val cel = l.cels.getOrNull(idx)
                if (cel != null) {
                    val merged = VectorMerge.attach(cel.vectorStrokes(), shape, max(ed.vecMergeRadius / vecZoom(), br.size * 0.5f), ed.vecMergeSharp)
                    if (merged != null) { replaceVectorCel(cut, l, idx, vecRefreshAnchors(l, idx, vecFreshAfterMerge(l, merged, shape.id, shape.isClosed))); return }
                }
            }
            shape = when { anchorGhostTrace[0] -> shape; shapeDeferred -> vecFreshAnchors(l, shape); else -> vecWithEnds(l, shape) } // a ghost trace keeps its reference correspondence even when it remains a separate stroke
            commitOp(l, shape.toInk())
            return
        }
        val p = FloatArray(pts.size * 4)
        pts.forEachIndexed { i, o -> p[i * 4] = o.x; p[i * 4 + 1] = o.y; p[i * 4 + 2] = w; p[i * 4 + 3] = a }
        commitOp(l, Ink(p, ed.color.toArgb(), br, 0, paintClip(), null, null, newInkSeed(br)))
    }

    fun minorFrom(e: Ell, d: Offset): Ell { val rel = d - e.c; return e.copy(ry = max(2f, abs(-sin(e.a) * rel.x + cos(e.a) * rel.y))) }

    /** Wand / fill: threshold from the cached bottleneck map (cheap), expand / reduce, then refresh the preview bitmap in place. */
    // ----- selection: add / erase, stroke-aware refinement, auto fill -----
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun selOpOf(ev: MotionEvent): Int {
        val m = ev.metaState
        return when {
            m and android.view.KeyEvent.META_SHIFT_ON != 0 -> 1
            m and (android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_CTRL_ON) != 0 -> 2
            else -> ed.selOp
        }
    }

    /** A rectangle / lasso / point-based / wand selection is finished: replace, add or erase, then (rectangle, lasso, point) follow the strokes and auto fill. */
    fun finishSelection(raw: Sel, op: Int, refine: Boolean) {
        val old = ed.sel
        val quick = combineSel(old, raw, op, cut)
        ed.sel = quick
        if (quick == null) return
        val strokes = if (refine && ed.selSmart) cut.drawLayer?.celAt(cut.time)?.strokes?.takeIf { it.isNotEmpty() } else null
        val set = strokes?.let { TileCache.getForDraw(it) }
        if (set != null) {
            Thread({
                val refined = runCatching { strokeAwareSel(raw, set) }.getOrDefault(raw)
                val fin = if (refined === raw) quick else combineSel(old, refined, op, cut)
                mainHandler.post {
                    if (ed.sel === quick && ed.xform == null) { ed.sel = fin; if (fin != null && ed.selAutoFill) fillSelectionNow(ed, cut) }
                }
            }, "selection-refine").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY }.start()
        } else if (ed.selAutoFill) fillSelectionNow(ed, cut)
    }

    // ----- Warp / Puppet Warp of the selection transform -----
    fun toPre(x: Xform, d: Offset): Offset { val inv = Matrix(); return if (x.mat.invert(inv)) inv.mapOf(d) else d }
    fun preToScr(x: Xform, p: Offset): Offset = toScr(x.mat.mapOf(p))

    fun wpDown(x: Xform, ev: MotionEvent, d: Offset) {
        st.kind = 13; st.wpNode = -1; st.wpPin = null; st.wpNew = false; st.wpStart = null
        val sp = Offset(ev.x, ev.y); val pre = toPre(x, d)
        val m = x.mesh ?: return
        if (x.mode == 1) {
            var best = -1; var bd = 36f
            for (j in 0..m.rows) for (i in 0..m.cols) {
                val o = m.idx(i, j)
                val dd = (preToScr(x, Offset(m.verts[o], m.verts[o + 1])) - sp).getDistance()
                if (dd < bd) { bd = dd; best = j * (m.cols + 1) + i }
            }
            if (best >= 0) { st.wpNode = best; st.wpStart = m.copy(); st.wpD0 = pre }
        } else {
            var hit: Pin? = null; var bd = 36f
            for (p in x.pins) { val dd = (preToScr(x, Offset(p.qx, p.qy)) - sp).getDistance(); if (dd < bd) { bd = dd; hit = p } }
            if (hit == null) {
                // a new pin sits on the nearest mesh point: its rest position is that point of the untouched mesh
                val base = x.puppetBase ?: return
                var bi = 0; var bdist = Float.MAX_VALUE
                for (k in 0 until (m.verts.size / 2)) {
                    val dx = m.verts[k * 2] - pre.x; val dy = m.verts[k * 2 + 1] - pre.y
                    val dd = dx * dx + dy * dy
                    if (dd < bdist) { bdist = dd; bi = k }
                }
                hit = Pin(base.verts[bi * 2], base.verts[bi * 2 + 1], m.verts[bi * 2], m.verts[bi * 2 + 1], x.pinRadius)
                x.pins.add(hit); st.wpNew = true; x.applyPins()
            }
            x.pinSel = x.pins.indexOf(hit) // the touched pin is the selected one: the Radius control works on it
            st.wpPin = hit; st.wpD0 = pre; st.wpQ0 = Offset(hit.qx, hit.qy)
        }
    }

    fun wpMove(d: Offset) {
        val x = ed.xform ?: return
        val pre = toPre(x, d)
        if (x.mode == 1) {
            val ni = st.wpNode; val s0 = st.wpStart ?: return
            if (ni < 0) return
            val c1 = s0.cols + 1; val delta = pre - st.wpD0
            x.mesh = nudgedMesh(s0, ni % c1, ni / c1, delta.x, delta.y, x.strength); x.rev++
        } else st.wpPin?.let { p -> val q = st.wpQ0 + (pre - st.wpD0); p.qx = q.x; p.qy = q.y; x.applyPins() }
    }

    fun wpUp() {
        val x = ed.xform ?: return
        if (x.mode == 2 && !st.wpNew && !st.moved) st.wpPin?.let { x.pins.remove(it); x.pinSel = -1; x.applyPins() } // tapping an existing pin removes it
        st.wpPin = null; st.wpStart = null
    }

    fun recompute() {
        val lv = st.lvl ?: return; val mask = st.mask ?: return; val ws = st.work ?: return
        val w = st.ww; val h = st.wh; val n = w * h
        val userGap = st.gapNow.coerceIn(0, MAX_GAP_FILL)
        val auto = st.tl == 3 && ed.fillAuto && !st.gapEdited
        val level = tolToLevel(WAND_FILL_TOL) // fixed colour tolerance; the horizontal drag only sets the gap fill
        // This exact mask is previewed and committed. Fill looks for the gap that keeps the fill inside the outline by itself (once per tap).
        val used = if (auto && st.autoGap < 0) smartFlood(lv, w, h, st.sx, st.sy, level, userGap, true, mask, ws).also { st.autoGap = it }
        else smartFlood(lv, w, h, st.sx, st.sy, level, if (auto) st.autoGap else userGap, false, mask, ws)
        if (st.grow != 0) {
            morph(mask, ws.region, w, h, st.grow)
            System.arraycopy(ws.region, 0, mask, 0, n)
        }
        val buf = ws.queue
        val bmp = st.bmp ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { st.bmp = it }
        for (i in 0 until n) buf[i] = if (mask[i]) 0x553B82F6 else 0
        bmp.setPixels(buf, 0, w, 0, 0, w, h)
        liveOn = true; thr = used
    }

    /** Scrubbed past the last frame: the next stroke creates a new frame with the previous frame's timing. */
    fun materialize(l: Layer) {
        if (!ed.newFrameArmed || l.kind != 0) return
        ed.newFrameArmed = false
        cut.record { val last = l.cels.lastOrNull(); l.cels.add(Cel(last?.dur ?: 1)); cut.time = l.startOf(l.cels.size - 1) }
    }

    fun pDown(ev: MotionEvent, d: Offset, p: Persp) {
        val z = fit * ed.vScale
        st.kind = 10; st.drag = 0; st.vi = -1; st.snap = cut.snap()
        if (ed.vpSel in p.vps.indices) {
            val tp = toScr(p.pos(p.vps[ed.vpSel])) + Offset(40f, -40f)
            if ((Offset(ev.x, ev.y) - tp).getDistance() < 28f) { cut.record { p.vps.removeAt(ed.vpSel) }; ed.vpSel = -1; st.kind = 0; return }
        }
        val vi = p.vps.indexOfFirst { (p.pos(it) - d).getDistance() * z < 26f }
        if (vi >= 0) { st.vi = vi; activePerspectiveGuide = p.vps[vi].kind; return }
        val hDistance = p.distanceToGuide(0, d); val vDistance = p.distanceToGuide(1, d)
        val kind = if (hDistance <= vDistance && hDistance * z < 24f) 0 else if (vDistance * z < 24f) 1 else -1
        if (kind >= 0) {
            activePerspectiveGuide = kind
            st.drag = kind + 1; st.guideOrigin0 = p.origin; st.guideDown0 = d
            st.guideRotation0 = p.guide(kind).rotation; st.guidePivot0 = p.pivot(kind, ed.vpSel)
            st.guideVpPositions0 = p.vps.indices.filter { p.vps[it].kind == kind }.map { it to p.pos(p.vps[it]) }
            val q = d - st.guidePivot0; st.guideDownAngle = atan2(q.y, q.x) * 180f / PI.toFloat()
        }
    }

    fun screenDeltaToDoc(dx: Float, dy: Float): Offset {
        val q = rot(Offset(dx, dy), -ed.vRot)
        return Offset(q.x * sgx, q.y * sgy) / (fit * ed.vScale).coerceAtLeast(0.0001f)
    }

    // ---------- vector layers ----------

    fun vecAdd(d: Offset, width: Float, force: Boolean = false) {
        val last = vecPts.lastOrNull()
        if (!force && last != null && hypot(d.x - last.x, d.y - last.y) < 1.5f / vecZoom()) return
        if (last != null) vecMergeLen[0] += hypot(d.x - last.x, d.y - last.y)
        vecPts.add(VectorPoint(d.x, d.y, width.coerceIn(0.03f, 1f)))
        if (ed.vecMerge) {
            val reach = max(ed.vecMergeRadius / vecZoom(), ed.brushes[ed.brushIdx].size * 0.5f)
            val hit = VectorMerge.snapTarget(vecMergeLines[0], d.x, d.y, reach)
            val first = vecPts.size == 1
            var hp = hit?.let { Offset(it[0], it[1]) }
            if (hp == null && !first && vecPts.size >= 4 && vecMergeLen[0] > 4f * reach) { // the end came back to its own start
                val s0 = vecPts.first()
                if (hypot(d.x - s0.x, d.y - s0.y) <= reach) hp = Offset(s0.x, s0.y)
            }
            if (first) vecMergeStart = hp else vecMergeEnd = hp
        }
        vecLive++ // the live stroke is drawn by the draw pass from vecPts (no per-point list copy)
    }

    /** One raw pen sample: through the selected brush's stabilizer, then pressure -> size curve (and start taper) -> a point. */
    fun vecSample(x: Float, y: Float, pressure: Float, t: Long) {
        val vp = vecPen ?: return
        if (vp.raw == 0) vp.ps = pressure
        vp.raw++; vp.rx = x; vp.ry = y; vp.rp = pressure
        if (vp.stab.push(x, y, t)) vecAdd(Offset(vp.stab.ox, vp.stab.oy), vp.width(vp.stab.ox, vp.stab.oy, pressure))
    }

    fun vecFeed(ev: MotionEvent) {
        for (h in 0 until ev.historySize) { val q = toDoc(ev.getHistoricalX(h), ev.getHistoricalY(h)); vecSample(q.x, q.y, ev.getHistoricalPressure(h), ev.getHistoricalEventTime(h)) }
        val q = toDoc(ev.x, ev.y); vecSample(q.x, q.y, ev.pressure, ev.eventTime)
    }

    /** Finishes a drawn vector stroke: the stabilizer catches up to the pen, end taper, pressure-aware simplification, optional Merge, one undoable commit. */
    fun vecCommitStroke(l: Layer) {
        val vp = vecPen
        if (vp != null && vp.raw > 0) {
            val b = vp.brush
            if (b.stabMethod == 5 && b.stab > 0) { var g = 0; while (g++ < 120 && vp.stab.settleStep(vp.rx, vp.ry)) vecAdd(Offset(vp.stab.ox, vp.stab.oy), vp.width(vp.stab.ox, vp.stab.oy, vp.rp)) } // the spring finishes its swing
            if (b.stabMethod != 0 && b.stab > 0 && hypot(vp.rx - vp.lx, vp.ry - vp.ly) > 0.5f) vecAdd(Offset(vp.rx, vp.ry), vp.width(vp.rx, vp.ry, vp.rp), force = true) // catch up to the pen
        }
        if (vecPts.isEmpty()) return
        val br = vp?.brush ?: ed.brushes[ed.brushIdx]
        val dense = vp?.taperEnd(vecPts.toList()) ?: vecPts.toList()
        val eps = (0.8f / vecZoom()).coerceIn(0.25f, 3f)
        val pts = VectorSimplify.fit(dense, eps, br.size)
        var stroke = vecAnchorNew(l, VectorStroke(newStrokeId(), pts, ed.color.toArgb() or (0xFF shl 24), br.size, br.id.toString(), false, br.opacity.coerceIn(0.05f, 1f), br.aa).withBrush(br), deferFresh = ed.vecMerge)
        val strokeDeferred = anchorDeferred[0]
        if (ed.vecMerge) {
            val idx = l.indexAt(cut.time)
            val cel = l.cels.getOrNull(idx)
            if (cel != null) {
                val merged = VectorMerge.attach(cel.vectorStrokes(), stroke, max(ed.vecMergeRadius / vecZoom(), br.size * 0.5f), ed.vecMergeSharp)
                if (merged != null) { replaceVectorCel(cut, l, idx, vecRefreshAnchors(l, idx, vecFreshAfterMerge(l, merged, stroke.id, stroke.isClosed))); return }
            }
        }
        stroke = when { anchorGhostTrace[0] -> stroke; strokeDeferred -> vecFreshAnchors(l, stroke); else -> vecWithEnds(l, stroke) } // a ghost trace keeps its reference correspondence even when it remains a separate stroke
        commitOp(l, stroke.toInk())
    }

    /** Puts [strokes] into the cel being edited (in place; the cel was swapped for a fresh one when the drag began) and refreshes its tiles. */
    fun vecApplyLive(cel: Cel, strokes: List<VectorStroke>) {
        cel.strokes.clear(); cel.vecRev++
        strokes.forEach { cel.strokes.add(it.toInk()) }
        TileCache.invalidate(cel.strokes)
        TileCache.get(cel.strokes)
    }

    /** The current vector scope; drops the selection when the scope (layers / keyframes) is not the one it was made on. */
    fun vecEnsureScope(): VecScope? {
        val sc = vecScope(cut, ed.refAll) ?: return null
        if (!sc.sameCels(ed.vecSelCels)) { ed.vecSel = emptySet(); ed.vecSelCels = sc.cels }
        return sc
    }

    /** First real movement of a drag: every cel of the scope is swapped for a fresh one (undo keeps the old ones) and edited in place from here on. */
    fun vecBeginLive(sc: VecScope): Boolean {
        vecBefore = cut.snap()
        val lives = ArrayList<Cel>()
        for (i in sc.cels.indices) {
            val l = sc.layers[i]; val idx = l.cels.indexOfFirst { it === sc.cels[i] }
            if (idx < 0) return false
            swapVectorCel(l, sc.cels[i], sc.perCel[i])
            lives.add(l.cels[idx])
        }
        cut.invalidateStructureCaches()
        vecLives = lives; ed.vecSelCels = lives
        vecLiveCels = lives.mapIndexed { i, c -> VecLiveCel(c, sc.perCel[i]) }
        return true
    }

    /** Applies an edit now, or (when the last one was applied less than 14 ms ago) keeps only the newest one for later: pen events arrive faster than frames. */
    fun vecThrottled(edit: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - vecLastApply < 14L) { vecPending = edit; return }
        vecLastApply = now; vecPending = null; edit()
    }

    /** Release of a drag: the skipped newest edit is applied, then the incremental editors are closed (unpinned, tiles put in order). */
    fun vecFinishLive() {
        vecPending?.let { it() }; vecPending = null
        vecLiveCels?.forEach { it.finish() }; vecLiveCels = null
    }

    fun vecApplyAll(sc: VecScope, edited: List<VectorStroke>) {
        val lives = vecLives ?: return
        val parts = sc.split(edited)
        val inc = vecLiveCels
        lives.forEachIndexed { i, cel -> if (i < parts.size) { if (inc != null && i < inc.size) inc[i].apply(parts[i]) else vecApplyLive(cel, parts[i]) } }
    }

    fun vecEditDown(l: Layer, d: Offset) {
        val sc = vecEnsureScope() ?: return
        val hit = VectorEdit.hitPoint(sc.strokes, d.x, d.y, 24f / vecZoom())
        vecFinishLive()
        st.kind = 16; vecLives = null; vecOrig = null; vecBefore = null; vecSc = sc
        if (hit != null) {
            if (hit !in ed.vecSel) ed.vecSel = setOf(hit)
            vecDrag = 1; vecOrig = sc.strokes
        } else { vecDrag = 2; vecLasso = listOf(d) }
    }

    fun vecEditMove(ev: MotionEvent, d: Offset) {
        if (vecDrag == 2) { vecLasso = vecLasso + d; return }
        val orig = vecOrig ?: return
        val sc = vecSc ?: return
        if (!st.farScr) return
        if (vecLives == null && !vecBeginLive(sc)) return
        val raise = ed.vecRaise; val dy = (st.downScr.y - ev.y) / vecZoom() * 0.5f; val mx = d.x - st.start.x; val my = d.y - st.start.y
        vecThrottled { vecApplyAll(sc, if (raise) VectorEdit.elevate(orig, ed.vecSel, dy, ed.vecFalloff) else VectorEdit.move(orig, ed.vecSel, mx, my, ed.vecFalloff)) }
    }

    fun vecEditUp(l: Layer, d: Offset) {
        if (vecDrag == 2) {
            val sc = vecEnsureScope()
            if (sc != null && st.farScr && vecLasso.size >= 3) ed.vecSel = VectorEdit.pointsInPolygon(sc.strokes, vecLasso)
            else ed.vecSel = emptySet() // a tap on empty space clears the selection
            vecLasso = emptyList()
        } else if (vecDrag == 1) {
            val before = vecBefore
            vecFinishLive()
            if (vecLives != null && before != null) { cut.pushStruct(before) }
            else if (!st.farScr) { // a tap on a point selects just that point
                val sc = vecEnsureScope()
                if (sc != null) VectorEdit.hitPoint(sc.strokes, st.start.x, st.start.y, 24f / vecZoom())?.let { ed.vecSel = setOf(it) }
            }
        }
        vecDrag = 0; vecOrig = null; vecLives = null; vecBefore = null; vecSc = null
    }

    // ---------- point-edit brushes (Shift / Smoothen / Distort) ----------
    fun vecBrushStep(d: Offset) {
        val sc = vecSc ?: return
        val cur = vecBrushCur ?: return
        val delta = d - vecBrushLast
        vecBrushLast = d
        vecBrushPhase++
        val out = VectorBrush.apply(cur, ed.reshapeMode + 1, d.x, d.y, ed.vecBrushRadius, ed.vecBrushStrength, delta.x, delta.y, vecBrushPhase)
        if (out === cur) return
        if (vecLives == null && !vecBeginLive(sc)) return
        vecBrushCur = out
        vecApplyAll(sc, out)
    }

    fun vecBrushDown(d: Offset) {
        val sc = vecEnsureScope() ?: return
        st.kind = 24; vecSc = sc; vecBrushCur = sc.strokes; vecBrushLast = d; vecBrushPhase = 0
        vecLives = null; vecOrig = null; vecBefore = null
        ed.vecSel = emptySet() // point numbers change when the brush adds points
        if (ed.reshapeMode != 0) vecBrushStep(d)
    }

    fun vecBrushUp() {
        vecFinishLive()
        val before = vecBefore
        if (vecLives != null && before != null) cut.pushStruct(before)
        vecBrushCur = null; vecOrig = null; vecLives = null; vecBefore = null; vecSc = null
    }

    // ---------- vector eraser: delete a line up to the next crossing ----------
    fun vecEraseUp(l: Layer) {
        val path = vecCutPath
        vecCutPath = emptyList()
        if (path.isEmpty()) return
        val radius = max(8f / vecZoom(), ed.erasers[ed.eraserIdx].size * 0.5f)
        val step = max(1f, radius * 0.5f)
        val samples = ArrayList<Offset>()
        samples.add(path[0])
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val k = (len / step).toInt()
            for (j in 1..k) samples.add(Offset(a.x + (b.x - a.x) * j / (k + 1f), a.y + (b.y - a.y) * j / (k + 1f)))
            samples.add(b)
            if (samples.size > 600) break
        }
        // "All layers" (the toggle of the selection tool) lets the eraser work on every visible, unlocked vector layer at once; otherwise only on the layer being drawn
        val targets = if (ed.refAll) cut.layers.filter { it.vector && it.visible && !it.locked } else listOf(l)
        val changes = ArrayList<Triple<Layer, Cel, List<VectorStroke>>>()
        for (tl in targets) {
            val cel = tl.cels.getOrNull(tl.indexAt(cut.time)) ?: continue
            var strokes = cel.vectorStrokes()
            var any = false
            for (q in samples) (if (ed.vecErasePoints) VectorEraser.erasePointsAt(strokes, q.x, q.y, radius, ed.vecAutoAnchor) else VectorEraser.eraseAt(strokes, q.x, q.y, radius, ed.vecAutoAnchor))?.let { strokes = it; any = true }
            // the lines that were cut get anchors on their new ends, and keep the ones they had on the ends that stayed
            if (any) changes.add(Triple(tl, cel, vecRefreshAnchors(tl, tl.indexAt(cut.time), strokes)))
        }
        if (changes.isEmpty()) return
        ed.vecSel = emptySet()
        cut.record { for ((tl, cel, strokes) in changes) swapVectorCel(tl, cel, strokes) } // one undo step for all layers
    }

    fun vecCutUp(l: Layer) {
        val path = vecCutPath
        vecCutPath = emptyList()
        val idx = l.indexAt(cut.time)
        val cel = l.cels.getOrNull(idx) ?: return
        var strokes = cel.vectorStrokes()
        var any = false
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            if (hypot(b.x - a.x, b.y - a.y) < 1e-3f) continue
            VectorEdit.cut(strokes, a.x, a.y, b.x, b.y)?.let { strokes = it; any = true }
        }
        if (!any) return
        ed.vecSel = emptySet()
        replaceVectorCel(cut, l, idx, strokes)
    }

    /** Document-space box of the selected vector points (all layers of the scope), or null. */
    fun vecSelBox(): List<Offset>? {
        val sc = vecScope(cut, ed.refAll) ?: return null
        if (!sc.sameCels(ed.vecSelCels) || ed.vecSel.isEmpty()) return null
        val b = VectorEdit.selectionBounds(sc.strokes, ed.vecSel) ?: return null
        return rectBox(RectF(b[0] - 4f, b[1] - 4f, b[2] + 4f, b[3] + 4f))
    }

    fun vecSelect(picked: Set<Long>, op: Int) {
        ed.vecSel = when (op) { 1 -> ed.vecSel + picked; 2 -> ed.vecSel - picked; else -> picked }
    }

    /** Selection tool on vector layers: handle drag of the selection box, else start a selection (rectangle / lasso / polygon / wand = whole stroke). */
    fun vecSelectDown(l: Layer, ev: MotionEvent, d: Offset) {
        val sc = vecEnsureScope() ?: return
        val op = selOpOf(ev)
        val box = vecSelBox()
        if (box != null && op == 0) {
            val h = hitHandle(box.map { toScr(it) }, Offset(ev.x, ev.y), 26f, 86f)
            if (h != H_NONE) {
                st.kind = 18; vecXfHandle = h; vecXfBox = box; vecXfD0 = d
                vecOrig = sc.strokes; vecSc = sc; vecLives = null; vecBefore = null
                return
            }
        }
        st.selOp = op
        if (ed.selWand) { st.kind = 19; return }
        when (ed.selMode) {
            0, 1 -> { st.kind = 20; preSel = listOf(d) }
            else -> st.kind = 21
        }
    }

    fun vecXfMove(d: Offset) {
        val orig = vecOrig ?: return
        val sc = vecSc ?: return
        if (!st.farScr) return
        if (vecLives == null && !vecBeginLive(sc)) return
        val op = gizmoOp(vecXfHandle, vecXfBox, vecXfD0, d, ed.xfLock)
        val xm = op.m
        if (vecXfHandle == H_ROT && ed.rotationSnap) { // rotation magnet: absolute 30-degree steps, like the other transforms
            val values = FloatArray(9); xm.getValues(values)
            val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
            val center = (vecXfBox[0] + vecXfBox[2]) / 2f
            xm.postRotate((angle / 30f).roundToInt() * 30f - angle, center.x, center.y)
        }
        val sel = ed.vecSel; val lock = ed.vecLockWeight
        vecThrottled { vecApplyAll(sc, VectorEdit.transform(orig, sel, xm, lock)) }
    }

    fun vecXfUp() {
        vecFinishLive()
        val before = vecBefore
        if (vecLives != null && before != null) cut.pushStruct(before)
        vecXfHandle = H_NONE; vecOrig = null; vecLives = null; vecBefore = null; vecSc = null
    }

    fun vecSelectUp(l: Layer, ev: MotionEvent, d: Offset) {
        val sc = vecEnsureScope() ?: return
        val strokes = sc.strokes
        val op = st.selOp
        when (st.kind) {
            19 -> { // wand: the stroke under the pen; a tap on nothing clears
                val hit = VectorEdit.strokeAt(strokes, d.x, d.y, 18f / vecZoom())
                if (hit.isEmpty() && op == 0) ed.vecSel = emptySet() else vecSelect(hit, op)
            }
            20 -> {
                if (st.moved && preSel.size >= 3) vecSelect(VectorEdit.strokesTouching(strokes, preSel), op)
                else if (op == 0) ed.vecSel = emptySet()
                preSel = emptyList()
            }
            21 -> {
                val first = poly.firstOrNull()
                if (first != null && poly.size >= 3 && (d - first).getDistance() * vecZoom() < 28f) {
                    vecSelect(VectorEdit.strokesTouching(strokes, poly.toList()), op); poly.clear()
                } else poly.add(d)
            }
        }
    }

    /** The point of [strokes] nearest to [d] within [reach], as a joint spot. [arcs] (one array per stroke, see [Warped]) gives the arc position each point has on the line it was warped from; without it the position along the drawn line is used. */
    fun nearestGuideJointPoint(strokes: List<VectorStroke>, d: Offset, reach: Float, arcs: List<FloatArray?>? = null): VectorGuideJoint? {
        var best: VectorGuideJoint? = null
        var bestD = reach
        for ((si, stroke) in strokes.withIndex()) {
            if (stroke.points.size < 2) continue
            val arc = arcs?.getOrNull(si)?.takeIf { it.size == stroke.points.size }
            val lengths = FloatArray(stroke.points.size)
            for (i in 1 until stroke.points.size) lengths[i] = lengths[i - 1] + hypot(stroke.points[i].x - stroke.points[i - 1].x, stroke.points[i].y - stroke.points[i - 1].y)
            val count = if (stroke.isClosed && stroke.points.size > 2) stroke.points.size else stroke.points.size - 1
            val closing = if (count == stroke.points.size) hypot(stroke.points.last().x - stroke.points.first().x, stroke.points.last().y - stroke.points.first().y) else 0f
            val total = (lengths.last() + closing).coerceAtLeast(1e-5f)
            for (i in 0 until count) {
                val a = stroke.points[i]; val b = stroke.points[(i + 1) % stroke.points.size]
                val vx = b.x - a.x; val vy = b.y - a.y
                val den = (vx * vx + vy * vy).coerceAtLeast(1e-6f)
                val f = (((d.x - a.x) * vx + (d.y - a.y) * vy) / den).coerceIn(0f, 1f)
                val x = a.x + vx * f; val y = a.y + vy * f
                val dist = hypot(d.x - x, d.y - y)
                if (dist < bestD) {
                    bestD = dist
                    val along = lengths[i] + hypot(vx, vy) * f
                    val u = if (arc != null) arc[i] + ((if (i + 1 < arc.size) arc[i + 1] else 1f) - arc[i]) * f else along / total
                    best = VectorGuideJoint(stroke.id, u.coerceIn(0f, 1f), x, y)
                }
            }
        }
        return best
    }

    /** Selection tool, Displace on: tap a selected line to put a joint on it (or take hold of an existing joint), then drag it; the selection warps like an interpolation with guide joints. */
    fun vecDisplaceDown(l: Layer, d: Offset) {
        val sc = vecScope(cut, ed.refAll) ?: return
        if (ed.vecSel.isEmpty() || !sc.sameCels(ed.vecSelCels)) { android.widget.Toast.makeText(vecCtx, "Displace: select lines or points first, then tap a selected line", android.widget.Toast.LENGTH_SHORT).show(); return }
        val reach = 24f / vecZoom()
        val joints = ed.vecDisplaceJoints
        val near = joints.indices.minByOrNull { hypot(joints[it].x - d.x, joints[it].y - d.y) }
        if (near != null && hypot(joints[near].x - d.x, joints[near].y - d.y) <= reach) { vecDispTapDel = if (near == ed.vecDisplaceSelected) near else -1; vecJointDragged = false; ed.vecDisplaceSelected = near; st.kind = 28; return }
        // Once joints warp the selection, the new joint goes on the PREVIEW (the orange line), not on the line it comes from; the preview's arc positions say where on the original line it belongs
        val shown = vecDisplaceRef[0]
        val picked = ed.vecSel.map { vecKeyStroke(it) }.toSet().sorted().mapNotNull { sc.strokes.getOrNull(it) }
        val hit = (if (joints.isNotEmpty() && !shown.isNullOrEmpty()) nearestGuideJointPoint(shown.map { it.stroke }, d, reach, shown.map { it.arcs }) else nearestGuideJointPoint(picked, d, reach)) ?: return
        ed.vecDisplaceJoints = joints + hit
        ed.vecDisplaceSelected = ed.vecDisplaceJoints.lastIndex
        vecDispTapDel = -1; vecJointDragged = false
        st.kind = 28
    }

    /** The interpolation as it is before any guide joint moves it (Fixed offset, smoothness and per-line positions applied, no manual transform): what a relative joint is measured from. */
    fun vecJointBaseStrokes(source: Cel, target: Cel, cfg: InterpolationConfig, mode: InterpolationMode, chosen: Set<Int>?): List<VectorStroke> {
        val p = ed.inbetweenPlace()
        val basePlace = p?.let { InbetweenPlace(it.fixed, Aff.ID, emptyMap(), it.lockWeight, it.smoothAll, it.smoothLines, it.tLines, fixedAt = it.fixedAt, fixedAtLines = it.fixedAtLines) }
        return VectorInterpolation.interpolateConfigured(source.vectorStrokes(), target.vectorStrokes(), cfg.copy(mode = mode, guideJoints = emptyList()), ed.vecDragT, chosen, false, basePlace)
    }

    /** Where the unmoved line [base] is at the arc position of joint [g]. */
    fun vecJointBaseAt(base: List<VectorStroke>?, g: VectorGuideJoint): Offset? {
        val s = base?.firstOrNull { it.id == g.strokeId } ?: return null
        return polylinePointAt(s.points, s.isClosed, g.u)
    }

    /** The spot of joint [g] in the preview's own space (before its manual transform). */
    fun vecJointSpot(base: List<VectorStroke>?, g: VectorGuideJoint): Offset =
        if (g.rel) vecJointBaseAt(base, g)?.let { Offset(it.x + g.x, it.y + g.y) } ?: Offset(g.x, g.y) else Offset(g.x, g.y)

    /** Interpolation drag: the lines Shift / Select work on: the picked preview lines, else the whole preview. */
    fun vecPreTargets(): List<VectorStroke> {
        val prev = vecPreviewRef[0] ?: return emptyList()
        val picked = prev.filter { it.id in ed.vecPreSel }
        return if (picked.isEmpty()) prev else picked
    }

    /** A transform box (document space) around [strokes]. */
    fun vecPreBox(strokes: List<VectorStroke>): List<Offset>? {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in strokes) for (p in s.points) { x0 = min(x0, p.x); x1 = max(x1, p.x); y0 = min(y0, p.y); y1 = max(y1, p.y) }
        return if (x0 > x1) null else rectBox(RectF(x0 - 4f, y0 - 4f, x1 + 4f, y1 + 4f))
    }

    /** Shift on: take hold of a handle of the preview's box (corners scale, sides stretch, outside the corners rotates, inside moves); a drag that hits no handle moves it. */
    fun vecPlaceDown(ev: MotionEvent, d: Offset) {
        val targets = vecPreTargets()
        val box = vecPreBox(targets)
        var h = H_MOVE
        if (box != null) { val hh = hitHandle(box.map { toScr(it) }, Offset(ev.x, ev.y), 26f, 86f); if (hh != H_NONE) h = hh }
        st.kind = 27; vecPlHandle = h; vecPlD0 = d
        vecPlBox = box ?: rectBox(RectF(d.x - 1f, d.y - 1f, d.x + 1f, d.y + 1f))
        vecPlBaseAll = ed.vecPlaceAll; vecPlBaseLines = ed.vecPlaceLines
        vecPlIds = if (ed.vecPreSel.isNotEmpty() && targets.any { it.id in ed.vecPreSel }) targets.map { it.id }.toSet() else emptySet()
    }

    fun vecPlaceMove(d: Offset) {
        if (!st.farScr) return
        val op = gizmoOp(vecPlHandle, vecPlBox, vecPlD0, d, ed.xfLock)
        val xm = op.m
        if (vecPlHandle == H_ROT && ed.rotationSnap) { // rotation magnet: absolute 30-degree steps, like the other transforms
            val values = FloatArray(9); xm.getValues(values)
            val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
            val center = (vecPlBox[0] + vecPlBox[2]) / 2f
            xm.postRotate((angle / 30f).roundToInt() * 30f - angle, center.x, center.y)
        }
        if (vecPlIds.isEmpty()) {
            // the whole preview: a line's own transform is kept as it is on screen (M o L o G = L' o (M o G) with L' = M o L o M^-1)
            val inv = Matrix(); val ok = xm.invert(inv)
            ed.vecPlaceAll = vecPlBaseAll.after(xm)
            ed.vecPlaceLines = if (!ok) vecPlBaseLines else vecPlBaseLines.mapValues { (_, l) -> Aff.of(inv).then(l).after(xm) }
        } else ed.vecPlaceLines = vecPlBaseLines + vecPlIds.associateWith { (vecPlBaseLines[it] ?: Aff.ID).after(xm) }
    }

    /** Select on: a tap picks the preview line under the pen, a dragged rectangle picks every line it touches; Add / Erase (Shift / Alt keys) work as in the selection tool. A tap on nothing clears. */
    fun vecPreSelectUp(ev: MotionEvent, d: Offset) {
        val prev = vecPreviewRef[0] ?: emptyList()
        val ids: Set<String> = if (st.moved && preSel.size >= 3) VectorEdit.strokesTouching(prev, preSel).mapNotNull { prev.getOrNull(vecKeyStroke(it))?.id }.toSet()
            else nearestGuideJointPoint(prev, d, 24f / vecZoom())?.strokeId?.let { setOf(it) } ?: emptySet()
        ed.vecPreSel = when (selOpOf(ev)) { 1 -> ed.vecPreSel + ids; 2 -> ed.vecPreSel - ids; else -> ids }
        preSel = emptyList()
    }

    fun guideSourceFor(layer: Layer): Pair<Cel, Cel>? {
        if (vecRefSelected(layer, ed, cut.time).isNotEmpty()) return vecDragRefs(layer, ed, cut.time)?.let { it.a to it.b } // reference frames picked: they are the ends (the current frame needs no selection)
        val i = layer.indexAt(cut.time)
        if (i < 1 || i >= layer.cels.size) return null
        return layer.cels[i - 1] to layer.cels[i]
    }

    // ----- Transform / Warp / Puppet Warp of vector lines (Selection tool): the vector counterpart of the raster transform -----
    fun vToPre(x: VecXform, d: Offset): Offset { val inv = Matrix(); return if (x.mat.invert(inv)) inv.mapOf(d) else d }
    fun vPreToScr(x: VecXform, p: Offset): Offset = toScr(x.mat.mapOf(p))

    fun vecXfBeginNow() {
        if (ed.vecXf != null) return
        if (ed.vecMode == 3) { // the interpolation preview (the picked lines, else all of it)
            val prev = vecPreviewRef[0] ?: return
            val picked = ed.vecPreSel.filter { id -> prev.any { it.id == id } }.toSet()
            val keys = HashSet<Long>()
            for ((si, s0) in prev.withIndex()) if (picked.isEmpty() || s0.id in picked) for (pi in s0.points.indices) keys.add(vecKey(si, pi))
            val b = VectorEdit.selectionBounds(prev, keys) ?: return
            vecXfPrev = prev; vecXfOrig = null; vecXfSc = null
            ed.vecXf = VecXform(RectF(b[0] - 4f, b[1] - 4f, b[2] + 4f, b[3] + 4f), keys, picked)
        } else { // the selected lines / points of the layer(s)
            val sc = vecEnsureScope() ?: return
            val sel = ed.vecSel
            if (sel.isEmpty() || !sc.sameCels(ed.vecSelCels)) return
            val b = VectorEdit.selectionBounds(sc.strokes, sel) ?: return
            vecXfSc = sc; vecXfOrig = sc.strokes; vecXfPrev = null
            ed.vecXf = VecXform(RectF(b[0] - 4f, b[1] - 4f, b[2] + 4f, b[3] + 4f), sel, null)
        }
        vecXfCtx[0] = cut.time; vecXfCtx[1] = cut.layerId
    }

    /** Real lines: shows the session's result in the layer while it runs (the first change swaps in live cels, as a handle drag does). The preview is drawn from the session instead. */
    fun vecXfRefresh() {
        val vx = ed.vecXf ?: return
        if (vx.onPreview) return
        val sc = vecXfSc ?: return; val orig = vecXfOrig ?: return
        if (vecLives == null) { if (vx.isIdentity) return; if (!vecBeginLive(sc)) return }
        vecApplyAll(sc, vecDeform(orig, vx.sel, vx.frozen(), ed.vecLockWeight, false))
    }

    fun vecXfEnd() { ed.vecXf = null; vecXfOrig = null; vecXfSc = null; vecXfPrev = null; vecLives = null; vecBefore = null }

    fun vecXfCancelNow() {
        val vx = ed.vecXf ?: return
        val sc = vecXfSc; val orig = vecXfOrig
        if (!vx.onPreview && vecLives != null && sc != null && orig != null) { vecApplyAll(sc, orig); vecFinishLive() }
        vecXfEnd()
    }

    fun vecXfApplyNow() {
        val vx = ed.vecXf ?: return
        val d = vx.frozen()
        if (d.isIdentity) { vecXfCancelNow(); return }
        if (vx.onPreview) {
            // the step joins the preview's placement; the in-between is made with it (and it is undone with Reset on the bar)
            val ids = vx.ids?.takeIf { it.isNotEmpty() }
            val prev = vecXfPrev
            val gridMesh = vx.mesh
            if (gridMesh != null && prev != null) {
                // a warp stays on its lines: record how far each line's points were pushed (along the line), then the box handles' matrix as a separate step
                val meshOnly = VecDeform(vx.rect, gridMesh.copy(), Aff.ID)
                val offs = HashMap<String, LineOffsets>()
                if (!meshOnly.isIdentity) for ((si, s0) in prev.withIndex()) if (s0.points.indices.any { vecKey(si, it) in vx.sel }) offs[s0.id] = lineOffsets(s0, meshOnly)
                var steps = ed.vecPlaceSteps
                if (offs.isNotEmpty()) steps = steps + PreviewWarp(offs.keys.toSet(), null, ed.vecLockWeight, offs)
                if (!vx.mat.isIdentity) steps = steps + PreviewWarp(ids, VecDeform(vx.rect, null, Aff.of(vx.mat)), ed.vecLockWeight)
                ed.vecPlaceSteps = steps
            } else ed.vecPlaceSteps = ed.vecPlaceSteps + PreviewWarp(ids, d, ed.vecLockWeight)
            vecXfEnd(); return
        }
        val sc = vecXfSc; val orig = vecXfOrig
        if (sc == null || orig == null || (vecLives == null && !vecBeginLive(sc))) { vecXfCancelNow(); return }
        val edited = vecDeform(orig, vx.sel, d, ed.vecLockWeight, true) // thinned with the curve-aware fitter
        vecApplyAll(sc, edited)
        vecFinishLive()
        vecBefore?.let { cut.pushStruct(it) }
        val keep = HashSet<Long>() // a line that got another number of points stays selected as a whole
        for (si in vx.sel.map { vecKeyStroke(it) }.toSet()) {
            if (orig.getOrNull(si)?.points?.size == edited.getOrNull(si)?.points?.size) vx.sel.filterTo(keep) { vecKeyStroke(it) == si }
            else edited.getOrNull(si)?.points?.indices?.forEach { keep.add(vecKey(si, it)) }
        }
        ed.vecSel = keep
        vecXfEnd()
    }

    fun vwpDown(x: VecXform, ev: MotionEvent, d: Offset) {
        st.kind = 30; st.wpNode = -1; st.wpPin = null; st.wpNew = false; st.wpStart = null
        val sp = Offset(ev.x, ev.y); val pre = vToPre(x, d)
        val m = x.mesh ?: return
        if (x.mode == 1) {
            var best = -1; var bd = 36f
            for (j in 0..m.rows) for (i in 0..m.cols) {
                val o = m.idx(i, j)
                val dd = (vPreToScr(x, Offset(m.verts[o], m.verts[o + 1])) - sp).getDistance()
                if (dd < bd) { bd = dd; best = j * (m.cols + 1) + i }
            }
            if (best >= 0) { st.wpNode = best; st.wpStart = m.copy(); st.wpD0 = pre }
        } else {
            var hit: Pin? = null; var bd = 36f
            for (p in x.pins) { val dd = (vPreToScr(x, Offset(p.qx, p.qy)) - sp).getDistance(); if (dd < bd) { bd = dd; hit = p } }
            if (hit == null) { // a new pin sits on the nearest mesh point: its rest position is that point of the untouched mesh
                val base = x.puppetBase ?: return
                var bi = 0; var bdist = Float.MAX_VALUE
                for (k in 0 until (m.verts.size / 2)) {
                    val dx = m.verts[k * 2] - pre.x; val dy = m.verts[k * 2 + 1] - pre.y
                    val dd = dx * dx + dy * dy
                    if (dd < bdist) { bdist = dd; bi = k }
                }
                hit = Pin(base.verts[bi * 2], base.verts[bi * 2 + 1], m.verts[bi * 2], m.verts[bi * 2 + 1], x.pinRadius)
                x.pins.add(hit); st.wpNew = true; x.applyPins()
            }
            x.pinSel = x.pins.indexOf(hit)
            st.wpPin = hit; st.wpD0 = pre; st.wpQ0 = Offset(hit.qx, hit.qy)
        }
    }

    fun vwpMove(d: Offset) {
        val x = ed.vecXf ?: return
        val pre = vToPre(x, d)
        if (x.mode == 1) {
            val ni = st.wpNode; val s0 = st.wpStart ?: return
            if (ni < 0) return
            val c1 = s0.cols + 1; val delta = pre - st.wpD0
            x.mesh = nudgedMesh(s0, ni % c1, ni / c1, delta.x, delta.y, x.strength); x.rev++
        } else st.wpPin?.let { p -> val q = st.wpQ0 + (pre - st.wpD0); p.qx = q.x; p.qy = q.y; x.applyPins() }
    }

    fun vwpUp() {
        val x = ed.vecXf ?: return
        if (x.mode == 2 && !st.wpNew && !st.moved) st.wpPin?.let { x.pins.remove(it); x.pinSel = -1; x.applyPins() } // tapping an existing pin removes it
        st.wpPin = null; st.wpStart = null
    }

    /** Guide joints on the interpolation preview (Selection tool > Displace): tap a preview line to add a joint, tap a joint to select it, drag it to bend the line. */
    fun vecJointDown(l: Layer, d: Offset) {
        val pair = guideSourceFor(l) ?: return
        val (source, target) = pair
        val cfg = source.vecCfg ?: InterpolationConfig()
        val mode = if (cfg.mode == InterpolationMode.NONE) InterpolationMode.SAME_STROKE else cfg.mode
        val chosen = if (vecRefSelected(l, ed, cut.time).isNotEmpty()) vecSelectedLinesIn(ed, target)
            else vecSelectedLineIndices(cut, ed, target).takeIf { it.isNotEmpty() }
        val place = ed.inbetweenPlace()
        val preview = VectorInterpolation.interpolateConfigured(source.vectorStrokes(), target.vectorStrokes(), cfg.copy(mode = mode), ed.vecDragT, chosen, false, place)
        // Joints are stored in the preview's own space (before its manual transform), so they stay attached to their line when the preview is moved, scaled or rotated
        val base = vecJointBaseStrokes(source, target, cfg, mode, chosen)
        fun shownAt(g: VectorGuideJoint): Offset = vecJointSpot(base, g).let { (place?.total(g.strokeId) ?: Aff.ID).map(it.x, it.y) }
        val existing = cfg.guideJoints.indices.minByOrNull { i -> (shownAt(cfg.guideJoints[i]) - d).getDistance() }
        val hitIndex = existing?.takeIf { (shownAt(cfg.guideJoints[it]) - d).getDistance() <= 24f / vecZoom() }
        val hitShown = if (hitIndex != null) cfg.guideJoints[hitIndex] else nearestGuideJointPoint(preview, d, 24f / vecZoom())
        if (hitShown != null) {
            val toJoint = (place?.total(hitShown.strokeId) ?: Aff.ID).inverse() ?: Aff.ID
            vecJointInv = toJoint
            vecJointBaseRef[0] = base
            // a new joint is stored as an offset from the unmoved line at its arc position, so it follows the line when the interpolation or the preview moves
            val hit = if (hitIndex != null) hitShown else toJoint.map(hitShown.x, hitShown.y).let { back ->
                val b = vecJointBaseAt(base, hitShown)
                if (b != null) hitShown.copy(x = back.x - b.x, y = back.y - b.y, rel = true) else hitShown.copy(x = back.x, y = back.y)
            }
            vecBefore = cut.snap()
            vecGuideChanged = false
            vecJointDragged = false
            vecJointTapDel = if (hitIndex != null && hitIndex == ed.vecGuideSelected) hitIndex else -1 // second tap on the selected joint deletes it (when the pen lifts without dragging)
            if (hitIndex != null) ed.vecGuideSelected = hitIndex else {
                val next = cfg.copy(mode = mode, guideJoints = cfg.guideJoints + hit)
                source.vecCfg = next
                ed.vecGuideSelected = next.guideJoints.lastIndex
                vecGuideChanged = true
            }
            st.kind = 26
        }
    }

    fun penDown(ev: MotionEvent, tl: Int) {
        val d = toDoc(ev.x, ev.y); hover = Offset(ev.x, ev.y)
        st.kind = 0; st.tl = tl; st.start = d; st.last = d; st.moved = false; st.sx0 = ev.x; st.psum = 0f; st.pn = 0
        st.downScr = Offset(ev.x, ev.y); st.farScr = false; st.refOutside = false
        shapePerspective = null; shapeMagnetState = null; shapeMagnetAnchor = null; shapeHoverDirection = null
        val cl = cut.layer
        // A selected video owns stylus drags; don't let stale selection handles or
        // drawing-tool state redirect the gesture into the canvas.
        if (cl != null && cl.kind == 7) {
            if (!cl.locked && cl.visible && gDown(Offset(ev.x, ev.y), 26f, true)) { st.kind = 11; return }
            if (!cl.locked && cl.visible) { st.kind = 12; st.snap = cut.snap(); st.last = Offset(ev.x, ev.y) }
            return
        }
        selModHeld = (ev.metaState and (android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_CTRL_ON)) != 0
        val xw = ed.xform
        if (xw != null && xw.mode != 0) { wpDown(xw, ev, d); return } // Warp / Puppet Warp: grid points or pins instead of the box handles
        val vxw = ed.vecXf
        if (vxw != null && vxw.mode != 0) { vwpDown(vxw, ev, d); return } // the same on vector lines
        if (gDown(Offset(ev.x, ev.y), 26f, true)) {
            st.kind = 11
            // a pen-down in the handle zone but outside every image still counts as a tap on empty space
            st.refOutside = gz.ctx == 5 && cut.layer?.let { it.kind == 5 && refInkAt(it, Offset(ev.x, ev.y)) == null } == true
            return
        }
        cut.layer?.let { rl -> if (rl.kind == 5 && rl.visible && !rl.locked && !cut.playing) { st.kind = 14; return } } // pen on empty space of a reference layer: a tap ends the edit
        if (ed.xform != null || ed.vecXf != null) { st.kind = 5; return }
        val bz = bez
        if (bz != null) { st.kind = 23; bez = bezSet(bz, d); return }
        val pe = pend
        if (pe != null) { st.kind = 6; pend = minorFrom(pe, d); return }
        if (cl != null && cl.kind == 2) { st.kind = 9; st.snap = cut.snap(); return } // selecting a camera layer is enough to move / scale / rotate it
        if (cl != null && cl.kind == 3) { cl.persp?.let { pDown(ev, d, it) }; return }
        val l = cut.drawLayer ?: return
        if (tl == 5) {
            val sx = d.x.toInt().coerceIn(0, cut.w - 1); val sy = d.y.toInt().coerceIn(0, cut.h - 1)
            val sample = renderWindow(cut, cut.time, sx, sy, 1, 1)
            ed.color = Color(sample.getPixel(0, 0)); sample.recycle()
            st.kind = 0
            return
        }
        if (tl == 6) { // Reshape tool: vector lines only for now
            if (!l.vector) { android.widget.Toast.makeText(vecCtx, "Reshape works on vector layers for now; drawing layers are not supported yet", android.widget.Toast.LENGTH_SHORT).show(); return }
            if (l.locked || !l.visible) return
            vecBrushDown(d)
            return
        }
        if (l.vector) { // vector layer: the Brush tool draws vector strokes; point edit and cutter work on the strokes already there
            if (tl != 0 && tl != 1 && tl != 2 && tl != 3 && tl != 4) { android.widget.Toast.makeText(vecCtx, "Vector layers: Brush (draw, edit points, cut), Eraser, Fill, Selection and Line / shapes", android.widget.Toast.LENGTH_SHORT).show(); return }
            if (l.locked || !l.visible) return
            if (tl == 2) { // Selection tool: Points on = pick / drag single points (and the point brushes), off = select whole lines
                if (ed.vecMode == 3) { // the interpolation preview is up: the Selection tool works on the PREVIEW lines
                    if (ed.vecDisplace) vecJointDown(l, d) else { st.kind = 29; preSel = listOf(d) } // Displace: joints on the preview; otherwise tap a line / drag a rectangle to pick
                    return
                }
                if (ed.vecDisplace) { vecDisplaceDown(l, d); return } // Displace: tap a selected line to add a joint, drag a joint to warp the selection
                if (ed.vecPoints) vecEditDown(l, d) else vecSelectDown(l, ev, d)
                return
            }
            if (tl == 1) { st.kind = 25; vecCutPath = listOf(d); return } // eraser: deletes a line up to its crossings when the pen lifts
            if (tl == 0) {
                when (ed.vecMode) {
                    2 -> { st.kind = 17; vecCutPath = listOf(d) }
                    3 -> { st.kind = 22; vecDragX0 = ev.x; vecDragT0 = ed.dragTShown() }
                    4 -> vecJointDown(l, d)
                    else -> { materialize(l); cut.ensureCelUniform(l); st.kind = 15; vecPts.clear(); vecMergeLen[0] = 0f; vecMergeStart = null; vecMergeEnd = null; vecMergeLines[0] = if (ed.vecMerge) (l.celAt(cut.time)?.vectorStrokes() ?: emptyList()) else emptyList(); vecPen = VecPen(ed.brushes[ed.brushIdx]); vecSample(d.x, d.y, ev.pressure, ev.eventTime) }
                }
                return
            }
            // tl == 3 (Fill) and tl == 4 (line / shape) continue below; commitShape writes a vector stroke on this layer, the fill a closed fill stroke
        }
        if (tl != 2 && (l.locked || !l.visible)) return
        if (tl != 2) materialize(l)
        when (tl) {
            0, 1 -> {
                val br = if (tl == 1) ed.erasers[ed.eraserIdx] else ed.brushes[ed.brushIdx]
                val cel = cut.ensureCelUniform(l)
                val perspective = cut.layers.firstOrNull { it.kind == 3 && it.visible }?.persp
                // One latch per stroke: stronger capture, then continuous projection onto that same guide/ray.
                val magnetState = PerspectiveMagnetState()
                // A visible perspective layer owns every brush and eraser stroke: there is
                // no ordinary freehand fallback. Hover direction helps choose the intended
                // path; if unavailable, the first contact movement selects it.
                val hoverDirectionDoc = hoverDirection?.takeIf { android.os.SystemClock.uptimeMillis() - hoverDirectionAt < 900L }?.let { screenDeltaToDoc(it.x, it.y) }
                val magnet: ((Offset, Offset) -> Offset)? = perspective?.let { p -> { anchor, point -> p.magnet(anchor, point, 42f / (fit * ed.vScale).coerceAtLeast(0.0001f), magnetState, max(dw, dh) * 4f, hoverDirectionDoc) } }
                val s = Session(cel, TileCache.get(cel.strokes), br, ed.color.toArgb(), paintClip(), magnet)
                s.onStamped = { tick++ } // dabs are stamped on a worker thread: redraw when pixels are ready
                sess[0] = s; st.kind = 1; feedEv(ev, s)
            }
            2 -> { st.selOp = selOpOf(ev); when (if (ed.selWand) 3 else ed.selMode) {
                0, 1 -> { st.kind = 2; preSel = listOf(d) }
                2 -> st.kind = 8
                else -> st.kind = 3
            } }
            3 -> st.kind = 3
            4 -> {
                st.kind = 4; preShape = emptyList(); st.psum = ev.pressure; st.pn = 1
                if (ed.lineShape != 3) {
                    val perspective = cut.layers.firstOrNull { it.kind == 3 && it.visible }?.persp
                    if (perspective != null) {
                        val state = PerspectiveMagnetState()
                        val hd = hoverDirection?.takeIf { android.os.SystemClock.uptimeMillis() - hoverDirectionAt < 900L }?.let { screenDeltaToDoc(it.x, it.y) }
                        shapePerspective = perspective; shapeMagnetState = state; shapeMagnetAnchor = d; shapeHoverDirection = hd
                        perspective.magnet(d, d, 42f / (fit * ed.vScale).coerceAtLeast(0.0001f), state, max(dw, dh) * 4f, hd)
                        if (state.kind >= 0) st.start = perspective.magnet(d, d, 42f / (fit * ed.vScale).coerceAtLeast(0.0001f), state, max(dw, dh) * 4f, hd)
                    }
                }
            }
        }
        if (st.kind == 3) {
            // The mask window is the Cut with Bleed off; with Bleed on it also covers painted content and the visible area.
            val celSet = TileCache.get(cut.ensureCelUniform(l).strokes)
            val win = workWindow(cut, ed.drawOutsideFrame, celSet, viewRect(), d.x, d.y)
            st.wx = win.left; st.wy = win.top; st.ww = win.width(); st.wh = win.height()
            val n = st.ww * st.wh
            val px = IntArray(n)
            if (l.vector) {
                val walls = vecFillPixels(cut, l, ed.vecFillAcross, cut.time, st.wx, st.wy, st.ww, st.wh)
                System.arraycopy(walls, 0, px, 0, n)
            } else if (ed.refAll) {
                val src = renderWindow(cut, cut.time, st.wx, st.wy, st.ww, st.wh)
                src.getPixels(px, 0, st.ww, 0, 0, st.ww, st.wh); src.recycle()
            } else celSet.readRect(st.wx, st.wy, st.ww, st.wh, px)
            st.sx = (d.x.toInt() - st.wx).coerceIn(0, st.ww - 1); st.sy = (d.y.toInt() - st.wy).coerceIn(0, st.wh - 1)
            if (tl == 3) { val sn = snapSeed(px, st.ww, st.wh, st.sx, st.sy, tolToLevel(WAND_FILL_TOL)); st.sx = sn[0]; st.sy = sn[1] } // a tap on / beside a line moves to the open area next to it
            st.lvl = floodMap(px, st.ww, st.wh, st.sx, st.sy)
            val ws = FillWork(n); st.work = ws; st.autoGap = -1; st.gapEdited = false
            st.baseMask = ws.region; st.mask = BooleanArray(n); st.gapTmp = ws.tmp; st.buf = ws.queue
            st.gap0 = if (tl == 3) ed.fillGap else ed.wandGap; st.gapNow = st.gap0.coerceIn(0, MAX_GAP_FILL)
            st.grow = if (tl == 3) ed.fillGrow else 0; st.g0 = st.grow; st.y0 = ev.y
            recompute()
        }
    }

    fun lineEnd(d: Offset): Offset {
        val p = shapePerspective ?: return d
        val state = shapeMagnetState ?: return d
        val anchor = shapeMagnetAnchor ?: st.start
        val radius = 42f / (fit * ed.vScale).coerceAtLeast(0.0001f)
        val mapped = p.magnet(anchor, d, radius, state, max(dw, dh) * 4f, shapeHoverDirection)
        if (state.kind >= 0) st.start = p.magnet(anchor, anchor, radius, state, max(dw, dh) * 4f, shapeHoverDirection)
        return mapped
    }

    fun lineShapePoints(d: Offset): List<Offset> {
        val end = lineEnd(d)
        if (ed.lineShape == 0 || ed.lineShape == 4) {
            val p = shapePerspective
            val state = shapeMagnetState
            val anchor = shapeMagnetAnchor
            if (p != null && state != null && anchor != null) {
                p.magnet(anchor, d, 42f / (fit * ed.vScale).coerceAtLeast(0.0001f), state, max(dw, dh) * 4f, shapeHoverDirection)
                p.magnetPathBetween(anchor, d, state)?.takeIf { it.size >= 2 }?.let { return it }
            }
        }
        return shapePts(ed.lineShape, st.start, end)
    }

    /** Joint gestures use a small tap slop (screen px) instead of the general TAP_SLOP_PX, so a joint can be nudged a little; once it is exceeded the gesture stays a drag. */
    fun jointDragNow(ev: MotionEvent): Boolean {
        if (!vecJointDragged && (Offset(ev.x, ev.y) - st.downScr).getDistance() > JOINT_TAP_SLOP_PX) vecJointDragged = true
        return vecJointDragged
    }

    fun penMove(ev: MotionEvent) {
        val d = toDoc(ev.x, ev.y); hover = Offset(ev.x, ev.y)
        if (!st.moved && (d - st.start).getDistance() > 6f) st.moved = true
        if (!st.farScr && (Offset(ev.x, ev.y) - st.downScr).getDistance() > AppSettings.tapSlopPx) st.farScr = true // tap slop in SCREEN px: pen jitter must not turn a tap into a drag
        when (st.kind) {
            1 -> sess[0]?.let { feedEv(ev, it) }
            15 -> vecFeed(ev)
            16 -> vecEditMove(ev, d)
            24 -> vecBrushStep(d)
            25 -> vecCutPath = vecCutPath + d
            17 -> vecCutPath = vecCutPath + d
            18 -> vecXfMove(d)
            22 -> ed.setDragT((vecDragT0 + (ev.x - vecDragX0) / (260f * vecCtx.resources.displayMetrics.density)).coerceIn(0f, 1f)) // only the picked preview lines when there are any
            27 -> vecPlaceMove(d)
            29 -> preSel = listOf(st.start, Offset(d.x, st.start.y), d, Offset(st.start.x, d.y))
            28 -> if (vecDispTapDel < 0 || jointDragNow(ev)) { val j = ed.vecDisplaceJoints; val at = ed.vecDisplaceSelected; if (at in j.indices) ed.vecDisplaceJoints = j.toMutableList().also { it[at] = it[at].copy(x = d.x, y = d.y) } }
            26 -> if (vecJointTapDel < 0 || jointDragNow(ev)) cut.drawLayer?.let { layer -> guideSourceFor(layer)?.first?.let { source ->
                val cfg = source.vecCfg
                val at = ed.vecGuideSelected
                if (cfg != null && at in cfg.guideJoints.indices) {
                    val old = cfg.guideJoints[at]
                    val q = vecJointInv.map(d.x, d.y) // the pen is on the canvas; the joint lives in the preview's own space
                    val moved = if (old.rel) vecJointBaseAt(vecJointBaseRef[0], old)?.let { b -> old.copy(x = q.x - b.x, y = q.y - b.y) } ?: old else old.copy(x = q.x, y = q.y)
                    if (moved != old) {
                        source.vecCfg = cfg.copy(guideJoints = cfg.guideJoints.toMutableList().also { it[at] = moved })
                        vecGuideChanged = true
                    }
                }
            } }
            20 -> preSel = if (!ed.selWand && ed.selMode == 0) listOf(st.start, Offset(d.x, st.start.y), d, Offset(st.start.x, d.y)) else preSel + d
            2 -> preSel = if (!ed.selWand && ed.selMode == 0) listOf(st.start, Offset(d.x, st.start.y), d, Offset(st.start.x, d.y)) else preSel + d
            3 -> { // horizontal drag = gap fill (1 px per 3 screen px), vertical drag = expand / reduce
                val nt = (st.gap0 + ((ev.x - st.sx0) / 3f).toInt()).coerceIn(0, MAX_GAP_FILL)
                val ng = (st.g0 + ((st.y0 - ev.y) / 10f).toInt()).coerceIn(-15, 15)
                val now = System.currentTimeMillis()
                if ((nt != st.gapNow || ng != st.grow) && now - st.lastT > 45) { if (nt != st.gapNow) st.gapEdited = true; st.gapNow = nt; st.grow = ng; st.lastT = now; recompute() }
            }
            4 -> { st.psum += ev.pressure; st.pn++; preShape = lineShapePoints(d) }
            11 -> gMove(Offset(ev.x, ev.y))
            13 -> wpMove(d)
            12 -> cut.layer?.takeIf { it.kind == 7 }?.let { l ->
                val delta = screenDeltaToDoc(ev.x - st.last.x, ev.y - st.last.y)
                l.mediaX += delta.x; l.mediaY += delta.y; st.last = Offset(ev.x, ev.y)
            }
            5 -> { ed.xform?.let { x -> val t = d - st.last; x.mat.postTranslate(t.x, t.y); x.rev++; st.last = d }; ed.vecXf?.let { x -> val t = d - st.last; x.mat.postTranslate(t.x, t.y); x.rev++; st.last = d } }
            30 -> vwpMove(d)
            6 -> pend?.let { pend = minorFrom(it, d) }
            23 -> bez = bez?.let { bezSet(it, d) }
            9 -> { cut.layer?.cam?.let { camNudge(ed, cut, it, cut.time, d.x - st.last.x, d.y - st.last.y, 1f, 0f) }; st.last = d }
            10 -> if (st.moved) cut.layer?.persp?.let { p ->
                if (st.vi >= 0 && st.vi in p.vps.indices) {
                    val v = p.vps[st.vi]; val next = p.coordinate(v.kind, d); val delta = next - v.t
                    if (p.guide(v.kind).lockVpDistance) {
                        p.vps.indices.filter { p.vps[it].kind == v.kind }.forEach { i -> val other = p.vps[i]; p.vps[i] = Vp(other.kind, other.t + delta) }
                    } else p.vps[st.vi] = Vp(v.kind, next)
                } else if (st.drag in 1..2) {
                    val kind = st.drag - 1; val g = p.guide(kind)
                    if (g.rotationUnlocked) {
                        val q = d - st.guidePivot0
                        val angle = atan2(q.y, q.x) * 180f / PI.toFloat()
                        val rawDelta = ((angle - st.guideDownAngle + 180f) % 360f + 360f) % 360f - 180f
                        val delta = if (g.rotationSnap30) (rawDelta / 30f).roundToInt() * 30f else rawDelta
                        p.rotateGuideFrom(kind, st.guidePivot0, st.guideOrigin0, st.guideRotation0, st.guideVpPositions0, delta)
                    } else {
                        val n = p.axisNormal(kind); val delta = d - st.guideDown0
                        val shift = delta.x * n.x + delta.y * n.y
                        p.center = st.guideOrigin0.x + n.x * shift; p.horizon = st.guideOrigin0.y + n.y * shift
                    }
                }
            }
        }
        tick++
    }

    fun penUp(ev: MotionEvent) {
        val d = toDoc(ev.x, ev.y)
        when (st.kind) {
            15 -> { vecFeed(ev); cut.drawLayer?.let { vecCommitStroke(it) }; vecPts.clear(); vecPen = null; preShape = emptyList(); vecMergeStart = null; vecMergeEnd = null; vecMergeLines[0] = emptyList(); vecLive++ }
            16 -> cut.drawLayer?.let { vecEditUp(it, d) }
            24 -> vecBrushUp()
            25 -> cut.drawLayer?.let { vecEraseUp(it) }
            17 -> cut.drawLayer?.let { vecCutUp(it) }
            26 -> {
                if (vecJointTapDel >= 0 && !vecJointDragged) cut.drawLayer?.let { layer -> guideSourceFor(layer)?.first?.let { source -> // second tap on the selected joint: delete it
                    val cfg = source.vecCfg
                    if (cfg != null && vecJointTapDel in cfg.guideJoints.indices) {
                        source.vecCfg = cfg.copy(guideJoints = cfg.guideJoints.toMutableList().also { it.removeAt(vecJointTapDel) })
                        ed.vecGuideSelected = -1; vecGuideChanged = true
                    }
                } }
                vecJointTapDel = -1
                if (vecGuideChanged) vecBefore?.let { cut.pushStruct(it) }; vecBefore = null; vecGuideChanged = false
            }
            28 -> {
                if (vecDispTapDel in ed.vecDisplaceJoints.indices && !vecJointDragged) { ed.vecDisplaceJoints = ed.vecDisplaceJoints.toMutableList().also { it.removeAt(vecDispTapDel) }; ed.vecDisplaceSelected = -1 } // second tap on the selected joint
                vecDispTapDel = -1
            }
            18 -> vecXfUp()
            29 -> vecPreSelectUp(ev, d)
            19, 20, 21 -> cut.drawLayer?.let { vecSelectUp(it, ev, d) }
            1 -> sess[0]?.let { s ->
                feedEv(ev, s)
                s.finish()?.let { ink ->
                    cut.commit(s.cel, ink, s.backup)
                    s.entry.count = s.list.size
                }
            }
            2 -> { if (st.moved && preSel.size >= 3) finishSelection(selFromPath(androidPath(preSel), cut, ed.drawOutsideFrame), st.selOp, true); preSel = emptyList() }
            8 -> {
                val first = poly.firstOrNull()
                if (first != null && poly.size >= 3 && (d - first).getDistance() * fit * ed.vScale < 28f) {
                    finishSelection(selFromPath(androidPath(poly.toList()), cut, ed.drawOutsideFrame), selOpOf(ev), true); poly.clear()
                } else poly.add(d)
            }
            3 -> {
                val mk = st.mask
                if (mk != null && liveOn) {
                    if (st.tl == 3) {
                        if (ed.paintInSel && ed.xform == null) ed.sel?.let { clipMaskToSel(mk, st.wx, st.wy, st.ww, st.wh, it) } // fill inside the selection only
                        val fl = cut.drawLayer
                        if (fl != null && fl.vector) {
                            val pts = maskToFillPoints(mk, st.ww, st.wh, st.wx, st.wy)
                            if (pts != null) {
                                val sdx = st.sx + st.wx + 0.5f; val sdy = st.sy + st.wy + 0.5f
                                val fill = VectorStroke(newStrokeId(), pts, ed.color.toArgb() or (0xFF shl 24), 0f, "", true, 1f)
                                    .copy(fill = if (ed.vecFillLive) 2 else 1, fillGap = st.gapNow, fillGrow = st.grow, fillAcross = ed.vecFillAcross, fillSeedX = sdx, fillSeedY = sdy)
                                addVectorFill(cut, fl, cut.time, fill, sdx, sdy)
                            }
                        } else {
                        val fr = runsOf(mk, st.ww, st.wh, st.wx, st.wy)
                        if (fr.isNotEmpty()) cut.drawLayer?.let { l -> commitOp(l, Ink(FloatArray(0), ed.color.toArgb() or (0xFF shl 24), Brush(""), 1, fr, null)) }
                        }
                        ed.fillGap = st.gapNow
                    } else { finishSelection(Sel(st.wx, st.wy, st.ww, st.wh, mk.copyOf()), st.selOp, false); ed.wandGap = st.gapNow }
                }
                liveOn = false; thr = -1
                st.lvl = null; st.baseMask = null; st.mask = null; st.gapTmp = null; st.buf = null; st.work = null; st.bmp?.recycle(); st.bmp = null
            }
            4 -> {
                val avg = if (st.pn > 0) st.psum / st.pn else 1f
                if (ed.lineShape == 3) {
                    val a = st.start; val len = (d - a).getDistance()
                    if (len > 6f) { pend = Ell(Offset((a.x + d.x) / 2, (a.y + d.y) / 2), len / 2, len / 4, atan2(d.y - a.y, d.x - a.x)); pendP = avg }
                } else if (ed.lineShape == 4) {
                    val pts = lineShapePoints(d); val a = pts.firstOrNull(); val b = pts.lastOrNull()
                    if (a != null && b != null && (b - a).getDistance() > 6f) { bez = Bez(a, b, a + (b - a) / 3f, b, 1); pendP = avg }
                } else commitShape(lineShapePoints(d), avg)
                preShape = emptyList(); shapePerspective = null; shapeMagnetState = null; shapeMagnetAnchor = null; shapeHoverDirection = null
            }
            6 -> { pend?.let { e -> commitShape(ellPts(e.c, e.rx, e.ry, e.a), pendP) }; pend = null }
            23 -> bez?.let { z -> if (z.stage == 1) { bez = z.copy(stage = 2, c2 = z.b) } else { commitShape(bezierPts(z.a, z.b, z.c1, z.c2), pendP); bez = null } }
            9 -> st.snap?.let { cut.pushStruct(it) }
            11 -> {
                val tapOut = st.refOutside && !st.farScr
                if (tapOut) gz.referenceInk?.m?.let { m -> gz.referenceMatrix0?.copyInto(m) } // undo the jitter of the tap before ending the drag
                gUp()
                if (tapOut) exitReference()
            }
            14 -> if (!st.farScr) exitReference()
            13 -> wpUp()
            30 -> vwpUp()
            12 -> if (st.moved) st.snap?.let { cut.pushStruct(it) }
            10 -> cut.layer?.persp?.let { p ->
                if (!st.moved) {
                    if (st.vi >= 0) ed.vpSel = st.vi // pressing a point shows its trash bin
                    else if (st.drag == 1) { cut.record { p.vps.add(Vp(0, p.coordinate(0, d))) }; ed.vpSel = -1 }
                    else if (st.drag == 2) { cut.record { p.vps.add(Vp(1, p.coordinate(1, d))) }; ed.vpSel = -1 }
                    else ed.vpSel = -1
                } else st.snap?.let(cut::pushStruct)
            }
        }
        sess[0]?.release(); sess[0] = null; st.kind = 0; tick++
    }

    val base: DrawTransform.() -> Unit = {
        translate(cx + ed.vOff.x, cy + ed.vOff.y); rotate(ed.vRot, Offset.Zero)
        scale(fit * ed.vScale * sgx, fit * ed.vScale * sgy, Offset.Zero); translate(-dw / 2, -dh / 2)
    }

    // vector layers: strokes of the shown keyframe for the point-edit / cutter overlay
    val vecLayerNow = cut.layer?.takeIf { it.vector && it.visible && ((ed.tool == 0 && ed.vecMode == 2) || ed.tool == 2) && !cut.playing }
    val vecShowStrokes = remember(vecLayerNow, cut.time, ed.refAll, cut.layers.size, tick, ed.vecSel, ed.vecSelCels) { if (vecLayerNow == null) emptyList<VectorStroke>() else vecScope(cut, ed.refAll)?.strokes ?: emptyList<VectorStroke>() }

    // interpolation drag: the in-between of the previous and the current keyframe at the dragged position
    // (the Selection tool keeps the preview up: it picks, transforms, warps and displaces the preview lines)
    val vecPrevLayer = cut.layer?.takeIf { it.vector && it.visible && ed.vecMode == 3 && (ed.tool == 0 || ed.tool == 2) && !cut.playing }
    val vecJointsOn = vecPrevLayer != null && ed.tool == 2 && ed.vecDisplace // Displace on the preview: guide joints are shown and edited
    ed.vecXfBegin = { vecXfBeginNow() }; ed.vecXfApply = { vecXfApplyNow() }; ed.vecXfCancel = { vecXfCancelNow() }
    val vxNow = ed.vecXf
    LaunchedEffect(vxNow) { if (vxNow != null) snapshotFlow { vxNow.rev }.collect { vecXfRefresh() } }
    LaunchedEffect(cut.time, ed.tool, cut.layerId, vxNow) { if (vxNow != null && (ed.tool != 2 || vecXfCtx[0] != cut.time || vecXfCtx[1] != cut.layerId)) vecXfCancelNow() }
    val vecPreviewCfg = vecPrevLayer?.let { layer -> guideSourceFor(layer)?.first?.vecCfg }
    /** The interpolation preview of [pl] at the dragged position. [joints] = false leaves the guide joints out and [place] = what to place it with (the unmoved line a relative joint is measured from). */
    fun previewStrokes(pl: Layer, joints: Boolean, place: InbetweenPlace?): List<VectorStroke> {
        fun cfgOf(c: InterpolationConfig?, pm: InterpolationMode): InterpolationConfig = (c ?: InterpolationConfig()).copy(mode = pm).let { if (joints) it else it.copy(guideJoints = emptyList()) }
        return if (vecRefSelected(pl, ed, cut.time).isNotEmpty()) { // reference frames picked (one or two, the current frame needs no selection): they are the ends, the result lands on the current frame
            val r = vecDragRefs(pl, ed, cut.time)
            if (r == null) emptyList<VectorStroke>() else {
                val pm = (r.a.vecCfg ?: InterpolationConfig()).mode.let { if (it == InterpolationMode.NONE) InterpolationMode.SAME_STROKE else it }
                VectorInterpolation.interpolateConfigured(r.a.vectorStrokes(), r.b.vectorStrokes(), cfgOf(r.a.vecCfg, pm), ed.vecDragT, vecSelectedLinesIn(ed, r.b), false, place)
            }
        } else {
            val pi = pl.indexAt(cut.time)
            if (pi < 1) emptyList<VectorStroke>() else {
                val pc = pl.cels[pi - 1]; val cc = pl.cels[pi]
                val pm = (pc.vecCfg ?: InterpolationConfig()).mode.let { if (it == InterpolationMode.NONE) InterpolationMode.SAME_STROKE else it }
                VectorInterpolation.interpolateConfigured(pc.vectorStrokes(), cc.vectorStrokes(), cfgOf(pc.vecCfg, pm), ed.vecDragT, vecSelectedLineIndices(cut, ed, cc).takeIf { it.isNotEmpty() }, false, place) // only the selected lines when there is a selection
            }
        }
    }
    val vecPreviewKeys = arrayOf<Any?>(vecPrevLayer, cut.time, ed.vecDragT, ed.vecDragFixed, ed.vecPlaceAll, ed.vecPlaceLines, ed.vecPlaceSteps, vecJointsOn, ed.vecLockWeight, ed.vecSmoothAll, ed.vecSmoothLines, ed.vecDragTLines, vecPrevLayer?.cels?.size, vecPreviewCfg, tick, ed.vecSel, ed.vecSelCels, ed.selCels.toList())
    val vecPreview = remember(*vecPreviewKeys) {
        val pl = vecPrevLayer
        val fixedCtx = Pair(cut.time, pl)
        if (vecFixedCtx[0] != fixedCtx) { vecFixedCtx[0] = fixedCtx; ed.vecFixedAll.clear(); ed.vecFixedLines.clear() } // another frame: Fixed pins the preview where it is there
        if (pl == null) emptyList<VectorStroke>() else previewStrokes(pl, true, ed.inbetweenPlace())
    }
    // the unmoved interpolation (no joints, no manual transform): relative joints are measured from it, to be drawn where they are
    val vecPreviewBase = remember(*vecPreviewKeys) {
        val pl = vecPrevLayer
        if (pl == null || !vecJointsOn || vecPreviewCfg?.guideJoints?.any { it.rel } != true) emptyList<VectorStroke>() else {
            val p = ed.inbetweenPlace()
            previewStrokes(pl, false, p?.let { InbetweenPlace(it.fixed, Aff.ID, emptyMap(), it.lockWeight, it.smoothAll, it.smoothLines, it.tLines, fixedAt = it.fixedAt, fixedAtLines = it.fixedAtLines) })
        }
    }

    vecPreviewRef[0] = vecPreview
    val vecGuideSourceCel = vecPrevLayer?.let { layer -> guideSourceFor(layer)?.first }

    // selection tool, Displace: the selection as it will look once the joints are applied
    val vecDisplaceWarped = remember(vecShowStrokes, ed.tool, ed.vecDisplace, ed.vecDisplaceJoints, ed.vecSel, ed.vecPoints) {
        if (ed.tool != 2 || !ed.vecDisplace || ed.vecDisplaceJoints.isEmpty() || ed.vecSel.isEmpty()) emptyList<Warped>() else {
            val picked = ed.vecSel.map { vecKeyStroke(it) }.toSet()
            displaceSelectionWarped(vecShowStrokes, ed.vecSel, ed.vecDisplaceJoints, !ed.vecPoints).filterIndexed { i, _ -> i in picked }
        }
    }
    vecDisplaceRef[0] = vecDisplaceWarped
    val vecDisplaceShow = remember(vecDisplaceWarped) { vecDisplaceWarped.map { it.stroke } }

    // selection tool AND brush tool: places where separate lines cross or touch without being joined (they shimmer); not while a drag is running.
    // The key holds the screen zoom (the reach is in screen pixels) and the edit counter, so the marks follow zoom and every committed stroke / undo.
    val vecGapBrush = ed.tool == 0 && ed.vecMode == 0 && !cut.playing && cut.layer?.let { it.vector && it.visible } == true
    val vecGapStrokes = remember(vecShowStrokes, vecGapBrush, cut.layerId, cut.time, ed.refAll, tick, cut.edits) {
        if (vecGapBrush) vecScope(cut, ed.refAll)?.strokes ?: emptyList<VectorStroke>() else vecShowStrokes
    }
    val vecGaps = remember(vecGapStrokes, ed.vecMergeRadius, ed.tool, vecLives != null, ed.vecMode, fit * ed.vScale, vecGapBrush) {
        if (!vecGapBrush && (ed.tool != 2 || vecLayerNow == null || vecLives != null || ed.vecMode == 3)) FloatArray(0) else VectorGaps.find(vecGapStrokes, (ed.vecMergeRadius / vecZoom()).coerceAtLeast(2f))
    }

    // line order onion skin: the next untraced line of the previous / next keyframe shimmers, with its anchors; a new line drawn over it takes those anchors
    val ghostLayer = cut.layer?.takeIf { it.vector && it.visible && !it.locked && ed.vecGhost && !cut.playing && ((ed.tool == 0 && ed.vecMode == 0) || ed.tool == 2 || ed.tool == 4) }
    val ghostPhase = if (ghostLayer != null) rememberInfiniteTransition(label = "ghost").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ghostPhase") else null
    val ghostStep by remember(ghostPhase) { derivedStateOf { ((ghostPhase?.value ?: 0f) * 8f).toInt() } } // 8 steps a cycle: the shimmer redraws a few times a second, not every frame
    // the current keyframe's cel object, its revision and line count are keys: a merge / delete made with the selection tool swaps the cel without bumping `tick`
    val ghostCur = ghostLayer?.let { gl -> gl.cels.getOrNull(gl.indexAt(cut.time)) }
    val ghostData = remember(ghostLayer, cut.time, ed.vecGhostNext, ed.vecGhostPin, ed.vecGhostPinKey, tick, ed.vecAutoAnchor, ed.selCels.toList(), ghostCur, ghostCur?.vecRev, ghostCur?.strokes?.size, ed.vecSelCels) {
        val gl = ghostLayer
        val idx = gl?.indexAt(cut.time) ?: -1
        val cur = gl?.cels?.getOrNull(idx)
        val ref = if (gl != null && cur != null) LineGhost.reference(gl, idx, ed.vecGhostNext, ed) else null
        if (cur == null || ref == null) null else {
            val all = LineGhost.lines(ref)
            val open = LineGhost.unclaimed(all, cur.vectorStrokes(), 1f / vecZoom())
            val shown = LineGhost.shown(all, open, ed, LineGhost.key(gl, idx))
            val openIds = open.map { it.id }.toSet()
            // every line of the reference keyframe stays on the ghost: untraced ones in teal, traced ones in yellow-orange
            if (all.isEmpty()) null else GhostView(shown, open.filter { it.id != shown?.id }, all.filter { it.id !in openIds && it.id != shown?.id }, shown != null && shown.id !in openIds)
        }
    }

    Box(modifier.clipToBounds()) {
        if (planes) {
            if (!ed.gridFront) Media3VideoGridBackground(cut, ed, box.width, box.height, fit)
            Media3VideoPageBackground(cut, ed, box.width, box.height, fit)
        }
        videoLayers.forEach { layer ->
            Media3VideoReferenceLayer(cut, ed, layer, box.width, box.height, fit, hidden = !videoActive && planes)
        }
        GlLayerPlane(cut, ed, box.width, box.height, fit) { onionK.value }
        Canvas(
            Modifier.fillMaxSize()
            .zIndex(2f)
            .clipToBounds()
            .onSizeChanged { box = it }
            // ----- S Pen: raw MotionEvents give pressure, tilt and history -----
            .pointerInteropFilter { ev ->
                val tt = ev.getToolType(0)
                if ((tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) || cut.playing) return@pointerInteropFilter false
                val metrics = android.content.res.Resources.getSystem().displayMetrics
                val corrected = PenCalibration.correctedEvent(ev, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                val input = corrected ?: ev
                val handled = when (input.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        // Start a fresh trajectory on each entry into hover range.
                        val position = Offset(input.x, input.y)
                        hover = position; hoverLastPosition = position
                        hoverDirection = null; hoverDirectionAt = 0L
                        true
                    }
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        val position = Offset(input.x, input.y)
                        hover = position
                        val previous = hoverLastPosition
                        val delta = previous?.let { position - it }
                        if (delta != null && delta.getDistance() >= 0.5f) {
                            val direction = delta / delta.getDistance()
                            val old = hoverDirection
                            val blended = old?.let { it * 0.55f + direction * 0.45f } ?: direction
                            val length = blended.getDistance()
                            hoverDirection = if (length > 0.0001f) blended / length else direction
                            hoverDirectionAt = android.os.SystemClock.uptimeMillis()
                        }
                        hoverLastPosition = position
                        true
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        // Android may send HOVER_EXIT immediately before ACTION_DOWN. Keep the
                        // last direction briefly so pen-down can still use the approach vector.
                        hover = null; hoverLastPosition = null
                        true
                    }
                    MotionEvent.ACTION_DOWN -> { penDown(input, if (tt == MotionEvent.TOOL_TYPE_ERASER) 1 else ed.tool); tick++; true }
                    MotionEvent.ACTION_MOVE -> { penMove(input); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { penUp(input); true }
                    else -> true
                }
                corrected?.recycle()
                handled
            }
            // ----- pen hovering above the screen: cursor preview; also sets the ellipse's minor axis -----
            .pointerInput(box, ed.flipH, ed.flipV) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: continue
                        if (c.type == PointerType.Stylus || c.type == PointerType.Eraser || c.type == PointerType.Mouse) {
                            if (ev.type == PointerEventType.Exit) {
                                // Keep the last direction through the hover-to-contact transition.
                                hover = null; hoverLastPosition = null
                            } else {
                                hover = c.position
                                hoverLastPosition = c.position
                            }
                            val pe = pend
                            if (pe != null && ed.lineShape == 3 && ev.type != PointerEventType.Exit) { val d = toDoc(c.position.x, c.position.y); pend = minorFrom(pe, d) }
                            bez?.let { z -> if (ev.type != PointerEventType.Exit) { bez = bezSet(z, toDoc(c.position.x, c.position.y)) } }
                        }
                    }
                }
            }
            // ----- fingers: 1 = pan, 2 = pinch zoom / rotate, 3-finger drag = flip through drawings, 2-finger tap = undo -----
            .pointerInput(cut, box, ed.flipH, ed.flipV) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Stylus) return@awaitEachGesture
                    fingerTouches = listOf(down.position)
                    resetCue = false
                    var resetArmed = false
                    var refSeed: Ink? = null // the reference image this gesture started on (a finger on it moves it; a second finger turns the gesture into pinch / rotate of that image)
                    if (gDown(down.position, 38f, true)) { // a finger on an active transform handle drags it instead of panning
                        down.consume()
                        val pid = down.id
                        var toPinch = false
                        while (true) {
                            val e = awaitPointerEvent()
                            if (gz.ctx == 5 && e.changes.count { it.pressed } >= 2) { toPinch = true; e.changes.forEach { it.consume() }; break }
                            val ch = e.changes.firstOrNull { it.id == pid } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            fingerTouches = listOf(ch.position)
                            gMove(ch.position); ch.consume()
                        }
                        refSeed = if (toPinch) ed.refSel else null
                        gUp()
                        if (!toPinch) {
                            fingerTouches = emptyList()
                            return@awaitEachGesture
                        }
                    }
                    val ctr = Offset(cx, cy)
                    val t0 = System.currentTimeMillis()
                    var zt = 1f; var rt = 0f; var mv = 0f; var maxN = 0; var acc = 0f
                    var over = 0f // distance scrubbed past the last frame in this gesture
                    var camBefore: Snap? = null
                    var selectionPivot = Offset.Zero
                    var refGesture = false // two-finger gesture on a reference layer: transforms its images instead of the canvas view
                    var refList: List<Ink> = emptyList(); var refBefore: List<FloatArray> = emptyList(); var refLayerG: Layer? = null
                    val refG = Matrix() // the whole gesture so far (rotation / scale / drag), UNsnapped; the images show refBefore * refG with the magnet applied on top
                    do {
                        val ev = awaitPointerEvent()
                        val n = ev.changes.count { it.pressed }
                        maxN = max(maxN, n)
                        fingerTouches = ev.changes.filter { it.pressed }.map { it.position }
                        if (n == 2) {
                            val pair = ev.changes.filter { it.pressed }.take(2)
                            if (pair.size == 2 && ed.xform == null && cut.layer?.kind != 2 && !(cut.layer?.kind == 5 && cut.layer?.visible == true && cut.layer?.locked == false) && AppSettings.pinchReset && (pair[0].position - pair[1].position).getDistance() <= RESET_PINCH_DP.dp.toPx()) {
                                resetArmed = true
                                resetCue = true
                            }
                        }
                        if (n > 0) {
                            val z = ev.calculateZoom(); val r = ev.calculateRotation()
                            val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                            if (n >= 3 && AppSettings.threeFingerScrub) {
                                val dd = pan.x + pan.y
                                acc += dd; glow = if (dd > 0.5f) 1 else if (dd < -0.5f) -1 else glow
                                val animatedLayer = cut.layers.firstOrNull { it.id == cut.layerId }?.takeIf { it.cam != null || (it.isGroup && it.transformFolder) }
                                val step = 48f
                                if (animatedLayer != null) {
                                    // On Camera / Transformation layers, the same canvas scrub walks the diamond-key times.
                                    val times = keyTimes(animatedLayer)
                                    if (times.isNotEmpty()) {
                                        var idx = times.indices.minByOrNull { abs(times[it] - cut.time) } ?: 0
                                        while (acc >= step && idx < times.lastIndex) {
                                            idx++; acc -= step; cut.time = times[idx]; ed.newFrameArmed = false; bumpDir = 1; keyBump++
                                        }
                                        while (acc <= -step && idx > 0) {
                                            idx--; acc += step; cut.time = times[idx]; ed.newFrameArmed = false; bumpDir = -1; keyBump++
                                        }
                                    }
                                } else {
                                    val l = cut.drawLayer
                                    if (l != null && l.kind == 0 && l.cels.isNotEmpty()) {
                                        var idx = l.indexAt(cut.time).let { if (it < 0) l.cels.size - 1 else it }
                                        while (acc >= step) {
                                            if (idx < l.cels.size - 1) { idx++; ed.newFrameArmed = false; bumpDir = 1; keyBump++ }
                                            else {
                                                // Past the last frame: only a deliberate, sustained pull arms the new frame.
                                                over = (over + step).coerceAtMost(NEW_FRAME_PULL + step)
                                                if (over >= NEW_FRAME_PULL) ed.newFrameArmed = true
                                                newFramePull = (over / NEW_FRAME_PULL).coerceIn(0f, 1f)
                                            }
                                            acc -= step
                                        }
                                        while (acc <= -step && (over > 0f || idx > 0)) {
                                            acc += step
                                            if (over > 0f) {
                                                over = (over - step).coerceAtLeast(0f)
                                                if (over < NEW_FRAME_PULL) ed.newFrameArmed = false
                                                newFramePull = (over / NEW_FRAME_PULL).coerceIn(0f, 1f)
                                            } else { idx--; ed.newFrameArmed = false; bumpDir = -1; keyBump++ }
                                        }
                                        cut.playing = false; cut.time = l.startOf(idx)
                                    }
                                }
                                acc = acc.coerceIn(-step, step)
                                cut.playing = false
                            } else if (maxN < 3) {
                                if (n == 2) { zt *= z; rt += abs(r); mv += pan.getDistance() }
                                val xf = ed.xform
                                val selectedVideo = cut.layer?.takeIf { it.kind == 7 && it.visible && !it.locked }
                                val cam = cut.layer?.takeIf { it.kind == 2 }?.cam
                                val refLayer = cut.layer?.takeIf { it.kind == 5 && it.visible && !it.locked && !cut.playing }
                                if (refLayer != null && (n >= 2 || refGesture)) {
                                    // Reference layer: pinch / rotate / two-finger drag. On an image: only that image; on empty space: every image of the layer.
                                    if (n >= 2) {
                                        if (!refGesture) {
                                            refGesture = true; refLayerG = refLayer
                                            val hit = refSeed ?: refInkAt(refLayer, c)
                                            val all = cut.referenceInks(refLayer)
                                            val one = hit?.takeIf { h -> all.any { it === h } }
                                            refList = if (one != null) listOf(one) else all
                                            if (one != null) ed.refSel = one
                                            refBefore = refList.map { it.m?.copyOf() ?: FloatArray(9) }
                                        }
                                        if (z.isFinite() && z > 0f) {
                                            val cd = refLocal(refLayer, c); val pd = refLocal(refLayer, c + pan) - cd
                                            val rr = if (refLayer.refPinned) r else r * sgx * sgy
                                            refG.postRotate(rr, cd.x, cd.y); refG.postScale(z, z, cd.x, cd.y); refG.postTranslate(pd.x, pd.y)
                                            // Magnet (30 degree steps): one image snaps to an absolute angle around its own centre, several images
                                            // snap the gesture's total rotation around the fingers. The raw gesture keeps accumulating underneath,
                                            // so small turns are not swallowed by the snap.
                                            var corr = 0f; var pivot = cd
                                            if (ed.rotationSnap) {
                                                if (refList.size == 1) {
                                                    val b0 = refBefore.getOrNull(0)
                                                    val dims = refList[0].data?.toString(Charsets.UTF_8)?.let { ReferenceBitmapCache.dimensions(it) }
                                                    if (b0 != null && dims != null) {
                                                        val m0 = Matrix().apply { setValues(b0); postConcat(refG) }
                                                        val v0 = FloatArray(9); m0.getValues(v0)
                                                        val ang = Math.toDegrees(atan2(v0[Matrix.MSKEW_Y], v0[Matrix.MSCALE_X]).toDouble()).toFloat()
                                                        val ctrPt = floatArrayOf(dims.first / 2f, dims.second / 2f); m0.mapPoints(ctrPt)
                                                        corr = (ang / 30f).roundToInt() * 30f - ang; pivot = Offset(ctrPt[0], ctrPt[1])
                                                    }
                                                } else {
                                                    val gv = FloatArray(9); refG.getValues(gv)
                                                    val ang = Math.toDegrees(atan2(gv[Matrix.MSKEW_Y], gv[Matrix.MSCALE_X]).toDouble()).toFloat()
                                                    corr = (ang / 30f).roundToInt() * 30f - ang
                                                }
                                            }
                                            for ((i, ink) in refList.withIndex()) {
                                                val mv = ink.m ?: continue
                                                val b = refBefore.getOrNull(i) ?: continue
                                                val mat = Matrix().apply { setValues(b); postConcat(refG) }
                                                if (corr != 0f) mat.postRotate(corr, pivot.x, pivot.y)
                                                mat.getValues(mv)
                                            }
                                            refLayer.imageRevision++; tick++
                                        }
                                    }
                                } else if (selectedVideo != null) {
                                    if (camBefore == null) camBefore = cut.snap()
                                    val delta = screenDeltaToDoc(pan.x, pan.y)
                                    selectedVideo.mediaX += delta.x; selectedVideo.mediaY += delta.y
                                    if (n >= 2) {
                                        selectedVideo.mediaScale = (selectedVideo.mediaScale * z).coerceIn(0.05f, 20f)
                                        selectedVideo.mediaRotation += r
                                    }
                                } else if (xf != null) {
                                    val cd = toDoc(c.x, c.y); val pd = toDoc(c.x + pan.x, c.y + pan.y) - cd
                                    xf.mat.postRotate(r, cd.x, cd.y); xf.mat.postScale(z, z, cd.x, cd.y); xf.mat.postTranslate(pd.x, pd.y)
                                    selectionPivot = cd
                                    xf.rev++; tick++
                                } else if (cam != null) {
                                    if (camBefore == null) camBefore = cut.snap()
                                    val cd = toDoc(c.x, c.y); val pd = toDoc(c.x + pan.x, c.y + pan.y) - cd
                                    camNudge(ed, cut, cam, cut.time, pd.x, pd.y, z, r * sgx * sgy)
                                } else if (n >= 2 || AppSettings.onePan) {
                                    val ns = (ed.vScale * z).coerceIn(0.2f, 20f); val ze = ns / ed.vScale
                                    ed.vOff = c + pan + rot(ctr + ed.vOff - c, r) * ze - ctr
                                    ed.vRot += r; ed.vScale = ns
                                }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    glow = 0; newFramePull = 0f
                    fingerTouches = emptyList()
                    if (rt > 0.5f && ed.rotationSnap) {
                        cut.layer?.cam?.let { cam ->
                            val angle = cam.rot(cut.time)
                            setKey(cam.rs, cut.time, (angle / 30f).roundToInt() * 30f)
                        }
                        ed.xform?.let { x ->
                            val values = FloatArray(9); x.mat.getValues(values)
                            val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
                            val snapped = (angle / 30f).roundToInt() * 30f
                            x.mat.postRotate(snapped - angle, selectionPivot.x, selectionPivot.y); x.rev++
                        }
                    }
                    camBefore?.let { cut.pushStruct(it) }
                    refLayerG?.let { rl ->
                        if (refGesture) {
                            cut.pushReferenceMatrices(rl, refList, refBefore) // one undo step for the whole gesture
                            rl.imageRevision++
                        }
                    }
                    // Canvas view rotation is committed in 30-degree increments when the gesture ends.
                    if (rt > 0.5f && cut.layer?.kind != 7 && !refGesture) ed.vRot = (ed.vRot / 30f).roundToInt() * 30f
                    val dt = System.currentTimeMillis() - t0
                    if (maxN == 2 && resetArmed && !refGesture && ed.xform == null && cut.layer?.kind != 7) {
                        ed.resetView()
                    } else if (maxN == 2 && cut.layer?.kind != 7 && dt < 300 && mv < 14f && abs(ln(zt)) < 0.08f && rt < 7f) { when (AppSettings.twoFingerTap) { 1 -> cut.undo(); 2 -> cut.redo() } } // two-finger tap (Settings > Gestures: undo / redo / nothing)
                    resetCue = false
                }
            }
    ) {
        val tk = tick
        val px1 = 1f / (fit * ed.vScale)
        val xf = ed.xform
        val xrev = xf?.rev ?: 0
        // During playback the GL plane normally contains every drawing layer. Avoid
        // rebuilding Compose-side folder transforms and walking those layers again.
        // A selected overlay can still need the active layer's folder transform.
        val drawComposeLayers = glSkip < cut.layers.size
        val needActiveOverlayTransform = !drawComposeLayers && xf == null && (ed.sel != null || liveOn)
        val folderTr: Map<Int, GroupTransform> = if (drawComposeLayers || needActiveOverlayTransform) {
            HashMap<Int, GroupTransform>().apply {
                for (fl in cut.layers) if (fl.isGroup && fl.transformFolder) put(fl.id, fl.transformAt(cut.time))
            }
        } else emptyMap()
        fun drawGridOverlay() {
            if (!ed.gridOn || box.width <= 0 || box.height <= 0) return
            val cs = listOf(toDoc(0f, 0f), toDoc(box.width.toFloat(), 0f), toDoc(0f, box.height.toFloat()), toDoc(box.width.toFloat(), box.height.toFloat()))
            val minX = cs.minOf { it.x }; val maxX = cs.maxOf { it.x }; val minY = cs.minOf { it.y }; val maxY = cs.maxOf { it.y }
            val step = adaptiveGridStep(ed.gridStep, fit * ed.vScale, maxX - minX, maxY - minY)
            val g = Color(0x44607080)
            var x = floor(minX / step) * step
            while (x <= maxX) { drawLine(g, Offset(x, minY), Offset(x, maxY), strokeWidth = px1); x += step }
            var y = floor(minY / step) * step
            while (y <= maxY) { drawLine(g, Offset(minX, y), Offset(maxX, y), strokeWidth = px1); y += step }
        }
        // Behind: draw the workspace grid first; the opaque Cut page masks it.
        // Front: draw the white page, then the grid across the ENTIRE workspace.
        // Artwork is drawn in a later pass in either mode, so it always stays above the grid.
        if (!ed.gridFront && !planes) withTransform({ base() }) { drawGridOverlay() }
        if (!planes) withTransform({ base() }) { drawRect(Color.White, size = Size(dw, dh)) }
        if (ed.gridFront) withTransform({ base() }) { drawGridOverlay() }
        withTransform({ base(); if ((drawComposeLayers || ed.sel != null || liveOn) && !ed.drawOutsideFrame && cut.layers.none { it.kind == 5 && it.visible }) clipRect(0f, 0f, dw, dh) }) {
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                val onionA = (77 * onionK.value).toInt()
                val warm = OnionWarmup.ready.intValue // read so the canvas redraws when a neighbouring drawing finished rebuilding in the background
                // Persistent cels remain as a subdued red canvas reference regardless of the playhead.
                for (l in cut.layers) {
                    if (!l.visible || l.kind != 0) continue
                    val activeCel = l.celAt(cut.time)
                    for (c in l.cels) if (c.persistent && c !== activeCel && c.strokes.isNotEmpty()) {
                        val persistentSet = TileCache.peekReady(c.strokes) ?: run { OnionWarmup.request(c.strokes); null } ?: continue
                        paint.colorFilter = red; paint.alpha = 105
                        val tick = l.startOf(l.cels.indexOf(c)); val tr = cut.transformFor(l, tick)
                        val transformed = tr != GroupTransform()
                        if (transformed) { nc.save(); nc.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); nc.rotate(tr.rotation); nc.scale(tr.scale, tr.scale); nc.translate(-cut.w / 2f, -cut.h / 2f) }
                        if (!ed.drawOutsideFrame) { nc.save(); nc.clipRect(0f, 0f, cut.w.toFloat(), cut.h.toFloat()) }
                        persistentSet.draw(nc, nc.clipBounds, paint)
                        if (!ed.drawOutsideFrame) nc.restore()
                        if (transformed) nc.restore()
                    }
                }
                // Interpolation drag / preview on a vector layer: the keyframes the preview is made from are shown as onion skins (previous red, next green) and the
                // layer's own picture is replaced by the preview, which is drawn in its natural colour. With reference frames picked the references are the
                // picked reference frames (see vecDragRefs); otherwise the previous keyframe (red) and the current one (green).
                if (vecPrevLayer != null && vecPreview.isNotEmpty()) {
                    val pl = vecPrevLayer
                    val dr = vecDragRefs(pl, ed, cut.time)
                    val pi = pl.indexAt(cut.time)
                    val refs: List<Pair<Cel, android.graphics.ColorFilter?>> =
                        if (dr != null) listOf(Pair(dr.a, red), Pair(dr.b, green))
                        else listOfNotNull(pl.cels.getOrNull(pi - 1)?.let { Pair(it, red) }, pl.cels.getOrNull(pi)?.let { Pair(it, green) })
                    for ((rc, flt) in refs) {
                        if (rc.strokes.isEmpty()) continue
                        val refSet = TileCache.peekReady(rc.strokes) ?: run { OnionWarmup.request(rc.strokes); null } ?: continue
                        paint.colorFilter = flt
                        paint.alpha = (pl.opacity * 120).toInt().coerceIn(0, 255)
                        val rIdx = pl.cels.indexOfFirst { it === rc }
                        val tr = cut.transformFor(pl, pl.startOf(rIdx.coerceAtLeast(0)))
                        val transformed = pl.group >= 0 && cut.layers.any { it.isGroup && it.id == pl.group && it.transformFolder }
                        if (transformed) { nc.save(); nc.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); nc.rotate(tr.rotation); nc.scale(tr.scale, tr.scale); nc.translate(-cut.w / 2f, -cut.h / 2f) }
                        if (!ed.drawOutsideFrame) { nc.save(); nc.clipRect(0f, 0f, cut.w.toFloat(), cut.h.toFloat()) }
                        refSet.draw(nc, nc.clipBounds, paint)
                        if (!ed.drawOutsideFrame) nc.restore()
                        if (transformed) nc.restore()
                    }
                }
                if (ed.onion && !cut.playing && onionA > 0 && !glSkipOnion) for (l in cut.layers) {
                    if (!l.visible || l.kind == 5) continue
                    if (vecPrevLayer != null && l === vecPrevLayer && vecPreview.isNotEmpty()) continue // its references are drawn above
                    val activeDrawingLayerId = cut.drawLayer?.id ?: cut.layerId
                    if (!ed.onionAllLayers && l.id != activeDrawingLayerId) continue
                    val i = l.indexAt(cut.time)
                    val refs = ArrayList<Triple<Int, android.graphics.ColorFilter?, Int>>()
                    for (distance in 1..ed.onionPrevious) refs.add(Triple((if (i < 0) l.cels.size - distance else i - distance), red, distance))
                    for (distance in 1..ed.onionFollowing) refs.add(Triple((if (i < 0) -1 else i + distance), green, distance))
                    for ((idx, flt, distance) in refs) {
                        val c = l.cels.getOrNull(idx) ?: continue
                        if (c.strokes.isEmpty()) continue
                        val refSet = TileCache.peekReady(c.strokes) ?: run { OnionWarmup.request(c.strokes); null } ?: continue
                        paint.colorFilter = flt
                        // The nearest drawing is strongest; each additional drawing is progressively fainter.
                        paint.alpha = (onionA * Math.pow(0.68, (distance - 1).toDouble())).toInt().coerceIn(0, 255)
                        val sourceTick = l.startOf(idx)
                        val tr = cut.transformFor(l, sourceTick)
                        val pose = if (ed.outOfPeg) ed.referencePose(c) else ReferencePose()
                        val transformed = (l.group >= 0 && cut.layers.any { it.isGroup && it.id == l.group && it.transformFolder }) ||
                            pose != ReferencePose()
                        if (transformed) {
                            nc.save()
                            nc.translate(cut.w / 2f + tr.x + pose.x, cut.h / 2f + tr.y + pose.y)
                            nc.rotate(tr.rotation + pose.rotation)
                            nc.scale(tr.scale * pose.scaleX * (if (pose.flipX) -1f else 1f), tr.scale * pose.scaleY * (if (pose.flipY) -1f else 1f))
                            nc.translate(-cut.w / 2f, -cut.h / 2f)
                        }
                        if (!ed.drawOutsideFrame) { nc.save(); nc.clipRect(0f, 0f, cut.w.toFloat(), cut.h.toFloat()) }
                        refSet.draw(nc, nc.clipBounds, paint)
                        if (!ed.drawOutsideFrame) nc.restore()
                        if (transformed) nc.restore()
                    }
                }
                paint.colorFilter = null
                if (drawComposeLayers) for ((layerIdx, l) in cut.layers.withIndex()) {
                    if (layerIdx < glSkip) continue // drawn by the GPU compositor plane
                    if (!l.visible) continue
                    if (l.kind == 7) continue
                    if (l === vecPrevLayer && vecPreview.isNotEmpty()) continue // interpolation preview: the keyframes are the onion references above, the preview is drawn on top
                    val ls = l.drawCel(cut.time)?.strokes ?: continue
                    if (l.kind == 5) {
                        val imageRevision = l.imageRevision
                        paint.alpha = ((l.opacity * 255).toInt() + (imageRevision and 0)).coerceIn(0, 255)
                        val refTransform = if (l.refPinned) null else folderTr[l.group] // pinned images ignore the view and any folder: they sit on the screen
                        val refTransformed = refTransform != null
                        if (l.refPinned) { nc.save(); nc.concat(pinMatrix()) }
                        if (refTransform != null) { nc.save(); nc.translate(cut.w / 2f + refTransform.x, cut.h / 2f + refTransform.y); nc.rotate(refTransform.rotation); nc.scale(refTransform.scale, refTransform.scale); nc.translate(-cut.w / 2f, -cut.h / 2f) }
                        for (ink in ls) {
                            val path = ink.data?.toString(Charsets.UTF_8) ?: continue
                            val image = ReferenceBitmapCache.peek(path) ?: ReferenceBitmapCache.get(path) ?: continue
                            val values = ink.m ?: continue
                            val matrix = android.graphics.Matrix().apply { setValues(values) }
                            nc.drawBitmap(image, matrix, paint)
                        }
                        if (refTransformed) nc.restore()
                        if (l.refPinned) nc.restore()
                        continue
                    }
                    if (ls.isEmpty() && sess[0]?.list !== ls) continue
                    paint.alpha = (l.opacity * 255).toInt()
                    val tr = folderTr[l.group]
                    val transformed = tr != null
                    if (tr != null) {
                        nc.save(); nc.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); nc.rotate(tr.rotation); nc.scale(tr.scale, tr.scale); nc.translate(-cut.w / 2f, -cut.h / 2f)
                    }
                    // Unbounded cel: draw only the tiles in view. With Bleed off the Cut rectangle clips them (nothing is deleted).
                    val layerSet = TileCache.getForDraw(ls) // never replays strokes on the UI thread (scrubbing / frame changes used to wait for it)
                    if (!ed.drawOutsideFrame) { nc.save(); nc.clipRect(0f, 0f, cut.w.toFloat(), cut.h.toFloat()) }
                    layerSet?.draw(nc, nc.clipBounds, paint)
                    sess[0]?.let { s -> // a capped stroke is stamped into its own tile set and shown at its opacity until the pen lifts
                        val sc = s.scratch
                        if (sc != null && s.list === ls) sc.draw(nc, nc.clipBounds, android.graphics.Paint(paint).apply { alpha = (l.opacity * s.b.opacity.coerceIn(0f, 1f) * 255).toInt() })
                    }
                    if (!ed.drawOutsideFrame) nc.restore()
                    if (xf != null && xrev >= 0) {
                        val item = xf.itemFor(ls)
                        if (item != null) {
                            val mesh = xf.mesh
                            if (mesh != null) { // Warp / Puppet Warp: the lifted pixels through the deformation mesh, then the transform
                                nc.save(); nc.concat(xf.mat)
                                nc.drawBitmapMesh(item.lift.bmp, mesh.cols, mesh.rows, mesh.verts, 0, null, 0, paint)
                                nc.restore()
                            } else {
                                val m = Matrix(xf.mat); m.preTranslate(item.lift.left.toFloat(), item.lift.top.toFloat())
                                nc.drawBitmap(item.lift.bmp, m, paint)
                            }
                        }
                    }
                    if (transformed) nc.restore()
                }
                val activeTransform = if (xf == null && (ed.sel != null || liveOn)) folderTr[cut.layer?.group ?: -1] else null
                if (activeTransform != null) {
                    nc.save(); nc.translate(cut.w / 2f + activeTransform.x, cut.h / 2f + activeTransform.y)
                    nc.rotate(activeTransform.rotation); nc.scale(activeTransform.scale, activeTransform.scale); nc.translate(-cut.w / 2f, -cut.h / 2f)
                }
                // A completed selection is indicated by its marching-ants outline only; do not tint its pixels.
                if (liveOn) st.bmp?.let { nc.drawBitmap(it, st.wx.toFloat(), st.wy.toFloat(), selPaint) }
                if (activeTransform != null) nc.restore()
            }
            val blue = Color(0xFF3B82F6)
            if (preSel.size > 1) { drawPoly(preSel, !ed.selWand && ed.selMode == 0, Color.White, 3f * px1); drawPoly(preSel, !ed.selWand && ed.selMode == 0, blue, 1.5f * px1) }
            if (poly.isNotEmpty()) {
                val pl = poly.toList()
                drawPoly(pl, false, Color.White, 3f * px1); drawPoly(pl, false, blue, 1.5f * px1)
                pl.forEachIndexed { i, o -> drawCircle(if (i == 0) Color.Red else blue, (if (i == 0) 7f else 4f) * px1, o) }
            }
            val pe = pend
            val bzNow = bez
            val sp: List<Offset> = if (preShape.isNotEmpty()) preShape else if (pe != null) ellPts(pe.c, pe.rx, pe.ry, pe.a) else if (bzNow != null) bezierPts(bzNow.a, bzNow.b, bzNow.c1, bzNow.c2) else emptyList()
            if (sp.size > 1) drawPoly(sp, false, ed.color.copy(alpha = 0.85f), max(1f, ed.brushes[ed.brushIdx].size))
            if (bzNow != null) { // Bezier handles: the active one is orange
                drawPoly(listOf(bzNow.a, bzNow.c1), false, Color.White.copy(alpha = 0.9f), 1.5f * px1); if (bzNow.stage == 2) drawPoly(listOf(bzNow.b, bzNow.c2), false, Color.White.copy(alpha = 0.9f), 1.5f * px1)
                for (o in listOf(bzNow.a, bzNow.b)) drawCircle(Color(0xFF3B82F6), 4f * px1, o)
                listOf(bzNow.c1, bzNow.c2).forEachIndexed { i, o ->
                    if (i == 1 && bzNow.stage < 2) return@forEachIndexed // the second handle only exists once the first one is set
                    drawCircle(if (bzNow.stage == i + 1) Color(0xFFFF9800) else Color.White, 7f * px1, o)
                    drawCircle(Color(0xFF1E3A8A), 7f * px1, o, style = Stroke(1.5f * px1))
                }
            }
            val vxp = ed.vecXf?.takeIf { it.onPreview }
            val vecPreviewDraw = if (vxp != null && vxp.rev >= 0) vecDeform(vecXfPrev ?: vecPreview, vxp.sel, vxp.frozen(), ed.vecLockWeight, false) else vecPreview // a running session shows its result on the preview
            // The interpolation preview is drawn in its natural colour (the previous / next keyframes are the onion-skin references, see below); a picked line gets an orange outline.
            if (vecPreviewDraw.isNotEmpty()) {
                drawIntoCanvas { cv -> val pt = android.graphics.Paint(); for (vs in vecPreviewDraw) VectorTessellator.draw(cv.nativeCanvas, vs, pt) }
                for (vs in vecPreviewDraw) if (vs.id in ed.vecPreSel && vs.points.size > 1) {
                    val pl = vs.points.map { Offset(it.x, it.y) }
                    drawPoly(pl, vs.isClosed, Color.White, 3f * px1); drawPoly(pl, vs.isClosed, Color(0xFFFF9800), 1.5f * px1)
                }
            }
            if (vecDisplaceShow.isNotEmpty()) drawIntoCanvas { cv -> val pt = android.graphics.Paint(); for (vs in vecDisplaceShow) VectorTessellator.draw(cv.nativeCanvas, vs.copy(color = 0xFFFF9800.toInt() or (0xFF shl 24), opacity = (vs.opacity * 0.55f).coerceIn(0.2f, 1f)), pt) }
            if (ed.tool == 2 && ed.vecDisplace) ed.vecDisplaceJoints.forEachIndexed { i, g ->
                val p = Offset(g.x, g.y)
                drawCircle(Color.White, 9f * px1, p)
                drawCircle(if (i == ed.vecDisplaceSelected) Color(0xFFFF9800) else Color(0xFF00BCD4), 6f * px1, p)
                drawCircle(Color.Black.copy(alpha = 0.8f), 9f * px1, p, style = Stroke(1.2f * px1))
            }
            val placeNow = ed.inbetweenPlace()
            if (vecJointsOn) vecGuideSourceCel?.vecCfg?.guideJoints?.forEachIndexed { i, g ->
                val p = vecJointSpot(vecPreviewBase, g).let { (placeNow?.total(g.strokeId) ?: Aff.ID).map(it.x, it.y) } // a joint lives in the preview's own space (an offset from the unmoved line); draw it where the preview is
                drawCircle(Color.White, 9f * px1, p)
                drawCircle(if (i == ed.vecGuideSelected) Color(0xFFFF9800) else Color(0xFF00BCD4), 6f * px1, p)
                drawCircle(Color.Black.copy(alpha = 0.8f), 9f * px1, p, style = Stroke(1.2f * px1))
            }
            // The stroke being drawn with the vector brush, rendered like the committed line (ribbon with pressure width, curve and opacity), so lifting the pen does not change how it looks.
            if (vecLive >= 0 && st.kind == 15 && vecPts.isNotEmpty()) {
                val lb = ed.brushes[ed.brushIdx]
                val liveStroke = VectorStroke("live", vecPts.toList(), ed.color.toArgb() or (0xFF shl 24), lb.size, "", false, lb.opacity.coerceIn(0.05f, 1f), lb.aa).withBrush(lb)
                drawIntoCanvas { cv -> VectorTessellator.draw(cv.nativeCanvas, liveStroke, android.graphics.Paint()) }
                for (ring in listOfNotNull(vecMergeStart, vecMergeEnd)) { // Merge: this end will join the line it is on
                    drawCircle(Color.White, 10f * px1, ring, style = Stroke(3.5f * px1))
                    drawCircle(Color(0xFF3B82F6), 10f * px1, ring, style = Stroke(1.8f * px1))
                }
            }
            // Vector edit overlay: hairline centrelines, points (selected = orange, raised = green, lowered = purple, size follows elevation), lasso and cutter path
            if (vecLayerNow != null) {
                val numPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * px1; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                // Interpolation drag with the Selection tool: the current frame shows no lines and no points (only the preview is worked on).
                // Points view (Selection tool button): 0 = every point, 1 = only the anchor points, 2 = none.
                val pointView = if (ed.tool == 2) ed.vecPointView else 0
                if (vecPrevLayer == null) for ((si, vs) in vecShowStrokes.withIndex()) {
                    if (vs.points.size > 1) drawPoly(vs.points.map { Offset(it.x, it.y) }, vs.isClosed, Color(0xAA3B82F6), 1.2f * px1)
                    val anchorCount = vs.points.maxOfOrNull { it.anchor } ?: 0
                    for ((pi, vp) in vs.points.withIndex()) {
                        if (pointView == 2 || (pointView == 1 && vp.anchor <= 0)) continue
                        val picked = ed.vecSel.contains(vecKey(si, pi))
                        val e = vp.elevationOffset
                        val r = (3.5f + kotlin.math.min(abs(e), 12f) * 0.25f + (if (vp.anchor > 0) 2.5f else 0f)) * px1
                        val tint = if (vp.anchor > 0) Color(anchorColor(vp.anchor, anchorCount)) else if (picked) Color(0xFFFF9800) else if (e > 0.5f) Color(0xFF66BB6A) else if (e < -0.5f) Color(0xFFAB47BC) else Color.White
                        if (vp.hidden) { // invisible point: a hollow ring (orange when selected), its anchor number is still shown
                            drawCircle(if (picked) Color(0xFFFF9800) else Color(0xFF9CA3AF), r, Offset(vp.x, vp.y), style = Stroke(1.6f * px1))
                        } else drawCircle(tint, r, Offset(vp.x, vp.y))
                        if (vp.anchor > 0) drawIntoCanvas { cv -> // the number sits on the outside of the point (up and to the right)
                            val label = vp.anchor.toString()
                            val tx = vp.x + r * 0.8f + 2f * px1; val ty = vp.y - r * 0.8f - 1f * px1
                            numPaint.style = android.graphics.Paint.Style.STROKE; numPaint.strokeWidth = 3f * px1; numPaint.color = 0xFF111827.toInt()
                            cv.nativeCanvas.drawText(label, tx, ty, numPaint)
                            numPaint.style = android.graphics.Paint.Style.FILL; numPaint.color = tint.toArgb() or (0xFF shl 24)
                            cv.nativeCanvas.drawText(label, tx, ty, numPaint)
                        }
                        if (!vp.hidden) drawCircle(if (picked && vp.anchor > 0) Color(0xFFFF9800) else if (vp.anchor > 0) Color.White else Color(0xFF1E3A8A), r, Offset(vp.x, vp.y), style = Stroke((if (vp.anchor > 0) 2f else 1f) * px1))
                    }
                }
                if (vecLasso.size > 1) { drawPoly(vecLasso, true, Color.White, 3f * px1); drawPoly(vecLasso, true, Color(0xFF3B82F6), 1.5f * px1) }
                if (vecCutPath.size > 1) { drawPoly(vecCutPath, false, Color.White, 3.5f * px1); drawPoly(vecCutPath, false, Color(0xFFEF4444), 2f * px1) }
            }
            if (ed.tool == 1 && vecCutPath.size > 1 && cut.layer?.vector == true) { drawPoly(vecCutPath, false, Color.White, 3.5f * px1); drawPoly(vecCutPath, false, Color(0xFFEF4444), 2f * px1) } // vector eraser path
            if (ed.tool == 6 && !cut.playing) hover?.let { h -> // Reshape tool: the brush circle
                val c = toDoc(h.x, h.y)
                drawCircle(Color.White.copy(alpha = 0.9f), ed.vecBrushRadius, c, style = Stroke(2.5f * px1))
                drawCircle(Color(0xFFFF9800), ed.vecBrushRadius, c, style = Stroke(1.2f * px1))
            }
            sess[0]?.takeIf { it.line }?.let { s ->
                drawLine(if (s.b.erase) Color(0x88888888) else Color(s.color), Offset(s.ax, s.ay), Offset(s.bx, s.by), strokeWidth = max(1f, s.b.size), cap = StrokeCap.Round)
            }
            if (ed.drawOutsideFrame) drawRect(Color(0xFFFFC107), Offset.Zero, Size(dw, dh), style = Stroke(2f * px1))
        }
        // overlays that may extend beyond the page: camera frame and perspective guides
        withTransform({ base() }) {
            // The line ghost is drawn here, outside the page clip above: with Bleed off the page rectangle clips the drawing overlay, but the ghost must also show in the bleed area
            ghostData?.let { gv ->
                val shown = gv.shown
                val pulse = 0.5f + 0.5f * kotlin.math.sin(ghostStep / 8f * 2f * Math.PI.toFloat())
                drawIntoCanvas { cv ->
                    val gp = android.graphics.Paint()
                    for (o in gv.open) VectorTessellator.draw(cv.nativeCanvas, o.copy(color = 0xFF22D3EE.toInt(), opacity = 0.14f), gp)
                    for (o in gv.claimed) VectorTessellator.draw(cv.nativeCanvas, o.copy(color = 0xFFFFA726.toInt(), opacity = 0.24f), gp) // already traced: still shown, in yellow-orange
                    if (shown != null) VectorTessellator.draw(cv.nativeCanvas, shown.copy(color = (if (gv.shownClaimed) 0xFFFFA726 else 0xFF22D3EE).toInt(), opacity = 0.28f + 0.42f * pulse), gp)
                }
                if (shown == null) return@let
                val gc = shown.points.maxOfOrNull { it.anchor } ?: 0
                val np = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * px1; typeface = android.graphics.Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE }
                for (vp in shown.points) if (vp.anchor > 0) {
                    drawCircle(Color.White.copy(alpha = 0.9f), 6f * px1, Offset(vp.x, vp.y))
                    drawCircle(Color(anchorColor(vp.anchor, gc)).copy(alpha = 0.55f + 0.45f * pulse), 4.5f * px1, Offset(vp.x, vp.y))
                    drawIntoCanvas { cv -> cv.nativeCanvas.drawText(vp.anchor.toString(), vp.x + 7f * px1, vp.y - 7f * px1, np) }
                }
            }
            val sel = cut.layer
            // The camera's animation is seen across all layers while playing; on the camera layer itself the frame is also the canvas frame (only the outside is shaded).
            val camLayer = if (sel != null && sel.kind == 2) sel else if (cut.playing) cut.layers.firstOrNull { it.kind == 2 && it.visible && it.cam != null } else null
            val onCamLayer = camLayer != null && camLayer === sel
            if (camLayer != null) camLayer.cam?.let { cam ->
                val s = cam.sc(cut.time); val fw = cam.w * s; val fh = cam.h * s
                val fx = cam.x(cut.time, cut.w); val fy = cam.y(cut.time, cut.h)
                withTransform({ translate(fx, fy); rotate(cam.rot(cut.time), Offset.Zero) }) {
                    val fr = Rect(-fw / 2, -fh / 2, fw / 2, fh / 2); val big = max(dw, dh) * 6
                    // Darken only OUTSIDE the camera frame: four plain rectangles around it (no even-odd path), so the frame itself always stays clear, also during playback.
                    val shade = Color(if (onCamLayer) 0x66000000L else 0x40000000L)
                    drawRect(shade, Offset(-big, -big), Size(2 * big, big + fr.top))
                    drawRect(shade, Offset(-big, fr.bottom), Size(2 * big, big - fr.bottom))
                    drawRect(shade, Offset(-big, fr.top), Size(big + fr.left, fr.height))
                    drawRect(shade, Offset(fr.right, fr.top), Size(big - fr.right, fr.height))
                    drawRect(Color(0xFFFFC107), fr.topLeft, fr.size, style = Stroke(2.5f * px1))
                    drawLine(Color(0xAAFFC107), Offset(-12 * px1, 0f), Offset(12 * px1, 0f), strokeWidth = px1)
                    drawLine(Color(0xAAFFC107), Offset(0f, -12 * px1), Offset(0f, 12 * px1), strokeWidth = px1)
                }
            }
            for (pl in cut.layers) {
                if (pl.kind != 3 || !pl.visible) continue
                val p = pl.persp ?: continue
                val big = max(dw, dh) * 4; val pink = Color(0xFFE91E63)
                for (kind in 0..1) {
                    // The guide itself is always a straight axis. Curvilinear mode adds a fan
                    // of curved rays from each VP; rays closest to the horizon have least bend.
                    drawPoly(p.guidePoints(kind, big), false, pink, 2f * px1)
                    if (p.guide(kind).curvilinear) {
                        val arcs = p.curvilinearArcs(kind, steps = 48, span = big)
                        arcs.forEach { arc ->
                            val middleRay = (p.guide(kind).curvilinearRayCount - 1) / 2
                            val oneVp = p.vps.count { it.kind == kind } == 1
                            val isHorizonRay = if (oneVp) arc.curveIndex == 0 || arc.curveIndex == p.guide(kind).curvilinearRayCount / 2 else arc.curveIndex == middleRay || arc.curveIndex == middleRay + 1
                            drawPoly(arc.points, false, if (isHorizonRay) Color(0x99E91E63) else Color(0x55E91E63), if (isHorizonRay) 1.7f * px1 else 1.1f * px1)
                        }
                    }
                }
                p.vps.forEachIndexed { i, vp ->
                    val v = p.pos(vp)
                    if (!p.guide(vp.kind).curvilinear) {
                        for (k in 0 until 30) { val a = (k * 6f + p.axisAngle(vp.kind)) * (PI.toFloat() / 180f); val ray = Offset(cos(a), sin(a)); drawLine(Color(0x33E91E63), v - ray * big, v + ray * big, strokeWidth = px1) }
                    }
                    drawCircle(if (i == ed.vpSel) Color.White else pink, 8f * px1, v); drawCircle(Color.Black, 8f * px1, v, style = Stroke(1.5f * px1))
                }
                // The blue cone shows horizontal camera FOV. The lens model also scales VP
                // offsets around this optical centre when focal length changes.
                val fovLeft = p.fovEdgeDir(-1f); val fovRight = p.fovEdgeDir(1f)
                drawLine(Color(0x6674B9FF), p.origin, p.origin + fovLeft * big, strokeWidth = 1.2f * px1)
                drawLine(Color(0x6674B9FF), p.origin, p.origin + fovRight * big, strokeWidth = 1.2f * px1)
                drawCircle(Color.White, 4f * px1, p.origin); drawCircle(pink, 2f * px1, p.origin)
            }
        }
        // vector selection box: corners scale, sides stretch, outside the corners rotates, inside moves
        if (vecLayerNow != null && ed.tool == 2 && vecPrevLayer == null && ed.vecXf == null) vecSelBox()?.let { drawGizmo(it.map { p -> toScr(p) }, Color(0xFFFF9800)) }
        // picked preview lines (Selection tool on the interpolation preview) are drawn orange with the preview; the session's handles come from the transform handles below
        // transform handles (corners scale, sides stretch, outside the corners rotates)
        gCtx().let { c ->
            val box = if (c >= 0) gBox(c) else null
            if (box != null && !((c == 0 && (ed.xform?.mode ?: 0) != 0) || (c == 7 && (ed.vecXf?.mode ?: 0) != 0))) drawGizmo(box.map { toScr(it) }, when (c) { 7 -> Color(0xFFFF9800); 1 -> Color(0xFFFFC107); 2 -> Color(0xFF8E7CFF); 3 -> Color(0xFF26C6DA); 4 -> Color(0xFFB56CFF); 6 -> Color(0xFF26C6DA); else -> Color(0xFF3B82F6) }, tint = c == 2)
        }
        // Warp: the grid points; Puppet Warp: the pins (document space before the transform matrix -> screen)
        ed.xform?.takeIf { it.mode != 0 && !cut.playing }?.let { xw ->
            val xr = xw.rev
            val m = xw.mesh
            if (m != null && xr >= 0) {
                if (xw.mode == 1) {
                    val pts = Array(m.rows + 1) { j -> Array(m.cols + 1) { i -> val o = m.idx(i, j); preToScr(xw, Offset(m.verts[o], m.verts[o + 1])) } }
                    for (j in 0..m.rows) for (i in 0..m.cols) {
                        if (i < m.cols) { drawLine(Color.White, pts[j][i], pts[j][i + 1], strokeWidth = 3f); drawLine(Color(0xFF8E7CFF), pts[j][i], pts[j][i + 1], strokeWidth = 1.5f) }
                        if (j < m.rows) { drawLine(Color.White, pts[j][i], pts[j + 1][i], strokeWidth = 3f); drawLine(Color(0xFF8E7CFF), pts[j][i], pts[j + 1][i], strokeWidth = 1.5f) }
                    }
                    for (j in 0..m.rows) for (i in 0..m.cols) { drawCircle(Color.White, 8f, pts[j][i]); drawCircle(Color(0xFF8E7CFF), 5.5f, pts[j][i]) }
                } else {
                    val bb = m.bounds(); val c4 = rectBox(bb, xw.mat).map { toScr(it) }
                    drawPoly(c4, true, Color(0x88FFFFFF), 1.5f)
                    xw.pins.forEachIndexed { pi, p ->
                        val q = preToScr(xw, Offset(p.qx, p.qy))
                        if (p.r > 0f && pi == xw.pinSel) { val rr = (preToScr(xw, Offset(p.qx + p.r, p.qy)) - q).getDistance(); drawCircle(Color(0x33E91E63), rr, q); drawCircle(Color(0xFFE91E63), rr, q, style = Stroke(2f)) } // reach of the selected pin
                        drawCircle(Color.White, 14f, q); drawCircle(Color(0xFFE91E63), 10f, q); drawCircle(if (pi == xw.pinSel) Color(0xFFFFC107) else Color.White, 3.5f, q)
                    }
                }
            }
        }
        // vector Warp: the grid points; vector Puppet Warp: the pins (with the selected pin's reach)
        ed.vecXf?.takeIf { it.mode != 0 && !cut.playing }?.let { xv ->
            val m = xv.mesh
            if (m != null && xv.rev >= 0) {
                if (xv.mode == 1) {
                    val pts = Array(m.rows + 1) { j -> Array(m.cols + 1) { i -> val o = m.idx(i, j); vPreToScr(xv, Offset(m.verts[o], m.verts[o + 1])) } }
                    for (j in 0..m.rows) for (i in 0..m.cols) {
                        if (i < m.cols) { drawLine(Color.White, pts[j][i], pts[j][i + 1], strokeWidth = 3f); drawLine(Color(0xFF8E7CFF), pts[j][i], pts[j][i + 1], strokeWidth = 1.5f) }
                        if (j < m.rows) { drawLine(Color.White, pts[j][i], pts[j + 1][i], strokeWidth = 3f); drawLine(Color(0xFF8E7CFF), pts[j][i], pts[j + 1][i], strokeWidth = 1.5f) }
                    }
                    for (j in 0..m.rows) for (i in 0..m.cols) { drawCircle(Color.White, 8f, pts[j][i]); drawCircle(Color(0xFF8E7CFF), 5.5f, pts[j][i]) }
                } else {
                    drawPoly(rectBox(xv.rect, xv.mat).map { toScr(it) }, true, Color(0x88FFFFFF), 1.5f)
                    xv.pins.forEachIndexed { pi, p ->
                        val q = vPreToScr(xv, Offset(p.qx, p.qy))
                        if (p.r > 0f && pi == xv.pinSel) { val rr = (vPreToScr(xv, Offset(p.qx + p.r, p.qy)) - q).getDistance(); drawCircle(Color(0x33E91E63), rr, q); drawCircle(Color(0xFFE91E63), rr, q, style = Stroke(2f)) }
                        drawCircle(Color.White, 14f, q); drawCircle(Color(0xFFE91E63), 10f, q); drawCircle(if (pi == xv.pinSel) Color(0xFFFFC107) else Color.White, 3.5f, q)
                    }
                }
            }
        }
        // trash widget of a pressed vanishing point
        val pp = cut.layer?.takeIf { it.kind == 3 }?.persp
        if (pp != null && ed.vpSel in pp.vps.indices) {
            val c0 = toScr(pp.pos(pp.vps[ed.vpSel])) + Offset(40f, -40f)
            drawCircle(Color(0xFFD32F2F), 24f, c0)
            translate(c0.x - 13f, c0.y - 13f) { with(delPainter) { draw(Size(26f, 26f), colorFilter = ColorFilter.tint(Color.White)) } }
        }
        // scrubbing feedback at the left / right canvas edge (a purple-to-white gradient that bumps with every frame step);
        // a growing purple pull at the right edge past the last frame = a new frame will be created by the next stroke
        val gw = 70f
        val white = Color.White
        if (glow != 0 && AppSettings.cueEdgeGlow) { val left = glow < 0; drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color.Transparent), startX = if (left) 0f else size.width, endX = if (left) gw else size.width - gw), topLeft = Offset(if (left) 0f else size.width - gw, 0f), size = Size(gw, size.height)) }
        if (AppSettings.cueEdgeGlow && keyBumpAnim.value > 0.01f) {
            val a = keyBumpAnim.value
            val left = bumpDir < 0
            val bw = gw * (1f + 0.6f * a)
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(purple.copy(alpha = 0.85f * a), white.copy(alpha = 0.45f * a), Color.Transparent), startX = if (left) 0f else size.width, endX = if (left) bw else size.width - bw), topLeft = Offset(if (left) 0f else size.width - bw, 0f), size = Size(bw, size.height))
        }
        if (AppSettings.cueNewFramePull && (ed.newFrameArmed || newFramePull > 0f)) {
            val p = if (ed.newFrameArmed) 1f else newFramePull
            val pw = gw * (0.5f + 0.5f * p)
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(purple.copy(alpha = (if (ed.newFrameArmed) 0.85f else 0.15f + 0.5f * p)), Color.Transparent), startX = size.width, endX = size.width - pw), topLeft = Offset(size.width - pw, 0f), size = Size(pw, size.height))
        }
        if (AppSettings.cueTouchRings) fingerTouches.forEach { point ->
            drawCircle(purple.copy(alpha = 0.14f), 22f, point)
            drawCircle(purple.copy(alpha = 0.8f), 22f, point, style = Stroke(2f))
        }
        if (resetCue) {
            drawRect(purple.copy(alpha = 0.10f), size = size)
            drawRect(purple.copy(alpha = 0.9f), size = size, style = Stroke(4f))
        }
    }
        // Pen hover cursor in its own light layer: the pen reports hover hundreds of times a second, and as part of the big canvas above
        // every report re-drew the page, grid and all tiles (diagnostics 2026-11-15: ~20 frames/s of ~10 ms GPU while idle). No pointer input here.
        Canvas(Modifier.fillMaxSize().zIndex(3f)) {
            hover?.takeIf { !cut.playing }?.let { hp ->
                val r = if (ed.tool == 0 || ed.tool == 1 || ed.tool == 4) max(ed.brush.size * fit * ed.vScale / 2f, 2f) else 8f
                if (AppSettings.cueHoverCursor) { // the Gap fill / Grow read-out below is working feedback, not a cue: it stays
                    drawCircle(Color.White, r + 1f, hp, style = Stroke(3f))
                    drawCircle(Color.Black, r, hp, style = Stroke(1.5f))
                }
                if (thr >= 0) drawIntoCanvas { it.nativeCanvas.drawText("Gap fill ${thr}px   Grow ${st.grow}px", hp.x + 30f, hp.y - 30f, textPaint) }
            }
        }
        // Shimmering selection outline ("marching ants") in its own light layer: animating it must not redraw the page and every tile.
        val shimmer = remember { mutableFloatStateOf(0f) }
        val antsWhite = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = android.graphics.Color.WHITE } }
        val antsBlack = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = android.graphics.Color.BLACK } }
        LaunchedEffect(ed.sel != null) { while (ed.sel != null) { kotlinx.coroutines.delay(70); shimmer.floatValue = (shimmer.floatValue + 1f) % 10f } }
        LaunchedEffect(ed.sel) { // the outline is traced once per selection, off the UI thread
            val sl = ed.sel ?: return@LaunchedEffect
            if (sl.outlineState != 0) return@LaunchedEffect
            sl.outlineState = 1
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { sl.outline = runCatching { buildOutline(sl) }.getOrNull() }
            sl.outlineState = 2
        }
        Canvas(Modifier.fillMaxSize().zIndex(3f)) {
            val sl = ed.sel ?: return@Canvas
            val ph = shimmer.floatValue
            val xw = ed.xform
            if (xw != null && xw.mesh != null) return@Canvas // deformed: the grid points / pins show the shape
            val path = sl.outline ?: return@Canvas
            if (cut.playing) return@Canvas
            withTransform({ base() }) {
                drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    var z = 1f / (fit * ed.vScale).coerceAtLeast(0.0001f)
                    nc.save()
                    val gl = cut.layer
                    if (gl != null && gl.group >= 0 && xw == null) {
                        val tr = cut.transformFor(gl, cut.time)
                        nc.translate(dw / 2f + tr.x, dh / 2f + tr.y); nc.rotate(tr.rotation); nc.scale(tr.scale, tr.scale); nc.translate(-dw / 2f, -dh / 2f)
                        z /= tr.scale.coerceAtLeast(0.0001f)
                    }
                    if (xw != null) {
                        nc.concat(xw.mat)
                        val v = FloatArray(9); xw.mat.getValues(v)
                        z /= kotlin.math.sqrt(kotlin.math.abs(v[0] * v[4] - v[1] * v[3])).coerceAtLeast(0.0001f)
                    }
                    antsWhite.strokeWidth = 2.2f * z
                    antsBlack.strokeWidth = 2.2f * z
                    antsBlack.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * z, 5f * z), ph * z)
                    nc.drawPath(path, antsWhite); nc.drawPath(path, antsBlack)
                    nc.restore()
                }
            }
        }
        // Ghost inset: while the ghost line is partly or fully outside the visible canvas (zoom / pan), a small window in the
        // upper left frames the whole ghost line (same orientation as the canvas) with its anchors and the visible area.
        ghostData?.shown?.takeIf { box.width > 0 && box.height > 0 }?.let { ghostLine ->
            val gScr = ghostLine.points.map { toScr(Offset(it.x, it.y)) }
            val edge = 3f
            val cropped = gScr.any { it.x < edge || it.y < edge || it.x > box.width - edge || it.y > box.height - edge }
            if (cropped) {
                val minX = gScr.minOf { it.x }; val maxX = gScr.maxOf { it.x }
                val minY = gScr.minOf { it.y }; val maxY = gScr.maxOf { it.y }
                val bw = (maxX - minX).coerceAtLeast(1f); val bh = (maxY - minY).coerceAtLeast(1f)
                val asp = (bw / bh).coerceIn(0.4f, 2.5f)
                val wDp = if (asp >= 1f) 170f else max(90f, 170f * asp)
                val hDp = if (asp >= 1f) max(90f, 170f / asp) else 170f
                val anchorMax = ghostLine.points.maxOfOrNull { it.anchor } ?: 0
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                Canvas(
                    Modifier.align(Alignment.TopStart).zIndex(3f).padding(10.dp).size(wDp.dp, hDp.dp)
                        .background(Color(0xE6111827), shape)
                        .border(1.dp, Color(0x6622D3EE), shape)
                        .clipToBounds()
                ) {
                    val pad = 12.dp.toPx()
                    val k = min((size.width - 2f * pad) / bw, (size.height - 2f * pad) / bh)
                    val ox = (size.width - bw * k) / 2f - minX * k
                    val oy = (size.height - bh * k) / 2f - minY * k
                    fun m(p: Offset) = Offset(p.x * k + ox, p.y * k + oy)
                    // the part of the page currently visible on the canvas
                    val v0 = m(Offset(0f, 0f))
                    drawRect(Color(0xFFFFC107), topLeft = v0, size = Size(box.width * k, box.height * k), style = Stroke(1.5.dp.toPx()))
                    // the ghost line
                    val path = Path()
                    gScr.forEachIndexed { i, q -> val mp = m(q); if (i == 0) path.moveTo(mp.x, mp.y) else path.lineTo(mp.x, mp.y) }
                    if (ghostLine.isClosed) path.close()
                    drawPath(path, Color(0xFF22D3EE), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val np = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE }
                    ghostLine.points.forEachIndexed { i, vp ->
                        if (vp.anchor > 0) {
                            val mp = m(gScr[i])
                            drawCircle(Color.White, 5.dp.toPx(), mp)
                            drawCircle(Color(anchorColor(vp.anchor, anchorMax)), 3.5.dp.toPx(), mp)
                            drawIntoCanvas { cv -> cv.nativeCanvas.drawText(vp.anchor.toString(), mp.x + 6.dp.toPx(), mp.y - 5.dp.toPx(), np) }
                        }
                    }
                }
            }
        }
        // The bar shows only the selected guide. Guide settings remain independent in Persp.
        val selectedPerspective = cut.layer?.takeIf { it.kind == 3 && it.visible && !cut.playing }?.persp
        if (selectedPerspective != null) {
            Column(Modifier.align(Alignment.TopStart).zIndex(3f).padding(start = 10.dp, top = 6.dp).background(Color(0xDD202124), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(
                    if (activePerspectiveGuide == 0) "HORIZON" else "PERPENDICULAR",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
                val kind = activePerspectiveGuide
                val g = selectedPerspective.guide(kind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PerspectiveIconButton(if (g.rotationUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock, if (g.rotationUnlocked) "Lock rotation" else "Unlock rotation about this guide's vanishing point", g.rotationUnlocked, { perspectiveHover = it }, { cut.record { g.rotationUnlocked = !g.rotationUnlocked } })
                    PerspectiveIconButton(Icons.Filled.RotateRight, if (g.rotationSnap30) "30-degree rotation lock is on" else "30-degree rotation lock is off; rotation is freeform", g.rotationSnap30, { perspectiveHover = it }, { cut.record { g.rotationSnap30 = !g.rotationSnap30 } })
                    PerspectiveIconButton(if (g.lockVpDistance) Icons.Filled.Lock else Icons.Filled.LockOpen, "Lock vanishing-point spacing", g.lockVpDistance, { perspectiveHover = it }, { cut.record { g.lockVpDistance = !g.lockVpDistance } })
                    PerspectiveIconButton(Icons.Filled.CameraAlt, "Camera horizontal field of view ${selectedPerspective.fovDegrees.roundToInt()} degrees; ${selectedPerspective.focalLengthMm.roundToInt()} mm equivalent", false, { perspectiveHover = it }, { fovValue = selectedPerspective.fovDegrees; fovText = "%.1f".format(java.util.Locale.US, fovValue); focalValue = selectedPerspective.focalLengthMm; focalText = "%.1f".format(java.util.Locale.US, focalValue); fovDialog = kind })
                    PerspectiveIconButton(if (selectedPerspective.vanishingPointMagnet) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (selectedPerspective.vanishingPointMagnet) "Vanishing-point magnetism on; VP rays override the horizon and perpendicular guides" else "Guide-line magnetism on; horizon and perpendicular lines override vanishing points", selectedPerspective.vanishingPointMagnet, { perspectiveHover = it }, { cut.record { selectedPerspective.vanishingPointMagnet = !selectedPerspective.vanishingPointMagnet } })
                    PerspectiveIconButton(Icons.Filled.Waves, "Curvilinear fisheye perspective", g.curvilinear, { perspectiveHover = it }, { cut.record { g.curvilinear = !g.curvilinear } })
                    PerspectiveIconButton(Icons.Filled.Tune, "Set curvilinear curvature strength", false, { perspectiveHover = it }, { curvatureStrengthValue = g.curvatureStrength; curvatureDialog = kind }, enabled = g.curvilinear)
                    PerspectiveIconButton(Icons.Filled.Add, "Set the number of curvilinear rays", false, { perspectiveHover = it }, { rayCountValue = g.curvilinearRayCount.toFloat(); raysDialog = kind }, enabled = g.curvilinear)
                    PerspectiveIconButton(Icons.Filled.Refresh, "Reset this guide", false, { perspectiveHover = it }, {
                        cut.record {
                            selectedPerspective.rotateGuide(kind, -g.rotation, ed.vpSel)
                            g.rotationUnlocked = false; g.rotationSnap30 = false; g.lockVpDistance = false; g.curvilinear = false; g.curvatureStrength = 1f; g.curvilinearRayCount = 24
                        }
                    })
                }
                // Keep a fixed-height hover label slot so showing/hiding text cannot move the buttons.
                Box(Modifier.fillMaxWidth().height(16.dp)) {
                    if (perspectiveHover.isNotBlank()) Text(perspectiveHover, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (fovDialog >= 0 && selectedPerspective != null) {
            AlertDialog(
                onDismissRequest = { fovDialog = -1 },
                title = { Text("Camera lens") },
                text = { Column {
                    NumberField(fovText, { value -> value.toFloatOrNull()?.let { f -> fovValue = f.coerceIn(4.1f, 170f); fovText = "%.1f".format(java.util.Locale.US, fovValue); focalValue = focalLengthForFov(fovValue); focalText = "%.1f".format(java.util.Locale.US, focalValue) } }, Modifier.fillMaxWidth(), label = "Horizontal FOV (degrees)", min = 4.1, max = 170.0)
                    Slider(value = fovValue, onValueChange = { fovValue = it; fovText = "%.1f".format(java.util.Locale.US, it); focalValue = focalLengthForFov(it); focalText = "%.1f".format(java.util.Locale.US, focalValue) }, valueRange = 4.1f..170f)
                    NumberField(focalText, { value -> value.toFloatOrNull()?.let { mm -> focalValue = mm.coerceIn(1.57f, 500f); fovValue = fovFromFocalLength(focalValue); fovText = "%.1f".format(java.util.Locale.US, fovValue); focalText = "%.1f".format(java.util.Locale.US, focalValue) } }, Modifier.fillMaxWidth(), label = "Focal length (mm)", min = 1.57, max = 500.0)
                    Text("Linked camera values for a 36 mm full-frame sensor. Changing focal length also scales vanishing-point offsets around the optical centre: longer focal lengths push them outward; shorter focal lengths pull them inward. The blue rays show the horizontal camera view cone.", fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = { val mm = focalText.toFloatOrNull()?.coerceIn(1.57f, 500f) ?: focalValue; cut.record { selectedPerspective.setFocalLength(mm) }; fovDialog = -1 }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { fovDialog = -1 }) { Text("Cancel") } }
            )
        }
        if (curvatureDialog >= 0 && selectedPerspective != null) {
            val kind = curvatureDialog
            AlertDialog(
                onDismissRequest = { curvatureDialog = -1 },
                title = { Text("Curvilinear strength") },
                text = { Column {
                    Text("${(curvatureStrengthValue * 100f).roundToInt()}%", fontSize = 16.sp)
                    Slider(value = curvatureStrengthValue, onValueChange = { curvatureStrengthValue = it }, valueRange = 0f..2f, steps = 19)
                    Text("Controls how strongly rays bow away from the guide. The guide axis and horizon stay straight; rays parallel to it remain straight.", fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = { val strength = curvatureStrengthValue; cut.record { selectedPerspective.guide(kind).curvatureStrength = strength }; curvatureDialog = -1 }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { curvatureDialog = -1 }) { Text("Cancel") } }
            )
        }
        if (raysDialog >= 0 && selectedPerspective != null) {
            val kind = raysDialog
            AlertDialog(
                onDismissRequest = { raysDialog = -1 },
                title = { Text("Curvilinear ray count") },
                text = { Column {
                    Text("${rayCountValue.roundToInt().coerceIn(8, 48)} rays per neighbouring VP pair", fontSize = 16.sp)
                    Slider(value = rayCountValue, onValueChange = { rayCountValue = it }, valueRange = 8f..48f, steps = 19)
                    Text("Each curved ray joins neighbouring vanishing points. The central horizon paths stay perfectly flat; rays bow progressively more as they move farther from the horizon.", fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = { val count = (rayCountValue.roundToInt().coerceIn(8, 48) / 2) * 2; cut.record { selectedPerspective.guide(kind).curvilinearRayCount = count }; raysDialog = -1 }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { raysDialog = -1 }) { Text("Cancel") } }
            )
        }
        if (vecGaps.isNotEmpty() && !cut.playing) VecGapShimmer(vecGaps, base, 1f / (fit * ed.vScale), Modifier.fillMaxSize().zIndex(3f))
        // Vector widget bar: bottom centre, tied to the active vector tool (point edit / selection, interpolation drag)
        if (!cut.playing) VectorWidgetBar(cut, ed, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp).zIndex(5f))
        if (!cut.playing) VectorInterpolationHost(cut, ed)
        if (!cut.playing) VectorFillBar(cut, ed, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).zIndex(5f))
        if (!cut.playing) VectorInkBar(cut, ed, Modifier.align(if (AppSettings.leftHanded) Alignment.BottomEnd else Alignment.BottomStart).padding(start = if (AppSettings.leftHanded) 0.dp else 14.dp, end = if (AppSettings.leftHanded) 14.dp else 0.dp, bottom = 14.dp).zIndex(6f))
        if (!cut.playing) VectorWobbleBar(cut, ed, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).zIndex(6f))
        ClipboardBar(ed, cut, Modifier.align(if (AppSettings.leftHanded) Alignment.BottomStart else Alignment.BottomEnd).padding(start = if (AppSettings.leftHanded) 14.dp else 0.dp, end = if (AppSettings.leftHanded) 0.dp else 14.dp, bottom = 14.dp).zIndex(5f))
        // Selection widget bar: follows the selection (and its transform) instead of staying at the top of the screen
        if ((ed.sel != null || ed.xform != null) && !(ed.outOfPeg && ed.outOfPegSide != 0) && !cut.playing) {
            SelectionBar(ed, cut) {
                val xw = ed.xform
                if (xw != null) { xw.rev; rectBox(xw.contentBounds(), xw.mat).map { toScr(it) } }
                else selRect?.let { r -> rectBox(r).map { toScr(it) } }
            }
        }
    }
}

/** What the line ghost shows: the pinned (or first untraced) line [shown] that shimmers, the other untraced lines [open], and the traced ones [claimed]; [shownClaimed] = the shimmering line is already traced. */
class GhostView(val shown: VectorStroke?, val open: List<VectorStroke>, val claimed: List<VectorStroke>, val shownClaimed: Boolean)
