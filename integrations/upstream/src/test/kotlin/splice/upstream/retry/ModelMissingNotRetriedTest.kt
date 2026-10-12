// A runtime that does not serve the requested model says so until the operator pulls or loads it, which outlasts
// any retry curve. The turn ends on the first answer and carries the runtime's own instruction to the client
// (spec v0.4.0 section 10: unavailable-model failures).
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.postOnce
import java.util.concurrent.atomic.AtomicInteger

class ModelMissingNotRetriedTest {

    private data class Answered(val sends: Int, val body: String)

    private suspend fun answeredWith(body: String): Answered {
        val sends = AtomicInteger()
        val engine = MockEngine {
            sends.incrementAndGet()
            respond(body, HttpStatusCode.NotFound, headersOf())
        }
        val ended = assertEnds<UpstreamFailed> { postOnce(clientOver(engine)) }
        return Answered(sends.get(), ended.body)
    }

    @Test
    fun `Ollama's missing model ends the turn on its first answer, with the runtime's instruction`() = runTest {
        val ollama = """{"error":{"message":"model \"qwen3:8b\" not found, try pulling it first",""" +
            """"type":"api_error","param":null,"code":null}}"""

        val answered = answeredWith(ollama)

        assertEquals(1, answered.sends)
        assertTrue("try pulling it first" in answered.body, answered.body)
    }

    @Test
    fun `vLLM's and OpenAI's missing model end the turn on the first answer`() = runTest {
        val vllm = """{"object":"error","message":"The model `qwen3` does not exist.","type":"NotFoundError",""" +
            """"param":null,"code":404}"""
        val openAi = """{"error":{"message":"The model `gpt-x` does not exist or you do not have access to it.",""" +
            """"type":"invalid_request_error","param":null,"code":"model_not_found"}}"""

        assertEquals(1, answeredWith(vllm).sends)
        assertEquals(1, answeredWith(openAi).sends)
    }

    // V4-62 stands for everything else: a 404 that names no missing model still takes the curve.
    @Test
    fun `a 404 that names no missing model is still retried`() = runTest {
        assertTrue(answeredWith("""{"error":"Not Found"}""").sends > 1)
    }
}
