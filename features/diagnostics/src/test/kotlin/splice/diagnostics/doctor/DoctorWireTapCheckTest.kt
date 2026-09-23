// NEW: V4-173 — the doctor row that keeps an opted-in upstream wire tap visible. Off (the default,
// and a count of zero) is silent because nothing is kept; a count WARNs on every run and names what
// is held, who can read it and how to turn it off; a value that is not a count FAILs, because a
// head that boots with it would treat it as the knob's default and keep nothing while the operator
// believes it keeps something.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.topology.TopologyLoader
import java.nio.file.Paths

class DoctorWireTapCheckTest {

    @Test
    fun `a head that keeps bodies warns on every run, naming what is held and how to stop`() {
        val row = wireRows(head("one", wireTap = "\"8\"")).single()

        assertEquals("wire-tap:one", row.name)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("last 8 upstream request bodies in memory"), row.detail)
        assertTrue(row.detail.contains("whole conversation"), row.detail)
        assertTrue(row.detail.contains("splice wire one"), row.detail)
        assertTrue(row.detail.contains("nothing is written to disk"), row.detail)
        assertTrue(requireNotNull(row.fix).contains("remove overrides.wireTap from [heads.one]"), row.fix.orEmpty())
    }

    @Test
    fun `a head without the knob, or with zero, has no row - nothing is kept`() {
        assertEquals(emptyList<String>(), wireRows(head("one", wireTap = null)).map { it.name })
        assertEquals(emptyList<String>(), wireRows(head("one", wireTap = "\"0\"")).map { it.name })
    }

    @Test
    fun `a value that is not a count fails`() {
        val row = wireRows(head("one", wireTap = "\"lots\"")).single()

        assertEquals(CheckStatus.FAIL, row.status)
        assertTrue(row.detail.contains("not a number"), row.detail)
    }

    @Test
    fun `only the opted-in head has a row when two share a provider`() {
        val toml = head("one", wireTap = "\"2\"") + head("two", wireTap = null, port = 3902, provider = false)
        assertEquals(listOf("wire-tap:one"), wireRows(toml).map { it.name })
    }

    private fun wireRows(toml: String) = DoctorTestPorts.configChecks()
        .configurationChecks(DoctorTopology.Parsed(TopologyLoader.parse(toml)), Paths.get("/tmp/splice.toml"))
        .filter { it.name.startsWith("wire-tap:") }

    private fun head(key: String, wireTap: String?, port: Int = 3901, provider: Boolean = true): String = buildString {
        if (provider) {
            append(
                """
                [providers.cloud]
                dialect = "openai-chat"
                base_url = "https://openrouter.ai/api/v1"
                auth = { kind = "api-key", env = "K" }
                [[providers.cloud.models]]
                id = "m1"
                context_window = 8192

                """.trimIndent() + "\n",
            )
        }
        append("[heads.$key]\n")
        append("provider = \"cloud\"\n")
        append("port = $port\n")
        append("discovery_prefix = \"claude-$key--\"\n")
        append("pinned_model = \"m1\"\n")
        if (wireTap != null) append("[heads.$key.overrides]\nwireTap = $wireTap\n")
        append("\n")
    }
}
