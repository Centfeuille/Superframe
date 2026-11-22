package com.example.inbetween

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream

/*
 * Copy / Paste of a selection, for drawing layers, the background layer and vector layers. One clipboard for the session (not saved in the .inb): the newest copy
 * replaces the older one, and Paste works on any layer or frame of any cut, not only where the copy was made.
 *  - Drawing / background layer: the selected pixels are copied (nothing is cleared) and pasted as an embedded-image ink (Ink kind 3 carrying PNG bytes, the same
 *    kind imported images use), so it is saved, undone and redrawn like any other stroke. The pasted pixels end up selected, so Transform is one tap away.
 *  - Vector layer: the selected lines, or only the selected stretches of lines in Points mode, are copied and pasted as new lines of the active keyframe (one undo step).
 */
object SelectionClipboard {
    sealed class Item
    /** [source]: the strokes list of the cel it was copied from (pasting there again shifts the copy so it does not hide the original). */
    class Pixels(val png: ByteArray, val left: Int, val top: Int, val w: Int, val h: Int, val source: Any?) : Item() { var pastes = 0 }
    class Lines(val strokes: List<VectorStroke>, val source: Any?) : Item() { var pastes = 0 }

    var item by mutableStateOf<Item?>(null)
        private set

    private const val NUDGE = 24f

    fun describe(): String = when (val i = item) {
        null -> ""
        is Pixels -> "Copied drawing ${i.w} \u00D7 ${i.h} px"
        is Lines -> "Copied ${i.strokes.size} line${if (i.strokes.size == 1) "" else "s"}"
    }

    /** Copies the current selection of the active layer. Returns a short message for the person (a Toast). */
    fun copy(cut: Cut, ed: Editor): String = if (cut.drawLayer?.vector == true) copyLines(cut, ed) else copyPixels(cut, ed)

    /** Pastes the clipboard onto the active layer and frame. Returns a short message for the person. */
    fun paste(cut: Cut, ed: Editor): String {
        if (ed.xform != null) return "Apply or cancel the transform first"
        return when (val i = item) {
            null -> "Nothing copied yet: make a selection and tap Copy"
            is Pixels -> pastePixels(cut, ed, i)
            is Lines -> pasteLines(cut, ed, i)
        }
    }

    private fun copyPixels(cut: Cut, ed: Editor): String {
        val s = ed.sel ?: return "Select something to copy first"
        val layer = cut.drawLayer ?: return "Select a drawing, background or vector layer"
        val cel = layer.celAt(cut.time) ?: return "There is no drawing on this frame"
        val ts = TileCache.get(cel.strokes)
        ts.sync(cel.strokes)
        val lf = ts.copyRuns(runsOf(s.mask, s.w, s.h, s.ox, s.oy)) ?: return "Nothing is drawn inside the selection"
        val bos = ByteArrayOutputStream()
        lf.bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        item = Pixels(bos.toByteArray(), lf.left, lf.top, lf.bmp.width, lf.bmp.height, cel.strokes)
        return "Copied ${lf.bmp.width} \u00D7 ${lf.bmp.height} px. Paste works on any layer or frame"
    }

    private fun pastePixels(cut: Cut, ed: Editor, p: Pixels): String {
        val layer = cut.drawLayer?.takeIf { !it.vector } ?: return "A copied drawing pastes onto a drawing or background layer, not a vector layer"
        if (layer.locked) return "This layer is locked"
        val cel = layer.celAt(cut.time) ?: return "There is no drawing on this frame"
        val nudge = if (p.source === cel.strokes) { p.pastes++; NUDGE * ((p.pastes - 1) % 8 + 1) } else 0f
        val ox = p.left + nudge.toInt(); val oy = p.top + nudge.toInt()
        val m = Matrix(); m.setTranslate(ox.toFloat(), oy.toFloat())
        val v = FloatArray(9); m.getValues(v)
        val ink = Ink(FloatArray(0), 0, Brush(""), 3, null, v, p.png)
        cut.commit(cel, ink)
        val ts = TileCache.get(cel.strokes)
        ts.sync(cel.strokes)
        // select what was pasted: Transform (move, scale, rotate) is one tap away
        val bmp = InkImageCache.get(ink)
        if (bmp != null) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            ed.sel = Sel(ox, oy, w, h, BooleanArray(w * h) { (px[it] ushr 24) != 0 })
        }
        return "Pasted"
    }

    private fun copyLines(cut: Cut, ed: Editor): String {
        val sc = vecScope(cut, ed.refAll) ?: return "Select a visible, unlocked vector layer"
        if (ed.vecSel.isEmpty() || !sc.sameCels(ed.vecSelCels)) return "Select some lines or points to copy first"
        val out = ArrayList<VectorStroke>()
        var source: Any? = null
        for (c in sc.cels.indices) {
            val lk = sc.localKeys(ed.vecSel, c)
            if (lk.isEmpty()) continue
            val list = sc.perCel[c]
            // deleting everything that is NOT selected leaves the selected lines whole and the selected stretches of the others
            val part = VectorEdit.delete(list, VectorEdit.allPoints(list) - lk)
            if (part.isNotEmpty() && source == null) source = sc.cels[c].strokes
            out.addAll(part)
        }
        if (out.isEmpty()) return "Nothing to copy: select at least two neighbouring points of a line"
        item = Lines(out, source)
        return "Copied ${out.size} line${if (out.size == 1) "" else "s"}. Paste works on any vector layer or frame"
    }

    private fun pasteLines(cut: Cut, ed: Editor, p: Lines): String {
        val layer = cut.drawLayer?.takeIf { it.vector } ?: return "Copied lines paste onto a vector layer, not a drawing or background layer"
        if (layer.locked) return "This layer is locked"
        if (!layer.visible) return "This layer is hidden"
        val sc = vecScope(cut, ed.refAll) ?: return "Select a visible, unlocked vector layer"
        val c = sc.layers.indexOfFirst { it === layer }
        if (c < 0) return "There is no keyframe on this frame"
        val existing = sc.perCel[c]
        val ids = existing.map { it.id }.toHashSet()
        val nudge = if (p.source === sc.cels[c].strokes) { p.pastes++; NUDGE * ((p.pastes - 1) % 8 + 1) } else 0f
        // a line keeps its id when it is pasted onto another keyframe (so it is still the same line to the interpolation); on a clash it gets a new one
        val fresh = p.strokes.map { s ->
            val moved = if (nudge == 0f) s else s.copy(points = s.points.map { it.copy(x = it.x + nudge, y = it.y + nudge) })
            moved.copy(id = if (s.id in ids) newStrokeId() else s.id, fill = if (s.fill == 2) 1 else s.fill) // a live fill would recompute from its old seed place: paste it baked
        }
        applyScope(cut, sc, sc.perCel.mapIndexed { i, l -> if (i == c) existing + fresh else l })
        vecScope(cut, ed.refAll)?.let { ns ->
            val c2 = ns.layers.indexOfFirst { it === layer }
            if (c2 >= 0) {
                val keys = HashSet<Long>()
                for (k in fresh.indices) for (pi in fresh[k].points.indices) keys.add(vecKey(ns.offsets[c2] + existing.size + k, pi))
                ed.vecSelCels = ns.cels; ed.vecSel = keys // the pasted lines stay selected: drag or transform them straight away
            }
        }
        return "Pasted ${fresh.size} line${if (fresh.size == 1) "" else "s"}"
    }
}

fun toastMessage(ctx: android.content.Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

/**
 * Paste widget for drawing / background layers while the Selection tool is on but nothing is selected (the selection bar, which also has Paste, is not there then).
 * Vector layers have Copy / Paste in their own bar. Always pressable once something is copied: on a layer that cannot take it, it says why.
 */
@Composable
fun ClipboardBar(ed: Editor, cut: Cut, modifier: Modifier = Modifier) {
    val item = SelectionClipboard.item ?: return
    val layer = cut.layer ?: return
    if (ed.tool != 2 || ed.sel != null || ed.xform != null || cut.playing) return
    if (layer.vector || (layer.kind != 0 && layer.kind != 4)) return
    val ctx = LocalContext.current
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Filled.ContentPaste, on = true, tip = "Paste what you copied onto this layer and frame") { toastMessage(ctx, SelectionClipboard.paste(cut, ed)) }
        Text(SelectionClipboard.describe(), fontSize = 12.sp, color = Color(0xFFE6E6EC), modifier = Modifier.padding(end = 10.dp))
    }
}
