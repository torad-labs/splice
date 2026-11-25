// A manifest's `on_for` (modes/README.md, "The manifest"): the callers a mode is on for by default where its `on` is
// false. It is said only where every place that shows the mode can show it truthfully, so a word that is not a
// caller's, a caller splice does not serve it for yet (door, head), a caller `[callers] off` leaves out, a true `on`
// beside it and a non-empty `[heads] only` beside it are findings that refuse boot, every one at once.
package splice.modes.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.capability.TomlTree

class ManifestOnForTest {
    private fun read(tail: String, on: Boolean = false): Pair<ModeManifest?, List<String>> {
        val faults = ModeFaults("demo")
        val toml = """
            name = "demo"
            summary = "A demo."
            server = "demo"
            handler = "splice.modes.demo.DemoMode"
            tools = ["show"]
            kind = "mcp"
            on = $on

        """.trimIndent() + tail.trimIndent()
        return ManifestRead(checkNotNull(TomlTree.read(toml)), "demo", faults).manifest() to faults.list
    }

    @Test
    fun `on_for names Claude Desktop, and a manifest without it names none`() {
        val (named, none) = read("on_for = [\"desktop\"]")
        assertEquals(emptyList<String>(), none)
        assertEquals(listOf(ModeCaller.DESKTOP), checkNotNull(named).offer.onFor)
        val (plain, _) = read("")
        assertEquals(emptyList<ModeCaller>(), checkNotNull(plain).offer.onFor)
    }

    @Test
    fun `door and head are not served yet, and a word that is not a caller's is a finding, all at once`() {
        val (manifest, faults) = read("on_for = [\"desktop\", \"door\", \"head\", \"phone\"]")
        assertEquals(null, manifest)
        assertEquals(
            listOf(
                "mode demo: on_for names a caller that is not door, head or desktop",
                "mode demo: on_for names door, which splice does not serve yet: on_for takes desktop alone",
                "mode demo: on_for names head, which splice does not serve yet: on_for takes desktop alone",
            ),
            faults,
        )
    }

    @Test
    fun `a caller callers off leaves out is a finding`() {
        val (manifest, faults) = read(
            """
            on_for = ["desktop"]
            [callers]
            off = ["desktop"]
            """,
        )
        assertEquals(null, manifest)
        assertEquals(listOf("mode demo: on_for names desktop, which callers.off leaves out"), faults)
    }

    @Test
    fun `on_for beside a true on does nothing, and is a finding`() {
        val (manifest, faults) = read("on_for = [\"desktop\"]", on = true)
        assertEquals(null, manifest)
        assertEquals(
            listOf("mode demo: on_for does nothing while on is true: the mode is on for every caller already"),
            faults,
        )
    }

    @Test
    fun `on_for beside a non-empty heads only never reaches Desktop, and is a finding`() {
        val (manifest, faults) = read(
            """
            on_for = ["desktop"]
            [heads]
            only = ["acme"]
            """,
        )
        assertEquals(null, manifest)
        assertEquals(
            listOf("mode demo: on_for needs an empty heads.only: Claude Desktop runs no head, so never gets the mode"),
            faults,
        )
    }

    @Test
    fun `on_for that is not a list of strings is a finding`() {
        val (manifest, faults) = read("on_for = \"desktop\"")
        assertEquals(null, manifest)
        assertEquals(1, faults.count { "on_for is not a list of strings" in it }, "$faults")
    }
}
