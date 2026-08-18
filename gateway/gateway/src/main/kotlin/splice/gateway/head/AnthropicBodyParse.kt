// NEW: the one body-parse expression both request handlers share (HD-24). prepareTurn and
// handleCountTokens each spelled out
// `Cancellables.runCatchingCancellable { AnthropicParse.parseAnthropicBody(text) }.getOrNull()`
// and turned the null into a client 400 — the same call, the same null-on-failure contract, in the
// only two places a raw body becomes a typed turn. Naming it also keeps splice.core.parse and
// splice.core.util out of both handlers.
package splice.gateway.head

import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.core.util.Cancellables

internal class AnthropicBodyParse {
    /** Null when the body is not a parseable Anthropic request — a client 400 for both handlers,
     *  never a crash. Cancellation is not caught (runCatchingCancellable rethrows it). */
    fun parseOrNull(text: String): AnthropicTurnBody? =
        Cancellables.runCatchingCancellable { AnthropicParse.parseAnthropicBody(text) }.getOrNull()
}
