package com.example.inbetween

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.MediaCodec
import android.media.MediaExtractor
import android.content.ContentResolver
import android.net.Uri
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.graphics.toArgb
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Output frame size: the visible camera's resolution, otherwise the cut size. */
fun outSize(cut: Cut): Pair<Int, Int> { val c = cut.activeCamera()?.cam; return if (c != null) c.w to c.h else cut.w to cut.h }

/** Renders timeline cell `tick` at w x h: all visible layers, framed by the visible camera (position, scale, rotation) if any. */
fun renderBitmap(cut: Cut, tick: Int, w: Int, h: Int, useCamera: Boolean = true): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(android.graphics.Color.WHITE)
    val cam = if (useCamera) cut.activeCamera()?.cam else null
    if (cam != null) {
        val s = cam.sc(tick); val fw = cam.w * s; val fh = cam.h * s
        cv.scale(w / fw, h / fh); cv.translate(fw / 2f, fh / 2f); cv.rotate(-cam.rot(tick)); cv.translate(-cam.x(tick, cut.w), -cam.y(tick, cut.h))
    } else cv.scale(w / cut.w.toFloat(), h / cut.h.toFloat())
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (l in cut.layers) {
        // Reference layers are visual guides only; never flatten them into exports or project thumbnails.
        if (!l.visible || l.kind == 5) continue
        val list = l.drawCel(tick)?.strokes ?: continue
        if (list.isEmpty()) continue
        paint.alpha = (l.opacity * 255).toInt()
        val tr = cut.transformFor(l, tick)
        val transformed = l.group >= 0 && cut.layers.any { it.isGroup && it.id == l.group && it.transformFolder }
        if (transformed) {
            cv.save(); cv.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); cv.rotate(tr.rotation); cv.scale(tr.scale, tr.scale); cv.translate(-cut.w / 2f, -cut.h / 2f)
        }
        cv.drawBitmap(CelCache.get(list, cut.w, cut.h).bmp, 0f, 0f, paint)
        if (transformed) cv.restore()
    }
    return bmp
}

/** Splits the playback window into runs where no layer changes: (first tick, length). Layers have independent timing. */
fun segments(cut: Cut): List<Pair<Int, Int>> {
    val lo = cut.firstTick(); val hi = cut.lastTick() + 1
    if (cut.activeCamera()?.cam?.let { listOf(it.xs, it.ys, it.ss, it.rs).any { ch -> ch.size > 1 } } == true || cut.layers.any { it.isGroup && it.transformFolder && it.transformChannels().any { ch -> ch.size > 1 } }) return (lo until hi).map { it to 1 } // animated camera / folder transform: every cell differs
    val marks = sortedSetOf(lo, hi)
    cut.layers.filter { it.isGroup && it.transformFolder }.flatMap { it.transformChannels().flatten() }.forEach { if (it.t in (lo + 1) until hi) marks.add(it.t) }
    for (l in cut.layers) { var a = 0; for (c in l.cels) { a += c.dur; if (a in (lo + 1) until hi) marks.add(a) } }
    return marks.toList().zipWithNext { x, y -> x to (y - x) }
}

// ---------- GIF ----------

/** Per-frame median-cut palette built from a 5-bit RGB histogram. This avoids the severe banding of a fixed 6x6x6 cube. */
private data class GifBin(val index: Int, val count: Int) {
    val r get() = (index shr 10) and 31
    val g get() = (index shr 5) and 31
    val b get() = index and 31
}
private class GifBox(val bins: MutableList<GifBin>) {
    val population get() = bins.sumOf { it.count }
    fun range(channel: Int): Int {
        val values = bins.map { when (channel) { 0 -> it.r; 1 -> it.g; else -> it.b } }
        return (values.maxOrNull() ?: 0) - (values.minOrNull() ?: 0)
    }
}

private fun gifPaletteAndIndices(pixels: IntArray): Pair<ByteArray, ByteArray> {
    val histogram = IntArray(1 shl 15)
    for (pixel in pixels) {
        val r = (pixel shr 19) and 31
        val g = (pixel shr 11) and 31
        val b = (pixel shr 3) and 31
        histogram[(r shl 10) or (g shl 5) or b]++
    }
    val bins = ArrayList<GifBin>()
    for (i in histogram.indices) if (histogram[i] != 0) bins.add(GifBin(i, histogram[i]))
    val boxes = mutableListOf(GifBox(bins))
    while (boxes.size < 256) {
        val candidate = boxes.indices
            .filter { boxes[it].bins.size > 1 }
            .maxByOrNull { idx ->
                val box = boxes[idx]
                maxOf(box.range(0), box.range(1), box.range(2)) * box.population.toLong()
            } ?: break
        val box = boxes.removeAt(candidate)
        val channel = (0..2).maxByOrNull { box.range(it) } ?: 0
        val sorted = box.bins.sortedBy { when (channel) { 0 -> it.r; 1 -> it.g; else -> it.b } }
        val half = (box.population + 1) / 2
        var seen = 0
        var split = 1
        for (i in sorted.indices) {
            seen += sorted[i].count
            if (seen >= half) { split = (i + 1).coerceIn(1, sorted.size - 1); break }
        }
        boxes.add(GifBox(sorted.subList(0, split).toMutableList()))
        boxes.add(GifBox(sorted.subList(split, sorted.size).toMutableList()))
    }
    val palette = ByteArray(256 * 3)
    val lookup = IntArray(1 shl 15)
    boxes.forEachIndexed { colorIndex, box ->
        var count = 0L; var rr = 0L; var gg = 0L; var bb = 0L
        for (bin in box.bins) {
            count += bin.count
            rr += (bin.r * 255L / 31L) * bin.count
            gg += (bin.g * 255L / 31L) * bin.count
            bb += (bin.b * 255L / 31L) * bin.count
            lookup[bin.index] = colorIndex
        }
        val den = count.coerceAtLeast(1L)
        palette[colorIndex * 3] = (rr / den).toInt().toByte()
        palette[colorIndex * 3 + 1] = (gg / den).toInt().toByte()
        palette[colorIndex * 3 + 2] = (bb / den).toInt().toByte()
    }
    val last = (boxes.size - 1).coerceAtLeast(0)
    for (i in boxes.size until 256) {
        palette[i * 3] = palette[last * 3]
        palette[i * 3 + 1] = palette[last * 3 + 1]
        palette[i * 3 + 2] = palette[last * 3 + 2]
    }
    val indices = ByteArray(pixels.size)
    for (i in pixels.indices) {
        val px = pixels[i]
        val key = (((px shr 19) and 31) shl 10) or (((px shr 11) and 31) shl 5) or ((px shr 3) and 31)
        indices[i] = lookup[key].toByte()
    }
    return palette to indices
}

private fun lzw(px: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(px.size / 4 + 16)
    val keys = IntArray(8192); val vals = IntArray(8192)
    var bits = 0; var nb = 0; var size = 9; var next = 258
    fun emit(c: Int) {
        bits = bits or (c shl nb); nb += size
        while (nb >= 8) { out.write(bits and 255); bits = bits ushr 8; nb -= 8 }
    }
    fun reset() { keys.fill(-1); size = 9; next = 258 }
    reset(); emit(256)
    var pre = px[0].toInt() and 255
    for (i in 1 until px.size) {
        val c = px[i].toInt() and 255
        val key = (pre shl 8) or c
        var h = (key * -1640531535) ushr 19
        while (keys[h] != -1 && keys[h] != key) h = (h + 1) and 8191
        if (keys[h] == key) { pre = vals[h]; continue }
        emit(pre)
        if (next < 4096) { keys[h] = key; vals[h] = next; if (next == (1 shl size)) size++; next++ }
        else { emit(256); reset() }
        pre = c
    }
    emit(pre); emit(257)
    if (nb > 0) out.write(bits and 255)
    return out.toByteArray()
}

fun exportGif(cut: Cut, scale: Float, out: OutputStream, progress: (Int, Int) -> Unit) {
    val (ow, oh) = outSize(cut)
    val w = (ow * scale).toInt().coerceAtLeast(1); val h = (oh * scale).toInt().coerceAtLeast(1)
    val o = BufferedOutputStream(out, 1 shl 16)
    fun s(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
    // No global table: each frame carries its own adaptive 256-color table.
    o.write("GIF89a".toByteArray()); s(w); s(h); o.write(0x70); o.write(0); o.write(0)
    o.write(0x21); o.write(0xFF); o.write(11); o.write("NETSCAPE2.0".toByteArray()); o.write(3); o.write(1); s(0); o.write(0)
    val segs = segments(cut); val n = segs.size
    var exactCentiseconds = 0.0
    var writtenCentiseconds = 0
    for (fi in 0 until n) {
        progress(fi, n)
        exactCentiseconds += 100.0 * segs[fi].second.toDouble() / cut.fps.coerceAtLeast(1)
        val cumulative = kotlin.math.round(exactCentiseconds).toInt()
        val delay = (cumulative - writtenCentiseconds).coerceAtLeast(1)
        writtenCentiseconds = cumulative
        val bmp = renderBitmap(cut, segs[fi].first, w, h)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val (palette, idx) = gifPaletteAndIndices(px)
        o.write(0x21); o.write(0xF9); o.write(4); o.write(0); s(delay); o.write(0); o.write(0)
        o.write(0x2C); s(0); s(0); s(w); s(h); o.write(0x87); o.write(palette)
        o.write(8)
        val data = lzw(idx)
        var p = 0
        while (p < data.size) { val len = minOf(255, data.size - p); o.write(len); o.write(data, p, len); p += len }
        o.write(0)
    }
    o.write(0x3B); o.flush()
}

// ---------- MP4 (H.264) ----------

fun exportMp4(cut: Cut, fd: FileDescriptor, progress: (Int, Int) -> Unit, resolver: ContentResolver? = null, includeAudio: Boolean = false) {
    val (ow, oh) = outSize(cut)
    val sc = minOf(1f, 1920f / maxOf(ow, oh))
    val w = ((ow * sc).toInt() and 1.inv()).coerceAtLeast(16)
    val h = ((oh * sc).toInt() and 1.inv()).coerceAtLeast(16)
    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 6).coerceIn(2_000_000, 40_000_000))
        setInteger(MediaFormat.KEY_FRAME_RATE, cut.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = enc.createInputSurface()
    enc.start()
    val mux = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    data class AudioSource(val extractor: MediaExtractor, val muxTrack: Int, val startUs: Long, val endUs: Long, val offsetUs: Long)
    val audioSources = ArrayList<AudioSource>()
    try {
        if (includeAudio && resolver != null) {
            val lo = cut.firstTick(); val hi = cut.lastTick() + 1
            for (layer in cut.layers.filter { it.kind == 6 && it.visible && it.mediaUri.isNotBlank() }) {
                val clipStart = layer.mediaStartFrame
                val clipEnd = clipStart + layer.mediaFrames(cut.fps)
                val fromTick = maxOf(lo, clipStart); val toTick = minOf(hi, clipEnd)
                if (toTick <= fromTick) continue
                val extractor = MediaExtractor()
                try {
                    val mediaUri = Uri.parse(layer.mediaUri)
                    if (mediaUri.scheme == "file") {
                        extractor.setDataSource(mediaUri.path!!)
                    } else {
                        resolver.openFileDescriptor(mediaUri, "r")!!.use { pfd -> extractor.setDataSource(pfd.fileDescriptor) }
                    }
                    val audioIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                        extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    if (audioIndex == null) { extractor.release(); continue }
                    val sourceFormat = extractor.getTrackFormat(audioIndex)
                    extractor.selectTrack(audioIndex)
                    val startUs = ((fromTick - clipStart) * 1_000_000L / cut.fps.coerceAtLeast(1))
                    val endUs = minOf(layer.mediaDurationMs * 1000L, ((toTick - clipStart) * 1_000_000L / cut.fps.coerceAtLeast(1)))
                    val offsetUs = ((fromTick - lo) * 1_000_000L / cut.fps.coerceAtLeast(1))
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    audioSources.add(AudioSource(extractor, mux.addTrack(sourceFormat), startUs, endUs, offsetUs))
                } catch (e: Exception) { runCatching { extractor.release() }; throw e }
            }
        }
    } catch (e: Exception) {
        audioSources.forEach { runCatching { it.extractor.release() } }
        runCatching { enc.stop() }; enc.release(); surface.release(); mux.release()
        throw e
    }
    var track = -1; var started = false; var written = 0
    val info = MediaCodec.BufferInfo()
    data class PendingAudio(val source: AudioSource, val bytes: ByteArray, val ptsUs: Long)
    val audioQueue = java.util.PriorityQueue<PendingAudio>(compareBy { it.ptsUs })
    fun nextAudio(source: AudioSource): PendingAudio? {
        val buffer = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
        while (true) {
            buffer.clear()
            val size = source.extractor.readSampleData(buffer, 0)
            if (size < 0) return null
            val pts = source.extractor.sampleTime
            source.extractor.advance()
            if (pts >= source.endUs) return null
            if (pts >= source.startUs && size > 0) {
                val data = ByteArray(size)
                buffer.position(0); buffer.get(data, 0, size)
                return PendingAudio(source, data, source.offsetUs + (pts - source.startUs))
            }
        }
    }
    fun seedAudioQueue() { audioSources.forEach { source -> nextAudio(source)?.let { audioQueue.add(it) } } }
    fun writeAudioUntil(videoPtsUs: Long, flush: Boolean = false) {
        while (audioQueue.isNotEmpty() && (flush || audioQueue.peek().ptsUs <= videoPtsUs)) {
            val sample = audioQueue.remove()
            val bi = MediaCodec.BufferInfo().apply { set(0, sample.bytes.size, sample.ptsUs, 0) }
            mux.writeSampleData(sample.source.muxTrack, java.nio.ByteBuffer.wrap(sample.bytes), bi)
            nextAudio(sample.source)?.let { audioQueue.add(it) }
        }
    }

    fun drain(end: Boolean) {
        if (end) enc.signalEndOfInputStream()
        while (true) {
            val i = enc.dequeueOutputBuffer(info, 10_000)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return }
            else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start(); started = true; seedAudioQueue() }
            else if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) {
                    val videoPts = written * 1_000_000L / cut.fps
                    writeAudioUntil(videoPts)
                    info.presentationTimeUs = videoPts; written++ // exact frame timing
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    mux.writeSampleData(track, buf, info)
                }
                enc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    try {
        val segs = segments(cut); val n = segs.size
        for (fi in 0 until n) {
            progress(fi, n)
            val bmp = renderBitmap(cut, segs[fi].first, w, h)
            repeat(segs[fi].second) { // held frames are submitted once per timeline cell
                val c = surface.lockHardwareCanvas()
                c.drawBitmap(bmp, 0f, 0f, null)
                surface.unlockCanvasAndPost(c)
                drain(false)
            }
            bmp.recycle()
        }
        drain(true)
        if (started) writeAudioUntil(Long.MAX_VALUE, flush = true)
    } finally {
        runCatching { enc.stop() }; enc.release(); surface.release()
        audioSources.forEach { runCatching { it.extractor.release() } }
        runCatching { if (started) mux.stop() }; mux.release()
    }
}

// ---------- image export ----------

/** One layer's own drawing at a timeline cell, transparent (PNG) or on white (JPG). */
private fun layerImage(cut: Cut, l: Layer, tick: Int, jpg: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(cut.w, cut.h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    if (jpg) cv.drawColor(android.graphics.Color.WHITE)
    val list = l.drawCel(tick)?.strokes
    if (list != null && list.isNotEmpty()) {
        val tr = cut.transformFor(l, tick)
        val transformed = l.group >= 0 && cut.layers.any { it.isGroup && it.id == l.group && it.transformFolder }
        if (transformed) { cv.save(); cv.translate(cut.w / 2f + tr.x, cut.h / 2f + tr.y); cv.rotate(tr.rotation); cv.scale(tr.scale, tr.scale); cv.translate(-cut.w / 2f, -cut.h / 2f) }
        cv.drawBitmap(CelCache.get(list, cut.w, cut.h).bmp, 0f, 0f, null)
        if (transformed) cv.restore()
    }
    return bmp
}

fun exportCurrentFrame(cut: Cut, jpg: Boolean, out: OutputStream) {
    val bmp = renderBitmap(cut, cut.time, cut.w, cut.h, useCamera = false)
    bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, out)
    bmp.recycle()
}

/** Zip: Cut_name/Group_name/Layer_001.png (no group folder for ungrouped layers, never a layer folder). Visible layers only.
 *  unique = one image per drawing; otherwise one image per timeline cell. */
fun exportImageSequence(cut: Cut, unique: Boolean, jpg: Boolean, out: OutputStream, progress: (Int, Int) -> Unit) {
    val layers = cut.layers.filter { it.visible && !it.isGroup && (it.kind == 0 || it.kind == 4) }
    val total = cut.total()
    val lo = cut.firstTick(); val hi = cut.lastTick()
    fun ticksOf(l: Layer): List<Int> {
        val animatedFolder = cut.layers.firstOrNull { it.isGroup && it.id == l.group && it.transformFolder }?.transformChannels()?.any { it.size > 1 } == true
        return if (unique && !animatedFolder) l.cels.indices.filter { i -> l.cels[i].strokes.isNotEmpty() && l.startOf(i) in lo..hi && (0 until i).none { j -> l.cels[j].sameDrawing(l.cels[i]) } /* a clone is not a unique drawing */ }.map { l.startOf(it) }
        else (lo..hi).toList() // playback window only; animated group transforms make each tick visually distinct
    }
    val all = layers.sumOf { ticksOf(it).size }
    var done = 0
    ZipOutputStream(out).use { z ->
        val root = safeName(cut.name)
        for (l in layers) {
            val gname = cut.layers.firstOrNull { it.id == l.group }?.name
            val dir = root + "/" + (if (gname != null) safeName(gname) + "/" else "")
            var n = 0
            for (t in ticksOf(l)) {
                n++
                val bmp = layerImage(cut, l, t, jpg)
                z.putNextEntry(ZipEntry(dir + safeName(l.name) + "_" + "%03d".format(n) + (if (jpg) ".jpg" else ".png")))
                bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, z)
                z.closeEntry(); bmp.recycle()
                progress(++done, all)
            }
        }
    }
}
