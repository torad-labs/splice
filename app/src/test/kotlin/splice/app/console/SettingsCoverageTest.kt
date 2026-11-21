// The Settings page draws every setting the daemon takes live: a knob that turns live without a place on the page
// fails here, the day it turns live, so the page never lags the daemon again. The Night Deck Settings page places a
// setting by giving it splice's default in its live model (deck-settings-model.js, DEFAULTS), which every part's glass
// draws its rows from. (Rewritten Nov 2026 with the Night Deck page: the old page's knobs.js and settings.js went.)
package splice.app.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.config.Knob

class SettingsCoverageTest {

    private fun console(name: String): String =
        checkNotNull(javaClass.getResource("/console/$name")) { "console/$name is not on the classpath" }.readText()

    /** The settings the page places: the keys of its DEFAULTS table. */
    private fun placed(model: String): Set<String> {
        val start = model.indexOf("const DEFAULTS = {")
        check(start >= 0) { "deck-settings-model.js has no DEFAULTS table" }
        val table = model.substring(start, model.indexOf("};", start))
        return Regex("""(\w+):""").findAll(table).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every knob the daemon takes live has a place on the Settings page`() {
        val onPage = placed(console("deck-settings-model.js"))
        val undrawn = Knob.entries.filter { !it.restartRequired && it.key !in onPage }.map { it.key }
        assertEquals(
            emptyList<String>(),
            undrawn,
            "live knobs with no place on Settings: give each its default in deck-settings-model.js and a row",
        )
    }
}
