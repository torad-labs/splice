// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). How the listener writes each turn's ledger row: a
// turn is followed from the moment it arrives (LedgerTurn, in its own file) to the moment its answer ended or it was refused, and then
// its row is written from what is known of it by then. A turn on the Messages route is named to its head with a fresh
// id (SERVED_TURN_HEADER), and the head says on the board how that turn went, from the row it wrote itself: the model,
// the tokens the provider billed, the price, the time to the first word, the upstream and the speed. An OpenAI-style
// client's turn is read by the chat dialect as it is relayed (AnswerReading), and priced at its head's rate card.
//
// A row is written once the answer has ended, on the ledger's own scope: the response is never held open while a head
// is still writing its own row, and a stop waits a moment for the rows still being written.
//
// A person's next turn reads their budget and their tokens a minute only once the rows of their answered turns are
// written (settled). A turn is answered from the moment the last part of its answer is handed to the client's
// connection, where its slot is given back (AnswersInFlight), or from its end when it has no such moment. The client
// cannot have the whole answer before that hand-over, so it cannot ask again before the turn counts as answered.
// Counted only once the relay returned, which can come after the client has the answer, the last turn could go
// uncounted and a person go over by about one turn's usage.
//
// A head can say its row comes later than its answer: code mode returns a turn while the round that writes its script
// still streams, and the turn's bill is whole only once that round settles (ServedTurnBoard.holds). Such a row waits
// for its head however long that is, so the request is billed what it cost; a ledger that stops writes it with what
// it has.
package splice.server.ledger

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.server.ServedTurn
import splice.core.server.ServedTurnBoard
import splice.core.topology.ServerTeams
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.server.ServedCommand
import splice.server.ServerKey
import splice.server.ServerPeople
import splice.server.UpstreamApi
import splice.server.metrics.ServerMetrics
import splice.share.AnsweredStatus
import splice.upstream.LifecycleScope
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** What the ledger rows of turns are written from: where they go, the board heads tell their turns on, each person's
 *  team, the rate cards of OpenAI-style turns, the time, and the live metrics each row is counted in (F6). */
internal data class LedgerParts(
    val ledger: UsageLedger,
    val board: ServedTurnBoard,
    val people: ServerPeople,
    val teams: ServerTeams,
    val upstreams: UpstreamApi,
    val clock: Clock,
    val metrics: ServerMetrics,
)

internal class TurnLedger(private val parts: LedgerParts, io: CoroutineDispatcher, private val log: LogSink) {
    private val scope = LifecycleScope(io)
    private val job = scope.coroutineContext.job
    private val rows = LedgerRows(parts)

    // Every turn from its arrival until its row is written; a person's next turn waits on those of theirs answered.
    private val pending = ConcurrentHashMap.newKeySet<LedgerTurn>()

    // Completed as the ledger stops: a row still waiting on its head is then written with what it has.
    private val stopping = CompletableDeferred<Unit>()

    // Why the ledger last refused rows, while it does; the log says so once as it stops and once as it starts again.
    private val broken = AtomicReference<String?>(null)

    /** A turn of [key]'s person on [command], arriving now on [route], for the team they are on now. */
    fun arrived(key: ServerKey, command: ServedCommand, route: String): LedgerTurn {
        val turn = LedgerTurn(key, command, route, parts.clock.millis(), parts.people.team(key.person, parts.teams))
        pending.add(turn)
        turn.row.invokeOnCompletion { pending.remove(turn) }
        return turn
    }

    /** The header that names [turn] to its head, which from now on is expected to say how it went. */
    fun named(turn: LedgerTurn): Pair<String, String> {
        turn.served = parts.board.expect(turn.id)
        return turn.header
    }

    /** Why no turn may be served now, with [turn] marked splice's own failure; null while the ledger takes rows. A
     *  turn served while its row could not be written would be one no report or budget ever counts (F5). */
    suspend fun unwritable(turn: LedgerTurn): String? {
        val why = parts.ledger.unwritable()
        val was = broken.getAndSet(why)
        if (why != null) {
            turn.failedOwn = true
            val said = LogSafe.str(why)
            if (was == null) log("[server] the usage ledger cannot be written; turns are refused until it can: $said\n")
            return LEDGER_DOWN
        }
        if (was != null) log("[server] the usage ledger takes rows again; turns are served\n")
        return null
    }

    /** [turn] ended, answered as [call] was: its row is written once its head, if it had one, has said how it went.
     *  The status is the one the answer was given under, which a stream opened before it no longer says. */
    fun ended(call: ApplicationCall, turn: LedgerTurn) {
        val status = AnsweredStatus.of(call) ?: HttpStatus.OK
        val durationMs = turn.since(parts.clock.millis())
        val row = scope.launch(start = CoroutineStart.LAZY) {
            val served = turn.served?.let { word(turn.id, it) }
            parts.board.forget(turn.id)
            val end = LedgerEnd(status, durationMs, served)
            val written = rows.of(turn, end)
            parts.metrics.counted(written, stall(turn, served))
            Cancellables.runCatchingCancellable { parts.ledger.add(written) }
                .onFailure { log("[server] a ledger row was not written: ${SafeFailureText.render(it)}\n") }
        }
        turn.answered.complete()
        // Written, failed, or cancelled as the ledger stops: nothing waits on it any longer.
        row.invokeOnCompletion { turn.row.complete() }
        row.start()
    }

    /** The longest [turn]'s answer waited for its upstream's next words after its first: as its head measured it, or
     *  as the chat dialect read an OpenAI-style stream; null for an answer that streamed no words. */
    private fun stall(turn: LedgerTurn, served: ServedTurn?): Long? {
        if (served != null) return served.pace.stallMs
        val chat = turn.chat?.takeIf { it.stream != null } ?: return null
        return chat.reading?.stallMs
    }

    /** What the head of turn [id] said of it: within a moment of its answer ending, or, where the head said its row
     *  waits on a round the turn left streaming, once that round settles. A head that stops writes such a row with
     *  what it knows; null from a head that never says, and for a row still waited for as the ledger stops. */
    private suspend fun word(id: String, said: CompletableFuture<ServedTurn>): ServedTurn? {
        // why: awaited as a Deferred, because a future's own await cancels the future when its wait is cut short, and
        // this one is waited for again past the moment.
        val word = said.asDeferred()
        val soon = withTimeoutOrNull(HEAD_WORD_MS) { word.await() }
        if (soon != null || !parts.board.holds(id)) return soon
        return select {
            word.onAwait { it }
            stopping.onAwait { null }
        }
    }

    /** Waits, a moment at most, for the rows still to be written of [person]'s turns and their team's whose answers
     *  their clients may have whole (LedgerTurn.answered). A turn still answering is not waited on, and neither is a
     *  row its head holds for a round still streaming, which has no bill yet. */
    suspend fun settled(person: String) {
        val team = parts.people.team(person, parts.teams)
        val theirs = pending.filter {
            it.answered.isCompleted && !parts.board.holds(it.id) && (it.key.person == person || onTeam(it, team))
        }
        withTimeoutOrNull(HEAD_WORD_MS) { theirs.forEach { it.row.join() } }
    }

    /** Whether [turn] counts toward [team]'s budget, as the turn of someone on it when it arrived; never when there
     *  is no team. */
    private fun onTeam(turn: LedgerTurn, team: String?): Boolean = team != null && turn.team == team

    /** Waits a moment for the rows still being written, then stops. A row still waiting on its head is written with
     *  what it has. */
    suspend fun close() {
        stopping.complete(Unit)
        withTimeoutOrNull(ROWS_CLOSE_WAIT_MS) { job.children.forEach { it.join() } }
        job.cancelAndJoin()
    }
}

// why: a head writes its own row as its answer ends, a few milliseconds either side of the relay's own end; two
// seconds is far past that, and a head that never says (one that refused the turn before it began) costs only that.
// A head whose row waits on a round still streaming says so in the same moment, and is waited for past it.
private const val HEAD_WORD_MS = 2_000L

// why: a stop waits this long for rows still waiting on their head, the longest one waits, and a little more.
private const val ROWS_CLOSE_WAIT_MS = 3_000L

// What a client is told when splice cannot write its ledger: splice's own failure, never the upstream's, and no path.
private const val LEDGER_DOWN =
    "splice cannot write its usage ledger, so it serves no turn until it can; the server's operator has the reason " +
        "in the daemon's log"
