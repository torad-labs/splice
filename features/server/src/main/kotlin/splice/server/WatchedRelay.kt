// NEW: server mode (docs/specs/server-mode.md, F3: "a client that disconnects frees its slot"). A relayed turn can
// wait a long while on the head before its first byte (a self-hosted model prefilling a large prompt takes minutes),
// and until something is written to the client nothing notices that the client has gone. So the connection's own close
// is awaited for the length of the turn (ClientConnectionClose, the signal the heads' own turns use): when the client
// hangs up, the person's slot is given back at once and the relay is cancelled, which hangs up on the head, so the box
// stops computing an answer nobody is waiting for. The relay is whatever the route runs: a turn handed to a head, or
// an OpenAI-style client's request handed to the provider's own server (F4).
package splice.server

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import splice.http.ClientConnectionClose

/** One request's relay, which a cancel ends. */
internal fun interface RelayedTurn {
    suspend fun relay()
}

internal class WatchedRelay {
    /** Runs [turn] for [call], holding [slot] when the turn counts against its person. The slot is released when
     *  the turn ends, and before that the moment the client's connection closes, which cancels the turn as well. */
    suspend fun turn(call: ApplicationCall, slot: TurnSlot?, turn: RelayedTurn) {
        try {
            coroutineScope {
                val relayed = launch { turn.relay() }
                val gone = launch {
                    ClientConnectionClose.await(call)
                    slot?.release()
                    relayed.cancel()
                }
                relayed.join()
                gone.cancel()
            }
        } finally {
            slot?.release()
        }
    }
}
