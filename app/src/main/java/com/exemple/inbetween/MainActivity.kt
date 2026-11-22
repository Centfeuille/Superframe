package com.example.inbetween

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinearScale
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

private val BG = Color(0xFF1E1E22)

/** Every text style bold; colors come from the light-grey content color. */
private fun boldTypography(): Typography {
    fun s(sz: Int) = TextStyle(fontWeight = FontWeight.Bold, fontSize = sz.sp)
    return Typography(
        headlineSmall = s(24), titleLarge = s(22), titleMedium = s(16), titleSmall = s(14),
        bodyLarge = s(16), bodyMedium = s(14), bodySmall = s(12), labelLarge = s(14), labelMedium = s(12), labelSmall = s(11)
    )
}

class MainActivity : ComponentActivity() {
    private val ed: Editor by viewModels()
    private var editorDataLoaded = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.install(applicationContext)
        val previousStartup = CrashDiagnostics.beginStartup()
        CrashDiagnostics.log("INFO", "MainActivity.onCreate entered")
        var startupError: String? = null
        try {
            CrashDiagnostics.log("INFO", "Loading saved editor data")
            ReferenceBitmapCache.resolver = contentResolver
            runCatching { // tile memory while editing: a share of the device RAM (4 GB tablet -> 320 MB) instead of a fixed 128 MB
                val mi = android.app.ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
                TileCache.floorBytes = (mi.totalMem / 12L).coerceIn(128L shl 20, 320L shl 20)
            }
            ed.load(filesDir, applicationContext)
            editorDataLoaded = true
            CrashDiagnostics.log("INFO", "Saved editor data loaded")
        } catch (error: Exception) {
            startupError = "Startup data loading failed: ${error.javaClass.simpleName}: ${error.message ?: "No error message"}"
            CrashDiagnostics.log("FATAL", "$startupError\n${error.stackTraceToString()}")
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(onSurface = LightGrey, onBackground = LightGrey, onSurfaceVariant = LightGrey),
                typography = boldTypography()
            ) {
                Surface(Modifier.fillMaxSize(), color = BG, contentColor = LightGrey) {
                    var recoveryContinued by remember { mutableStateOf(false) }
                    when {
                        startupError != null -> StartupRecoveryScreen(startupError!!)
                        previousStartup != null && !recoveryContinued -> StartupRecoveryScreen(
                            "The previous launch did not reach the main interface. Export the diagnostic report before continuing.",
                            onContinue = { recoveryContinued = true }
                        )
                        else -> {
                            App(ed)
                            RecoveryAutosave(ed)
                            AudioScrubHost(ed)
                            if (ed.recoveryPending.isNotEmpty() && !ed.recoveryResolved) RecoveryDialog(ed, ed.recoveryPending) { ed.recoveryResolved = true }
                            else WhatsNewHost()
                        }
                    }
                }
            }
        }
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    override fun onStop() {
        super.onStop()
        if (editorDataLoaded) {
            runCatching { ed.saveAll(filesDir) }
                .onSuccess { CrashDiagnostics.log("INFO", "Editor data saved during onStop") }
                .onFailure { CrashDiagnostics.log("ERROR", "Saving during onStop failed: ${it.stackTraceToString()}") }
        } else {
            CrashDiagnostics.log("WARN", "Skipped onStop save because editor data did not finish loading")
        }
    }
}

@Composable
fun App(ed: Editor, previousStartup: String? = null) {
    LaunchedEffect(Unit) { CrashDiagnostics.markHealthy() }
    val cut = ed.open
    val ctx = LocalContext.current
    BackHandler(enabled = cut != null) { if (ed.fullscreen) ed.fullscreen = false else ed.close(ctx.filesDir) }
    if (cut == null) MenuScreen(ed, previousStartup) else EditorScreen(ed, cut)
}

@Composable
fun MenuScreen(ed: Editor, previousStartup: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Cut?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(previousStartup != null) }
    val importPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { Store.unpack(it, ctx.filesDir, ctx) } }.getOrNull() }
            if (c == null) note = "Couldn't read that file."
            else { c.refreshThumb(); ed.cuts.add(c); Store.save(ctx.filesDir, c); note = "Imported “${c.name}”." }
        }
    }
    // New cut from an image sequence (several numbered images, or one ZIP laid out like the image-sequence export).
    val seqPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            note = "Importing image sequence…"
            val result = withContext(Dispatchers.IO) { runCatching { cutFromImageSequence(ctx, uris, ed.cuts.size + 1) } }
            val c = result.getOrNull()
            if (c == null) note = "Couldn't build a cut from those files."
            else { c.refreshThumb(); ed.cuts.add(c); Store.save(ctx.filesDir, c); note = "Created “${c.name}” from ${c.total()} frames." }
        }
    }
    val exportPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val c = pending
        if (uri != null && c != null) scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { Store.pack(c, it) }; "Exported “${c.name}”." }
                    .getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(BG).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Inbetween", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton({ diagnosticsOpen = true }) { Text("Diagnostics") }
            TextButton({ importPick.launch(arrayOf("*/*")) }) { Text("Import") }
            TextButton({ seqPick.launch(arrayOf("image/*", "application/zip")) }) { Text("Image sequence") }
            Button({ creating = true }) { Text("+ New cut") }
        }
        note?.let { Text(it, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        if (ed.cuts.isEmpty()) Text("No cuts yet. Tap “+ New cut” to start.", color = Color.Gray)
        CutBrowser(ed, { c -> pending = c; exportPick.launch("${c.name}.inbetween") }, Modifier.weight(1f))
    }
    if (creating) NewCutDialog(ed) { creating = false }
    if (diagnosticsOpen) DiagnosticsDialog(
        onDismiss = { diagnosticsOpen = false },
        startupNotice = previousStartup
    )
}

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit, startupNotice: String? = null) {
    val ctx = LocalContext.current
    val exportReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val result = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(CrashDiagnostics.makeReport()) }
                    ?: error("Android could not open the selected destination")
            }
            Toast.makeText(ctx, if (result.isSuccess) "Diagnostic report exported." else "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            CrashDiagnostics.log(if (result.isSuccess) "INFO" else "ERROR", "Diagnostic report export ${if (result.isSuccess) "completed" else "failed"}")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics & bug reports") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (startupNotice != null) {
                    Text("The previous launch did not reach the startup checkpoint:", color = Color(0xFFFFC46B))
                    Text(startupNotice, fontSize = 12.sp)
                }
                Text("Recent application log", style = MaterialTheme.typography.titleSmall)
                Text(CrashDiagnostics.readLog().takeLast(18000), fontSize = 10.sp, lineHeight = 13.sp, color = Color.LightGray)
                Text("The ZIP report contains this log plus app/device version details. It does not include drawings or project files.", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton({ exportReport.launch("Inbetween-bug-report.zip") }) { Text("Export ZIP") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } }
    )
}

@Composable
private fun StartupRecoveryScreen(message: String, onContinue: (() -> Unit)? = null) {
    var diagnosticsOpen by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(BG).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Inbetween couldn't finish starting", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color(0xFFFFC46B))
        Spacer(Modifier.height(16.dp))
        Text("Your project files have not been deliberately changed. Export a diagnostic report so the startup problem can be investigated.", color = Color.LightGray)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ diagnosticsOpen = true }) { Text("Open diagnostics") }
            if (onContinue != null) TextButton(onContinue) { Text("Continue to Inbetween") }
        }
    }
    if (diagnosticsOpen) DiagnosticsDialog(onDismiss = { diagnosticsOpen = false }, startupNotice = message)
}

@Composable
private fun Field(label: String, v: String, modifier: Modifier = Modifier, num: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        v, { onChange(if (num) it.filter { c -> c.isDigit() }.take(5) else it) }, modifier,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Number else KeyboardType.Text)
    )
}

@Composable
fun NewCutDialog(ed: Editor, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("Cut ${ed.cuts.size + 1}") }
    var fps by remember { mutableStateOf("24") }
    var dpi by remember { mutableStateOf("144") }
    var w by remember { mutableStateOf("1920") }
    var h by remember { mutableStateOf("1080") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("New cut") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", name) { name = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Framerate", fps, Modifier.weight(1f), true) { fps = it }
                    Field("DPI", dpi, Modifier.weight(1f), true) { dpi = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Width", w, Modifier.weight(1f), true) { w = it }
                    Field("Height", h, Modifier.weight(1f), true) { h = it }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("720p" to (1280 to 720), "1080p" to (1920 to 1080), "4K" to (3840 to 2160), "Square" to (1080 to 1080)).forEach { (n, wh) ->
                        TextButton({ w = wh.first.toString(); h = wh.second.toString() }) { Text(n) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val c = Cut(
                    name.ifBlank { "Untitled" },
                    (fps.toIntOrNull() ?: 24).coerceIn(1, 120),
                    (w.toIntOrNull() ?: 1920).coerceIn(64, 8192),
                    (h.toIntOrNull() ?: 1080).coerceIn(64, 8192),
                    (dpi.toIntOrNull() ?: 144).coerceIn(1, 1200)
                )
                ed.cuts.add(c); Store.save(ctx.filesDir, c); ed.open = c; onDone()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } }
    )
}

@Composable
fun EditorScreen(ed: Editor, cut: Cut) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableIntStateOf(0) }      // 0 none, 1 tool options, 2 quick size panel
    var shown by remember { mutableIntStateOf(1) }
    var optsDetail by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    // Hardware keyboard: the editor root holds focus and runs the shortcuts (see Keys.kt); text fields still get their own keys first.
    val keyFocus = remember { FocusRequester() }
    remember { KeyBindings.ensureLoaded(ctx.filesDir) }
    val keyActions = remember(cut) {
        KeyActions(
            save = { Store.save(ctx.filesDir, cut); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() },
            export = { exportOpen = true },
            settings = { settingsOpen = true }
        )
    }
    LaunchedEffect(cut) { ed.resetView() }
    LaunchedEffect(settingsOpen, shortcutsOpen, ed.cutDraft == null, exportOpen) {
        if (!settingsOpen && !shortcutsOpen && ed.cutDraft == null && !exportOpen) runCatching { keyFocus.requestFocus() }
    }
    var fmt by remember { mutableIntStateOf(0) }        // 0 MP4, 1 GIF, 2 image
    var gifScale by remember { mutableFloatStateOf(0.5f) }
    var imgSeq by remember { mutableStateOf(true) }     // image sequence (zip) or current frame
    var imgUnique by remember { mutableStateOf(false) }
    var imgJpg by remember { mutableStateOf(false) }
    var includeAudio by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyDone by remember { mutableStateOf(false) }
    /** kind: 0 mp4, 1 gif, 2 image-sequence zip, 3 png, 4 jpg */
    fun doExport(uri: Uri?, kind: Int) {
        if (uri == null) return
        busy = "Starting…"; busyDone = false
        scope.launch {
            val msg = withContext(Dispatchers.Default) {
                runCatching {
                    when (kind) {
                        1 -> ctx.contentResolver.openOutputStream(uri)!!.use { exportGif(cut, gifScale, it) { i, n -> busy = "Frame ${i + 1} / $n" } }
                        0 -> ctx.contentResolver.openFileDescriptor(uri, "rw")!!.use { exportMp4(cut, it.fileDescriptor, { i, n -> busy = "Frame ${i + 1} / $n" }, ctx.contentResolver, includeAudio) }
                        2 -> ctx.contentResolver.openOutputStream(uri)!!.use { exportImageSequence(cut, imgUnique, imgJpg, it) { i, n -> busy = "Image $i / $n" } }
                        else -> ctx.contentResolver.openOutputStream(uri)!!.use { exportCurrentFrame(cut, kind == 4, it) }
                    }
                }.fold({ "Saved." }, { "Export failed: ${it.message}" })
            }
            busy = msg; busyDone = true
        }
    }
    val gifPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { doExport(it, 1) }
    val mp4Pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { doExport(it, 0) }
    val zipPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { doExport(it, 2) }
    val pngPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { doExport(it, 3) }
    val jpgPick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { doExport(it, 4) }

    LaunchedEffect(ed.tool) { panel = 0 }
    LaunchedEffect(ed.fullscreen) { if (ed.fullscreen) panel = 0 else { ed.miniTl = false; ed.miniLayers = false; ed.radialToolsOpen = false } }
    val lastLayerId = remember(cut) { intArrayOf(cut.layerId) }
    LaunchedEffect(cut.layerId) {
        if (cut.layer?.kind != 2) { ed.camPanel = false; ed.camOpt = 0 }
        ed.vpSel = -1; ed.newFrameArmed = false
        // Reference layers: remember where we came from (a pen tap outside the images goes back there) and drop a stale picked image.
        val before = lastLayerId[0]; lastLayerId[0] = cut.layerId
        val now = cut.layer
        if (now?.kind == 5) {
            if (before != cut.layerId) cut.layerById(before)?.takeIf { it.kind != 5 && !it.isGroup }?.let { ed.refReturnId = it.id }
            val picked = ed.refSel
            if (picked != null && cut.referenceInks(now).none { it === picked }) ed.refSel = null
        } else ed.refSel = null
    }
    // Warm persistent reference images after opening a Cut so the first canvas draw does not decode them on the UI thread.
    LaunchedEffect(cut) {
        val refs = cut.layers.filter { it.kind == 5 }.flatMap { layer ->
            cut.referenceInks(layer).mapNotNull { ink -> ink.data?.toString(Charsets.UTF_8)?.let { layer to it } }
        }
        withContext(Dispatchers.IO) { ReferenceBitmapCache.removeMissing(); refs.forEach { (_, path) -> ReferenceBitmapCache.get(path) } }
        refs.forEach { (layer, _) -> layer.imageRevision++ }
    }
    // Real-time playback clock: media audio/video must not wait for raster-cache preparation.
    // Prefetch stays asynchronous; if a drawing cel is late, the playhead does not slow down.
    LaunchedEffect(cut.playing, cut.fps) {
        CelCache.budget = if (cut.playing) 256L shl 20 else 96L shl 20
        ed.buffering = false
        if (cut.playing) {
            val a = cut.firstTick()
            val b = cut.lastTick().coerceAtLeast(a)
            val span = (b - a + 1).coerceAtLeast(1)
            val fps = cut.fps.coerceAtLeast(1)
            val startFrame = if (cut.time in a..b) cut.time else a
            val requestedFrame = java.util.concurrent.atomic.AtomicInteger(startFrame)
            val prefetchJob = launch(Dispatchers.Default) {
                var requested = Int.MIN_VALUE
                while (isActive && cut.playing) {
                    val now = requestedFrame.get()
                    if (now != requested) {
                        CelCache.prefetch(cut, now, 2)
                        requested = now
                    }
                    delay(12)
                }
            }
            try {
            // Preroll audio / video under the playhead and start them together, then anchor the clock.
            // Drawing prefetch (launched above) runs meanwhile. See MediaSync.
            MediaSync.begin(startFrame, fps)
            val startNanos = System.nanoTime()
            var lastTarget = Int.MIN_VALUE
            while (cut.playing) {
                val elapsedNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L)
                val elapsedFrames = elapsedNanos * fps.toLong() / 1_000_000_000L
                val target = a + ((startFrame - a + elapsedFrames) % span).toInt()
                if (target != lastTarget) {
                    cut.time = target
                    requestedFrame.set(target)
                    ed.buffering = !CelCache.frameReady(cut, target)
                    MediaSync.tick(target, fps)
                    lastTarget = target
                }
                val nextBoundary = (elapsedFrames + 1L) * 1_000_000_000L / fps
                val remaining = nextBoundary - (System.nanoTime() - startNanos)
                delay((remaining / 1_000_000L).coerceIn(1L, 8L))
            }
            } finally {
                MediaSync.stop()
                prefetchJob.cancel()
            }
        }
        ed.buffering = false
    }
    Media3AudioLayerPlayback(cut, ed, ctx)
    Box(Modifier.fillMaxSize().background(BG).statusBarsPadding().navigationBarsPadding().focusRequester(keyFocus).focusable().onKeyEvent { handleKey(ed, cut, it.nativeKeyEvent, keyActions) }) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(BG).zIndex(1f).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.AutoMirrored.Filled.ArrowBack) { ed.fullscreen = false; ed.close(ctx.filesDir) }
                    if (!ed.fullscreen) Text(cut.name, Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { ed.cutDraft = if (ed.cutDraft == null) CutDraft(cut) else null }.padding(horizontal = 6.dp, vertical = 8.dp), maxLines = 1, style = MaterialTheme.typography.titleSmall) // tap: cut settings panel
                    else {
                        // Fullscreen: minimal timeline. Right to left: [fullscreen] | current layer | single-layer timeline | the timeline's buttons, compact.
                        MiniLayerButton(ed, cut)
                        Timeline(ed, cut, Modifier.weight(1f), mode = 1)
                        IconBtn(if (ed.miniTl) Icons.Filled.KeyboardArrowUp else Icons.Filled.LinearScale, on = ed.miniTl, size = 36.dp, tip = "Timeline of the current layer") { ed.miniTl = !ed.miniTl }
                        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(Color(0x55FFFFFF)))
                    }
                    IconBtn(if (ed.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, on = ed.fullscreen) { ed.fullscreen = !ed.fullscreen }
                    IconBtn(Icons.AutoMirrored.Filled.Undo) { cut.undo() }
                    IconBtn(Icons.AutoMirrored.Filled.Redo) { cut.redo() }
                    // Grid is always visible; tap changes its depth, hold opens density options.
                    IconBtn(Icons.Filled.Grid4x4, on = ed.gridFront, tip = if (ed.gridFront) "Grid in front · tap for behind" else "Grid behind · tap for in front", onLong = { ed.viewOpt = if (ed.viewOpt == 1) 0 else 1 }) { ed.gridFront = !ed.gridFront }
                    IconBtn(Icons.Filled.Flip, on = ed.flipH || ed.flipV, onLong = { ed.viewOpt = if (ed.viewOpt == 2) 0 else 2 }) {
                        if (ed.flipH || ed.flipV) ed.setFlip(false, false) else ed.setFlip(true, false)
                    }
                    BleedBtn(ed.drawOutsideFrame, onLong = { ed.viewOpt = if (ed.viewOpt == 3) 0 else 3 }) { ed.drawOutsideFrame = !ed.drawOutsideFrame }
                    IconBtn(Icons.Filled.Speed, on = Perf.hudVisible || Perf.recording, tip = "Performance HUD and recorder") { Perf.toggleHud() }
                    IconBtn(Icons.Filled.Share) { exportOpen = true }
                    IconBtn(Icons.Filled.Settings) { settingsOpen = true }
                }
                DrawingCanvas(
                    ed, cut,
                    Modifier.weight(1f).fillMaxWidth().padding(if (ed.fullscreen) 2.dp else 8.dp)
                        .pointerInput(Unit) { // touching the canvas gives the keyboard shortcuts focus back (e.g. after typing in a number field)
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final); runCatching { keyFocus.requestFocus() } }
                        }
                )
                if (!ed.fullscreen) {
                    // Tap the lip to hide / show the timeline; drag it to resize.
                    Box(
                        Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF2E2E36))
                            .pointerInput(Unit) { detectTapGestures(onTap = { ed.timelineOpen = !ed.timelineOpen }) }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { c, dy -> c.consume(); ed.timelineH = (ed.timelineH - dy / density).coerceIn(96f, 520f) }
                            },
                        contentAlignment = Alignment.Center
                    ) { Box(Modifier.size(48.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray)) }
                    if (ed.timelineOpen) Timeline(ed, cut, Modifier.fillMaxWidth().height(ed.timelineH.dp))
                }
            }
            if (!ed.fullscreen) ToolPanel(ed, cut, panel) { p -> panel = p; if (p != 0) shown = p; if (p == 1) optsDetail = false }
        }
        // Performance profiler: HUD (hideable), persistent recording indicator, frame-metrics lifecycle. See PerformanceProfiler.kt.
        PerformanceEffects()
        PerformanceHud(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 84.dp))
        PerformanceRecordingIndicator(Modifier.align(Alignment.TopStart).padding(top = 52.dp, start = 8.dp))
        // The selection widget bar (invert, fill, add / erase, transform, warp, puppet, apply) lives in DrawingCanvas so it follows the selection: SelectionBar.kt.
        if (cut.layer?.kind == 5 && !cut.playing) {
            val refLayer = cut.layer!!
            val refInks = cut.referenceInks(refLayer)
            val picked = ed.refSel?.takeIf { p -> refInks.any { it === p } }
            if (refInks.isNotEmpty()) Row(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp).zIndex(5f), verticalAlignment = Alignment.CenterVertically) {
                // Flip acts on the picked image, or on all images (around their common centre) when none is picked.
                TextButton({ cut.flipReferences(refLayer, picked?.let { listOf(it) } ?: refInks, true) }) { Text("Flip H") }
                TextButton({ cut.flipReferences(refLayer, picked?.let { listOf(it) } ?: refInks, false) }) { Text("Flip V") }
                IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio") { ed.xfLock = !ed.xfLock }
                MagnetBtn(ed)
                TextButton({ ed.toggleRefPinned(cut, refLayer) }) {
                    Text("Relative position", color = if (refLayer.refPinned) MaterialTheme.colorScheme.primary else Color.White)
                }
                if (picked != null) TextButton({
                    cut.removeReferenceImage(refLayer, picked, ed.refReturnId); ed.refSel = null
                }) { Text("Delete reference", color = Color(0xFFFF8A80)) }
            }
        }
        if (ed.outOfPeg && ed.outOfPegSide != 0 && cut.layer?.kind == 0) {
            val refLayer = cut.layer!!
            val refCel = refLayer.adjacentCel(cut.time, ed.outOfPegSide)
            val pose = ed.referencePose(refCel)
            Row(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp).zIndex(5f), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ ed.setReferencePose(refCel, pose.copy(flipX = !pose.flipX)) }) { Text("Flip H") }
                TextButton({ ed.setReferencePose(refCel, pose.copy(flipY = !pose.flipY)) }) { Text("Flip V") }
                IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio") { ed.xfLock = !ed.xfLock }
                MagnetBtn(ed)
                IconBtn(Icons.Filled.Refresh, tip = "Reset this reference") { ed.resetReferencePose(refCel) }
                IconBtn(Icons.Filled.Check, tip = "Apply reference adjustment") { ed.outOfPegSide = 0 }
            }
        }
        if (ed.buffering && cut.playing) Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color(0xDD25252C)).padding(horizontal = 12.dp, vertical = 8.dp).zIndex(8f), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Preparing drawing…", fontSize = 12.sp)
        }
        if (ed.fullscreen && ed.miniTl) Box(Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 8.dp, end = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).zIndex(2f)) {
            Timeline(ed, cut, Modifier.fillMaxWidth().height((28 + 44 * miniRowCount(cut)).dp), mode = 2)
        }
        // Keep the dismiss surface below the fullscreen radial menu and options panel so their controls remain tappable.
        if (panel != 0) Box(Modifier.fillMaxSize().padding(end = if (ed.fullscreen) 0.dp else TOOL_W).pointerInput(Unit) { detectTapGestures { panel = 0 } })
        TransformBars(ed, cut)
        CameraOverlays(ed, cut)
        // Keep the settings panel away from the right/bottom Cut resize handles.
        ed.cutDraft?.let { dr -> CutSettingsPanel(ed, cut, dr, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 56.dp).heightIn(max = 460.dp)) }
        ViewPopups(ed, cut)
        AnimatedVisibility(
            panel != 0, Modifier.align(if (shown == 1) Alignment.TopEnd else Alignment.CenterEnd).padding(end = if (ed.fullscreen) 8.dp else TOOL_W).zIndex(7f),
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
        ) { if (shown == 1) OptionsPanel(ed, optsDetail) { optsDetail = it } else QuickPanel(ed) }
        if (ed.fullscreen) FullscreenRadialTools(ed) { p -> panel = p; if (p != 0) shown = p; if (p == 1) optsDetail = false }
    }
    if (settingsOpen) AlertDialog(
        onDismissRequest = { settingsOpen = false }, title = { Text("Settings") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { settingsOpen = false; shortcutsOpen = true }.padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Keyboard, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Keyboard shortcuts")
                        Text("View and rebind keys for playback, tools, layers and the view", fontSize = 12.sp, color = Color.Gray)
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.size(24.dp))
                }
            }
        },
        confirmButton = { TextButton({ settingsOpen = false }) { Text("Close") } }
    )
    if (shortcutsOpen) KeyboardShortcutsDialog(ctx.filesDir, onBack = { shortcutsOpen = false; settingsOpen = true }, onClose = { shortcutsOpen = false })
    if (exportOpen) AlertDialog(
        onDismissRequest = { exportOpen = false },
        title = { Text("Export ${cut.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(fmt == 0, { fmt = 0 }, { Text("MP4") })
                    FilterChip(fmt == 1, { fmt = 1 }, { Text("GIF") })
                    FilterChip(fmt == 2, { fmt = 2 }, { Text("Image") })
                }
                when (fmt) {
                    1 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1f to "100%", 0.5f to "50%", 0.25f to "25%").forEach { (s, t) -> FilterChip(gifScale == s, { gifScale = s }, { Text(t) }) }
                        }
                        Text("GIF size relative to the output frame. Smaller exports faster.", fontSize = 12.sp)
                    }
                    2 -> {
                        FilterChip(imgSeq, { imgSeq = true }, { Text("Image sequence") }, Modifier.fillMaxWidth())
                        FilterChip(!imgSeq, { imgSeq = false }, { Text("Current frame") }, Modifier.fillMaxWidth())
                        if (imgSeq) FilterChip(imgUnique, { imgUnique = !imgUnique }, { Text("Unique frames") }, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!imgJpg, { imgJpg = false }, { Text("PNG") })
                            FilterChip(imgJpg, { imgJpg = true }, { Text("JPG") })
                        }
                        Text(
                            if (imgSeq) "Zip with Cut/Group/Layer_001 … Visible layers only, no layer folders. Unique frames: one image per drawing; otherwise every frame of the timeline."
                            else "One image of what the canvas shows now.", fontSize = 12.sp
                        )
                    }
                    else -> {
                        Text("MP4 (H.264), up to 1920 px on the long side. Uses the visible camera if there is one.", fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeAudio, onCheckedChange = { includeAudio = it })
                            Text("Include visible audio layers (separate MP4 tracks)", fontSize = 13.sp)
                        }
                        Text("Video reference layers are never exported.", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                exportOpen = false; cut.playing = false
                when (fmt) {
                    0 -> mp4Pick.launch("${cut.name}.mp4")
                    1 -> gifPick.launch("${cut.name}.gif")
                    else -> if (imgSeq) zipPick.launch("${cut.name}.zip") else if (imgJpg) jpgPick.launch("${cut.name}_${cut.time + 1}.jpg") else pngPick.launch("${cut.name}_${cut.time + 1}.png")
                }
            }) { Text("Choose file…") }
        },
        dismissButton = { TextButton({ exportOpen = false }) { Text("Cancel") } }
    )
    busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {}, title = { Text("Export") }, text = { Text(msg) },
            confirmButton = { if (busyDone) TextButton({ busy = null }) { Text("OK") } }
        )
    }
}

/**
 * Camera / Transformation-folder pop-ups (aspect lock, Reset). They read the playhead, so they live in their own composable:
 * otherwise every playback frame recomposed the whole editor screen (timeline included). Hidden while playing.
 */
@Composable
private fun BoxScope.TransformBars(ed: Editor, cut: Cut) {
    if (cut.playing) return
    val camSel = cut.layer?.takeIf { it.kind == 2 }?.cam
    if (camSel != null) {
        val t = cut.time
        val off = abs(camSel.x(t, cut.w) - cut.w / 2f) > 0.5f || abs(camSel.y(t, cut.h) - cut.h / 2f) > 0.5f || abs(camSel.sc(t) - 1f) > 0.001f || abs(camSel.rot(t)) > 0.05f || camSel.stretchedFrom != null
        Row(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
            IconBtn(if (ed.xfLock) Icons.Filled.Lock else Icons.Filled.LockOpen, ed.xfLock, tip = "Lock aspect ratio of the transform handles") { ed.xfLock = !ed.xfLock }
            MagnetBtn(ed)
            if (off) IconBtn(Icons.Filled.Refresh, tip = "Reset the camera at the playhead") {
                cut.record { resetCamera(cut, camSel, t); camSel.stretchedFrom?.let { (w0, h0) -> camSel.w = w0; camSel.h = h0; camSel.stretchedFrom = null } }
            }
        }
    }
    // Video reference layer: same widget bar as the other transforms (rotation magnet, reset). Scale is always uniform, so there is no aspect lock to offer.
    val vidSel = cut.layer?.takeIf { it.kind == 7 && it.visible && !it.locked }
    if (vidSel != null) {
        val off = abs(vidSel.mediaX) > 0.5f || abs(vidSel.mediaY) > 0.5f || abs(vidSel.mediaScale - 1f) > 0.001f || abs(vidSel.mediaRotation) > 0.05f
        Row(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
            MagnetBtn(ed)
            if (off) IconBtn(Icons.Filled.Refresh, tip = "Reset the video position, scale and rotation") {
                cut.record { vidSel.mediaX = 0f; vidSel.mediaY = 0f; vidSel.mediaScale = 1f; vidSel.mediaRotation = 0f }
            }
        }
    }
    val folSel = cut.layers.firstOrNull { it.isGroup && it.id == cut.layerId && it.transformFolder }
    if (folSel != null) {
        val tr = folSel.transformAt(cut.time)
        Row(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE34343C)).padding(4.dp)) {
            MagnetBtn(ed)
            if (abs(tr.x) > 0.5f || abs(tr.y) > 0.5f || abs(tr.scale - 1f) > 0.001f || abs(tr.rotation) > 0.05f) IconBtn(Icons.Filled.Refresh, tip = "Reset the transformation at the playhead") { cut.record { folSel.setTransformAt(cut.time, 0f, 0f, 1f, 0f, ed.interp, ed.ease) } }
        }
    }
}


/** Fullscreen radial tool selector. Drag from the floating puck out of its circle and release toward a tool. */
@Composable
private fun BoxScope.FullscreenRadialTools(ed: Editor, setPanel: (Int) -> Unit) {
    val tools = listOf(0 to "Brush", 1 to "Eraser", 2 to "Select", 3 to "Fill", 4 to "Shapes", 5 to "Eyedropper")
    val centers = listOf(Offset(180f, 45f), Offset(280f, 95f), Offset(280f, 215f), Offset(180f, 265f), Offset(80f, 215f), Offset(80f, 95f))
    val center = Offset(180f, 155f)
    var armed by remember { mutableStateOf(false) }
    var dragVector by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().zIndex(6f)) {
        if (ed.radialToolsOpen) Box(Modifier.fillMaxSize().clickable { ed.radialToolsOpen = false; setPanel(0) })
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp).size(360.dp, 310.dp)) {
        if (armed) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = Offset(180.dp.toPx(), 155.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.85f), c, c + dragVector, strokeWidth = 3.dp.toPx())
        }
        if (ed.radialToolsOpen) tools.forEachIndexed { i, item ->
            val pos = centers[i]
            Box(Modifier.offset(x = pos.x.dp - 25.dp, y = pos.y.dp - 25.dp).size(50.dp).clip(CircleShape)
                .background(if (ed.tool == item.first) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f) else Color(0xDD303039))
                .border(1.dp, Color(0x99FFFFFF), CircleShape)
                .clickable {
                    if (ed.tool == item.first) setPanel(1) else { ed.tool = item.first; setPanel(0) }
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(listOf("✎", "⌫", "▧", "◉", "／", "⌕")[i], fontSize = 18.sp, color = Color.White)
                    Text(item.second, fontSize = 8.sp, color = Color.White)
                }
            }
        }
        Box(Modifier.offset(x = 151.dp, y = 126.dp).size(58.dp).clip(CircleShape)
            .background(if (armed || ed.radialToolsOpen) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color(0x663B3B45))
            .border(1.dp, Color(0xAAFFFFFF), CircleShape)
            .pointerInput(Unit) {
                // No long press: dragging the line out of the circle (puck radius = 29 dp) makes the tools appear.
                val outside = 29.dp.toPx()
                detectDragGestures(
                    onDragStart = { armed = true; dragVector = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume(); dragVector += amount
                        if (dragVector.getDistance() > outside) ed.radialToolsOpen = true
                    },
                    onDragEnd = {
                        if (ed.radialToolsOpen && dragVector.getDistance() > outside) {
                            val angle = atan2(dragVector.y, dragVector.x)
                            val index = centers.indices.minByOrNull { i ->
                                val p = centers[i] - center
                                var d = atan2(p.y, p.x) - angle
                                while (d > PI.toFloat()) d -= 2f * PI.toFloat()
                                while (d < -PI.toFloat()) d += 2f * PI.toFloat()
                                kotlin.math.abs(d)
                            } ?: 0
                            val tool = tools[index].first
                            if (ed.tool == tool) setPanel(1) else { ed.tool = tool; setPanel(0) }
                        }
                        armed = false; dragVector = Offset.Zero
                    },
                    onDragCancel = { armed = false; dragVector = Offset.Zero }
                )
            }, contentAlignment = Alignment.Center) {
            Text("＋", color = Color.White, fontSize = 26.sp)
        }
        }
    }
}


/**
 * Media3 owns continuous audio decoding. This composable creates / registers the audio players and
 * handles scrub seeks while paused; during playback `MediaSync` (driven by the timeline clock) starts,
 * stops and seeks every audio and video clip.
 */
@Composable
private fun Media3AudioLayerPlayback(cut: Cut, ed: Editor, context: android.content.Context) {
    val audioLayers = cut.layers.filter { it.kind == 6 && it.visible && it.mediaUri.isNotBlank() }
    val key = audioLayers.joinToString("|") {
        "${it.id}:${it.mediaUri}:${it.mediaStartFrame}:${it.mediaDurationMs}"
    }
    val players = remember(key) {
        audioLayers.associate { layer ->
            layer.id to newMediaPlayer(context, layer.mediaUri, 1f)
        }
    }
    DisposableEffect(players) {
        audioLayers.forEach { layer -> players[layer.id]?.let { MediaSync.register(layer, it) } }
        onDispose {
            audioLayers.forEach { layer -> players[layer.id]?.let { MediaSync.unregister(layer.id, it) } }
            players.values.forEach { runCatching { it.release() } }
        }
    }

    LaunchedEffect(key, cut.playing, cut.fps) {
        val fps = cut.fps.coerceAtLeast(1)
        fun positionMs(layer: Layer, frame: Int): Long =
            (frame - layer.mediaStartFrame).coerceAtLeast(0).toLong() * 1000L / fps

        if (!cut.playing) {
            // During scrubbing, seek only in response to an actual playhead change.
            snapshotFlow { cut.time }.collect { frame ->
                audioLayers.forEach { layer ->
                    players[layer.id]?.let { player ->
                        player.pause()
                        player.seekTo(positionMs(layer, frame))
                    }
                }
            }
            return@LaunchedEffect
        }

        // While playing, MediaSync (driven by the timeline clock) owns start / stop / seek of every clip.
    }
}


/** Orders names the way people number files: img2 before img10. */
private fun naturalCompare(a: String, b: String): Int {
    val x = Regex("(\\d+)|(\\D+)").findAll(a.lowercase()).map { it.value }.toList()
    val y = Regex("(\\d+)|(\\D+)").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) (p.toBigInteger().compareTo(q.toBigInteger())).takeIf { it != 0 } ?: p.length.compareTo(q.length) else p.compareTo(q)
        if (c != 0) return c
    }
    return x.size.compareTo(y.size)
}

/** Builds a new Cut (default 24 fps, 144 dpi) from picked images, sized to the first one. A single ZIP is read like the image-sequence export (Cut/Group/Layer_001.png). Runs off the main thread. */
private fun cutFromImageSequence(ctx: android.content.Context, uris: List<android.net.Uri>, number: Int): Cut? {
    val named = uris.map { displayName(ctx, it) to it }.sortedWith { p, q -> naturalCompare(p.first, q.first) }
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }
    val zipMode = named.size == 1 && named[0].first.endsWith(".zip", true)
    val entries = ArrayList<Pair<String, ByteArray>>()
    if (zipMode) {
        java.util.zip.ZipInputStream(ctx.contentResolver.openInputStream(named[0].second)!!).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) entries.add(e.name to z.readBytes()); e = z.nextEntry }
        }
        entries.sortWith { p, q -> naturalCompare(p.first, q.first) }
    } else named.forEach { n -> entries.add(n.first to ctx.contentResolver.openInputStream(n.second)!!.use { it.readBytes() }) }
    val isImage = Regex(".*\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE)
    val images = entries.filter { isImage.matches(it.first) }
    val first = images.firstNotNullOfOrNull { size(it.second) } ?: return null
    val base = (if (zipMode) named[0].first.substringBeforeLast('.') else images.first().first.substringBeforeLast('.')).trimEnd('_', '-', ' ', *"0123456789".toCharArray())
    val cut = Cut(base.ifBlank { "Sequence $number" }, 24, first.first.coerceIn(64, 8192), first.second.coerceIn(64, 8192), 144)
    if (zipMode) {
        importZip(cut, entries)
        // the two empty starter layers are not wanted when the ZIP brought its own
        if (cut.layers.any { !it.isGroup && it.kind == 0 && it.cels.any { c -> c.strokes.isNotEmpty() } }) {
            cut.layers.removeAll { !it.isGroup && it.kind == 0 && it.id <= 1 && it.cels.all { c -> c.strokes.isEmpty() } }
        }
    } else {
        cut.layers.removeAll { it.id == 1 } // one layer "A" carries the sequence, one frame per image
        fillLayer(cut.layers[0], images.mapNotNull { imageInk(cut, it.second) })
    }
    if (cut.layers.none { !it.isGroup && it.kind == 0 }) return null
    if (cut.total() <= 1 && cut.layers.all { l -> l.cels.all { it.strokes.isEmpty() } }) return null
    cut.layerId = cut.layers.first { !it.isGroup && it.kind == 0 }.id
    cut.relabelLayers(); cut.invalidateStructureCaches(); cut.resetWindow()
    return cut
}
