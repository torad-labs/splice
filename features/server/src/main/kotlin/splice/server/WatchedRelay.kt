// NEW: server mode (docs/specs/server-mode.md, F3: "a client that disconnects frees its slot"). A relayed turn can
// wait a long while on the head before its first byte (a self-hosted model prefilling a large prompt takes minutes),
// and until something is written to the client nothing notices that the client has gone. So the connection's own close
// is awaited for the length of the turn (ClientConnectionClose, the signal the heads' own turns use): when the client
// hangs up, the person's slot is given back at once and the relay is cancelled, which hangs up on the head, so the box
// stops computing an answer nobody is waiting for. The relay is whatever the route runs: a turn handed to a head, or
// an OpenAI-style client's request handed to the provider's own server (F4).
//
// A turn that runs to its end gives its slot back as the last part of its answer is handed to the client's connection
// (AnswersInFlight), before the client can have it all: a client that waits for the whole answer and asks again finds
// its place free. The relay returns only after that hand-over, so giving the slot back there could come too late.
package splice.server

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import splice.http.ClientConnectionClose
import splice.server.load.AnswersInFlight

/** One request's relay, which a cancel ends. */
internal fun interface RelayedTurn {
    suspend fun relay()
}

internal class WatchedRelay(
    /** The listener's connections, which give a slot back as the last part of its turn's answer goes out. */
    private val answers: AnswersInFlight,
) {
    /** Runs [turn] for [call], holding [slot] when the turn counts against its person. The slot is released as the
     *  last part of the turn's answer is handed to the connection, the moment the client's connection closes, which
     *  cancels the turn as well, or when the turn ends, whichever comes first. Answers whether the client left before
     *  the turn ended. */
    suspend fun turn(call: ApplicationCall, slot: TurnSlot?, turn: RelayedTurn): Boolean {
        var left = false
        slot?.let { answers.freesAtLastPart(call, it) }
        try {
            coroutineScope {
                val relayed = launch { turn.relay() }
                val gone = launch {
                    ClientConnectionClose.await(call)
                    left = true
                    slot?.release()
                    relayed.cancel()
                }
                relayed.join()
                gone.cancel()
            }
        } finally {
            slot?.release()
        }
        return left
    }
}
