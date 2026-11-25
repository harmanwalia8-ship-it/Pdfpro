package app.margin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Ways of calling MuPDF that are known to break PDFs on Android. */
class MupdfUsageTest {
    @Test
    fun noAddObject() {
        // MuPDF's Java addObject returns the object you passed in, not a reference to the stored one,
        // and gives up its hold on it: pages and contents built with it end up copied inline (a table
        // of contents then points at the wrong pages, or loops and crashes). Use PdfDoc.newPageObject.
        val uses = File("src/main/java").walk().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { ".addObject(" in it }.map { "${f.name}: ${it.trim()}" } }
            .toList()
        assertEquals(emptyList<String>(), uses)
    }
}
