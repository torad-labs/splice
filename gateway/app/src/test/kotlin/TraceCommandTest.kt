// NEW: V4-174 — `splice trace <head>`: reads the head's day files with no daemon, groups records
// by turn, and prints a table, one turn in full, or the raw lines; `--purge` deletes the files and
// says what went. The files here are written by the SAME TraceStore the daemon uses, so the verb
// is tested against the writer's real shape rather than a hand-typed fixture.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.trace.TraceCommand
import splice.app.cli.trace.TraceOpts
import splice.core.activity.ActivityDays
import splice.core.config.StatePaths
import splice.core.perf.PerfSnapshot
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.gateway.wire.ClientInbound
import splice.gateway.wire.TraceStore
import splice.gateway.wire.TurnIdMint
import splice.spi.WireAttempt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

class TraceCommandTest {

    /** The starter topology (head `openrouter`) and a state dir under [tmp]. */
    private fun env(tmp: Path): EnvReader {
        TopologyLoader.loadOrMaterialize(tmp.resolve("splice.toml"))
        return EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
                "CLAUDEX_STATE_DIR" -> tmp.resolve("state").toString()
                else -> null
            }
        }
    }

    private fun meta(session: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-openrouter--m1",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = session,
    )

    /** Two turns as the daemon would write them: ids turn-1 and turn-2, sessions alpha and beta. */
    private fun writeTrace(env: EnvReader) {
        val ids = ArrayDeque(listOf("turn-1", "turn-2"))
        val store = TraceStore(
            ActivityDays(StatePaths(envReader = env).traceDir, "openrouter", 7, WallClock { DAY_ONE }, true),
            "openrouter",
            maxBodyChars = 1 shl 20,
            now = WallClock { DAY_ONE },
            ids = TurnIdMint { ids.removeFirst() },
        )
        listOf("alpha-session", "beta-session").forEachIndexed { n, session ->
            val trace = store.begin(
                meta(session),
                ClientInbound("POST", "/v1/messages", mapOf("authorization" to "[redacted]"), """{"client":$n}"""),
            )
            trace.responseText("data: {\"n\":$n}\n\n")
            trace.attempted(
                WireAttempt(
                    attempt = 1,
                    url = "https://openrouter.ai/api/v1/chat/completions",
                    requestHeaders = mapOf("Authorization" to "[redacted]", "content-type" to "application/json"),
                    requestBody = """{"upstream":$n}""",
                    requestEncoding = null,
                    status = 200,
                    responseHeaders = mapOf("x-request-id" to "r$n"),
                    errorText = null,
                    failure = null,
                    durationMs = 40,
                ),
            )
            trace.clientFrame("event: message_start\ndata: {}\n\n")
            trace.finish("ok", PerfSnapshot(mapOf("total" to 120L + n), mapOf("in_tokens" to 3L)))
        }
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
    }

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
    fun `the table lists every turn on disk, oldest first, with its outcome and counts`(@TempDir tmp: Path) {
        val env = env(tmp)
        writeTrace(env)

        val (ok, out, _) = capture { TraceCommand().trace(listOf("openrouter"), env) }

        assertTrue(ok)
        assertTrue(out.contains("2 of 2 turn(s) on disk"), out)
        val lines = out.lines().filter { it.contains("turn-") }
        assertEquals(2, lines.size, out)
        assertTrue(lines[0].contains("turn-1") && lines[0].contains("session=alpha-se"), lines[0])
        assertTrue(lines[0].contains("  ok  rounds=1 attempts=1  total=120ms"), lines[0])
        assertTrue(lines[1].contains("turn-2") && lines[1].contains("total=121ms"), lines[1])
        assertFalse(out.contains("{\"upstream\""), "bodies are not in the table")
    }

    @Test
    fun `--turn prints that turn's records in full - bodies verbatim, headers as redacted`(@TempDir tmp: Path) {
        val env = env(tmp)
        writeTrace(env)

        val (ok, out, _) = capture { TraceCommand().trace(listOf("openrouter", "--turn", "turn-2"), env) }

        assertTrue(ok)
        assertTrue(out.contains("upstream attempt 1"), out)
        assertTrue(out.contains("https://openrouter.ai/api/v1/chat/completions"), out)
        assertTrue(out.contains("  Authorization: [redacted]"), out)
        assertTrue(out.contains("""{"upstream":1}"""), out)
        assertTrue(out.contains("data: {\"n\":1}"), out)
        assertTrue(out.contains("client request") && out.contains("  POST /v1/messages"), out)
        assertTrue(out.contains("""{"client":1}"""), out)
        assertTrue(out.contains("event: message_start"), out)
        assertTrue(out.contains("── outcome") && out.contains("  ok  "), out)
        assertFalse(out.contains("""{"upstream":0}"""), "the other turn is not printed")
    }

    @Test
    fun `--session narrows to that session's turns, --json prints the raw records`(@TempDir tmp: Path) {
        val env = env(tmp)
        writeTrace(env)

        val (ok, out, _) = capture { TraceCommand().trace(listOf("openrouter", "--session", "beta", "--json"), env) }

        assertTrue(ok)
        val records = out.lines().filter { it.isNotBlank() }
        assertEquals(2, records.size, out)
        assertTrue(records.all { it.startsWith("{\"kind\":") && it.contains("\"turn\":\"turn-2\"") }, out)
    }

    @Test
    fun `a turn that is not there, and a head that is not configured, each fail in words`(@TempDir tmp: Path) {
        val env = env(tmp)
        writeTrace(env)

        val (okTurn, _, errTurn) = capture { TraceCommand().trace(listOf("openrouter", "--turn", "turn-9"), env) }
        assertFalse(okTurn)
        assertTrue(errTurn.contains("no turn turn-9"), errTurn)

        val (okHead, _, errHead) = capture { TraceCommand().trace(listOf("nope"), env) }
        assertFalse(okHead)
        assertTrue(errHead.contains("no head named 'nope'") && errHead.contains("openrouter"), errHead)
    }

    @Test
    fun `an untraced head prints an empty table with the knob to set`(@TempDir tmp: Path) {
        val env = env(tmp)

        val (ok, out, _) = capture { TraceCommand().trace(listOf("openrouter"), env) }

        assertTrue(ok)
        assertTrue(out.contains("0 of 0 turn(s)"), out)
        assertTrue(out.contains("[heads.openrouter.overrides] trace = true"), out)
    }

    @Test
    fun `--purge deletes the head's day files and names them, and a second purge has nothing`(@TempDir tmp: Path) {
        val env = env(tmp)
        writeTrace(env)
        val dayFile = StatePaths(envReader = env).traceDir.resolve("openrouter-2026-09-18.jsonl")
        assertTrue(Files.exists(dayFile))

        val (ok, out, _) = capture { TraceCommand().trace(listOf("openrouter", "--purge"), env) }

        assertTrue(ok)
        assertTrue(out.contains("purged 1 day file(s) of openrouter"), out)
        assertTrue(out.contains(dayFile.toString()), out)
        assertFalse(Files.exists(dayFile))
        val (_, again, _) = capture { TraceCommand().trace(listOf("openrouter", "--purge"), env) }
        assertTrue(again.contains("nothing to purge"), again)
    }

    @Test
    fun `argument parsing`() {
        val command = TraceCommand()
        assertEquals(TraceOpts("kimi"), command.parseTraceArgs(listOf("kimi")))
        assertEquals(
            TraceOpts("kimi", last = 3, session = "s1", turn = "t1", json = true, purge = true),
            command.parseTraceArgs(
                listOf("--json", "kimi", "--last", "3", "--session", "s1", "--turn", "t1", "--purge"),
            ),
        )
        assertNull(command.parseTraceArgs(emptyList()), "the head is required")
        assertNull(command.parseTraceArgs(listOf("kimi", "--last")), "--last needs a count")
        assertNull(command.parseTraceArgs(listOf("kimi", "--last", "0")), "a count is positive")
        assertNull(command.parseTraceArgs(listOf("kimi", "--turn")), "--turn needs an id")
        assertNull(command.parseTraceArgs(listOf("kimi", "extra")), "one head only")
        assertNull(command.parseTraceArgs(listOf("kimi", "--follow")), "no unknown flags")
    }
}
