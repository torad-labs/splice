// NEW: the construction bag for ResponsesTurnSeams. Split so the collaborator
// stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.util.LogSink
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.FoldConfig
import splice.upstream.ToolNameShortener

/** What the seams carry from one round to the next: intra-turn fold, and transcript replay of reasoning. */
internal data class RoundCarry(
    val foldConfig: FoldConfig?,
    val replayReasoning: Boolean,
)

/** The two collaborators the seams hand the stream translator beside its turn context. */
internal data class TranslatorServices(
    val toolNames: ToolNameShortener = ToolNameShortener(),
    val log: LogSink = LogSink { },
)

/** Everything stream/fold/reanchor construction reads from the provider. */
internal data class ResponsesTurnSeamsDeps(
    val quirks: ResponsesQuirks,
    val continuity: ReasoningContinuity,
    val summaryParts: ConversationSummaryParts,
    val turnOptions: ResponsesTurnOptions,
    val carry: RoundCarry,
    val caps: WatchdogCaps,
    val services: TranslatorServices = TranslatorServices(),
)
