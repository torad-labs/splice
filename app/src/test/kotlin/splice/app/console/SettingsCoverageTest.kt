// The Settings page draws every setting the daemon takes live, under one of its jobs: a knob that turns live without a
// row fails here, the day it turns live, so the page never lags the daemon again. Restart-only knobs are not drawn
// (Marlin, Oct 10: every knob the console draws applies live), so they owe the page nothing.
package splice.app.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.config.Knob

private const val NO_JOB = "no job among the six the page offers"

class SettingsCoverageTest {

    private fun console(name: String): String =
        checkNotNull(javaClass.getResource("/console/$name")) { "console/$name is not on the classpath" }.readText()

    /** The jobs the page offers, with the keys a job names itself (Many agents at once names its first two). */
    private fun jobs(settings: String): Pair<Set<String>, Set<String>> {
        val ids = Regex("""\{ id: "(\w+)", name:""").findAll(settings).map { it.groupValues[1] }.toSet()
        val own = Regex("""keys: \[([^\]]*)]""").findAll(settings)
            .flatMap { Regex("\"(\\w+)\"").findAll(it.groupValues[1]).map { k -> k.groupValues[1] } }.toSet()
        return ids to own
    }

    /** A knob's row in knobs.js: the Settings job it names, or null when the row names none. */
    private fun homeOf(kit: String, key: String): String? {
        val at = kit.indexOf("K(\"$key\"")
        if (at < 0) return null
        val end = kit.indexOf("\n    K(\"", at + 1).takeIf { it > 0 } ?: kit.length
        return Regex("""home: "(\w+)"""").find(kit.substring(at, end))?.groupValues?.get(1)
    }

    @Test
    fun `every knob the daemon takes live is drawn under a job on the Settings page`() {
        val kit = console("knobs.js")
        val (jobIds, ownKeys) = jobs(console("settings.js"))
        // live and deliberately off the page, each with the reason: no job among the six holds it
        val offPage = mapOf("statuslineGitRoots" to NO_JOB)

        val undrawn = Knob.entries.filter { !it.restartRequired && it.key !in offPage }
            .filter { it.key !in ownKeys && homeOf(kit, it.key) !in jobIds }
            .map { it.key }

        assertEquals(
            emptyList<String>(),
            undrawn,
            "live knobs with no row on Settings: give each a row in knobs.js with a job",
        )
    }
}
