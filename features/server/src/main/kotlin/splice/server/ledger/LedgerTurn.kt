// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). One turn as the listener follows it, from the moment
// it arrives to the moment its row is written (TurnLedger): whose it is, the command it went to, and what became known of
// it on the way. Never a word of its prompt or its answer.
package splice.server.ledger

import splice.core.server.ServedTurn
import splice.core.turn.SERVED_TURN_HEADER
import splice.server.ChatAsk
import splice.server.ProviderWay
import splice.server.ServedCommand
import splice.server.ServerKey
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** One turn, from its arrival to its row. [team] is the team its person was on when it arrived, the one whose budget
 *  it was admitted under: a person moved to another team while their turn runs leaves that turn where it began. */
internal class LedgerTurn(
    val key: ServerKey,
    val command: ServedCommand,
    val route: String,
    val startedMs: Long,
    val team: String?,
) {
    /** The id its head is told it by, on the Messages route. */
    val id: String = UUID.randomUUID().toString()

    /** The header that names it to its head. */
    val header: Pair<String, String> get() = SERVED_TURN_HEADER to id

    /** What its head will say of it, once it was handed to one. */
    @Volatile
    var served: CompletableFuture<ServedTurn>? = null

    /** The OpenAI-style request as it went to the provider's server, once it went. */
    @Volatile
    var chat: ChatAsk? = null

    /** The way it took through its provider's servers, once it took one. */
    @Volatile
    var way: ProviderWay? = null

    /** Whether its client left before its answer ended. */
    @Volatile
    var gone: Boolean = false

    /** Whether splice refused it for a failure of its own: a ledger it cannot write, or its head not running. */
    @Volatile
    var failedOwn: Boolean = false

    /** How long after it arrived [atMs] is. */
    fun since(atMs: Long): Long = atMs - startedMs
}
