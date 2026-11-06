package app.margin.pdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves your ink into the original PDF, and keeps a backup of the untouched original
 * (made just before the first save) so it can be restored.
 */
object PdfSaver {
    enum class Result { Saved, ReadOnly, Failed }

    private val lock = Mutex()

    private fun backupFile(ctx: Context, docId: String) = File(ctx.filesDir, "originals/$docId.pdf")

    fun hasBackup(ctx: Context, docId: String): Boolean = backupFile(ctx, docId).exists()

    suspend fun save(ctx: Context, docId: String, uri: Uri, pdf: PdfDoc, ink: Map<Int, List<InkForPdf>>): Result = lock.withLock {
        val tmp = File(ctx.cacheDir, "save/$docId.pdf")
        try {
            if (!pdf.saveWithInk(ink, tmp)) return@withLock Result.ReadOnly
            withContext(Dispatchers.IO) {
                val backup = backupFile(ctx, docId)
                if (!backup.exists()) {
                    // First time Margin writes into this file: keep the untouched original.
                    backup.parentFile?.mkdirs()
                    val part = File(backup.parentFile, backup.name + ".part")
                    ctx.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("Can't read the original")
                    part.renameTo(backup)
                }
                writeTo(ctx, uri, tmp)
            }
            Result.Saved
        } catch (e: SecurityException) {
            Result.ReadOnly
        } catch (e: Exception) {
            Result.Failed
        } finally {
            tmp.delete()
        }
    }

    /** Puts the untouched original back (your ink in Margin is cleared by the caller). */
    suspend fun restore(ctx: Context, docId: String, uri: Uri): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val backup = backupFile(ctx, docId)
            if (!backup.exists()) return@withContext false
            runCatching {
                writeTo(ctx, uri, backup)
                backup.delete()
            }.isSuccess
        }
    }

    private fun writeTo(ctx: Context, uri: Uri, from: File) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write this file")
        out.use { o -> from.inputStream().use { it.copyTo(o) } }
    }
}
