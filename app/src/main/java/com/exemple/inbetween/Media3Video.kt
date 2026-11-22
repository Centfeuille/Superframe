package com.example.inbetween

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.roundToInt


/**
 * In video mode the Behind grid is composited between the white page and video
 * video layer: visible on the page, but still behind opaque video pixels.
 */
@Composable
fun Media3VideoGridBackground(cut: Cut, ed: Editor, boxWidth: Int, boxHeight: Int, fit: Float) {
    if (!ed.gridOn || ed.gridFront || boxWidth <= 0 || boxHeight <= 0) return
    Canvas(
        Modifier
            .zIndex(-1.5f)
            .size(with(LocalDensity.current) { boxWidth.toDp() }, with(LocalDensity.current) { boxHeight.toDp() })
    ) {
        val scale = fit * ed.vScale
        if (scale <= 0f) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val sx = if (ed.flipH) -1f else 1f
        val sy = if (ed.flipV) -1f else 1f
        fun doc(x: Float, y: Float): Offset {
            val qx = x - cx - ed.vOff.x
            val qy = y - cy - ed.vOff.y
            val r = Math.toRadians((-ed.vRot).toDouble())
            val rx = (qx * kotlin.math.cos(r) - qy * kotlin.math.sin(r)).toFloat()
            val ry = (qx * kotlin.math.sin(r) + qy * kotlin.math.cos(r)).toFloat()
            return Offset(rx * sx / scale + cut.w / 2f, ry * sy / scale + cut.h / 2f)
        }
        val corners = listOf(doc(0f, 0f), doc(size.width, 0f), doc(0f, size.height), doc(size.width, size.height))
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        val step = adaptiveGridStep(ed.gridStep, scale, maxX - minX, maxY - minY)
        withTransform({
            translate(cx + ed.vOff.x, cy + ed.vOff.y)
            rotate(ed.vRot, Offset.Zero)
            scale(scale * sx, scale * sy, Offset.Zero)
            translate(-cut.w / 2f, -cut.h / 2f)
        }) {
            val gridColor = Color(0x44607080)
            var x = floor(minX / step) * step
            while (x <= maxX) {
                drawLine(gridColor, Offset(x, minY), Offset(x, maxY), strokeWidth = 1f / scale)
                x += step
            }
            var y = floor(minY / step) * step
            while (y <= maxY) {
                drawLine(gridColor, Offset(minX, y), Offset(maxX, y), strokeWidth = 1f / scale)
                y += step
            }
        }
    }
}

@Composable
fun Media3VideoPageBackground(cut: Cut, ed: Editor, boxWidth: Int, boxHeight: Int, fit: Float) {
    val density = LocalDensity.current
    val fittedWidthPx = cut.w * fit
    val fittedHeightPx = cut.h * fit

    // IMPORTANT: apply the viewport transform to a workspace-sized node. A
    // page-sized graphicsLayer has its own centre as its transform pivot, which
    // differs from the drawing Canvas pivot whenever the page is letterboxed.
    // That mismatch made the video/page drift away from drawing coordinates.
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(-1f)
            .graphicsLayer {
                translationX = ed.vOff.x
                translationY = ed.vOff.y
                rotationZ = ed.vRot
                scaleX = ed.vScale * (if (ed.flipH) -1f else 1f)
                scaleY = ed.vScale * (if (ed.flipV) -1f else 1f)
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(with(density) { fittedWidthPx.toDp() }, with(density) { fittedHeightPx.toDp() })
                .background(Color.White)
        )
    }
}

/**
 * Hardware-decoded reference video shown by a TextureView beneath the drawing canvas.
 *
 * Why a TextureView and not a separate GL SurfaceView: a SurfaceView is presented to the screen
 * independently of the Compose window, so with a baked-in viewport transform it trails the ink during
 * pan / zoom / rotate (measured as lag on the tablet) and it punches a hole that erases the Compose page
 * and grid. A TextureView is composited inside the window by the UI renderer, so the viewport and the
 * video's own transform are plain `graphicsLayer` properties applied in the same frame as the drawing.
 * All of them are read in the layer blocks (draw phase), so viewport gestures and playback ticks do not
 * recompose this function. Decoding is still MediaCodec hardware decoding straight into the texture.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun Media3VideoReferenceLayer(
    cut: Cut,
    ed: Editor,
    layer: Layer,
    boxWidth: Int,
    boxHeight: Int,
    fit: Float,
    hidden: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val player = remember(layer.id, layer.mediaUri) {
        newMediaPlayer(context, layer.mediaUri, 0f) // A video reference layer never contributes audio.
    }
    val textureView = remember(player) {
        TextureView(context).apply {
            // Do NOT set isOpaque = true here: with the layer opacity below 100% the video then
            // darkened (blended against black) instead of fading toward the white page.
            isClickable = false
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    var videoAspect by remember(player) {
        val initial = player.videoSize.let { if (it.width > 0 && it.height > 0) it.width * it.pixelWidthHeightRatio / it.height else cut.w / cut.h.toFloat() }
        mutableFloatStateOf(initial.coerceAtLeast(0.01f))
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceAtLeast(0.01f)
                }
            }
        }
        player.addListener(listener)
        player.setVideoTextureView(textureView)
        MediaSync.register(layer, player)
        onDispose {
            MediaSync.unregister(layer.id, player)
            player.removeListener(listener)
            player.clearVideoTextureView(textureView)
            player.release()
        }
    }
    LaunchedEffect(player, cut.playing, if (cut.playing) -1 else cut.time, cut.fps, layer.mediaStartFrame) {
        if (!cut.playing) {
            player.pause()
            val relative = (cut.time - layer.mediaStartFrame).coerceAtLeast(0)
            player.seekTo(relative.toLong() * 1000L / cut.fps.coerceAtLeast(1))
            return@LaunchedEffect
        }
        // While playing, MediaSync (driven by the timeline clock) owns start / stop / seek.
    }

    // Same layout contract as Media3VideoPageBackground: the viewport transform is applied to a
    // workspace-sized node (pivot = workspace centre, like the drawing Canvas). The video image is fitted
    // inside the Cut page at the base fit scale; its own folder / media transform is a second layer.
    val imageW = min(cut.w.toFloat(), cut.h * videoAspect)
    val imageH = min(cut.h.toFloat(), cut.w / videoAspect)
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(-0.5f)
            .graphicsLayer {
                translationX = ed.vOff.x
                translationY = ed.vOff.y
                rotationZ = ed.vRot
                scaleX = ed.vScale * (if (ed.flipH) -1f else 1f)
                scaleY = ed.vScale * (if (ed.flipV) -1f else 1f)
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(with(density) { (imageW * fit).toDp() }, with(density) { (imageH * fit).toDp() })
                .graphicsLayer {
                    // Evaluate the folder channels directly in the layer block. Calling
                    // transformFor() here constructs a short-lived GroupTransform on every
                    // playback frame; the scalar reads preserve the same interpolation while
                    // avoiding that per-frame object allocation on the UI render path.
                    val folder = cut.layerById(layer.group)
                        ?.takeIf { it.isGroup && it.transformFolder }
                    val frame = cut.time
                    val groupX = folder?.let { evalChan(it.transformX, frame, 0f) } ?: 0f
                    val groupY = folder?.let { evalChan(it.transformY, frame, 0f) } ?: 0f
                    val groupScale = folder?.let { evalChan(it.transformScale, frame, 1f) } ?: 1f
                    val groupRotation = folder?.let { evalChan(it.transformRotation, frame, 0f) } ?: 0f
                    translationX = (groupX + layer.mediaX) * fit
                    translationY = (groupY + layer.mediaY) * fit
                    rotationZ = groupRotation + layer.mediaRotation
                    val s = (groupScale * layer.mediaScale).coerceIn(0.001f, 1000f)
                    scaleX = s
                    scaleY = s
                    alpha = if (hidden) 0f else layer.opacity.coerceIn(0f, 1f)
                }
        ) {
            AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
        }
    }
}
