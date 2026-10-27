// A runtime that does not serve the requested model says so until the operator pulls or loads it, which outlasts
// any retry curve. The turn ends at once on the model the answer names, and the head's log says why it was not
// retried (spec v0.4.0 section 10: unavailable-model failures).
//
// Failure-handling slice 3: the head's own reader names the missing model. OpenAI's model_not_found is the shared
// rows' (OpenAiReplies, here); Ollama's "try pulling it first" is the local runtime's (LocalRuntimeReplies, pinned in
// its own module). vLLM's wording with no code is a 404 no table names (final-design.md section 7), so the case that
// pinned the classifier's wording match went with it.
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.upstream.RetryNotice
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.Replies
import splice.upstream.transport.PostContext
import splice.upstream.transport.PostObservers
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.posted

class ModelMissingNotRetriedTest {

    /** [cause]: what the turn ends on; [sends]: how many times the request reached the runtime. */
    private data class Answered(val cause: FailureCause.Provider, val sends: Int, val log: List<String>)

    private suspend fun answeredWith(body: String): Answered {
        val log = mutableListOf<String>()
        val engine = MockEngine { respond(body, HttpStatusCode.NotFound, headersOf()) }
        val ctx = PostContext(
            url = "http://localhost:11434/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(onRetry = RetryNotice { log += it }),
        )
        val ended = assertEnds<UpstreamFailed> { clientOver(engine).posted(ctx, "{}") { "ok" } }
        return Answered(ended.cause, engine.requestHistory.size, log)
    }

    @Test
    fun `OpenAI's missing model ends the turn at once on the model it names, and the log says why`() = runTest {
        val openAi = """{"error":{"message":"The model `gpt-x` does not exist or you do not have access to it.",""" +
            """"type":"invalid_request_error","param":null,"code":"model_not_found"}}"""

        val answered = answeredWith(openAi)

        assertEquals("gpt-x", (answered.cause as? FailureCause.Provider.ModelMissing)?.model, "${answered.cause}")
        assertEquals(1, answered.sends, "a model the runtime does not hold is not there on the next attempt either")
        assertTrue(answered.log.any { "no retry" in it && "gpt-x" in it }, "${answered.log}")
    }

    // V4-62 stands for everything else: a 404 that names no missing model is not called one.
    @Test
    fun `a 404 that names no missing model is not called one`() = runTest {
        val answered = answeredWith("""{"error":"Not Found"}""")

        assertFalse(answered.cause is FailureCause.Provider.ModelMissing, "${answered.cause}")
    }
}
