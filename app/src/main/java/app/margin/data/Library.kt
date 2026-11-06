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

/** One PDF the user has opened. The file itself stays where it is; we keep a link to it. */
data class Doc(
    val id: String,
    val uri: String,
    val title: String,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
    val starred: Boolean = false,
    val zoom: Float = 1f,
    val zoomLocked: Boolean = false,
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

    /** Adds (or refreshes) a PDF picked by the user and returns its entry. */
    fun add(uri: Uri): Doc {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
                uri = o.getString("uri"),
                title = o.getString("title"),
                pageCount = o.optInt("pageCount"),
                lastPage = o.optInt("lastPage"),
                lastOpened = o.optLong("lastOpened"),
                starred = o.optBoolean("starred"),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                zoomLocked = o.optBoolean("zoomLocked"),
            )
        }.sortedByDescending { it.lastOpened }
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
                            .put("zoom", d.zoom.toDouble()).put("zoomLocked", d.zoomLocked),
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
    }
}
