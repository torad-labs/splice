// NEW: server mode (docs/specs/server-mode.md, F3 and F4). What a turn passes before it is relayed, on either route
// the listener serves one on: the Messages route Claude Code speaks, and the chat completions an OpenAI-style client
// asks. The caps are a person's, not a route's, so both take their slots here, and each refuses in its own client's
// error shape (decision 6): a turn whose provider has no server up is a 503 naming its upstreams, which counts toward
// no cap; a turn of a person whose budget, or whose team's, is spent and blocks is a 400 naming that budget (F5), the
// way a head refuses its own spent budget; and a turn past a cap is a 429 with `Retry-After`. Before all of them, while
// splice cannot write its usage ledger, every turn is a 500: splice's own failure, never served uncounted (F5).
//
// Both routes open and close a turn here (served), so every turn on either leaves its row in the ledger however it
// ends: refused, relayed to its end, or left by its client.
package splice.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.wire.HttpStatus
import splice.server.ledger.LedgerTurn
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.SharedHead

internal class TurnAdmission(
    private val parts: ServerParts,
    private val voice: RefusalVoice,
    /** Says a head is not running, in the route's own error shape. */
    private val catalog: ShareCatalog,
    private val watched: WatchedRelay,
) {
    /** A turn of [key]'s person on [head], arriving on [route]: it takes one of its person's slots or is refused, is
     *  run by [relay] holding that slot until its answer ends or its client leaves, and however it ends, leaves its row
     *  in the ledger. */
    suspend fun served(
        call: ApplicationCall,
        key: ServerKey,
        head: SharedHead,
        route: String,
        relay: AdmittedRelay,
    ) {
        val turn = parts.books.turns.arrived(key, head, route)
        try {
            val slot = slot(call, turn) ?: return
            turn.gone = watched.turn(call, slot) { relay.relay(turn) }
        } finally {
            parts.books.turns.ended(call, turn)
        }
    }

    /** The slot [turn] takes among those of its person, or null with it refused: one past a cap, before that one past
     *  a budget that blocks, before that one whose provider has no server up, before that one splice cannot record,
     *  and first one whose head is not running. */
    private suspend fun slot(call: ApplicationCall, turn: LedgerTurn): TurnSlot? {
        if (!turn.head.running()) {
            // A head of splice's own that is down is splice's failure, never the upstream's (F5).
            turn.failedOwn = true
            catalog.notRunning(call, turn.head)
            return null
        }
        val refused = refusal(turn)
        if (refused != null) {
            voice.refuse(call, refused.status, refused.why)
            return null
        }
        return when (val verdict = parts.limits.begin(turn.key.person)) {
            is LimitVerdict.Admitted -> verdict.slot
            is LimitVerdict.Refused -> {
                call.response.headers.append(HttpHeaders.RetryAfter, verdict.retryAfterSeconds.toString())
                voice.refuse(call, HttpStatus.TOO_MANY_REQUESTS, verdict.why)
                null
            }
        }
    }

    /** Why [turn] is refused before any cap is asked, in the order they are asked, or null when nothing refuses it. */
    private suspend fun refusal(turn: LedgerTurn): Refusal? =
        parts.books.turns.unwritable(turn)?.let { Refusal(HttpStatus.INTERNAL_SERVER_ERROR, it) }
            ?: parts.upstreams.noneUp(turn.head.key)?.let { Refusal(HttpStatus.SERVICE_UNAVAILABLE, it) }
            ?: spent(turn.key.person)?.let { Refusal(HttpStatus.BAD_REQUEST, it) }

    /** Why [person]'s budget, or their team's, refuses their next turn; null while none blocks. Their turns that ended
     *  and are still being written are counted first, so a turn never slips past a budget its last turn spent. */
    private suspend fun spent(person: String): String? {
        if (!parts.books.budgets.holds(person)) return null
        parts.books.turns.settled(person)
        return parts.books.budgets.refusal(person)
    }
}

/** An admitted turn's relay, run while it holds its slot; a cancel ends it. */
internal fun interface AdmittedRelay {
    suspend fun relay(turn: LedgerTurn)
}

/** A turn refused with [status], and why. */
private data class Refusal(val status: Int, val why: String)
