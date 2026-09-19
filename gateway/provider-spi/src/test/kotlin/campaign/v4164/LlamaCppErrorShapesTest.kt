// NEW: V4-164 — llama-server's three error shapes, each NAMED, through the one classifier every
// transport shares. The failure this row repairs was a Claude Code banner reading "API error" for a
// model that was merely still loading, and two quieter siblings: a context overflow the overflow
// rule could not see (so the client never compacted), and a full KV pool that arrived as bare text.
// Every body below is llama.cpp's own shape (format_error_response + server_task_result_error).
package campaign.v4164

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier

class LlamaCppErrorShapesTest {

    // Claude Code 2.1.276's own overflow parse, quoted from its bundle: the two numbers it reads out
    // of the line. A message that carries the phrase without them still compacts, blind.
    private val clientOverflowParse = Regex("prompt is too long[^0-9]*(\\d+)\\s*tokens?\\s*>\\s*(\\d+)")

    // Mutant: explain() returns the vendor text as-is. The 503 is still retried, but the banner the
    // client prints from the third attempt reads "Loading model" with nothing saying whose model.
    @Test
    fun `a model still loading is named, and stays a retryable server condition`() {
        val f = UpstreamFailureClassifier.classify(
            FailureSource.HTTP,
            """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}""",
            503,
        )

        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.transient)
        assertEquals(FailureCause.UPSTREAM_STATUS_5XX, f.cause)
        assertTrue(f.message.startsWith("Loading model — the local model server has not finished loading"), f.message)
    }

    // Mutant: drop the counts. The phrase survives, so the client still compacts, but it can no
    // longer say by how much the conversation overran.
    @Test
    fun `a context overflow carries the line and the numbers Claude Code compacts on`() {
        val f = UpstreamFailureClassifier.classify(
            FailureSource.HTTP,
            """{"error":{"code":400,"message":"the request exceeds the available context size, try increasing it",""" +
                """"type":"exceed_context_size_error","n_prompt_tokens":270000,"n_ctx":262144}}""",
            400,
        )

        assertEquals(ErrorType.INVALID_REQUEST, f.type)
        assertEquals(FailureCause.REQUEST_TOO_LARGE, f.cause)
        assertEquals(listOf("270000", "262144"), clientOverflowParse.find(f.message)?.groupValues?.drop(1), f.message)
    }

    // Mutant: send the decode literal through the overflow rule. The request is not what is too big
    // — the other conversations on the server hold the rest of the pool — so "prompt is too long"
    // would compact a conversation that fits and throw away the retry that heals this.
    @Test
    fun `a full KV pool mid-decode is named and retried, never reported as an overflow`() {
        val f = UpstreamFailureClassifier.classify(
            FailureSource.SSE,
            "Context size has been exceeded.",
            500,
            "server_error",
        )

        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.transient)
        assertEquals(FailureCause.UPSTREAM_STATUS_5XX, f.cause)
        assertTrue(f.message.contains("KV cache is full"), f.message)
        assertFalse(f.message.contains("prompt is too long"), f.message)
    }

    // The rewrite keys on llama.cpp's structured types and its one exact literal, so every other
    // vendor's text — including a near-miss of that literal — reaches the client byte-identical.
    @Test
    fun `every other vendor's text comes back unchanged`() {
        val openAi = """{"error":{"message":"The server had an error while processing your request.","type":"server_error"}}"""

        assertEquals(
            "The server had an error while processing your request.",
            UpstreamFailureClassifier.classify(FailureSource.HTTP, openAi, 500).message,
        )
        assertEquals(
            "Context size has been exceeded somewhere else",
            UpstreamFailureClassifier.classify(
                FailureSource.SSE,
                "Context size has been exceeded somewhere else",
            ).message,
        )
    }
}
