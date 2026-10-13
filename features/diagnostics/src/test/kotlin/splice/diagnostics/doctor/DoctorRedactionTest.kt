package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.diagnostics.doctor.report.DoctorRedaction
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
    fun `a JSON-quoted credential key masks its short quoted value and keeps the structure`() {
        val body = """{"refresh_token":"1//0eXk3-abcDEF_ghij","api_key": "AIzaSyA-short-one","note":"kept"}"""
        val out = redaction.text(body)
        listOf("1//0eXk3-abcDEF_ghij", "AIzaSyA-short-one").forEach {
            assertFalse(out.contains(it), "$it leaked: $out")
        }
        assertTrue(out.contains(""""refresh_token":"<redacted>""""), out)
        assertTrue(out.contains(""""api_key": "<redacted>""""), out)
        assertTrue(out.contains(""""note":"kept"}"""), "the value after the secret survives: $out")
        assertEquals("token=<redacted> next", redaction.text("token=abc,def next"), "unquoted: masked to the space")
    }

    @Test
    fun `long opaque tokens and UUID-shaped ids are masked while ordinary words survive`() {
        val out = redaction.text(
            "session 0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b key AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefghij done",
        )
        assertFalse(out.contains("0f1e2d3c"), "a session/account id is an identifier: $out")
        assertTrue(out.contains("session <redacted:id> key <redacted:token> done"), out)
    }

    @Test
    fun `only splice's own and system paths survive, everything else is masked whole`() {
        val out = redaction.text(
            "cwd /home/operator/projects/private-repo state ~/.local/share/splice/codex-perf.jsonl " +
                "cfg /home/operator/.config/splice/splice.toml bin /usr/local/bin/claude claude ~/.claude/sessions " +
                "tmp /tmp/private-customer-checkout route /api/usage other /srv/customer-data/x.csv root / home ~",
        )
        listOf("private-repo", "customer-data", "customer-checkout").forEach {
            assertFalse(out.contains(it), "$it leaked: $out")
        }
        listOf(
            // A foreign path takes the words after it (a name with spaces has no visible end); the
            // next path start or delimiter ends the value, so an allowed path after it survives.
            "cwd <redacted:path> ~/.local/share/splice/codex-perf.jsonl",
            "cfg ~/.config/splice/splice.toml bin /usr/local/bin/claude claude ~/.claude/sessions",
            "tmp <redacted:path> /api/usage other <redacted:path> / home ~",
        ).forEach { assertTrue(out.contains(it), "$it expected in: $out") }
    }

    @Test
    fun `bare short opaque keys never survive text or structured log output`() {
        val key = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        assertEquals(32, key.length)
        assertFalse(redaction.text("connection failed $key").contains(key))
        val log = "[2026-09-13 10:00:53.000] [$key] turn compact=false model=$key"
        assertFalse(redaction.text(log).contains(key), redaction.text(log))
    }

    @Test
    fun `only the host of a URL survives`() {
        assertEquals("api.example.invalid", redaction.host("https://user:pass@api.example.invalid/v1?key=abc"))
        assertEquals("<unparsable>", redaction.host("not a url"))
        assertEquals(
            "<redacted:id>.tenant.example.invalid",
            redaction.host("https://0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b.tenant.example.invalid/v1"),
            "a UUID-shaped label is an identifier wherever it rides",
        )
        val keyed = redaction.host("https://AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefghij.example.invalid/v1")
        assertFalse(keyed.contains("AbCdEfGh"), keyed)
    }

    @Test
    fun `a foreign path with spaces is masked whole, an allowed path after it survives`() {
        val out = redaction.text(
            "cwd /home/operator/projects/Secret Merger/client state ~/.local/share/splice/x " +
                "next /srv/Secret Merger Plan/client done",
        )
        assertFalse(out.contains("Merger"), out)
        assertEquals("cwd <redacted:path> ~/.local/share/splice/x next <redacted:path>", out)
        val leaf = redaction.text("cwd /home/operator/projects/Secret Merger; then 'x'")
        assertEquals("cwd <redacted:path>; then 'x'", leaf, "a leaf name with spaces and no slash after it")
        val quoted = redaction.text("at '/srv/Secret Merger' is not linked")
        assertEquals("at '<redacted:path>' is not linked", quoted, "a quote ends the value")
    }
}
