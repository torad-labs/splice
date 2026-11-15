// The moment a call's client goes away, read off its connection rather than off a write: work that has written nothing
// yet (a collected turn, a stream still holding its status, a relay waiting on a head) learns of a hang-up no other
// way. Moved here from features/turns (CollectTurn.kt) on Nov 15, 2026, when server mode's relay came to need the same
// signal: this module is the one both depend on.
package splice.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Shared Netty close signal for collect, the stream's uncommitted status hold, and server mode's relayed turns. */
public object ClientConnectionClose {
    /** Returns when [call]'s connection closes. A call no Netty connection carries never returns, only cancels. */
    public suspend fun await(call: ApplicationCall) {
        val netty = nettyCall(call)
        if (netty == null) {
            awaitCancellation()
            return
        }
        suspendCancellableCoroutine { cont ->
            val future = netty.context.channel().closeFuture()
            val listener = GenericFutureListener<ChannelFuture> {
                if (cont.isActive) cont.resume(Unit)
            }
            future.addListener(listener)
            cont.invokeOnCancellation { future.removeListener(listener) }
        }
    }

    // Ktor 3 routing wraps the engine call twice; checking call directly misses every turn.
    private fun nettyCall(call: ApplicationCall): NettyApplicationCall? {
        val pipeline = (call as? RoutingCall)?.pipelineCall ?: call
        val engine = (pipeline as? RoutingPipelineCall)?.engineCall ?: pipeline
        return engine as? NettyApplicationCall
    }
}
