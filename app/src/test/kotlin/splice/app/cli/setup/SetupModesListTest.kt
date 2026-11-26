// `splice setup`'s Modes step lists each mode as splice.toml and its manifest leave it: visual, which its manifest puts
// on for Claude Desktop alone, is listed as on for Claude Desktop and not asked about, with a line saying where to have
// it in every session; splice.toml's word for every session lists it on or off.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.modes.api.ModeCaller
import splice.terminal.ConfirmPrompt
import splice.terminal.ConsolePresence
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

class SetupModesListTest {
    private fun said(path: Path): Map<String, String> = CarriedModes()(path).associate { it.name to it.said }

    @Test
    fun `visual is on for Claude Desktop with nothing in splice toml, and on or off everywhere as splice toml says`(
        @TempDir tmp: Path,
    ) {
        val fresh = said(tmp.resolve("splice.toml"))
        assertEquals("on for Claude Desktop", fresh["visual"])
        assertEquals("on", fresh["ccdsession"], "a mode without on_for lists as before")
        assertEquals("off", fresh["tableapp"])
        val on = Files.writeString(tmp.resolve("on.toml"), "[modes.visual]\non = true\n")
        assertEquals("on", said(on)["visual"])
        val off = Files.writeString(tmp.resolve("off.toml"), "[modes.visual]\non = false\n")
        assertEquals("off", said(off)["visual"])
    }

    @Test
    fun `a mode on for Desktop alone is not asked about, and the step says where to have it in every session`(
        @TempDir tmp: Path,
    ) {
        val shown = StringBuilder()
        val asked = mutableListOf<String>()
        val prompts = SetupPrompts(
            frame = WizardFrame(
                shown,
                ConfirmPrompt { question, _ ->
                    asked += question
                    false
                },
            ),
            hasConsole = ConsolePresence { true },
        )
        val rows = ModeRows {
            listOf(
                ModeRow("visual", "Draws.", on = true, onlyFor = listOf(ModeCaller.DESKTOP)),
                ModeRow("tableapp", "Tables.", on = false),
            )
        }
        val turnedOn = SetupModes(prompts, EnvReader { null }, rows).offer(tmp.resolve("splice.toml"))
        assertEquals(emptyList<String>(), turnedOn)
        assertEquals(listOf("Turn on tableapp? Tables."), asked, "visual is not asked about")
        val text = shown.toString()
        assertTrue("visual (on for Claude Desktop), tableapp (off)" in text, text)
        assertTrue(
            "visual in every session: Settings > Modes, or [modes.visual] on = true in splice.toml" in text,
            text,
        )

        val onlyPartly = ModeRows {
            listOf(ModeRow("visual", "Draws.", on = true, onlyFor = listOf(ModeCaller.DESKTOP)))
        }
        val again = StringBuilder()
        val quiet = SetupPrompts(
            frame = WizardFrame(again, ConfirmPrompt { _, _ -> error("nothing to ask") }),
            hasConsole = ConsolePresence { true },
        )
        assertNull(SetupModes(quiet, EnvReader { null }, onlyPartly).offer(tmp.resolve("splice.toml")), "nothing asked")
        assertTrue("visual in every session: Settings > Modes" in again.toString(), "and the line is still said")
    }
}
