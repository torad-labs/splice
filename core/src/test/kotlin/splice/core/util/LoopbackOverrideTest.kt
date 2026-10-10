package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Oct 10, 2026: an endpoint override carries the operator's token, so only a stand-in on this machine may take it.
class LoopbackOverrideTest {
    private val vendor = "https://api.anthropic.com/api/oauth/usage"

    private fun resolve(value: String?, log: MutableList<String>, name: String = "TEST_USAGE_URL"): String =
        LoopbackOverride.url(EnvReader { if (it == name) value else null }, name, vendor, LogSink { log += it })

    @Test
    fun `an override on this machine is used, and any other host is ignored with one line`() {
        val log = mutableListOf<String>()
        listOf("http://127.0.0.1:31990/u", "http://localhost:31990/u", "http://[::1]:31990/u").forEach {
            assertEquals(it, resolve(it, log))
        }
        assertEquals(vendor, resolve(null, log))
        assertEquals(vendor, resolve("https://collector.example.test/u", log, name = "TEST_REMOTE_URL"))
        assertEquals(vendor, resolve("http://127.0.0.1.example.test/u", log, name = "TEST_REMOTE_URL"))
        assertEquals(vendor, resolve("not a url", log, name = "TEST_REMOTE_URL"))
        assertEquals(1, log.size, "one line per variable, not one per read")
    }
}
