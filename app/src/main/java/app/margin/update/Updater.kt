package app.margin.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import app.margin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub repo's latest release ("build-<number>") and, when it's newer,
 * opens its APK download in the browser. The app never installs anything itself
 * (no "install apps" permission), so Play Protect has nothing to worry about.
 */
object Updater {
    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val build: Int, val url: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    fun check(ctx: Context, force: Boolean = false) {
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < 6 * 60 * 60 * 1000L) return
        if (_status.value is Status.Checking) return
        _status.value = Status.Checking
        scope.launch {
            _status.value = try {
                val latest = fetchLatest()
                prefs.edit { putLong("lastCheck", now) }
                if (latest != null && latest.first > currentBuild) Status.Available(latest.first, latest.second) else Status.UpToDate
            } catch (e: Exception) {
                if (force) Status.Failed("Couldn't reach GitHub. Check your connection.") else Status.Idle
            }
        }
    }

    private fun fetchLatest(): Pair<Int, String>? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode == 404) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.getString("tag_name").substringAfterLast('-').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) return build to a.getString("browser_download_url")
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Opens the new version's APK in the browser; tap the finished download to install it. */
    fun openDownload(ctx: Context) {
        val s = _status.value as? Status.Available ?: return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
