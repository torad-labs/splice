// PORT-OF: splice/gateway/head/TurnDriver.kt (collect) @ 86f1411 — invariants unchanged: the
// non-stream sibling of TurnDriver.stream. This is the campaign's own pre-priced contingency
// (dev/campaigns/head-decoupling.toml HD-24): the un-split TurnDriver.kt measured 1.83, just over
// the 1.8 gate, so collect's 18 lines move here exactly as pre-priced. [driver] is held (not a
// lambda — kt-no-lambda-seam) so [TurnDriver.driveSealingCancellation] stays the ONE copy of the
// L3 seal contract shared by stream and collect; the visibility widening (private -> internal) is
// named on that method.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import splice.core.perf.TurnPerf
import splice.gateway.usage.QuotaTracker
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnWiring
import splice.spi.BuiltTurn
import splice.spi.InflightGate
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class CollectTurn(
    private val provider: Provider,
    private val driveFactory: TurnDriveFactory,
    private val driver: TurnDriver,
    private val quota: QuotaTracker?,
) {
    private val wiring = TurnWiring()

    /** Non-stream sibling of TurnDriver.stream: Claude Code sends stream:false on some internal
     *  calls (the Node predecessor served them by collecting the terminal object). Drives the SAME
     *  fold/translator/honesty machinery into a [CollectingTerminal], then writes ONE Anthropic
     *  Messages JSON body — no SSE channel, no liveness pinger. */
    suspend fun collect(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        val terminal = CollectingTerminal(
            built.meta.originalModel,
            wiring.usagePayloadBuilder(provider.catalog, built.meta),
        )
        // Inert writer: collect never writes SSE frames. clientGone is flipped by Netty
        // closeFuture (HD-29), not by a failed write.
        val channel = ClientChannel(
            coalesced = ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
            writeMutex = Mutex(),
            clientGone = AtomicBoolean(false),
        )
        val drive = driveFactory.assembleDrive(TurnInputs(built, slot, t0, perf), terminal, channel)
        // collect never commits a 200 before its terminal respondText — a cancelled collect is a
        // native connection abort client-side, and sealing there only wrote an error body nobody
        // reads while polluting localOriginErrors (review 2026-07-22 round 3).
        //
        // pingClient = false stays: there is no committed SSE channel to write a keepalive to
        // (THE FIX IS NOT A PINGER, HD-29). Ktor still does not cancel the call coroutine on this
        // path. Liveness is Netty closeFuture → [ClientChannel.connectionClosed] → parent cancel.
        // HeadEngine remains the bootstrap; this file is the second head file that names Netty,
        // and only to READ closeFuture off the call's ChannelHandlerContext.
        coroutineScope {
            val parent = coroutineContext[Job]
            val watch = if (parent == null) {
                null
            } else {
                launch {
                    awaitClientConnectionClosed(call)
                    channel.connectionClosed(parent)
                }
            }
            try {
                driver.driveSealingCancellation(drive, pingClient = false, seal = false)
                // Same unified rate-limit headers as the streaming path (TurnStreamer).
                quota?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
                call.respondText(
                    terminal.responseBody().toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.fromValue(terminal.httpStatus()),
                )
            } finally {
                watch?.cancel()
            }
        }
    }

    private suspend fun awaitClientConnectionClosed(call: ApplicationCall) {
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

    // Ktor 3 routing hands [RoutingCall], which wraps [RoutingPipelineCall], which wraps the
    // engine call. `call as? NettyApplicationCall` is therefore always null on this path
    // (HD-29 measured: the watch never attached and the slot stayed pinned). Walk the public
    // getters; if the engine call is not Netty, fall back to awaitCancellation so we never
    // false-positive-cancel.
    private fun nettyCall(call: ApplicationCall): NettyApplicationCall? {
        val pipeline = (call as? RoutingCall)?.pipelineCall ?: call
        val engine = (pipeline as? RoutingPipelineCall)?.engineCall ?: pipeline
        return engine as? NettyApplicationCall
    }
}
