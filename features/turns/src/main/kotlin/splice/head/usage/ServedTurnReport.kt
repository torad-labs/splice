// NEW: server mode (docs/specs/server-mode.md, F5). A turn the server listener relayed carries the id it named it by
// (TurnRelay.servedTurn); once its row is written the head tells the listener how it went, from that row's own
// numbers: the bill as the provider reported it (never scaled for the client's window), its price at the model's
// rate card, the time to its first word, the server of the pool that took it, and the speed a llama-server reported.
// A turn no listener relayed tells nobody anything.
package splice.head.usage

import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.server.ServedTurns
import splice.core.server.TurnSpeed
import splice.core.turn.TurnMeta

internal class ServedTurnReport(private val served: ServedTurns, private val price: TurnPrice) {

    /** The head's reading of one row it wrote: [meta]'s turn ended on [outcome], with [snap] as its row carries it. */
    fun ended(meta: TurnMeta, outcome: String, snap: PerfSnapshot, way: ServedWay = ServedWay()) {
        val id = meta.route.relay.servedTurn ?: return
        val model = meta.route.upstreamModel
        val row = snap.counters
        val total = TurnBill.total(row)
        val billed = row.containsKey(PerfKeys.IN_TOKENS) || row.containsKey(PerfKeys.OUT_TOKENS)
        val tokens = ServedTokens(total.input + total.cacheRead + total.cacheWrite, total.cacheRead, total.output)
        val firstWord = snap.marks[PerfKeys.FIRST_DELTA]
        val turn = ServedTurn(
            model = model,
            upstream = way.upstream,
            outcome = outcome,
            tokens = tokens.takeIf { billed },
            costUsd = price.usd(model, row),
            ttftMs = firstWord,
            speed = way.speed,
        )
        served.ended(id, turn)
    }

    /** [meta]'s row waits on a round its turn left streaming: the listener that relayed it is told, so it waits for
     *  the row instead of recording a request that billed nothing. */
    fun waits(meta: TurnMeta) {
        meta.route.relay.servedTurn?.let(served::held)
    }
}

/** Where a turn went beyond its row: the pool's server that took it, and how fast that server worked. */
internal data class ServedWay(val upstream: String? = null, val speed: TurnSpeed? = null)
