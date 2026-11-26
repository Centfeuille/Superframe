package com.example.inbetween

import androidx.compose.ui.geometry.Offset
import android.net.Uri
import android.content.Context
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cuts are compact binary (.inb). V29 stores vector anchor values as variable-length numbers (anchor numbers above 99); it is written only when a stroke needs it. V28 adds a "relative" flag to guide joints (offset from the unmoved line); V27 adds non-destructive interpolation guide joints; V26 adds Uniform timing flags (folder + attached layers); V25 adds vector point reference anchors; V24 adds vector layers (a layer flag, ink kind 5 strokes and per-keyframe interpolation settings); V23 adds the vanishing-point-vs-guide-line magnetism mode; V22 stores physical focal length directly; V21 adds adjustable curvilinear ray density; V20 adds per-guide 30-degree rotation snapping and camera FOV; V19 adds per-guide curvilinear strength; V18 adds independent perspective-guide controls; V17 adds selection-clipped strokes and warp meshes; V16 adds brush flow / cap, tip shape, texture controls and wet pressure, and embeds imported brush assets.
 * v2 (per-layer timing) and v1 (shared frames) are still read and converted.
 */
object Store {
    private const val V1 = 0x494E4231
    private const val V2 = 0x494E4232
    private const val V3 = 0x494E4233
    private const val V4 = 0x494E4234
    private const val V5 = 0x494E4235
    private const val V6 = 0x494E4236
    private const val V7 = 0x494E4237
    private const val V8 = 0x494E4238
    private const val V9 = 0x494E4239
    private const val V10 = 0x494E423A
    private const val V11 = 0x494E423B
    private const val V14 = 0x494E423E // V14: each stroke also stores its dab jitter (scatter, size, rotation, opacity + seed) and wet mix, when used
    private const val V13 = 0x494E423D // V13: each stroke also stores tip, texture id / strength and anti-aliasing
    private const val V18 = 0x494E4242 // V18: independent perspective guide settings
    private const val V19 = 0x494E4243 // V19: per-guide curvilinear strength
    private const val V20 = 0x494E4244 // V20: per-guide 30-degree rotation lock and camera horizontal FOV
    private const val V21 = 0x494E4245 // V21: per-guide curvilinear ray count
    private const val V22 = 0x494E4246 // V22: persist physical camera focal length directly
    private const val V23 = 0x494E4247 // V23: choose VP-ray or guide-line stroke magnetism
    private const val V24 = 0x494E4248 // V24: vector layers (ink kind 5) and per-keyframe interpolation settings
    private const val V25 = 0x494E4249 // V25: vector point reference anchors
    private const val V26 = 0x494E424A // V26: Uniform timing flags
    private const val V27 = 0x494E424B // V27: non-destructive interpolation guide joints
    private const val V28 = 0x494E424C // V28: guide joints carry a "relative" flag (offset from the unmoved line instead of an absolute spot)
    private const val V29 = 0x494E424D // V29: vector anchor values are variable-length numbers instead of one byte (anchor numbers above 99 / 255). Written ONLY when a stroke needs it; has the V28 layout otherwise.
    private const val V17 = 0x494E4241 // V17: strokes drawn inside a selection store its runs; region warps (ink kind 4: runs + mesh vertices)
    private const val V16 = 0x494E4240 // V16: stroke flow / cap, tip shape, texture controls, wet pressure, and the imported stamps / textures the strokes use
    private const val V15 = 0x494E423F // V15: per-cel persistent-canvas flag and inbetween label
    private const val V12 = 0x494E423C // V12: cloned frames store a link to the cel whose drawing they share

    fun save(dir: File, c: Cut) {
        val tmp = File(dir, "cut_${c.id}.tmp")
        tmp.outputStream().use { write(c, it) }
        tmp.renameTo(File(dir, "cut_${c.id}.inb"))
    }

    private fun wv(o: DataOutputStream, v: Int) {
        var z = (v shl 1) xor (v shr 31)
        while ((z and -128) != 0) { o.writeByte((z and 127) or 128); z = z ushr 7 }
        o.writeByte(z)
    }
    private fun rv(i: DataInputStream): Int {
        var r = 0; var s = 0
        while (true) { val b = i.readUnsignedByte(); r = r or ((b and 127) shl s); if (b < 128) break; s += 7 }
        return (r ushr 1) xor -(r and 1)
    }

    private fun writeInk(o: DataOutputStream, k: Ink, wide: Boolean = false) {
        o.writeByte(k.kind); o.writeInt(k.color); o.writeBoolean(k.b.erase)
        o.writeFloat(k.b.size); o.writeFloat(k.b.opacity); o.writeFloat(k.b.hardness); o.writeFloat(k.b.spacing); o.writeFloat(k.b.texture)
        o.writeByte(k.b.tip); o.writeByte(k.b.textureId); o.writeByte((k.b.textureAmt * 100f).roundToInt().coerceIn(0, 100)); o.writeByte((k.b.aa * 100f).roundToInt().coerceIn(0, 100))
        fun pct(f: Float) = (f * 100f).roundToInt().coerceIn(0, 100)
        val jit = k.kind == 0 && (k.b.scatter > 0f || k.b.sizeJitter > 0f || k.b.angleJitter > 0f || k.b.opacityJitter > 0f)
        val wet = k.kind == 0 && k.b.wet > 0.01f
        o.writeByte((if (jit) 1 else 0) or (if (wet) 2 else 0)) // V14: 1 = jitter block follows, 2 = wet block follows
        if (jit) { o.writeByte(pct(k.b.scatter)); o.writeByte(pct(k.b.sizeJitter)); o.writeByte(pct(k.b.angleJitter)); o.writeByte(pct(k.b.opacityJitter)); o.writeInt(k.seed) }
        if (wet) { o.writeByte(pct(k.b.wet)); o.writeByte(pct(k.b.wetLength)) }
        // V16: a second flag byte; 1 = tip shape, 2 = flow / cap, 4 = texture controls, 8 = imported stamp / texture ids, 16 = wet pressure
        val shape = k.kind == 0 && (abs(k.b.tipAngle) > 0.01f || k.b.tipFollow || k.b.roundness < 0.99f)
        val flowB = k.kind == 0 && (k.b.flow < 0.995f || k.b.capped)
        val texB = k.kind == 0 && (k.b.texScale != 1f || k.b.texInvert || k.b.texMoving)
        val imgB = k.kind == 0 && (k.b.tipImg != 0 || k.b.texImg != 0)
        val wpB = k.kind == 0 && abs(k.b.wetPressure) > 0.005f
        val clipB = k.kind == 0 && (k.runs?.isNotEmpty() == true) // V17: 32 = the selection the stroke was drawn inside follows the points
        o.writeByte((if (shape) 1 else 0) or (if (flowB) 2 else 0) or (if (texB) 4 else 0) or (if (imgB) 8 else 0) or (if (wpB) 16 else 0) or (if (clipB) 32 else 0))
        if (shape) { o.writeShort(k.b.tipAngle.roundToInt().coerceIn(-180, 180)); o.writeBoolean(k.b.tipFollow); o.writeByte(pct(k.b.roundness)) }
        if (flowB) { o.writeByte(pct(k.b.flow)); o.writeBoolean(k.b.capped) }
        if (texB) { o.writeByte((k.b.texScale * 50f).roundToInt().coerceIn(12, 200)); o.writeBoolean(k.b.texInvert); o.writeBoolean(k.b.texMoving) }
        if (imgB) { o.writeInt(k.b.tipImg); o.writeInt(k.b.texImg) }
        if (wpB) o.writeByte((k.b.wetPressure * 100f).roundToInt().coerceIn(-100, 100) + 100)
        if (k.kind == 0) {
            val n = k.p.size / 4
            wv(o, n)
            var px = 0; var py = 0
            for (j in 0 until n) {
                val x = (k.p[j * 4] * 16f).roundToInt(); val y = (k.p[j * 4 + 1] * 16f).roundToInt()
                wv(o, x - px); wv(o, y - py); px = x; py = y
                o.writeByte((k.p[j * 4 + 2] * 64f).roundToInt().coerceIn(0, 255))
                o.writeByte((k.p[j * 4 + 3] * 255f).roundToInt().coerceIn(0, 255))
            }
            if (clipB) writeRuns(o, k.runs!!)
        } else if (k.kind == 3) {
            val d = k.data ?: ByteArray(0)
            o.writeInt(d.size); o.write(d); for (f in k.m ?: FloatArray(9)) o.writeFloat(f)
        } else if (k.kind == 5) {
            // V24 vector stroke: id / brush text, width, closed, opacity, then x, y, pressure, elevation per point (full precision: vectors are edited and animated)
            val d = k.data ?: ByteArray(0)
            val mm = k.m ?: floatArrayOf(4f, 0f, 1f)
            o.writeInt(d.size); o.write(d)
            o.writeFloat(mm.getOrElse(0) { 4f }); o.writeFloat(mm.getOrElse(1) { 0f }); o.writeFloat(mm.getOrElse(2) { 1f })
            // V25: sparse reference anchors (point index, rank)
            val na = if (mm.size > 3) (mm.size - 3) / 2 else 0
            wv(o, na)
            for (q in 0 until na) { wv(o, mm[3 + q * 2].toInt()); if (wide) wv(o, mm[4 + q * 2].toInt()) else o.writeByte(mm[4 + q * 2].toInt().coerceIn(0, 255)) } // V29: the value (rank / flags) is a variable-length number
            val n = k.p.size / 4
            wv(o, n)
            for (f in k.p) o.writeFloat(f)
        } else {
            writeRuns(o, k.runs ?: IntArray(0))
            if (k.kind == 2) for (f in k.m ?: FloatArray(9)) o.writeFloat(f)
            else if (k.kind == 4) { val mm = k.m ?: FloatArray(0); wv(o, mm.size); for (f in mm) o.writeFloat(f) }
        }
    }

    private fun writeRuns(o: DataOutputStream, r: IntArray) {
        wv(o, r.size / 3)
        var py = 0
        for (i in r.indices step 3) { wv(o, r[i] - py); wv(o, r[i + 1]); wv(o, r[i + 2] - r[i + 1]); py = r[i] }
    }

    private fun readRuns(i: DataInputStream): IntArray {
        val n = rv(i); val r = IntArray(n * 3); var py = 0
        for (j in 0 until n) { py += rv(i); val x0 = rv(i); r[j * 3] = py; r[j * 3 + 1] = x0; r[j * 3 + 2] = x0 + rv(i) }
        return r
    }

    private fun readInk(i: DataInputStream, v: Int): Ink {
        val kind = if (v >= 3) i.readUnsignedByte() else 0
        val color = i.readInt(); val erase = i.readBoolean()
        val size = i.readFloat(); val op = i.readFloat(); val hd = i.readFloat(); val sp = i.readFloat(); val tx = i.readFloat()
        var seed = 0
        var clipFlag = false
        val brush = if (v >= 13) {
            val tp = i.readUnsignedByte(); val tid = i.readUnsignedByte(); val ta = i.readUnsignedByte() / 100f; val aa = i.readUnsignedByte() / 100f
            var sc = 0f; var sj = 0f; var aj = 0f; var oj = 0f; var wt = 0f; var wl = 0.5f
            if (v >= 14) {
                val ext = i.readUnsignedByte()
                if (ext and 1 != 0) { sc = i.readUnsignedByte() / 100f; sj = i.readUnsignedByte() / 100f; aj = i.readUnsignedByte() / 100f; oj = i.readUnsignedByte() / 100f; seed = i.readInt() }
                if (ext and 2 != 0) { wt = i.readUnsignedByte() / 100f; wl = i.readUnsignedByte() / 100f }
            }
            var ta2 = 0f; var fol = false; var rnd = 1f; var flw = 1f; var cap = false; var tsc = 1f; var tin = false; var tmv = false; var timg = 0; var xim = 0; var wpr = 0f
            if (v >= 16) {
                val ext2 = i.readUnsignedByte()
                if (ext2 and 1 != 0) { ta2 = i.readShort().toFloat(); fol = i.readBoolean(); rnd = (i.readUnsignedByte() / 100f).coerceIn(0.1f, 1f) }
                if (ext2 and 2 != 0) { flw = (i.readUnsignedByte() / 100f).coerceIn(0.01f, 1f); cap = i.readBoolean() }
                if (ext2 and 4 != 0) { tsc = (i.readUnsignedByte() / 50f).coerceIn(0.25f, 4f); tin = i.readBoolean(); tmv = i.readBoolean() }
                if (ext2 and 8 != 0) { timg = i.readInt(); xim = i.readInt() }
                if (ext2 and 16 != 0) wpr = ((i.readUnsignedByte() - 100) / 100f).coerceIn(-1f, 1f)
                if (v >= 17 && ext2 and 32 != 0) clipFlag = true
            }
            Brush("", erase, size, op, hd, sp, texture = tx, tip = tp.coerceIn(0, TIP_NAMES.size - 1), textureId = tid.coerceIn(0, TEXTURE_NAMES.size - 1), textureAmt = ta, aa = aa,
                scatter = sc, sizeJitter = sj, angleJitter = aj, opacityJitter = oj, wet = wt, wetLength = wl,
                flow = flw, capped = cap, wetPressure = wpr, tipImg = timg, texImg = xim, texScale = tsc, texInvert = tin, texMoving = tmv, tipAngle = ta2, tipFollow = fol, roundness = rnd)
        } else Brush("", erase, size, op, hd, sp, texture = tx) // older files: plain anti-aliasing (aa 0.5), as they were drawn
        if (kind == 0) {
            val n = rv(i); val p = FloatArray(n * 4); var px = 0; var py = 0
            for (j in 0 until n) {
                px += rv(i); py += rv(i)
                p[j * 4] = px / 16f; p[j * 4 + 1] = py / 16f
                p[j * 4 + 2] = i.readUnsignedByte() / 64f; p[j * 4 + 3] = i.readUnsignedByte() / 255f
            }
            val clip = if (clipFlag) readRuns(i) else null
            return Ink(p, color, brush, 0, clip, null, null, seed)
        }
        if (kind == 3) {
            val d = ByteArray(i.readInt()); i.readFully(d)
            return Ink(FloatArray(0), color, brush, 3, null, FloatArray(9) { i.readFloat() }, d)
        }
        if (kind == 5) {
            val d = ByteArray(i.readInt().coerceIn(0, 1 shl 16)); i.readFully(d)
            var mm = floatArrayOf(i.readFloat(), i.readFloat(), i.readFloat())
            if (v >= 25) {
                val na = rv(i).coerceIn(0, 2_000_000)
                val extra = FloatArray(na * 2)
                for (q in 0 until na) { extra[q * 2] = rv(i).toFloat(); extra[q * 2 + 1] = (if (v >= 29) rv(i) else i.readUnsignedByte()).toFloat() }
                mm = mm + extra
            }
            val n = rv(i).coerceIn(0, 2_000_000)
            val p = FloatArray(n * 4) { i.readFloat() }
            return Ink(p, color, brush, 5, null, mm, d)
        }
        val r = readRuns(i)
        val m = if (kind == 2) FloatArray(9) { i.readFloat() }
            else if (kind == 4) { val cnt = rv(i).coerceIn(0, 400_000); FloatArray(cnt) { i.readFloat() } }
            else null
        return Ink(FloatArray(0), color, brush, kind, r, m)
    }

    private val discardOutput = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0L) {
            val n = input.skip(remaining)
            if (n > 0L) remaining -= n
            else { if (input.read() < 0) throw EOFException("Embedded media ended before its declared size"); remaining-- }
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Embedded media ended before its declared size")
            output.write(buffer, 0, count)
            remaining -= count.toLong()
        }
    }

    /** [embedMedia] false writes a media layer without its bytes (size 0): the reader then keeps the app-owned file:// copy. Same format, used by recovery snapshots. */
    fun write(c: Cut, out: OutputStream, embedMedia: Boolean = true) {
        val o = DataOutputStream(BufferedOutputStream(out))
        val usesUniform = c.layers.any { it.uniformTiming || it.uniformAttached }
        // V29 only when an anchor value does not fit the old single byte (anchor numbers above 99 are written as 1000 + number, see encodeAnchorRank). A V29 file has the V28 layout (guide counts and
        // the Uniform flags block are always written), so `usesGuides` is also true for it. Drawings that only use anchors up to 99 are written exactly as before.
        val wide = c.layers.any { l -> l.cels.any { cel -> cel.strokes.any { k -> k.kind == 5 && k.m?.let { m -> (4 until m.size step 2).any { m[it] > 255f } } == true } } }
        val usesGuides = wide || c.layers.any { l -> l.vector && l.cels.any { !it.vecCfg?.guideJoints.isNullOrEmpty() } }
        o.writeInt(if (wide) V29 else if (usesGuides) V28 else if (usesUniform) V26 else V25); o.writeLong(c.id); o.writeUTF(c.name) // use V27 only when guide joints are present; otherwise retain the older compatible version
        o.writeInt(c.fps); o.writeInt(c.w); o.writeInt(c.h); o.writeInt(c.dpi)
        o.writeInt(c.layers.size)
        for (l in c.layers) {
            o.writeInt(l.id); o.writeUTF(l.name); o.writeBoolean(l.visible); o.writeBoolean(l.locked); o.writeFloat(l.opacity)
            o.writeBoolean(l.isGroup); o.writeInt(l.group); o.writeBoolean(l.collapsed)
            o.writeBoolean(l.transformFolder)
            for (ci in 0..3) { val ch = l.transformChan(ci); o.writeInt(ch.size); for (k in ch) { o.writeInt(k.t); o.writeFloat(k.v); o.writeByte(k.interp); o.writeFloat(k.ease) } }
            o.writeByte(l.kind)
            l.cam?.let { c2 ->
                o.writeInt(c2.w); o.writeInt(c2.h); o.writeBoolean(c2.lockW); o.writeBoolean(c2.lockH); o.writeBoolean(c2.lockA); o.writeFloat(c2.aspect)
                for (ci in 0..3) { val ch = c2.chan(ci); o.writeInt(ch.size); for (k in ch) { o.writeInt(k.t); o.writeFloat(k.v); o.writeByte(k.interp); o.writeFloat(k.ease) } }
            }
            l.persp?.let { p ->
                o.writeFloat(p.horizon); o.writeFloat(p.center); o.writeInt(p.vps.size); for (pv in p.vps) { o.writeByte(pv.kind); o.writeFloat(pv.t) }
                for (g in listOf(p.horizonGuide, p.perpendicularGuide)) { o.writeFloat(g.rotation); o.writeBoolean(g.rotationUnlocked); o.writeBoolean(g.lockVpDistance); o.writeBoolean(g.curvilinear); o.writeFloat(g.curvatureStrength); o.writeBoolean(g.rotationSnap30) }
                o.writeFloat(p.focalLengthMm)
                for (g in listOf(p.horizonGuide, p.perpendicularGuide)) o.writeInt(g.curvilinearRayCount.coerceIn(8, 48))
                o.writeBoolean(p.vanishingPointMagnet)
            }
            if (l.kind == 6 || l.kind == 7) {
                o.writeUTF(l.mediaUri); o.writeLong(l.mediaDurationMs); o.writeInt(l.mediaDurationFrames); o.writeInt(l.mediaStartFrame)
                val mediaFile = runCatching {
                    val uri = Uri.parse(l.mediaUri)
                    if (uri.scheme == "file") uri.path?.let(::File) else null
                }.getOrNull()?.takeIf { it.isFile }
                val mediaSize = if (embedMedia) mediaFile?.length()?.coerceAtLeast(0L) ?: 0L else 0L
                o.writeLong(mediaSize)
                if (mediaSize > 0L) mediaFile!!.inputStream().buffered().use { copyExactly(it, o, mediaSize) }
                o.writeFloat(l.mediaX); o.writeFloat(l.mediaY); o.writeFloat(l.mediaScale); o.writeFloat(l.mediaRotation)
            }
            o.writeBoolean(l.vector) // V24
            o.writeInt(l.cels.size)
            for ((celIndex, cel) in l.cels.withIndex()) {
                o.writeInt(cel.dur)
                o.writeBoolean(cel.persistent); o.writeUTF(cel.inbetweenName)
                if (l.vector) { // V24: the interpolation settings of this keyframe
                    val cfg = cel.vecCfg
                    o.writeBoolean(cfg != null)
                    if (cfg != null) {
                        o.writeByte(cfg.mode.ordinal); o.writeByte(cfg.curveType); o.writeFloat(cfg.ease); o.writeInt(cfg.bakeStep)
                        val bz = cfg.customBezier
                        o.writeBoolean(bz != null && bz.size >= 4)
                        if (bz != null && bz.size >= 4) for (q in 0..3) o.writeFloat(bz[q])
                        if (usesGuides) {
                            o.writeInt(cfg.guideJoints.size)
                            for (g in cfg.guideJoints) { o.writeUTF(g.strokeId); o.writeFloat(g.u); o.writeFloat(g.x); o.writeFloat(g.y); o.writeBoolean(g.rel) }
                        }
                    }
                }
                val link = l.cels.indexOfFirst { it.strokes === cel.strokes } // first cel showing this drawing; a later one is its clone
                if (link in 0 until celIndex) { o.writeInt(link); continue }
                o.writeInt(-1); o.writeInt(cel.strokes.size); for (k in cel.strokes) writeInk(o, k, wide)
            }
        }
        // V16: the imported stamps / textures the strokes use, so the cut opens correctly on a device that never imported them
        val used = LinkedHashMap<Int, Int>() // asset id -> kind
        for (l in c.layers) for (cel in l.cels) for (k in cel.strokes) if (k.kind == 0) {
            if (k.b.tipImg != 0) used[k.b.tipImg] = BrushAssets.TIP
            if (k.b.texImg != 0) used[k.b.texImg] = BrushAssets.TEXTURE
        }
        val assets = used.mapNotNull { (id, kind) -> BrushAssets.png(id)?.let { Triple(id, kind, it) } }
        o.writeInt(assets.size)
        for ((id, kind, png) in assets) { o.writeInt(id); o.writeByte(kind); o.writeInt(png.size); o.write(png) }
        if (usesUniform || usesGuides) { // V26/V27: Uniform timing flags, after the stamp assets
            val flagged = c.layers.filter { it.uniformTiming || it.uniformAttached }
            o.writeInt(flagged.size)
            for (l in flagged) { o.writeInt(l.id); o.writeBoolean(l.uniformTiming); o.writeBoolean(l.uniformAttached) }
        }
        o.flush()
    }

    fun read(input: InputStream, newId: Long? = null, mediaRoot: File? = null, context: Context? = null): Cut {
        val i = DataInputStream(BufferedInputStream(input))
        val v = when (i.readInt()) { V1 -> 1; V2 -> 2; V3 -> 3; V4 -> 4; V5 -> 5; V6 -> 6; V7 -> 7; V8 -> 8; V9 -> 9; V10 -> 10; V11 -> 11; V12 -> 12; V13 -> 13; V14 -> 14; V15 -> 15; V16 -> 16; V17 -> 17; V18 -> 18; V19 -> 19; V20 -> 20; V21 -> 21; V22 -> 22; V23 -> 23; V24 -> 24; V25 -> 25; V26 -> 26; V27 -> 27; V28 -> 28; V29 -> 29; else -> throw IllegalArgumentException("Not an Inbetween cut") }
        val id = i.readLong()
        val c = Cut(i.readUTF(), i.readInt(), i.readInt(), i.readInt(), i.readInt(), newId ?: id)
        c.layers.clear()
        repeat(i.readInt()) {
            val lid = i.readInt(); val name = i.readUTF(); val vis = i.readBoolean(); val lock = i.readBoolean(); val op = i.readFloat()
            var isG = false; var grp = -1; var col = false; var tf = false; val transformChannels = List(4) { ArrayList<CamKey>() }; var kind = 0; var cam: Camera? = null; var pr: Persp? = null
            if (v >= 3) { isG = i.readBoolean(); grp = i.readInt(); col = i.readBoolean() }
            if (v >= 6) {
                tf = i.readBoolean()
                if (v >= 8) {
                    for (ci in 0..3) repeat(i.readInt()) { transformChannels[ci].add(CamKey(i.readInt(), i.readFloat(), i.readUnsignedByte(), i.readFloat())) }
                } else {
                    val legacy = ArrayList<TransformKey>()
                    repeat(i.readInt()) { val t = i.readInt(); val x = i.readFloat(); val y = i.readFloat(); val sc = i.readFloat(); val r = i.readFloat(); val interp = if (v >= 7) i.readUnsignedByte() else 1; val ease = if (v >= 7) i.readFloat() else 0.33f; legacy.add(TransformKey(t, x, y, sc, r, interp, ease)) }
                    legacy.forEach { k -> transformChannels[0].add(CamKey(k.t, k.x, k.interp, k.ease)); transformChannels[1].add(CamKey(k.t, k.y, k.interp, k.ease)); transformChannels[2].add(CamKey(k.t, k.scale, k.interp, k.ease)); transformChannels[3].add(CamKey(k.t, k.rotation, k.interp, k.ease)) }
                }
            }
            if (v >= 4) {
                kind = i.readUnsignedByte()
                if (kind == 2) {
                    val cw = i.readInt(); val chh = i.readInt()
                    cam = Camera(cw, chh).also { c2 ->
                        c2.lockW = i.readBoolean(); c2.lockH = i.readBoolean(); c2.lockA = i.readBoolean(); c2.aspect = i.readFloat()
                        for (ci in 0 until (if (v >= 5) 4 else 3)) { val ch = c2.chan(ci); repeat(i.readInt()) { ch.add(CamKey(i.readInt(), i.readFloat(), i.readUnsignedByte(), i.readFloat())) } }
                    }
                }
                if (kind == 3) pr = Persp(i.readFloat(), i.readFloat()).also { p ->
                    repeat(i.readInt()) { if (v >= 5) p.vps.add(Vp(i.readUnsignedByte(), i.readFloat())) else { val vx = i.readFloat(); i.readFloat(); p.vps.add(Vp(0, vx)) } }
                    if (v >= 18) for (g in listOf(p.horizonGuide, p.perpendicularGuide)) { g.rotation = i.readFloat(); g.rotationUnlocked = i.readBoolean(); g.lockVpDistance = i.readBoolean(); g.curvilinear = i.readBoolean(); if (v >= 19) g.curvatureStrength = i.readFloat().coerceIn(0f, 2f); if (v >= 20) g.rotationSnap30 = i.readBoolean() }
                    if (v >= 20) {
                        val lensValue = i.readFloat()
                        // V20/V21 stored FOV while their VP coordinates were already lens-scaled.
                        // Restore only the equivalent lens value; do not scale those VPs twice.
                        p.focalLengthMm = if (v >= 22) lensValue.coerceIn(1.57f, 500f) else focalLengthForFov(lensValue)
                    }
                    if (v >= 21) for (g in listOf(p.horizonGuide, p.perpendicularGuide)) g.curvilinearRayCount = i.readInt().coerceIn(8, 48)
                    if (v >= 23) p.vanishingPointMagnet = i.readBoolean()
                }
            }
            var mediaUri = ""; var mediaDurationMs = 0L; var mediaDurationFrames = 1; var mediaStartFrame = 0
            if (v >= 9 && (kind == 6 || kind == 7)) {
                mediaUri = i.readUTF(); mediaDurationMs = i.readLong().coerceAtLeast(1L); mediaDurationFrames = i.readInt().coerceAtLeast(1); mediaStartFrame = i.readInt().coerceAtLeast(0)
                if (v >= 10) {
                    val mediaSize = i.readLong().coerceAtLeast(0L)
                    if (mediaSize > 0L && mediaRoot != null) {
                        val mediaDir = File(mediaRoot, "media_assets").apply { mkdirs() }
                        val target = File(mediaDir, "${newId ?: id}_${lid}.media")
                        // Cold start used to rewrite every embedded clip (hundreds of MB) although the app-owned copy is already there.
                        // Same length = same file: these copies are written once and never edited.
                        if (target.isFile && target.length() == mediaSize) skipExactly(i, mediaSize)
                        else target.outputStream().buffered().use { copyExactly(i, it, mediaSize) }
                        mediaUri = Uri.fromFile(target).toString()
                    } else {
                        if (mediaSize > 0L) copyExactly(i, discardOutput, mediaSize)
                        val sourceUri = Uri.parse(mediaUri)
                        if (mediaSize == 0L && context != null && sourceUri.scheme == "content") {
                            copyMediaIntoAppStorage(context, sourceUri)?.let { mediaUri = it }
                        } else if (v >= 10 && mediaSize == 0L && sourceUri.scheme == "file" && sourceUri.path?.let(::File)?.exists() != true) {
                            mediaUri = ""
                        }
                    }
                } else if (context != null && Uri.parse(mediaUri).scheme == "content") {
                    // One-time migration for V9 projects: copy the old provider-backed source
                    // into app storage while its persisted grant is still available.
                    copyMediaIntoAppStorage(context, Uri.parse(mediaUri))?.let { mediaUri = it }
                }
            }
            // Cuts saved after the media fields were lost on reload hold an empty audio / video layer, but the payload was unpacked once to
            // media_assets/<cut>_<layer>.media: link it again and re-read its duration (the clip starts at frame 0).
            if ((kind == 6 || kind == 7) && v >= 10 && mediaUri.isBlank() && mediaRoot != null) {
                val old = File(File(mediaRoot, "media_assets"), "${newId ?: id}_${lid}.media")
                if (old.isFile && old.length() > 0L) {
                    mediaUri = Uri.fromFile(old).toString()
                    if (mediaDurationMs <= 1L && context != null) readMediaDurationMs(context, Uri.fromFile(old))?.let { mediaDurationMs = it; mediaDurationFrames = (it * c.fps / 1000.0).roundToInt().coerceAtLeast(1) }
                }
            }
            var mediaOffsetX = 0f; var mediaOffsetY = 0f; var mediaScaleValue = 1f; var mediaRotationValue = 0f
            if (v >= 11 && (kind == 6 || kind == 7)) { mediaOffsetX = i.readFloat(); mediaOffsetY = i.readFloat(); mediaScaleValue = i.readFloat().coerceIn(0.05f, 20f); mediaRotationValue = i.readFloat() }
            val shownName = if (tf && name.startsWith("Transformation Folder")) "Transformation" + name.removePrefix("Transformation Folder") else name // renamed default
            val l = Layer(lid, shownName, isG, kind, cam, pr).apply { visible = vis; locked = lock; opacity = op; group = grp; collapsed = col; transformFolder = tf; this.mediaUri = mediaUri; this.mediaDurationMs = mediaDurationMs; this.mediaDurationFrames = mediaDurationFrames; this.mediaStartFrame = mediaStartFrame; mediaX = mediaOffsetX; mediaY = mediaOffsetY; mediaScale = mediaScaleValue; mediaRotation = mediaRotationValue; for (ci in 0..3) transformChan(ci).addAll(transformChannels[ci]) }
            if (v >= 2) {
                val isVector = v >= 24 && i.readBoolean() // V24: vector layer flag
                l.vector = isVector
                l.cels.clear()
                repeat(i.readInt()) {
                    val dur = i.readInt().coerceAtLeast(1)
                    val persistent = if (v >= 15) i.readBoolean() else false
                    val inbetweenName = if (v >= 15) i.readUTF() else ""
                    var vcfg: InterpolationConfig? = null
                    if (isVector && i.readBoolean()) { // V24: this keyframe's interpolation settings
                        val mode = InterpolationMode.values().getOrElse(i.readUnsignedByte()) { InterpolationMode.SAME_STROKE }
                        val curve = i.readUnsignedByte().coerceIn(0, 4); val ease = i.readFloat().coerceIn(0f, 1f); val step = i.readInt().coerceIn(1, 240)
                        val bz = if (i.readBoolean()) FloatArray(4) { i.readFloat() } else null
                        val guides = if (v >= 27) List(i.readInt().coerceIn(0, 10000)) { VectorGuideJoint(i.readUTF(), i.readFloat().coerceIn(0f, 1f), i.readFloat(), i.readFloat(), v >= 28 && i.readBoolean()) } else emptyList()
                        vcfg = InterpolationConfig(mode, curve, ease, bz, step, guides)
                    }
                    val link = if (v >= 12) i.readInt() else -1
                    val source = if (link >= 0) l.cels.getOrNull(link) else null
                    if (source != null) l.cels.add(source.cloneLinked().also { it.dur = dur; it.persistent = persistent; it.inbetweenName = inbetweenName; it.vecCfg = vcfg })
                    else {
                        val cel = Cel(dur).also { it.persistent = persistent; it.inbetweenName = inbetweenName; it.vecCfg = vcfg }
                        if (link < 0) repeat(i.readInt()) { cel.strokes.add(readInk(i, v)) }
                        l.cels.add(cel)
                    }
                }
                if (l.cels.isEmpty() && !isG && (kind == 0 || kind == 4 || kind == 5)) l.cels.add(Cel(1))
            }
            c.layers.add(l)
        }
        if (v >= 16) repeat(i.readInt().coerceIn(0, 4096)) { // imported stamps / textures used by the strokes
            val aid = i.readInt(); val kind = i.readUnsignedByte(); val len = i.readInt()
            if (len < 0 || len > 8_000_000) throw IllegalArgumentException("Bad embedded stamp")
            val png = ByteArray(len); i.readFully(png)
            BrushAssets.register(aid, kind, png, persist = true)
        }
        if (v >= 26) repeat(i.readInt().coerceIn(0, 100_000)) { // V26/V27: Uniform timing flags
            val lid = i.readInt(); val folder = i.readBoolean(); val attached = i.readBoolean()
            c.layers.firstOrNull { it.id == lid }?.let { it.uniformTiming = folder; it.uniformAttached = attached }
        }
        c.relabelLayers()
        if (v == 1) { // shared frame list: every layer gets one cel per frame, with that frame's duration
            c.layers.forEach { it.cels.clear() }
            repeat(i.readInt()) {
                val dur = i.readInt().coerceAtLeast(1); val strokes = HashMap<Int, List<Ink>>()
                repeat(i.readInt()) { val lid = i.readInt(); strokes[lid] = List(i.readInt()) { readInk(i, 1) } }
                c.layers.forEach { l -> val cel = Cel(dur); strokes[l.id]?.let { cel.strokes.addAll(it) }; l.cels.add(cel) }
            }
            c.layers.forEach { if (it.cels.isEmpty()) it.cels.add(Cel(1)) }
        }
        // Reference-image layers still use external paths. Audio/video bytes are embedded by V10; independent video transforms are stored by V11.
        c.layers.removeAll { layer ->
            if (layer.kind != 5) false else {
                // A reference layer may hold several images: only the ones whose file can no longer be opened are dropped; the layer goes when none is left.
                val cel = layer.cels.firstOrNull()
                cel?.strokes?.removeAll { ink ->
                    val path = ink.data?.toString(Charsets.UTF_8)
                    ink.kind == 3 && (path.isNullOrBlank() || !ReferenceBitmapCache.exists(path))
                }
                cel == null || cel.strokes.none { it.kind == 3 }
            }
        }
        if (c.layers.none { !it.isGroup && it.kind == 0 }) c.layers.add(Layer(0, "A"))
        c.nextLayer = c.layers.maxOf { it.id } + 1
        c.layerId = c.layers.first { !it.isGroup && it.kind == 0 }.id
        return c
    }

    fun pack(c: Cut, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("cut.inb")); write(c, z); z.closeEntry()
            z.putNextEntry(ZipEntry("thumb.png"))
            outSize(c).let { (ow, oh) -> renderBitmap(c, 0, 320, (320 * oh / ow).coerceAtLeast(1)) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, z)
            z.closeEntry()
        }
    }

    fun unpack(input: InputStream, mediaRoot: File? = null, context: Context? = null): Cut? {
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "cut.inb") return read(z, System.currentTimeMillis(), mediaRoot, context)
                e = z.nextEntry
            }
        }
        return null
    }

    fun load(dir: File, context: Context? = null): List<Cut> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("cut_") && it.name.endsWith(".inb") }
            .mapNotNull { f -> runCatching { f.inputStream().use { read(it, mediaRoot = dir, context = context) } }.getOrNull() }.sortedBy { it.id }
}
