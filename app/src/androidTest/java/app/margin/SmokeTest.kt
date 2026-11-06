package app.margin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
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
                swipeUp(startY = bottom * 0.7f, endY = bottom * 0.2f, durationMillis = 120)
            }
            settle(1500)
            check(compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isEmpty()) {
                "A quick flick stayed on page 1: scrolling doesn't glide"
            }
            shotWhenPageShowsText("04-reader-scrolled")

            val textBeforeZoom = textPixels(instrumentation.uiAutomation.takeScreenshot())
            compose.onRoot().performTouchInput {
                pinch(
                    start0 = center - Offset(60f, 0f), end0 = center - Offset(360f, 0f),
                    start1 = center + Offset(60f, 0f), end1 = center + Offset(360f, 0f),
                    durationMillis = 500,
                )
            }
            settle(3000) // includes the sharp re-render of the zoomed area
            shotWhenPageShowsText("05-reader-zoomed") // zoomed-in pages must still show their text
            val textZoomed = textPixels(instrumentation.uiAutomation.takeScreenshot())
            check(textZoomed > textBeforeZoom * 1.3f) { "Pinching didn't zoom in ($textBeforeZoom -> $textZoomed text pixels)" }

            compose.onRoot().performTouchInput { doubleClick(center) } // back to fit-width
            settle(1200)

            // ---- S Pen: write, erase, undo. Fingers never write (the flick above left no ink). ----
            val before = blueInk()
            penStroke(zigzag())
            settle(1500) // well after the pen lifts: the ink must stay on the page
            val written = waitForBlueInk { it > before + 250 }
            shot("06-pen-stroke")

            compose.onNodeWithContentDescription("Eraser").performClick()
            settle(600)
            penStroke(scribbleOverZigzag())
            settle(1200)
            val erased = blueInk()
            check(erased < before + (written - before) / 4) { "The eraser didn't remove the ink ($before -> $written -> $erased)" }
            shot("07-erased")

            compose.onNodeWithContentDescription("Undo").performClick()
            waitForBlueInk { it > erased + (written - before) / 2 }

            compose.onNodeWithContentDescription("Pen 1: Fountain").performClick() // pick the pen again
            settle(500)
            compose.onNodeWithContentDescription("Pen 1: Fountain").performClick() // ...and open its options
            settle(900)
            shot("08-pen-options")
            compose.onNodeWithContentDescription("Pen 1: Fountain").performClick()
            settle(500)

            // ---- Zoom lock: one finger does nothing, two fingers scroll ----
            compose.onNodeWithContentDescription("Lock zoom").performClick()
            settle(600)
            val lockedOn = currentPage()
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.3f, durationMillis = 120) }
            settle(1200)
            check(currentPage() == lockedOn) { "With the zoom locked, one finger still scrolled" }
            compose.onRoot().performTouchInput {
                down(0, Offset(centerX - 150f, bottom * 0.7f))
                down(1, Offset(centerX + 150f, bottom * 0.7f))
                repeat(30) {
                    updatePointerBy(0, Offset(0f, -90f))
                    updatePointerBy(1, Offset(0f, -90f))
                    move()
                }
                up(0)
                up(1)
            }
            settle(1500)
            check(currentPage() != lockedOn) { "With the zoom locked, two fingers didn't scroll" }
            shot("09-zoom-locked")
            compose.onNodeWithContentDescription("Unlock zoom").performClick()
            settle(600)

            compose.onNodeWithContentDescription("Show pages").performClick()
            settle(2000)
            shot("10-pages-panel")
            compose.onNodeWithContentDescription("Close pages").performClick()
            settle(800)

            compose.onNodeWithContentDescription("Back to library").performClick()
            waitForText("CONTINUE WHERE YOU LEFT OFF")
            settle(1500)
            shot("11-library-after-reading")
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

    private val screen by lazy { ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds }

    /** A zigzag across the middle of the screen, as dense pen points. */
    private fun zigzag(): List<Offset> {
        val w = screen.width().toFloat()
        val h = screen.height().toFloat()
        val corners = (0..6).map { i -> Offset(w * (0.35f + 0.05f * i), h * (if (i % 2 == 0) 0.40f else 0.50f)) }
        return dense(corners)
    }

    /** Back-and-forth passes covering the zigzag, for the eraser. */
    private fun scribbleOverZigzag(): List<Offset> {
        val w = screen.width().toFloat()
        val h = screen.height().toFloat()
        val corners = (0..10).map { i -> Offset(w * (if (i % 2 == 0) 0.32f else 0.68f), h * (0.37f + 0.016f * i)) }
        return dense(corners)
    }

    private fun dense(corners: List<Offset>): List<Offset> = buildList {
        for (k in 0 until corners.size - 1) {
            val a = corners[k]
            val b = corners[k + 1]
            val n = ((b - a).getDistance() / 6f).toInt().coerceAtLeast(1)
            for (j in 0 until n) add(a + (b - a) * (j.toFloat() / n))
        }
        add(corners.last())
    }

    /** Draws with a simulated S Pen (stylus tool type, real pressure), like a person would. */
    private fun penStroke(points: List<Offset>) {
        val ui = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        var t = down
        fun send(action: Int, p: Offset) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.8f; size = 0.02f })
            val ev = MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            ui.injectInputEvent(ev, true)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, points.first())
        for (p in points.drop(1)) {
            t += 8
            send(MotionEvent.ACTION_MOVE, p)
        }
        t += 8
        send(MotionEvent.ACTION_UP, points.last())
    }

    /** Counts blue (pen-ink) pixels on the page area of the screen. */
    private fun blueInk(): Int {
        compose.waitForIdle()
        val b = instrumentation.uiAutomation.takeScreenshot() ?: return 0
        var n = 0
        for (y in (b.height * 0.15f).toInt() until (b.height * 0.75f).toInt() step 3) {
            for (x in (b.width * 0.2f).toInt() until (b.width * 0.8f).toInt() step 3) {
                val c = b.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val bl = Color.blue(c)
                if (bl > 100 && bl > r + 50 && bl > g + 30) n++
            }
        }
        return n
    }

    private fun waitForBlueInk(timeoutMs: Long = 10_000, ok: (Int) -> Boolean): Int {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last = 0
        while (SystemClock.uptimeMillis() < end) {
            last = blueInk()
            if (ok(last)) return last
            Thread.sleep(400)
        }
        shot("ink-FAILED")
        error("Pen ink didn't show up as expected (last count $last)")
    }

    /** The page shown in the top bar ("Page 3 of 6" -> 3). */
    private fun currentPage(): Int =
        (1..6).firstOrNull { compose.onAllNodesWithText("Page $it of 6").fetchSemanticsNodes().isNotEmpty() } ?: -1

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
