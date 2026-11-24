// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). One turn's ledger row, from what is known of it once
// it ended: its head's own word on a Messages turn, the dialect's reading of an OpenAI-style client's answer, and for
// a turn refused before either, only how it was answered. Tokens are the provider's own counts, never the ones a
// client is shown for its window; a turn that billed nothing reads zero tokens and no cost.
package splice.server.ledger

import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.server.TurnSpeed
import splice.core.wire.HttpStatus
import java.time.Instant

/** What was heard of a turn's answer, from its head or from the chat dialect's reading of it. */
private data class Heard(
    val model: String?,
    val tokens: ServedTokens?,
    val speed: TurnSpeed?,
    val ttftMs: Long?,
    val upstream: String?,
    val costUsd: Double?,
)

/** How a turn ended: the [status] it was answered with, how long it took, and what its head said of it. */
internal data class LedgerEnd(val status: Int, val durationMs: Long, val served: ServedTurn?)

internal class LedgerRows(private val parts: LedgerParts) {

    fun of(turn: LedgerTurn, end: LedgerEnd): LedgerRow {
        val heard = end.served?.let(::fromHead) ?: fromChat(turn)
        val ending = ending(turn, end)
        val tokens = heard.tokens
        val person = turn.key.person
        return LedgerRow(
            at = Instant.ofEpochMilli(turn.startedMs).toString(),
            person = person,
            team = turn.team,
            device = turn.key.prefix,
            command = turn.command.name,
            model = heard.model,
            route = turn.route,
            upstream = heard.upstream ?: turn.way?.served(),
            status = ending.status,
            outcome = ending.outcome.wire,
            promptTokens = tokens?.prompt ?: 0,
            cachedTokens = tokens?.cached ?: 0,
            completionTokens = tokens?.completion ?: 0,
            ttftMs = heard.ttftMs,
            durationMs = end.durationMs,
            prefillTps = heard.speed?.prefillTps,
            decodeTps = heard.speed?.decodeTps,
            costUsd = heard.costUsd,
        )
    }

    /** How [turn] ended: splice's own failure above all, then a client that left, a gateway timeout, its head's word,
     *  the dialect's reading, or its status. A timeout outranks the head's word: the relay that ended a silent stream
     *  hung up on the head, which then reads its own client as gone. */
    private fun ending(turn: LedgerTurn, end: LedgerEnd): RowEnd {
        val served = end.served
        val chat = turn.chat
        val reading = chat?.reading
        return when {
            turn.failedOwn -> RowOutcome.own
            turn.gone -> RowOutcome.gone
            end.status == HttpStatus.GATEWAY_TIMEOUT -> RowOutcome.timedOut
            served != null -> RowOutcome.ofHead(served.outcome, end.status)
            reading != null -> RowOutcome.ofChat(reading, chat.stream != null, end.status)
            else -> RowOutcome.ofStatus(end.status)
        }
    }

    private fun fromHead(served: ServedTurn): Heard =
        Heard(served.model, served.tokens, served.speed, served.pace.ttftMs, served.upstream, served.costUsd)

    /** An OpenAI-style turn as the dialect read it, priced at its head's rate card; for a turn that never reached a
     *  provider's server, only the model it asked for. */
    private fun fromChat(turn: LedgerTurn): Heard {
        val chat = turn.chat
        val reading = chat?.reading
        val model = reading?.model ?: chat?.model
        val tokens = reading?.tokens
        val ttft = reading?.spokeAtMs?.let(turn::since)
        return Heard(model, tokens, reading?.speed, ttft, upstream = null, costUsd = price(turn, model, tokens))
    }

    /** An OpenAI-style turn's price at its head's rate card; null when it billed nothing or the model has no card. */
    private fun price(turn: LedgerTurn, model: String?, tokens: ServedTokens?): Double? {
        if (model == null || tokens == null) return null
        return parts.upstreams.price(turn.command.key, model, tokens)
    }
}
