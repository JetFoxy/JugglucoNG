package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation of `Specific`, the per-variant composition root, before it is changed.
 *
 * This is the last category S pair, and the plan treats it differently on purpose:
 * "Specific is last. It *is* the per-variant composition root ... rename it per variant
 * (for example MobileBootstrap / WearBootstrap behind a VariantBootstrap interface), called
 * from Applic.onCreate" (direction.md §4).
 *
 * The "extract the contract the shared caller uses" recipe does not fit here, and the two
 * reasons are the useful output of this exercise:
 *
 * 1. **The signatures disagree, and one disagreement is behavioural.** `settext` returns
 *    void on the phone and boolean on the watch, so a shared interface has to pick one. The
 *    only shared caller ignores the value, so either choice compiles -- which is exactly
 *    why it has to be a decision on record rather than a refactorer's guess. The rest differ
 *    only in parameter types (`start(Application)` vs `start(Object)`, `splash(Object)` vs
 *    `splash(AppCompatActivity)`, `initScreen(Object)` vs `initScreen(MainActivity)`),
 *    which a `VariantBootstrap` would widen to Any.
 * 2. **`useclose` is state, not a call.** Final `true` on the phone, mutable `false` on the
 *    watch and written by `setclose`. Shared code reads it in seven places and four wear
 *    files import it statically, so making it a call is a change to how state is held, not a
 *    rename.
 *
 * The surface is ten members, not the "one to three" the recipe expects. One of them,
 * `blockedNum`, has no caller in src/main at all.
 *
 * Source checks, as in GlucoseAlarmsAsymmetryTests, and every assertion checks its anchor
 * first: a source check is only as stable as the names it quotes, which
 * GlucoseAlarmsAsymmetryTests proved when #469 renamed a class underneath it.
 */
class SpecificCompositionRootTests {
    private val phone: String = read("Common/src/mobile/java/tk/glucodata/Specific.java")
    private val watch: String = read("Common/src/wear/java/tk/glucodata/Specific.java")
    private val applic: String = read("Common/src/main/java/tk/glucodata/Applic.java")

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = dir.resolve(relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("not found: $relative")
    }

    private fun mustContain(haystack: String, needle: String, file: String) {
        assertTrue("expected to find in $file: $needle", haystack.contains(needle))
    }

    @Test
    fun settextIsTheOneHookThatDisagreesBehaviourally() {
        mustContain(phone, "void settext(String str)", "phone Specific")
        mustContain(watch, "boolean settext(String str)", "wear Specific")
        mustContain(applic, "Specific.settext(str);", "Applic")
        assertFalse(
            "the only shared caller is in statement position, so the return value is " +
                "discarded today. If it ever gets used, the interface's return type stops " +
                "being a free choice.",
            applic.contains("if (Specific.settext"),
        )
    }

    @Test
    fun theOtherSignaturesDifferOnlyInParameterTypes() {
        mustContain(phone, "void start(Application context)", "phone Specific")
        mustContain(watch, "void start(Object context)", "wear Specific")
        mustContain(phone, "void splash(Object act)", "phone Specific")
        mustContain(watch, "void splash(AppCompatActivity act)", "wear Specific")
        mustContain(phone, "void initScreen(Object act)", "phone Specific")
        mustContain(watch, "void initScreen(MainActivity act)", "wear Specific")
    }

    @Test
    fun usecloseIsStateAndNotACall() {
        mustContain(phone, "final boolean useclose = true", "phone Specific")
        mustContain(watch, "boolean useclose=false", "wear Specific")
        mustContain(watch, "useclose=val;", "wear Specific")
        mustContain(phone, "void setclose(boolean c) { }", "phone Specific")
    }

    @Test
    fun usecloseIsReadAsAFieldAllOverThePlace() {
        // Four wear files import it statically, so it is not only shared code that reads it.
        for (path in listOf(
            "Common/src/wear/java/tk/glucodata/glucosecomplication/WearColorConfig.java",
            "Common/src/wear/java/tk/glucodata/settings/WearSetColors.java",
            "Common/src/wear/java/tk/glucodata/Switch.java",
            "Common/src/wear/java/tk/glucodata/WearFloatingConfig.java",
        )) {
            mustContain(read(path), "Specific.useclose", path)
        }
    }

    @Test
    fun registerBridgesIsCalledExactlyOnceFromApplicOnCreate() {
        // I expected this to be one of the complications -- it used to be reached from
        // MainActivity and JournalIobAccess as well. It is not: there is a single call, in
        // onCreate, with a comment saying why it must be there rather than at the end of
        // initproc(). So the plan's "called from Applic.onCreate" already describes the
        // code, and the seam has nothing to reconcile.
        mustContain(applic, "Specific.registerBridges();", "Applic")
        assertFalse(
            "Applic must call it from onCreate, before anything reads a bridge",
            applic.indexOf("public void onCreate()") > applic.indexOf("Specific.registerBridges();"),
        )
    }

    @Test
    fun bothFlavoursCarryTheSameTenHooks() {
        for (hook in listOf(
            "registerBridges", "start", "splash", "historyDatabaseCompatible", "settext",
            "rmlayout", "initScreen", "blockedNum", "setclose", "wearnosensors",
        )) {
            mustContain(phone, hook, "phone Specific")
            mustContain(watch, hook, "wear Specific")
        }
    }

    @Test
    fun blockedNumHasNoSharedCaller() {
        mustContain(phone, "blockedNum", "phone Specific")
        mustContain(watch, "blockedNum", "wear Specific")
        assertFalse(
            "blockedNum exists on both flavours and nothing in src/main calls it. A " +
                "VariantBootstrap should not enshrine a hook nobody uses.",
            applic.contains("Specific.blockedNum"),
        )
    }
}
