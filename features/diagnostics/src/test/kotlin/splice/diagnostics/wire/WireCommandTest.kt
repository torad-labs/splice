// NEW: V4-173 — `splice wire <head>`: the verb resolves the head's port from the topology, presents
// the management key on the head's own port, and prints what the head served — or the head's own
// reason when the tap is off. The network is the WireFetch seam, so every cell pins the exact URL
// and bearer the verb sends, which is the whole contract between this CLI and HeadEngine's route.
package splice.diagnostics.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.ControlReply
import splice.topology.TopologyLoader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val MGMT_KEY = "the-mgmt-key"

class WireCommandTest {

    /** The starter topology (head `openrouter` on :3101) and a minted management key. */
    private fun env(tmp: Path): EnvReader {
        TopologyLoader.loadOrMaterialize(tmp.resolve("splice.toml"))
        val env = EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
                "CLAUDEX_STATE_DIR" -> tmp.resolve("state").toString()
                else -> null
            }
        }
        val keyFile = StatePaths(envReader = env).mgmtKeyFile
        Files.createDirectories(keyFile.parent)
        Files.writeString(keyFile, MGMT_KEY)
        return env
    }

    private class RecordingHttp(private val reply: ControlReply?) : WireFetch {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override fun request(method: String, url: String, bearer: String): ControlReply? {
            calls += Triple(method, url, bearer)
            return reply
        }
    }

    /** The verb as app wires it — stdout and stderr — so capture() reads what an operator sees. */
    private fun wireCommand(http: WireFetch) =
        WireCommand(TerminalOutput(::println), TerminalOutput(System.err::println), http)

    private fun capture(block: () -> Boolean): Triple<Boolean, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val ok = try {
            block()
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
        return Triple(ok, out.toString(), err.toString())
    }

    @Test
    fun `asks the head's own port for its wire, with the management key, and prints each body verbatim`(
        @TempDir tmp: Path,
    ) {
        val payload = """{"key":"openrouter","keep":4,"records":[""" +
            """{"ts":1700000000000,"session":"abc12345","model":"m1","compact":false,"body":"{\"system\":\"house rules\"}"},""" +
            """{"ts":1700000001000,"model":"m1","compact":true,"body":"{\"n\":2}"}]}"""
        val http = RecordingHttp(ControlReply(200, payload))

        val (ok, out, _) = capture { wireCommand(http).wire(listOf("openrouter", "--last", "2"), env(tmp)) }

        assertTrue(ok)
        assertEquals(listOf(Triple("GET", "http://127.0.0.1:3101/wire?last=2", MGMT_KEY)), http.calls)
        assertTrue(out.contains("2/4 kept upstream bodies"), out)
        assertTrue(out.contains("""{"system":"house rules"}"""), out)
        assertTrue(out.contains("session=abc12345"), out)
        assertTrue(out.contains("session=-  model=m1 compact"), out)
    }

    @Test
    fun `--json prints the head's payload as served`(@TempDir tmp: Path) {
        val payload = """{"key":"openrouter","keep":1,"records":[]}"""
        val http = RecordingHttp(ControlReply(200, payload))

        val (ok, out, _) = capture { wireCommand(http).wire(listOf("openrouter", "--json"), env(tmp)) }

        assertTrue(ok)
        assertEquals(payload, out.trim())
        assertEquals("http://127.0.0.1:3101/wire?last=0", http.calls.single().second, "no --last = the whole ring")
    }

    @Test
    fun `a head whose tap is off fails with the head's own reason`(@TempDir tmp: Path) {
        val off = """{"error":"wire tap is off for head openrouter: set [heads.openrouter.overrides] wireTap = N"}"""
        val http = RecordingHttp(ControlReply(404, off))

        val (ok, _, err) = capture { wireCommand(http).wire(listOf("openrouter"), env(tmp)) }

        assertFalse(ok)
        assertTrue(err.contains("[heads.openrouter.overrides] wireTap"), err)
    }

    @Test
    fun `a head that does not answer, and a head that is not configured, each fail in words`(@TempDir tmp: Path) {
        val silent = RecordingHttp(null)
        val (okSilent, _, errSilent) = capture { wireCommand(silent).wire(listOf("openrouter"), env(tmp)) }
        assertFalse(okSilent)
        assertTrue(errSilent.contains("not answering on :3101"), errSilent)

        val unasked = RecordingHttp(ControlReply(200, "{}"))
        val (okUnknown, _, errUnknown) = capture { wireCommand(unasked).wire(listOf("nope"), env(tmp)) }
        assertFalse(okUnknown)
        assertTrue(errUnknown.contains("no head named 'nope'"), errUnknown)
        assertTrue(errUnknown.contains("openrouter"), "the configured heads are listed: $errUnknown")
        assertTrue(unasked.calls.isEmpty(), "nothing is asked for a head that is not configured")
    }

    @Test
    fun `argument parsing`() {
        val command = WireArgs()
        assertEquals(WireOpts("kimi", 0, false), command.parse(listOf("kimi")))
        assertEquals(WireOpts("kimi", 3, true), command.parse(listOf("--json", "kimi", "--last", "3")))
        assertNull(command.parse(emptyList()), "the head is required")
        assertNull(command.parse(listOf("kimi", "--last")), "--last needs a count")
        assertNull(command.parse(listOf("kimi", "--last", "0")), "a count is positive")
        assertNull(command.parse(listOf("kimi", "extra")), "one head only")
        assertNull(command.parse(listOf("kimi", "--follow")), "no unknown flags")
    }
}
