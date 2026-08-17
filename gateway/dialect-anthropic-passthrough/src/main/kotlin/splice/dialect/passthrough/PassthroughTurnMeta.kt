// NEW: mapping the client request to gateway turn metadata — split out of
// PassthroughRequestBuilder.kt (2026-08-17, concentration campaign). Different output (TurnMeta,
// not a JsonObject), different consumer (splice.spi.BuiltTurn) than JSON-request building. Every
// relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest

internal class PassthroughTurnMeta {

    fun turnMeta(
        typed: AnthropicRequest,
        compact: Boolean,
        originalModel: String,
        upstreamModel: String,
        effort: String,
    ): TurnMeta = TurnMeta(
        compact = compact,
        // Passthrough emits REAL thinking blocks; the text mirror must NOT double-render them,
        // so pick the showReasoning value that makes mirrorInto a no-op (any value != "text").
        showReasoning = ReasoningDisplay.THINKING,
        stream = typed.stream,
        originalModel = originalModel,
        upstreamModel = upstreamModel,
        clientMaxTokens = typed.maxTokens?.takeIf { it > 0 },
        effort = effort,
        summary = null,
        budgetTokens = typed.thinking?.budgetTokens,
    )
}
