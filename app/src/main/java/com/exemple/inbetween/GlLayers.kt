package com.example.inbetween

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.zIndex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * GPU layer compositor.
 *
 * The layers BELOW the active layer (and, during playback, every drawing layer) are static while you draw, so they are
 * composited by OpenGL ES into a plane that sits between the page / video and the Compose drawing Canvas. Compose keeps
 * drawing the active layer, the layers above it, the live stroke and every overlay, so pen latency and tool behaviour are
 * unchanged. Onion skins are drawn here too (they sit under all layers).
 *
 * How a frame reaches the screen: the GL thread renders into an ImageReader surface; Compose receives each finished frame as a
 * hardware Bitmap TOGETHER WITH the viewport it was rendered with and draws it through the transform "viewport of the frame ->
 * viewport now" in its own draw pass. So the plane always moves in the same frame as the Canvas (a pan / rotate / zoom is
 * exact even before the next GL frame arrives); GL only supplies the pixels. A SurfaceView or TextureView cannot do this: both
 * are presented a frame behind the Canvas and the viewport baked into them trails the ink.
 *
 * Fallback: if EGL / GL fails, the device is older than Android 10, or the switch in Cut settings is off, `gpuPlan` returns -1
 * and Compose draws everything exactly as before.
 */
object GpuLayers {
    /** Cut settings switch. */
    var enabled by mutableStateOf(true)
    /** Set when EGL / GL setup or rendering failed: the Compose path is used for the rest of the session. */
    var failed by mutableStateOf(false)
    /** The plane has delivered a frame to Compose. */
    var ready by mutableStateOf(false)
    /** How many leading layers the last presented frame contained (-1 = nothing). Compose only skips layers the plane shows. */
    var shownK by mutableIntStateOf(-1)
    /** The last presented frame contained onion skins. */
    var shownOnion by mutableStateOf(false)
}

/** Number of leading cut.layers drawn by the GPU compositor, or -1 when Compose draws everything. */
fun gpuPlan(cut: Cut, ed: Editor): Int {
    if (!GpuLayers.enabled || GpuLayers.failed || !GpuLayers.ready) return -1
    if (cut.layers.any { it.kind == 5 && it.visible }) return -1 // reference images stay on the Compose path
    // Playback uses the Compose path: every GL frame is wrapped as a multi-megabyte hardware Bitmap, which on the SM-P610 caused
    // ~4 GCs per second (HeapTaskDaemon ~33% of a core), +50 points of process CPU, timeline-tick stalls and slower
    // frames than the Compose path (profiler scenario F, compositor on vs off, 2026-11-13). Editing still uses the plane.
    if (cut.playing) return -1
    val active = cut.layer
    val k = if (active == null) 0 else max(0, cut.layers.indexOf(active))
    val onion = ed.onion
    return if (k > 0 || onion) k else -1
}

// ---------------------------------------------------------------------------------------------------------------------
// Scene description (built on the main thread, read on the GL thread)
// ---------------------------------------------------------------------------------------------------------------------

/** One cel's tiles and its affine transform. Scalar fields avoid allocating a FloatArray for every layer on every playback tick. */
class GlItem(
    val set: TileSet, val alpha: Float,
    val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float,
    val clipLocal: Boolean, val tint: Int
)

class GlScene(val items: List<GlItem>, val clipPage: Boolean, val pageW: Float, val pageH: Float, val k: Int, val onion: Boolean) {
    companion object { val EMPTY = GlScene(emptyList(), false, 0f, 0f, -1, false) }
}

/** Document coordinates to workspace screen pixels: sx = a*px + b*py + tx, sy = c*px + d*py + ty (the Canvas viewport). */
class ScreenView(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float)

/** What one presented frame contains: the viewport it was rendered with and which scene content it shows. */
class FrameInfo(val seq: Long, val view: ScreenView, val k: Int, val onion: Boolean)

/** A frame delivered to Compose. `image` must stay open while `bitmap` can still be drawn. */
class PlaneFrame(val bitmap: Bitmap, val info: FrameInfo, val image: Image, val margin: Int)

private const val TINT_PREVIOUS = 1
private const val TINT_NEXT = 2

/** Adds one scene item without temporary GroupTransform / FloatArray objects. */
private fun addGlItem(
    items: MutableList<GlItem>, set: TileSet, alpha: Float, clipPage: Boolean, tint: Int,
    folder: Layer?, tick: Int, pageW: Float, pageH: Float,
    offsetX: Float = 0f, offsetY: Float = 0f, rotationOffset: Float = 0f,
    scaleX: Float = 1f, scaleY: Float = 1f
) {
    val folderX = if (folder != null) evalChan(folder.transformX, tick, 0f) else 0f
    val folderY = if (folder != null) evalChan(folder.transformY, tick, 0f) else 0f
    val folderScale = if (folder != null) evalChan(folder.transformScale, tick, 1f) else 1f
    val folderRotation = if (folder != null) evalChan(folder.transformRotation, tick, 0f) else 0f
    val sx = folderScale * scaleX
    val sy = folderScale * scaleY
    val tx = folderX + offsetX
    val ty = folderY + offsetY
    val rotation = folderRotation + rotationOffset
    if (folder == null && offsetX == 0f && offsetY == 0f && rotationOffset == 0f && scaleX == 1f && scaleY == 1f) {
        items.add(GlItem(set, alpha, 1f, 0f, 0f, 1f, 0f, 0f, clipPage, tint))
        return
    }
    val r = Math.toRadians(rotation.toDouble())
    val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
    val a = cs * sx; val b = -sn * sy; val c = sn * sx; val d = cs * sy
    val cx = pageW / 2f; val cy = pageH / 2f
    val outX = cx + tx - (a * cx + b * cy)
    val outY = cy + ty - (c * cx + d * cy)
    items.add(GlItem(set, alpha, a, b, c, d, outX, outY, clipPage, tint))
}

/** Mirrors the layer / onion passes of DrawingCanvas for layers [0, k). Reads snapshot state, so call it inside snapshotFlow. */
fun buildGlScene(cut: Cut, ed: Editor, k: Int, onionStrength: Float): GlScene {
    if (k < 0) return GlScene.EMPTY
    val w = cut.w.toFloat(); val h = cut.h.toFloat()
    // Most scenes contain at most one item per visible layer plus two onion items per layer.
    // Capacity avoids repeated backing-array growth while keeping the list local to this immutable scene.
    val items = ArrayList<GlItem>(cut.layers.size * if (ed.onion && !cut.playing) 3 else 1)
    val clipPage = !ed.drawOutsideFrame
    val onionA = (77 * onionStrength).toInt()
    var onionUsed = false
    // Neighbouring drawings are prepared off the UI thread (OnionWarmup); reading this makes the scene rebuild when one is ready.
    @Suppress("UNUSED_VARIABLE") val warmupTick = OnionWarmup.ready.intValue
    if (ed.onion && !cut.playing && onionA > 0) {
        for (l in cut.layers) {
            if (!l.visible || l.kind == 5) continue
            val i = l.indexAt(cut.time)
            val pi = if (i < 0) l.cels.size - 1 else i - 1
            val ni = if (i < 0) -1 else i + 1
            val previous = l.cels.getOrNull(pi)
            if (previous != null && previous.strokes.isNotEmpty()) {
                val sourceTick = l.startOf(pi)
                val folder = if (l.group >= 0) cut.layerById(l.group)?.takeIf { it.isGroup && it.transformFolder } else null
                val pose = if (ed.outOfPeg) ed.referencePose(previous) else null
                val previousSet = TileCache.peekReady(previous.strokes) ?: run { OnionWarmup.request(previous.strokes); null }
                if (previousSet != null) addGlItem(items, previousSet, onionA / 255f, clipPage, TINT_PREVIOUS,
                    folder, sourceTick, w, h,
                    pose?.x ?: 0f, pose?.y ?: 0f, pose?.rotation ?: 0f,
                    (pose?.scaleX ?: 1f) * (if (pose?.flipX == true) -1f else 1f),
                    (pose?.scaleY ?: 1f) * (if (pose?.flipY == true) -1f else 1f))
                onionUsed = true
            }
            val following = l.cels.getOrNull(ni)
            if (following != null && following.strokes.isNotEmpty()) {
                val sourceTick = l.startOf(ni)
                val folder = if (l.group >= 0) cut.layerById(l.group)?.takeIf { it.isGroup && it.transformFolder } else null
                val pose = if (ed.outOfPeg) ed.referencePose(following) else null
                val followingSet = TileCache.peekReady(following.strokes) ?: run { OnionWarmup.request(following.strokes); null }
                if (followingSet != null) addGlItem(items, followingSet, onionA / 255f, clipPage, TINT_NEXT,
                    folder, sourceTick, w, h,
                    pose?.x ?: 0f, pose?.y ?: 0f, pose?.rotation ?: 0f,
                    (pose?.scaleX ?: 1f) * (if (pose?.flipX == true) -1f else 1f),
                    (pose?.scaleY ?: 1f) * (if (pose?.flipY == true) -1f else 1f))
                onionUsed = true
            }
        }
    }
    for ((li, l) in cut.layers.withIndex()) {
        if (li >= k) break
        if (!l.visible || l.kind == 7 || l.kind == 5) continue
        val strokes = l.drawCel(cut.time)?.strokes ?: continue
        if (strokes.isEmpty()) continue
        val folder = if (l.group >= 0) cut.layerById(l.group)?.takeIf { it.isGroup && it.transformFolder } else null
        val set = TileCache.getForDraw(strokes) ?: continue // built in the background; the scene is rebuilt when it is ready (OnionWarmup.ready)
        addGlItem(items, set, (l.opacity * 255).toInt() / 255f, clipPage, 0,
            folder, cut.time, w, h)
    }
    return GlScene(items, clipPage, w, h, k, onionUsed || (ed.onion && !cut.playing))
}

/** The same viewport transform as DrawingCanvas.base(), expressed document -> workspace screen pixels. */
fun screenView(ed: Editor, cutW: Int, cutH: Int, boxW: Int, boxH: Int, fit: Float): ScreenView {
    val rot = Math.toRadians(ed.vRot.toDouble())
    val cs = cos(rot).toFloat(); val sn = sin(rot).toFloat()
    val scale = fit * ed.vScale
    val fx = if (ed.flipH) -1f else 1f; val fy = if (ed.flipV) -1f else 1f
    val pcx = cutW / 2f; val pcy = cutH / 2f
    val sa = cs * scale * fx; val sb = -sn * scale * fy
    val sd = sn * scale * fx; val se = cs * scale * fy
    val sc = boxW / 2f + ed.vOff.x - (sa * pcx + sb * pcy)
    val sf = boxH / 2f + ed.vOff.y - (sd * pcx + se * pcy)
    return ScreenView(sa, sb, sd, se, sc, sf)
}

// ---------------------------------------------------------------------------------------------------------------------
// GL thread
// ---------------------------------------------------------------------------------------------------------------------

private const val TILE_BUDGET = 128 // texture tiles (1 MB each) kept on the GPU

private class TileTex(val tx: Int, val ty: Int, val tex: Int)

private class SetTex {
    var version = -1L
    var lastUse = 0L
    val tiles = ArrayList<TileTex>()
}

private const val VERTEX_SHADER = """
attribute vec2 aPos;
uniform vec2 uTileOrigin;
uniform float uTileSize;
uniform vec4 uLayerAB;
uniform vec2 uLayerT;
uniform vec4 uViewAB;
uniform vec2 uViewT;
varying vec2 vTex;
varying vec2 vLocal;
varying vec2 vPage;
void main() {
    vec2 local = uTileOrigin + aPos * uTileSize;
    vec2 page = vec2(uLayerAB.x * local.x + uLayerAB.y * local.y + uLayerT.x,
                     uLayerAB.z * local.x + uLayerAB.w * local.y + uLayerT.y);
    vec2 ndc = vec2(uViewAB.x * page.x + uViewAB.y * page.y + uViewT.x,
                    uViewAB.z * page.x + uViewAB.w * page.y + uViewT.y);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vTex = aPos;
    vLocal = local;
    vPage = page;
}
"""

private const val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform float uAlpha;
uniform vec4 uTint;
uniform float uClipLocalOn;
uniform float uClipPageOn;
uniform vec4 uClip;
varying vec2 vTex;
varying vec2 vLocal;
varying vec2 vPage;
void main() {
    if (uClipLocalOn > 0.5 && (vLocal.x < uClip.x || vLocal.y < uClip.y || vLocal.x > uClip.z || vLocal.y > uClip.w)) discard;
    if (uClipPageOn > 0.5 && (vPage.x < uClip.x || vPage.y < uClip.y || vPage.x > uClip.z || vPage.y > uClip.w)) discard;
    vec4 c = texture2D(uTex, vTex);
    if (uTint.w > 0.5) c = vec4(uTint.rgb * c.a, c.a);
    gl_FragColor = c * uAlpha;
}
"""

/**
 * Renders the scene on its own GL thread into an ImageReader surface (a ring of GPU buffers) and hands every finished frame to
 * Compose as a hardware Bitmap together with the viewport it was rendered with. Compose draws it through the transform
 * "viewport of the frame -> viewport now", so the plane moves in the same frame as the Canvas (no presentation lag, nothing to
 * correlate) and is only a little soft for the frame or two between a gesture and its re-render. The surface is larger than the
 * workspace by `margin` pixels on every side so the plane still covers the screen while it is slightly stale.
 */
class GlLayerRenderer(private val boxW: Int, private val boxH: Int, private val onFrame: (PlaneFrame) -> Unit) {
    val margin = max(16, (max(boxW, boxH) * 0.10f).toInt())
    private val surfW = boxW + 2 * margin
    private val surfH = boxH + 2 * margin

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val main = Handler(Looper.getMainLooper())

    // guarded by lock
    private var running = true
    private var dirty = false

    @Volatile private var scene: GlScene = GlScene.EMPTY
    @Volatile private var view: ScreenView? = null
    private val infos = java.util.concurrent.ConcurrentHashMap<Long, FrameInfo>()
    private var reader: android.media.ImageReader? = null

    private val thread = Thread({ loop() }, "InbetweenGlLayers").also { it.isDaemon = true }

    fun setScene(s: GlScene) { scene = s; requestRender() }
    fun setView(v: ScreenView) { view = v; requestRender() }

    fun requestRender() { Perf.glRequest(); lock.withLock { dirty = true; cond.signalAll() } }

    fun release() {
        lock.withLock { running = false; cond.signalAll() }
        try { thread.join(500) } catch (_: InterruptedException) {}
        val r = reader
        reader = null
        if (r != null) runCatching { r.setOnImageAvailableListener(null, null); r.close() }
    }

    // ----- GL thread state -----

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0
    private var uTileOrigin = 0; private var uTileSize = 0; private var uLayerAB = 0; private var uLayerT = 0
    private var uViewAB = 0; private var uViewT = 0; private var uTex = 0; private var uAlpha = 0; private var uTint = 0
    private var uClipLocalOn = 0; private var uClipPageOn = 0; private var uClip = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }
    private val sets = HashMap<TileSet, SetTex>()
    // GLES texture APIs accept an offset into an IntArray; reuse one slot on this GL thread.
    private val textureIdScratch = IntArray(1)
    private var gpuTileCount = 0
    private var frameId = 0L
    private var seq = 0L
    private var swapFailures = 0
    private var perfPrepNs = 0L // profiler: time spent in ensure() (texture upload / tile preparation) during the current render()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && surfW <= 8192 && surfH <= 8192) {
            try {
                val r = android.media.ImageReader.newInstance(
                    surfW, surfH, PixelFormat.RGBA_8888, 5,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
                r.setOnImageAvailableListener({ rd -> handleImage(rd) }, main)
                reader = r
                thread.start() // last step: the GL thread reads the fields above
            } catch (t: Throwable) {
                fail("ImageReader", t)
            }
        } else {
            fail("needs Android 10+ and a surface up to 8192 px")
        }
    }

    private fun fail(reason: String, t: Throwable? = null) {
        Log.e("GlLayers", "GPU layer compositor disabled: $reason", t)
        runCatching { CrashDiagnostics.log("WARN", "GPU layer compositor disabled: $reason ${t?.message ?: ""}") }
        Perf.glFailure(reason)
        main.post { GpuLayers.failed = true; GpuLayers.ready = false; GpuLayers.shownK = -1; GpuLayers.shownOnion = false }
    }

    /** Main thread: a frame was queued. Pair it with the viewport it was rendered with and give it to Compose. */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun handleImage(rd: android.media.ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val info = infos.remove(img.timestamp) ?: infos.values.maxByOrNull { it.seq }
        val hb = img.hardwareBuffer
        val bmp = if (hb != null) {
            try { Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) } catch (e: Exception) { null }
        } else null
        if (bmp == null || info == null) { img.close(); fail("frame wrap"); return }
        val keys = infos.keys.iterator()
        while (keys.hasNext()) if (keys.next() <= info.seq) keys.remove()
        onFrame(PlaneFrame(bmp, info, img, margin))
    }

    private fun loop() {
        try {
            if (!initEgl()) { fail("EGL init"); return }
            val surf = reader?.surface ?: run { fail("no surface"); return }
            val es = EGL14.eglCreateWindowSurface(dpy, cfg, surf, intArrayOf(EGL14.EGL_NONE), 0)
            if (es == null || es == EGL14.EGL_NO_SURFACE) { fail("eglCreateWindowSurface"); return }
            eglSurface = es
            if (!EGL14.eglMakeCurrent(dpy, es, es, ctx)) { fail("eglMakeCurrent"); return }
            EGL14.eglSwapInterval(dpy, 0)
            if (!initGl()) { fail("GL init"); return }
            while (true) {
                lock.withLock {
                    while (running && !dirty) cond.await()
                    if (!running) return
                    dirty = false
                }
                val v = view ?: continue
                val prof = Perf.active // profiler timings are only taken while the profiler is on
                val t0 = if (prof) System.nanoTime() else 0L
                perfPrepNs = 0L
                val drawn = render(v)
                val t1 = if (prof) System.nanoTime() else 0L
                if (prof) { val err = GLES20.glGetError(); if (err != GLES20.GL_NO_ERROR) Perf.glError(err) }
                seq++
                val mySeq = seq
                infos[mySeq] = FrameInfo(mySeq, v, drawn.k, drawn.onion)
                EGLExt.eglPresentationTimeANDROID(dpy, eglSurface, mySeq) // read back as Image.timestamp to pair frame and viewport
                val t2 = if (prof) System.nanoTime() else 0L
                // Do not call glFinish here. eglSwapBuffers queues the producer buffer with the
                // driver's acquire fence; ImageReader only exposes it when GPU work is complete.
                // The explicit wait serialized CPU and GPU and is unnecessary for this buffer path.
                val t3 = t2
                val swapped = EGL14.eglSwapBuffers(dpy, eglSurface)
                if (prof) Perf.glFrame(perfPrepNs, (t1 - t0) - perfPrepNs, 0L, System.nanoTime() - t3, swapped)
                if (swapped) {
                    swapFailures = 0
                } else {
                    infos.remove(mySeq)
                    if (++swapFailures > 3) { fail("eglSwapBuffers"); return }
                }
            }
        } catch (t: Throwable) {
            fail("render thread", t)
        } finally {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurface)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx) // frees the textures with it
                EGL14.eglTerminate(dpy)
            }
        }
    }

    private fun initEgl(): Boolean {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return false
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, n, 0) || n[0] < 1) return false
        cfg = configs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        return ctx != EGL14.EGL_NO_CONTEXT
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e("GlLayers", GLES20.glGetShaderInfoLog(s)); GLES20.glDeleteShader(s); return 0 }
        return s
    }

    private fun initGl(): Boolean {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) return false
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) return false
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTileOrigin = GLES20.glGetUniformLocation(program, "uTileOrigin")
        uTileSize = GLES20.glGetUniformLocation(program, "uTileSize")
        uLayerAB = GLES20.glGetUniformLocation(program, "uLayerAB")
        uLayerT = GLES20.glGetUniformLocation(program, "uLayerT")
        uViewAB = GLES20.glGetUniformLocation(program, "uViewAB")
        uViewT = GLES20.glGetUniformLocation(program, "uViewT")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uClipLocalOn = GLES20.glGetUniformLocation(program, "uClipLocalOn")
        uClipPageOn = GLES20.glGetUniformLocation(program, "uClipPageOn")
        uClip = GLES20.glGetUniformLocation(program, "uClip")
        return true
    }

    /** Deletes one texture without allocating a one-element IntArray for each deletion. */
    private fun deleteTexture(id: Int) {
        textureIdScratch[0] = id
        GLES20.glDeleteTextures(1, textureIdScratch, 0)
    }

    /** Uploads the tiles of a set when its pixels changed since the last upload. */
    private fun ensure(set: TileSet): SetTex {
        val e = sets.getOrPut(set) { SetTex() }
        e.lastUse = frameId
        val v = set.version
        if (e.version != v) {
            for (t in e.tiles) deleteTexture(t.tex)
            gpuTileCount -= e.tiles.size
            e.tiles.clear()
            set.forEachTile { tx, ty, bmp ->
                GLES20.glGenTextures(1, textureIdScratch, 0)
                val id = textureIdScratch[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                e.tiles.add(TileTex(tx, ty, id))
                gpuTileCount++
            }
            e.version = v
        }
        return e
    }

    /**
     * Enforces the texture budget without sortedBy()/temporary sorted-entry lists.
     * This path can run on every rendered frame when the project exceeds the budget.
     */
    private fun evict() {
        while (gpuTileCount > TILE_BUDGET) {
            var oldestKey: TileSet? = null
            var oldestEntry: SetTex? = null
            for ((key, value) in sets) {
                if (value.lastUse == frameId) continue // never evict a set used by this frame
                if (oldestEntry == null || value.lastUse < oldestEntry.lastUse) {
                    oldestKey = key
                    oldestEntry = value
                }
            }
            val victim = oldestEntry ?: break
            val victimKey = oldestKey ?: break
            for (tile in victim.tiles) deleteTexture(tile.tex)
            gpuTileCount -= victim.tiles.size
            sets.remove(victimKey)
        }
    }

    /** Draws one frame with viewport [sv] and returns the scene that is now in it. */
    private fun render(sv: ScreenView): GlScene {
        frameId++
        val sc = scene
        GLES20.glViewport(0, 0, surfW, surfH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (sc.items.isEmpty()) return sc
        // workspace pixels -> NDC of the margin-extended surface
        val w = surfW.toFloat(); val h = surfH.toFloat(); val m = margin.toFloat()
        val va = 2f * sv.a / w; val vb = 2f * sv.b / w; val vtx = 2f * (sv.tx + m) / w - 1f
        val vc = -2f * sv.c / h; val vd = -2f * sv.d / h; val vty = 1f - 2f * (sv.ty + m) / h
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // tiles are premultiplied
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(uTileSize, TileCache.T.toFloat())
        GLES20.glUniform4f(uViewAB, va, vb, vc, vd)
        GLES20.glUniform2f(uViewT, vtx, vty)
        GLES20.glUniform4f(uClip, 0f, 0f, sc.pageW, sc.pageH)
        GLES20.glUniform1f(uClipPageOn, if (sc.clipPage) 1f else 0f)
        val size = TileCache.T.toFloat()
        for (item in sc.items) {
            val e = if (Perf.active) { val p0 = System.nanoTime(); val r = ensure(item.set); perfPrepNs += System.nanoTime() - p0; r } else ensure(item.set)
            if (e.tiles.isEmpty()) continue
            GLES20.glUniform4f(uLayerAB, item.a, item.b, item.c, item.d)
            GLES20.glUniform2f(uLayerT, item.tx, item.ty)
            GLES20.glUniform1f(uAlpha, item.alpha)
            if (item.tint == 0) GLES20.glUniform4f(uTint, 0f, 0f, 0f, 0f)
            else if (item.tint == TINT_PREVIOUS) GLES20.glUniform4f(uTint, 0xE5 / 255f, 0x39 / 255f, 0x35 / 255f, 1f)
            else GLES20.glUniform4f(uTint, 0x43 / 255f, 0xA0 / 255f, 0x47 / 255f, 1f)
            GLES20.glUniform1f(uClipLocalOn, if (item.clipLocal) 1f else 0f)
            for (t in e.tiles) {
                val x0 = t.tx * size; val y0 = t.ty * size; val x1 = x0 + size; val y1 = y0 + size
                // cull tiles whose projected bounds are completely outside the surface
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (corner in 0 until 4) {
                    val lx = if (corner and 1 == 0) x0 else x1
                    val ly = if (corner and 2 == 0) y0 else y1
                    val px = item.a * lx + item.b * ly + item.tx
                    val py = item.c * lx + item.d * ly + item.ty
                    val nx = va * px + vb * py + vtx
                    val ny = vc * px + vd * py + vty
                    minX = min(minX, nx); maxX = max(maxX, nx); minY = min(minY, ny); maxY = max(maxY, ny)
                }
                if (maxX < -1f || minX > 1f || maxY < -1f || minY > 1f) continue
                GLES20.glUniform2f(uTileOrigin, x0, y0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }
        GLES20.glDisableVertexAttribArray(aPos)
        evict()
        return sc
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Compose side
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The plane: draws the newest GPU frame through "viewport of the frame -> viewport now" (zIndex between the video and the
 * drawing Canvas), and keeps the renderer fed with the current scene and viewport.
 */
@Composable
fun GlLayerPlane(cut: Cut, ed: Editor, boxW: Int, boxH: Int, fit: Float, onionStrength: () -> Float) {
    if (!GpuLayers.enabled || GpuLayers.failed || boxW <= 0 || boxH <= 0) return
    var frame by remember(boxW, boxH) { mutableStateOf<PlaneFrame?>(null) }
    val held = remember(boxW, boxH) { ArrayList<PlaneFrame>() }
    val renderer = remember(boxW, boxH) {
        GlLayerRenderer(boxW, boxH) { f ->
            val old = frame
            frame = f
            if (old != null) held.add(old)
            // keep the displayed frame and the two before it open: the UI renderer may still be sampling their buffers
            while (held.size > 2) runCatching { held.removeAt(0).image.close() }
            GpuLayers.shownK = f.info.k
            GpuLayers.shownOnion = f.info.onion
            GpuLayers.ready = true
        }
    }
    DisposableEffect(renderer) {
        onDispose {
            renderer.release()
            runCatching { frame?.image?.close() }
            for (f in held) runCatching { f.image.close() }
            held.clear()
            GpuLayers.ready = false; GpuLayers.shownK = -1; GpuLayers.shownOnion = false
        }
    }
    LaunchedEffect(renderer) {
        snapshotFlow { buildGlScene(cut, ed, gpuPlan(cut, ed), onionStrength()) }.collect { renderer.setScene(it) }
    }
    LaunchedEffect(renderer, fit) {
        snapshotFlow { screenView(ed, cut.w, cut.h, boxW, boxH, fit) }.collect { renderer.setView(it) }
    }
    val matrix = remember { android.graphics.Matrix() }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val values = remember { FloatArray(9) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().zIndex(-0.25f).clipToBounds()) {
        val f = frame ?: return@Canvas
        val now = screenView(ed, cut.w, cut.h, boxW, boxH, fit) // read in the draw phase: a gesture redraws only this
        val v0 = f.info.view
        val det = v0.a * v0.d - v0.b * v0.c
        if (kotlin.math.abs(det) < 1e-12f) return@Canvas
        val i00 = v0.d / det; val i01 = -v0.b / det; val i10 = -v0.c / det; val i11 = v0.a / det
        val m00 = now.a * i00 + now.b * i10; val m01 = now.a * i01 + now.b * i11
        val m10 = now.c * i00 + now.d * i10; val m11 = now.c * i01 + now.d * i11
        values[0] = m00; values[1] = m01; values[2] = now.tx - (m00 * v0.tx + m01 * v0.ty)
        values[3] = m10; values[4] = m11; values[5] = now.ty - (m10 * v0.tx + m11 * v0.ty)
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.concat(matrix)
            nc.drawBitmap(f.bitmap, -f.margin.toFloat(), -f.margin.toFloat(), paint)
            nc.restore()
        }
    }
}
