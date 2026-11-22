package com.example.inbetween

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.DataInputStream
import java.io.File
import kotlinx.coroutines.delay

/**
 * Crash recovery. While a cut is open and has unsaved edits, a snapshot is written to filesDir/recovery (same .inb
 * format, media layers without their bytes because the app-owned copy already sits in media_assets). The normal save
 * (onStop, closing a cut) deletes the snapshots. If the app dies before that, the next launch finds snapshots newer
 * than the saved file and offers to restore them. The last [KEEP] snapshots of a cut are kept.
 * The cut is serialized on the main thread (it is only ever mutated there, so a snapshot is always consistent); the disk write runs on a background thread.
 */
object Recovery {
    private const val DIR = "recovery"
    private const val KEEP = 3
    private const val IDLE_MS = 2500L   // snapshot once the user has paused editing this long
    private const val FORCE_MS = 60_000L // ...or this long after the first unsnapshotted edit while still editing

    class Pending(val cutId: Long, val name: String, val savedAt: Long)

    private val snapEdits = java.util.concurrent.ConcurrentHashMap<Long, Long>() // cut id -> Cut.edits at the last snapshot or normal save
    private var lastId = -1L
    private var lastEdits = -1L
    private var stableSince = 0L
    private var dirtySince = 0L

    private fun dir(root: File) = File(root, DIR)
    private fun tsOf(f: File): Long = f.name.removeSuffix(".rec").substringAfter('_').toLongOrNull() ?: 0L
    private fun idOf(f: File): Long? = f.name.substringBefore('_').toLongOrNull()

    private fun snapshotsOf(root: File, cutId: Long): List<File> =
        (dir(root).listFiles() ?: emptyArray()).filter { it.name.endsWith(".rec") && idOf(it) == cutId }.sortedBy { tsOf(it) }

    fun isDirty(c: Cut): Boolean = snapEdits[c.id]?.let { it != c.edits } ?: (c.edits != 0L)

    /** Cut id -> time of the last normal save / clear. A snapshot serialized before it is obsolete and is dropped by the writer. */
    private val invalidated = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** Disk writes (and the fsync) happen here, never on the UI thread: on the tablet they stalled pen input for up to ~1.4 s and a frame for up to ~69 s. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "recovery-io").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }

    /**
     * Snapshots [c] now. The cut is serialized into memory HERE (main thread, so the snapshot is consistent); the file write, fsync,
     * rename and pruning run on [io]. Returns false (and logs) if it could not be serialized; a failed disk write is logged and
     * makes the cut dirty again so the next tick retries.
     */
    fun snapshot(root: File, c: Cut): Boolean {
        return try {
            val stamp = System.currentTimeMillis()
            val id = c.id; val edits = c.edits
            val bytes = java.io.ByteArrayOutputStream(1 shl 16)
            Store.write(c, bytes, embedMedia = false)
            snapEdits[id] = edits
            io.execute { writeSnapshot(root, id, stamp, bytes.toByteArray()) }
            true
        } catch (e: Exception) {
            CrashDiagnostics.log("WARN", "Recovery snapshot of cut ${c.id} failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: OutOfMemoryError) { // a very large cut: skip this snapshot instead of crashing
            CrashDiagnostics.log("WARN", "Recovery snapshot of cut ${c.id} skipped: out of memory")
            false
        }
    }

    private fun writeSnapshot(root: File, id: Long, stamp: Long, data: ByteArray) {
        val d = dir(root)
        try {
            d.mkdirs()
            val tmp = File(d, "${id}_$stamp.tmp")
            tmp.outputStream().use { it.write(data); it.flush(); it.fd.sync() }
            val done = File(d, "${id}_$stamp.rec")
            if (!tmp.renameTo(done)) { tmp.delete(); throw java.io.IOException("rename failed") }
            if ((invalidated[id] ?: 0L) >= stamp) { done.delete(); return } // saved or cleared meanwhile: this snapshot is obsolete
            snapshotsOf(root, id).dropLast(KEEP).forEach { it.delete() }
        } catch (e: Exception) {
            snapEdits.remove(id) // not on disk: the cut counts as unsnapshotted again, so a later tick retries
            CrashDiagnostics.log("WARN", "Recovery snapshot write of cut $id failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Called about once a second with the open cut. Takes a snapshot when the cut has unsaved edits and either the user
     * paused editing for IDLE_MS or the edits have been going on for FORCE_MS. Never while playing.
     */
    fun tick(root: File, c: Cut?, now: Long): Boolean {
        if (c == null || c.playing || !isDirty(c)) { dirtySince = 0L; return false }
        if (c.id != lastId || c.edits != lastEdits) {
            lastId = c.id; lastEdits = c.edits; stableSince = now
            if (dirtySince == 0L) dirtySince = now
        }
        if (now - stableSince < IDLE_MS && now - dirtySince < FORCE_MS) return false
        dirtySince = 0L
        val ok = snapshot(root, c)
        if (!ok) stableSince = now + 15_000L // back off after a failed write instead of retrying every second
        return ok
    }

    /** After a normal save: the saved file is current, so the snapshots are obsolete. They are kept if the save did not finish. */
    fun afterSave(root: File, c: Cut) {
        if (!File(root, "cut_${c.id}.inb").isFile || File(root, "cut_${c.id}.tmp").exists()) return
        snapEdits[c.id] = c.edits
        invalidated[c.id] = System.currentTimeMillis()
        snapshotsOf(root, c.id).forEach { it.delete() }
    }

    fun clear(root: File, cutId: Long) {
        snapEdits.remove(cutId)
        invalidated[cutId] = System.currentTimeMillis()
        snapshotsOf(root, cutId).forEach { it.delete() }
    }

    /** Cuts that have a snapshot newer than their saved file. Call once, right after loading on a fresh start. */
    fun pending(root: File): List<Pending> {
        val files = dir(root).listFiles() ?: return emptyList()
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() } // half-written snapshots
        val out = ArrayList<Pending>()
        for (id in files.filter { it.name.endsWith(".rec") }.mapNotNull { idOf(it) }.distinct()) {
            val newest = snapshotsOf(root, id).lastOrNull() ?: continue
            val saved = File(root, "cut_$id.inb")
            if (saved.isFile && saved.lastModified() >= tsOf(newest)) { clear(root, id); continue } // saved file is newer: stale snapshot
            val name = runCatching { DataInputStream(newest.inputStream().buffered()).use { it.readInt(); it.readLong(); it.readUTF() } }.getOrNull()
            if (name == null) { newest.delete(); continue } // unreadable header
            out.add(Pending(id, name, tsOf(newest)))
        }
        return out.sortedBy { it.savedAt }
    }

    /** Loads the newest readable snapshot of the cut into the editor, replacing the saved version, and saves it as the normal file. */
    fun restore(root: File, ed: Editor, p: Pending, context: Context?): Boolean {
        val snaps = snapshotsOf(root, p.cutId).reversed()
        if (snaps.isEmpty()) return true // already restored or cleared
        for (f in snaps) {
            val cut = runCatching { f.inputStream().use { Store.read(it, mediaRoot = root, context = context) } }.getOrNull() ?: continue
            if (runCatching { Store.save(root, cut) }.isFailure) return false
            cut.refreshThumb()
            val i = ed.cuts.indexOfFirst { it.id == cut.id }
            if (i >= 0) ed.cuts[i] = cut else ed.cuts.add(cut)
            afterSave(root, cut)
            CrashDiagnostics.log("INFO", "Recovered cut ${cut.id} from snapshot ${f.name}")
            return true
        }
        return false
    }
}

/** Drives the periodic snapshots of the open cut. */
@Composable
fun RecoveryAutosave(ed: Editor) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            try { Recovery.tick(ctx.filesDir, ed.open, SystemClock.elapsedRealtime()) }
            catch (e: Exception) { CrashDiagnostics.log("WARN", "Recovery tick failed: ${e.message}") }
        }
    }
}

private fun agoText(t: Long): String {
    val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1L -> "less than a minute before the crash"
        m < 60L -> "$m min ago"
        else -> "${m / 60L} h ago"
    }
}

@Composable
fun RecoveryDialog(ed: Editor, pending: List<Recovery.Pending>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Recover unsaved work?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Inbetween closed unexpectedly. Newer unsaved changes were found for:", fontSize = 13.sp)
                pending.forEach { Text("•  ${it.name}  (${agoText(it.savedAt)})", fontSize = 13.sp) }
                if (failed) Text("Some snapshots could not be restored and were kept. Restore retries them; Discard deletes them.", fontSize = 12.sp, color = Color(0xFFFF8A80))
            }
        },
        confirmButton = {
            TextButton({
                var ok = true
                for (p in pending) if (!Recovery.restore(ctx.filesDir, ed, p, ctx)) ok = false
                if (ok) onDone() else failed = true
            }) { Text("Restore") }
        },
        dismissButton = {
            TextButton({ for (p in pending) Recovery.clear(ctx.filesDir, p.cutId); onDone() }) { Text("Discard") }
        }
    )
}
