package com.example.inbetween

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Builds the ExoPlayer used by every audio / video-reference clip. Local files need only a short
 * read-ahead; the default 50 s compressed-sample buffer per player costs memory and I/O when
 * several clips are loaded, and a small buffer also makes a seek reach READY sooner.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
fun newMediaPlayer(context: Context, uri: String, volume: Float): ExoPlayer {
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(1500, 3000, 150, 300)
        .build()
    return ExoPlayer.Builder(context).setLoadControl(load).build().apply {
        this.volume = volume
        repeatMode = Player.REPEAT_MODE_OFF
        setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        prepare()
    }
}

/**
 * Single owner of media start / stop / seek during timeline playback (main thread only).
 *
 * Why: the timeline clock is wall-clock, but a player that is seeked and started cold needs
 * 100-300 ms before it produces sound or frames, and each audio / video player used to start on
 * its own, so audio and video began late and at different times. Here:
 *  - [begin] seeks every clip under the playhead while paused, waits (bounded) until all are
 *    READY, then starts them together; the caller starts the timeline clock right after.
 *  - [tick] primes clips that start within half a second (seek to 0, paused, decoder ready) and
 *    starts them on their exact frame; loop wraps re-seek running clips once.
 *  - Running clips are checked against the clock about once a second. Video uses a 120 ms
 *    threshold with consecutive-sample confirmation; audio uses a 300 ms threshold and a longer
 *    cooldown because the diagnostic tablet showed a persistent ~190 ms player-position offset
 *    that made the old hard-seek rule restart audio about once per second.
 * Players call [register] / [unregister]; scrub seeks while paused stay in the composables.
 */
object MediaSync {
    private const val PREROLL_TIMEOUT_MS = 700L
    private const val VIDEO_DRIFT_MS = 120L
    // Audio currentPosition on the diagnostic tablet stayed ~190 ms behind the wall clock even
    // after every seek, so the old 120 ms threshold caused a seek/buffer/restart loop every second.
    // Treat that repeatable offset as a measured baseline for now; only recover from a sustained,
    // substantially larger deviation. Revisit after collecting per-thread and longer-run samples.
    private const val AUDIO_DRIFT_MS = 300L
    private const val SETTLE_NS = 800_000_000L
    private const val CHECK_NS = 1_000_000_000L
    private const val AUDIO_CORRECTION_COOLDOWN_NS = 5_000_000_000L
    private const val VIDEO_CORRECTION_COOLDOWN_NS = 2_000_000_000L

    private class Clip(val layer: Layer, val player: ExoPlayer) {
        var running = false
        var primed = false
        var anchorNanos = 0L
        var anchorPosMs = 0L
        var lastCheckNanos = 0L
        var largeDriftSamples = 0
        var lastCorrectionNanos = 0L
    }

    private val clips = LinkedHashMap<Int, Clip>()
    private var lastFrame = Int.MIN_VALUE

    fun register(layer: Layer, player: ExoPlayer) {
        clips[layer.id]?.let { if (it.player !== player) Perf.unregisterPlayer(it.player) }
        clips[layer.id] = Clip(layer, player)
        Perf.registerPlayer(layer.id, layer.kind == 7, player) // profiler: Media3 analytics (observes only; ignores events unless the profiler is active)
    }

    fun unregister(layerId: Int, player: ExoPlayer) {
        if (clips[layerId]?.player === player) clips.remove(layerId)
        Perf.unregisterPlayer(player)
    }

    private fun posMs(layer: Layer, frame: Int, fps: Int): Long =
        (frame - layer.mediaStartFrame).coerceAtLeast(0).toLong() * 1000L / fps

    private fun inRange(layer: Layer, frame: Int, fps: Int): Boolean =
        frame >= layer.mediaStartFrame && frame < layer.mediaStartFrame + layer.mediaFrames(fps)

    private fun launch(c: Clip, positionMs: Long, now: Long) {
        c.player.play()
        c.running = true
        c.anchorNanos = now
        c.anchorPosMs = positionMs
        c.lastCheckNanos = now
        c.largeDriftSamples = 0
        c.lastCorrectionNanos = 0L
    }

    /** Preroll all clips under [startFrame] and start them together. Suspends at most ~0.7 s. */
    suspend fun begin(startFrame: Int, fps: Int) {
        lastFrame = startFrame
        val prerollStartNs = System.nanoTime()
        val targets = clips.values.filter { inRange(it.layer, startFrame, fps) }
        for (c in clips.values) { c.running = false; c.primed = false }
        for (c in targets) {
            c.player.pause()
            c.player.seekTo(posMs(c.layer, startFrame, fps))
        }
        val deadline = System.nanoTime() + PREROLL_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline && targets.any { it.player.playbackState == Player.STATE_BUFFERING }) delay(4)
        val now = System.nanoTime()
        if (Perf.active) Perf.preroll(targets.size, (now - prerollStartNs) / 1_000_000L, now >= deadline)
        for (c in targets) launch(c, posMs(c.layer, startFrame, fps), now)
    }

    /** Called once per timeline frame change while playing. */
    fun tick(frame: Int, fps: Int) {
        if (!Perf.active) { tickInner(frame, fps); return }
        val t0 = System.nanoTime()
        tickInner(frame, fps)
        Perf.tick(frame, fps, t0, System.nanoTime()) // profiler: how long the coordinator itself took, and whether the clock skipped frames
    }

    private fun tickInner(frame: Int, fps: Int) {
        val now = System.nanoTime()
        val wrapped = lastFrame != Int.MIN_VALUE && frame < lastFrame
        lastFrame = frame
        val lead = maxOf(2, fps / 2)
        for (c in clips.values) {
            val layer = c.layer
            val inside = inRange(layer, frame, fps)
            when {
                inside && (!c.running || wrapped) -> {
                    val pos = posMs(layer, frame, fps)
                    if (!c.running && Perf.active) Perf.clip(layer.id, true, frame)
                    if (c.primed && !wrapped && frame - layer.mediaStartFrame <= 1) {
                        launch(c, pos, now) // already positioned at 0 and ready
                    } else {
                        if (Perf.active) Perf.seek(layer.id, pos, if (wrapped) "loop wrap" else "clip start")
                        c.player.seekTo(pos)
                        launch(c, pos, now)
                    }
                    c.primed = false
                }
                !inside && c.running -> { if (Perf.active) Perf.clip(layer.id, false, frame); c.player.pause(); c.running = false; c.primed = false }
                !inside -> {
                    val ahead = layer.mediaStartFrame - frame
                    if (!c.primed && ahead in 1..lead) {
                        c.player.pause()
                        c.player.seekTo(0L)
                        c.primed = true
                    }
                }
                else -> checkDrift(c, layer, now)
            }
        }
    }

    private fun checkDrift(c: Clip, layer: Layer, now: Long) {
        if (now - c.anchorNanos < SETTLE_NS || now - c.lastCheckNanos < CHECK_NS) return
        c.lastCheckNanos = now
        if (c.player.playbackState != Player.STATE_READY) return
        val expected = c.anchorPosMs + (now - c.anchorNanos) / 1_000_000L
        if (expected >= layer.mediaDurationMs - 50L) return
        val actual = c.player.currentPosition
        val audio = layer.kind == 6
        val threshold = if (audio) AUDIO_DRIFT_MS else VIDEO_DRIFT_MS
        val cooldown = if (audio) AUDIO_CORRECTION_COOLDOWN_NS else VIDEO_CORRECTION_COOLDOWN_NS
        val requiredSamples = if (audio) 3 else 2
        val outside = abs(actual - expected) > threshold
        c.largeDriftSamples = if (outside) c.largeDriftSamples + 1 else 0
        val cooldownElapsed = c.lastCorrectionNanos == 0L || now - c.lastCorrectionNanos >= cooldown
        val corrected = outside && c.largeDriftSamples >= requiredSamples && cooldownElapsed
        if (Perf.active) Perf.drift(layer.id, expected, actual, corrected)
        if (corrected) {
            c.player.seekTo(expected)
            c.anchorNanos = now
            c.anchorPosMs = expected
            c.lastCorrectionNanos = now
            c.largeDriftSamples = 0
        }
    }

    /** Pause everything; call when playback ends or is cancelled. */
    fun stop() {
        for (c in clips.values) {
            if (c.running || c.primed) runCatching { c.player.pause() }
            c.running = false
            c.primed = false
        }
        lastFrame = Int.MIN_VALUE
    }
}
