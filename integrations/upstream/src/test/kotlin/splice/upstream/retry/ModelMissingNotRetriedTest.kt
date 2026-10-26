// A runtime that does not serve the requested model says so until the operator pulls or loads it, which outlasts
// any retry curve. The turn ends with the runtime's own instruction, and the head's log says why it was not
// retried (spec v0.4.0 section 10: unavailable-model failures).
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RetryNotice
import splice.upstream.transport.PostContext
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.posted

class ModelMissingNotRetriedTest {

    /** [message]: what the turn ends with, read from the runtime's answer. */
    private data class Answered(val message: String, val log: List<String>)

    private suspend fun answeredWith(body: String): Answered {
        val log = mutableListOf<String>()
        val engine = MockEngine { respond(body, HttpStatusCode.NotFound, headersOf()) }
        val ctx = PostContext(
            url = "http://localhost:11434/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = RetryNotice { log += it },
        )
        val ended = assertEnds<UpstreamFailed> { clientOver(engine).posted(ctx, "{}") { "ok" } }
        return Answered(ended.evidence.message, log)
    }

    private fun Answered.saysModelMissing() = log.any { "does not serve the requested model" in it }

    @Test
    fun `Ollama's missing model ends the turn with the runtime's instruction, and the log says why`() = runTest {
        val ollama = """{"error":{"message":"model \"qwen3:8b\" not found, try pulling it first",""" +
            """"type":"api_error","param":null,"code":null}}"""

        val answered = answeredWith(ollama)

        assertTrue("try pulling it first" in answered.message, answered.message)
        assertTrue(answered.saysModelMissing(), "${answered.log}")
    }

    @Test
    fun `vLLM's and OpenAI's missing model are named as missing`() = runTest {
        val vllm = """{"object":"error","message":"The model `qwen3` does not exist.","type":"NotFoundError",""" +
            """"param":null,"code":404}"""
        val openAi = """{"error":{"message":"The model `gpt-x` does not exist or you do not have access to it.",""" +
            """"type":"invalid_request_error","param":null,"code":"model_not_found"}}"""

        assertTrue(answeredWith(vllm).saysModelMissing())
        assertTrue(answeredWith(openAi).saysModelMissing())
    }

    // V4-62 stands for everything else: a 404 that names no missing model is not called one.
    @Test
    fun `a 404 that names no missing model is not called one`() = runTest {
        val answered = answeredWith("""{"error":"Not Found"}""")

        assertFalse(answered.saysModelMissing(), "${answered.log}")
    }
}
