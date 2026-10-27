// NEW: failure-handling slice 4. The ChatGPT backend cuts the reasoning of three models short at 518n-2 tokens and
// finishes the round on the shallower answer it had, and codex's own reading names that round a ReasoningCut, which the
// engine continues (final-design.md sections 8 and 12). Held over generated completed rounds: a round is cut exactly
// when one of those models stopped reasoning at 518n-2 tokens with n at most 6, and every other round, any other
// model's included, is a round finished whole. The rule is the goal's own; the test restates it as its model.
package splice.provider.codex.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.turn.Usage
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.failure.Replies
import kotlin.random.Random

private const val RUNS = 5_000

/** The three models the backend cuts, and the model it does not (gpt-5.6-sol, probed 2026-07-19), beside others. */
private val CUT_MODELS = setOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.5")
private val OTHER_MODELS = listOf("gpt-5.6-sol", "gpt-5.4", "gpt-5.5-mini", "scripted-model", "")

/** The period of the cut and how far below each multiple of it the backend stops. */
private const val PERIOD = 518L
private const val BELOW = 2L

/** The highest n of 518n-2 a cut is read at; a count past it is a long reasoning that happened to land there. */
private const val HIGHEST_TIER = 6L

/** The goal's rule, as the model the reading is held to. */
private fun cutBy(model: String, tokens: Long): Boolean =
    model in CUT_MODELS && tokens > 0 && (tokens + BELOW) % PERIOD == 0L && (tokens + BELOW) / PERIOD <= HIGHEST_TIER

/** Reasoning counts on the fingerprint, beside it, and anywhere, for tiers past the bound as well as within it. */
private fun tokens(random: Random): Long {
    val onFingerprint = PERIOD * random.nextLong(1, HIGHEST_TIER + 3) - BELOW
    return when (random.nextInt(3)) {
        0 -> onFingerprint
        1 -> onFingerprint + random.nextLong(-3, 4)
        else -> random.nextLong(0, PERIOD * (HIGHEST_TIER + 2))
    }
}

class CodexReasoningCutPropertyTest {
    @Test
    fun `a round is cut exactly when a model the backend cuts stopped reasoning at 518n-2 tokens, n at most 6`() {
        val random = Random(PERIOD)
        val models = CUT_MODELS.toList() + OTHER_MODELS
        repeat(RUNS) {
            val model = models.random(random)
            val reasoning = tokens(random)
            val read = CodexReplies.completed(model, Usage(outputTokens = reasoning + 40, reasoningTokens = reasoning))
            if (cutBy(model, reasoning)) {
                assertEquals(FailureCause.Provider.ReasoningCut(reasoning), read, "$model stopped at $reasoning")
            } else {
                assertNull(read, "$model finished whole at $reasoning")
            }
        }
    }

    @Test
    fun `a codex head reads the cut through its replies, and a Responses head with none reads each round whole`() {
        val codex = Replies(ResponsesReplies, CodexReplies)
        val standard = Replies(ResponsesReplies)
        for (model in CUT_MODELS) {
            for (tier in 1..HIGHEST_TIER) {
                val reasoning = PERIOD * tier - BELOW
                val usage = Usage(reasoningTokens = reasoning)
                assertEquals(FailureCause.Provider.ReasoningCut(reasoning), codex.completed(model, usage), "$model")
                assertNull(standard.completed(model, usage), "the standard names no cut: $model at $reasoning")
            }
        }
    }
}
