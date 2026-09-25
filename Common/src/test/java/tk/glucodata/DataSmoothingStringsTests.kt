package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapse setting's text is translated into a dozen locales by hand. These guard what a
 * translator can silently break: the format argument the summary lines depend on, and the note
 * that tells the user the loop feeds are not thinned.
 */
class DataSmoothingStringsTests {
    private val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }

    private fun stringFiles(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }

    private fun value(file: File, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    @Test
    fun theDefaultLocaleExplainsThatLoopFeedsAreNotThinned() {
        val note = value(File(resDir, "values/strings.xml"), "data_smoothing_collapse_loop_note")
        assertTrue("data_smoothing_collapse_loop_note missing from values/strings.xml", note != null)
        assertTrue(note!!.contains("xDrip") && note.contains("xInfuus"))
    }

    @Test
    fun theIntervalIsFilledInByEveryLocaleThatTranslatesTheSummaryLines() {
        val checked = ArrayList<String>()
        for (file in stringFiles()) {
            val match = value(file, "data_smoothing_collapse_desc_match")
            val capped = value(file, "data_smoothing_collapse_desc_capped")
            match?.let { assertTrue("${file.parentFile.name}: _desc_match lost its %1\$d", it.contains("%1\$d")) }
            capped?.let { assertTrue("${file.parentFile.name}: _desc_capped lost its %1\$d", it.contains("%1\$d")) }
            checked += file.parentFile.name
        }
        assertTrue("expected the default locale and the translations, got $checked", checked.size >= 15)
    }

    @Test
    fun everyLocaleHasTheNoteAndItNamesBothLoopFeeds() {
        for (file in stringFiles()) {
            val note = value(file, "data_smoothing_collapse_loop_note")
            assertTrue("${file.parentFile.name}: data_smoothing_collapse_loop_note missing", note != null)
            assertTrue("${file.parentFile.name}: empty note", note!!.isNotBlank())
            assertEquals("${file.parentFile.name}: names dropped", listOf(true, true), listOf(note.contains("xDrip"), note.contains("xInfuus")))
        }
    }

    @Test
    fun theNoteSaysWhichSettingLeavesTheLoopFeedsUnsmoothed() {
        // The note refers to "Smooth only graph" by name, so it has to be the name this locale shows.
        for (file in stringFiles()) {
            val title = value(file, "data_smoothing_graph_only_title") ?: continue
            val note = value(file, "data_smoothing_collapse_loop_note") ?: continue
            assertTrue("${file.parentFile.name}: note does not mention \"$title\"", note.contains(title))
        }
    }

    @Test
    fun noLocaleStillShowsTheSummaryLinesInEnglish() {
        val english = listOf("data_smoothing_collapse_desc_match", "data_smoothing_collapse_desc_capped", "data_smoothing_collapse_summary_format")
            .associateWith { value(File(resDir, "values/strings.xml"), it) }
        for (file in stringFiles().filter { it.parentFile.name != "values" }) {
            for ((key, en) in english) {
                val translated = value(file, key) ?: continue
                assertTrue("${file.parentFile.name}: $key is still the English text", translated != en)
            }
        }
    }
}
