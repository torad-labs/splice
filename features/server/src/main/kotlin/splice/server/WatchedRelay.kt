// NEW: server mode (docs/specs/server-mode.md, F3: "a client that disconnects frees its slot"). A relayed turn can
// wait a long while on the head before its first byte (a self-hosted model prefilling a large prompt takes minutes),
// and until something is written to the client nothing notices that the client has gone. So the connection's own close
// is watched for the length of the turn: when the client hangs up, the person's slot is given back at once and the
// relay is cancelled, which hangs up on the head, so the box stops computing an answer nobody is waiting for.
package splice.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import splice.share.ShareRelay
import splice.share.SharedHead

internal class WatchedRelay(private val relay: ShareRelay) {
    /** Relays [call] to [head] at [path], holding [slot] when the turn counts against its person. The slot is released
     *  when the turn ends, and before that the moment the client's connection closes. */
    suspend fun turn(call: ApplicationCall, head: SharedHead, path: String, slot: TurnSlot?) {
        val closed = (call as? NettyApplicationCall)?.context?.channel()?.closeFuture()
        try {
            coroutineScope {
                val relayed = launch { relay.turn(call, head, path) }
                val left = ChannelFutureListener {
                    slot?.release()
                    relayed.cancel()
                }
                closed?.addListener(left)
                relayed.invokeOnCompletion { closed?.removeListener(left) }
            }
        } finally {
            slot?.release()
        }
    }
}
