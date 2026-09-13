import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.GATEWAY_VERSION
import splice.core.client.ClientVersionParser
import splice.core.version.ClientVersionTracker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClientVersionTest {
    private val parser = ClientVersionParser()

    @Test
    fun `Claude CLI user agent yields its dotted numeric version`() {
        assertEquals("2.1.257", parser.fromUserAgent("claude-cli/2.1.257")?.wire)
        assertEquals(
            "10.20.300",
            parser.fromUserAgent("other/1 claude-cli/10.20.300 (external, cli)")?.wire,
        )
        assertNull(parser.fromUserAgent("other-cli/2.1.258"))
        assertNull(parser.fromUserAgent("claude-cli/latest"))
        assertNull(parser.fromUserAgent("claude-cli/2.1.258beta"))
    }

    @Test
    fun `versions compare by numeric components and ignore trailing zero components`() {
        assertTrue(parser.parse("2.10.0")!! > parser.parse("2.9.999")!!)
        assertTrue(parser.parse("10.0.0")!! > parser.parse("2.999.999")!!)
        assertEquals(parser.parse("2.1.0"), parser.parse("2.1.0.0"))
    }

    @Test
    fun `equal older malformed and sessionless observations stay silent`() {
        val versions = ClientVersionTracker(testedVersion = "2.1.257")
        versions.observe("equal", "claude-cli/2.1.257")
        versions.observe("older", "claude-cli/2.1.99")
        versions.observe("malformed", "claude-cli/newest")
        versions.observe(null, "claude-cli/99.0.0")
        versions.observe("", "claude-cli/99.0.0")

        assertNull(versions.aggregateWarning())
        assertNull(versions.statuslineWarning("equal"))
        assertNull(versions.statuslineWarning("older"))
    }

    @Test
    fun `newest version is retained per session and the aggregate reports the newest session`() {
        val versions = ClientVersionTracker(testedVersion = "2.1.257")
        versions.observe("a", "claude-cli/2.2.0")
        versions.observe("a", "claude-cli/2.1.300")
        versions.observe("b", "claude-cli/3.0.0")

        assertEquals(warning("3.0.0", "2.1.257"), versions.aggregateWarning())
        assertEquals(warning("2.2.0", "2.1.257"), versions.statuslineWarning("a"))
        assertEquals(warning("3.0.0", "2.1.257"), versions.statuslineWarning("b"))
    }

    @Test
    fun `statusline emits once per session without consuming aggregate or peer surfaces`() {
        val versions = ClientVersionTracker(testedVersion = "2.1.257")
        versions.observe("a", "claude-cli/2.1.258")
        versions.observe("b", "claude-cli/2.1.258")
        val expected = warning("2.1.258", "2.1.257")

        assertEquals(expected, versions.aggregateWarning())
        assertEquals(expected, versions.statuslineWarning("a"))
        assertNull(versions.statuslineWarning("a"))
        assertEquals(expected, versions.aggregateWarning())
        assertEquals(expected, versions.statuslineWarning("b"))
    }

    @Test
    fun `concurrent observations retain the numerically newest version`() {
        val versions = ClientVersionTracker(testedVersion = "1.0.0")
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..100).forEach { patch ->
                pool.submit { versions.observe("shared", "claude-cli/2.0.$patch") }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(warning("2.0.100", "1.0.0"), versions.aggregateWarning())
    }

    private fun warning(version: String, tested: String) =
        "Claude Code $version is newer than the version splice $GATEWAY_VERSION was tested with ($tested)"
}
