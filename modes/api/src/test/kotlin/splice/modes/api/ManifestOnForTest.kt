// A manifest's `on_for` (modes/README.md, "The manifest"): the callers a mode is on for by default where its `on` is
// false. A word that is not a caller's, and a caller `[callers] off` leaves out, are findings that refuse boot, every
// one at once.
package splice.modes.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.capability.TomlTree

class ManifestOnForTest {
    private fun read(tail: String): Pair<ModeManifest?, List<String>> {
        val faults = ModeFaults("demo")
        val toml = """
            name = "demo"
            summary = "A demo."
            server = "demo"
            handler = "splice.modes.demo.DemoMode"
            tools = ["show"]
            kind = "mcp"
            on = false

        """.trimIndent() + tail.trimIndent()
        return ManifestRead(checkNotNull(TomlTree.read(toml)), "demo", faults).manifest() to faults.list
    }

    @Test
    fun `on_for names the callers the mode is on for, and a manifest without it names none`() {
        val (named, none) = read("on_for = [\"desktop\", \"head\"]")
        assertEquals(emptyList<String>(), none)
        assertEquals(listOf(ModeCaller.DESKTOP, ModeCaller.HEAD), checkNotNull(named).offer.onFor)
        val (plain, _) = read("")
        assertEquals(emptyList<ModeCaller>(), checkNotNull(plain).offer.onFor)
    }

    @Test
    fun `a word that is not a caller's, and a caller callers off leaves out, are findings, all at once`() {
        val (manifest, faults) = read(
            """
            on_for = ["desktop", "phone"]
            [callers]
            off = ["desktop"]
            """,
        )
        assertEquals(null, manifest)
        assertEquals(2, faults.size, faults.toString())
        assertEquals(1, faults.count { "on_for names a caller that is not door, head or desktop" in it }, "$faults")
        assertEquals(1, faults.count { "on_for names desktop, which callers.off leaves out" in it }, "$faults")
    }

    @Test
    fun `on_for that is not a list of strings is a finding`() {
        val (manifest, faults) = read("on_for = \"desktop\"")
        assertEquals(null, manifest)
        assertEquals(1, faults.count { "on_for is not a list of strings" in it }, "$faults")
    }
}
