import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.DoctorRedaction
import java.nio.file.Paths

class DoctorRedactionTest {
    private val home = Paths.get("/home/operator")
    private val redaction = DoctorRedaction(home)

    @Test
    fun `credential shapes and e-mails are masked and the home directory becomes a tilde`() {
        val line = "[codex] refresh for ops@example.com with Bearer abc.DEF-123 token=sk-live-0123456789ab " +
            "at /home/operator/.config/splice/auth.json jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlX3ZhbHVl"
        val out = redaction.text(line)
        listOf("ops@example.com", "abc.DEF-123", "sk-live-0123456789ab", "eyJhbGci", "/home/operator").forEach {
            assertFalse(out.contains(it), "$it leaked: $out")
        }
        assertTrue(out.contains("~/.config/splice/auth.json"), out)
        assertTrue(out.contains("Bearer <redacted>"), out)
        assertTrue(out.contains("<redacted:email>"), out)
    }

    @Test
    fun `long opaque tokens are masked while ordinary words and ids survive`() {
        val out = redaction.text(
            "session 0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b key AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefghij",
        )
        assertTrue(out.contains("0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"), out)
        assertTrue(out.contains("<redacted:token>"), out)
    }

    @Test
    fun `only the host of a URL survives`() {
        assertEquals("api.example.invalid", redaction.host("https://user:pass@api.example.invalid/v1?key=abc"))
        assertEquals("<unparsable>", redaction.host("not a url"))
    }

    @Test
    fun `log lines keep the daemon shape only, redacted and capped`() {
        val lines = listOf(
            "[2026-09-13 10:00:52] [daemon] up: control :3196, heads [openrouter]",
            "user: please remember my secret plan PLANTEXT",
            "[2026-09-13 10:00:53] [codex] Authorization: Bearer verysecrettoken",
            "[2026-09-13 10:00:54] [gateway] " + "x".repeat(1000),
        )
        val out = redaction.logLines(lines)
        assertEquals(3, out.size)
        assertFalse(out.any { it.contains("PLANTEXT") })
        assertFalse(out.any { it.contains("verysecrettoken") })
        assertTrue(out.all { it.length <= 400 })
    }
}
