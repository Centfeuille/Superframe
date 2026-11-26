package com.example.inbetween

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush as BrushIc
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image as ImageIc
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.foundation.gestures.drag as dragPointer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import kotlin.math.*

val pad = PaddingValues(horizontal = 8.dp)
val LightGrey = Color(0xFFD6D6DC)
val TOOL_W = 92.dp

fun displayName(ctx: Context, uri: Uri): String =
    runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        ?: (uri.lastPathSegment ?: "file")

// ---------- hover tips (pen / mouse hover over a button shows what it does) ----------
private val TIPS = mapOf(
    "Share" to "Export", "Add" to "New frame", "ContentCopy" to "Copy frame", "Delete" to "Delete", "PlaylistAdd" to "Add layer…",
    "LayersClear" to "Delete layer", "Layers" to "Onion skin", "ArrowBack" to "Back to cuts", "Undo" to "Undo", "Redo" to "Redo",
    "Grid4x4" to "Grid (hold for options)", "Flip" to "Invert canvas (hold for options)", "PlayArrow" to "Play", "Pause" to "Pause",
    "Check" to "Apply", "Close" to "Cancel", "SwapVert" to "Invert selection", "OpenWith" to "Transform", "Fullscreen" to "Fullscreen canvas",
    "FullscreenExit" to "Exit fullscreen", "AddPhotoAlternate" to "Import image sequence / zip", "Refresh" to "Reset camera position",
    "Vibration" to "Shake between selected keys (options)", "SkipPrevious" to "Wind to first frame", "SkipNext" to "Wind to last frame",
    "Timer" to "Retime frame", "Settings" to "Settings"
)

private object TipPos : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val rightSide = anchorBounds.center.x > windowSize.width * 0.8f // right toolbar: show the tip to its left
        val x = if (rightSide) anchorBounds.left - popupContentSize.width - 8
        else (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val topBar = anchorBounds.top < windowSize.height * 0.16f
        val bottomArea = anchorBounds.bottom > windowSize.height * 0.78f
        val y = when {
            topBar -> anchorBounds.bottom + 6 // top-bar descriptions always unfold below their buttons
            rightSide || bottomArea -> anchorBounds.top - popupContentSize.height - 6 // timeline / right-toolbar descriptions sit above
            else -> anchorBounds.bottom + 6
        }.coerceIn(0, max(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(Modifier.hoverable(src)) {
        content()
        if (hovered && text.isNotEmpty()) Popup(popupPositionProvider = TipPos) {
            Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xEE111114)).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = Color.White)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconBtn(icon: ImageVector, on: Boolean = false, size: Dp = 40.dp, tip: String = "", onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val key = icon.name.substringAfterLast('.')
    Tip(tip.ifEmpty { TIPS[key] ?: key }) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(size * 0.58f), tint = if (on) MaterialTheme.colorScheme.onPrimary else LightGrey) }
    }
}

/** Minimal onion logo. Tap: on / off. Hold: detailed options (Fade out, Turn off when scrubbing). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnionBtn(on: Boolean, box: Dp = 36.dp, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else LightGrey
    Tip("Onion skin") {
        Box(
            Modifier.size(box).clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(box * 0.6f)) {
                val w = this.size.width; val h = this.size.height; val sw = 1.8.dp.toPx()
                val p = Path()
                p.moveTo(w * 0.5f, h * 0.12f)
                p.cubicTo(w * 0.60f, h * 0.30f, w * 0.96f, h * 0.38f, w * 0.90f, h * 0.65f)
                p.cubicTo(w * 0.84f, h * 0.92f, w * 0.16f, h * 0.92f, w * 0.10f, h * 0.65f)
                p.cubicTo(w * 0.04f, h * 0.38f, w * 0.40f, h * 0.30f, w * 0.5f, h * 0.12f)
                p.close()
                drawPath(p, fg, style = Stroke(sw))
                val q = Path(); q.moveTo(w * 0.5f, h * 0.24f); q.cubicTo(w * 0.26f, h * 0.46f, w * 0.30f, h * 0.80f, w * 0.5f, h * 0.88f)
                drawPath(q, fg, style = Stroke(sw * 0.8f))
                val q2 = Path(); q2.moveTo(w * 0.5f, h * 0.24f); q2.cubicTo(w * 0.74f, h * 0.46f, w * 0.70f, h * 0.80f, w * 0.5f, h * 0.88f)
                drawPath(q2, fg, style = Stroke(sw * 0.8f))
                drawLine(fg, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.58f, h * 0.0f), sw) // sprout
                drawLine(fg, Offset(w * 0.5f, h * 0.9f), Offset(w * 0.5f, h * 1.0f), sw)  // root
            }
        }
    }
}

/** Bleed logo: the Cut frame with a stroke running out past its edge. Tap: draw outside the Cut on / off. Hold: Bleed options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BleedBtn(on: Boolean, size: Dp = 40.dp, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else LightGrey
    Tip(if (on) "Bleed on · hold for options" else "Bleed off · hold for options") {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(size * 0.58f)) {
                val w = this.size.width; val h = this.size.height; val sw = 1.8.dp.toPx()
                drawRoundRect(fg, topLeft = Offset(w * 0.16f, h * 0.2f), size = Size(w * 0.56f, h * 0.6f), cornerRadius = CornerRadius(sw), style = Stroke(sw))
                val s = Path()
                s.moveTo(w * 0.34f, h * 0.7f)
                s.cubicTo(w * 0.44f, h * 0.3f, w * 0.7f, h * 0.7f, w * 0.94f, h * 0.22f)
                drawPath(s, fg, style = Stroke(sw * 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    }
}

/** Transform magnet (30-degree rotation snap). Same box, corner radius and colours as [IconBtn]. */
@Composable
fun MagnetBtn(ed: Editor, size: Dp = 40.dp) {
    val on = ed.rotationSnap
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else LightGrey
    Tip("Rotation magnet: snap to 30° steps") {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { ed.rotationSnap = !ed.rotationSnap },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(size * 0.58f)) {
                val w = this.size.width; val sw = w * 0.2f
                val u = Path()
                u.moveTo(w * 0.2f, w * 0.86f)
                u.lineTo(w * 0.2f, w * 0.4f)
                u.arcTo(Rect(w * 0.2f, w * 0.1f, w * 0.8f, w * 0.7f), 180f, 180f, false)
                u.lineTo(w * 0.8f, w * 0.86f)
                drawPath(u, fg, style = Stroke(sw))
                drawLine(fg, Offset(w * 0.06f, w * 0.94f), Offset(w * 0.34f, w * 0.94f), strokeWidth = sw * 0.7f) // pole tips
                drawLine(fg, Offset(w * 0.66f, w * 0.94f), Offset(w * 0.94f, w * 0.94f), strokeWidth = sw * 0.7f)
            }
        }
    }
}

@Composable
fun NumSlider(label: String, v: Int, min: Int, max: Int, onV: (Int) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(v.toFloat(), { onV(it.roundToInt()) }, Modifier.weight(1f), valueRange = min.toFloat()..max.toFloat())
            NumberField(v.toString(), { s -> s.toIntOrNull()?.let { onV(it.coerceIn(min, max)) } }, Modifier.width(84.dp), decimals = false, min = min.toDouble(), max = max.toDouble())
        }
    }
}

@Composable
private fun ToolBtn(selected: Boolean, tip: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Tip(tip) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF34343C)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

@Composable
private fun EraserIcon(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        rotate(-35f) {
            drawRoundRect(tint, Offset(size.width * 0.12f, size.height * 0.3f), Size(size.width * 0.76f, size.height * 0.4f), CornerRadius(4.dp.toPx()))
            drawLine(Color(0x66000000), Offset(size.width * 0.42f, size.height * 0.3f), Offset(size.width * 0.42f, size.height * 0.7f), 3.dp.toPx())
        }
    }
}

/** Right tool column. Tap a tool to select it; tap it again to open its options. Playback buttons sit at the bottom. */
@Composable
fun ToolPanel(ed: Editor, cut: Cut, panel: Int, setPanel: (Int) -> Unit) {
    var colorOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    fun tap(t: Int) { if (ed.tool == t) setPanel(if (panel == 1) 0 else 1) else { ed.tool = t; setPanel(0) } }
    @Composable fun fg(t: Int) = if (ed.tool == t) MaterialTheme.colorScheme.onPrimary else LightGrey
    Column(Modifier.width(TOOL_W).fillMaxHeight().background(Color(0xFF26262C)).padding(6.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ToolBtn(ed.tool == 0, "Brush (tap again for presets)", { tap(0) }) { Icon(Icons.Filled.BrushIc, null, tint = fg(0)) }
            ToolBtn(ed.tool == 1, "Eraser (tap again for presets)", { tap(1) }) { EraserIcon(fg(1)) }
            ToolBtn(ed.tool == 2, "Selection (tap again for modes)", { tap(2) }) { Icon(Icons.Filled.SelectAll, null, tint = fg(2)) }
            ToolBtn(ed.tool == 3, "Fill (tap again for options)", { tap(3) }) { Icon(Icons.Filled.FormatColorFill, null, tint = fg(3)) }
            ToolBtn(ed.tool == 4, "Line / shapes (tap again for options)", { tap(4) }) { Icon(Icons.Filled.HorizontalRule, null, tint = fg(4)) }
            ToolBtn(ed.tool == 5, "Eyedropper / color picker", { tap(5) }) { Text("◉", color = fg(5), fontSize = 22.sp) }
            Tip("Brush size and stabilization") {
                Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { setPanel(if (panel == 2) 0 else 2) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size((ed.brush.size.coerceIn(4f, 30f)).dp).clip(CircleShape).background(LightGrey))
                    Text("${ed.brush.size.roundToInt()}", fontSize = 12.sp)
                }
            }
            Tip("Color") { Box(Modifier.size(44.dp).clip(CircleShape).background(ed.color).border(2.dp, LightGrey, CircleShape).clickable { colorOpen = true }) }
            ToolBtn(paletteOpen, "Color palettes", { paletteOpen = true }) { Icon(Icons.Filled.Palette, null, tint = if (paletteOpen) MaterialTheme.colorScheme.onPrimary else LightGrey) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.SkipPrevious, size = 26.dp) { cut.playing = false; cut.time = cut.firstTick(); ed.tlPanFrame = 0; ed.tlPanRequest++ }
            IconBtn(if (cut.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, on = cut.playing, size = 30.dp) { cut.playing = !cut.playing }
            IconBtn(Icons.Filled.SkipNext, size = 26.dp) { cut.playing = false; cut.time = cut.lastTick() }
        }
    }
    if (colorOpen) ColorDialog(ed) { colorOpen = false }
    if (paletteOpen) PaletteDialog(ed) { paletteOpen = false }
}

@OptIn(ExperimentalFoundationApi::class)
/** Create, fill and save color palettes. Tap a swatch to use it, hold it to remove it. The active palette also appears in the fullscreen minimal timeline. */
@Composable
fun PaletteDialog(ed: Editor, onDone: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    val pal = ed.palettes.getOrNull(ed.paletteIdx)
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onDone) { Text("Done") } },
        title = { Text("Palettes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ed.palettes.forEachIndexed { i, p -> FilterChip(i == ed.paletteIdx, { ed.paletteIdx = i; ed.persistPalettes() }, { Text(p.name, maxLines = 1) }) }
                    IconBtn(Icons.Filled.Add, size = 36.dp, tip = "New palette") { ed.newPalette(); nameDraft = ed.palettes.last().name; renaming = true }
                }
                if (pal == null) Text("No palette yet. Tap + to create one.", fontSize = 13.sp, color = Color.Gray)
                else {
                    if (renaming) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(nameDraft, { nameDraft = it }, Modifier.weight(1f), singleLine = true, label = { Text("Name") })
                        IconBtn(Icons.Filled.Check, size = 40.dp) { ed.renamePalette(pal, nameDraft); renaming = false }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pal.name, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1)
                        IconBtn(Icons.Filled.Edit, size = 36.dp, tip = "Rename palette") { nameDraft = pal.name; renaming = true }
                        IconBtn(Icons.Filled.Delete, size = 36.dp, tip = "Delete palette") { ed.deletePalette(pal) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        pal.colors.forEach { c ->
                            Box(Modifier.size(36.dp).clip(CircleShape).background(c).border(if (c == ed.color) 3.dp else 1.dp, if (c == ed.color) Color.White else Color.Gray, CircleShape)
                                .combinedClickable(onClick = { ed.color = c }, onLongClick = { ed.removePaletteColor(pal, c) }))
                        }
                        Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape).clickable { ed.addPaletteColor(pal, ed.color) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = ed.color)
                        }
                    }
                    Text("Tap + to add the current color. Hold a swatch to remove it.", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    )
}

@Composable
private fun Pct(label: String, v: Float, min: Int = 0, onV: (Float) -> Unit) =
    NumSlider(label, (v * 100).roundToInt(), min, 100) { onV(it / 100f) }

@Composable
fun QuickPanel(ed: Editor) {
    val b = ed.brush; val e = ed.erase
    Column(
        Modifier.width(240.dp).clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)).background(Color(0xFF2E2E36)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NumSlider("Size (px)", b.size.roundToInt(), 1, 300) { v -> ed.update(e) { it.copy(size = v.toFloat()) } }
        NumSlider("Stabilization", b.stab, 0, 100) { v -> ed.update(e) { it.copy(stab = v) } }
    }
}

// ---------- brush list / detail ----------

/** A horizontal curved stroke drawn with the brush itself (with its pressure curves), like Procreate's list. */
private fun brushPreview(b: Brush): ImageBitmap {
    val w = 260; val h = 60
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val capped = b.capped && !b.erase
    val layer = if (capped) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else bmp // a capped stroke is drawn at full strength, then laid on at its opacity
    val cv = android.graphics.Canvas(layer)
    val pb = b.copy(erase = false, size = b.size * min(1f, 30f / b.size))
    val st = PathStamper(cv, pb, 0xFFD6D6DC.toInt(), 1)
    val n = 56
    for (i in 0 until n) {
        val u = i / (n - 1f)
        val p = sin(PI.toFloat() * u).pow(0.7f)
        st.add(14f + (w - 28f) * u, h / 2f + sin(u * 2f * PI.toFloat()) * h * 0.2f, curveEval(pb.sizeCurve, p).coerceAtLeast(0.05f), curveEval(pb.opacityCurve, p).coerceIn(0f, 1f))
    }
    st.finish()
    if (capped) android.graphics.Canvas(bmp).drawBitmap(layer, 0f, 0f, android.graphics.Paint().apply { alpha = (b.opacity.coerceIn(0f, 1f) * 255).toInt() })
    return bmp.asImageBitmap()
}

/** One dab of [b] on a small square, for the tip and texture pickers. */
private fun stampPreview(b: Brush, px: Int = 56): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val st = PathStamper(android.graphics.Canvas(bmp), b, 0xFFE6E6EC.toInt(), 1)
    st.add(px / 2f, px / 2f, 1f, 1f); st.finish()
    return bmp.asImageBitmap()
}

/** A preview brush: only the tip / texture fields matter, everything else is neutral so the picture shows that one thing. */
private fun previewBrush(tip: Int = 0, tipImg: Int = 0, textureId: Int = 0, texImg: Int = 0, px: Int = 56) =
    Brush("", false, px * 0.86f, 1f, 1f, 1f, tip = tip, tipImg = tipImg, textureId = textureId, texImg = texImg, textureAmt = 1f)

@Composable
private fun PicButton(img: ImageBitmap?, selected: Boolean, label: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick); val long by rememberUpdatedState(onLong)
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(56.dp).clip(shape).background(Color(0xFF1E1E24))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFFB39DFF) else Color(0xFF55555F), shape)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { long?.invoke() }, onTap = { click() }) },
        contentAlignment = Alignment.Center
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize())
        if (label != null) Text(label, fontSize = 22.sp, color = Color(0xFFB0B0BA))
    }
}

/** A collapsible group of brush settings; the open / closed state is kept in the editor for the session. */
@Composable
private fun Section(ed: Editor, key: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    val open = ed.brushSections[key] ?: (key == "shape")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF34343C))) {
        Row(Modifier.fillMaxWidth().clickable { ed.brushSections[key] = !open }.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 13.sp)
            Text(if (open) "▾" else "▸", fontSize = 15.sp, color = Color(0xFFB0B0BA))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun BrushList(ed: Editor, forE: Boolean, onDetail: () -> Unit) {
    val ctx = LocalContext.current
    val list = if (forE) ed.erasers else ed.brushes
    val idx = if (forE) ed.eraserIdx else ed.brushIdx
    var revealed by remember { mutableStateOf<Long?>(null) }
    var exporting by remember { mutableStateOf<Brush?>(null) }
    var renaming by remember { mutableStateOf<Brush?>(null) }
    val importPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { ed.importBrush(forE, it) }
    }
    val exportPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val b = exporting
        if (uri != null && b != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(brushToFile(ed.savedMap[b.id] ?: b).toByteArray()) } }
    }
    renaming?.let { rb ->
        var t by remember(rb) { mutableStateOf(rb.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("Rename brush") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ ed.renameBrush(forE, rb, t); renaming = null }) { Text("OK") } }
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (forE) "Eraser presets" else "Brush presets", Modifier.weight(1f), fontSize = 14.sp)
        IconBtn(Icons.Filled.Add, size = 34.dp, tip = "Import brush (.inbrush)") { importPick.launch(arrayOf("*/*")) }
    }
    Text("Tap to choose, tap the chosen one again for details. Swipe left for delete / copy / export.", fontSize = 11.sp, color = Color.Gray)
    list.forEachIndexed { i, b ->
        val off by animateDpAsState(if (revealed == b.id) (-136).dp else 0.dp, label = "swipe")
        Box(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF34343C))) {
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                IconBtn(Icons.Filled.Delete, size = 42.dp, tip = "Delete brush") { ed.deleteBrush(forE, b); revealed = null }
                IconBtn(Icons.Filled.ContentCopy, size = 42.dp, tip = "Copy brush") { ed.copyBrush(forE, b); revealed = null }
                IconBtn(Icons.Filled.Share, size = 42.dp, tip = "Export brush") { exporting = b; exportPick.launch(b.name + ".inbrush") }
            }
            Row(
                Modifier.fillMaxSize().offset(x = off).background(if (i == idx) Color(0xFF4A4A78) else Color(0xFF3A3A44))
                    .pointerInput(b.id) {
                        var acc = 0f
                        detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = { if (acc < -30f) revealed = b.id else if (acc > 30f) revealed = null }) { c, d -> c.consume(); acc += d }
                    }
                    .clickable { if (revealed != null) revealed = null else if (idx == i) onDetail() else ed.selectBrush(forE, i) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(b.name + if (ed.isModified(b)) "  •" else "", Modifier.clickable { renaming = b }, fontSize = 12.sp, maxLines = 1) // tap the name to rename
                    Image(remember(b) { brushPreview(b) }, null, Modifier.fillMaxWidth().height(36.dp))
                }
            }
        }
    }
}

/** Pressure curve: press on the curve to add a point and drag it; hold a point to delete it. */
@Composable
private fun CurveEditor(pts: List<Float>, onChange: (List<Float>) -> Unit) {
    val cur by rememberUpdatedState(pts); val cb by rememberUpdatedState(onChange)
    var sel by remember { mutableIntStateOf(-1) }
    var menu by remember { mutableStateOf(false) }
    var dragKind by remember { mutableIntStateOf(0) } // 0 none, 1 point, 2 right handle dot, 3 left handle dot
    val dens = LocalDensity.current
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262C))
                .pointerInput(Unit) {
                    // tap a point: select it (handle + menu); tap empty space: create a point right there; hold a point: delete it
                    fun nearest(o: Offset): Int {
                        val p = cur; val w = size.width.toFloat(); val h = size.height.toFloat()
                        var best = -1; var bd = 40f
                        for (i in 0 until curveSize(p)) { val d = hypot(curveX(p, i) * w - o.x, (1f - curveY(p, i)) * h - o.y); if (d < bd) { bd = d; best = i } }
                        return best
                    }
                    detectTapGestures(
                        onTap = { o ->
                            val i = nearest(o)
                            if (i >= 0) { sel = i; menu = true }
                            else {
                                val p = cur.toMutableList(); val n = curveSize(p)
                                val x = (o.x / size.width).coerceIn(0f, 1f); val y = (1f - o.y / size.height).coerceIn(0f, 1f)
                                var at = 0; while (at < n && curveX(p, at) < x) at++
                                if (at in 1 until n && x - curveX(p, at - 1) > 0.02f && curveX(p, at) - x > 0.02f) { p.addAll(at * 3, curvePoint(x, y)); cb(p); sel = at; menu = false }
                            }
                        },
                        onLongPress = { o ->
                            val i = nearest(o)
                            if (i > 0 && i < curveSize(cur) - 1) { val p = cur.toMutableList(); repeat(3) { p.removeAt(i * 3) }; cb(p); sel = -1; menu = false }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            menu = false
                            val p = cur; val w = size.width.toFloat(); val h = size.height.toFloat(); val n = curveSize(p)
                            dragKind = 0
                            // the selected point's tangent handle first
                            if (sel in 0 until n) {
                                val m = curveTangentAt(p, sel); val px = curveX(p, sel) * w; val py = (1f - curveY(p, sel)) * h
                                val len = hypot(w, -m * h); val dx = w / len * 44f; val dy = -m * h / len * 44f
                                if (hypot(px + dx - o.x, py + dy - o.y) < 36f) dragKind = 2
                                else if (hypot(px - dx - o.x, py - dy - o.y) < 36f) dragKind = 3
                            }
                            if (dragKind == 0) {
                                var best = -1; var bd = 40f
                                for (i in 0 until n) { val d = hypot(curveX(p, i) * w - o.x, (1f - curveY(p, i)) * h - o.y); if (d < bd) { bd = d; best = i } }
                                if (best < 0) { // empty space: a new point is created where the finger landed and follows it
                                    val x = (o.x / w).coerceIn(0f, 1f); val y = (1f - o.y / h).coerceIn(0f, 1f)
                                    var at = 0; while (at < n && curveX(p, at) < x) at++
                                    if (at in 1 until n && x - curveX(p, at - 1) > 0.02f && curveX(p, at) - x > 0.02f) { val np = p.toMutableList(); np.addAll(at * 3, curvePoint(x, y)); cb(np); best = at }
                                }
                                if (best >= 0) { sel = best; dragKind = 1 }
                            }
                        },
                        onDragEnd = { dragKind = 0 }, onDragCancel = { dragKind = 0 },
                        onDrag = { c, _ ->
                            c.consume()
                            val i = sel
                            if (i >= 0 && dragKind != 0) {
                                val p = cur.toMutableList(); val n = curveSize(p); val w = size.width.toFloat(); val h = size.height.toFloat()
                                if (i < n) when (dragKind) {
                                    1 -> {
                                        val x = (c.position.x / w).coerceIn(0f, 1f); val y = (1f - c.position.y / h).coerceIn(0f, 1f)
                                        val nx = if (i == 0) 0f else if (i == n - 1) 1f else x.coerceIn(curveX(p, i - 1) + 0.01f, curveX(p, i + 1) - 0.01f)
                                        p[i * 3] = nx; p[i * 3 + 1] = y; cb(p)
                                    }
                                    else -> {
                                        var vx = c.position.x - curveX(p, i) * w; var vy = c.position.y - (1f - curveY(p, i)) * h
                                        if (dragKind == 3) { vx = -vx; vy = -vy }
                                        if (vx < 6f) vx = 6f
                                        p[i * 3 + 2] = (-(vy / vx) * (w / h)).coerceIn(-40f, 40f); cb(p)
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            for (k in 1..3) {
                drawLine(Color(0x22FFFFFF), Offset(size.width * k / 4f, 0f), Offset(size.width * k / 4f, size.height))
                drawLine(Color(0x22FFFFFF), Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f))
            }
            val path = Path()
            for (i in 0..96) { val x = i / 96f; val px = x * size.width; val py = (1f - curveEval(pts, x)) * size.height; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            drawPath(path, Color(0xFF8E8EE8), style = Stroke(3f))
            val n = curveSize(pts)
            if (sel in 0 until n) { // tangent handle of the selected point
                val m = curveTangentAt(pts, sel); val px = curveX(pts, sel) * size.width; val py = (1f - curveY(pts, sel)) * size.height
                val len = hypot(size.width, -m * size.height); val dx = size.width / len * 44f; val dy = -m * size.height / len * 44f
                val orange = Color(0xFFE0A04A)
                drawLine(orange, Offset(px - dx, py - dy), Offset(px + dx, py + dy), strokeWidth = 4f)
                drawCircle(orange, 7f, Offset(px - dx, py - dy)); drawCircle(orange, 7f, Offset(px + dx, py + dy))
            }
            for (i in 0 until n) {
                val c = if (i == sel) Color(0xFFE0A04A) else if (i == 0 || i == n - 1) Color.White else Color(0xFF8E8EE8)
                drawCircle(c, if (i == sel) 13f else 11f, Offset(curveX(pts, i) * size.width, (1f - curveY(pts, i)) * size.height))
            }
        }
        if (sel in 0 until curveSize(pts)) {
            val at = with(dens) { DpOffset((curveX(pts, sel) * 300f).toInt().dp * 0f, 0.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = at) {
                val endPoint = sel == 0 || sel == curveSize(pts) - 1
                DropdownMenuItem({ Text("Delete") }, { val p = pts.toMutableList(); repeat(3) { p.removeAt(sel * 3) }; onChange(p); sel = -1; menu = false }, enabled = !endPoint)
                DropdownMenuItem({ Text("Tangents set to medium") }, { val p = pts.toMutableList(); p[sel * 3 + 2] = TAN_AUTO; onChange(p); menu = false })
                DropdownMenuItem({ Text("Tangents set to linear") }, { val p = pts.toMutableList(); p[sel * 3 + 2] = TAN_LINEAR; onChange(p); menu = false })
            }
        }
    }
}

@Composable
private fun CurveSection(title: String, pts: List<Float>, saved: List<Float>?, onChange: (List<Float>) -> Unit) {
    var open by remember { mutableStateOf(false) } // collapsed by default
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, Modifier.size(24.dp))
        Text(title, fontSize = 13.sp)
    }
    if (open) {
        CurveEditor(pts, onChange)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({ onChange(linearCurve(0f)) }, contentPadding = pad) { Text("Linear", fontSize = 12.sp) }
            TextButton({ onChange(easeInCurve()) }, contentPadding = pad) { Text("Ease in", fontSize = 12.sp) }
            TextButton({ onChange(easeOutCurve()) }, contentPadding = pad) { Text("Ease out", fontSize = 12.sp) }
            TextButton({ onChange(sCurve()) }, contentPadding = pad) { Text("S curve", fontSize = 12.sp) }
            TextButton({ onChange(saved ?: linearCurve(0f)) }, contentPadding = pad) { Text("Reset", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun BrushDetail(ed: Editor, forE: Boolean, back: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val list = if (forE) ed.erasers else ed.brushes
    val b = list[if (forE) ed.eraserIdx else ed.brushIdx]
    val saved = ed.savedMap[b.id]
    fun up(f: (Brush) -> Brush) = ed.update(forE, f)
    fun pick(kind: Int, uri: android.net.Uri?) {
        if (uri == null) return
        val id = BrushAssets.importImage(ctx, uri, kind)
        if (id == null) android.widget.Toast.makeText(ctx, "Could not read that image", android.widget.Toast.LENGTH_SHORT).show()
        else if (kind == BrushAssets.TIP) up { it.copy(tipImg = id) } else up { it.copy(texImg = id) }
    }
    val pickTip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pick(BrushAssets.TIP, uri) }
    val pickTex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pick(BrushAssets.TEXTURE, uri) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(back, contentPadding = pad) { Text("‹ Presets") }
            Text(b.name + if (ed.isModified(b)) "  • modified" else "", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
        }
        // the preview stays put while the settings below scroll, so every change can be seen as it is made
        Image(remember(b) { brushPreview(b) }, null, Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF24242A)))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section(ed, "shape", "Shape and size") {
                NumSlider("Size (px)", b.size.roundToInt(), 1, 300) { v -> up { it.copy(size = v.toFloat()) } }
                Pct(if (b.capped && !forE) "Opacity (limit for the whole stroke)" else "Opacity", b.opacity, 1) { v -> up { it.copy(opacity = v) } }
                Pct("Flow (paint per dab)", b.flow, 1) { v -> up { it.copy(flow = v) } }
                if (!forE) FilterChip(b.capped, { up { it.copy(capped = !b.capped) } }, { Text("Opacity limits the whole stroke (no build-up)") }, Modifier.fillMaxWidth())
                Pct("Hardness", b.hardness) { v -> up { it.copy(hardness = v) } }
                Pct("Spacing (dab distance)", b.spacing, 2) { v -> up { it.copy(spacing = v) } }
                Pct("Anti-aliasing", b.aa) { v -> up { it.copy(aa = v) } }
            }
            Section(ed, "tip", "Tip") {
                PicRow {
                    TIP_NAMES.forEachIndexed { t, _ ->
                        PicButton(remember(t) { stampPreview(previewBrush(tip = t)) }, b.tipImg == 0 && b.tip == t) { up { it.copy(tip = t, tipImg = 0) } }
                    }
                    for (id in BrushAssets.tipIds.toList()) {
                        PicButton(remember(id) { BrushAssets.mask(id)?.let { stampPreview(previewBrush(tipImg = id)) } }, b.tipImg == id, onLong = { BrushAssets.forget(id) }) { up { it.copy(tipImg = id) } }
                    }
                    PicButton(null, false, label = "+") { pickTip.launch(arrayOf("image/*")) }
                }
                Text("+ imports an image as a stamp (transparent shape, or dark on light). Hold a stamp to remove it from the library.", fontSize = 11.sp, color = Color.Gray)
                NumSlider("Rotation (°)", b.tipAngle.roundToInt(), -180, 180) { v -> up { it.copy(tipAngle = v.toFloat()) } }
                FilterChip(b.tipFollow, { up { it.copy(tipFollow = !b.tipFollow) } }, { Text("Tip follows the stroke direction") }, Modifier.fillMaxWidth())
                NumSlider("Roundness (%)", (b.roundness * 100).roundToInt(), 10, 100) { v -> up { it.copy(roundness = v / 100f) } }
            }
            Section(ed, "dynamics", "Pressure, tilt and speed") {
                CurveSection("Pressure → size curve", b.sizeCurve, saved?.sizeCurve) { c -> up { it.copy(sizeCurve = c) } }
                CurveSection("Pressure → flow curve", b.opacityCurve, saved?.opacityCurve) { c -> up { it.copy(opacityCurve = c) } }
                Pct("Tilt → width", b.tilt) { v -> up { it.copy(tilt = v) } }
                Pct("Velocity taper (faster = thinner)", b.velSize) { v -> up { it.copy(velSize = v) } }
                Pct("Taper start", b.taperStart) { v -> up { it.copy(taperStart = v) } }
                Pct("Taper end", b.taperEnd) { v -> up { it.copy(taperEnd = v) } }
            }
            Section(ed, "texture", "Texture and grain") {
                PicRow {
                    PicButton(null, b.textureId == 0 && b.texImg == 0, label = "∅") { up { it.copy(textureId = 0, texImg = 0) } }
                    for (t in 1 until TEXTURE_NAMES.size) {
                        PicButton(remember(t, b.texInvert) { stampPreview(previewBrush(textureId = t).copy(texInvert = b.texInvert)) }, b.texImg == 0 && b.textureId == t) { up { it.copy(textureId = t, texImg = 0) } }
                    }
                    for (id in BrushAssets.texIds.toList()) {
                        PicButton(remember(id, b.texInvert) { BrushAssets.mask(id)?.let { stampPreview(previewBrush(texImg = id).copy(texInvert = b.texInvert)) } }, b.texImg == id, onLong = { BrushAssets.forget(id) }) { up { it.copy(texImg = id) } }
                    }
                    PicButton(null, false, label = "+") { pickTex.launch(arrayOf("image/*")) }
                }
                Text("+ imports an image as a texture (bright = ink). Hold a texture to remove it from the library.", fontSize = 11.sp, color = Color.Gray)
                if (b.textureId > 0 || b.texImg != 0) {
                    Pct("Texture strength", b.textureAmt) { v -> up { it.copy(textureAmt = v) } }
                    NumSlider("Texture scale (%)", (b.texScale * 100).roundToInt(), 25, 400) { v -> up { it.copy(texScale = v / 100f) } }
                    FilterChip(b.texInvert, { up { it.copy(texInvert = !b.texInvert) } }, { Text("Invert texture") }, Modifier.fillMaxWidth())
                    FilterChip(b.texMoving, { up { it.copy(texMoving = !b.texMoving) } }, { Text("Texture moves with the stroke (not fixed to the page)") }, Modifier.fillMaxWidth())
                }
                Pct("Grain", b.texture) { v -> up { it.copy(texture = v) } }
            }
            Section(ed, "jitter", "Dab jitter") {
                Pct("Scatter", b.scatter) { v -> up { it.copy(scatter = v) } }
                Pct("Size jitter", b.sizeJitter) { v -> up { it.copy(sizeJitter = v) } }
                Pct("Rotation jitter (shaped tips)", b.angleJitter) { v -> up { it.copy(angleJitter = v) } }
                Pct("Opacity jitter", b.opacityJitter) { v -> up { it.copy(opacityJitter = v) } }
            }
            if (!forE) Section(ed, "wet", "Wet mix") {
                Pct("Wet mix (takes on the colour under the brush)", b.wet) { v -> up { it.copy(wet = v) } }
                if (b.wet > 0f) {
                    Pct("Wet length (how far the colour carries)", b.wetLength) { v -> up { it.copy(wetLength = v) } }
                    NumSlider("Pressure → wetness (− = a light touch mixes more)", (b.wetPressure * 100).roundToInt(), -100, 100) { v -> up { it.copy(wetPressure = v / 100f) } }
                }
            }
            Section(ed, "stab", "Stabilization") {
                STAB_NAMES.forEachIndexed { m, name -> FilterChip(b.stabMethod == m, { up { it.copy(stabMethod = m) } }, { Text(name) }, Modifier.fillMaxWidth()) }
                NumSlider("Stabilization strength", b.stab, 0, 100) { v -> up { it.copy(stab = v) } }
                Text("0 = off, 100 = straight lines only", fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button({ ed.saveBrush(forE, false) }, Modifier.weight(1f), contentPadding = pad) { Text("Save", fontSize = 11.sp) }
                Button({ ed.saveBrush(forE, true) }, Modifier.weight(1f), contentPadding = pad) { Text("Save new", fontSize = 11.sp) }
                OutlinedButton({ ed.resetBrush(forE) }, Modifier.weight(1f), contentPadding = pad) { Text("Reset", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun PicRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)

/** Options of the selected tool. Brush / eraser: preset list (previews), then the chosen preset's detail page. */
@Composable
fun OptionsPanel(ed: Editor, detail: Boolean, setDetail: (Boolean) -> Unit) {
    val brushDetail = detail && ed.tool !in 2..4
    val outerScroll = rememberScrollState()
    Column(
        Modifier.width(290.dp).clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            .background(Color(0xFF2E2E36)).then(if (brushDetail) Modifier else Modifier.verticalScroll(outerScroll)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (ed.tool) {
            2 -> {
                Text("Selection", fontSize = 14.sp)
                listOf("Rectangle", "Lasso", "Point-based").forEachIndexed { i, n ->
                    FilterChip(ed.selMode == i, { ed.selMode = i }, { Text(n) }, Modifier.fillMaxWidth(), leadingIcon = { SelGlyph(i) })
                }
                FilterChip(ed.selWand, { ed.selWand = !ed.selWand }, { Text("Magic wand") }, Modifier.fillMaxWidth(), leadingIcon = { SelGlyph(3) })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("New", "Add (Shift)", "Erase (Alt)").forEachIndexed { i, n ->
                        FilterChip(ed.selOp == i, { ed.selOp = i }, { Text(n, fontSize = 11.sp) }, Modifier.weight(1f))
                    }
                }
                FilterChip(ed.selSmart, { ed.selSmart = !ed.selSmart }, { Text("Stroke-aware selection") }, Modifier.fillMaxWidth())
                FilterChip(ed.selAutoFill, { ed.selAutoFill = !ed.selAutoFill }, { Text("Auto fill the selection") }, Modifier.fillMaxWidth())
                FilterChip(ed.paintInSel, { ed.paintInSel = !ed.paintInSel }, { Text("Draw and paint inside the selection only") }, Modifier.fillMaxWidth())
                if (ed.sel != null) OutlinedButton({ ed.open?.let { invertSelection(ed, it) } }, Modifier.fillMaxWidth()) { Text("Invert selection") }
                Text("Stroke-aware: rectangle, lasso and point selections hug the drawn strokes; a stroke that pokes a few pixels out of the outline is selected with it, the rest of the drawing outside stays untouched. Add / Erase can also be held on a keyboard (Shift / Alt or Ctrl).", fontSize = 12.sp)
                FilterChip(ed.refAll, { ed.refAll = !ed.refAll }, { Text("All visible drawing layers") }, Modifier.fillMaxWidth())
                Text("When enabled, visible drawing layers are combined for boundary detection; when disabled, only the selected drawing layer is sampled. Hidden layers are ignored. Colour tolerance is fixed. The blue preview shows the exact selection; while holding, drag sideways to adjust the gap fill (px) and up or down to expand / reduce. Point-based: tap the first point to close.", fontSize = 12.sp)
            }
            3 -> {
                Text("Fill", fontSize = 14.sp)
                NumSlider("Grow (px)", ed.fillGrow, 0, 6) { ed.fillGrow = it }
                FilterChip(ed.fillAuto, { ed.fillAuto = !ed.fillAuto }, { Text("Auto gap closing") }, Modifier.fillMaxWidth())
                Text("Auto gap closing: when the fill would escape through a gap in the outline, the smallest gap that keeps it inside is closed for you (up to 24 px). Dragging sideways sets the gap by hand instead. A tap on or beside a line fills the open area next to it.", fontSize = 12.sp)
                FilterChip(ed.refAll, { ed.refAll = !ed.refAll }, { Text("All visible drawing layers") }, Modifier.fillMaxWidth())
                Text("When enabled, visible drawing layers are combined for boundary detection; when disabled, only the selected drawing layer is sampled. Hidden layers are ignored. Colour tolerance is fixed. The blue preview shows the exact fill; drag sideways while holding to adjust the gap fill (px), or up / down to grow / shrink.", fontSize = 12.sp)
            }
            4 -> {
                Text("Line tool", fontSize = 14.sp)
                listOf("Line", "Rectangle", "Circle", "Ellipse", "Bezier curve").forEachIndexed { i, n ->
                    FilterChip(ed.lineShape == i, { ed.lineShape = i }, { Text(n) }, Modifier.fillMaxWidth())
                }
                Text("Uses the selected brush and its pressure curves. Circle: drag a corner to the opposite corner. Ellipse: drag the major axis, lift, move the pen to set the minor axis, tap to apply. Bezier curve: drag the end points, lift, move the pen to place the first handle and tap, then place the second handle and tap to apply.", fontSize = 12.sp)
            }
            else -> if (!detail) BrushList(ed, ed.tool == 1) { setDetail(true) } else BrushDetail(ed, ed.tool == 1, { setDetail(false) }, Modifier.weight(1f, fill = false))
        }
    }
}

// ---------- color: triangle + ring wheel ----------

private fun triVerts(h: Float): Array<Offset> {
    val a = Math.toRadians(h.toDouble()).toFloat()
    return Array(3) { k -> val ang = a + k * (2f * PI.toFloat() / 3f); Offset(150f + 116f * cos(ang), 150f + 116f * sin(ang)) }
}

private fun bary(p: Offset, v: Array<Offset>): FloatArray {
    val d = (v[1].y - v[2].y) * (v[0].x - v[2].x) + (v[2].x - v[1].x) * (v[0].y - v[2].y)
    val a = ((v[1].y - v[2].y) * (p.x - v[2].x) + (v[2].x - v[1].x) * (p.y - v[2].y)) / d
    val b = ((v[2].y - v[0].y) * (p.x - v[2].x) + (v[0].x - v[2].x) * (p.y - v[2].y)) / d
    return floatArrayOf(a, b, 1f - a - b)
}

/** Shrinks [r] (a [lo, hi] pixel range) to where the linear function f0 + s * x stays above -eps. */
private fun clipRange(f0: Float, s: Float, eps: Float, r: FloatArray) {
    if (abs(s) < 1e-9f) { if (f0 < -eps) { r[0] = 1f; r[1] = 0f }; return }
    val x = (-eps - f0) / s
    if (s > 0f) { if (x > r[0]) r[0] = x } else { if (x < r[1]) r[1] = x }
}

/**
 * Draws the saturation / value triangle at the bitmap's real pixel size (n x n), so it stays sharp at any screen density.
 * Colors are an exact barycentric RGB mix of hue / white / black (no per-pixel HSV conversion); each row is filled
 * incrementally and only across the triangle, and the three edges get a one-pixel anti-aliased fringe.
 */
private fun fillTriangle(bmp: Bitmap, buf: IntArray, n: Int, h: Float) {
    java.util.Arrays.fill(buf, 0)
    val v = triVerts(h); val sc = 300f / n
    val hc = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)); val hr = ((hc shr 16) and 255).toFloat(); val hg = ((hc shr 8) and 255).toFloat(); val hb = (hc and 255).toFloat()
    val d = (v[1].y - v[2].y) * (v[0].x - v[2].x) + (v[2].x - v[1].x) * (v[0].y - v[2].y)
    val a1 = (v[1].y - v[2].y) / d; val a2 = (v[2].x - v[1].x) / d
    val b1 = (v[2].y - v[0].y) / d; val b2 = (v[0].x - v[2].x) / d
    val da = a1 * sc; val db = b1 * sc; val dc = -da - db      // change of each weight per pixel step
    val heightPx = 174f / sc                                    // triangle height (1.5 x 116) in bitmap pixels
    val eps = 0.75f / heightPx                                  // weight margin that covers the anti-aliased fringe
    val r = FloatArray(2)
    for (y in 0 until n) {
        val py = (y + 0.5f) * sc
        val wa0 = a1 * (0.5f * sc - v[2].x) + a2 * (py - v[2].y)
        val wb0 = b1 * (0.5f * sc - v[2].x) + b2 * (py - v[2].y)
        val wc0 = 1f - wa0 - wb0
        r[0] = 0f; r[1] = (n - 1).toFloat()
        clipRange(wa0, da, eps, r); clipRange(wb0, db, eps, r); clipRange(wc0, dc, eps, r)
        val x0 = kotlin.math.ceil(r[0]).toInt().coerceAtLeast(0); val x1 = kotlin.math.floor(r[1]).toInt().coerceAtMost(n - 1)
        var x = x0
        while (x <= x1) {
            val wa = wa0 + da * x; val wb = wb0 + db * x; val wc = 1f - wa - wb
            val edge = minOf(wa, minOf(wb, wc)) * heightPx     // signed distance (pixels) to the nearest edge
            val cover = (edge + 0.5f).coerceIn(0f, 1f)
            if (cover > 0f) {
                val ca = max(wa, 0f); val cb = max(wb, 0f); val cc = max(wc, 0f)
                val sum = ca + cb + cc
                val ra = ca / sum; val rb = cb / sum
                val red = (ra * hr + rb * 255f + 0.5f).toInt().coerceIn(0, 255)
                val grn = (ra * hg + rb * 255f + 0.5f).toInt().coerceIn(0, 255)
                val blu = (ra * hb + rb * 255f + 0.5f).toInt().coerceIn(0, 255)
                buf[y * n + x] = ((cover * 255f + 0.5f).toInt() shl 24) or (red shl 16) or (grn shl 8) or blu
            }
            x++
        }
    }
    bmp.setPixels(buf, 0, n, 0, 0, n, n)
}

@Composable
fun ColorWheel(hue: Float, sat: Float, v: Float, onChange: (Float, Float, Float) -> Unit) {
    val n = with(LocalDensity.current) { 260.dp.roundToPx() }  // triangle bitmap = the wheel's real pixel size, so it is never scaled (no pixelation)
    val triBmp = remember(n) { Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888) }
    val triBuf = remember(n) { IntArray(n * n) }
    val tri = remember(triBmp) { triBmp.asImageBitmap() }
    remember(hue, n) { fillTriangle(triBmp, triBuf, n, hue); hue }
    val hNow by rememberUpdatedState(hue); val sNow by rememberUpdatedState(sat); val vNow by rememberUpdatedState(v)
    val cb by rememberUpdatedState(onChange)
    Canvas(
        Modifier.size(260.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val k = size.width / 300f
                val ring = hypot(down.position.x / k - 150f, down.position.y / k - 150f) > 121f
                fun handle(pos: Offset) {
                    val q = Offset(pos.x / k, pos.y / k)
                    if (ring) {
                        var a = Math.toDegrees(atan2((q.y - 150f).toDouble(), (q.x - 150f).toDouble())).toFloat(); if (a < 0f) a += 360f
                        cb(a, sNow, vNow)
                    } else {
                        val bc = bary(q, triVerts(hNow))
                        var wa = max(0f, bc[0]); var wb = max(0f, bc[1]); var wc = max(0f, bc[2]); val sum = wa + wb + wc
                        if (sum <= 0f) return
                        wa /= sum; wb /= sum; wc /= sum
                        val vv = 1f - wc
                        cb(hNow, (if (vv < 1e-4f) 0f else wa / vv).coerceIn(0f, 1f), vv.coerceIn(0f, 1f))
                    }
                }
                handle(down.position)
                dragPointer(down.id) { change -> change.consume(); handle(change.position) }
            }
        }
    ) {
        val k = size.width / 300f; val c = Offset(size.width / 2f, size.height / 2f)
        val sweep = androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center = c)
        drawCircle(brush = sweep, radius = 133f * k, center = c, style = Stroke(24f * k))
        drawImage(tri, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        val ang = Math.toRadians(hue.toDouble()).toFloat()
        drawCircle(Color.White, 12f * k, Offset(c.x + 133f * k * cos(ang), c.y + 133f * k * sin(ang)), style = Stroke(3f))
        val vt = triVerts(hue); val pos = (vt[0] * (v * sat) + vt[1] * (v * (1f - sat)) + vt[2] * (1f - v)) * k
        drawCircle(Color.White, 10f * k, pos, style = Stroke(3f)); drawCircle(Color.Black, 12f * k, pos, style = Stroke(1.5f))
    }
}

@Composable
fun ColorDialog(ed: Editor, onDone: () -> Unit) {
    val init = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(ed.color.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2]) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Color") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(ed.color))
                ColorWheel(h, s, v) { nh, ns, nv ->
                    h = nh; s = ns; v = nv
                    ed.color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))); if (ed.tool == 1) ed.tool = 0
                }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Done") } }
    )
}

/** Whole-number keypad (frame lengths, anchor numbers, bake step): see [NumberPadDialog]; the current value opens selected, + - x / work. */
@Composable
fun NumpadDialog(title: String, initial: Int, onOk: (Int) -> Unit, onDismiss: () -> Unit) {
    NumberPadDialog(title, initial.toString(), decimals = false, min = 1.0, max = null, onResult = { onOk(it.roundToInt()) }, onDismiss = onDismiss)
}

/**
 * Cut settings: a panel (not a dialog) that opens on tap of the Cut name and closes when settings are confirmed.
 * Values here are pending ([CutDraft]); the canvas shows resize handles on the frame edges and corners. Confirm writes them to the Cut.
 */
@Composable
fun CutSettingsPanel(ed: Editor, cut: Cut, draft: CutDraft, modifier: Modifier = Modifier) {
    // Settings > Safety: a resize that moves / scales the drawing clears Undo, so Confirm can ask first.
    var askClearUndo by remember { mutableStateOf(false) }
    if (askClearUndo) AlertDialog(
        onDismissRequest = { askClearUndo = false },
        title = { Text("Apply the new Cut size?") },
        text = { Text("The drawing is moved or scaled to the new size and Undo history is cleared. This cannot be undone.") },
        confirmButton = { TextButton({ askClearUndo = false; ed.dropPendingTransform(); draft.apply(); ed.cutDraft = null }) { Text("Apply") } },
        dismissButton = { TextButton({ askClearUndo = false }) { Text("Cancel") } }
    )
    Column(modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF22E2E36)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cut settings", Modifier.weight(1f), fontSize = 15.sp)
            IconBtn(Icons.Filled.Close, size = 32.dp, tip = "Close without applying") { ed.cutDraft = null }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResRow("Width (px)", draft.w.toString(), cut.lockW, { cut.lockW = !cut.lockW }) { v -> v.toFloatOrNull()?.let { draft.changeW(it.roundToInt()) } }
            ResRow("Height (px)", draft.h.toString(), cut.lockH, { cut.lockH = !cut.lockH }) { v -> v.toFloatOrNull()?.let { draft.changeH(it.roundToInt()) } }
            ResRow("Aspect (w / h)", "%.3f".format(draft.aspect), cut.lockA, { cut.lockA = !cut.lockA; if (cut.lockA) cut.aspect = draft.aspect }) { v -> v.toFloatOrNull()?.let { draft.changeAspect(it) } }
            ResRow("DPI", draft.dpi.toString(), cut.lockDpi, { cut.lockDpi = !cut.lockDpi }) { v -> v.toFloatOrNull()?.let { draft.changeDpi(it.roundToInt()) } }
            ResRow("Images per second", draft.fps.toString(), cut.lockFps, { cut.lockFps = !cut.lockFps }) { v -> v.toFloatOrNull()?.let { draft.changeFps(it.roundToInt()) } }
            FilterChip(draft.stretch, { draft.setStretching(!draft.stretch) }, { Text("Stretch image") }, Modifier.fillMaxWidth())
            Text(if (draft.stretch) "Stretch is on: Confirm scales the whole drawing (every layer, camera, reference image and folder) so the old frame fills the new size. This clears Undo."
                else "Stretch is off: the drawing keeps its size. A bigger Cut shows more space, a smaller one crops. Pulling a left or top handle moves that edge of the page and shifts the drawing with it (this clears Undo).", fontSize = 11.sp, color = Color.LightGray)
            Text("Tap a value to type it on the keypad (it also adds, subtracts, multiplies and divides), or drag the cyan handles on the canvas (any corner or edge). A locked value is not changed by edits or by Reset. Nothing is applied until you confirm.", fontSize = 11.sp)
            FilterChip(GpuLayers.enabled, { GpuLayers.enabled = !GpuLayers.enabled }, { Text("GPU layer compositor") }, Modifier.fillMaxWidth())
            Text(if (GpuLayers.failed) "Unavailable on this device: using the standard renderer." else "Draws the layers below the active layer (and all layers during playback) with OpenGL. Turn off to use the standard renderer.", fontSize = 12.sp, color = Color.LightGray)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ draft.reset() }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            Button({ if (draft.changesContent && AppSettings.confirmDestructive) askClearUndo = true else { if (draft.changesContent) ed.dropPendingTransform(); draft.apply(); ed.cutDraft = null } }) { Text("Confirm") }
        }
    }
}

/** [ and ] of the playback window: drag to move, tap for Reset (first / last visible frame across all layers). */
@Composable
private fun WindowMarker(cut: Cut, isStart: Boolean, cell: Dp) {
    var menu by remember { mutableStateOf(false) }
    val x = if (isStart) cell * cut.firstTick() else cell * (cut.lastTick() + 1) - 14.dp
    Box(
        Modifier.offset(x = x).width(14.dp).height(24.dp)
            .pointerInput(isStart, cell) {
                val cellPx = cell.toPx(); var base = 0f
                detectDragGestures(
                    onDragStart = { base = (if (isStart) cut.firstTick() else cut.lastTick() + 1) * cellPx },
                    onDrag = { c, d ->
                        c.consume(); base += d.x
                        val tick = (base / cellPx).roundToInt()
                        if (isStart) cut.winStart = tick.coerceIn(0, cut.lastTick()) else cut.winEnd = (tick - 1).coerceIn(cut.firstTick(), cut.maxTick())
                    }
                )
            }
            .clickable { menu = true }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Color(0xFFFFC107); val sw = 2.dp.toPx()
            val xl = if (isStart) 2.dp.toPx() else this.size.width - 2.dp.toPx()
            val dx = if (isStart) 8.dp.toPx() else -8.dp.toPx()
            drawLine(c, Offset(xl, 0f), Offset(xl, this.size.height), sw)
            // zoomed out: a plain vertical line; the bracket serifs only when frames are wide enough to show them
            if (cell.toPx() >= 12f) {
                drawLine(c, Offset(xl, 1f), Offset(xl + dx, 1f), sw)
                drawLine(c, Offset(xl, this.size.height - 1f), Offset(xl + dx, this.size.height - 1f), sw)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem({ Text("Reset") }, { cut.resetWindow(); menu = false }) }
    }
}

// ================= camera / view helpers =================

@Composable
fun CurveIcon(type: Int, on: Boolean, box: Dp = 36.dp, onClick: () -> Unit) {
    Tip(listOf("Hold", "Linear", "Curve (ease in-out)", "Smooth in", "Smooth out")[type] + " (tap again for options)") { CurveIconRaw(type, on, box, onClick) }
}

@Composable
private fun CurveIconRaw(type: Int, on: Boolean, box: Dp, onClick: () -> Unit) {
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else LightGrey
    Box(
        Modifier.size(box).clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(box * 0.56f)) {
            val p = Path()
            for (i in 0..24) {
                val u = i / 24f; val v = if (type == 0) (if (u < 1f) 0f else 1f) else easeFn(type, u, 0.5f)
                val x = u * size.width; val y = size.height * (1f - v)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, fg, style = Stroke(2.5.dp.toPx()))
        }
    }
}

@Composable
private fun ResRow(label: String, value: String, locked: Boolean, toggle: () -> Unit, onApply: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(value, { onApply(it) }, Modifier.weight(1f), label = label)
        Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.clickable(onClick = toggle).padding(6.dp).size(30.dp))
    }
}

@Composable
fun BoxScope.ViewPopups(ed: Editor, cut: Cut) {
    val ctx = LocalContext.current
    if (ed.viewOpt != 0) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { ed.viewOpt = 0 } })
    if (ed.viewOpt != 0) Column(
        Modifier.align(if (AppSettings.leftHanded) Alignment.TopStart else Alignment.TopEnd).padding(top = 56.dp).toolSidePadding(TOOL_W + 8.dp).width(250.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E2E36)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (ed.viewOpt == 1) {
            Text("Grid", fontSize = 14.sp)
            NumSlider("Density (spacing, px)", ed.gridStep, 8, 400) { ed.gridStep = it }
            Text("Tap the grid button to switch between Behind and In Front.", fontSize = 12.sp, color = Color.LightGray)
        } else if (ed.viewOpt == 3) {
            Text("Bleed", fontSize = 14.sp)
            FilterChip(ed.drawOutsideFrame, { ed.drawOutsideFrame = !ed.drawOutsideFrame }, { Text("Draw outside the Cut") }, Modifier.fillMaxWidth())
            Text("Delete ink outside the Cut", fontSize = 13.sp)
            listOf("This frame", "This layer", "Whole Cut").forEachIndexed { i, label ->
                FilterChip(ed.bleedScope == i, { ed.bleedScope = i }, { Text(label) }, Modifier.fillMaxWidth())
            }
            Button(onClick = {
                if (ed.xform != null) android.widget.Toast.makeText(ctx, "Apply or cancel the transform first", android.widget.Toast.LENGTH_SHORT).show()
                else {
                    cut.playing = false
                    val n = clearBleedInk(ed, cut, ed.bleedScope)
                    android.widget.Toast.makeText(ctx, if (n == 0) "No ink outside the Cut (locked layers are skipped)" else "Trimmed $n stroke${if (n == 1) "" else "s"} back to the Cut", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, Modifier.fillMaxWidth()) { Text("Delete bleed ink") }
            Text("A stroke that starts inside the Cut keeps its part inside; only the part outside goes (a brush-width rim at the edge stays so the inside looks the same). Selection moves and imported images are not trimmed. Locked layers are skipped. Undo restores everything.", fontSize = 12.sp, color = Color.LightGray)
        } else {
            Text("Invert canvas", fontSize = 14.sp)
            FilterChip(ed.flipH, { ed.flipH = !ed.flipH }, { Text("Horizontal") }, Modifier.fillMaxWidth())
            FilterChip(ed.flipV, { ed.flipV = !ed.flipV }, { Text("Vertical") }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun BoxScope.CameraOverlays(ed: Editor, cut: Cut) {
    val cam = cut.layer?.takeIf { it.kind == 2 }?.cam ?: return
    Column(Modifier.align(if (AppSettings.leftHanded) Alignment.TopEnd else Alignment.TopStart).padding(top = 56.dp, start = if (AppSettings.leftHanded) 0.dp else 8.dp, end = if (AppSettings.leftHanded) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ed.camPanel) {
            var open by remember { mutableStateOf(true) }
            Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE2E2E36)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, Modifier.clickable { open = !open }.padding(4.dp).size(28.dp))
                    Text("Camera frame", Modifier.weight(1f), fontSize = 14.sp)
                    Icon(Icons.Filled.Close, null, Modifier.clickable { ed.camPanel = false; ed.camEdit = false }.padding(4.dp).size(28.dp))
                }
                if (open) {
                    ResRow("Width", cam.w.toString(), cam.lockW, { cam.lockW = !cam.lockW }) { v -> v.toFloatOrNull()?.let { cam.changeW(it.roundToInt()) } }
                    ResRow("Height", cam.h.toString(), cam.lockH, { cam.lockH = !cam.lockH }) { v -> v.toFloatOrNull()?.let { cam.changeH(it.roundToInt()) } }
                    ResRow("Aspect (w / h)", "%.3f".format(cam.w / cam.h.toFloat()), cam.lockA, { cam.lockA = !cam.lockA; if (cam.lockA) cam.aspect = cam.w / cam.h.toFloat() }) { v -> v.toFloatOrNull()?.let { cam.changeAspect(it) } }
                    Text("The lock icons keep a value fixed while you change another. Drag with the pen or fingers to move the frame, pinch to scale it; changes are keyed at the playhead.", fontSize = 11.sp)
                }
            }
        }
        if (ed.camOpt == 1 && ed.selKeys.isEmpty()) Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE2E2E36)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Interpolation", Modifier.weight(1f), fontSize = 14.sp)
                Icon(Icons.Filled.Close, null, Modifier.clickable { ed.camOpt = 0 }.padding(4.dp).size(28.dp))
            }
            Pct("Ease strength", ed.ease) { v -> ed.ease = v; applyInterp(cam, cut.time, ed.interp, v) }
            Text("Applies to the segment that starts at the key at or before the playhead.", fontSize = 11.sp)
        }
    }
}

// ================= timeline =================

private class DragSt(val c: Cel, val l: Layer, val mode: Int, val dx: Float) // 0 move, 1 left edge, 2 right edge, 3 shift whole layer
private class LDrag(val l: Layer, val dy: Float, val pointerY: Float = 0f, val startScroll: Int = 0)
/** One ruler number; only the number whose frame becomes / stops being current recomposes during playback. */
@Composable
private fun RulerNum(t: Int, cut: Cut) {
    val on by remember(t) { derivedStateOf { t == cut.time } }
    Text("${t + 1}", fontSize = 10.sp, color = if (on) MaterialTheme.colorScheme.primary else Color.Gray)
}

/** Current value of a Camera / Transformation channel; reads the playhead here so only this text recomposes while playing. */
@Composable
private fun ChanValue(l: Layer, sub: Int, cut: Cut, modifier: Modifier) {
    val t = cut.time
    val value = if (l.isGroup) { val tr = l.transformAt(t); when (sub) { 0 -> "%.0f".format(tr.x); 1 -> "%.0f".format(tr.y); 2 -> "%.2f".format(tr.scale); else -> "%.1f°".format(tr.rotation) } }
    else { val cam = l.cam!!; when (sub) { 0 -> "%.0f".format(cam.x(t, cut.w)); 1 -> "%.0f".format(cam.y(t, cut.h)); 2 -> "%.2f".format(cam.sc(t)); else -> "%.1f°".format(cam.rot(t)) } }
    Text(value, modifier, fontSize = 11.sp, color = Color.Gray)
}

/** Pop-up position: below the anchor when the anchor is in the upper half of the window (fullscreen top bar), above it otherwise. */
private object SmartAnchorPos : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val y = if (anchorBounds.top < windowSize.height / 2) anchorBounds.bottom + 4 else (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Detailed onion skin options (hold the onion button). */
@Composable
fun OnionOptions(ed: Editor, cut: Cut) {
    Column(Modifier.width(286.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E2E36)).heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Onion skin", fontSize = 14.sp)
        FilterChip(ed.onionFade, { ed.onionFade = !ed.onionFade }, { Text("Fade out") }, Modifier.fillMaxWidth())
        Text("When a scrub ends the onion skin shows, then fades away in half a second.", fontSize = 11.sp, color = Color.Gray)
        FilterChip(ed.onionOffScrub, { ed.onionOffScrub = !ed.onionOffScrub }, { Text("Turn off when scrubbing") }, Modifier.fillMaxWidth())
        FilterChip(ed.onionAllLayers, { ed.onionAllLayers = !ed.onionAllLayers }, { Text("Show frames across all layers") }, Modifier.fillMaxWidth())
        Text("Frames farther from the current drawing become progressively more transparent.", fontSize = 11.sp, color = Color.Gray)
        Text("Previous drawings: ${ed.onionPrevious}", fontSize = 12.sp)
        Slider(ed.onionPrevious.toFloat(), { ed.onionPrevious = it.roundToInt().coerceIn(0, 8) }, valueRange = 0f..8f, steps = 7)
        Text("Following drawings: ${ed.onionFollowing}", fontSize = 12.sp)
        Slider(ed.onionFollowing.toFloat(), { ed.onionFollowing = it.roundToInt().coerceIn(0, 8) }, valueRange = 0f..8f, steps = 7)
        Text("Choose how many previous and following drawings are shown around the current frame.", fontSize = 11.sp, color = Color.Gray)
    }
}

/** Rows of the fullscreen single-layer timeline: the current layer (and a Camera / Transformation folder's channel rows when expanded). */
private fun miniItems(cut: Cut): List<RowItem> {
    val l = cut.layers.firstOrNull { it.id == cut.layerId } ?: return emptyList()
    val out = ArrayList<RowItem>(); out.add(RowItem(l, -1))
    if ((l.kind == 2 || (l.isGroup && l.transformFolder)) && l.expanded) for (i in 0..3) out.add(RowItem(l, i))
    return out
}

/** Number of rows [miniItems] gives for the current layer (used to size the fullscreen single-layer timeline). */
fun miniRowCount(cut: Cut): Int = miniItems(cut).size

private class MDrag(val dy: Float)

/**
 * Fullscreen top bar: up / down buttons that flip to the layer above / below, then the current layer (name, visibility, lock, three dots).
 * Tap or hold the layer name to unfold the list; tap a layer in the list to make it current. Hold a layer inside the unfolded list and
 * drag it to place it above / under the others (the drag lives in the list itself, so the held row and the finger are the same thing).
 */
@Composable
fun MiniLayerButton(ed: Editor, cut: Cut) {
    val cur = cut.layers.firstOrNull { it.id == cut.layerId } ?: return
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<MDrag?>(null) }
    val rowH = 36.dp; val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val rows = cut.layers.reversed().filter { l -> l.isGroup || cut.layers.firstOrNull { it.id == l.group }?.collapsed != true }
    val curAt = rows.indexOfFirst { it.id == cut.layerId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Keep the current-layer bar at the leading edge; navigation arrows sit immediately to its right.
        Box {
            Row(Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34343C)), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.fillMaxHeight().pointerInput(Unit) { // tap or hold the layer name: unfold / fold the list
                        detectTapGestures(onTap = { ed.miniLayers = !ed.miniLayers }, onLongPress = { ed.miniLayers = true })
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (cur.isGroup) Icons.Filled.Folder else when (cur.kind) { 2 -> Icons.Filled.Videocam; 6 -> Icons.Filled.PlayArrow; 7 -> Icons.Filled.Videocam; 3 -> Icons.Filled.GridOn; 4 -> Icons.Filled.ImageIc; 5 -> Icons.Filled.ImageIc; else -> if (cur.vector) Icons.Filled.Edit else Icons.Filled.BrushIc }, null, Modifier.padding(start = 6.dp).size(16.dp))
                    Text(cur.name, Modifier.widthIn(max = 90.dp).padding(horizontal = 6.dp), maxLines = 1, fontSize = 12.sp)
                }
                if (!cur.isGroup) Icon(if (cur.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, Modifier.clickable { cut.record { cur.visible = !cur.visible } }.padding(4.dp).size(24.dp))
                if (cur.kind == 0 || cur.kind == 4 || cur.kind == 5 || cur.kind == 6 || cur.kind == 7) Icon(if (cur.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.clickable { cut.record { cur.locked = !cur.locked } }.padding(4.dp).size(24.dp))
                if (cur.kind == 0 || cur.kind == 2 || (cur.isGroup && cur.transformFolder)) Box {
                    Icon(Icons.Filled.MoreVert, null, Modifier.clickable { menu = true }.padding(4.dp).size(24.dp))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (cur.isGroup) {
                            if (cur.transformFolder) DropdownMenuItem({ Text("Select all") }, { cur.expanded = true; ed.clearMulti(); ed.selKeys.addAll(selectAllKeys(cur)); menu = false })
                            DropdownMenuItem({ Text(if (cur.uniformTiming) "Uniform timing (on)" else "Uniform timing") }, { cut.setUniformTiming(cur, !cur.uniformTiming); menu = false })
                        } else if (cur.kind == 2) {
                            DropdownMenuItem({ Text("Set resolution") }, { ed.camPanel = true; ed.camEdit = true; menu = false })
                            DropdownMenuItem({ Text("Select all") }, { ed.clearMulti(); ed.selKeys.addAll(selectAllKeys(cur)); menu = false })
                            DropdownMenuItem({ Text("Rasterize") }, { rasterizeCamera(cut, cur); menu = false })
                        } else {
                            DropdownMenuItem({ Text("Select all") }, { cut.selAll = cur.id; menu = false })
                            DropdownMenuItem({ Text("Duplicate") }, { cut.duplicateLayer(true); menu = false })
                            DropdownMenuItem({ Text("Duplicate but empty") }, { cut.duplicateLayer(false); menu = false })
                            DropdownMenuItem({ Text("Duplicate Empty Vector") }, { cut.duplicateEmptyVector(); menu = false })
                            if (cur.uniformAttached) DropdownMenuItem({ Text("Detach timing") }, { cut.detachTiming(cur); menu = false })
                            else if (cut.layerById(cur.group)?.uniformTiming == true) DropdownMenuItem({ Text("Attach timing") }, { cut.attachTiming(cur); menu = false })
                            DropdownMenuItem({ Text("Flatten w/ layer below") }, { flattenDown(cut, cur); menu = false })
                        }
                    }
                }
            }
            if (ed.miniLayers) Popup(popupPositionProvider = SmartAnchorPos, onDismissRequest = { if (drag == null) ed.miniLayers = false }, properties = PopupProperties(focusable = true)) {
                Box(Modifier.width(230.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E36))) {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        val d = drag
                        rows.forEach { l ->
                            val me = l.id == cut.layerId
                            Row(
                                Modifier.height(rowH).fillMaxWidth()
                                    .offset { IntOffset(0, if (me && d != null) d.dy.roundToInt() else 0) }
                                    .alpha(if (me && d != null) 0.6f else 1f)
                                    .background(if (me) Color(0xFF3A3A58) else Color.Transparent)
                                    .clickable { cut.layerId = l.id; ed.miniLayers = false }
                                    .pointerInput(l.id, cut) { // hold this row and drag it to its new place
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { cut.layerId = l.id; drag = MDrag(0f) },
                                            onDrag = { ch, a -> ch.consume(); drag = drag?.let { MDrag(it.dy + a.y) } },
                                            onDragEnd = {
                                                val dd = drag; drag = null
                                                if (dd != null) {
                                                    val rs = cut.layers.reversed().filter { x -> x.isGroup || cut.layers.firstOrNull { it.id == x.group }?.collapsed != true }
                                                    val at = rs.indexOfFirst { it.id == l.id }
                                                    if (at >= 0) plan(rs, l, at + 0.5f + dd.dy / rowPx)?.let { (a, after, g) -> cut.moveLayer(l, a, after, g) }
                                                }
                                            },
                                            onDragCancel = { drag = null }
                                        )
                                    }
                                    .padding(start = if (l.group >= 0) 14.dp else 0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (l.isGroup) Icons.Filled.Folder else when (l.kind) { 2 -> Icons.Filled.Videocam; 6 -> Icons.Filled.PlayArrow; 7 -> Icons.Filled.Videocam; 3 -> Icons.Filled.GridOn; 4 -> Icons.Filled.ImageIc; 5 -> Icons.Filled.ImageIc; else -> if (l.vector) Icons.Filled.Edit else Icons.Filled.BrushIc }, null, Modifier.padding(start = 8.dp).size(18.dp))
                                Text(l.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, fontSize = 13.sp)
                                if (!l.isGroup) Icon(if (l.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, Modifier.clickable { cut.record { l.visible = !l.visible } }.padding(6.dp).size(28.dp))
                            }
                        }
                    }
                    drag?.let { d -> // drop indicator while the current layer is dragged
                        val at = rows.indexOfFirst { it.id == cut.layerId }
                        rows.getOrNull(at)?.let { me ->
                            plan(rows, me, at + 0.5f + d.dy / rowPx)?.let { (a, after, _) ->
                                Box(Modifier.offset(y = rowH * (if (after) rows.lastIndexOf(a) + 1 else rows.indexOf(a))).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // Up = the layer above the current one in the list; down = the layer below it.
        IconBtn(Icons.Filled.KeyboardArrowUp, size = 30.dp, tip = "Layer above") { rows.getOrNull(curAt - 1)?.let { cut.layerId = it.id } }
        IconBtn(Icons.Filled.KeyboardArrowDown, size = 30.dp, tip = "Layer below") { rows.getOrNull(curAt + 1)?.let { cut.layerId = it.id } }
    }
}

private class RowItem(val l: Layer, val sub: Int) // sub >= 0: one of a camera's x / y / scale key rows

private fun items(cut: Cut): List<RowItem> {
    val out = ArrayList<RowItem>()
    for (l in cut.layers.reversed()) {
        if (!l.isGroup && cut.layers.firstOrNull { it.id == l.group }?.collapsed == true) continue
        out.add(RowItem(l, -1))
        if ((l.kind == 2 || (l.isGroup && l.transformFolder)) && l.expanded) for (i in 0..3) out.add(RowItem(l, i))
    }
    return out
}

/** Where a dragged layer lands: (anchor row, after anchor?, new group id) or null for no change. */
private fun plan(rows: List<Layer>, d: Layer, pos: Float): Triple<Layer, Boolean, Int>? {
    if (rows.isEmpty()) return null
    val ri = pos.toInt().coerceIn(0, rows.size - 1); val frac = (pos - pos.toInt()).coerceIn(0f, 1f)
    val t = rows[ri]
    if (t === d) return null
    if (t.isGroup) return if (frac < 0.3f) Triple(t, false, -1) else Triple(t, true, t.id)
    val before = frac < 0.5f
    var k = if (before) ri - 1 else ri + 1
    if (rows.getOrNull(k) === d) k += if (before) -1 else 1
    val nb = rows.getOrNull(k)
    val inGroup = t.group >= 0 && nb != null && ((nb.isGroup && nb.id == t.group) || (!nb.isGroup && nb.group == t.group))
    return Triple(t, !before, if (inGroup) t.group else -1)
}

/** How long a frame must be held (without moving) before it can be dragged: shorter than the system long-press. */
const val CEL_HOLD_MS = 200L // default; the live value is AppSettings.celHoldMs (Settings > Timings)

/** Puts a pop-up centred just above its anchor, never on top of it. */
private object AboveAnchorPos : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** "Add key at playhead" button: a keyframe diamond with a plus. Same button for Camera layers and Transformation folders. */
@Composable
fun KeyBtn(box: Dp = 36.dp, onClick: () -> Unit) {
    Tip("Add key at playhead") {
        Box(
            Modifier.size(box).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(box * 0.6f)) {
                val w = this.size.width; val h = this.size.height; val sw = 1.8.dp.toPx(); val amber = Color(0xFFFFC107)
                val p = Path()
                p.moveTo(w * 0.5f, h * 0.04f); p.lineTo(w * 0.96f, h * 0.5f); p.lineTo(w * 0.5f, h * 0.96f); p.lineTo(w * 0.04f, h * 0.5f); p.close()
                drawPath(p, amber, style = Stroke(sw))
                drawLine(amber, Offset(w * 0.5f, h * 0.3f), Offset(w * 0.5f, h * 0.7f), sw)
                drawLine(amber, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.7f, h * 0.5f), sw)
            }
        }
    }
}

/** The layer the add-key button / shortcut applies to: the selected Transformation folder or the selected Camera layer. */
fun keyTarget(cut: Cut): Layer? =
    cut.layers.firstOrNull { it.isGroup && it.id == cut.layerId && it.transformFolder } ?: cut.layer?.takeIf { it.kind == 2 }

/** Adds a key (all four channels, holding the current values) at the playhead on the selected Camera layer or Transformation folder. */
fun addKeyAtPlayhead(ed: Editor, cut: Cut) {
    val target = keyTarget(cut) ?: return
    val cam = target.cam
    cut.record {
        if (cam != null) keyCameraAt(cut, cam, cut.time, ed.interp, ed.ease) else keyFolderAt(target, cut.time, ed.interp, ed.ease)
        Unit
    }
}

/** Diamond key. `selected` keys can be held and dragged together (onBulk: 0 = start, 1 = moving with total dx in px, 2 = released). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyMark(cell: Dp, t: Int, isNow: () -> Boolean, selected: Boolean, shift: Float, onClick: () -> Unit, onLong: () -> Unit, onBulk: (Int, Float) -> Unit) {
    val bulk by rememberUpdatedState(onBulk)
    val on by remember(t) { derivedStateOf { isNow() } }
    Box(
        Modifier.offset(cell * t + cell / 2 - 7.dp, 15.dp).offset { IntOffset(if (selected) shift.roundToInt() else 0, 0) }.size(14.dp)
            .pointerInput(selected, cell) { // selected keys only: hold, then drag them all sideways
                if (!selected) return@pointerInput
                val cellPx = cell.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var held = true
                    withTimeoutOrNull(AppSettings.celHoldMs.toLong()) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) { held = false; break }
                        }
                    }
                    if (!held) return@awaitEachGesture
                    bulk(0, 0f)
                    var tot = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = ch.positionChange().x
                        ch.consume()
                        if (!ch.pressed) break
                        tot += dx; bulk(1, tot)
                    }
                    bulk(2, tot)
                }
            }
            .rotate(45f)
            .background(if (selected) Color(0xFF4DD0E1) else if (on) MaterialTheme.colorScheme.primary else Color(0xFFFFC107))
            .then(if (selected) Modifier.border(2.dp, Color.White) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    )
}

private const val MULTI_GAP_MS = 350L // (default; live value AppSettings.doubleTouchMs) a second touch must land this soon after the first one lifted (and near it) ...
private const val MULTI_TAP_MS = 300L // ... and the first touch only counts if it was a quick, still tap

/**
 * Double-touch + drag selection. Tap, then touch again within MULTI_GAP_MS close to the first tap and move more than the touch slop
 * before the hold time (CEL_HOLD_MS) elapses: the drag draws a rectangle (finger or pen). Single taps, holds and ordinary pans are left
 * untouched. onTap fires for every quick tap; onDone receives the rectangle (in this element's own coordinates) on release.
 */
@Composable
private fun Modifier.marqueeSelect(onRect: (Rect?) -> Unit, onDone: (Rect) -> Unit, onTap: () -> Unit, onCut: (Offset, Offset) -> Boolean = { _, _ -> false }, enabled: Boolean = false): Modifier {
    val rectCb by rememberUpdatedState(onRect); val doneCb by rememberUpdatedState(onDone); val tapCb by rememberUpdatedState(onTap); val cutCb by rememberUpdatedState(onCut)
    val enabledNow by rememberUpdatedState(enabled)
    return pointerInput(Unit) {
        var lastUp = -1L; var lastPos = Offset.Zero
        val radius = 36.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val armed = enabledNow || (lastUp >= 0L && down.uptimeMillis - lastUp <= AppSettings.doubleTouchMs && (down.position - lastPos).getDistance() <= radius)
            var marquee = false
            fun rectOf(b: Offset) = Rect(min(down.position.x, b.x), min(down.position.y, b.y), max(down.position.x, b.x), max(down.position.y, b.y))
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (!marquee && ev.changes.count { it.pressed } > 1) { lastUp = -1L; return@awaitEachGesture } // second finger: pinch / other gesture
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) {
                    if (marquee) { c.consume(); if (!cutCb(down.position, c.position)) doneCb(rectOf(c.position)); rectCb(null); lastUp = -1L }
                    else if (c.uptimeMillis - down.uptimeMillis <= MULTI_TAP_MS && (c.position - down.position).getDistance() <= slop) { lastUp = c.uptimeMillis; lastPos = down.position; tapCb() }
                    else lastUp = -1L
                    return@awaitEachGesture
                }
                if (!marquee && armed && (enabledNow || c.uptimeMillis - down.uptimeMillis < AppSettings.celHoldMs) && (c.position - down.position).getDistance() > slop) marquee = true
                if (marquee) { c.consume(); rectCb(rectOf(c.position)) }
            }
            if (marquee) rectCb(null)
        }
    }
}

@Composable
private fun MarqueeBox(r: Rect, scrollY: Int = 0) {
    val dens = LocalDensity.current
    Box(
        Modifier.offset { IntOffset(r.left.roundToInt(), (r.top - scrollY).roundToInt()) }
            .size(with(dens) { r.width.toDp() }, with(dens) { r.height.toDp() })
            .background(Color(0x334DD0E1)).border(1.dp, Color(0xFF4DD0E1))
    )
}

/** Moves the selected frames together by n timeline cells: on each layer they keep their order and land as one block (frames are reordered, never overlapped). */
private fun moveCelsBulk(cut: Cut, sel: List<Cel>, n: Int) {
    for (l in cut.layers) {
        val mine = l.cels.filter { c -> sel.any { it === c } }
        if (mine.isEmpty()) continue
        val center = l.startOf(l.cels.indexOf(mine.first())) + n + mine.sumOf { it.dur } / 2f
        var cursor = 0f; var ins = 0
        for (o in l.cels) { if (mine.none { it === o } && center > cursor + o.dur / 2f) ins++; cursor += o.dur }
        l.cels.removeAll { c -> mine.any { it === c } }
        l.cels.addAll(ins.coerceIn(0, l.cels.size), mine)
    }
}

/** Shake options: strength, density (one new key every N frames) and horizontal / vertical locks. Applies between the selected diamond keys. */
@Composable
private fun ShakeMenu(ed: Editor, cut: Cut) {
    val ready = shakeReady(ed.selKeys.toList()) && !(ed.shakeLockH && ed.shakeLockV)
    Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E2E36)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shake", Modifier.weight(1f), fontSize = 14.sp)
            Icon(Icons.Filled.Close, null, Modifier.clickable { ed.camOpt = 0 }.padding(4.dp).size(28.dp))
        }
        NumSlider("Strength (px)", ed.shakeAmt, 1, 200) { ed.shakeAmt = it }
        NumSlider("Density (1 = a key every frame, 2 = every second frame...)", ed.shakeDensity, 1, 24) { ed.shakeDensity = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(ed.shakeLockH, { ed.shakeLockH = !ed.shakeLockH }, { Text("Horizontal lock") }, Modifier.weight(1f))
            FilterChip(ed.shakeLockV, { ed.shakeLockV = !ed.shakeLockV }, { Text("Vertical lock") }, Modifier.weight(1f))
        }
        Text(
            if (ed.shakeLockH && ed.shakeLockV) "Both axes are locked: nothing would move."
            else if (!shakeReady(ed.selKeys.toList())) "Select two or more diamond keys first (double-touch and drag over them). New keys appear between them; a locked axis is not shaken."
            else "New keys appear between the selected ones; a locked axis is not shaken.",
            fontSize = 11.sp, color = Color.Gray
        )
        Button({
            cut.record { shakeSelected(cut, ed.selKeys.toList(), ed.shakeAmt.toFloat(), ed.shakeDensity, ed.shakeLockH, ed.shakeLockV) }
            ed.camOpt = 0
        }, enabled = ready) { Text("Apply") }
    }
}

/** Set [ and ] to the first/last selected cel or diamond-key time. */
private fun setWindowToSelection(ed: Editor, cut: Cut) {
    var first = Int.MAX_VALUE
    var last = -1
    for (cel in ed.selCels) {
        val owner = cut.layers.firstOrNull { it.cels.contains(cel) } ?: continue
        val start = owner.startOf(owner.cels.indexOf(cel))
        first = minOf(first, start)
        last = maxOf(last, start + cel.dur - 1)
    }
    for (key in ed.selKeys) { first = minOf(first, key.t); last = maxOf(last, key.t) }
    if (last >= 0) { cut.winStart = first.coerceAtLeast(0); cut.winEnd = maxOf(first, last) }
}


@Composable
private fun TimelineToolbarContent(
    ed: Editor,
    cut: Cut,
    mode: Int,
    buttonSize: Dp,
    keysPicked: Boolean,
    onImportImages: () -> Unit,
    onImportReference: () -> Unit,
    onAddReference: () -> Unit,
    onImportAudio: () -> Unit,
    onImportVideo: () -> Unit,
    addMenu: Boolean,
    onAddMenuChange: (Boolean) -> Unit,
    onOpacityChange: (Layer, Float) -> Unit,
    onOpacityFinished: () -> Unit
) {
    val diamondTarget = keyTarget(cut) != null
    // Explicit timeline selection mode also supports non-contiguous cel/key picks.
    IconBtn(Icons.Filled.HighlightAlt, on = ed.timelineSelectMode, size = buttonSize, tip = if (ed.timelineSelectMode) "Timeline selection: on. Tap or drag frames and diamond keys to select them (tap to turn off)" else "Timeline selection: off. Tap to select frames and diamond keys by tapping or dragging") { ed.timelineSelectMode = !ed.timelineSelectMode }
    val durationTargets = when {
        ed.selCels.isNotEmpty() -> ed.selCels.toList()
        cut.selAll == cut.layerId && cut.layer?.kind == 0 -> cut.layer?.cels?.toList() ?: emptyList() // three-dot "Select all" behaves like selecting every frame by hand
        ed.selKeys.isEmpty() -> listOfNotNull(cut.layer?.takeIf { it.kind == 0 }?.celAt(cut.time))
        else -> emptyList()
    }
    fun diamondSelection(): List<KeySel> {
        if (ed.selKeys.isNotEmpty()) return ed.selKeys.toList()
        val target = keyTarget(cut) ?: return emptyList()
        return (0..3).flatMap { ci -> target.keyChan(ci)?.filter { it.t == cut.time }?.map { KeySel(target.id, ci, it.t) } ?: emptyList() }
    }
    if (diamondTarget) {
        IconBtn(Icons.Filled.ContentCopy, size = buttonSize, tip = "Copy selected diamond keys") {
            val selection = diamondSelection()
            if (selection.isNotEmpty()) cut.record { val copied = duplicateKeysSel(cut, selection); ed.selKeys.clear(); ed.selKeys.addAll(copied) }
        }
        IconBtn(Icons.Filled.Delete, size = buttonSize, tip = "Delete selected diamond keys") {
            val selection = diamondSelection()
            if (selection.isNotEmpty()) cut.record { deleteKeysSel(cut, selection); ed.selKeys.clear() }
        }
    } else {
        IconBtn(Icons.Filled.Add, size = buttonSize) { cut.addFrame() }
        IconBtn(Icons.Filled.ContentCopy, size = buttonSize, tip = "Copy frame (the selected frames, or the current one)") { cut.duplicateFrame(ed.selCels.toList()) }
        IconBtn(Icons.Filled.Link, size = buttonSize, tip = "Clone frame (linked, non-unique copy)") { cut.cloneFrames(ed.selCels.toList()) }
        IconBtn(Icons.Filled.Delete, on = cut.selAll == cut.layerId, size = buttonSize, tip = "Delete frame") { cut.deleteFrame() }
        IconBtn(Icons.Filled.CheckBoxOutlineBlank, size = buttonSize, tip = "Empty frame (keeps the timing)") { cut.emptyFrames(ed.selCels.toList()) }
    }
    if (ed.selCels.isNotEmpty() || ed.selKeys.isNotEmpty()) {
        IconBtn(Icons.Filled.SelectAll, size = buttonSize, tip = "Set playback window to selected frames and keys") { setWindowToSelection(ed, cut) }
    }
    if (!diamondTarget && ed.selCels.isNotEmpty()) {
        val allPersistent = ed.selCels.all { it.persistent }
        TextButton(onClick = { cut.record { ed.selCels.forEach { it.persistent = !allPersistent } } }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
            Text(if (allPersistent) "Reset persistence" else "Persist on canvas", fontSize = 11.sp)
        }
        TextButton(onClick = {
            val selected = ed.selCels.toSet()
            cut.record {
            cut.layers.filter { !it.isGroup && it.kind == 0 }.forEach { layer ->
                val indices = layer.cels.indices.filter { layer.cels[it] in selected }
                for (idx in indices) nameAsInbetween(layer, idx) { it in selected }
            }
            }
        }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("Set inbetweens", fontSize = 11.sp) }
    }
    // The chronometer is on the timeline bar, including when only the playhead's current cel is active.
    var showDurationPad by remember { mutableStateOf(false) }
    if (durationTargets.isNotEmpty()) {
        IconBtn(Icons.Filled.Timer, size = buttonSize, tip = "Set selected frame duration") { showDurationPad = true }
        if (showDurationPad) NumpadDialog(
            if (durationTargets.size == 1) "New length of this frame (frames)" else "New length of every selected frame (frames)",
            durationTargets.firstOrNull()?.dur ?: 1,
            { n -> if (durationTargets.isNotEmpty()) cut.record { if (ed.rippleRetime && durationTargets.size == 1) retimeKeepingLength(cut, durationTargets[0], n) else durationTargets.forEach { it.dur = n } } },
            { showDurationPad = false }
        )
    }
    IconBtn(Icons.Filled.AddPhotoAlternate, size = buttonSize, onClick = onImportImages)
    if (cut.layer?.kind == 5) { // a reference layer is selected: add more images to it
        TextButton(onClick = onAddReference, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Add reference", fontSize = 11.sp) }
    }
    if (cut.layer?.vector == true) { // a vector layer is selected: its toolset joins the timeline bar
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Color(0x33FFFFFF)))
        VectorToolStrip(cut, ed, buttonSize, durationTargets)
    }
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Color(0x33FFFFFF)))
    Box {
        IconBtn(Icons.Filled.PlaylistAdd, size = buttonSize) { onAddMenuChange(true) }
        DropdownMenu(expanded = addMenu, onDismissRequest = { onAddMenuChange(false) }) {
            DropdownMenuItem({ Text("Drawing layer") }, { cut.addLayer(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.BrushIc, null) })
            DropdownMenuItem({ Text("Vector layer") }, { cut.addVector(); ed.vecMode = 0; ed.tool = 0; onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
            DropdownMenuItem({ Text("Reference image…") }, { onImportReference(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.ImageIc, null) })
            DropdownMenuItem({ Text("Audio layer…") }, { onImportAudio(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
            DropdownMenuItem({ Text("Video layer…") }, { onImportVideo(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.Videocam, null) })
            DropdownMenuItem({ Text("Background") }, { cut.addBackground(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.ImageIc, null) })
            DropdownMenuItem({ Text("Camera") }, { cut.addCamera(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.Videocam, null) })
            DropdownMenuItem({ Text("Perspective") }, { cut.addPerspective(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.GridOn, null) })
            DropdownMenuItem({ Text("Folder") }, { cut.addGroup(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.Folder, null) })
            DropdownMenuItem({ Text("Transformation") }, { cut.addTransformFolder(); onAddMenuChange(false) }, leadingIcon = { Icon(Icons.Filled.OpenWith, null) })
        }
    }
    IconBtn(Icons.Filled.LayersClear, size = buttonSize) { cut.deleteLayer() }
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Color(0x33FFFFFF)))
    Box {
        OnionBtn(ed.onion, buttonSize, onLong = { ed.onionOpt = !ed.onionOpt }) { ed.onion = !ed.onion }
        if (ed.onionOpt) Popup(popupPositionProvider = SmartAnchorPos, onDismissRequest = { ed.onionOpt = false }, properties = PopupProperties(focusable = true)) { OnionOptions(ed, cut) }
    }
    IconBtn(Icons.Filled.CenterFocusStrong, size = buttonSize, on = ed.outOfPeg, tip = "Out of Peg reference adjustment") {
        ed.outOfPeg = !ed.outOfPeg
        ed.outOfPegSide = 0
    }
    if (ed.outOfPeg) {
        IconBtn(Icons.Filled.SkipPrevious, size = buttonSize, on = ed.outOfPegSide == -1, tip = "Adjust previous drawing") {
            if (ed.outOfPegSide == -1) { ed.resetReferencePose(cut.layer?.adjacentCel(cut.time, -1)); ed.outOfPegSide = 0 } else ed.outOfPegSide = -1
        }
        IconBtn(Icons.Filled.SkipNext, size = buttonSize, on = ed.outOfPegSide == 1, tip = "Adjust following drawing") {
            if (ed.outOfPegSide == 1) { ed.resetReferencePose(cut.layer?.adjacentCel(cut.time, 1)); ed.outOfPegSide = 0 } else ed.outOfPegSide = 1
        }
    }
    if (keyTarget(cut) != null) {
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Color(0x33FFFFFF)))
        KeyBtn(buttonSize) { addKeyAtPlayhead(ed, cut) }
    }
    val selectedLayer = cut.layer
    if (selectedLayer != null) {
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Color(0x33FFFFFF)))
        if (mode != 1) Text("Opacity", fontSize = 11.sp, color = Color.Gray)
        Slider(
            selectedLayer.opacity,
            { value -> onOpacityChange(selectedLayer, value) },
            Modifier.width(if (mode == 1) 84.dp else 120.dp),
            onValueChangeFinished = onOpacityFinished
        )
        Text("${(selectedLayer.opacity * 100).roundToInt()}%", fontSize = 11.sp)
    }
    if (mode == 1) {
        // Simplified brush resize (same value as the toolbar's size button); square-root scale so small sizes stay easy to hit.
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(18.dp).background(Color(0x33FFFFFF)))
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFBDBDC8)))
        Slider(
            sqrt(((ed.brush.size - 1f) / 299f).coerceIn(0f, 1f)),
            { v -> ed.update(ed.erase) { it.copy(size = (1f + 299f * v * v).roundToInt().toFloat()) } },
            Modifier.width(84.dp)
        )
        Text("${ed.brush.size.roundToInt()}", fontSize = 11.sp)
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(18.dp).background(Color(0x33FFFFFF)))
        var palOpen by remember { mutableStateOf(false) }
        IconBtn(Icons.Filled.Palette, size = 30.dp, tip = "Color palettes") { palOpen = true }
        TextButton(onClick = { ed.tool = 5 }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("◉", fontSize = 18.sp, color = if (ed.tool == 5) MaterialTheme.colorScheme.primary else Color.White) }
        if (palOpen) PaletteDialog(ed) { palOpen = false }
    }
}

@Composable
private fun TimelineCurveContent(
    ed: Editor,
    cut: Cut,
    buttonSize: Dp,
    selCam: Camera?,
    selFolder: Layer?,
    keysPicked: Boolean
) {
    val shown: Int? = when {
        keysPicked -> selInterp(cut, ed.selKeys.toList())
        selFolder != null && !cut.playing -> selFolder.transformChannels().mapNotNull { ch -> ch.lastOrNull { it.t <= cut.time }?.interp }.firstOrNull() ?: ed.interp
        else -> ed.interp
    }
    for (t in 0..4) CurveIcon(t, shown == t, buttonSize) {
        if (shown == t) ed.camOpt = if (ed.camOpt == 1) 0 else 1
        else {
            ed.interp = t; ed.camOpt = 0
            cut.record {
                if (keysPicked) applyInterpSel(cut, ed.selKeys.toList(), t, ed.ease)
                else if (selFolder != null) {
                    if (selFolder.transformChannels().all { ch -> ch.none { it.t <= cut.time } }) {
                        val value = selFolder.transformAt(cut.time)
                        selFolder.setTransformAt(cut.time, value.x, value.y, value.scale, value.rotation, t, ed.ease)
                    }
                    applyInterp(selFolder, cut.time, t, ed.ease)
                } else if (selCam != null) applyInterp(selCam, cut.time, t, ed.ease)
                Unit
            }
        }
    }
    if (ed.camOpt == 1 && (keysPicked || selFolder != null)) Pct("Ease strength", ed.ease) { value ->
        ed.ease = value
        if (keysPicked) applyInterpSel(cut, ed.selKeys.toList(), null, value)
        else if (selFolder != null) applyInterp(selFolder, cut.time, ed.interp, value)
    }
    Spacer(Modifier.width(10.dp))
    Box {
        IconBtn(Icons.Filled.Vibration, on = ed.camOpt == 2, size = buttonSize) { ed.camOpt = if (ed.camOpt == 2) 0 else 2 }
        if (ed.camOpt == 2) Popup(popupPositionProvider = AboveAnchorPos, onDismissRequest = { ed.camOpt = 0 }, properties = PopupProperties(focusable = true)) { ShakeMenu(ed, cut) }
    }
}

@Composable
private fun TimelineDialogs(
    ed: Editor,
    cut: Cut,
    renaming: Layer?,
    onRenameDismiss: () -> Unit,
    transformDialogFor: Layer?,
    onTransformDismiss: () -> Unit
) {
    renaming?.let { layer ->
        var name by remember(layer) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = onRenameDismiss,
            title = { Text(if (layer.isGroup) "Rename folder" else "Rename layer") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ if (name.isNotBlank()) cut.record { layer.name = name.trim() }; onRenameDismiss() }) { Text("OK") } }
        )
    }
    transformDialogFor?.let { folder ->
        val current = folder.transformAt(cut.time)
        var x by remember(folder, cut.time) { mutableStateOf(current.x.toString()) }
        var y by remember(folder, cut.time) { mutableStateOf(current.y.toString()) }
        var scale by remember(folder, cut.time) { mutableStateOf(current.scale.toString()) }
        var rotation by remember(folder, cut.time) { mutableStateOf(current.rotation.toString()) }
        AlertDialog(
            onDismissRequest = onTransformDismiss,
            title = { Text("Transformation at frame ${cut.time + 1}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Drawing layers inside this folder follow these animated values.", fontSize = 12.sp, color = Color.Gray)
                NumberField(x, { x = it }, Modifier.fillMaxWidth(), label = "X offset (px)")
                NumberField(y, { y = it }, Modifier.fillMaxWidth(), label = "Y offset (px)")
                NumberField(scale, { scale = it }, Modifier.fillMaxWidth(), label = "Scale", min = 0.01)
                NumberField(rotation, { rotation = it }, Modifier.fillMaxWidth(), label = "Rotation (°)")
            } },
            confirmButton = { TextButton({
                val nx = x.toFloatOrNull(); val ny = y.toFloatOrNull(); val ns = scale.toFloatOrNull(); val nr = rotation.toFloatOrNull()
                if (nx != null && ny != null && ns != null && nr != null && ns > 0f) cut.record { folder.setTransformAt(cut.time, nx, ny, ns, nr, ed.interp, ed.ease) }
                onTransformDismiss()
            }) { Text("Set key") } },
            dismissButton = { TextButton(onTransformDismiss) { Text("Cancel") } }
        )
    }
}

/** Mutable UI state shared by the independently compiled timeline composables. */
/** Ripple retime: changes [cel] to [n] frames by moving the boundary with the next frame, so the layer keeps its length. */
private fun retimeKeepingLength(cut: Cut, cel: Cel, n: Int) {
    val l = cut.layers.firstOrNull { it.cels.contains(cel) } ?: return
    val i = l.cels.indexOf(cel)
    val next = l.cels.getOrNull(i + 1)
    val want = (n - cel.dur)
    if (next == null) { if (want < 0) cel.dur = maxOf(1, n); return }
    val d = want.coerceIn(1 - cel.dur, next.dur - 1)
    cel.dur += d; next.dur -= d
}

/** Double-touch + short vertical drag across one frame cuts it in two at the touched frame line.
 *  Dragged downward (cut from above): both halves keep the drawing (the right one is a regular copy, not a clone). Dragged upward (from below): the right half is empty. */
private fun timelineCutFrame(ed: Editor, cut: Cut, rows: List<RowItem>, a: Offset, b: Offset, rowPx: Float, cellPx: Float): Boolean {
    val dx = kotlin.math.abs(b.x - a.x); val dy = b.y - a.y
    // Tolerances are in pixels (not fractions of a frame): a thin, zoomed-out frame is only a few pixels wide.
    if (dx > maxOf(cellPx * 0.6f, rowPx * 0.5f) || kotlin.math.abs(dy) < rowPx * 0.35f || kotlin.math.abs(dy) > rowPx * 1.6f) return false
    val row = floor(a.y / rowPx).toInt()
    val item = rows.getOrNull(row) ?: return false
    val l = item.l
    if (item.sub >= 0 || l.isGroup || l.kind != 0 || l.locked) return false
    val boundary = ((a.x + b.x) / 2f / cellPx).roundToInt()   // the frame line nearest to the cut
    val idx = l.indexAt(floor((a.x + b.x) / 2f / cellPx).toInt())
    if (idx < 0) return false
    val cel = l.cels[idx]
    if (cel.dur < 2) return false // a one-frame cell cannot be split (checked first: the range below would be empty and coerceIn would throw)
    val at = (boundary - l.startOf(idx)).coerceIn(1, cel.dur - 1)  // snaps to the nearest line inside the frame
    cut.splitFrame(l, cel, at, fromAbove = dy > 0f)
    ed.clearMulti()
    return true
}

private class TimelineUiState {
    val menuFor = mutableStateOf<Int?>(null)
    val drag = mutableStateOf<DragSt?>(null)
    val lDrag = mutableStateOf<LDrag?>(null)
    val addMenu = mutableStateOf(false)
    val transformDialogFor = mutableStateOf<Layer?>(null)
    val opBefore = mutableStateOf<Snap?>(null)
    val marq = mutableStateOf<Rect?>(null)
    val marqL = mutableStateOf<Rect?>(null)
    val bulkKind = mutableStateOf(0)
    val bulkDx = mutableStateOf(0f)
    val renaming = mutableStateOf<Layer?>(null)
}

private fun timelineLanding(l: Layer, c: Cel, dxPx: Float, cellPx: Float): Int {
    val from = l.cels.indexOf(c)
    if (from < 0 || cellPx <= 0f) return 0
    val targetCenter = l.startOf(from) + c.dur / 2f + dxPx / cellPx
    var cursor = 0f
    var insertion = 0
    for (other in l.cels) {
        if (other !== c && targetCenter > cursor + other.dur / 2f) insertion++
        cursor += other.dur
    }
    return insertion.coerceIn(0, (l.cels.size - 1).coerceAtLeast(0))
}

private fun timelinePickGrid(ed: Editor, rows: List<RowItem>, r: Rect, rowPx: Float, cellPx: Float) {
    if (rows.isEmpty() || rowPx <= 0f || cellPx <= 0f) return
    val r0 = (r.top / rowPx).toInt().coerceIn(0, rows.lastIndex)
    val r1 = (r.bottom / rowPx).toInt().coerceIn(0, rows.lastIndex)
    val f0 = r.left / cellPx
    val f1 = r.right / cellPx
    ed.clearMulti()
    for (i in minOf(r0, r1)..maxOf(r0, r1)) {
        val row = rows[i]
        val layer = row.l
        if (row.sub >= 0) {
            layer.keyChan(row.sub)?.forEach { k ->
                if (k.t + 0.5f in f0..f1) ed.selKeys.add(KeySel(layer.id, row.sub, k.t))
            }
        } else if (layer.kind == 2) {
            layer.cam?.let { cam ->
                (cam.xs + cam.ys + cam.ss + cam.rs).map { it.t }.distinct().forEach { t ->
                    if (t + 0.5f in f0..f1) ed.selKeys.add(KeySel(layer.id, -1, t))
                }
            }
        } else if (!layer.isGroup && layer.kind == 0) {
            var st = 0
            layer.cels.forEach { cel ->
                if (st < f1 && st + cel.dur > f0) ed.selCels.add(cel)
                st += cel.dur
            }
        }
    }
}

private fun timelinePickLayers(ed: Editor, rows: List<RowItem>, r: Rect, rowPx: Float) {
    if (rows.isEmpty() || rowPx <= 0f) return
    val r0 = (r.top / rowPx).toInt().coerceIn(0, rows.lastIndex)
    val r1 = (r.bottom / rowPx).toInt().coerceIn(0, rows.lastIndex)
    ed.clearMulti()
    for (i in minOf(r0, r1)..maxOf(r0, r1)) {
        if (!rows[i].l.isGroup && rows[i].sub < 0) ed.selLayers.add(rows[i].l.id)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Timeline(ed: Editor, cut: Cut, modifier: Modifier = Modifier, mode: Int = 0) {
    val ui = remember { TimelineUiState() }
    val vs = rememberScrollState()
    val hs = rememberScrollState()
    var timelineScale by remember { mutableFloatStateOf(1f) }
    val rowH = 44.dp
    val rulerH = 32.dp
    val dens = LocalDensity.current
    // Whole-pixel cell width. Every tick / cel Box is measured at cell.roundToPx() while the playhead and window
    // markers are placed at cell * frame; with a fractional pixel width the two drift apart by the fraction per frame.
    val requestedCellPx = with(dens) { (38f * timelineScale).dp.toPx() }.roundToInt().coerceAtLeast(1).toFloat()
    // Compose packs width constraints into a finite integer range. Long media clips can
    // otherwise make the timeline wider than that range and crash measurement.
    val maxTimelineWidthPx = 180_000f
    val cellPxUi = minOf(requestedCellPx, maxTimelineWidthPx / (cut.total().toLong() + cut.tail().toLong()).coerceAtLeast(1L)).roundToInt().coerceAtLeast(1).toFloat()
    val cell = with(dens) { cellPxUi.toDp() }
    val rowPxUi = with(dens) { rowH.toPx() }
    val cellPxNow by rememberUpdatedState(cellPxUi)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val its = if (mode == 2) miniItems(cut) else items(cut)
    val rows = its.map { it.l }

    /** Reads the picked images (keeping persistent access) and puts them in a NEW reference layer, or - with [target] - adds them to that layer. */
    fun importReferences(uris: List<Uri>, target: Layer?) {
        if (uris.isEmpty()) return
        scope.launch {
            val cutW = cut.w; val cutH = cut.h
            var denied = 0; var failed = 0
            val prepared = ArrayList<PreparedReferenceImage>()
            for (uri in uris) {
                val persisted = runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                }.getOrDefault(false)
                if (!persisted) { denied++; continue }
                val one = withContext(Dispatchers.IO) { runCatching { prepareReferenceImage(uri.toString(), cutW, cutH) }.getOrNull() }
                if (one != null) { withContext(Dispatchers.IO) { ReferenceBitmapCache.get(one.path) }; prepared.add(one) } else failed++
            }
            if (denied > 0) android.widget.Toast.makeText(ctx, "This image provider does not allow persistent access ($denied)", android.widget.Toast.LENGTH_LONG).show()
            if (failed > 0) android.widget.Toast.makeText(ctx, "Could not import $failed reference image(s)", android.widget.Toast.LENGTH_LONG).show()
            if (prepared.isEmpty()) return@launch
            if (target != null && target.kind == 5 && cut.layers.contains(target)) {
                val made = cut.appendReferenceImages(target, prepared)
                ed.refSel = made.lastOrNull()
            } else {
                val layer = cut.addReferenceImages(prepared)
                ed.refSel = if (prepared.size == 1) layer?.cels?.firstOrNull()?.strokes?.lastOrNull { it.kind == 3 } else null // a single image is ready to place, several wait for a tap on one of them
                layer?.let { it.imageRevision++ }
            }
        }
    }
    val referencePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importReferences(uris, null) }
    val referenceAddPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importReferences(uris, cut.layer?.takeIf { it.kind == 5 }) }
    fun importMedia(uri: Uri?, kind: Int) {
        if (uri == null) return
        scope.launch {
            val label = displayName(ctx, uri)
            val storedUri = withContext(Dispatchers.IO) { copyMediaIntoAppStorage(ctx, uri) }
            if (storedUri == null) {
                android.widget.Toast.makeText(ctx, "Could not copy the selected media into this project", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val duration = withContext(Dispatchers.IO) { readMediaDurationMs(ctx, Uri.parse(storedUri)) }
            if (duration == null) {
                runCatching { Uri.parse(storedUri).path?.let { java.io.File(it).delete() } }
                android.widget.Toast.makeText(ctx, "Could not read media duration", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            cut.addMediaLayer(kind, storedUri, duration, label)
        }
    }
    val audioPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importMedia(uri, 6) }
    val videoPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importMedia(uri, 7) }
    val imgPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            var zipEntries: List<Pair<String, ByteArray>>? = null
            var images: List<ByteArray>? = null
            withContext(Dispatchers.IO) {
                runCatching {
                    val named = uris.map { displayName(ctx, it) to it }.sortedBy { it.first.lowercase() }
                    if (named.size == 1 && named[0].first.endsWith(".zip", true)) {
                        val list = ArrayList<Pair<String, ByteArray>>()
                        ZipInputStream(ctx.contentResolver.openInputStream(named[0].second)!!).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (!e.isDirectory) list.add(e.name to z.readBytes())
                                e = z.nextEntry
                            }
                        }
                        zipEntries = list
                    } else {
                        images = named.map { n -> ctx.contentResolver.openInputStream(n.second)!!.use { it.readBytes() } }
                    }
                }
            }
            zipEntries?.let { z -> cut.record { importZip(cut, z) } }
            images?.let { imgs ->
                cut.layer?.let { layer ->
                    if (layer.kind == 0 || (layer.kind == 4 && imgs.size == 1)) {
                        cut.record { fillLayer(layer, imgs.mapNotNull { imageInk(cut, it) }) }
                    }
                }
            }
        }
    }

    var trackW by remember { mutableIntStateOf(0) }
    var handledPan by remember { mutableIntStateOf(ed.tlPanRequest) }
    var handledFit by remember { mutableIntStateOf(ed.tlFitRequest) }
    LaunchedEffect(ed.tlPanRequest) {
        if (ed.tlPanRequest != handledPan) { handledPan = ed.tlPanRequest; hs.animateScrollTo((ed.tlPanFrame * cellPxNow).roundToInt().coerceAtLeast(0)) }
    }
    LaunchedEffect(ed.tlFitRequest) {
        if (ed.tlFitRequest != handledFit) {
            handledFit = ed.tlFitRequest
            val a = cut.firstTick(); val b = cut.lastTick()
            val span = (b - a + 1).coerceAtLeast(1)
            if (trackW > 0) {
                val basePx = with(dens) { 38.dp.toPx() }
                val cap = maxTimelineWidthPx / (cut.total().toLong() + cut.tail().toLong()).coerceAtLeast(1L)
                val wantPx = (trackW.toFloat() / span).coerceAtLeast(1f)
                val s = (wantPx / basePx).coerceIn(0.008f, 2.5f)
                timelineScale = s
                val newPx = minOf((basePx * s).roundToInt().coerceAtLeast(1).toFloat(), cap.toFloat()).roundToInt().coerceAtLeast(1)
                withFrameNanos { }; withFrameNanos { }
                hs.scrollTo((a * newPx).coerceAtLeast(0))
            }
        }
    }
    // The layer whose curves are open: the selected Camera / Transformation layer if it is expanded, otherwise the first expanded one
    // (a reloaded cut selects another layer but keeps the expanded flag).
    // A vector layer joins them while some of its keyframes have interpolation settings on (its curves are the easing of each span).
    val curveLayer: Layer? = cut.layer?.takeIf { (it.kind == 2 || (it.isGroup && it.transformFolder) || it.hasInterpolation()) && it.expanded }
        ?: cut.layers.firstOrNull { (it.kind == 2 || (it.isGroup && it.transformFolder) || it.hasInterpolation()) && it.expanded }
    val selCam = curveLayer?.takeIf { it.kind == 2 }?.cam
    val selFolder = curveLayer?.takeIf { it.isGroup && it.transformFolder }
    val keysPicked = ed.selKeys.isNotEmpty()
    val buttonSize = if (mode == 1) 28.dp else 36.dp

    val onKeyBulk: (Int, Float) -> Unit = { phase, value ->
        when (phase) {
            0 -> { cut.playing = false; ui.bulkKind.value = 2; ui.bulkDx.value = 0f }
            1 -> ui.bulkDx.value = value
            else -> {
                val n = (value / cellPxNow).roundToInt()
                ui.bulkKind.value = 0
                ui.bulkDx.value = 0f
                if (n != 0) cut.record {
                    val moved = moveKeysSel(cut, ed.selKeys.toList(), n)
                    ed.selKeys.clear()
                    ed.selKeys.addAll(moved)
                }
            }
        }
        Unit
    }
    val onPickGrid: (Rect) -> Unit = { r -> timelinePickGrid(ed, its, r, rowPxUi, cellPxNow) }
    val onPickLayers: (Rect) -> Unit = { r -> timelinePickLayers(ed, its, r, rowPxUi) }
    val onImportImages: () -> Unit = { imgPick.launch(arrayOf("image/*", "application/zip")) }
    val onImportReference: () -> Unit = { referencePick.launch(arrayOf("image/*")) }
    val onAddReference: () -> Unit = { referenceAddPick.launch(arrayOf("image/*")) }
    val onImportAudio: () -> Unit = { audioPick.launch(arrayOf("audio/*")) }
    val onImportVideo: () -> Unit = { videoPick.launch(arrayOf("video/*")) }
    val onOpacityChange: (Layer, Float) -> Unit = { layer, value ->
        if (ui.opBefore.value == null) ui.opBefore.value = cut.snap()
        layer.opacity = value
    }
    val onOpacityFinished: () -> Unit = {
        ui.opBefore.value?.let { cut.pushStruct(it) }
        ui.opBefore.value = null
    }

    if (mode == 1) {
        Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TimelineToolbarContent(ed, cut, mode, buttonSize, keysPicked, onImportImages, onImportReference, onAddReference, onImportAudio, onImportVideo,
                ui.addMenu.value, { ui.addMenu.value = it }, onOpacityChange, onOpacityFinished)
            if (selFolder != null || selCam != null || keysPicked) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(18.dp).background(Color(0x33FFFFFF)))
                TimelineCurveContent(ed, cut, buttonSize, selCam, selFolder, keysPicked)
            }
        }
        return
    }

    Column(
        modifier.background(Color(0xFF26262C)).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        // Keep the frame under the fingers stationary while zooming. Also allow
                        // two-finger translation to pan the timeline instead of only scaling it.
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val oldScale = timelineScale
                        val newScale = (oldScale * zoom).coerceIn(0.008f, 2.5f)
                        val actualZoom = newScale / oldScale
                        var handled = false

                        if (actualZoom.isFinite() && actualZoom > 0f &&
                            kotlin.math.abs(actualZoom - 1f) > 0.002f) {
                            // Scroll by the amount needed to keep the content point beneath
                            // the pinch centroid at the same screen position.
                            val anchorScroll = hs.value + centroid.x
                            val zoomScrollDelta = anchorScroll * (actualZoom - 1f)
                            timelineScale = newScale
                            if (zoomScrollDelta.isFinite()) hs.dispatchRawDelta(zoomScrollDelta)
                            handled = true
                        }

                        if (pan.x.isFinite() && kotlin.math.abs(pan.x) > 0.5f) {
                            hs.dispatchRawDelta(-pan.x)
                            handled = true
                        }

                        if (handled) event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        if (mode == 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimelineToolbarContent(ed, cut, mode, buttonSize, keysPicked, onImportImages, onImportReference, onAddReference, onImportAudio, onImportVideo,
                    ui.addMenu.value, { ui.addMenu.value = it }, onOpacityChange, onOpacityFinished)
            }
        }
        if (mode == 0 && (selFolder != null || selCam != null || keysPicked)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimelineCurveContent(ed, cut, buttonSize, selCam, selFolder, keysPicked)
            }
        }
        if (curveLayer != null) {
            if (curveLayer.vector) VectorCurveEditor(ed, cut, curveLayer, cell, cellPxUi, hs, rulerH, Modifier.weight(1f).fillMaxWidth(),
                { trackW = it }, { curveLayer.expanded = false })
            else KeyCurveEditor(ed, cut, curveLayer, cell, cellPxUi, hs, rulerH, Modifier.weight(1f).fillMaxWidth(),
                { trackW = it }, { curveLayer.expanded = false })
        } else Row(Modifier.weight(1f).fillMaxWidth()) {
            if (mode != 2) {
                TimelineLayerColumn(ed, cut, its, rows, rowH, rulerH, rowPxUi, vs, dens, ui, onPickLayers)
            }
            Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { trackW = it.width }) {
                TimelineRuler(cut, cell, rulerH, hs, cellPxUi)
                TimelineTrackRows(ed, cut, its, cell, rowH, hs, vs, cellPxUi, rowPxUi, ui, onPickGrid, onKeyBulk)
            }
        }
        if (mode == 0) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.LastPage, on = ed.rippleRetime, size = 30.dp,
                tip = if (ed.rippleRetime) "Retiming keeps the layer length (frames next to it give way) · tap to push instead" else "Retiming pushes the next frames and extends the layer · tap to keep the layer length") { ed.rippleRetime = !ed.rippleRetime }
            IconBtn(Icons.Filled.SettingsEthernet, size = 30.dp, tip = "Zoom and pan the timeline to the playback brackets") { ed.tlFitRequest++ }
            if (cut.layers.any { it.kind == 6 }) IconBtn(if (ed.audioScrub) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, on = ed.audioScrub, size = 30.dp,
                tip = "Audio scrub: hear the audio while you move the playhead by hand") { ed.audioScrub = !ed.audioScrub }
        }
    }
    TimelineDialogs(ed, cut, ui.renaming.value, { ui.renaming.value = null },
        ui.transformDialogFor.value, { ui.transformDialogFor.value = null })
}

@Composable
private fun TimelineLayerColumn(
    ed: Editor, cut: Cut, its: List<RowItem>, rows: List<Layer>, rowH: Dp, rulerH: Dp,
    rowPxUi: Float, vs: ScrollState, dens: Density, ui: TimelineUiState, onPickLayers: (Rect) -> Unit
) {
    var lDrag by ui.lDrag
    var marqL by ui.marqL
    Column(Modifier.width(240.dp).fillMaxHeight()) {
        Spacer(Modifier.height(rulerH))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(vs).marqueeSelect({ marqL = it }, onPickLayers, { ed.clearMulti() })) {
                its.forEach { item -> TimelineLayerEntry(ed, cut, item, rowH, ui, vs) }
            }
            marqL?.let { MarqueeBox(it, vs.value) }
            lDrag?.let { state ->
                plan(rows, state.l, rows.indexOf(state.l) + 0.5f + state.dy / rowPxUi)?.let { (anchor, after, _) ->
                    val y = rowH * (if (after) rows.lastIndexOf(anchor) + 1 else rows.indexOf(anchor)) - with(dens) { vs.value.toDp() }
                    Box(Modifier.offset(y = y).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun TimelineLayerEntry(ed: Editor, cut: Cut, item: RowItem, rowH: Dp, ui: TimelineUiState, vs: ScrollState) {
    val layer = item.l
    when {
        item.sub >= 0 -> TimelineChannelLayerRow(ed, cut, layer, item.sub, rowH, ui)
        layer.isGroup -> TimelineFolderLayerRow(ed, cut, layer, rowH, ui)
        else -> TimelineNormalLayerRow(ed, cut, layer, rowH, ui, vs)
    }
}

@Composable
private fun TimelineChannelLayerRow(ed: Editor, cut: Cut, layer: Layer, channel: Int, rowH: Dp, ui: TimelineUiState) {
    var transformDialogFor by ui.transformDialogFor
    val isFolder = layer.isGroup
    Row(Modifier.height(rowH).fillMaxWidth().background(Color(0x14FFFFFF)).padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(listOf("X", "Y", "Scale", "Rotation")[channel], Modifier.weight(1f), fontSize = 12.sp)
        ChanValue(layer, channel, cut, Modifier.then(if (isFolder) Modifier.clickable { cut.layerId = layer.id; transformDialogFor = layer } else Modifier).padding(horizontal = 6.dp, vertical = 6.dp))
        if (isFolder) Text("Key", Modifier.clickable {
            val transform = layer.transformAt(cut.time)
            val value = when (channel) { 0 -> transform.x; 1 -> transform.y; 2 -> transform.scale; else -> transform.rotation }
            cut.record { setKey(layer.transformChan(channel), cut.time, value, ed.interp, ed.ease) }
        }.padding(end = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TimelineFolderLayerRow(ed: Editor, cut: Cut, layer: Layer, rowH: Dp, ui: TimelineUiState) {
    var menuFor by ui.menuFor
    Row(Modifier.height(rowH).fillMaxWidth()
        .background(if (layer.transformFolder && layer.id == cut.layerId) Color(0xFF3A3A58) else Color(0x22FFFFFF))
        .then(if (layer.transformFolder) Modifier.clickable { cut.layerId = layer.id } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (layer.transformFolder) Icons.Filled.OpenWith else Icons.Filled.Folder, null, Modifier.padding(start = 6.dp).size(20.dp))
        Icon(if (layer.collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            null, Modifier.clickable { layer.collapsed = !layer.collapsed }.padding(6.dp).size(28.dp))
        Text(layer.name, Modifier.weight(1f).clickable { if (layer.id == cut.layerId) ui.renaming.value = layer else cut.layerId = layer.id }.padding(horizontal = 6.dp), maxLines = 1, fontSize = 13.sp)
        if (layer.transformFolder) {
            Icon(if (layer.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                null, Modifier.clickable { cut.layerId = layer.id; layer.expanded = !layer.expanded }.padding(2.dp).size(28.dp))
        }
        Box {
            Icon(Icons.Filled.MoreVert, null, Modifier.clickable { menuFor = layer.id }.padding(2.dp).size(28.dp))
            DropdownMenu(expanded = menuFor == layer.id, onDismissRequest = { menuFor = null }) {
                if (layer.transformFolder) DropdownMenuItem({ Text("Select all") }, { cut.layerId = layer.id; layer.expanded = true; ed.clearMulti(); ed.selKeys.addAll(selectAllKeys(layer)); menuFor = null })
                DropdownMenuItem({ Text(if (layer.uniformTiming) "Uniform timing (on)" else "Uniform timing") }, { cut.setUniformTiming(layer, !layer.uniformTiming); menuFor = null })
            }
        }
        Icon(Icons.Filled.Delete, null, Modifier.clickable { cut.removeGroup(layer) }.padding(2.dp).size(28.dp))
    }
}

@Composable
private fun TimelineNormalLayerRow(ed: Editor, cut: Cut, layer: Layer, rowH: Dp, ui: TimelineUiState, vs: ScrollState) {
    var lDrag by ui.lDrag
    var menuFor by ui.menuFor
    val ld = lDrag?.takeIf { it.l === layer || (ed.selLayers.size > 1 && ed.selLayers.contains(it.l.id) && ed.selLayers.contains(layer.id)) }
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    LaunchedEffect(lDrag?.l?.id) {
        while (lDrag?.l?.id == layer.id) {
            val state = lDrag
            if (state != null) {
                val rowIndex = items(cut).indexOfFirst { it.l === state.l }.coerceAtLeast(0)
                val fingerY = rowIndex * rowPx - vs.value + state.pointerY + state.dy
                val edge = 42f
                val viewport = vs.viewportSize.toFloat()
                if (fingerY < edge) vs.scrollBy(-((edge - fingerY) * 0.22f).coerceIn(2f, 22f))
                else if (fingerY > viewport - edge) vs.scrollBy(((fingerY - (viewport - edge)) * 0.22f).coerceIn(2f, 22f))
            }
            kotlinx.coroutines.delay(16)
        }
    }
    Row(Modifier.height(rowH).fillMaxWidth()
        .offset { IntOffset(0, ld?.dy?.roundToInt() ?: 0) }
        .alpha(if (ld != null) 0.6f else 1f)
        .background(if (layer.id == cut.layerId) Color(0xFF3A3A58) else Color(0xFF26262C))
        .then(if (ed.selLayers.contains(layer.id)) Modifier.border(2.dp, Color(0xFF4DD0E1)) else Modifier)
        .clickable { cut.layerId = layer.id }
        .pointerInput(layer) {
            val rowPx = rowH.toPx()
            detectDragGesturesAfterLongPress(
                onDragStart = { cut.layerId = layer.id; lDrag = LDrag(layer, 0f, 0f, vs.value) },
                onDrag = { change, amount -> change.consume(); lDrag = lDrag?.let { LDrag(it.l, it.dy + amount.y, change.position.y, it.startScroll) } },
                onDragEnd = {
                    val state = lDrag; lDrag = null
                    if (state != null) {
                        val currentRows = items(cut).map { it.l }
                        plan(currentRows, state.l, currentRows.indexOf(state.l) + 0.5f + (state.dy + vs.value - state.startScroll) / rowPx)?.let { (anchor, after, group) ->
                            if (ed.selLayers.size > 1 && ed.selLayers.contains(state.l.id)) cut.moveLayers(cut.layers.filter { x -> !x.isGroup && ed.selLayers.contains(x.id) }, anchor, after, group)
                            else cut.moveLayer(state.l, anchor, after, group)
                        }
                    }
                },
                onDragCancel = { lDrag = null }
            )
        }
        .padding(start = if (layer.group >= 0) 14.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when (layer.kind) { 2 -> Icons.Filled.Videocam; 6 -> Icons.Filled.PlayArrow; 7 -> Icons.Filled.Videocam; 3 -> Icons.Filled.GridOn; 4 -> Icons.Filled.ImageIc; 5 -> Icons.Filled.ImageIc; else -> Icons.Filled.BrushIc }, null, Modifier.padding(start = 6.dp).size(20.dp))
        Icon(if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            null, Modifier.clickable { cut.record { layer.visible = !layer.visible } }.padding(6.dp).size(28.dp))
        Text(layer.name, Modifier.weight(1f).clickable(enabled = layer.id == cut.layerId) { ui.renaming.value = layer }, maxLines = 1, fontSize = 13.sp)
        if (layer.kind == 0 || layer.kind == 4 || layer.kind == 5 || layer.kind == 6 || layer.kind == 7) Icon(if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            null, Modifier.clickable { cut.record { layer.locked = !layer.locked } }.padding(6.dp).size(28.dp))
        if (layer.kind == 2 || layer.hasInterpolation()) Icon(if (layer.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            null, Modifier.clickable { cut.layerId = layer.id; layer.expanded = !layer.expanded }.padding(4.dp).size(28.dp)) // vector layers: only while interpolation settings are on for some keyframe
        if (layer.kind == 0 || layer.kind == 2) {
            Box {
                Icon(Icons.Filled.MoreVert, null, Modifier.clickable { menuFor = layer.id }.padding(4.dp).size(28.dp))
                DropdownMenu(expanded = menuFor == layer.id, onDismissRequest = { menuFor = null }) {
                    if (layer.kind == 2) {
                        DropdownMenuItem({ Text("Set resolution") }, { cut.layerId = layer.id; ed.camPanel = true; ed.camEdit = true; menuFor = null })
                        DropdownMenuItem({ Text("Select all") }, { cut.layerId = layer.id; ed.clearMulti(); ed.selKeys.addAll(selectAllKeys(layer)); menuFor = null })
                        DropdownMenuItem({ Text("Rasterize") }, { rasterizeCamera(cut, layer); menuFor = null })
                    } else {
                        DropdownMenuItem({ Text("Select all") }, { cut.layerId = layer.id; cut.selAll = layer.id; menuFor = null })
                        DropdownMenuItem({ Text("Duplicate") }, { cut.layerId = layer.id; cut.duplicateLayer(true); menuFor = null })
                        DropdownMenuItem({ Text("Duplicate but empty") }, { cut.layerId = layer.id; cut.duplicateLayer(false); menuFor = null })
                        DropdownMenuItem({ Text("Duplicate Empty Vector") }, { cut.layerId = layer.id; cut.duplicateEmptyVector(); menuFor = null })
                        if (layer.uniformAttached) DropdownMenuItem({ Text("Detach timing") }, { cut.detachTiming(layer); menuFor = null })
                        else if (cut.layerById(layer.group)?.uniformTiming == true) DropdownMenuItem({ Text("Attach timing") }, { cut.attachTiming(layer); menuFor = null })
                        DropdownMenuItem({ Text("Flatten w/ layer below") }, { cut.layerId = layer.id; flattenDown(cut, layer); menuFor = null })
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimelineRuler(cut: Cut, cell: Dp, rulerH: Dp, hs: ScrollState, cellPxUi: Float) {
    val tickCount = cut.total() + cut.tail()
    // Keep ticks, playhead and playback-window markers inside the same scroll content.
    // Previously only the Row scrolled, leaving the playhead fixed to the viewport.
    Box(Modifier.fillMaxWidth().height(rulerH).horizontalScroll(hs)) {
        Box(Modifier.width(cell * tickCount).height(rulerH)) {
            val dens = LocalDensity.current
            val fps = cut.fps.coerceAtLeast(1)
            val numPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() } }
            val secPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true } }
            // One canvas instead of a box per frame. The density adapts to the zoom: ticks and numbers are only drawn where they have room,
            // numbers never wrap, and the frame numbers thin out (1, 2, 5, 10, 20, 50 ...) as frames get narrower.
            Canvas(Modifier.width(cell * tickCount).height(rulerH).pointerInput(cut, cellPxUi) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Stylus && down.type != PointerType.Mouse) return@awaitEachGesture
                    fun frameAt(x: Float) = floor(x / cellPxUi).toInt().coerceIn(0, cut.maxTick())
                    cut.playing = false
                    cut.time = frameAt(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        cut.time = frameAt(change.position.x)
                    }
                }
            }) {
                val cp = cell.toPx()
                val dp = 1.dp.toPx()
                val h = size.height
                val secPx = fps * cp
                val half = (fps / 2).coerceAtLeast(1)
                // Levels of detail, from finest to coarsest. Each one disappears when its marks would be closer than a few dp, so zooming out
                // thins the ruler gradually: frame ticks, then half-second ticks, then seconds, then every 2 / 5 / 10 ... seconds, up to hours - it never runs out of marks.
                val drawFrameTicks = cp >= 4 * dp
                val drawHalfTicks = half * cp >= 8 * dp
                val secNice = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)
                val secTick = secNice.firstOrNull { it * secPx >= 8 * dp } ?: 3600           // a mark every secTick seconds
                val secLabel = secNice.firstOrNull { it >= secTick && it * secPx >= 40 * dp } ?: 3600
                // Frame numbers are anchored to the seconds: ... 12, 24, 36, 48 ... then only 24, 48, 72 ..., then every 2 s, 5 s, 10 s, and none when zoomed out further.
                val frameStrides = ((1..fps).filter { fps % it == 0 } + listOf(2, 5, 10).map { it * fps }).distinct()
                val frameStride = frameStrides.firstOrNull { it * cp >= 34 * dp }
                numPaint.textSize = 10.sp.toPx(); secPaint.textSize = 9.sp.toPx()
                fun clock(sec: Int) = if (sec < 60) "$sec" else "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
                for (t in 0 until tickCount) {
                    val n = t + 1                                   // frame number shown at this tick (1-based)
                    val isSecond = n % fps == 0
                    val secIdx = n / fps
                    val secondTick = isSecond && secIdx % secTick == 0
                    val halfTick = drawHalfTicks && t % half == 0
                    if (!(secondTick || halfTick || drawFrameTicks)) continue
                    val x = t * cp
                    val len = if (secondTick) h else if (t % half == 0 && drawHalfTicks) h * 0.65f else h * 0.3f
                    drawLine(Color(if (secondTick) 0xAAFFFFFFL else 0x66FFFFFFL), Offset(x, h - len), Offset(x, h), strokeWidth = if (secondTick) 2f else 1f)
                    drawIntoCanvas { c ->
                        if (secondTick && secIdx % secLabel == 0) c.nativeCanvas.drawText(clock(secIdx), x + 3 * dp, 10 * dp, secPaint)
                        if (frameStride != null && n % frameStride == 0) c.nativeCanvas.drawText("$n", x + 2 * dp, h - 3 * dp, numPaint)
                    }
                }
            }
            Box(Modifier.offset { IntOffset((cell * cut.time).roundToPx(), 0) }.width(2.dp).height(rulerH).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
            WindowMarker(cut, true, cell)
            WindowMarker(cut, false, cell)
        }
    }
}

@Composable
private fun ColumnScope.TimelineTrackRows(
    ed: Editor, cut: Cut, its: List<RowItem>, cell: Dp, rowH: Dp, hs: ScrollState, vs: ScrollState,
    cellPxUi: Float, rowPxUi: Float, ui: TimelineUiState, onPickGrid: (Rect) -> Unit, onKeyBulk: (Int, Float) -> Unit
) {
    var marq by ui.marq
    Column(Modifier.weight(1f).fillMaxWidth().horizontalScroll(hs).verticalScroll(vs)) {
        // Match the ruler's exact scroll-content width. Sharing one ScrollState between
        // independently measured rows with different content widths lets Compose clamp
        // hs to whichever child measured last, causing the playhead and ruler to drift.
        Box(Modifier.width(cell * (cut.total() + cut.tail())).marqueeSelect({ marq = it }, onPickGrid, { if (!ed.timelineSelectMode) ed.clearMulti() }, { a, b -> timelineCutFrame(ed, cut, its, a, b, rowPxUi, cellPxUi) }, enabled = ed.timelineSelectMode)) {
            Column {
                its.forEach { item ->
                    TimelineTrackRow(ed, cut, item, cell, rowH, cellPxUi, ui, onKeyBulk)
                }
            }
            Box(Modifier.offset { IntOffset((cell * cut.time).roundToPx(), 0) }.width(2.dp).height(rowH * its.size).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
            marq?.let { MarqueeBox(it) }
        }
    }
}

private val INTERP_COLORS = listOf(Color(0xFF9E9E9E), Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFBA68C8)) // hold, linear, curve, smooth in, smooth out

/** Colored lines linking the diamond keys of one channel row (X / Y / scale / rotation of a Camera or Transformation folder).
 *  The line draws the segment's easing and is colored by interpolation type. Tap it to cycle the type (linear > curve > in > out > hold);
 *  drag it up / down to strengthen / soften the ease. Undoable as one step. */
@Composable
private fun ChannelSegments(cut: Cut, keys: androidx.compose.runtime.snapshots.SnapshotStateList<CamKey>, cell: Dp, rowH: Dp) {
    for (i in 0 until keys.size - 1) {
        val a = keys[i]; val endT = keys[i + 1].t
        key(a.t) { ChannelSegment(cut, keys, a, endT, cell, rowH) }
    }
}

@Composable
private fun ChannelSegment(cut: Cut, keys: androidx.compose.runtime.snapshots.SnapshotStateList<CamKey>, a: CamKey, endT: Int, cell: Dp, rowH: Dp) {
    val inner = cell * (endT - a.t) - 14.dp
    if (inner < 10.dp) return
    val t0 = a.t
    val color = INTERP_COLORS[a.interp.coerceIn(0, INTERP_COLORS.size - 1)]
    fun replace(f: (CamKey) -> CamKey) {
        val i = keys.indexOfFirst { it.t == t0 }
        if (i >= 0) { keys[i] = f(keys[i]); bumpTimelineRevision() }
    }
    androidx.compose.foundation.Canvas(
        Modifier.offset(cell * t0 + cell / 2 + 7.dp, 0.dp).width(inner).height(rowH)
            .pointerInput(t0) {
                detectTapGestures(onTap = {
                    val before = cut.snap()
                    replace { k -> CamKey(k.t, k.v, when (k.interp) { 1 -> 2; 2 -> 3; 3 -> 4; 4 -> 0; else -> 1 }, k.ease) }
                    cut.pushStruct(before)
                })
            }
            .pointerInput(t0) {
                var before: Snap? = null
                var acc = 0f; var ease0 = 0.33f
                detectVerticalDragGestures(
                    onDragStart = {
                        before = cut.snap(); acc = 0f
                        val k = keys.firstOrNull { it.t == t0 }
                        ease0 = k?.ease ?: 0.33f
                        if (k != null && k.interp <= 1) replace { c -> CamKey(c.t, c.v, 2, c.ease) } // dragging a straight / held line turns it into a curve
                    },
                    onDragEnd = { before?.let { cut.pushStruct(it) }; before = null },
                    onDragCancel = { before?.let { cut.pushStruct(it) }; before = null }
                ) { c, dy ->
                    c.consume(); acc -= dy
                    val e = (ease0 + acc / 120.dp.toPx()).coerceIn(0f, 1f)
                    replace { k -> CamKey(k.t, k.v, k.interp, e) }
                }
            }
    ) {
        val h = size.height; val w = size.width
        val top = h / 2f - 8.dp.toPx(); val bot = h / 2f + 8.dp.toPx()
        val path = Path()
        for (s in 0..24) {
            val u = s / 24f
            val x = u * w; val y = bot - (bot - top) * easeFn(a.interp, u, a.ease)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (a.interp == 0) path.lineTo(w, top) // hold: flat, then the value jumps at the next key
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun TimelineTrackRow(
    ed: Editor, cut: Cut, item: RowItem, cell: Dp, rowH: Dp, cellPxUi: Float,
    ui: TimelineUiState, onKeyBulk: (Int, Float) -> Unit
) {
    val layer = item.l
    val bulkKind = ui.bulkKind.value
    val bulkDx = ui.bulkDx.value
    if (item.sub >= 0) {
        Box(Modifier.width(cell * (cut.total() + cut.tail())).height(rowH).background(Color(0x14FFFFFF))) {
            if (layer.isGroup) {
                ChannelSegments(cut, layer.transformChan(item.sub), cell, rowH)
                layer.transformChan(item.sub).forEach { key ->
                    val selected = ed.selKeys.any { it.layerId == layer.id && (it.ch == item.sub || it.ch < 0) && it.t == key.t }
                    KeyMark(cell, key.t, { key.t == cut.time }, selected, if (bulkKind == 2) bulkDx else 0f,
                        { cut.playing = false; cut.layerId = layer.id; cut.time = key.t },
                        { if (!selected) cut.record { layer.transformChan(item.sub).remove(key) } }, onKeyBulk)
                }
            } else {
                val keys = layer.cam!!.chan(item.sub)
                ChannelSegments(cut, keys, cell, rowH)
                keys.forEach { key ->
                    val selected = ed.selKeys.any { it.layerId == layer.id && (it.ch == item.sub || it.ch < 0) && it.t == key.t }
                    KeyMark(cell, key.t, { key.t == cut.time }, selected, if (bulkKind == 2) bulkDx else 0f,
                        { cut.playing = false; cut.layerId = layer.id; cut.time = key.t },
                        { if (!selected) cut.record { keys.remove(key) } }, onKeyBulk)
                }
            }
        }
    } else if (layer.isGroup || layer.kind == 3) {
        Box(Modifier.width(cell * (cut.total() + cut.tail())).height(rowH).background(Color(0x14FFFFFF)))
    } else if (layer.kind == 4) {
        Box(Modifier.width(cell * (cut.total() + cut.tail())).height(rowH).padding(1.dp).clip(RoundedCornerShape(6.dp))
            .background(if (layer.id == cut.layerId) MaterialTheme.colorScheme.primary else Color(0xFF3F3F4A)).clickable { cut.layerId = layer.id },
            contentAlignment = Alignment.CenterStart) {
            Text("Background (lasts the whole cut)", Modifier.padding(start = 10.dp), fontSize = 12.sp, maxLines = 1)
        }
    } else if (layer.kind == 2) {
        val cam = layer.cam!!
        Box(Modifier.width(cell * (cut.total() + cut.tail())).height(rowH).background(Color(0x14FFFFFF))) {
            (cam.xs + cam.ys + cam.ss + cam.rs).map { it.t }.distinct().forEach { t ->
                KeyMark(cell, t, { t == cut.time }, ed.selKeys.any { it.layerId == layer.id && it.t == t }, if (bulkKind == 2) bulkDx else 0f,
                    { cut.playing = false; cut.layerId = layer.id; cut.time = t }, {}, onKeyBulk)
            }
        }
    } else if (layer.kind == 6 || layer.kind == 7) {
        TimelineMediaTrack(cut, layer, cell, rowH)
    } else {
        TimelineDrawingTrack(ed, cut, layer, cell, rowH, cellPxUi, ui)
    }
}

@Composable
private fun TimelineMediaTrack(cut: Cut, layer: Layer, cell: Dp, rowH: Dp) {
    val start = layer.mediaStartFrame
    val duration = layer.mediaFrames(cut.fps)
    val color = if (layer.kind == 6) Color(0xFF386A79) else Color(0xFF66518A)
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    var dragPx by remember(layer.id) { mutableFloatStateOf(0f) }
    Box(Modifier.width(cell * (cut.total() + cut.tail())).height(rowH).background(Color(0x14FFFFFF))) {
        Box(
            Modifier.offset(x = cell * start + with(LocalDensity.current) { dragPx.toDp() }).width(cell * duration).height(rowH).padding(1.dp)
                .clip(RoundedCornerShape(6.dp)).background(color)
                .clickable { cut.playing = false; cut.layerId = layer.id; cut.time = start }
                .then(if (!layer.locked) Modifier.pointerInput(layer, cellPx) {
                    detectDragGestures(
                        onDragStart = { cut.playing = false; cut.layerId = layer.id; dragPx = 0f },
                        onDrag = { change, amount -> change.consume(); dragPx += amount.x },
                        onDragEnd = {
                            val shift = (dragPx / cellPx).roundToInt(); dragPx = 0f
                            if (shift != 0) cut.record { layer.mediaStartFrame = (layer.mediaStartFrame + shift).coerceAtLeast(0) }
                        },
                        onDragCancel = { dragPx = 0f }
                    )
                } else Modifier),
            contentAlignment = Alignment.CenterStart
        ) {
            if (layer.kind == 6) {
                val wave by rememberWave(layer.mediaUri)
                wave?.let { WaveformCanvas(it, Modifier.matchParentSize()) }
            }
            Text(
                "${if (layer.kind == 6) "♫" else "▶"} ${layer.name}${if (layer.kind == 6) AudioPeaks.status[layer.mediaUri]?.let { "  ·  $it" } ?: "" else ""}",
                Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, maxLines = 1
            )
        }
    }
}

@Composable
private fun TimelineDrawingTrack(ed: Editor, cut: Cut, layer: Layer, cell: Dp, rowH: Dp, cellPxUi: Float, ui: TimelineUiState) {
    var drag by ui.drag
    val dens = LocalDensity.current
    val cellPx = with(dens) { cell.toPx() }
    Box(Modifier.height(rowH)) {
        // The blank tail is segmented like the frames (one slot per frame, merged into groups when frames are too narrow to tell apart).
        val used = layer.startOf(layer.cels.size)
        val tailN = (cut.total() + cut.tail() - used).coerceAtLeast(0)
        if (tailN > 0) Box(
            Modifier.offset(cell * used).width(cell * tailN).height(rowH).padding(vertical = 1.dp).drawBehind {
                val cp = cell.toPx()
                val group = intArrayOf(1, 2, 5, 10, 20, 50, 100).firstOrNull { it * cp >= 7f } ?: 100
                val gap = if (group * cp >= 6f) 1.dp.toPx() else 0.5f
                var f = 0
                while (f < tailN) {
                    val n = minOf(group, tailN - f)
                    drawRoundRect(Color(0xFF2A2A33), Offset(f * cp + gap, 0f), androidx.compose.ui.geometry.Size((n * cp - 2 * gap).coerceAtLeast(1f), size.height), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    f += n
                }
            }
        )
        Row {
            layer.cels.forEachIndexed { index, cel ->
                key(cel) {
                    TimelineCelCell(ed, cut, layer, cel, index, cell, rowH, cellPxUi, ui)
                }
            }
        }
        drag?.takeIf { it.l === layer && it.mode == 0 }?.let { current ->
            val gapStart = layer.cels.filter { it !== current.c }.take(timelineLanding(layer, current.c, current.dx, cellPx)).sumOf { it.dur }
            Box(Modifier.offset(cell * gapStart).size(cell * current.c.dur, rowH).padding(1.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.28f)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineCelCell(
    ed: Editor, cut: Cut, layer: Layer, cel: Cel, index: Int, cell: Dp, rowH: Dp,
    cellPxUi: Float, ui: TimelineUiState
) {
    var drag by ui.drag
    var bulkKind by ui.bulkKind
    var bulkDx by ui.bulkDx
    val start = layer.startOf(index)
    val nowAt = { cut.time >= start && cut.time < start + cel.dur }
    val primaryC = MaterialTheme.colorScheme.primary
    val currentDrag = drag?.takeIf { it.c === cel }
    val shape = RoundedCornerShape(6.dp)
    var hoverZone by remember { mutableIntStateOf(0) }
    // A clone (a later frame sharing an earlier frame's drawing) is light green.
    val isClone = (0 until index.coerceAtMost(layer.cels.size)).any { layer.cels[it].strokes === cel.strokes }
    Box(
        Modifier.width(cell * cel.dur).height(rowH).padding(1.dp)
            .offset { IntOffset(if (currentDrag?.mode == 0) currentDrag.dx.roundToInt() else if (bulkKind == 1 && ed.selCels.contains(cel)) bulkDx.roundToInt() else 0, 0) }
            .alpha(if (currentDrag?.mode == 0 || (bulkKind == 1 && ed.selCels.contains(cel))) 0.55f else 1f)
            .clip(shape)
            .drawBehind {
                val now = nowAt()
                drawRect(when {
                    cel.persistent -> Color(0xFFB63B42)
                    isClone && now && layer.id == cut.layerId -> Color(0xFFC8F2C2)
                    isClone && now -> Color(0xFFA8DCA2)
                    isClone -> Color(0xFF8FCB8A)
                    now && layer.id == cut.layerId -> primaryC
                    now -> Color(0xFF5A5A86)
                    cel.strokes.isEmpty() -> Color(0xFF30303A)
                    else -> Color(0xFF3F3F4A)
                })
            }
            .then(if (cut.selAll == layer.id) Modifier.border(2.dp, Color(0xFF6EA8FF), shape) else Modifier)
            .then(if (ed.selCels.contains(cel)) Modifier.border(2.dp, Color(0xFF4DD0E1), shape) else Modifier)
            .clickable {
                cut.playing = false
                if (ed.timelineSelectMode) {
                    if (ed.selCels.contains(cel)) ed.selCels.remove(cel) else ed.selCels.add(cel)
                } else if (!(cut.layerId == layer.id && nowAt())) { cut.layerId = layer.id; cut.time = start; cut.selAll = -1 }
            }
            .pointerInput(cel) {
                val pxPerCell = cell.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        hoverZone = if ((change.type == PointerType.Stylus || change.type == PointerType.Mouse) && event.type != PointerEventType.Exit) {
                            val width = pxPerCell * cel.dur
                            val edge = maxOf(4.dp.toPx(), minOf(width * 0.30f, 18.dp.toPx()))
                            if (change.position.x < edge) 1 else if (change.position.x > width - edge) 2 else 3
                        } else 0
                    }
                }
            }
            .pointerInput(cel, cellPxUi) {
                val pxPerCell = cell.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var held = true
                    withTimeoutOrNull(AppSettings.celHoldMs.toLong()) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                held = false
                                break
                            }
                        }
                    }
                    if (!held) return@awaitEachGesture
                    if (ed.selCels.contains(cel)) {
                        cut.playing = false
                        var total = 0f
                        bulkKind = 1
                        bulkDx = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.positionChange().x
                            change.consume()
                            if (!change.pressed) break
                            total += dx
                            bulkDx = total
                        }
                        val moved = (total / pxPerCell).roundToInt()
                        bulkKind = 0
                        bulkDx = 0f
                        if (moved != 0) cut.record { moveCelsBulk(cut, ed.selCels.toList(), moved) }
                        return@awaitEachGesture
                    }
                    val indexNow = layer.cels.indexOf(cel)
                    val width = pxPerCell * cel.dur
                    val edge = maxOf(4.dp.toPx(), minOf(width * 0.30f, 18.dp.toPx()))
                    val dragMode = if (down.position.x < edge) 1 else if (down.position.x > width - edge) 2 else if (cut.selAll == layer.id) 3 else 0
                    val originalDuration = cel.dur
                    val previousDuration = if (indexNow > 0) layer.cels[indexNow - 1].dur else 1
                    val nextOriginal = layer.cels.getOrNull(indexNow + 1)?.dur ?: 0
                    val before = cut.snap()
                    cut.playing = false
                    cut.layerId = layer.id
                    cut.time = layer.startOf(indexNow)
                    var lead: Cel? = null
                    var leadDuration = 0
                    if (dragMode == 3) {
                        val first = layer.cels[0]
                        lead = if (first.strokes.isEmpty()) first else Cel(0).also { layer.cels.add(0, it) }
                        leadDuration = lead?.dur ?: 0
                    }
                    var total = 0f
                    drag = DragSt(cel, layer, dragMode, 0f)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.positionChange().x
                        change.consume()
                        if (!change.pressed) break
                        total += dx
                        val frameDelta = (total / pxPerCell).roundToInt()
                        val currentIndex = layer.cels.indexOf(cel)
                        when (dragMode) {
                            2 -> if (ed.rippleRetime) {
                                // Ripple: the boundary moves with the next frame; the layer keeps its length.
                                val next = layer.cels.getOrNull(currentIndex + 1)
                                if (next != null) {
                                    val adjusted = frameDelta.coerceIn(1 - originalDuration, nextOriginal - 1)
                                    cel.dur = originalDuration + adjusted; next.dur = nextOriginal - adjusted
                                } else cel.dur = maxOf(1, originalDuration + minOf(frameDelta, 0))
                            } else cel.dur = maxOf(1, originalDuration + frameDelta)
                            1 -> if (currentIndex > 0) {
                                val adjusted = frameDelta.coerceIn(-(previousDuration - 1), originalDuration - 1)
                                layer.cels[currentIndex - 1].dur = previousDuration + adjusted
                                cel.dur = originalDuration - adjusted
                            } else cel.dur = maxOf(1, originalDuration - frameDelta)
                            3 -> lead?.let { it.dur = maxOf(0, leadDuration + frameDelta) }
                        }
                        drag = DragSt(cel, layer, dragMode, total)
                    }
                    if (dragMode == 0) {
                        val destination = timelineLanding(layer, cel, total, pxPerCell)
                        val from = layer.cels.indexOf(cel)
                        if (destination != from) { layer.cels.removeAt(from); layer.cels.add(destination, cel); layer.invalidateTimelineCache() }
                        cut.time = layer.startOf(layer.cels.indexOf(cel))
                    }
                    if (dragMode == 3) lead?.let { if (it.dur == 0) { layer.cels.remove(it); if (layer.cels.isEmpty()) layer.cels.add(Cel(1)) } }
                    drag = null
                    cut.pushStruct(before)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val labelW = cell * cel.dur
        if (labelW >= 22.dp) Text(
            frameLabel(layer, index) + if (cel.dur > 1 && labelW >= 52.dp) " ×${cel.dur}" else "",
            fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            color = if (isClone) Color(0xFF14301A) else Color.Unspecified
        )
        if (cel.strokes.isNotEmpty()) Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(6.dp).clip(CircleShape).background(if (isClone) Color(0xFF14301A) else Color.White))
        if (ed.referencePoses[cel]?.let { it != ReferencePose() } == true) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(6.dp).clip(CircleShape).background(Color(0xFF26C6DA)))
        val zone = if (currentDrag != null) (if (currentDrag.mode == 1) 1 else if (currentDrag.mode == 2) 2 else 3) else hoverZone
        val edgeDp = (cell * cel.dur * 0.30f).coerceAtMost(18.dp).coerceAtLeast(4.dp)
        Box(Modifier.align(Alignment.CenterStart).width(edgeDp).fillMaxHeight().background(if (zone == 1) Color(0x88FFFFFF) else Color(0x14FFFFFF)))
        Box(Modifier.align(Alignment.CenterEnd).width(edgeDp).fillMaxHeight().background(if (zone == 2) Color(0x88FFFFFF) else Color(0x14FFFFFF)))
        if (zone == 3) Box(Modifier.fillMaxSize().border(2.dp, Color.White, shape))
    }
}
