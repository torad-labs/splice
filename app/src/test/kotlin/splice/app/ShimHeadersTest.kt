// NEW: #791 (Nov 23, 2026) — the headers a launched Claude Code sends: the caller shell's own
// ANTHROPIC_CUSTOM_HEADERS, which the launcher reads and never sends to the daemon, then the recipe's (splice's own:
// the client wall, the window, the launch proof). An outer session's planted headers give way to this launch's, and a
// wrapper's identity (x-splice-wrapper) rides on. Runs the shipped shim's own functions under plain sh, as the shim
// runs on a machine without bash.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ShimHeadersTest {
    private val shim: String = requireNotNull(System.getProperty("splice.launchShim")) { "splice.launchShim unset" }

    /** What the session sends with the caller's [ambient] headers (null: none set) and the recipe's [recipe]. */
    private fun sent(ambient: String?, recipe: String): String {
        val script = "SPLICE_LAUNCH_FUNCTIONS_ONLY=1 . \"\$SHIM\"; caller_headers; merged_headers \"\$RECIPE\"; " +
            "printf '%s' \"\$MERGED_HEADERS\""
        val builder = ProcessBuilder("sh", "-c", script)
        builder.environment().apply {
            remove("ANTHROPIC_CUSTOM_HEADERS")
            ambient?.let { put("ANTHROPIC_CUSTOM_HEADERS", it) }
            put("SHIM", shim)
            put("RECIPE", recipe)
        }
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the shim's functions did not finish")
        assertEquals(0, process.exitValue(), output)
        return output
    }

    @Test
    fun `the caller's headers come first, and the launch's own follow`() {
        val ambient = "x-team: blue\nx-corp-route: a:b"
        assertEquals(
            listOf("x-team: blue", "x-corp-route: a:b", "x-splice-client-wall-ms: 605760000"),
            sent(ambient, "x-splice-client-wall-ms: 605760000").lines(),
        )
    }

    @Test
    fun `an outer session's planted headers give way, in any case and indent, and a wrapper's identity stays`() {
        val ambient = listOf(
            "x-splice-client-wall-ms: 5",
            "  X-Splice-Launch-Proof: from-an-outer-session",
            "x-splice-client-window: 200000",
            "X-SPLICE-CLIENT: 0.6.0",
            "  X-Splice-Launch-Owner: outer",
            "",
            "x-splice-wrapper: acme-ai/1.4.0",
            "x-team: blue",
        ).joinToString("\n")
        assertEquals(
            listOf("x-splice-wrapper: acme-ai/1.4.0", "x-team: blue", "x-splice-client-wall-ms: 99"),
            sent(ambient, "x-splice-client-wall-ms: 99").lines(),
        )
    }

    @Test
    fun `with no header of the caller's the recipe's alone are sent, and with neither none are`() {
        assertEquals("x-splice-client-wall-ms: 99", sent(null, "x-splice-client-wall-ms: 99"))
        assertEquals("", sent("x-splice-launch-proof: old", ""))
        assertEquals("x-team: blue", sent("x-team: blue", ""))
    }
}
