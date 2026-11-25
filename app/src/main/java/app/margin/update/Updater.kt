package app.margin.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.margin.BuildConfig

/**
 * Updates without Margin touching the network: Margin has no internet access, so "Check for updates"
 * opens the GitHub Releases page in the browser. If the newest build there is higher than this one,
 * download its APK and tap it to install. The address is fixed; nothing about you or your PDFs is in it.
 */
object Updater {
    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    const val RELEASES_URL = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"

    fun openReleases(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
