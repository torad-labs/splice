// PORT-OF: splice/gateway/head/TurnDriver.kt (collect) @ 86f1411 — invariants unchanged: the
// non-stream sibling of TurnDriver.stream. This is the head-decoupling plan's pre-priced contingency
// (HD-24): the un-split TurnDriver.kt measured 1.83, just over the 1.8 gate, so collect's 18 lines
// move here exactly as pre-priced. [driver] is held (not a lambda — kt-no-lambda-seam) so [TurnDriver.driveSealingCancellation] stays the ONE copy of the
// L3 seal contract shared by stream and collect; the visibility widening (private -> internal) is
// named on that method.
package splice.head.turn

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import splice.core.model.ClientWindows
import splice.core.util.JsonWire
import splice.core.wire.RateLimitReply
import splice.head.ClientWindowWitness
import splice.head.admission.TurnQuota
import splice.head.turn.delivery.CollectedReply
import splice.head.wire.ClientAnswer
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnWiring
import splice.upstream.Provider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class CollectTurn(
    private val provider: Provider,
    private val driveFactory: TurnDriveFactory,
    private val sealedDrive: SealedDrive,
    private val turnQuota: TurnQuota,
    private val clientWindows: ClientWindows = ClientWindows(),
) {
    private val wiring = TurnWiring()
    private val clientWindow = ClientWindowWitness(clientWindows)

    /** Non-stream sibling of TurnDriver.stream: Claude Code sends stream:false on some internal
     *  calls (the Node predecessor served them by collecting the terminal object). Drives the SAME
     *  fold/translator/honesty machinery into a [CollectingTerminal], then writes ONE Anthropic
     *  Messages JSON body — no SSE channel, no liveness pinger. */
    suspend fun collect(call: ApplicationCall, inputs: TurnInputs): Boolean {
        val built = inputs.built
        val terminal = CollectingTerminal(
            built.meta.originalModel,
            wiring.usagePayloadBuilder(provider.catalog, built.meta, clientWindow.of(call, built.meta.sessionId)),
        )
        // Inert writer: collect never writes SSE frames. clientGone is flipped by Netty
        // closeFuture (HD-29), not by a failed write.
        val channel = ClientChannel(
            coalesced = ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
            writeMutex = Mutex(),
            clientGone = AtomicBoolean(false),
            trace = inputs.trace,
        )
        // V4-174: no frames cross this channel, so the trace reads the collected answer instead —
        // at the turn record, once the terminal has closed and the body exists.
        var nativeReply: RateLimitReply? = null
        inputs.trace?.collectedAnswer {
            ClientAnswer(
                nativeReply?.status ?: terminal.httpStatus(),
                nativeReply?.body ?: terminal.responseBody().let(JsonWire::string),
            )
        }
        val drive = driveFactory.assembleDrive(inputs, terminal, channel)
        drive.rateLimitRelay = TurnDrive.RateLimitRelay { reply ->
            nativeReply = reply
            true
        }
        drive.collectPerf.defer()
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
                    ClientConnectionClose.await(call)
                    channel.connectionClosed(parent)
                }
            }
            try {
                sealedDrive.driveSealingCancellation(drive, pingClient = false, seal = false)
                respond(call, inputs, drive, terminal, nativeReply)
            } finally {
                watch?.cancel()
                withContext(NonCancellable) { drive.collectPerf.publish(drive) }
            }
        }
        // The collect path never detaches a compaction, so it never hands the slot off (V4-99 item 3).
        return false
    }

    private suspend fun respond(
        call: ApplicationCall,
        inputs: TurnInputs,
        drive: TurnDrive,
        terminal: CollectingTerminal,
        nativeReply: RateLimitReply?,
    ) {
        val headers = nativeReply?.headers
        if (headers == null) {
            turnQuota.forSession(inputs.built.meta.sessionId, drive.account)
                ?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
        } else {
            headers.forEach { (name, values) -> values.forEach { call.response.header(name, it) } }
        }
        call.respond(
            CollectedReply(
                nativeReply?.body ?: terminal.responseBody().let(JsonWire::string),
                HttpStatusCode.fromValue(nativeReply?.status ?: terminal.httpStatus()),
                drive.perf,
            ),
        )
    }
}

/** Shared Netty close signal for collect and the stream's uncommitted status hold. */
internal object ClientConnectionClose {
    suspend fun await(call: ApplicationCall) {
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
