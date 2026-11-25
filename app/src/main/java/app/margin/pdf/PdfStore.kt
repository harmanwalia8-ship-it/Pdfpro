package app.margin.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.margin.data.Doc
import app.margin.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Margin's own copy of every PDF in the library (`files/docs/<id>.pdf`), kept in step with the original:
 *
 * - Opening refreshes the copy from the original when Margin can read it, and otherwise
 *   (the original was deleted, moved, or Margin lost access) simply opens the copy.
 * - Saving writes your ink into the copy and, when it can, into the original too, so other apps show it.
 *   Before the first save the untouched PDF is kept as a backup, so it can be restored.
 * - Deleting a PDF from Margin removes the copy and everything else Margin keeps for it.
 *
 * Files are replaced by renaming a finished file over them, so a reader never sees half a file
 * (MuPDF keeps reading the file it opened, even after it's replaced).
 */
object PdfStore {
    enum class Result {
        /** Saved into Margin's copy and the original (or there is no original). */
        Saved,
        /** Saved into Margin's copy; the original is read-only or gone. */
        SavedCopyOnly,
        /** Not a PDF Margin can write. */
        ReadOnly,
        Failed,
    }

    /** One file operation at a time: a PDF is never read while it's being saved. */
    private val lock = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun copyFile(ctx: Context, docId: String) = File(ctx.filesDir, "docs/$docId.pdf")

    private fun backupFile(ctx: Context, docId: String) = File(ctx.filesDir, "originals/$docId.pdf")

    fun hasBackup(ctx: Context, docId: String): Boolean = backupFile(ctx, docId).exists()

    /** Margin's copy, ready to open; [originalMissing] when the original file couldn't be read. */
    class Opened(val file: File, val originalMissing: Boolean)

    /** Gets Margin's copy of the PDF up to date (from the original, if it can) and returns it. */
    suspend fun prepare(ctx: Context, docId: String): Opened = lock.withLock {
        withContext(Dispatchers.IO) {
            // Read the entry now, not before waiting for the lock: a save that just finished may have changed it.
            val doc = Library.get(ctx).get(docId) ?: throw FileNotFoundException("Not in the library")
            val copy = copyFile(ctx, docId)
            val uri = doc.uri?.let(Uri::parse)
            when {
                uri == null -> {
                    if (!copy.exists()) throw FileNotFoundException("Margin's copy is missing")
                    Opened(copy, originalMissing = false)
                }
                // The original doesn't have Margin's latest changes; reading it again would lose them.
                doc.copyAhead && copy.exists() -> Opened(copy, originalMissing = !originalExists(ctx, uri))
                else -> try {
                    copyFromOriginal(ctx, uri, copy)
                    Opened(copy, originalMissing = false)
                } catch (e: Exception) {
                    if (!copy.exists()) throw e
                    Opened(copy, originalMissing = true)
                }
            }
        }
    }

    /**
     * Writes [ink] (and any page changes) into Margin's copy and, when it can, into the original file.
     * Before the first save, the untouched PDF is kept as a backup.
     */
    suspend fun save(ctx: Context, docId: String, pdf: PdfDoc, ink: Map<Int, List<InkForPdf>>): Result = lock.withLock {
        val copy = copyFile(ctx, docId)
        val part = File(copy.parentFile, "$docId.saving")
        try {
            if (!pdf.saveWithInk(ink, part)) return@withLock Result.ReadOnly
            withContext(Dispatchers.IO) {
                val library = Library.get(ctx)
                val doc = library.get(docId) ?: return@withContext Result.Failed // deleted from Margin meanwhile
                val backup = backupFile(ctx, docId)
                if (!backup.exists() && copy.exists()) {
                    // First time Margin writes into this PDF: keep the untouched one.
                    replace(backup) { out -> copy.inputStream().use { it.copyTo(out) } }
                }
                val uri = doc.uri?.let(Uri::parse)
                val wroteOriginal = uri != null && runCatching { writeTo(ctx, uri, part) }.isSuccess
                if (!part.renameTo(copy)) throw IOException("Can't update Margin's copy")
                library.update(docId) { it.copy(copyAhead = uri != null && !wroteOriginal) }
                if (uri == null || wroteOriginal) Result.Saved else Result.SavedCopyOnly
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed
        } finally {
            part.delete()
        }
    }

    /** Puts the untouched PDF back, in Margin's copy and (if it can) the original, and clears your ink. */
    suspend fun restore(ctx: Context, docId: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val library = Library.get(ctx)
            val doc = library.get(docId) ?: return@withContext false
            val backup = backupFile(ctx, docId)
            if (!backup.exists()) return@withContext false
            runCatching {
                replace(copyFile(ctx, docId)) { out -> backup.inputStream().use { it.copyTo(out) } }
                val uri = doc.uri?.let(Uri::parse)
                val wroteOriginal = uri != null && runCatching { writeTo(ctx, uri, backup) }.isSuccess
                library.update(docId) { it.copy(copyAhead = uri != null && !wroteOriginal) }
                library.inkFile(docId).delete()
                backup.delete()
            }.isSuccess
        }
    }

    /** Writes the PDF (as Margin has it, with your ink) to [to], e.g. a new file in Files. */
    suspend fun export(ctx: Context, docId: String, to: Uri): Boolean = runCatching {
        val file = prepare(ctx, docId).file
        withContext(Dispatchers.IO) { writeTo(ctx, to, file) }
    }.isSuccess

    /**
     * Deletes the PDF from Margin for good: the library entry, Margin's copy, your ink, the backup and
     * the thumbnail. The original file, if there still is one, isn't touched.
     */
    suspend fun delete(ctx: Context, doc: Doc): Unit = lock.withLock {
        val library = Library.get(ctx)
        library.remove(doc.id)
        withContext(Dispatchers.IO) {
            copyFile(ctx, doc.id).delete()
            backupFile(ctx, doc.id).delete()
            library.inkFile(doc.id).delete()
        }
        doc.uri?.let(Uri::parse)?.let { uri ->
            // Margin no longer needs access to the original.
            runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    @Volatile private var keptMissing = false

    /**
     * PDFs added before Margin kept copies only have a link to their original. Copies them in
     * once, in the background, so they too survive the original being deleted.
     */
    fun keepMissingCopies(ctx: Context) {
        if (keptMissing) return
        keptMissing = true
        val app = ctx.applicationContext
        background.launch {
            delay(3_000) // after the app has started
            val library = Library.get(app)
            for (d in library.docs.value) {
                val uri = d.uri?.let(Uri::parse) ?: continue
                val copy = copyFile(app, d.id)
                if (copy.exists()) continue
                val part = File(copy.parentFile, "${d.id}.keeping")
                try {
                    // Copy without the lock (it can be slow); only the final rename waits for it.
                    copy.parentFile?.mkdirs()
                    val input = app.contentResolver.openInputStream(uri) ?: continue
                    input.use { i -> part.outputStream().use { i.copyTo(it) } }
                    lock.withLock { if (!copy.exists() && library.get(d.id) != null) part.renameTo(copy) }
                } catch (e: Exception) {
                    // The original is already gone or unreadable: nothing to keep.
                } finally {
                    part.delete()
                }
            }
        }
    }

    private fun copyFromOriginal(ctx: Context, uri: Uri, copy: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't read the original")
        input.use { i -> replace(copy) { out -> i.copyTo(out) } }
    }

    /** Writes [file] with [write] into a side file first, then puts it in place in one step. */
    private fun replace(file: File, write: (java.io.OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        try {
            part.outputStream().use(write)
            if (!part.renameTo(file)) throw IOException("Can't write ${file.name}")
        } finally {
            part.delete()
        }
    }

    private fun originalExists(ctx: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).exists()
        } else {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
        }
    }.getOrDefault(false)

    private fun writeTo(ctx: Context, uri: Uri, from: File) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write this file")
        out.use { o -> from.inputStream().use { it.copyTo(o) } }
    }
}

/** A safe file name for a PDF called [title] ("Notes: week 3" -> "Notes_ week 3.pdf"). */
fun pdfFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(120).ifBlank { "PDF" } + ".pdf"
