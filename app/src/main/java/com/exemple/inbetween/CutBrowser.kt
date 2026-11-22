package com.example.inbetween

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** A named folder of Cuts in the main menu. Cuts are not changed by it: which folder a Cut is in lives in [CutLibrary] (library.json), not in the .inb file. */
class CutFolder(val id: Long, name: String, collapsed: Boolean = false) {
    var name by mutableStateOf(name)
    var collapsed by mutableStateOf(collapsed)
}

/**
 * Main-menu organisation: folders, which folder each Cut is in, and the list layout (vertical list / horizontal grid).
 * Saved in library.json in the app files directory (separate from the Cuts, so the .inb format is untouched).
 * A Cut with no entry, or in a folder that no longer exists, is at the top level.
 */
class CutLibrary {
    val folders = mutableStateListOf<CutFolder>()
    val folderOf = mutableStateMapOf<Long, Long>() // cut id -> folder id
    var grid by mutableStateOf(false)               // false = vertical list, true = horizontal grid (GRID_COLUMNS per line)

    fun folderIdOf(c: Cut): Long = folderOf[c.id]?.takeIf { id -> folders.any { it.id == id } } ?: 0L
    fun cutsIn(all: List<Cut>, folderId: Long): List<Cut> = all.filter { folderIdOf(it) == folderId }

    fun addFolder(dir: File): CutFolder {
        val f = CutFolder((folders.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(System.currentTimeMillis() - 1L) + 1L, "Folder ${folders.size + 1}")
        folders.add(f); save(dir); return f
    }
    fun removeFolder(f: CutFolder, dir: File) {
        folderOf.entries.removeAll { it.value == f.id } // its Cuts go back to the top level; nothing is deleted
        folders.remove(f); save(dir)
    }
    fun move(cutId: Long, folderId: Long, dir: File) { if (folderId == 0L) folderOf.remove(cutId) else folderOf[cutId] = folderId; save(dir) }
    fun forget(cutId: Long, dir: File) { if (folderOf.remove(cutId) != null) save(dir) }

    fun load(dir: File) {
        val f = File(dir, FILE)
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            grid = o.optBoolean("grid", false)
            val fa = o.optJSONArray("folders") ?: JSONArray()
            folders.clear()
            for (i in 0 until fa.length()) { val j = fa.getJSONObject(i); folders.add(CutFolder(j.getLong("id"), j.optString("name", "Folder"), j.optBoolean("collapsed", false))) }
            val m = o.optJSONObject("cutFolder")
            folderOf.clear()
            if (m != null) for (k in m.keys()) folderOf[k.toLong()] = m.getLong(k)
        }.onFailure { CrashDiagnostics.log("WARN", "library.json could not be read: ${it.message}") }
    }

    fun save(dir: File) {
        runCatching {
            val o = JSONObject()
            o.put("grid", grid)
            o.put("folders", JSONArray().also { a -> folders.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("collapsed", it.collapsed)) } })
            o.put("cutFolder", JSONObject().also { m -> folderOf.forEach { (k, v) -> m.put(k.toString(), v) } })
            val tmp = File(dir, "$FILE.tmp"); tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, FILE))) File(dir, FILE).writeText(o.toString())
        }.onFailure { CrashDiagnostics.log("WARN", "library.json could not be saved: ${it.message}") }
    }

    companion object { const val FILE = "library.json"; const val GRID_COLUMNS = 5 }
}

private class CutDrag(val cut: Cut, val pos: Offset)

private class BrowserState(val ed: Editor, val dir: File) {
    val lib: CutLibrary get() = ed.library
    var drag by mutableStateOf<CutDrag?>(null)
    val sectionRects = HashMap<Long, Rect>() // folder id -> bounds (header + its Cuts) in root coordinates
    var listRect = Rect.Zero
    var boxOrigin = Offset.Zero
    /** Folder id under [pos] (0 = top level), or null outside the list. */
    fun dropTarget(pos: Offset): Long? = if (!listRect.contains(pos)) null else sectionRects.entries.firstOrNull { it.value.contains(pos) }?.key ?: 0L
    fun finishDrag() {
        val d = drag ?: return; drag = null
        val target = dropTarget(d.pos) ?: return
        if (target != lib.folderIdOf(d.cut)) lib.move(d.cut.id, target, dir)
    }
}

/** Long-press a Cut and drag it onto a folder (or out of one, onto the free area) to move it. */
private fun Modifier.dragToFolder(c: Cut, st: BrowserState, origin: FloatArray): Modifier = pointerInput(c.id) {
    detectDragGesturesAfterLongPress(
        onDragStart = { off -> st.drag = CutDrag(c, Offset(origin[0] + off.x, origin[1] + off.y)) },
        onDrag = { change, amount -> change.consume(); st.drag?.let { st.drag = CutDrag(it.cut, it.pos + amount) } },
        onDragEnd = { st.finishDrag() },
        onDragCancel = { st.drag = null }
    )
}

@Composable
fun CutBrowser(ed: Editor, onExport: (Cut) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember(ed) { BrowserState(ed, ctx.filesDir) }
    val lib = ed.library
    var renameCut by remember { mutableStateOf<Cut?>(null) }
    var renameFolder by remember { mutableStateOf<CutFolder?>(null) }
    val onRenameCut: (Cut) -> Unit = { renameCut = it }
    Box(modifier.onGloballyPositioned { st.boxOrigin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ renameFolder = lib.addFolder(st.dir) }) { Text("+ New folder") }
                Spacer(Modifier.weight(1f))
                TextButton({ lib.grid = !lib.grid; lib.save(st.dir) }) { Text(if (lib.grid) "Layout: grid" else "Layout: list") }
            }
            val root = lib.cutsIn(ed.cuts, 0L)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { st.listRect = it.boundsInRoot() }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(lib.folders.toList(), key = { "f${it.id}" }) { f -> FolderSection(f, st, onExport, onRenameCut) { renameFolder = f } }
                if (!lib.grid) items(root, key = { "c${it.id}" }) { c -> CutRow(c, st, onExport, onRenameCut) }
                else items(root.chunked(CutLibrary.GRID_COLUMNS), key = { "r${it.first().id}" }) { row -> GridRow(row, st, onExport, onRenameCut) }
            }
        }
        st.drag?.let { d ->
            Box(
                Modifier.offset { IntOffset((d.pos.x - st.boxOrigin.x).roundToInt() - 60, (d.pos.y - st.boxOrigin.y).roundToInt() - 70) }
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xE6454560)).padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Moving “${d.cut.name}”", fontSize = 13.sp) }
        }
    }
    renameCut?.let { c ->
        RenameDialog("Rename cut", c.name, { renameCut = null }) { n ->
            if (n.isNotBlank() && n != c.name) { c.name = n; scope.launch(Dispatchers.IO) { runCatching { Store.save(st.dir, c) } } } // the name is stored in the cut's own file
            renameCut = null
        }
    }
    renameFolder?.let { f ->
        RenameDialog("Rename folder", f.name, { renameFolder = null }) { n ->
            if (n.isNotBlank()) { f.name = n; lib.save(st.dir) }
            renameFolder = null
        }
    }
}

@Composable
private fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FolderSection(f: CutFolder, st: BrowserState, onExport: (Cut) -> Unit, onRename: (Cut) -> Unit, onRenameFolder: () -> Unit) {
    val lib = st.lib
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(f.id) { onDispose { st.sectionRects.remove(f.id) } }
    val cuts = lib.cutsIn(st.ed.cuts, f.id)
    val hover = st.drag?.let { st.dropTarget(it.pos) } == f.id
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { st.sectionRects[f.id] = it.boundsInRoot() }
            .clip(RoundedCornerShape(12.dp)).background(if (hover) Color(0xFF3F3F63) else Color(0xFF25252D)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable { f.collapsed = !f.collapsed; lib.save(st.dir) }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.collapsed) "▸" else "▾", Modifier.padding(horizontal = 8.dp), fontSize = 18.sp)
            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${cuts.size}", fontSize = 13.sp, color = Color.Gray)
            Box {
                Text("⋮", Modifier.clickable { menu = true }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 18.sp)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename folder") }, { menu = false; onRenameFolder() })
                    DropdownMenuItem({ Text("Delete folder (keeps the cuts)") }, { menu = false; lib.removeFolder(f, st.dir) })
                }
            }
        }
        if (!f.collapsed) {
            if (cuts.isEmpty()) Text("Empty. Long-press a cut and drag it here.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            else if (!lib.grid) cuts.forEach { CutRow(it, st, onExport, onRename) }
            else cuts.chunked(CutLibrary.GRID_COLUMNS).forEach { GridRow(it, st, onExport, onRename) }
        }
    }
}

@Composable
private fun CutRow(c: Cut, st: BrowserState, onExport: (Cut) -> Unit, onRename: (Cut) -> Unit) {
    val origin = remember { floatArrayOf(0f, 0f) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E36))
            .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
            .dragToFolder(c, st, origin)
            .clickable { st.ed.open = c }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(112.dp).aspectRatio(c.w.toFloat() / c.h).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
            c.thumb?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            Text("${c.w}×${c.h} · ${c.fps} fps · ${c.dpi} dpi · ${c.layers.count { !it.isGroup }} layers · ${c.total()} frames", fontSize = 13.sp, color = Color.Gray)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton({ onRename(c) }) { Text("Rename") }
            TextButton({ onExport(c) }) { Text("Export") }
            TextButton({ st.ed.remove(c, st.dir) }) { Text("Delete") }
        }
    }
}

@Composable
private fun GridRow(row: List<Cut>, st: BrowserState, onExport: (Cut) -> Unit, onRename: (Cut) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { CutTile(it, st, Modifier.weight(1f), onExport, onRename) }
        repeat(CutLibrary.GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun CutTile(c: Cut, st: BrowserState, modifier: Modifier, onExport: (Cut) -> Unit, onRename: (Cut) -> Unit) {
    val origin = remember { floatArrayOf(0f, 0f) }
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E36))
            .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
            .dragToFolder(c, st, origin)
            .clickable { st.ed.open = c }.padding(6.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(c.w.toFloat() / c.h).clip(RoundedCornerShape(5.dp)).background(Color.White)) {
            c.thumb?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, Modifier.weight(1f).padding(start = 2.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                Text("⋮", Modifier.clickable { menu = true }.padding(horizontal = 6.dp, vertical = 4.dp), fontSize = 16.sp)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename(c) })
                    DropdownMenuItem({ Text("Export") }, { menu = false; onExport(c) })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; st.ed.remove(c, st.dir) })
                }
            }
        }
    }
}
