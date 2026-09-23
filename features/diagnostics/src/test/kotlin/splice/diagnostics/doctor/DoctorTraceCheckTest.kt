// NEW: V4-174 — the doctor row that keeps an opted-in full trace visible. Off (absent, or false)
// is silent because nothing is written; true WARNs on every run and names what is written, where,
// for how long, who can read it and how to purge it; a spelling that is neither true nor false
// FAILs, because a head that boots with it reads it as off while the operator may believe it is on.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.StatePaths
import splice.topology.TopologyLoader
import java.nio.file.Paths

class DoctorTraceCheckTest {

    @Test
    fun `a traced head warns on every run, naming the files, the retention and the purge verb`() {
        val row = traceRows(head("one", trace = "\"true\"", retention = "\"3\"")).single()

        assertEquals("trace:one", row.name)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("FULL request/response trace"), row.detail)
        assertTrue(row.detail.contains("exact body it sent (credentials redacted)"), row.detail)
        assertTrue(row.detail.contains(StatePaths().traceDir.toString()), row.detail)
        assertTrue(row.detail.contains("kept 3 day(s)"), row.detail)
        assertTrue(row.detail.contains("splice trace one"), row.detail)
        val fix = requireNotNull(row.fix)
        assertTrue(fix.contains("remove overrides.trace from [heads.one]"), fix)
        assertTrue(fix.contains("splice trace one --purge"), fix)
    }

    @Test
    fun `the default retention is named when the head did not set one`() {
        val row = traceRows(head("one", trace = "\"on\"")).single()
        assertTrue(row.detail.contains("kept 7 day(s)"), row.detail)
    }

    @Test
    fun `a head without the knob, or with it off, has no row - nothing is written`() {
        assertEquals(emptyList<String>(), traceRows(head("one", trace = null)).map { it.name })
        assertEquals(emptyList<String>(), traceRows(head("one", trace = "\"false\"")).map { it.name })
        assertEquals(emptyList<String>(), traceRows(head("one", trace = "\"off\"")).map { it.name })
    }

    @Test
    fun `a spelling that is neither true nor false fails`() {
        val row = traceRows(head("one", trace = "\"maybe\"")).single()

        assertEquals(CheckStatus.FAIL, row.status)
        assertTrue(row.detail.contains("neither true nor false"), row.detail)
    }

    private fun traceRows(toml: String) = DoctorTestPorts.configChecks()
        .configurationChecks(DoctorTopology.Parsed(TopologyLoader.parse(toml)), Paths.get("/tmp/splice.toml"))
        .filter { it.name.startsWith("trace:") }

    private fun head(key: String, trace: String?, retention: String? = null, port: Int = 3901): String = buildString {
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
        append("[heads.$key]\n")
        append("provider = \"cloud\"\n")
        append("port = $port\n")
        append("discovery_prefix = \"claude-$key--\"\n")
        append("pinned_model = \"m1\"\n")
        if (trace != null) {
            append("[heads.$key.overrides]\ntrace = $trace\n")
            if (retention != null) append("traceRetentionDays = $retention\n")
        }
        append("\n")
    }
}
