package app.margin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.margin.data.Library
import app.margin.ui.EaseOutSmooth
import app.margin.ui.LibraryScreen
import app.margin.ui.MarginTheme
import app.margin.ui.ReaderScreen
import app.margin.update.Updater

class MainActivity : ComponentActivity() {
    /** A PDF handed to us by another app ("Open with Margin"), waiting to be shown. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            MarginTheme {
                App(incoming = incoming, onIncomingShown = { incoming = null })
            }
        }
        Updater.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { incoming = Library.get(this).add(it).id }
        }
    }
}

@Composable
private fun App(incoming: String?, onIncomingShown: () -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            openId = incoming
            onIncomingShown()
        }
    }

    // Registered first, so the reader's own back handling (e.g. closing the pages panel) wins.
    BackHandler(enabled = openId != null) { openId = null }

    AnimatedContent(
        targetState = openId,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(280, easing = EaseOutSmooth), initialScale = 0.98f)) togetherWith
                fadeOut(tween(160))
        },
        label = "screen",
    ) { id ->
        if (id == null) {
            LibraryScreen(onOpen = { openId = it })
        } else {
            ReaderScreen(docId = id, onBack = { openId = null })
        }
    }
}
