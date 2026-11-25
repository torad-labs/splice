// NEW: server mode (docs/specs/server-mode.md, F3, F4 and F12). What a turn passes before it is relayed, on either
// route the listener serves one on: the Messages route Claude Code speaks, and the chat completions an OpenAI-style client
// asks. The caps are a person's, not a route's, so both take their slots here, and each refuses in its own client's
// error shape (decision 6): a turn whose provider has no server up is a 503 naming its upstreams, which counts toward
// no cap; a turn of a person whose budget, or whose team's, is spent and blocks is a 400 naming that budget (F5), the
// way a head refuses its own spent budget; and a turn past a cap (its person's turns in flight, turns a minute, or
// tokens a minute, F12) is a 429 with `Retry-After`. Before all of them, while splice cannot write its usage ledger,
// every turn is a 500: splice's own failure, never served uncounted (F5). And first, a turn on a command its person
// may not use is a 403 naming the command, and reaches no head (F12).
//
// The server's own caps (#791, ServerLoad) are asked after a person's tokens and before their turns: a turn past
// `inflight_total`, or on a provider whose healthy upstreams each hold `inflight_per_upstream`, is answered with
// `busy_status` and `Retry-After` and spends none of its person's turns a minute. While the server drains at stop, the
// listener refuses every keyed request, turns included, with a 503 and `Retry-After` before any of this is asked.
//
// Both routes open and close a turn here (served), so every turn on either leaves its row in the ledger however it
// ends: refused, relayed to its end, or left by its client.
package splice.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.server.UpstreamRefusal
import splice.core.wire.HttpStatus
import splice.server.ledger.LedgerTurn
import splice.share.RefusalVoice
import splice.share.ShareCatalog

internal class TurnAdmission(
    private val parts: ServerParts,
    private val voice: RefusalVoice,
    /** Says a head is not running, in the route's own error shape. */
    private val catalog: ShareCatalog,
    private val watched: WatchedRelay,
) {
    /** A turn of [key]'s person on [command], arriving on [route]: it takes one of its person's slots or is refused, is
     *  run by [relay] holding that slot until its answer ends or its client leaves, and however it ends, leaves its row
     *  in the ledger. */
    suspend fun served(
        call: ApplicationCall,
        key: ServerKey,
        command: ServedCommand,
        route: String,
        relay: AdmittedRelay,
    ) {
        val turn = parts.books.turns.arrived(key, command, route)
        try {
            val slot = slot(call, turn) ?: return
            turn.gone = watched.turn(call, slot) { relay.relay(turn) }
        } finally {
            parts.books.turns.ended(call, turn)
        }
    }

    /** The slot [turn] takes among those of its person, or null with it refused: first one on a command its person may
     *  not use (F12), then as [admitted] says. */
    private suspend fun slot(call: ApplicationCall, turn: LedgerTurn): TurnSlot? {
        val forbidden = parts.limits.access.refusal(turn.key.person, turn.command) ?: return admitted(call, turn)
        voice.refuse(call, HttpStatus.FORBIDDEN, forbidden)
        return null
    }

    /** The slot [turn] takes, or null with it refused: one past a cap ([capped]), before that one past a budget that
     *  blocks, before that one whose provider has no server up, before that one splice cannot record, and first one
     *  whose command is not running. A turn that arrives while the server drains never reaches here: the listener
     *  refuses every keyed request then (ServerListener), and one that slips in as the drain begins is refused by the
     *  server's own cap ([capped]). */
    private suspend fun admitted(call: ApplicationCall, turn: LedgerTurn): TurnSlot? {
        if (!turn.command.running()) {
            // A command of splice's own that is down is splice's failure, never the upstream's (F5).
            turn.failedOwn = true
            catalog.notRunning(call, turn.command.name)
            return null
        }
        val refused = refusal(turn)
        if (refused != null) {
            refuse(call, refused)
            return null
        }
        return when (val verdict = capped(turn)) {
            is LimitVerdict.Admitted -> verdict.slot
            is LimitVerdict.Refused -> {
                call.response.headers.append(HttpHeaders.RetryAfter, verdict.retryAfterSeconds.toString())
                voice.refuse(call, verdict.status, verdict.why)
                null
            }
        }
    }

    /** An upgrade notice normally bypasses admission; unavailable upstreams still refuse the turn. */
    suspend fun refusesNotice(call: ApplicationCall, key: ServerKey, command: ServedCommand): Boolean {
        val refused = parts.upstreams.externalRefusal() ?: return false
        val forbidden = parts.limits.access.refusal(key.person, command)
        refuse(call, if (forbidden == null) external(refused) else Refusal(HttpStatus.FORBIDDEN, forbidden))
        return true
    }

    private suspend fun refuse(call: ApplicationCall, refused: Refusal) {
        refused.retryAfter?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        voice.refuse(call, refused.status, refused.why)
    }

    /** [turn]'s places under every cap: a minute's tokens first, which takes nothing, then the server's turns in flight
     *  (#791), then its person's turns in flight and a minute's turns. The server's place is taken before the person's
     *  because a person's place spends one of their minute's turns, which giving it back does not return: a turn the
     *  server refuses must cost its person nothing. When the person's caps refuse, the server's place is given back at
     *  once, so a refused turn holds nothing. */
    private suspend fun capped(turn: LedgerTurn): LimitVerdict {
        val person = turn.key.person
        overTokens(person)?.let { return it }
        val shared = parts.limits.load.begin(turn.command.key, turn.id)
        if (shared !is LimitVerdict.Admitted) return shared
        return when (val own = parts.limits.begin(person)) {
            is LimitVerdict.Admitted -> LimitVerdict.Admitted(own.slot.joined(shared.slot))
            is LimitVerdict.Refused -> own.also { shared.slot.release() }
        }
    }

    /** Why [turn] is refused before any cap is asked, in the order they are asked, or null when nothing refuses it. */
    private suspend fun refusal(turn: LedgerTurn): Refusal? =
        parts.books.turns.unwritable(turn)?.let { Refusal(HttpStatus.INTERNAL_SERVER_ERROR, it) }
            ?: parts.upstreams.externalRefusal()?.let(::external)
            ?: parts.upstreams.noneUp(turn.command.key)?.let { why ->
                Refusal(HttpStatus.SERVICE_UNAVAILABLE, why, parts.upstreams.recheck(turn.command.key))
            }
            ?: spent(turn.key.person)?.let { Refusal(HttpStatus.BAD_REQUEST, it) }

    private fun external(refusal: UpstreamRefusal): Refusal =
        Refusal(refusal.status, refusal.message, refusal.retryAfterSeconds)

    /** Why [person]'s budget, or their team's, refuses their next turn; null while none blocks. Their turns that ended
     *  and are still being written are counted first, so a turn never slips past a budget its last turn spent. */
    private suspend fun spent(person: String): String? {
        if (!parts.books.budgets.holds(person)) return null
        parts.books.turns.settled(person)
        return parts.books.budgets.refusal(person)
    }

    /** Why [person]'s next turn waits on the tokens of their turns that ended in the last minute (F12); null while
     *  those are under their cap, and when nothing caps them. Their turns that ended and are still being written are
     *  counted first, as a budget's are, so a turn never slips past a cap its last turn reached. */
    private suspend fun overTokens(person: String): LimitVerdict.Refused? {
        val cap = parts.limits.tokenCap(person) ?: return null
        parts.books.turns.settled(person)
        return parts.books.tokens.refusal(person, cap)
    }
}

/** An admitted turn's relay, run while it holds its slot; a cancel ends it. */
internal fun interface AdmittedRelay {
    suspend fun relay(turn: LedgerTurn)
}

/** A turn refused with [status], and why. */
private data class Refusal(val status: Int, val why: String, val retryAfter: Long? = null)
