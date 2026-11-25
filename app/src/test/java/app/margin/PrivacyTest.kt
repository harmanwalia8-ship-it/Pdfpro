package app.margin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Margin itself must never send your PDFs, notes or ink anywhere: they only leave when you Share
 * them or Save a copy, or through Android's own (encrypted) backup and new-tablet transfer.
 * These checks fail the build if a change ever gives Margin a way to send them anywhere.
 * (CI also checks the finished APK has no internet permission, and the emulator test checks
 * that saving never changes the original file.)
 */
class PrivacyTest {
    private val main = File("src/main")
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val sources = main.resolve("java").walk().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun noInternetAccess() {
        assertTrue(
            "INTERNET must stay removed (also from libraries)",
            Regex("""<uses-permission\s+android:name="android.permission.INTERNET"\s+tools:node="remove"\s*/>""").containsMatchIn(manifest),
        )
        val asked = Regex("""<uses-permission[^>]*>""").findAll(manifest).map { it.value }.filterNot { "tools:node=\"remove\"" in it }.toList()
        assertEquals("Margin asks for no permissions", emptyList<String>(), asked)
    }

    @Test
    fun androidBackupAndNewTabletTransferStayOn() {
        // Your own encrypted Android backup and the move to a new tablet are wanted. Google's backup
        // skips an app entirely above 25 MB, so only the untouched-original safety copies are left out.
        assertTrue("allowBackup must stay true", "android:allowBackup=\"true\"" in manifest)
        assertTrue("Android 10-11 rules", "android:fullBackupContent=\"@xml/backup_rules\"" in manifest)
        assertTrue("Android 12+ rules", "android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
        val onlyOriginals = Regex("""<exclude domain="file" path="originals/" />""")
        val old = File(main, "res/xml/backup_rules.xml").readText()
        assertEquals("Android 10-11 leaves out only originals/", 1, Regex("<exclude").findAll(old).count())
        assertTrue(onlyOriginals.containsMatchIn(old))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        assertEquals("Google backup leaves out only originals/", 1, Regex("<exclude").findAll(cloud).count())
        assertTrue(onlyOriginals.containsMatchIn(cloud))
        assertTrue("nothing limits the transfer to a new tablet", "<device-transfer" !in rules)
        assertTrue("no include lists (they'd leave the rest out)", "<include" !in old + rules)
    }

    @Test
    fun noNetworkCode() {
        val banned = listOf("java.net.", "javax.net.", "HttpURLConnection", "openConnection", "okhttp", "WebView", "DatagramSocket")
        for (file in sources) {
            val text = file.readText()
            for (word in banned) assertTrue("${file.name} uses $word", word !in text)
        }
    }

    @Test
    fun onlyWritesOutsideTheAppWhenYouSaveACopy() {
        // The single place Margin writes to a file outside its own storage is "Save a copy to Files",
        // to a new file you pick. Saving ink, restoring and deleting never touch your original files.
        val writers = sources.flatMap { f -> f.readLines().filter { "openOutputStream(" in it || "openFileDescriptor(" in it }.map { f.name } }
        assertEquals(listOf("PdfStore.kt"), writers)
        val store = File(main, "java/app/margin/pdf/PdfStore.kt").readText()
        val exportFn = store.substringAfter("suspend fun export(").substringBefore("\n    }")
        assertTrue("the write is in export()", "openOutputStream(" in exportFn)
        val asksToWrite = sources.flatMap { f ->
            f.readLines().filter { "takePersistableUriPermission" in it && "WRITE" in it }.map { f.name }
        }
        assertEquals("Margin only asks to read your files", emptyList<String>(), asksToWrite)
    }
}
