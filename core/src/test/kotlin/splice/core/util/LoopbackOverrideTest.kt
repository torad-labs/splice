package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    // Review of adf35c39e, finding 1: the refusal line named the FALLBACK URL, and that URL is derived
    // from the operator's own issuer (CodexAuthFile.tokenUrl builds "${issuer}/oauth/token"). A loopback
    // issuer carrying userinfo is accepted, so its password rode the refusal line for the next variable.
    // Asserted per vendor, on the real derivation each one uses.
    @Test
    fun `a refusal names the host and port only, never the userinfo a derived endpoint carries`() {
        val secret = "private-token"
        val derived = mapOf(
            "CODEX_OAUTH_TOKEN_URL" to "http://user:$secret@127.0.0.1:1456/oauth/token",
            "GROK_OAUTH_TOKEN_URL" to "http://user:$secret@127.0.0.1:1457/oauth2/token",
            "KIMI_OAUTH_HOST" to "http://user:$secret@127.0.0.1:1458",
            "CLAUDE_OAUTH_USAGE_URL" to "http://user:$secret@127.0.0.1:1459/api/oauth/usage?key=$secret",
        )
        derived.forEach { (name, fallback) ->
            val log = mutableListOf<String>()
            val used = LoopbackOverride.url(
                EnvReader { if (it == name) "https://collector.example.test/u" else null },
                name,
                fallback,
                LogSink { log += it },
            )

            assertEquals(fallback, used, "$name: the vendor's own endpoint is still what splice uses")
            val line = log.single()
            assertFalse(line.contains(secret), "$name leaked its userinfo: $line")
            assertFalse(line.contains("user:"), "$name named userinfo: $line")
            assertTrue(line.contains("127.0.0.1:145"), "$name names the host and port: $line")
            assertFalse(line.contains("/oauth") || line.contains("/api/"), "$name named a path: $line")
        }
    }

    @Test
    fun `a fallback that does not parse is named as the default, never echoed`() {
        val log = mutableListOf<String>()
        val used = LoopbackOverride.url(
            EnvReader { "https://collector.example.test/u" },
            "TEST_UNPARSEABLE_URL",
            "not a url with a secret",
            LogSink { log += it },
        )

        assertEquals("not a url with a secret", used)
        assertFalse(log.single().contains("secret"), log.single())
    }
}
