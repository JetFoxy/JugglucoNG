package tk.glucodata.arch

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scripts/settings_inventory.py` is the T2.1 completeness gate: the committed
 * `docs/architecture/settings-inventory.md` must match what the code actually
 * declares, so a new SharedPreferences file or Room database cannot slip in
 * undocumented. These tests cover both the real tree and the trickiest part of
 * the scan — resolving a per-file `PREFS` constant, which a global name->value
 * map gets wrong.
 */
class SettingsInventoryCompletenessTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "scripts/settings_inventory.py").isFile }
        ?: error("repository root with scripts/settings_inventory.py not found")

    private val script = File(repoRoot, "scripts/settings_inventory.py")
    private lateinit var fixture: File

    @After
    fun cleanUp() {
        if (::fixture.isInitialized && fixture.exists()) fixture.deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val command = listOf("python3", script.absolutePath) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun fixtureFile(relative: String, content: String) {
        val target = File(fixture, relative)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    @Test
    fun theCommittedInventoryMatchesTheCode() {
        val (exit, output) = run("--check", "--root", repoRoot.absolutePath)
        assertEquals("settings inventory is stale:\n$output", 0, exit)
    }

    @Test
    fun resolvesAPrefsConstantInTheFileThatDeclaresIt() {
        fixture = Files.createTempDirectory("settings-inventory-fixture").toFile()
        // Two files, same constant name, different prefs file: a global map would
        // attribute one call site to the other file's prefs.
        fixtureFile(
            "Common/src/main/java/tk/glucodata/First.java",
            """
            package tk.glucodata;
            class First {
                private static final String PREFS = "first_prefs";
                void f() { getSharedPreferences(PREFS, 0); getSharedPreferences(PREFS, 0); }
            }
            """.trimIndent(),
        )
        fixtureFile(
            "Common/src/main/java/tk/glucodata/Second.java",
            """
            package tk.glucodata;
            class Second {
                private static final String PREFS = "second_prefs";
                void f() { getSharedPreferences(PREFS, 0); }
            }
            """.trimIndent(),
        )

        val (exit, output) = run("--stdout", "--root", fixture.absolutePath)

        assertEquals(output, 0, exit)
        assertTrue("first_prefs should be listed", output.contains("`first_prefs`"))
        assertTrue("second_prefs should be listed", output.contains("`second_prefs`"))
        assertTrue(
            "first_prefs has two call sites:\n$output",
            Regex("""\| `first_prefs` \| [^|]+ \| 2 \|""").containsMatchIn(output),
        )
    }

    @Test
    fun findsRoomDatabases() {
        fixture = Files.createTempDirectory("settings-inventory-fixture").toFile()
        fixtureFile(
            "Common/src/mobile/java/tk/glucodata/data/TestDatabase.kt",
            """
            package tk.glucodata.data
            @androidx.room.Database(entities = [AlphaEntity::class, BetaEntity::class], version = 1)
            abstract class TestDatabase
            """.trimIndent(),
        )

        val (exit, output) = run("--stdout", "--root", fixture.absolutePath)

        assertEquals(output, 0, exit)
        assertTrue(output.contains("`TestDatabase`"))
        assertTrue(output.contains("`AlphaEntity`"))
        assertTrue(output.contains("`BetaEntity`"))
    }

    @Test
    fun failsWhenTheCommittedInventoryIsMissingAFile() {
        fixture = Files.createTempDirectory("settings-inventory-fixture").toFile()
        fixtureFile(
            "Common/src/main/java/tk/glucodata/Only.java",
            """
            package tk.glucodata;
            class Only {
                void f() { getSharedPreferences("only_prefs", 0); }
            }
            """.trimIndent(),
        )
        // A committed doc that does not mention only_prefs.
        val doc = File(fixture, "docs/architecture/settings-inventory.md")
        doc.parentFile.mkdirs()
        doc.writeText("# Settings inventory\n\nstale\n")

        val (exit, output) = run("--check", "--root", fixture.absolutePath)

        assertEquals("drift must fail:\n$output", 1, exit)
    }
}
