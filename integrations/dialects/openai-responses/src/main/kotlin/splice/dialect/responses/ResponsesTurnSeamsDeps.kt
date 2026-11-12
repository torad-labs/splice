// NEW: the construction bag for ResponsesTurnSeams. Split so the collaborator
// stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.failure.LoginOwner
import splice.core.util.LogSink
import splice.dialect.responses.failure.ResponsesReplies
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.FoldConfig
import splice.upstream.ToolNameShortener
import splice.upstream.failure.Replies

/** What the seams carry from one round to the next: the intra-turn fold, as its provider declared it. */
internal data class RoundCarry(
    val fold: FoldConfig?,
)

/** The collaborators the seams hand the stream translator beside its turn context: the tool aliases, the log, and
 *  the head's own reading of a failure its backend sends in band. */
internal data class TranslatorServices(
    val toolNames: ToolNameShortener = ToolNameShortener(),
    val log: LogSink = LogSink { },
    val reading: FailureReading = FailureReading(),
)

/** How a round reads a failure its backend sent in band (failure-handling slice 3): the head's own [replies], the
 *  Responses standard with the deviation its vendor declared, and whose login the turn sends. A translator built with
 *  no head, as a test builds one, reads by the standard alone. */
internal data class FailureReading(
    val replies: Replies = Replies(ResponsesReplies),
    val owner: LoginOwner = LoginOwner.SPLICE,
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
