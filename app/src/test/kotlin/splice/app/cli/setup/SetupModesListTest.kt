// `splice setup`'s Modes step lists each mode as splice.toml and its manifest leave it: visual, which its manifest puts
// on for Claude Desktop alone, is listed as on for Claude Desktop and not asked about, and splice.toml's word for
// every session lists it on or off.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
