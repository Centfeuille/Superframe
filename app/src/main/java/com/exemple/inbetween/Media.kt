package com.example.inbetween

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.UUID

/** Copies picked media into app-owned storage so URI grants and provider lifetimes cannot break projects. */
fun copyMediaIntoAppStorage(context: Context, source: Uri): String? = runCatching {
    val directory = File(context.filesDir, "media_assets").apply { mkdirs() }
    val target = File(directory, "${UUID.randomUUID()}.media")
    try {
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw IllegalStateException("Media provider returned no stream")
        Uri.fromFile(target).toString()
    } catch (error: Exception) {
        target.delete()
        throw error
    }
}.getOrNull()

/** Reads duration metadata without decoding the full clip. */
fun readMediaDurationMs(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
