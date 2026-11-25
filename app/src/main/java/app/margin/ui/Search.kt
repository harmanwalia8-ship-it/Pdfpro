package app.margin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.ink.Bounds
import app.margin.pdf.PdfDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One place the search found [query]: its page and box(es) on it, in page points. */
class SearchHit(val page: Int, val boxes: List<Bounds>)

/** Finding text in the open PDF: the matches (found page by page, as you type) and the one you're on. */
@Stable
class SearchState(private val doc: PdfDoc, private val scope: CoroutineScope, private val onJump: (SearchHit) -> Unit) {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    val hits = mutableStateListOf<SearchHit>()
    var current by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** Searches every page for [q]; jumps to the first match on or after page [fromPage]. */
    fun run(q: String, fromPage: Int) {
        job?.cancel()
        hits.clear()
        current = -1
        val needle = q.trim()
        if (needle.isEmpty()) {
            searching = false
            return
        }
        searching = true
        job = scope.launch {
            for (i in 0 until doc.pageCount) {
                val found = doc.search(i, needle)
                found.forEach { hits += SearchHit(i, it) }
                if (current < 0 && i >= fromPage && found.isNotEmpty()) {
                    current = hits.size - found.size
                    onJump(hits[current])
                }
            }
            if (current < 0 && hits.isNotEmpty()) {
                current = 0
                onJump(hits[0])
            }
            searching = false
        }
    }

    fun next() = step(1)
    fun previous() = step(-1)

    private fun step(d: Int) {
        if (hits.isEmpty()) return
        current = ((current + d) % hits.size + hits.size) % hits.size
        onJump(hits[current])
    }

    fun close() {
        job?.cancel()
        open = false
        query = ""
        hits.clear()
        current = -1
        searching = false
    }

    /** "3 of 27", "Searching…", "No matches". */
    val status: String
        get() = when {
            query.isBlank() -> ""
            hits.isEmpty() -> if (searching) "Searching…" else "No matches"
            else -> "${current + 1} of ${hits.size}" + if (searching) "…" else ""
        }
}

/** The top bar while searching: the search box, how many matches, previous / next, close. */
@Composable
fun SearchBar(search: SearchState, currentPage: () -> Int, modifier: Modifier = Modifier) {
    val c = Margin.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(search.query) {
        delay(350) // search once typing pauses
        search.run(search.query, currentPage())
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(MIcons.Back, "Close search", { search.close() })
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = search.query,
                onValueChange = { search.query = it.take(200) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.next() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search in this PDF" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (search.query.isEmpty()) Text("Search in this PDF", color = c.textMuted, fontSize = 16.sp)
                        inner()
                    }
                },
            )
        }
        Text(search.status, color = c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        IconBtn(MIcons.ChevronUp, "Previous match", { search.previous() }, enabled = search.hits.isNotEmpty())
        IconBtn(MIcons.ChevronDown, "Next match", { search.next() }, enabled = search.hits.isNotEmpty())
    }
}
