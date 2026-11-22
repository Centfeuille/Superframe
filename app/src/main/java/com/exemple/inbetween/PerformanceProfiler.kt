@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.inbetween

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.io.File
import java.util.Locale

/**
 * Integrated performance profiler (spec: Superframe - Performance Profiler).
 *
 * It only OBSERVES. Nothing here changes playback, rendering or project data. When neither the HUD nor a recording is on,
 * [active] is false and every hook returns after one volatile read. Memory is bounded: rolling rings for the live numbers,
 * capped logs while recording, a histogram for the frame-time distribution.
 *
 * Sources: Android frame metrics (UI frames) | process sampler (CPU, heap, GC) | Media3 AnalyticsListener (audio underruns,
 * video dropped frames, decoder, buffering, errors) | GlLayers (prepare, commands, explicit GPU wait, swap) | MediaSync (tick, seek, preroll, drift).
 * Decoder-reported dropped frames and window frame metrics measure different stages and are never merged.
 */
object Perf {
    // ----- state the UI reads -----
    var hudVisible by mutableStateOf(false); private set
    var recording by mutableStateOf(false); private set
    var scenario by mutableStateOf(PERF_SCENARIOS[0])
    var notes by mutableStateOf("")
    /** 0 = follow the display refresh rate; otherwise the budget is 1000 / budgetHz ms. */
    var budgetHz by mutableIntStateOf(0)
    var snapshot by mutableStateOf(HudSnapshot.EMPTY); private set
    var recordingStartedElapsedMs by mutableLongStateOf(0L); private set
    var lastExportNote by mutableStateOf("")

    /** True while the HUD is shown or a recording runs. Hooks check this first. */
    @Volatile var active = false; private set

    private const val MAX_RECORDING_MS = 60L * 60L * 1000L
    private const val LOG_CAPACITY = 36_000          // about 5 minutes of 120 Hz frames
    private const val EVENT_CAPACITY = 4_000
    private val lock = Any()

    // ----- session -----
    private var baseElapsedMs = SystemClock.elapsedRealtime()   // time zero of the exported CSVs (last reset / recording start)
    private var sessionStartWallMs = 0L
    private var sessionEndWallMs = 0L
    @Volatile private var refreshHz = 60f

    // ----- frame metrics -----
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var attachedWindow: Window? = null
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, fm, dropped -> onFrame(fm, dropped) }
    private val frameTotal = FloatRing(600)
    private val histogram = Histogram(64)
    private var frames = 0L
    private var violations = 0L
    private var droppedFramework = 0L
    private val stageSum = DoubleArray(9)
    private val stageMax = FloatArray(9)
    private val frameLog = FrameLog(LOG_CAPACITY)
    private var gpuAvailable = false

    // ----- process samples -----
    private val sysLog = SysLog(3600)
    @Volatile private var lastCpuPct = -1f
    @Volatile private var lastJavaMb = 0f
    @Volatile private var lastNativeMb = 0f
    @Volatile private var lastPssMb = -1f
    @Volatile private var lastGcCount = 0L
    @Volatile private var lastGcMs = 0L
    private var gcCountAtLastSnapshot = 0L
    private var sampler: Thread? = null
    private val threadCpuRows = ArrayDeque<ThreadCpuSample>()
    private val threadCpuPrevious = HashMap<Int, Pair<Long, String>>()
    private val threadCpuCapacity = 16_000
    private val clockTicksPerSecond: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L) }

    // ----- GL -----
    private val glPrep = FloatRing(300); private val glCommands = FloatRing(300); private val glFinish = FloatRing(300); private val glSwap = FloatRing(300)
    private var glRequests = 0L; private var glFrames = 0L; private var glFailed = 0L; private var glSwapFailures = 0L; private var glErrors = 0L
    private val glLog = GlLog(LOG_CAPACITY)

    // ----- media -----
    private var audioUnderruns = 0L; private var audioUnderrunMs = 0L
    private var videoDropped = 0L; private var mediaErrors = 0L
    private val activeAudio = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeVideo = java.util.concurrent.atomic.AtomicInteger(0)
    private val listeners = IdentityHashMap<ExoPlayer, PlayerProbe>()
    private val mediaRows = ArrayDeque<MediaRow>()
    private var mediaRowsDropped = 0L

    // ----- playback coordinator -----
    private val tickInterval = FloatRing(300); private val tickProcessing = FloatRing(300); private val drift = FloatRing(200)
    private var lastTickNs = 0L; private var lastTickFrame = Int.MIN_VALUE
    private var tickCount = 0L; private var tickMissedFrames = 0L; private var prerolls = 0L; private var seeks = 0L

    private val events = ArrayDeque<PerfEvent>()
    private var eventsDropped = 0L

    // =====================================================================================================
    // Controls
    // =====================================================================================================

    fun setHudVisibility(v: Boolean) { hudVisible = v; refreshActive() }
    fun toggleHud() = setHudVisibility(!hudVisible)

    fun startRecording() {
        if (recording) return
        clearData()
        sessionStartWallMs = System.currentTimeMillis(); sessionEndWallMs = 0L
        recordingStartedElapsedMs = SystemClock.elapsedRealtime()
        recording = true
        refreshActive()
        event("session", "recording-start", scenario)
    }

    fun stopRecording() {
        if (!recording) return
        event("session", "recording-stop", "duration ${(SystemClock.elapsedRealtime() - recordingStartedElapsedMs) / 1000} s")
        sessionEndWallMs = System.currentTimeMillis()
        recording = false
        refreshActive()
    }

    /** Clears every statistic. A running recording keeps running and restarts its clock. */
    fun reset() {
        clearData()
        if (recording) { sessionStartWallMs = System.currentTimeMillis(); recordingStartedElapsedMs = SystemClock.elapsedRealtime() }
        snapshot = HudSnapshot.EMPTY
        event("session", "reset", "statistics cleared")
    }

    fun hasData(): Boolean = synchronized(lock) { frames > 0 || events.isNotEmpty() || glFrames > 0 || mediaRows.isNotEmpty() || threadCpuRows.isNotEmpty() }

    private fun refreshActive() {
        active = hudVisible || recording
        if (active) startSampler()
    }

    private fun clearData() {
        synchronized(lock) {
            baseElapsedMs = SystemClock.elapsedRealtime()
            frameTotal.clear(); histogram.clear(); frames = 0; violations = 0; droppedFramework = 0
            stageSum.fill(0.0); stageMax.fill(0f); frameLog.clear()
            sysLog.clear(); gcCountAtLastSnapshot = lastGcCount
            threadCpuRows.clear()
            glPrep.clear(); glCommands.clear(); glFinish.clear(); glSwap.clear()
            glRequests = 0; glFrames = 0; glFailed = 0; glSwapFailures = 0; glErrors = 0; glLog.clear()
            audioUnderruns = 0; audioUnderrunMs = 0; videoDropped = 0; mediaErrors = 0; mediaRows.clear(); mediaRowsDropped = 0
            tickInterval.clear(); tickProcessing.clear(); drift.clear()
            lastTickNs = 0; lastTickFrame = Int.MIN_VALUE; tickCount = 0; tickMissedFrames = 0; prerolls = 0; seeks = 0
            events.clear(); eventsDropped = 0
        }
    }

    // =====================================================================================================
    // Android frame metrics (UI / render-thread frames)
    // =====================================================================================================

    /** Called from a Compose effect with the activity window. Listening only happens while [active]. */
    fun attachWindow(window: Window, hz: Float) {
        if (hz > 1f) refreshHz = hz
        if (attachedWindow === window) return
        detachWindow()
        val thread = frameThread ?: HandlerThread("PerfFrameMetrics").also { it.start(); frameThread = it; frameHandler = Handler(it.looper) }
        runCatching { window.addOnFrameMetricsAvailableListener(frameListener, frameHandler ?: Handler(thread.looper)); attachedWindow = window }
            .onFailure { CrashDiagnostics.log("WARN", "Profiler: frame metrics unavailable: ${it.message}") }
        gpuAvailable = Build.VERSION.SDK_INT >= 31
    }

    fun detachWindow() {
        attachedWindow?.let { runCatching { it.removeOnFrameMetricsAvailableListener(frameListener) } }
        attachedWindow = null
    }

    private fun budgetMs(): Float = 1000f / (if (budgetHz > 0) budgetHz.toFloat() else refreshHz.coerceAtLeast(30f))

    private fun onFrame(fm: FrameMetrics, dropped: Int) {
        if (!active) return
        fun ms(id: Int) = fm.getMetric(id) / 1_000_000f
        val total = ms(FrameMetrics.TOTAL_DURATION)
        val input = ms(FrameMetrics.INPUT_HANDLING_DURATION); val anim = ms(FrameMetrics.ANIMATION_DURATION)
        val layout = ms(FrameMetrics.LAYOUT_MEASURE_DURATION); val draw = ms(FrameMetrics.DRAW_DURATION)
        val sync = ms(FrameMetrics.SYNC_DURATION); val issue = ms(FrameMetrics.COMMAND_ISSUE_DURATION); val swap = ms(FrameMetrics.SWAP_BUFFERS_DURATION)
        val gpu = if (Build.VERSION.SDK_INT >= 31) ms(FrameMetrics.GPU_DURATION) else -1f
        val budget = budgetMs()
        frameTotal.add(total); histogram.add(total)
        synchronized(lock) {
            frames++
            if (total > budget) violations++
            droppedFramework += dropped
            val v = floatArrayOf(total, input, anim, layout, draw, sync, issue, swap, gpu)
            for (i in v.indices) { if (v[i] >= 0f) stageSum[i] += v[i].toDouble(); if (v[i] > stageMax[i]) stageMax[i] = v[i] }
            if (recording) frameLog.add(SystemClock.elapsedRealtime() - baseElapsedMs, total, input, anim, layout, draw, sync, issue, swap, gpu)
        }
    }

    // =====================================================================================================
    // Process sampler: CPU, heap, native heap, PSS, GC. About once a second, off the UI thread.
    // =====================================================================================================

    private fun startSampler() {
        synchronized(lock) {
            if (sampler?.isAlive == true) return
            sampler = Thread({ samplerLoop() }, "PerfSampler").apply { isDaemon = true; start() }
        }
    }

    private fun gcStat(name: String): Long = runCatching { Debug.getRuntimeStat(name)?.toLongOrNull() }.getOrNull() ?: 0L

    /** Samples this process' Linux task table. This distinguishes a busy main/GL/media thread from aggregate process CPU. */
    private fun sampleThreadCpu(wallMs: Long, elapsedMs: Long) {
        val tasks = runCatching { File("/proc/self/task").listFiles()?.toList() }.getOrNull() ?: return
        val current = HashMap<Int, Pair<Long, String>>(tasks.size)
        val samples = ArrayList<ThreadCpuSample>(tasks.size)
        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: continue
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 1 >= stat.length) continue
            val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
            if (fields.size <= 12) continue
            val ticks = (fields[11].toLongOrNull() ?: continue) + (fields[12].toLongOrNull() ?: continue)
            val name = runCatching { File(task, "comm").readText().trim() }.getOrDefault("tid-$tid")
            current[tid] = ticks to name
            val previous = threadCpuPrevious[tid] ?: continue
            val deltaTicks = (ticks - previous.first).coerceAtLeast(0L)
            val cpuMs = deltaTicks * 1000f / clockTicksPerSecond
            val pct = cpuMs * 100f / elapsedMs
            samples.add(ThreadCpuSample(wallMs - baseElapsedMs, tid, name, pct))
        }
        threadCpuPrevious.clear()
        threadCpuPrevious.putAll(current)
        synchronized(lock) {
            for (sample in samples) {
                if (threadCpuRows.size >= threadCpuCapacity) threadCpuRows.removeFirst()
                threadCpuRows.addLast(sample)
            }
        }
    }

    private fun samplerLoop() {
        var lastCpu = Process.getElapsedCpuTime()
        var lastWall = SystemClock.elapsedRealtime()
        var n = 0
        while (active) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            val wall = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
            val sampleElapsedMs = (wall - lastWall).coerceAtLeast(1L)
            val pct = (cpu - lastCpu) * 100f / sampleElapsedMs
            lastCpu = cpu; lastWall = wall
            val rt = Runtime.getRuntime()
            val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576f
            val nativeMb = Debug.getNativeHeapAllocatedSize() / 1048576f
            val pss = if (n % 5 == 0) Debug.getPss() / 1024f else -1f  // PSS is the expensive one: every fifth sample only
            n++
            val gcCount = gcStat("art.gc.gc-count"); val gcMs = gcStat("art.gc.gc-time")
            sampleThreadCpu(wall, sampleElapsedMs)
            lastCpuPct = pct; lastJavaMb = javaMb; lastNativeMb = nativeMb; if (pss >= 0f) lastPssMb = pss; lastGcCount = gcCount; lastGcMs = gcMs
            sysLog.add(wall - baseElapsedMs, pct, javaMb, nativeMb, pss, gcCount, gcMs)
            if (recording && wall - recordingStartedElapsedMs > MAX_RECORDING_MS) {
                event("session", "recording-limit", "stopped after the maximum session length")
                stopRecording()
            }
        }
        threadCpuPrevious.clear()
    }

    // =====================================================================================================
    // Events and media rows
    // =====================================================================================================

    fun event(category: String, name: String, detail: String = "") {
        if (!active) return
        val e = PerfEvent(SystemClock.elapsedRealtime() - baseElapsedMs, category, name, detail)
        synchronized(lock) { if (events.size >= EVENT_CAPACITY) { events.removeFirst(); eventsDropped++ }; events.addLast(e) }
    }

    private fun mediaRow(layerId: Int, kind: String, event: String, a: Long = 0, b: Long = 0, detail: String = "") {
        val row = MediaRow(SystemClock.elapsedRealtime() - baseElapsedMs, layerId, kind, event, a, b, detail)
        synchronized(lock) { if (mediaRows.size >= EVENT_CAPACITY) { mediaRows.removeFirst(); mediaRowsDropped++ }; mediaRows.addLast(row) }
    }

    // =====================================================================================================
    // GL compositor hooks (GlLayers.kt). All durations in nanoseconds.
    // =====================================================================================================

    fun glRequest() { if (active) synchronized(lock) { glRequests++ } }

    fun glFrame(prepNs: Long, commandsNs: Long, finishNs: Long, swapNs: Long, ok: Boolean) {
        if (!active) return
        val p = prepNs / 1e6f; val c = commandsNs.coerceAtLeast(0L) / 1e6f; val f = finishNs / 1e6f; val s = swapNs / 1e6f
        glPrep.add(p); glCommands.add(c); glFinish.add(f); glSwap.add(s)
        synchronized(lock) {
            glFrames++
            if (!ok) glSwapFailures++
            if (recording) glLog.add(SystemClock.elapsedRealtime() - baseElapsedMs, p, c, f, s, ok)
        }
        if (!ok) event("gl", "swap-failed", "eglSwapBuffers returned false")
    }

    fun glError(code: Int) { if (active) { synchronized(lock) { glErrors++ }; event("gl", "gl-error", "glGetError 0x${Integer.toHexString(code)}") } }
    fun glFailure(reason: String) { synchronized(lock) { glFailed++ }; event("gl", "failure", reason) }

    // =====================================================================================================
    // Playback coordinator hooks (MediaSync.kt)
    // =====================================================================================================

    /** One timeline frame change handled by MediaSync.tick; [t0]/[t1] are System.nanoTime() before and after it. */
    fun tick(frame: Int, fps: Int, t0: Long, t1: Long) {
        if (!active) return
        val processingMs = (t1 - t0) / 1e6f
        tickProcessing.add(processingMs)
        synchronized(lock) {
            tickCount++
            if (lastTickNs != 0L) tickInterval.add((t0 - lastTickNs) / 1e6f)
            val delta = frame - lastTickFrame
            if (lastTickFrame != Int.MIN_VALUE && delta in 2..fps.coerceAtLeast(2)) tickMissedFrames += (delta - 1).toLong()
            lastTickNs = t0; lastTickFrame = frame
        }
        if (processingMs > 4f) event("playback", "slow-tick", "frame $frame took ${"%.1f".format(Locale.US, processingMs)} ms in MediaSync.tick")
    }

    fun preroll(clips: Int, ms: Long, timedOut: Boolean) {
        if (!active) return
        synchronized(lock) { prerolls++ }
        event("playback", if (timedOut) "preroll-timeout" else "preroll", "$clips clip(s), $ms ms")
    }

    fun seek(layerId: Int, targetMs: Long, reason: String) {
        if (!active) return
        synchronized(lock) { seeks++ }
        mediaRow(layerId, "sync", "seek", targetMs, 0, reason)
        event("sync", "seek", "layer $layerId to $targetMs ms ($reason)")
    }

    fun clip(layerId: Int, entering: Boolean, frame: Int) {
        if (!active) return
        event("playback", if (entering) "clip-enter" else "clip-exit", "layer $layerId at frame $frame")
    }

    /** Timeline-expected vs actual player position (once a second per running clip). */
    fun drift(layerId: Int, expectedMs: Long, actualMs: Long, corrected: Boolean) {
        if (!active) return
        val d = (actualMs - expectedMs).toFloat()
        drift.add(d)
        mediaRow(layerId, "sync", if (corrected) "drift-corrected" else "drift", expectedMs, actualMs, "player - timeline = ${d.toInt()} ms")
        if (corrected) event("sync", "drift-correction", "layer $layerId off by ${d.toInt()} ms")
    }

    // =====================================================================================================
    // Media3 analytics (audio and video players are registered by MediaSync)
    // =====================================================================================================

    fun registerPlayer(layerId: Int, video: Boolean, player: ExoPlayer) {
        val probe = PlayerProbe(layerId, video, player)
        synchronized(lock) { if (listeners.containsKey(player)) return; listeners[player] = probe }
        (if (video) activeVideo else activeAudio).incrementAndGet()
        player.addAnalyticsListener(probe)
    }

    fun unregisterPlayer(player: ExoPlayer) {
        val probe = synchronized(lock) { listeners.remove(player) } ?: return
        (if (probe.video) activeVideo else activeAudio).decrementAndGet()
        runCatching { player.removeAnalyticsListener(probe) }
    }

    private class PlayerProbe(val layerId: Int, val video: Boolean, val player: ExoPlayer) : AnalyticsListener {
        private val kind = if (video) "video" else "audio"
        private fun buffered() = runCatching { player.totalBufferedDuration }.getOrDefault(0L)
        private var bufferingSince = 0L

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            if (!active) return
            synchronized(lock) { audioUnderruns++; audioUnderrunMs += elapsedSinceLastFeedMs }
            mediaRow(layerId, kind, "audio-underrun", bufferSizeMs, elapsedSinceLastFeedMs, "bufferSize=$bufferSize bytes, bufferedMs=${buffered()}")
            event("audio", "underrun", "layer $layerId: buffer $bufferSizeMs ms, last feed $elapsedSinceLastFeedMs ms ago")
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            if (!active) return
            synchronized(lock) { videoDropped += droppedFrames }
            mediaRow(layerId, kind, "video-dropped-frames", droppedFrames.toLong(), elapsedMs, "decoder-reported")
            event("video", "dropped-frames", "layer $layerId: $droppedFrames in $elapsedMs ms (decoder-reported)")
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (!active) return
            mediaRow(layerId, kind, "video-decoder-init", initializationDurationMs, 0, decoderName)
            event("video", "decoder-init", "layer $layerId: $decoderName in $initializationDurationMs ms")
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (!active) return
            mediaRow(layerId, kind, "audio-decoder-init", initializationDurationMs, 0, decoderName)
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (!active) return
            mediaRow(layerId, kind, "video-format", format.width.toLong(), format.height.toLong(), "frameRate=${format.frameRate} codec=${format.sampleMimeType}")
        }
        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            if (!active) return
            mediaRow(layerId, kind, "video-size", videoSize.width.toLong(), videoSize.height.toLong(), "")
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (!active) return
            val name = when (state) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "state-$state" }
            val now = SystemClock.elapsedRealtime()
            if (state == Player.STATE_BUFFERING) bufferingSince = now
            val waited = if (state != Player.STATE_BUFFERING && bufferingSince != 0L) now - bufferingSince else 0L
            if (state != Player.STATE_BUFFERING) bufferingSince = 0L
            mediaRow(layerId, kind, "state-$name", waited, buffered(), if (waited > 0) "buffering lasted $waited ms" else "")
            if (state == Player.STATE_BUFFERING) event(kind, "buffering-start", "layer $layerId")
            if (waited > 0) event(kind, "buffering-end", "layer $layerId after $waited ms")
        }
        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (!active) return
            mediaRow(layerId, kind, if (isPlaying) "play-start" else "play-stop", player.currentPosition, buffered(), "")
            event(kind, if (isPlaying) "playback-start" else "playback-stop", "layer $layerId at ${player.currentPosition} ms")
        }
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            if (!active) return
            synchronized(lock) { mediaErrors++ }
            mediaRow(layerId, kind, "error", error.errorCode.toLong(), 0, error.errorCodeName)
            event(kind, "player-error", "layer $layerId: ${error.errorCodeName}")
        }
        override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!active) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                mediaRow(layerId, kind, "player-seek", oldPosition.positionMs, newPosition.positionMs, if (reason == Player.DISCONTINUITY_REASON_SEEK) "seek" else "seek-adjustment")
            }
        }
    }

    // =====================================================================================================
    // HUD snapshot (built off the main thread a few times a second)
    // =====================================================================================================

    private var lastSnapshotMs = 0L
    private var lastSnapshotFrames = 0L

    fun buildSnapshot(): HudSnapshot {
        val now = SystemClock.elapsedRealtime()
        val ft = frameTotal.snapshot()
        val st = statsOf(ft)
        val budget = budgetMs()
        var over = 0
        for (v in ft) if (v > budget) over++
        val s: HudSnapshot
        synchronized(lock) {
            val dt = (now - lastSnapshotMs).coerceAtLeast(1L)
            val fps = if (lastSnapshotMs == 0L) 0f else (frames - lastSnapshotFrames) * 1000f / dt
            lastSnapshotMs = now; lastSnapshotFrames = frames
            val gcDelta = (lastGcCount - gcCountAtLastSnapshot).coerceAtLeast(0L)
            gcCountAtLastSnapshot = lastGcCount
            s = HudSnapshot(
                refreshHz = refreshHz, budgetMs = budget, frames = st, fps = fps,
                jankPct = if (ft.isEmpty()) 0f else over * 100f / ft.size, budgetViolations = violations, droppedFrames = droppedFramework,
                cpuPct = lastCpuPct, cpuCores = Runtime.getRuntime().availableProcessors(), javaMb = lastJavaMb, nativeMb = lastNativeMb, pssMb = lastPssMb,
                gcCount = lastGcCount, gcMs = lastGcMs, gcCountDelta = gcDelta,
                glPrep = statsOf(glPrep.snapshot()), glCommands = statsOf(glCommands.snapshot()), glFinish = statsOf(glFinish.snapshot()), glSwap = statsOf(glSwap.snapshot()),
                glFrames = glFrames, glSkipped = (glRequests - glFrames).coerceAtLeast(0L), glFailed = glFailed, glSwapFailures = glSwapFailures, glErrors = glErrors,
                audioUnderruns = audioUnderruns, videoDropped = videoDropped, mediaErrors = mediaErrors,
                audioPlayers = activeAudio.get(), videoPlayers = activeVideo.get(),
                tickInterval = statsOf(tickInterval.snapshot()), tickProcessing = statsOf(tickProcessing.snapshot()), tickMissedFrames = tickMissedFrames,
                driftMs = statsOf(drift.snapshot())
            )
        }
        snapshot = s
        return s
    }

    // =====================================================================================================
    // Export (called on a background thread). Returns the performance files of the diagnostic ZIP.
    // =====================================================================================================

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun f1(v: Float) = String.format(Locale.US, "%.2f", v)
    private fun st(s: Stats) = if (s.count == 0) "no samples" else "avg ${f1(s.avg)} | min ${f1(s.min)} | p50 ${f1(s.p50)} | p95 ${f1(s.p95)} | p99 ${f1(s.p99)} | max ${f1(s.max)} ms (n=${s.count})"

    fun exportFiles(appVersion: String, androidVersion: String, device: String): Map<String, ByteArray> {
        buildSnapshot()
        val s = snapshot
        val out = LinkedHashMap<String, ByteArray>()
        val wallFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val startTxt = if (sessionStartWallMs > 0) wallFmt.format(java.util.Date(sessionStartWallMs)) else "no recording started (HUD statistics only)"
        val endTxt = if (sessionEndWallMs > 0) wallFmt.format(java.util.Date(sessionEndWallMs)) else if (recording) "still recording at export" else "n/a"
        val durationS = if (sessionStartWallMs > 0) ((if (sessionEndWallMs > 0) sessionEndWallMs else System.currentTimeMillis()) - sessionStartWallMs) / 1000 else 0L

        // copy everything under the lock, format outside it
        val evs: List<PerfEvent>; val rows: List<MediaRow>; val hist: LongArray
        val fl: Array<Any>; val gl: Array<Any>; val sysOrder: IntArray
        val stageAvg = DoubleArray(9); val stageMx: FloatArray
        val frameCount: Long; val viol: Long; val dropFw: Long; val droppedEv: Long; val droppedRows: Long
        val truncatedFrames: Boolean; val truncatedGl: Boolean
        val threadRows: List<ThreadCpuSample>
        synchronized(lock) {
            evs = events.toList(); rows = mediaRows.toList(); hist = histogram.copy()
            frameCount = frames; viol = violations; dropFw = droppedFramework; droppedEv = eventsDropped; droppedRows = mediaRowsDropped
            for (i in stageAvg.indices) stageAvg[i] = if (frames > 0) stageSum[i] / frames else 0.0
            stageMx = stageMax.copyOf()
            truncatedFrames = frameLog.truncated; truncatedGl = glLog.truncated
            sysOrder = sysLog.order()
            threadRows = threadCpuRows.toList()
            fl = arrayOf(frameLog.size, frameLog.t.copyOf(frameLog.size), frameLog.total.copyOf(frameLog.size), frameLog.input.copyOf(frameLog.size), frameLog.anim.copyOf(frameLog.size),
                frameLog.layout.copyOf(frameLog.size), frameLog.draw.copyOf(frameLog.size), frameLog.sync.copyOf(frameLog.size), frameLog.issue.copyOf(frameLog.size),
                frameLog.swap.copyOf(frameLog.size), frameLog.gpu.copyOf(frameLog.size))
            gl = arrayOf(glLog.size, glLog.t.copyOf(glLog.size), glLog.prep.copyOf(glLog.size), glLog.commands.copyOf(glLog.size), glLog.finish.copyOf(glLog.size),
                glLog.swap.copyOf(glLog.size), glLog.ok.copyOf(glLog.size))
        }

        // performance-summary.txt
        val names = listOf("total", "input handling", "animation", "layout/measure", "draw", "render-thread sync", "command issue", "buffer swap", "GPU")
        val summary = buildString {
            appendLine("Superframe performance summary (profiler v$PROFILER_VERSION)")
            appendLine("App version: $appVersion"); appendLine("Android: $androidVersion"); appendLine("Device: $device")
            appendLine("Display refresh rate: ${f1(refreshHz)} Hz; frame budget used: ${f1(s.budgetMs)} ms" + if (budgetHz > 0) " (selected manually: $budgetHz Hz)" else " (from the display)")
            appendLine("Session start: $startTxt"); appendLine("Session end: $endTxt"); appendLine("Recording duration: $durationS s")
            appendLine("Scenario: $scenario")
            appendLine("\n== UI frames (Android frame metrics) ==")
            appendLine("Frames measured: $frameCount; over budget: $viol (${if (frameCount > 0) f1(viol * 100f / frameCount) else "0"} %); dropped (framework-reported): $dropFw")
            appendLine("Last-600-frame window: ${st(s.frames)}; FPS now: ${f1(s.fps)}")
            for (i in names.indices) appendLine("  ${names[i]}: avg ${f1(stageAvg[i].toFloat())} ms, max ${f1(stageMx[i])} ms" + if (i == 8 && Build.VERSION.SDK_INT < 31) " (needs Android 12)" else "")
            appendLine("Frame-time histogram (1 ms bins, last bin = 64 ms and over):")
            for (i in hist.indices) if (hist[i] > 0) appendLine("  ${if (i == hist.size - 1) "$i+" else "$i"} ms: ${hist[i]}")
            appendLine("\n== Process ==")
            if (sysOrder.isEmpty()) appendLine("No process samples.") else {
                var cpuSum = 0f; var cpuMax = 0f; var jMax = 0f; var nMax = 0f; var pMax = 0f
                for (i in sysOrder) { cpuSum += sysCpu(i); if (sysCpu(i) > cpuMax) cpuMax = sysCpu(i); jMax = maxOf(jMax, sysJava(i)); nMax = maxOf(nMax, sysNative(i)); pMax = maxOf(pMax, sysPss(i)) }
                val first = sysOrder.first(); val last = sysOrder.last()
                appendLine("CPU (process, % of one core; ${s.cpuCores} cores, so 100 % = one core fully busy): avg ${f1(cpuSum / sysOrder.size)}, max ${f1(cpuMax)}")
                appendLine("Java heap MB: start ${f1(sysJava(first))}, end ${f1(sysJava(last))}, max ${f1(jMax)}")
                appendLine("Native heap MB: start ${f1(sysNative(first))}, end ${f1(sysNative(last))}, max ${f1(nMax)}")
                appendLine("PSS MB (sampled every 5 s): max ${f1(pMax)}")
                appendLine("GC: ${sysGcCount(last) - sysGcCount(first)} collections, ${sysGcMs(last) - sysGcMs(first)} ms over the samples")
                val busiest = threadRows.groupBy { it.tid to it.name }.map { (key, values) -> Triple(key.second, values.map { it.cpuPct }.average(), values.maxOfOrNull { it.cpuPct } ?: 0f) }.sortedByDescending { it.second }.take(8)
                appendLine("Per-thread CPU (top 8 by sampled average; % of one core):")
                if (busiest.isEmpty()) appendLine("  unavailable (the process task table could not be read)")
                else for ((name, avg, mx) in busiest) appendLine("  $name: avg ${f1(avg.toFloat())} %, max ${f1(mx)} %")
            }
            appendLine("\n== GL compositor ==")
            appendLine("Frames rendered: ${s.glFrames}; skipped/coalesced requests: ${s.glSkipped}; failed: ${s.glFailed}; swap failures: ${s.glSwapFailures}; GL errors: ${s.glErrors}")
            appendLine("CPU-side preparation: ${st(s.glPrep)}"); appendLine("GL commands: ${st(s.glCommands)}")
            appendLine("Explicit GPU wait (per-frame glFinish removed): ${st(s.glFinish)}"); appendLine("eglSwapBuffers: ${st(s.glSwap)}")
            appendLine("\n== Audio / video (Media3 analytics; decoder-reported, not comparable with the UI frame numbers) ==")
            appendLine("Audio underruns: ${s.audioUnderruns} (feed gaps ${audioUnderrunMs} ms in total); video frames dropped by the decoder: ${s.videoDropped}; player errors: ${s.mediaErrors}")
            appendLine("Players registered now: ${s.audioPlayers} audio, ${s.videoPlayers} video")
            appendLine("\n== Playback coordinator (MediaSync) ==")
            appendLine("Timeline ticks: $tickCount; frames skipped by the clock: ${s.tickMissedFrames}; prerolls: $prerolls; seeks: $seeks")
            appendLine("Tick interval: ${st(s.tickInterval)}"); appendLine("Tick processing: ${st(s.tickProcessing)}")
            appendLine("Audio/video drift (player - timeline): ${st(s.driftMs)}")
            appendLine("\n== Notes ==")
            appendLine("Events kept: ${evs.size} (dropped when full: $droppedEv); media rows: ${rows.size} (dropped: $droppedRows)")
            if (truncatedFrames) appendLine("frame-statistics.csv reached its $LOG_CAPACITY-row limit; later frames are in the aggregates only.")
            if (truncatedGl) appendLine("gl-statistics.csv reached its $LOG_CAPACITY-row limit; later frames are in the aggregates only.")
            appendLine("Per-frame CSVs are only filled while a recording is running.")
        }
        out["performance-summary.txt"] = summary.toByteArray(Charsets.UTF_8)

        out["performance-events.csv"] = buildString {
            appendLine("t_ms,category,event,detail")
            for (e in evs) appendLine("${e.tMs},${csv(e.category)},${csv(e.name)},${csv(e.detail)}")
        }.toByteArray(Charsets.UTF_8)

        out["frame-statistics.csv"] = buildString {
            appendLine("t_ms,total_ms,input_ms,animation_ms,layout_ms,draw_ms,sync_ms,command_issue_ms,swap_ms,gpu_ms")
            val n = fl[0] as Int
            val t = fl[1] as LongArray
            val cols = (2..10).map { fl[it] as FloatArray }
            for (i in 0 until n) appendLine("${t[i]}," + cols.joinToString(",") { f1(it[i]) })
        }.toByteArray(Charsets.UTF_8)

        out["media-statistics.csv"] = buildString {
            appendLine("t_ms,layer_id,kind,event,value_a,value_b,detail")
            for (r in rows) appendLine("${r.tMs},${r.layerId},${csv(r.kind)},${csv(r.event)},${r.a},${r.b},${csv(r.detail)}")
        }.toByteArray(Charsets.UTF_8)

        out["gl-statistics.csv"] = buildString {
            appendLine("t_ms,prepare_ms,commands_ms,finish_ms,swap_ms,swap_ok")
            val n = gl[0] as Int
            val t = gl[1] as LongArray; val p = gl[2] as FloatArray; val c = gl[3] as FloatArray; val f = gl[4] as FloatArray; val sw = gl[5] as FloatArray; val ok = gl[6] as BooleanArray
            for (i in 0 until n) appendLine("${t[i]},${f1(p[i])},${f1(c[i])},${f1(f[i])},${f1(sw[i])},${ok[i]}")
        }.toByteArray(Charsets.UTF_8)

        out["system-statistics.csv"] = buildString {
            appendLine("t_ms,cpu_percent_of_one_core,java_heap_mb,native_heap_mb,pss_mb,gc_count,gc_time_ms")
            for (i in sysOrder) appendLine("${sysT(i)},${f1(sysCpu(i))},${f1(sysJava(i))},${f1(sysNative(i))},${if (sysPss(i) < 0f) "" else f1(sysPss(i))},${sysGcCount(i)},${sysGcMs(i)}")
        }.toByteArray(Charsets.UTF_8)

        out["thread-statistics.csv"] = buildString {
            appendLine("t_ms,tid,thread_name,cpu_percent_of_one_core")
            for (r in threadRows) appendLine("${r.tMs},${r.tid},${csv(r.name)},${f1(r.cpuPct)}")
        }.toByteArray(Charsets.UTF_8)

        out["test-session.txt"] = buildString {
            appendLine("Scenario: $scenario")
            appendLine("Recording duration: $durationS s")
            appendLine("Session start: $startTxt"); appendLine("Session end: $endTxt")
            appendLine("Profiler version: $PROFILER_VERSION")
            appendLine("Tester notes: ${notes.ifBlank { "(none)" }}")
        }.toByteArray(Charsets.UTF_8)
        return out
    }

    // SysLog accessors (index-based, taken after the lock because the arrays are only appended to)
    private fun sysT(i: Int) = sysLog.t[i]
    private fun sysCpu(i: Int) = sysLog.cpu[i]
    private fun sysJava(i: Int) = sysLog.javaMb[i]
    private fun sysNative(i: Int) = sysLog.nativeMb[i]
    private fun sysPss(i: Int) = sysLog.pssMb[i]
    private fun sysGcCount(i: Int) = sysLog.gcCount[i]
    private fun sysGcMs(i: Int) = sysLog.gcMs[i]
}
