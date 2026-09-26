// NEW: V4-274 — after a resume on another head, the status line reads the new head's context, never
// the old head's last turn. Claude Code fills `context_window.current_usage` from the newest message
// of the transcript it loaded, whoever answered it, and a resume keeps the session id: claudex drew
// '87k/272k · 32%' right after `claudex -r` of a claude-splice session, and '20k/272k · 8%' after its
// own first turn (take-resume-3, 2026-09-26). Every post here goes through the real route, with each
// head's own per-session turns behind it.
package splice.usage.statusline

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.PerfModelTotal
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTotal
import splice.core.perf.PerfSessionTurn
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.HeadSessionPerfSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

private const val SESSION = "5f0c2a9e-7a41-4c1e-9b0d-274274274274"
private val ANSI = Regex("\u001B\\[[0-9;]*m")

/** One head's perf rows by session; [total] stands in for the running total a head keeps, which still
 *  counts a session whose rows have left the tail. */
private class HeadTurns : HeadPerfSource, HeadSessionPerfSource {
    private val turns = HashMap<String, MutableList<PerfSessionTurn>>()
    var total: PerfSessionTotal? = null

    fun answer() {
        turns.getOrPut(SESSION) { mutableListOf() } += PerfSessionTurn("gpt-6-sol", emptyMap())
    }

    override fun tailNumeric(n: Int): List<Map<String, Long>> = emptyList()

    override fun sessionTail(sessionId: String): PerfSessionTail =
        PerfSessionTail(turns[sessionId].orEmpty().toList(), null)

    override fun sessionTotal(sessionId: String): PerfSessionTotal? = total.takeIf { sessionId == SESSION }
}

class StatuslineResumeUsageTest {
    @TempDir
    lateinit var tmp: Path

    private fun head(key: String, perf: HeadPerfSource?) = UsageHead(
        key = key,
        label = key,
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warnPct = 80,
        warnTokens5h = 0,
        perf = perf,
    )

    private fun route(vararg heads: UsageHead) = StatuslineRoute(
        UsageHeadLookup { name -> heads.filter { it.key == name } },
        ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
    )

    private fun ApplicationTestBuilder.serve(route: StatuslineRoute) {
        application { routing { post("/statusline/{head}") { route.statusline(call) } } }
    }

    /** A 272k post whose last assistant usage is [usedK] thousand tokens, 98% of it a cache read. */
    private fun post(usedK: Int): String {
        val used = usedK * 1_000
        val pct = used * 100 / 272_000
        return """{"session_id":"$SESSION","model":{"id":"gpt-6-sol","display_name":"GPT-6 Sol"},""" +
            """"context_window":{"context_window_size":272000,"used_percentage":$pct,"current_usage":""" +
            """{"input_tokens":${used / 50},"cache_read_input_tokens":${used - used / 50},""" +
            """"cache_creation_input_tokens":0,"output_tokens":500}}}"""
    }

    private suspend fun ApplicationTestBuilder.line(head: String, body: String): String =
        client.post("/statusline/$head") { setBody(body) }.bodyAsText().replace(ANSI, "")

    @Test
    fun `a session resumed from another head draws no context until the new head answers`() = testApplication {
        val claudex = HeadTurns()
        serve(route(head("claudex", claudex)))

        val resumed = line("claudex", post(87))
        assertFalse("87k" in resumed, "the old head's last turn is not this head's context: $resumed")
        assertFalse("31%" in resumed, "nor its share of this head's window: $resumed")
        assertFalse("⚡" in resumed, "nor its cache hit: $resumed")
        assertTrue("GPT-6 Sol" in resumed, "the rest of the line still draws: $resumed")

        claudex.answer()
        val answered = line("claudex", post(20))
        assertTrue("20k/272k · 7%" in answered, "the new head's own turn is its context: $answered")
        assertTrue("⚡ 98%" in answered, answered)
    }

    @Test
    fun `a session that left this head and came back draws nothing until this head answers again`() =
        testApplication {
            val splice = HeadTurns()
            val claudex = HeadTurns()
            serve(route(head("claude-splice", splice), head("claudex", claudex)))
            splice.answer()

            assertTrue("70k/272k" in line("claude-splice", post(70)), "a head that answered draws its usage")
            assertFalse("70k" in line("claudex", post(70)), "resumed on claudex, which has not answered")
            claudex.answer()
            assertTrue("90k/272k" in line("claudex", post(90)), "claudex answered")

            val back = line("claude-splice", post(90))
            assertFalse("90k" in back, "back on claude-splice, whose turns all predate claudex's: $back")
            splice.answer()
            assertTrue("95k/272k" in line("claude-splice", post(95)), "claude-splice answered again")
        }

    @Test
    fun `a session whose rows left the tail is answered by its running total`() = testApplication {
        val claudex = HeadTurns()
        claudex.total = PerfSessionTotal(0L, mapOf("gpt-6-sol" to PerfModelTotal(3, 0, 0, 0, 0, 0.0, 3)))
        serve(route(head("claudex", claudex)))

        assertTrue("87k/272k · 31%" in line("claudex", post(87)), "the head counted the session's turns")
    }

    @Test
    fun `a fresh session with no usage yet still reads zero`() = testApplication {
        serve(route(head("claudex", HeadTurns())))
        val fresh = """{"session_id":"$SESSION","model":{"id":"gpt-6-sol"},""" +
            """"context_window":{"context_window_size":272000,"used_percentage":0,"current_usage":null}}"""

        val drawn = line("claudex", fresh)
        assertTrue("0/272k · 0%" in drawn, "no usage is no other head's usage: $drawn")
    }

    @Test
    fun `a head that cannot read its turns per session draws the post as before`() = testApplication {
        serve(route(head("claudex", null)))

        assertTrue("87k/272k · 31%" in line("claudex", post(87)), "nothing to judge by, so nothing withheld")
    }
}
