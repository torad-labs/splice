// DR-65 redo (codex probe, 2026-08-31): the sanitizer itself. render() must never execute a
// virtual method on a non-allowlisted throwable — toString() is overridable, and a colon-free
// override used to ride the class-name prefix trick into diagnostics verbatim. Non-allowlisted
// failures render as a FIXED literal; allowlisted filesystem/network classes keep their text.
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.SafeFailureText

class SafeFailureTextTest {

    @Test
    fun `an overridden toString never reaches diagnostics - DR-65`() {
        val hostile = object : RuntimeException("boom") {
            override fun toString(): String = "SECRET tok_9f8e7d rides a colon-free override"
        }
        val rendered = SafeFailureText.render(hostile)
        assertFalse(rendered.contains("tok_9f8e7d"), rendered)
        assertEquals("failure (message withheld — may quote file bytes)", rendered)
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
    fun `filesystem failures keep their safe diagnostic text - DR-65 control`() {
        val fs = java.nio.file.NoSuchFileException("/somewhere/auth.json")
        assertTrue(SafeFailureText.render(fs).contains("/somewhere/auth.json"))
    }
}
