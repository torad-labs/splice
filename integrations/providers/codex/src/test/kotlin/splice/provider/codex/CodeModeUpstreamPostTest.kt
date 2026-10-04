// NEW: the post code mode is handed keeps the turn's perf record, hands a tree only to a post that declares it
// takes one, and never skips a wrapper's own text override.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeUpstreamPosts
import splice.upstream.InterceptedRoundPost
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyPost
import splice.upstream.sse.WireSink

internal class CodeModeUpstreamPostTest : CodeModeBridgeTestSupport() {
    private val wire = CodexCodeModeWire(Json) {}
    private val record = TurnPerf()
    private val done = TurnOutcome.Success(false, false, Usage())
    private val tree = Json.parseToJsonElement("""{"input":[{"role":"user","content":"go"}]}""").jsonObject

    /** A turn's post that takes trees, and records which form each round reached it in. */
    private inner class TreePost : RedirectableRoundPost, RoundBodyPost {
        val seen = mutableListOf<Any>()
        override val perf: TurnPerf = record
        override suspend fun invoke(bodyJson: String): TurnOutcome = done.also { seen += bodyJson }
        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = done.also { seen += bodyJson }
        override suspend fun post(body: RoundBody): TurnOutcome = done.also { seen += body }
        override suspend fun postInto(body: RoundBody, sink: WireSink): TurnOutcome = done.also { seen += body }
    }

    @Test
    fun `a redirectable round post keeps its perf record through the code-mode wrapper`() {
        val wrapped = CodeModeUpstreamPosts.of(TreePost(), wire)

        assertTrue(wrapped is CodeModeRedirectablePost, "a redirectable post stays redirectable")
        assertSame(record, wrapped.perf)
    }

    @Test
    fun `a plain round post keeps its perf record and gains no redirect`() {
        val post = object : InterceptedRoundPost {
            override val perf: TurnPerf = record
            override suspend fun invoke(bodyJson: String): TurnOutcome = done
        }
        val wrapped = CodeModeUpstreamPosts.of(post, wire)

        assertSame(record, wrapped.perf)
        assertFalse(wrapped is CodeModeRedirectablePost, "a plain post must not be offered as redirectable")
    }

    @Test
    fun `a post that takes trees is handed the round's own tree, both ways it posts`() = runTest {
        val post = TreePost()
        val body = wire.body(RoundBody.Tree(tree))
        val wrapped = CodeModeUpstreamPosts.of(post, wire) as CodeModeRedirectablePost

        val _ = wrapped(body)
        val _ = wrapped.into(body, RecordingSink())

        assertEquals(listOf(body.round, body.round), post.seen)
    }

    /** Kotlin's `by` forwards every member the wrapper does not write itself. A tree-taking overload would be
     *  forwarded past this wrapper's ending; text keeps it on the path, which is what the wrapper means. */
    @Test
    fun `a wrapper built by delegation keeps its own text override`() = runTest {
        val inner = TreePost()
        val ending = TurnOutcome.Success(false, false, Usage(outputTokens = 7))
        val wrapper = object : RedirectableRoundPost by inner {
            override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = ending
        }
        val wrapped = CodeModeUpstreamPosts.of(wrapper, wire) as CodeModeRedirectablePost

        assertSame(ending, wrapped.into(wire.body(RoundBody.Tree(tree)), RecordingSink()))
        assertTrue(inner.seen.isEmpty(), "the wrapper's own override answered: ${inner.seen}")
    }
}
