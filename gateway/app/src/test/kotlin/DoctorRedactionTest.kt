import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.DoctorRedaction
import splice.app.cli.SafeNames
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
    fun `log lines keep the structure of daemon events only and count the rest`() {
        val lines = listOf(
            "[2026-09-13 10:00:52] [daemon] up: control :3196, heads [openrouter]",
            "user: please remember my secret plan PLANTEXT",
            "[2026-09-13 10:00:53] [daemon] PLANTEXT the user secret plan behind a daemon prefix",
            "[2026-09-13 10:00:53] [daemon] failed PLANTEXT the user said name=Voldemort city=Paris behind a head",
            "[2026-09-13 10:00:53] [claudex][code-mode] abandoned record 9 (outer call_x): history mismatch",
            "    at splice.core.topology.HeadConfig.deserialize(Topology.kt:202)",
            "[2026-09-13 10:00:53] [codex] turn ERROR auth-missing: Authorization: Bearer verysecrettoken",
            "[2026-09-13 10:00:53] [claudex] turn compact=false model=gpt-5.6-sol latency=1200ms ok out=42 tool=false",
            "[2026-09-13 10:00:54] [gateway] perf outcome=ok total=42 session=" + "x".repeat(1000) +
                " api_key=abc client_secret=s3cr3t model=gpt-6-astra compact=false attempts=NaN",
            "[2026-09-13 10:00:55] [mcp-host] context7: hosted as pid 4242",
            "[2026-09-13 10:00:56] [mcp-host] context7: pid 4242 exited (exited with code 3 /home/me/secret)",
            "[2026-09-13 10:00:57] [mcp-host] context7: some prose the child printed",
        )
        val out = redaction.logLines(lines, SafeNames(redaction, null))
        assertEquals(
            listOf(
                "[2026-09-13 10:00:52] [daemon] up: control",
                "[2026-09-13 10:00:53] [daemon] failed",
                "[2026-09-13 10:00:53] [claudex][code-mode] abandoned record",
                "[2026-09-13 10:00:53] [codex] turn ERROR",
                "[2026-09-13 10:00:53] [claudex] turn compact=false model=gpt-5.6-sol",
                "[2026-09-13 10:00:54] [gateway] perf outcome=ok total=42 model=gpt-6-astra compact=false",
                "[2026-09-13 10:00:55] [mcp-host] context7: hosted as pid",
                "[2026-09-13 10:00:56] [mcp-host] context7: pid 4242 exited",
            ),
            out.kept,
            "head plus vocabulary pairs only: prose pairs, a session id, secret-named keys, NaN are gone; " +
                "a turn line keeps compact= and model= (latency is not numeric, ok is not a pair)",
        )
        assertEquals(4, out.dropped, "prose, prefixed prose, a stack frame and a child's prose are not daemon events")
    }

    @Test
    fun `pairs are harvested only from the run that directly follows the event head`() {
        val lines = listOf(
            "[2026-09-13 10:00:53] [codex] turn ERROR upstream-echo model=ProjectApollo status=merger total=123456",
            "[2026-09-13 10:00:54] [gateway] perf outcome=ok total=42 said model=ProjectApollo compact=false",
            "[2026-09-13 10:00:55] [gateway] perf outcome=ok status=429 total=7",
        )
        val out = redaction.logLines(lines, SafeNames(redaction, null))
        assertEquals(
            listOf(
                "[2026-09-13 10:00:53] [codex] turn ERROR",
                "[2026-09-13 10:00:54] [gateway] perf outcome=ok total=42",
                "[2026-09-13 10:00:55] [gateway] perf outcome=ok status=429 total=7",
            ),
            out.kept,
            "free text ends the run; a pair after it is not the daemon's; status is a number only",
        )
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
