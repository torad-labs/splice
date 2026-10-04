// NEW: V4-174/V4-387 — the doctor row names an explicit trace opt-out. Absent or true is on
// by default and quiet; false WARNs, naming the declared directory, retention and purge verb.
// Invalid values FAIL without echoing the operator's raw text.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.StatePaths
import splice.topology.TopologyLoader
import java.nio.file.Paths

class DoctorTraceCheckTest {

    @Test
    fun `an opted out head warns on every run, naming its next-start files and the purge verb`() {
        val row = traceRows(head("one", trace = "\"false\"", retention = "\"3\"")).single()

        assertEquals("trace:one", row.name)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("will not write its FULL request/response trace on the next start"), row.detail)
        assertTrue(row.detail.contains("When enabled, it records"), row.detail)
        assertTrue(row.detail.contains("exact body it sent (credentials redacted)"), row.detail)
        assertTrue(row.detail.contains(StatePaths().traceDir.toString()), row.detail)
        assertTrue(row.detail.contains("kept 3 day(s)"), row.detail)
        assertTrue(row.detail.contains("splice trace one"), row.detail)
        val fix = requireNotNull(row.fix)
        assertTrue(fix.contains("remove overrides.trace = false from [heads.one]"), fix)
        assertTrue(fix.contains("splice trace one --purge"), fix)
    }

    @Test
    fun `the default retention is named when an opted out head did not set one`() {
        val row = traceRows(head("one", trace = "\"off\"")).single()
        assertTrue(row.detail.contains("kept 7 day(s)"), row.detail)
    }

    @Test
    fun `a head with no override or explicit on has no doctor warning`() {
        assertEquals(emptyList<String>(), traceRows(head("one", trace = null)).map { it.name })
        assertEquals(emptyList<String>(), traceRows(head("one", trace = "\"true\"")).map { it.name })
        assertEquals(emptyList<String>(), traceRows(head("one", trace = "\"on\"")).map { it.name })
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
