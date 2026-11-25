package app.margin.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * One PDF in the library. Margin reads and writes its own copy of the file (see PdfStore),
 * so the PDF stays in Margin even if the original is deleted.
 */
data class Doc(
    val id: String,
    /** The original file, or null for a PDF Margin made itself (split off another PDF). */
    val uri: String?,
    val title: String,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    /** 0 if it has never been opened (a PDF Margin just made). */
    val lastOpened: Long = System.currentTimeMillis(),
    val starred: Boolean = false,
    val zoom: Float = 1f,
    val zoomLocked: Boolean = false,
    /** When Margin made this PDF (0 for PDFs you opened). */
    val added: Long = 0,
    /**
     * You've changed this PDF in Margin (ink, pages), which only goes into Margin's copy, so Margin
     * opens its copy instead of reading the original again.
     */
    val copyAhead: Boolean = false,
    /**
     * Margin has saved ink or page changes into its copy since it was untouched. The untouched-original
     * backup is only ever taken before that (it isn't in Google backups, so after setting up a new tablet
     * there may be none, and a later save mustn't pass an inked copy off as the original).
     */
    val edited: Boolean = false,
    /** The library folder it's in, or null for none. */
    val folder: String? = null,
    /** Pages you bookmarked (0-based, sorted). */
    val bookmarks: List<Int> = emptyList(),
    /** For notebooks made in Margin: their paper (a PageTemplate name), used for pages added as you write. */
    val paper: String? = null,
)

/**
 * The list of recent PDFs, saved as a small JSON file in app storage.
 * Simple on purpose: a personal library is at most a few hundred entries.
 */
class Library private constructor(private val ctx: Context) {
    private val file = File(ctx.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _docs = MutableStateFlow(load())
    val docs: StateFlow<List<Doc>> = _docs.asStateFlow()

    fun get(id: String): Doc? = _docs.value.firstOrNull { it.id == id }

    fun thumbFile(id: String): File = File(ctx.filesDir, "thumbs/$id.png").also { it.parentFile?.mkdirs() }

    /** Your ink for this PDF, as Margin keeps it (it also goes into the PDF when saved). */
    fun inkFile(id: String): File = File(ctx.filesDir, "ink/$id.json")

    /** Images you put on this PDF's pages. */
    fun imagesDir(id: String): File = File(ctx.filesDir, "images/$id")

    /** All folder names in use, sorted. */
    fun folders(): List<String> = _docs.value.mapNotNull { it.folder }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Renames folder [from] to [to] on every PDF in it ([to] = null takes them out of the folder). */
    fun renameFolder(from: String, to: String?) {
        _docs.update { list -> list.map { if (it.folder == from) it.copy(folder = to) else it } }
        persist()
    }

    /** Adds (or refreshes) a PDF picked by the user and returns its entry. */
    fun add(uri: Uri): Doc {
        // Read access only: Margin never writes to your original files.
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val id = idFor(uri)
        val existing = get(id)
        val doc = existing?.copy(lastOpened = System.currentTimeMillis())
            ?: Doc(id = id, uri = uri.toString(), title = displayName(uri))
        _docs.update { list -> listOf(doc) + list.filterNot { it.id == id } }
        persist()
        return doc
    }

    fun update(id: String, change: (Doc) -> Doc) {
        _docs.update { list -> list.map { if (it.id == id) change(it) else it } }
        persist()
    }

    fun markOpened(id: String) = update(id) { it.copy(lastOpened = System.currentTimeMillis()) }

    /** Puts PDFs Margin just made (their files already in place) at the top of the library. */
    fun addMade(made: List<Doc>) {
        if (made.isEmpty()) return
        val ids = made.mapTo(HashSet()) { it.id }
        _docs.update { list -> made + list.filterNot { it.id in ids } }
        persist()
    }

    /** Takes the entry out of the list. Deleting the PDF for good is PdfStore.delete. */
    fun remove(id: String) {
        _docs.update { list -> list.filterNot { it.id == id } }
        thumbFile(id).delete()
        persist()
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
        return name.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    private fun load(): List<Doc> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Doc(
                id = o.getString("id"),
                uri = if (o.isNull("uri")) null else o.getString("uri"),
                title = o.getString("title"),
                pageCount = o.optInt("pageCount"),
                lastPage = o.optInt("lastPage"),
                lastOpened = o.optLong("lastOpened"),
                starred = o.optBoolean("starred"),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                zoomLocked = o.optBoolean("zoomLocked"),
                added = o.optLong("added"),
                copyAhead = o.optBoolean("copyAhead"),
                edited = o.optBoolean("edited"),
                folder = if (o.isNull("folder")) null else o.getString("folder"),
                bookmarks = o.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
                paper = if (o.isNull("paper")) null else o.getString("paper"),
            )
        }.sortedByDescending { maxOf(it.lastOpened, it.added) }
    }.getOrDefault(emptyList())

    private fun persist() {
        val snapshot = _docs.value
        scope.launch {
            writeLock.withLock {
                val arr = JSONArray()
                snapshot.forEach { d ->
                    arr.put(
                        JSONObject()
                            .put("id", d.id).put("uri", d.uri).put("title", d.title)
                            .put("pageCount", d.pageCount).put("lastPage", d.lastPage)
                            .put("lastOpened", d.lastOpened).put("starred", d.starred)
                            .put("zoom", d.zoom.toDouble()).put("zoomLocked", d.zoomLocked)
                            .put("added", d.added).put("copyAhead", d.copyAhead).put("edited", d.edited)
                            .put("folder", d.folder).put("paper", d.paper)
                            .put("bookmarks", JSONArray(d.bookmarks)),
                    )
                }
                val tmp = File(file.parentFile, "library.json.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        @Volatile private var instance: Library? = null

        fun get(ctx: Context): Library =
            instance ?: synchronized(this) { instance ?: Library(ctx.applicationContext).also { instance = it } }

        fun idFor(uri: Uri): String =
            MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)

        /** An id for a PDF Margin makes itself (it has no original file to name it after). */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
