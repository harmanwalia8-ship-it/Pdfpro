package app.margin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.margin.data.Library
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the app on a real (emulated) tablet: empty library, opening a PDF,
 * scrolling, pinch-zoom, the pages panel and back. Saves a screenshot at each step
 * (CI uploads them) and fails if a step doesn't show what it should.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "screenshots").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun libraryReaderAndGestures() {
        val pdf = samplePdf(File(ctx.filesDir, "Sample Notes.pdf"), pages = 6)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Open your first PDF").assertExists()
            shot("01-library-empty")
        }

        Library.get(ctx).add(Uri.fromFile(pdf))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("CONTINUE WHERE YOU LEFT OFF").assertExists()
            shot("02-library-with-pdf")

            compose.onNodeWithText("Continue").performClick()
            waitForText("Page 1 of 6")
            // The library fades out; make sure it's gone so its text can't pass for page text.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("CONTINUE WHERE YOU LEFT OFF").fetchSemanticsNodes().isEmpty()
            }
            shotWhenPageShowsText("03-reader-open")

            // A quick flick moves the finger half a screen; only a glide after lifting it
            // can carry the view off page 1.
            compose.onRoot().performTouchInput {
                swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f, durationMillis = 120)
            }
            settle(1500)
            check(compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isEmpty()) {
                "A quick flick stayed on page 1: scrolling doesn't glide"
            }
            shotWhenPageShowsText("04-reader-scrolled")

            compose.onRoot().performTouchInput {
                pinch(
                    start0 = center - Offset(60f, 0f), end0 = center - Offset(360f, 0f),
                    start1 = center + Offset(60f, 0f), end1 = center + Offset(360f, 0f),
                    durationMillis = 500,
                )
            }
            settle(3000) // includes the sharp re-render of the zoomed area
            shotWhenPageShowsText("05-reader-zoomed") // zoomed-in pages must still show their text

            compose.onRoot().performTouchInput { doubleClick(center) }
            settle(1200)
            compose.onNodeWithContentDescription("Show pages").performClick()
            settle(2000)
            shot("06-pages-panel")
            compose.onNodeWithContentDescription("Close pages").performClick()
            settle(800)

            compose.onNodeWithContentDescription("Back to library").performClick()
            waitForText("CONTINUE WHERE YOU LEFT OFF")
            settle(1500)
            shot("07-library-after-reading")
        }
    }

    /**
     * Waits until the middle of the screen shows page text (grey/black pixels on the white page)
     * on two screenshots in a row, then saves that screenshot. A page that stays blank fails the test.
     */
    private fun shotWhenPageShowsText(name: String, timeoutMs: Long = 20_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last = 0
        var hits = 0
        while (SystemClock.uptimeMillis() < end) {
            compose.waitForIdle()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                last = textPixels(bitmap)
                hits = if (last >= 1500) hits + 1 else 0
                if (hits >= 2) {
                    save(bitmap, name)
                    return
                }
            }
            Thread.sleep(400)
        }
        shot("$name-FAILED")
        error("$name: the page never showed its text (only $last text pixels on screen)")
    }

    /** Counts darkish pixels in the middle of the screen, away from bars and the page pill. */
    private fun textPixels(b: Bitmap): Int {
        var n = 0
        for (y in (b.height * 0.15f).toInt() until (b.height * 0.8f).toInt() step 3) {
            for (x in (b.width * 0.2f).toInt() until (b.width * 0.8f).toInt() step 3) {
                val c = b.getPixel(x, y)
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum < 130) n++
            }
        }
        return n
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Page rendering runs on the MuPDF thread, which Compose's test idling doesn't track. */
    private fun settle(ms: Long) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { save(it, name) }
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A plain 6-page A4 PDF made with Android's own PDF writer. */
    private fun samplePdf(file: File, pages: Int): File {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 26f; isFakeBoldText = true; isAntiAlias = true; color = Color.BLACK }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true; color = Color.DKGRAY }
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(43, 89, 195) }
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val c = page.canvas
            c.drawText("Chapter ${i + 1}", 56f, 92f, title)
            var y = 132f
            repeat(38) { line ->
                c.drawText("Line ${line + 1}. The quick brown fox jumps over the lazy dog, page ${i + 1}.", 56f, y, body)
                y += 16f
            }
            c.drawRect(56f, y + 10f, 539f, y + 120f, box)
            c.drawText("Figure ${i + 1}", 70f, y + 40f, body)
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
