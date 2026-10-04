// NEW: the post code mode is handed keeps the turn's perf record, so code mode's local work counts on the turn.
package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.upstream.InterceptedRoundPost
import splice.upstream.RedirectableRoundPost
import splice.upstream.sse.WireSink

internal class CodeModeUpstreamPostTest {
    private val wire = CodexCodeModeWire(Json) {}
    private val record = TurnPerf()
    private val done = TurnOutcome.Success(false, false, Usage())

    @Test
    fun `a redirectable round post keeps its perf record through the code-mode wrapper`() {
        val post = object : RedirectableRoundPost {
            override val perf: TurnPerf = record
            override suspend fun invoke(bodyJson: String): TurnOutcome = done
            override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = done
        }

        assertSame(record, CodeModeRedirectablePost(post, wire).perf)
    }

    @Test
    fun `a plain round post keeps its perf record and gains no redirect`() {
        val post = object : InterceptedRoundPost {
            override val perf: TurnPerf = record
            override suspend fun invoke(bodyJson: String): TurnOutcome = done
        }
        val wrapped: InterceptedRoundPost = CodeModeUpstreamPost(post, wire)

        assertSame(record, wrapped.perf)
        assertFalse(wrapped is RedirectableRoundPost, "a plain post must not be offered as redirectable")
    }
}
