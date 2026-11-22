package com.example.inbetween

/**
 * Data structures of the performance profiler (see PerformanceProfiler.kt). Everything here has a fixed size:
 * rolling rings for the live numbers, capped logs for recordings, and a histogram for the frame-time distribution.
 */

const val PROFILER_VERSION = 2

/** Rolling window of float samples; the newest [capacity] values are kept. Thread-safe, constant memory. */
class FloatRing(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var next = 0
    private var count = 0
    @Synchronized fun add(v: Float) { data[next] = v; next = (next + 1) % capacity; if (count < capacity) count++ }
    @Synchronized fun clear() { next = 0; count = 0 }
    @Synchronized fun size() = count
    /** Oldest-to-newest copy. */
    @Synchronized fun snapshot(): FloatArray {
        val out = FloatArray(count)
        val start = if (count < capacity) 0 else next
        for (i in 0 until count) out[i] = data[(start + i) % capacity]
        return out
    }
}

/** Summary of a set of float samples. */
data class Stats(val count: Int, val avg: Float, val min: Float, val max: Float, val p50: Float, val p95: Float, val p99: Float) {
    companion object { val EMPTY = Stats(0, 0f, 0f, 0f, 0f, 0f, 0f) }
}

fun statsOf(values: FloatArray): Stats {
    if (values.isEmpty()) return Stats.EMPTY
    val sorted = values.copyOf().also { it.sort() }
    fun pct(p: Float) = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    var sum = 0.0
    for (v in sorted) sum += v
    return Stats(sorted.size, (sum / sorted.size).toFloat(), sorted.first(), sorted.last(), pct(0.50f), pct(0.95f), pct(0.99f))
}

/** Frame-time distribution: [bins] one-millisecond bins plus one overflow bin. */
class Histogram(private val bins: Int = 64) {
    private val counts = LongArray(bins + 1)
    @Synchronized fun add(ms: Float) { counts[ms.toInt().coerceIn(0, bins)]++ }
    @Synchronized fun clear() { counts.fill(0L) }
    @Synchronized fun copy(): LongArray = counts.copyOf()
    fun binCount() = bins
}

/** One timestamped diagnostic event (performance-events.csv). */
data class PerfEvent(val tMs: Long, val category: String, val name: String, val detail: String)

/** One row of media-statistics.csv. */
data class MediaRow(val tMs: Long, val layerId: Int, val kind: String, val event: String, val a: Long, val b: Long, val detail: String)

/** Capped per-frame log of a recording (frame-statistics.csv). Columns are milliseconds; gpu < 0 means "not available on this Android version". */
class FrameLog(private val capacity: Int) {
    val t = LongArray(capacity)
    val total = FloatArray(capacity); val input = FloatArray(capacity); val anim = FloatArray(capacity); val layout = FloatArray(capacity)
    val draw = FloatArray(capacity); val sync = FloatArray(capacity); val issue = FloatArray(capacity); val swap = FloatArray(capacity); val gpu = FloatArray(capacity)
    var size = 0; private set
    var truncated = false; private set
    @Synchronized fun add(tMs: Long, tot: Float, inp: Float, an: Float, lay: Float, dr: Float, sy: Float, iss: Float, sw: Float, gp: Float) {
        if (size >= capacity) { truncated = true; return }
        val i = size++
        t[i] = tMs; total[i] = tot; input[i] = inp; anim[i] = an; layout[i] = lay; draw[i] = dr; sync[i] = sy; issue[i] = iss; swap[i] = sw; gpu[i] = gp
    }
    @Synchronized fun clear() { size = 0; truncated = false }
}

/** Capped per-frame log of the GL compositor (gl-statistics.csv). */
class GlLog(private val capacity: Int) {
    val t = LongArray(capacity)
    val prep = FloatArray(capacity); val commands = FloatArray(capacity); val finish = FloatArray(capacity); val swap = FloatArray(capacity)
    val ok = BooleanArray(capacity)
    var size = 0; private set
    var truncated = false; private set
    @Synchronized fun add(tMs: Long, p: Float, c: Float, f: Float, s: Float, success: Boolean) {
        if (size >= capacity) { truncated = true; return }
        val i = size++
        t[i] = tMs; prep[i] = p; commands[i] = c; finish[i] = f; swap[i] = s; ok[i] = success
    }
    @Synchronized fun clear() { size = 0; truncated = false }
}

/** One Linux task/thread CPU sample. CPU is percent of one logical core during the sample interval. */
data class ThreadCpuSample(val tMs: Long, val tid: Int, val name: String, val cpuPct: Float)

/** Once-a-second process samples (system-statistics.csv). pss < 0: not sampled this second. */
class SysLog(private val capacity: Int) {
    val t = LongArray(capacity)
    val cpu = FloatArray(capacity); val javaMb = FloatArray(capacity); val nativeMb = FloatArray(capacity); val pssMb = FloatArray(capacity)
    val gcCount = LongArray(capacity); val gcMs = LongArray(capacity)
    private var next = 0
    var size = 0; private set
    @Synchronized fun add(tMs: Long, c: Float, j: Float, n: Float, p: Float, gc: Long, gcTime: Long) {
        val i = next; next = (next + 1) % capacity; if (size < capacity) size++
        t[i] = tMs; cpu[i] = c; javaMb[i] = j; nativeMb[i] = n; pssMb[i] = p; gcCount[i] = gc; gcMs[i] = gcTime
    }
    @Synchronized fun clear() { next = 0; size = 0 }
    /** Oldest-to-newest indices. */
    @Synchronized fun order(): IntArray { val start = if (size < capacity) 0 else next; return IntArray(size) { (start + it) % capacity } }
}

/** What the HUD shows; built a few times a second off the main thread. */
data class HudSnapshot(
    val refreshHz: Float = 60f, val budgetMs: Float = 16.67f,
    val frames: Stats = Stats.EMPTY, val fps: Float = 0f, val jankPct: Float = 0f, val budgetViolations: Long = 0, val droppedFrames: Long = 0,
    val cpuPct: Float = -1f, val cpuCores: Int = 1, val javaMb: Float = 0f, val nativeMb: Float = 0f, val pssMb: Float = -1f,
    val gcCount: Long = 0, val gcMs: Long = 0, val gcCountDelta: Long = 0,
    val glPrep: Stats = Stats.EMPTY, val glCommands: Stats = Stats.EMPTY, val glFinish: Stats = Stats.EMPTY, val glSwap: Stats = Stats.EMPTY,
    val glFrames: Long = 0, val glSkipped: Long = 0, val glFailed: Long = 0, val glSwapFailures: Long = 0, val glErrors: Long = 0,
    val audioUnderruns: Long = 0, val videoDropped: Long = 0, val mediaErrors: Long = 0, val audioPlayers: Int = 0, val videoPlayers: Int = 0,
    val tickInterval: Stats = Stats.EMPTY, val tickProcessing: Stats = Stats.EMPTY, val tickMissedFrames: Long = 0,
    val driftMs: Stats = Stats.EMPTY
) {
    companion object { val EMPTY = HudSnapshot() }
}

val PERF_SCENARIOS = listOf(
    "A - Drawing only",
    "B - Video only",
    "C - Audio only",
    "D - Audio and video",
    "E - Combined workload",
    "F - Extended playback"
)
