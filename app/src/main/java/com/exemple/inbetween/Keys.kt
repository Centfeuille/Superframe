package com.example.inbetween

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** One key combination. [ctrl] also covers Cmd. Alt combinations are left to the system and cannot be bound. */
data class KeyBind(val code: Int, val ctrl: Boolean = false, val shift: Boolean = false)

private fun kb(code: Int, ctrl: Boolean = false, shift: Boolean = false) = KeyBind(code, ctrl, shift)

/**
 * Every shortcut action with its app-default keys. [id] is the stable name stored in keybinds.json; never rename one.
 * [repeats] = the action keeps firing while the key is held (stepping actions); the others fire once per press.
 * Add a new action here, handle it in [handleKey]; it appears in Settings > Keyboard shortcuts automatically.
 */
enum class KeyAction(val id: String, val group: String, val label: String, val defaults: List<KeyBind>, val repeats: Boolean = false) {
    PLAY("play", "Playback and frames", "Play / pause", listOf(kb(KeyEvent.KEYCODE_SPACE))),
    PREV_FRAME("prevFrame", "Playback and frames", "Previous frame", listOf(kb(KeyEvent.KEYCODE_DPAD_LEFT)), true),
    NEXT_FRAME("nextFrame", "Playback and frames", "Next frame", listOf(kb(KeyEvent.KEYCODE_DPAD_RIGHT)), true),
    BACK_SECOND("backSecond", "Playback and frames", "Back one second", listOf(kb(KeyEvent.KEYCODE_DPAD_LEFT, shift = true)), true),
    FWD_SECOND("fwdSecond", "Playback and frames", "Forward one second", listOf(kb(KeyEvent.KEYCODE_DPAD_RIGHT, shift = true)), true),
    PREV_KEYFRAME("prevKeyframe", "Playback and frames", "Previous keyframe", listOf(kb(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true)), true),
    NEXT_KEYFRAME("nextKeyframe", "Playback and frames", "Next keyframe", listOf(kb(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true)), true),
    FIRST_FRAME("firstFrame", "Playback and frames", "First frame of the playback window", listOf(kb(KeyEvent.KEYCODE_MOVE_HOME))),
    LAST_FRAME("lastFrame", "Playback and frames", "Last frame of the playback window", listOf(kb(KeyEvent.KEYCODE_MOVE_END))),
    NEW_FRAME("newFrame", "Playback and frames", "New frame", listOf(kb(KeyEvent.KEYCODE_N), kb(KeyEvent.KEYCODE_INSERT))),
    DUP_FRAME("dupFrame", "Playback and frames", "Duplicate frame", listOf(kb(KeyEvent.KEYCODE_D, ctrl = true))),
    DEL_FRAME("delFrame", "Playback and frames", "Delete frame", listOf(kb(KeyEvent.KEYCODE_FORWARD_DEL), kb(KeyEvent.KEYCODE_DEL))),
    SELECT_FRAMES("selectFrames", "Playback and frames", "Select every frame of the layer", listOf(kb(KeyEvent.KEYCODE_A, ctrl = true))),

    LAYER_UP("layerUp", "Layers and keys", "Select the layer above", listOf(kb(KeyEvent.KEYCODE_DPAD_UP)), true),
    LAYER_DOWN("layerDown", "Layers and keys", "Select the layer below", listOf(kb(KeyEvent.KEYCODE_DPAD_DOWN)), true),
    NEW_LAYER("newLayer", "Layers and keys", "New drawing layer", listOf(kb(KeyEvent.KEYCODE_N, ctrl = true, shift = true))),
    SET_ANCHOR("setAnchor", "Layers and keys", "Set anchor on selected vector points", listOf(kb(KeyEvent.KEYCODE_A))),
    CLEAR_ANCHOR("clearAnchor", "Layers and keys", "Clear anchor on selected vector points", listOf(kb(KeyEvent.KEYCODE_A, shift = true))),
    ADD_KEY("addKey", "Layers and keys", "Add key at the playhead (Camera / Transformation)", listOf(kb(KeyEvent.KEYCODE_K))),
    TIMELINE("timeline", "Layers and keys", "Show / hide the timeline", listOf(kb(KeyEvent.KEYCODE_TAB))),

    BRUSH("brush", "Tools", "Brush", listOf(kb(KeyEvent.KEYCODE_B))),
    ERASER("eraser", "Tools", "Eraser", listOf(kb(KeyEvent.KEYCODE_E))),
    SELECTION("selection", "Tools", "Selection", listOf(kb(KeyEvent.KEYCODE_S))),
    FILL("fill", "Tools", "Fill", listOf(kb(KeyEvent.KEYCODE_G))),
    LINE("line", "Tools", "Line / shapes", listOf(kb(KeyEvent.KEYCODE_L))),
    SIZE_DOWN("sizeDown", "Tools", "Smaller brush or eraser", listOf(kb(KeyEvent.KEYCODE_LEFT_BRACKET)), true),
    SIZE_UP("sizeUp", "Tools", "Bigger brush or eraser", listOf(kb(KeyEvent.KEYCODE_RIGHT_BRACKET)), true),
    TRANSFORM("transform", "Tools", "Transform the selection", listOf(kb(KeyEvent.KEYCODE_T))),
    INVERT_SEL("invertSel", "Tools", "Invert the selection", listOf(kb(KeyEvent.KEYCODE_I))),
    CONFIRM("confirm", "Tools", "Apply the transform / confirm the selection", listOf(kb(KeyEvent.KEYCODE_ENTER), kb(KeyEvent.KEYCODE_NUMPAD_ENTER))),
    CANCEL("cancel", "Tools", "Cancel the transform, drop the selection, leave fullscreen", listOf(kb(KeyEvent.KEYCODE_ESCAPE))),

    ZOOM_IN("zoomIn", "View", "Zoom in", listOf(kb(KeyEvent.KEYCODE_EQUALS, ctrl = true), kb(KeyEvent.KEYCODE_PLUS, ctrl = true), kb(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true)), true),
    ZOOM_OUT("zoomOut", "View", "Zoom out", listOf(kb(KeyEvent.KEYCODE_MINUS, ctrl = true), kb(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ctrl = true)), true),
    RESET_VIEW("resetView", "View", "Reset the view", listOf(kb(KeyEvent.KEYCODE_0, ctrl = true), kb(KeyEvent.KEYCODE_NUMPAD_0, ctrl = true))),
    FULLSCREEN("fullscreen", "View", "Fullscreen canvas", listOf(kb(KeyEvent.KEYCODE_F))),
    ONION("onion", "View", "Onion skin on / off", listOf(kb(KeyEvent.KEYCODE_O))),
    MIRROR("mirror", "View", "Mirror the canvas on / off", listOf(kb(KeyEvent.KEYCODE_M))),
    GRID_DEPTH("gridDepth", "View", "Grid in front / behind", listOf(kb(KeyEvent.KEYCODE_G, shift = true))),
    REFERENCE_VISIBILITY("referenceVisibility", "View", "Show / hide all reference-image layers", listOf(kb(KeyEvent.KEYCODE_F6))),

    UNDO("undo", "Cut", "Undo", listOf(kb(KeyEvent.KEYCODE_Z, ctrl = true)), true),
    REDO("redo", "Cut", "Redo", listOf(kb(KeyEvent.KEYCODE_Z, ctrl = true, shift = true), kb(KeyEvent.KEYCODE_Y, ctrl = true)), true),
    SAVE("save", "Cut", "Save", listOf(kb(KeyEvent.KEYCODE_S, ctrl = true))),
    EXPORT("export", "Cut", "Export\u2026", listOf(kb(KeyEvent.KEYCODE_E, ctrl = true))),
    SETTINGS("settings", "Cut", "Settings", listOf(kb(KeyEvent.KEYCODE_F1)))
}

/** The app defaults, complete for every action. */
val DEFAULT_BINDS: Map<KeyAction, List<KeyBind>> = KeyAction.values().associateWith { it.defaults }

/**
 * The active key map. Loaded once from keybinds.json in the app files directory (missing file = app defaults); [save] writes it,
 * [resetToDefaults] deletes the file. An action missing from the file (added by a later version) gets its defaults, unless a saved
 * key already uses that combination.
 */
object KeyBindings {
    private const val FILE = "keybinds.json"
    private var loaded = false
    private var lookup: Map<KeyBind, KeyAction> = emptyMap()

    @Volatile var active: Map<KeyAction, List<KeyBind>> = DEFAULT_BINDS
        private set

    private fun use(m: Map<KeyAction, List<KeyBind>>) {
        val l = HashMap<KeyBind, KeyAction>()
        for ((a, list) in m) for (b in list) l[b] = a
        lookup = l
        active = m
    }

    init { use(DEFAULT_BINDS) }

    fun ensureLoaded(dir: File) {
        if (loaded) return
        loaded = true
        val f = File(dir, FILE)
        if (!f.exists()) return
        try {
            val binds = JSONObject(f.readText()).getJSONObject("binds")
            val m = LinkedHashMap<KeyAction, List<KeyBind>>()
            val used = HashSet<KeyBind>()
            for (a in KeyAction.values()) {
                if (!binds.has(a.id)) continue
                val arr = binds.getJSONArray(a.id)
                val list = ArrayList<KeyBind>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val b = KeyBind(o.getInt("k"), o.optBoolean("c", false), o.optBoolean("s", false))
                    if (used.add(b)) list.add(b)
                }
                m[a] = list
            }
            for (a in KeyAction.values()) if (!m.containsKey(a)) m[a] = a.defaults.filter { used.add(it) }
            use(m)
        } catch (_: Exception) { use(DEFAULT_BINDS) }
    }

    fun find(code: Int, ctrl: Boolean, shift: Boolean): KeyAction? =
        lookup[KeyBind(code, ctrl, shift)] ?: if (shift) lookup[KeyBind(code, ctrl, false)] else null // Shift is ignored when the key has no Shift binding of its own

    fun save(dir: File, map: Map<KeyAction, List<KeyBind>>): Boolean {
        return try {
            val binds = JSONObject()
            for (a in KeyAction.values()) {
                val arr = JSONArray()
                for (b in map[a] ?: emptyList()) arr.put(JSONObject().put("k", b.code).put("c", b.ctrl).put("s", b.shift))
                binds.put(a.id, arr)
            }
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(JSONObject().put("version", 1).put("binds", binds).toString())
            val f = File(dir, FILE)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return false }
            use(LinkedHashMap(map))
            true
        } catch (_: Exception) { false }
    }

    fun resetToDefaults(dir: File) {
        try { File(dir, FILE).delete() } catch (_: Exception) {}
        use(DEFAULT_BINDS)
    }
}

/** Gives [bind] to [action] (replacing the key in [slot], or adding one when slot is null). A combination already used by another action is moved; that action is returned. */
fun rebind(map: Map<KeyAction, List<KeyBind>>, action: KeyAction, slot: Int?, bind: KeyBind): Pair<Map<KeyAction, List<KeyBind>>, KeyAction?> {
    var taken: KeyAction? = null
    val out = LinkedHashMap<KeyAction, List<KeyBind>>()
    for ((a, list) in map) {
        if (a != action && bind in list) { taken = a; out[a] = list.filter { it != bind } } else out[a] = list
    }
    val mine = (out[action] ?: emptyList()).toMutableList()
    if (slot != null && slot in mine.indices) mine[slot] = bind else mine.add(bind)
    out[action] = mine.distinct()
    return Pair(out, taken)
}

fun keyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_DPAD_LEFT -> "\u2190"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "\u2192"
    KeyEvent.KEYCODE_DPAD_UP -> "\u2191"
    KeyEvent.KEYCODE_DPAD_DOWN -> "\u2193"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
    KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
    KeyEvent.KEYCODE_INSERT -> "Insert"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_NUMPAD_ENTER -> "Num Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_PLUS -> "+"
    KeyEvent.KEYCODE_MINUS -> "\u2212"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    KeyEvent.KEYCODE_COMMA -> ","
    KeyEvent.KEYCODE_PERIOD -> "."
    KeyEvent.KEYCODE_SLASH -> "/"
    KeyEvent.KEYCODE_BACKSLASH -> "\\"
    KeyEvent.KEYCODE_SEMICOLON -> ";"
    KeyEvent.KEYCODE_APOSTROPHE -> "'"
    KeyEvent.KEYCODE_GRAVE -> "`"
    KeyEvent.KEYCODE_NUMPAD_ADD -> "Num +"
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "Num \u2212"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace("NUMPAD_", "Num ").replace('_', ' ')
}

fun bindLabel(b: KeyBind): String = buildList {
    if (b.ctrl) add("Ctrl")
    if (b.shift) add("Shift")
    add(keyName(b.code))
}.joinToString(" + ")

private val UNBINDABLE = setOf(
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION,
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_UNKNOWN
)

/** The combination a key-down event stands for, or null for modifier-only / system keys and Alt combinations. */
fun captureBind(e: KeyEvent): KeyBind? {
    if (e.action != KeyEvent.ACTION_DOWN || e.isAltPressed || e.keyCode in UNBINDABLE) return null
    return KeyBind(e.keyCode, e.isCtrlPressed || e.isMetaPressed, e.isShiftPressed)
}

/** What the key handler needs from the editor screen. */
class KeyActions(val save: () -> Unit, val export: () -> Unit, val settings: () -> Unit)

private fun stepLayer(cut: Cut, dir: Int) {
    val order = cut.layers.reversed().filter { l -> !l.isGroup && cut.layers.firstOrNull { it.id == l.group }?.collapsed != true }
    if (order.isEmpty()) return
    val cur = order.indexOfFirst { it.id == cut.layerId }
    val next = if (cur < 0) 0 else (cur + dir).coerceIn(0, order.size - 1)
    cut.layerId = order[next].id
}

private fun zoomView(ed: Editor, factor: Float) {
    val ns = (ed.vScale * factor).coerceIn(0.2f, 20f)
    val r = ns / ed.vScale
    ed.vOff = ed.vOff * r // zoom about the middle of the canvas
    ed.vScale = ns
}

private fun stepTime(cut: Cut, n: Int) {
    cut.playing = false
    cut.time = (cut.time + n).coerceIn(0, max(0, cut.total() - 1))
}

/** Keyframe times of the selected layer: diamond keys of a Camera layer / Transformation folder, or the first tick of each drawing of a drawing layer. */
private fun keyframeTimes(cut: Cut): List<Int> {
    val l = cut.layer ?: return emptyList()
    if (l.cam != null || (l.isGroup && l.transformFolder)) return keyTimes(l)
    if (l.kind == 0 && l.cels.isNotEmpty()) {
        var at = 0
        return l.cels.map { c -> val s = at; at += c.dur; s }
    }
    return emptyList()
}

/** Jumps to the previous (dir < 0) or next (dir > 0) keyframe of the selected layer; stays put at the ends. */
fun flipKeyframe(cut: Cut, dir: Int) {
    val times = keyframeTimes(cut)
    if (times.isEmpty()) return
    val t = cut.time
    val target = if (dir > 0) times.firstOrNull { it > t } else times.lastOrNull { it < t }
    if (target != null) { cut.playing = false; cut.time = target.coerceIn(0, max(0, cut.total() - 1)) }
}

/**
 * Hardware keyboard shortcuts for the editor. Returns true when the key was a shortcut (and was used).
 * The key -> action map comes from [KeyBindings] (rebindable in Settings). Only key-down events count; one-shot actions ignore
 * auto-repeat, stepping actions ([KeyAction.repeats]) repeat.
 */
fun handleKey(ed: Editor, cut: Cut, e: KeyEvent, act: KeyActions): Boolean {
    if (e.action != KeyEvent.ACTION_DOWN) return false
    if (e.isAltPressed) return false // leave Alt combinations to the system
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val action = KeyBindings.find(e.keyCode, ctrl, e.isShiftPressed) ?: return false
    val once = e.repeatCount == 0
    if (!once && !action.repeats) return action != KeyAction.CONFIRM

    when (action) {
        KeyAction.PLAY -> cut.playing = !cut.playing
        KeyAction.PREV_FRAME -> stepTime(cut, -1)
        KeyAction.NEXT_FRAME -> stepTime(cut, 1)
        KeyAction.BACK_SECOND -> stepTime(cut, -cut.fps)
        KeyAction.FWD_SECOND -> stepTime(cut, cut.fps)
        KeyAction.PREV_KEYFRAME -> flipKeyframe(cut, -1)
        KeyAction.NEXT_KEYFRAME -> flipKeyframe(cut, 1)
        KeyAction.FIRST_FRAME -> { cut.playing = false; cut.time = cut.firstTick(); ed.tlPanFrame = 0; ed.tlPanRequest++ }
        KeyAction.LAST_FRAME -> { cut.playing = false; cut.time = cut.lastTick() }
        KeyAction.NEW_FRAME -> { cut.playing = false; cut.addFrame() }
        KeyAction.DUP_FRAME -> { cut.playing = false; cut.duplicateFrame(ed.selCels.toList()) }
        KeyAction.DEL_FRAME -> { cut.playing = false; cut.deleteFrame() }
        KeyAction.SET_ANCHOR -> {
            if (cut.layer?.vector == true && ed.tool == 2) {
                val sc = vecScope(cut, ed.refAll)
                if (sc != null && sc.sameCels(ed.vecSelCels) && ed.vecSel.isNotEmpty()) autoAnchor(cut, ed, sc)
            }
        }
        KeyAction.CLEAR_ANCHOR -> { if (cut.layer?.vector == true && ed.tool == 2) clearAnchors(cut, ed) }
        KeyAction.SELECT_FRAMES -> cut.layer?.let { cut.selAll = it.id }
        KeyAction.LAYER_UP -> stepLayer(cut, -1)
        KeyAction.LAYER_DOWN -> stepLayer(cut, 1)
        KeyAction.NEW_LAYER -> cut.addLayer()
        KeyAction.ADD_KEY -> addKeyAtPlayhead(ed, cut)
        KeyAction.TIMELINE -> if (!ed.fullscreen) ed.timelineOpen = !ed.timelineOpen
        KeyAction.BRUSH -> ed.tool = 0
        KeyAction.ERASER -> ed.tool = 1
        KeyAction.SELECTION -> ed.tool = 2
        KeyAction.FILL -> ed.tool = 3
        KeyAction.LINE -> ed.tool = 4
        KeyAction.SIZE_DOWN, KeyAction.SIZE_UP -> {
            val b = ed.brush.size
            val ns = if (action == KeyAction.SIZE_DOWN) minOf(b * 0.85f, b - 1f) else max(b * 1.15f, b + 1f)
            val size = ns.coerceIn(1f, 300f).roundToInt().toFloat()
            ed.update(ed.erase) { it.copy(size = size) }
        }
        KeyAction.TRANSFORM -> if (ed.sel != null && ed.xform == null) beginTransform(ed, cut)
        KeyAction.INVERT_SEL -> if (ed.sel != null && ed.xform == null) invertSelection(ed, cut)
        KeyAction.CONFIRM -> {
            if (ed.xform != null) { applyTransform(ed, cut); return true }
            if (ed.sel != null) { ed.sel = null; return true }
            return false
        }
        KeyAction.CANCEL -> {
            if (ed.xform != null) { cancelTransform(ed); return true }
            if (ed.sel != null) { ed.sel = null; return true }
            if (ed.fullscreen) { ed.fullscreen = false; return true }
            return false
        }
        KeyAction.ZOOM_IN -> zoomView(ed, 1.25f)
        KeyAction.ZOOM_OUT -> zoomView(ed, 0.8f)
        KeyAction.RESET_VIEW -> ed.resetView()
        KeyAction.FULLSCREEN -> ed.fullscreen = !ed.fullscreen
        KeyAction.ONION -> ed.onion = !ed.onion
        KeyAction.MIRROR -> { if (ed.flipH || ed.flipV) { ed.flipH = false; ed.flipV = false } else ed.flipH = true }
        KeyAction.GRID_DEPTH -> ed.gridFront = !ed.gridFront
        KeyAction.REFERENCE_VISIBILITY -> {
            val refs = cut.layers.filter { it.kind == 5 }
            if (refs.isNotEmpty()) { val show = refs.any { !it.visible }; cut.record { refs.forEach { it.visible = show } } }
        }
        KeyAction.UNDO -> cut.undo()
        KeyAction.REDO -> cut.redo()
        KeyAction.SAVE -> act.save()
        KeyAction.EXPORT -> act.export()
        KeyAction.SETTINGS -> act.settings()
    }
    return true
}

/**
 * Settings > Keyboard shortcuts. Edits go to a draft; Save applies and stores them (keybinds.json), Reset restores the app defaults
 * immediately (and deletes the stored file). Leaving with unsaved edits asks first.
 */
@Composable
fun KeyboardShortcutsDialog(filesDir: File, onBack: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(KeyBindings.active) }
    var version by remember { mutableIntStateOf(0) } // bumped by Save / Reset so "unsaved" is re-evaluated
    var capture by remember { mutableStateOf<Pair<KeyAction, Int?>?>(null) } // action and key slot (null = add a key)
    var note by remember { mutableStateOf("") }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val unsaved = version >= 0 && draft != KeyBindings.active
    fun leave(go: () -> Unit) { if (unsaved) pendingLeave = go else go() }

    AlertDialog(
        onDismissRequest = { leave(onClose) },
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("For a USB or Bluetooth keyboard. Cmd works like Ctrl. Tap a key to change it, + adds another key, \u00d7 removes one. Save keeps your changes; Reset restores the app defaults.", fontSize = 12.sp, color = Color.Gray)
                if (unsaved) Text("Unsaved changes", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                KeyAction.values().groupBy { it.group }.forEach { (title, actions) ->
                    Text(title, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    actions.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(a.label, Modifier.weight(1f).padding(top = 5.dp), fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val binds = draft[a] ?: emptyList()
                                binds.forEachIndexed { i, b ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            bindLabel(b),
                                            Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF34343C)).clickable { note = ""; capture = Pair(a, i) }.padding(horizontal = 8.dp, vertical = 5.dp),
                                            fontSize = 12.sp
                                        )
                                        Icon(Icons.Filled.Close, null, Modifier.clickable {
                                            note = ""
                                            draft = LinkedHashMap(draft).also { it[a] = binds.filterIndexed { j, _ -> j != i } }
                                        }.padding(4.dp).size(22.dp))
                                    }
                                }
                                Text(
                                    if (binds.isEmpty()) "Unbound \u00b7 tap to add" else "+",
                                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { note = ""; capture = Pair(a, null) }.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({
                    KeyBindings.resetToDefaults(filesDir)
                    draft = KeyBindings.active; version++; note = ""
                    Toast.makeText(ctx, "Shortcuts reset to the app defaults", Toast.LENGTH_SHORT).show()
                }) { Text("Reset") }
                TextButton({
                    val ok = KeyBindings.save(filesDir, draft)
                    version++; note = ""
                    Toast.makeText(ctx, if (ok) "Shortcuts saved" else "Could not save the shortcuts", Toast.LENGTH_SHORT).show()
                }, enabled = unsaved) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                TextButton({ leave(onBack) }) { Text("Back") }
                TextButton({ leave(onClose) }) { Text("Close") }
            }
        }
    )

    capture?.let { (action, slot) ->
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { capture = null },
            title = { Text(action.label) },
            text = {
                Box(
                    Modifier.fillMaxWidth().height(90.dp).focusRequester(focus).focusable().onKeyEvent { ev ->
                        val ne = ev.nativeKeyEvent
                        if (ne.keyCode == KeyEvent.KEYCODE_BACK) return@onKeyEvent false
                        if (ne.action != KeyEvent.ACTION_DOWN) return@onKeyEvent true
                        if (ne.isAltPressed) { note = "Alt combinations are reserved by the system"; return@onKeyEvent true }
                        val b = captureBind(ne) ?: return@onKeyEvent true // modifier alone: keep waiting
                        val (map, taken) = rebind(draft, action, slot, b)
                        draft = map
                        note = if (taken != null) "${bindLabel(b)} moved from \u201c${taken.label}\u201d to \u201c${action.label}\u201d" else ""
                        capture = null
                        true
                    },
                    contentAlignment = Alignment.Center
                ) { Text("Press the new key or combination") }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            },
            confirmButton = { TextButton({ capture = null }) { Text("Cancel") } }
        )
    }

    pendingLeave?.let { go ->
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text("Discard changes?") },
            text = { Text("Your shortcut changes have not been saved.") },
            confirmButton = { TextButton({ pendingLeave = null; go() }) { Text("Discard") } },
            dismissButton = { TextButton({ pendingLeave = null }) { Text("Keep editing") } }
        )
    }
}
