// NEW: server mode (docs/specs/server-mode.md, F5). What a head tells the server listener about a turn the listener
// relayed to it, so the ledger row of that request carries the head's own reading of it: the model it asked, the
// server of the provider's pool that answered, the tokens the provider billed (raw, before any window scaling the
// client is shown), the price from the model's rate card, and how fast a self-hosted model server read the prompt and
// wrote the answer (llama-server's `timings`). Never a word of the prompt or the answer.
//
// The listener names each turn it relays with a fresh id in SERVED_TURN_HEADER (TurnRelay) and waits on the board for that id once
// the answer has ended; the head, when its turn's row is written, ends the id on the board. Both run in one JVM, so the
// board is a map. An id nobody expects (a head reached some other way) is dropped, so nothing accumulates.
//
// A row can be written long after its answer ended: code mode returns a turn while the round that writes its script
// still streams, and that round's bill lands on the turn's row only once it settles (SourceRowHold). The head says so
// as the turn ends (held), and the listener then waits for the row instead of recording a request that billed nothing.
package splice.core.server

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** How fast a model server worked one turn: prompt tokens it read a second ([prefillTps]) and answer tokens it wrote a
 *  second ([decodeTps]), as llama-server's `timings` report them; null where the server reported none. */
public data class TurnSpeed(val prefillTps: Double?, val decodeTps: Double?)

/** One served turn as its head saw it end. [outcome] is the head's outcome tag (splice.core.perf.OutcomeTag), tokens
 *  are null when the provider billed none, and [costUsd] is null when the model has no rate card. */
public data class ServedTurn(
    val model: String,
    val upstream: String?,
    val outcome: String,
    val tokens: ServedTokens?,
    val costUsd: Double?,
    val ttftMs: Long?,
    val speed: TurnSpeed?,
)

/** The tokens one turn billed: every prompt token, the share of them read from the prompt cache, and the answer's. */
public data class ServedTokens(val prompt: Long, val cached: Long, val completion: Long)

/** Where a head ends a served turn. */
public interface ServedTurns {
    public fun ended(id: String, turn: ServedTurn)

    /** [id]'s row waits on a round its turn left streaming, so its head ends it later than its answer ended: once that
     *  round settles, or when the head stops. */
    public fun held(id: String)
}

/** The daemon's one board between its heads and its server listener. */
public class ServedTurnBoard : ServedTurns {
    private val waiting = ConcurrentHashMap<String, CompletableFuture<ServedTurn>>()

    // The expected turns whose heads said their rows come later than their answers.
    private val late: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The turn [id] will be, once its head ends it. Call before the turn is relayed and [forget] it after. */
    public fun expect(id: String): CompletableFuture<ServedTurn> = waiting.computeIfAbsent(id) { CompletableFuture() }

    override fun ended(id: String, turn: ServedTurn) {
        waiting[id]?.complete(turn)
    }

    override fun held(id: String) {
        late += id
        // An id nobody expects, or one forgotten meanwhile, is dropped here too.
        if (!waiting.containsKey(id)) late -= id
    }

    /** Whether [id]'s head said its row waits on a round still streaming. */
    public fun holds(id: String): Boolean = id in late

    public fun forget(id: String) {
        waiting.remove(id)
        late.remove(id)
    }
}
