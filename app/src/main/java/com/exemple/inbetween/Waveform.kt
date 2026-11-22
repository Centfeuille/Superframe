package com.example.inbetween

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.platform.LocalContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Decoded audio: mono 16-bit PCM at [rate] Hz (for waveform drawing and scrubbing) plus one peak byte per [bucket] samples. */
class Wave(val rate: Int, val pcm: ShortArray, val bucket: Int) {
    val peaks: ByteArray = ByteArray((pcm.size + bucket - 1) / bucket).also { p ->
        for (i in p.indices) {
            var m = 0
            val a = i * bucket
            val b = min(pcm.size, a + bucket)
            for (k in a until b) { val v = abs(pcm[k].toInt()); if (v > m) m = v }
            p[i] = (m * 255 / 32768).coerceIn(0, 255).toByte()
        }
    }
    val peakMax: Int = (peaks.maxOfOrNull { it.toInt() and 255 } ?: 1).coerceAtLeast(1)
    val bytes: Long get() = pcm.size * 2L + peaks.size
}

/** Loads, caches (memory, and cacheDir for app-owned files) and shares decoded audio. One decode per file, in the background. */
object AudioPeaks {
    const val RATE = 22050      // the rate everything is decoded to (sources below this keep their own rate and do not scrub)
    const val BUCKET = 221      // samples per peak, about 10 ms
    private const val MAX_SECONDS = 20 * 60
    private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
    private const val MAGIC = 0x57465631

    /** What the timeline shows next to an audio clip while it has no waveform: "analyzing audio…" or "no waveform: reason". */
    val status = mutableStateMapOf<String, String>()

    private val lock = Any()
    private val cache = LinkedHashMap<String, Wave>(8, 0.75f, true)
    private val inflight = HashMap<String, Deferred<Wave?>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun peek(uri: String): Wave? = synchronized(lock) { cache[uri] }

    suspend fun get(ctx: Context, uri: String): Wave? {
        if (uri.isBlank()) return null
        peek(uri)?.let { return it }
        val app = ctx.applicationContext
        val job = synchronized(lock) { inflight.getOrPut(uri) { scope.async { load(app, uri) } } }
        val w = try { job.await() } finally { if (job.isCompleted) synchronized(lock) { inflight.remove(uri) } }
        if (w != null) synchronized(lock) {
            cache[uri] = w
            var total = cache.values.sumOf { it.bytes }
            val iter = cache.entries.iterator()
            while (total > MAX_CACHE_BYTES && cache.size > 1 && iter.hasNext()) { // least recently used first, never the newest
                val e = iter.next()
                if (e.value !== w) { total -= e.value.bytes; iter.remove() }
            }
        }
        return w
    }

    private suspend fun load(ctx: Context, uriString: String): Wave? {
        val t0 = SystemClock.elapsedRealtime()
        status[uriString] = "analyzing audio…"
        try {
            val uri = Uri.parse(uriString)
            val file = if (uri.scheme == "file") uri.path?.let(::File) else null
            val cacheFile = file?.takeIf { it.isFile }?.let { File(ctx.cacheDir, "waveforms/${it.name}_${it.length()}_${it.lastModified()}.wf") }
            val cached = cacheFile?.takeIf { it.isFile }?.let { f -> runCatching { readCache(f) }.getOrNull() }
            if (cached != null) {
                status.remove(uriString)
                CrashDiagnostics.log("INFO", "Audio waveform loaded from cache: ${cached.pcm.size} samples at ${cached.rate} Hz")
                return cached
            }
            if (file != null && !file.isFile) throw IllegalStateException("the audio file is missing")
            val w = decode(ctx, uri)
            if (cacheFile != null) runCatching { writeCache(cacheFile, w) }
            status.remove(uriString)
            CrashDiagnostics.log("INFO", "Audio decoded: ${w.pcm.size} samples at ${w.rate} Hz (${w.pcm.size * 1000L / w.rate} ms) in ${SystemClock.elapsedRealtime() - t0} ms")
            return w
        } catch (e: CancellationException) {
            status.remove(uriString)
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            status[uriString] = "no waveform: $why"
            CrashDiagnostics.log("WARN", "Audio decode failed after ${SystemClock.elapsedRealtime() - t0} ms: ${e.javaClass.simpleName}: $why")
            return null
        }
    }

    private fun readCache(f: File): Wave? = DataInputStream(f.inputStream().buffered()).use { i ->
        if (i.readInt() != MAGIC) return null
        val rate = i.readInt(); val bucket = i.readInt(); val n = i.readInt()
        if (rate <= 0 || bucket <= 0 || n <= 0 || n > RATE * MAX_SECONDS) return null
        val raw = ByteArray(n * 2)
        i.readFully(raw)
        val pcm = ShortArray(n)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        Wave(rate, pcm, bucket)
    }

    private fun writeCache(f: File, w: Wave) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC); o.writeInt(w.rate); o.writeInt(w.bucket); o.writeInt(w.pcm.size)
            val raw = ByteArray(w.pcm.size * 2)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(w.pcm)
            o.write(raw)
        }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /**
     * Decodes the first audio track to mono PCM at most RATE Hz (box-filter downsampling). Throws with a readable reason
     * if it cannot (no audio track, unsupported encoding, decoder stalled, nothing decoded).
     */
    private suspend fun decode(ctx: Context, uri: Uri): Wave {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("no audio track in the file")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("audio track without a type")
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var channels = 2
            var outRate = 0
            var step = 1.0
            var pcmFloat = false
            fun setFormat(f: MediaFormat) {
                val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (enc != AudioFormat.ENCODING_PCM_16BIT && enc != AudioFormat.ENCODING_PCM_FLOAT) throw IllegalStateException("unsupported PCM encoding $enc")
                pcmFloat = enc == AudioFormat.ENCODING_PCM_FLOAT
                val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                outRate = min(sr, RATE)
                step = sr.toDouble() / outRate
            }
            setFormat(fmt)
            CrashDiagnostics.log("INFO", "Audio decode start: $mime, ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz, $channels ch")
            var phase = 0.0
            var acc = 0L
            var cnt = 0
            var out = ShortArray(RATE * 30)
            var n = 0
            fun emit(mono: Int) {
                acc += mono; cnt++; phase += 1.0
                if (phase >= step) {
                    phase -= step
                    if (n == out.size) out = out.copyOf(out.size * 2)
                    out[n++] = (acc / cnt).toShort(); acc = 0L; cnt = 0
                }
            }
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var idle = 0
            while (!outDone && n < RATE * MAX_SECONDS) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inDone) {
                    val ib = c.dequeueInputBuffer(0)
                    if (ib >= 0) {
                        val sz = ex.readSampleData(c.getInputBuffer(ib)!!, 0)
                        if (sz < 0) { c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ib, 0, sz, ex.sampleTime, 0); ex.advance() }
                        progressed = true
                    }
                }
                val ob = c.dequeueOutputBuffer(info, if (progressed) 0L else 3_000L)
                if (ob >= 0) {
                    progressed = true
                    val buf = c.getOutputBuffer(ob)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    if (pcmFloat) {
                        val fb = buf.asFloatBuffer()
                        val frames = fb.remaining() / channels
                        for (f in 0 until frames) {
                            var m = 0f
                            for (k in 0 until channels) m += fb.get()
                            emit((m / channels * 32767f).toInt().coerceIn(-32768, 32767))
                        }
                    } else {
                        val sh = buf.asShortBuffer()
                        val frames = sh.remaining() / channels
                        for (f in 0 until frames) {
                            var m = 0
                            for (k in 0 until channels) m += sh.get().toInt()
                            emit(m / channels)
                        }
                    }
                    c.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    setFormat(c.outputFormat)
                }
                if (progressed) idle = 0 else if (++idle > 2000) throw IllegalStateException("the decoder stopped responding")
            }
            if (n == 0) throw IllegalStateException("the decoder produced no audio")
            return Wave(outRate, out.copyOf(n), BUCKET)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}

/**
 * Audio scrubbing: while the playhead is moved by hand, a short grain (about one frame of sound) around the playhead is
 * played at the NORMAL rate, so the pitch never changes however fast you scrub. Grains are 1024 samples, Hann-windowed
 * and overlapped by half. Moving backwards plays the grain reversed. One grain is played per playhead change, so a
 * single frame step gives one frame of sound and a drag gives a stream of them. Needs the decoded audio of the layer
 * ([AudioPeaks]); audio that is not decoded yet is silent.
 */
object AudioScrub {
    private const val RATE = AudioPeaks.RATE
    private const val N = 1024
    private const val H = 512
    private const val IDLE_MS = 400L

    private class Src(val wave: Wave, val startMs: Double)

    @Volatile private var sources: List<Src> = emptyList()
    @Volatile private var targetMs = 0.0
    @Volatile private var dir = 1
    @Volatile private var seq = 0
    @Volatile private var touchedAt = 0L
    private var thread: Thread? = null
    private var lastWarn = 0L

    /** The playhead moved by hand from [prev] to [frame]. */
    fun scrub(cut: Cut, frame: Int, prev: Int) {
        val fps = cut.fps.coerceAtLeast(1)
        val list = ArrayList<Src>()
        for (l in cut.layers) {
            if (l.kind != 6 || !l.visible || l.mediaUri.isBlank()) continue
            val w = AudioPeaks.peek(l.mediaUri) ?: continue
            if (w.rate != RATE) continue
            list.add(Src(w, l.mediaStartFrame * 1000.0 / fps))
        }
        if (list.isEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastWarn > 10_000L && cut.layers.any { it.kind == 6 && it.visible }) {
                lastWarn = now
                CrashDiagnostics.log("INFO", "Audio scrub: no decoded audio ready for this cut (see the audio clip label)")
            }
            return
        }
        val frameMs = 1000.0 / fps
        sources = list
        targetMs = frame * frameMs + frameMs / 2.0
        dir = if (frame < prev) -1 else 1
        touchedAt = SystemClock.uptimeMillis()
        seq++
        synchronized(this) {
            if (thread == null) thread = Thread({ loop() }, "audio-scrub").also { it.isDaemon = true; it.start() }
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minBuf, H * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            track = buildTrack()
            track.play()
            val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }
            val tail = FloatArray(H)
            val grain = FloatArray(N)
            val out = ShortArray(H)
            var lastSeq = seq
            var first = true
            var finishing = false
            while (true) {
                java.util.Arrays.fill(grain, 0f)
                if (!finishing && (first || seq != lastSeq)) {
                    first = false
                    lastSeq = seq
                    val rev = dir < 0
                    val centre = targetMs
                    for (s in sources) {
                        val first0 = ((centre - s.startMs) * RATE / 1000.0).toLong() - N / 2
                        val pcm = s.wave.pcm
                        for (k in 0 until N) {
                            val si = if (rev) first0 + (N - 1 - k) else first0 + k
                            if (si >= 0 && si < pcm.size) grain[k] += pcm[si.toInt()] / 32768f
                        }
                    }
                }
                for (i in 0 until H) {
                    val v = (tail[i] + grain[i] * win[i]) * 0.9f
                    out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    tail[i] = grain[i + H] * win[i + H]
                }
                track.write(out, 0, H)
                if (finishing) return
                if (SystemClock.uptimeMillis() - touchedAt > IDLE_MS) {
                    synchronized(this) {
                        if (SystemClock.uptimeMillis() - touchedAt > IDLE_MS && seq == lastSeq) { thread = null; finishing = true }
                    }
                }
            }
        } catch (e: Exception) {
            CrashDiagnostics.log("WARN", "Audio scrub stopped: ${e.javaClass.simpleName}: ${e.message}")
            synchronized(this) { thread = null }
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }
}

/** Decodes the audio layers of the open cut in the background and plays scrub audio when the playhead is moved by hand (not during playback). */
@Composable
fun AudioScrubHost(ed: Editor) {
    val ctx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { ed.open?.layers?.filter { it.kind == 6 }?.map { it.mediaUri }.orEmpty() }.collect { uris ->
                for (u in uris) launch { AudioPeaks.get(ctx, u) }
            }
        }
        var lastCut = -1L
        var lastT = 0
        snapshotFlow { ed.open?.let { it.id to it.time } }.collect { cur ->
            val c = ed.open
            if (cur == null || c == null) { lastCut = -1L; return@collect }
            if (cur.first != lastCut) { lastCut = cur.first; lastT = cur.second; return@collect }
            val prev = lastT
            lastT = cur.second
            if (prev != cur.second && !c.playing && ed.audioScrub) AudioScrub.scrub(c, cur.second, prev)
        }
    }
}

/** The decoded audio of [uri] once it is ready (null while decoding or if it cannot be decoded). */
@Composable
fun rememberWave(uri: String): State<Wave?> {
    val ctx = LocalContext.current
    return produceState<Wave?>(AudioPeaks.peek(uri), uri) { value = AudioPeaks.get(ctx, uri) }
}

/** Fills the clip with its waveform (mirrored peaks, loudest peak = full height). Draws at most about 12000 columns whatever the zoom. */
@Composable
fun WaveformCanvas(wave: Wave, modifier: Modifier, color: Color = Color(0x99FFFFFF)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val total = wave.peaks.size
        if (total == 0 || w < 1f || h < 1f) return@Canvas
        val cols = min(total, min(12_000, max(1, (w / 2f).toInt())))
        val mid = h / 2f
        val half = h / 2f - 3f
        val pts = ArrayList<Offset>(cols * 2)
        val norm = 255f / wave.peakMax
        for (c in 0 until cols) {
            val a = c.toLong() * total / cols
            val b = max(a + 1, (c + 1).toLong() * total / cols)
            var m = 0
            for (i in a.toInt() until min(total, b.toInt())) { val v = wave.peaks[i].toInt() and 255; if (v > m) m = v }
            val amp = (m * norm / 255f).coerceIn(0.02f, 1f) * half
            val x = (c + 0.5f) * w / cols
            pts.add(Offset(x, mid - amp)); pts.add(Offset(x, mid + amp))
        }
        drawPoints(pts, PointMode.Lines, color, strokeWidth = max(1f, w / cols))
    }
}
