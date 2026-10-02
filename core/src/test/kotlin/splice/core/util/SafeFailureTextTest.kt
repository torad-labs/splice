// DR-65 redo (codex probe, 2026-08-31): the sanitizer itself. render() must never execute a
// virtual method on a non-allowlisted throwable — toString() is overridable, and a colon-free
// override used to ride the class-name prefix trick into diagnostics verbatim. Non-allowlisted
// failures render as a FIXED literal; allowlisted filesystem/network classes keep their text.
package splice.core.util

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeFailureTextTest {

    @Test
    fun `an overridden toString never reaches diagnostics - DR-65`() {
        val hostile = object : RuntimeException("boom") {
            override fun toString(): String = "SECRET tok_9f8e7d rides a colon-free override"
        }
        val rendered = SafeFailureText.render(hostile)
        assertFalse(rendered.contains("tok_9f8e7d"), rendered)
        assertEquals("failure (message withheld: it may quote file bytes)", rendered)
    }

    @Test
    fun `a parser message quoting file bytes is withheld - DR-65`() {
        val parse = SerializationException(
            """Unexpected JSON token at offset 17 — JSON input: {"access_token":"tok_LIVE_9x"}""",
        )
        val rendered = SafeFailureText.render(parse)
        assertFalse(rendered.contains("tok_LIVE_9x"), rendered)
    }

    @Test
    fun `only the typed topology diagnosis can name a key while parser text stays withheld - V4-355`() {
        val safe = TopologyTypeFailure("heads.x.overrides.trace", 7, TopologyTypeFailure.Expected.QUOTED_STRING)
        assertEquals(
            "splice.toml: heads.x.overrides.trace at line 7 expects quoted string",
            SafeFailureText.render(safe),
        )
        assertEquals(safe.message, SafeFailureText.render(safe))
        val quoted = TopologyTypeFailure(
            "projects.\"/repo with space\".system_prompt",
            4,
            TopologyTypeFailure.Expected.QUOTED_STRING,
        )
        assertTrue(SafeFailureText.render(quoted).contains("/repo with space"))
        assertEquals(
            "failure (message withheld: it may quote file bytes)",
            SafeFailureText.render(SerializationException("trace = V4355_VALUE_MUST_NOT_LEAVE_FILE")),
        )
    }

    @Test
    fun `filesystem failures keep their safe diagnostic text - DR-65 control`() {
        val fs = java.nio.file.NoSuchFileException("/somewhere/auth.json")
        assertTrue(SafeFailureText.render(fs).contains("/somewhere/auth.json"))
    }

    @Test
    fun `a throw site names the splice source line and never the message`() {
        val thrown = checkNotNull(runCatching { check(false) { "PRIVATE_SCRIPT_BYTES" } }.exceptionOrNull())
        val site = SafeFailureText.site(thrown)
        assertTrue(site.matches(Regex(" at SafeFailureTextTest\\.kt:\\d+")), site)
        assertFalse(site.contains("PRIVATE_SCRIPT_BYTES"), site)
        val stackless = IllegalStateException("PRIVATE_SCRIPT_BYTES").apply { stackTrace = emptyArray() }
        assertEquals("", SafeFailureText.site(stackless))
    }
}
