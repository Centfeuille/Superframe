package com.example.inbetween

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Imported brush stamps (tips) and textures. An asset is a grey-scale mask kept as a PNG (white pixels, alpha = amount of ink),
 * identified by the CRC-32 of that PNG, so the same image always has the same id. Brushes and strokes store only the id:
 * the library (filesDir/stamps) serves the brushes, and every cut embeds the assets its strokes use (Store V16), so a cut
 * opens correctly on a device that never imported them.
 */
object BrushAssets {
    const val TIP = 0
    const val TEXTURE = 1
    private val masks = HashMap<Int, Bitmap>()
    private val pngs = HashMap<Int, ByteArray>()
    private val tiles = HashMap<String, Bitmap>()
    private var dir: File? = null

    /** The imported assets offered in the brush editor, oldest first. */
    val tipIds = mutableStateListOf<Int>()
    val texIds = mutableStateListOf<Int>()

    private fun fileName(id: Int, kind: Int) = (if (kind == TIP) "t_" else "x_") + Integer.toHexString(id) + ".png"

    /** Loads the library from filesDir/stamps. Call once at start-up, before any cut is read. */
    @Synchronized fun init(filesDir: File) {
        val d = File(filesDir, "stamps").also { it.mkdirs() }
        dir = d
        for (f in (d.listFiles() ?: emptyArray()).sortedBy { it.lastModified() }) {
            val n = f.name
            if (!n.endsWith(".png") || n.length < 7) continue
            val kind = when { n.startsWith("t_") -> TIP; n.startsWith("x_") -> TEXTURE; else -> continue }
            val id = n.substring(2, n.length - 4).toLongOrNull(16)?.toInt() ?: continue
            runCatching { register(id, kind, f.readBytes(), persist = false) }
        }
    }

    /** Decodes [png] and makes it available as asset [id]. [persist] also copies it into the library folder. */
    @Synchronized fun register(id: Int, kind: Int, png: ByteArray, persist: Boolean): Boolean {
        if (id == 0 || png.isEmpty() || png.size > 8_000_000) return false
        if (!masks.containsKey(id)) {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return false
            if (bmp.width > 1024 || bmp.height > 1024) return false
            masks[id] = bmp; pngs[id] = png
        }
        val list = if (kind == TIP) tipIds else texIds
        if (id !in list) list.add(id)
        val d = dir
        if (persist && d != null) runCatching {
            val f = File(d, fileName(id, kind))
            if (!f.exists()) { val tmp = File(d, f.name + ".tmp"); tmp.writeBytes(png); if (!tmp.renameTo(f)) tmp.delete() }
        }
        return true
    }

    @Synchronized fun mask(id: Int): Bitmap? = masks[id]
    @Synchronized fun png(id: Int): ByteArray? = pngs[id]

    /** Removes the asset from the library and the pickers. It stays usable in memory, so open strokes keep drawing; cuts that used it embed their own copy. */
    @Synchronized fun forget(id: Int) {
        tipIds.remove(id); texIds.remove(id)
        val d = dir ?: return
        File(d, fileName(id, TIP)).delete(); File(d, fileName(id, TEXTURE)).delete()
    }

    /**
     * Reads an image from [uri] and stores it as a tip or a texture. Tips: if the image has transparency its alpha is the shape,
     * otherwise dark = ink (black stamp on white paper); the result is centred in a square. Textures: bright = full ink.
     * The mask is stretched to the full 0..1 range. Returns the new asset id, or null if the image could not be read.
     */
    fun importImage(ctx: Context, uri: Uri, kind: Int): Int? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })!!
        val k = 256f / max(src.width, src.height)
        val img = if (k < 1f) Bitmap.createScaledBitmap(src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true) else src
        val w = img.width; val h = img.height
        val px = IntArray(w * h); img.getPixels(px, 0, w, 0, 0, w, h)
        var anyAlpha = false
        for (p in px) if ((p ushr 24) < 250) { anyAlpha = true; break }
        val m = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            val luma = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f
            m[i] = if (kind == TIP) (if (anyAlpha) a else 1f - luma) else luma * a + (1f - a)
        }
        var lo = 1f; var hi = 0f
        for (v in m) { lo = min(lo, v); hi = max(hi, v) }
        if (hi - lo > 0.1f && !(kind == TIP && anyAlpha)) for (i in m.indices) m[i] = (m[i] - lo) / (hi - lo)
        val side = if (kind == TIP) max(w, h) else 0
        val tw = if (kind == TIP) side else w; val th = if (kind == TIP) side else h
        val out = IntArray(tw * th)
        val ox = (tw - w) / 2; val oy = (th - h) / 2
        for (y in 0 until h) for (x in 0 until w) out[(y + oy) * tw + x + ox] = ((m[y * w + x] * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        val bmp = Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val crc = CRC32().also { it.update(png) }
        val id = crc.value.toInt().let { if (it == 0) 1 else it }
        if (!register(id, kind, png, persist = true)) throw IllegalStateException("could not store the image")
        id
    }.getOrNull()

    /** Seamless ALPHA_8 tile of an imported texture at strength [amt] (0..1), like Textures.tile. Alpha 255 = full ink. */
    @Synchronized fun textureTile(id: Int, amt: Float, invert: Boolean): Bitmap? {
        val m = masks[id] ?: return null
        val q = (amt.coerceIn(0f, 1f) * 20f).roundToInt()
        return tiles.getOrPut("$id/$q/$invert") {
            val w = m.width; val h = m.height
            val px = IntArray(w * h); m.getPixels(px, 0, w, 0, 0, w, h)
            val s = q / 20f
            val bytes = ByteArray(w * h)
            for (i in px.indices) {
                var ink = (px[i] ushr 24) / 255f
                if (invert) ink = 1f - ink
                bytes[i] = ((1f - s * (1f - ink)).coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            bmp
        }
    }
}
