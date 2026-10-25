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
import kotlinx.coroutines.CancellationException
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
import splice.head.admission.ClientRecheck
import splice.head.admission.TurnQuota
import splice.head.turn.delivery.CollectedReply
import splice.head.turn.detached.DetachedCompaction
import splice.head.turn.detached.HeldCollectAnswer
import splice.head.wire.ClientAnswer
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.DetachOwner
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
    /** One bundle for the four seams every detached collect needs; shared with the streaming path. */
    private val detachOwner: DetachOwner,
    private val clientWindows: ClientWindows = ClientWindows(),
) {
    private val wiring = TurnWiring()
    private val recheck = ClientRecheck()
    private val clientWindow = ClientWindowWitness(clientWindows)

    /** Non-stream sibling of TurnDriver.stream: Claude Code sends stream:false on some internal
     *  calls (the Node predecessor served them by collecting the terminal object). Drives the SAME
     *  fold/translator/honesty machinery into a [CollectingTerminal], then writes ONE Anthropic
     *  Messages JSON body — no SSE channel, no liveness pinger. */
    suspend fun collect(call: ApplicationCall, inputs: TurnInputs): Boolean {
        val built = inputs.built
        val terminal = CollectingTerminal(
            built.meta.route.originalModel,
            wiring.usagePayloadBuilder(provider.catalog, built.meta, clientWindow.of(call, built.meta.scope.sessionId)),
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
        // collect never commits a 200 before its terminal respondText — a cancelled collect is a
        // native connection abort client-side, and sealing there only wrote an error body nobody
        // reads while polluting localOriginErrors (review 2026-07-22 round 3).
        //
        // pingClient = false stays: there is no committed SSE channel to write a keepalive to
        // (THE FIX IS NOT A PINGER, HD-29). Ktor still does not cancel the call coroutine on this
        // path. Liveness is Netty closeFuture → [ClientChannel.connectionClosed] → parent cancel.
        // HeadEngine remains the bootstrap; this file is the second head file that names Netty,
        // and only to READ closeFuture off the call's ChannelHandlerContext.
        driveCollected(call, inputs, terminal, channel)
        // V4-99 item 3 held until the answer ledger existed: a compaction WITH a hold hands its slot to
        // the detached drive exactly as the streaming one does (markHandedOff in the launcher); every
        // other collect turn still ends with its call and returns no handoff.
        return false
    }

    /** The collect side's drive stage, extracted from [collect]: assembly with its 429 relay and the
     *  V4-174 collected-answer trace hook, liveness against the raw call, the attach-or-hold branch,
     *  the single body respond, and the deferred perf publish. */
    private suspend fun driveCollected(
        call: ApplicationCall,
        inputs: TurnInputs,
        terminal: CollectingTerminal,
        channel: ClientChannel,
    ) {
        val built = inputs.built
        var nativeReply: RateLimitReply? = null
        inputs.trace?.collectedAnswer {
            ClientAnswer(
                nativeReply?.status ?: terminal.httpStatus(),
                nativeReply?.body ?: terminal.responseBody().let(JsonWire::string),
            )
        }
        val drive = driveFactory.assembleDrive(inputs, terminal, channel)
        // The provider's own 429, told to come back within the recheck, as the streamed relay tells it.
        drive.rateLimitRelay = TurnDrive.RateLimitRelay { reply ->
            nativeReply = recheck.reply(reply)
            true
        }
        drive.collectPerf.defer()
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
                // A non-stream compaction records no frames: its hold is what a byte-identical retry
                // follows (Preparation.Collected → LocalResponses), so it detaches exactly like its
                // streaming sibling instead of throwing the whole prefill away with the call. Every
                // other collect turn runs attached as before.
                val heldKey = if (built.meta.compact) detachOwner.replay.key(built.meta, built.requestBody) else null
                if (heldKey == null) {
                    sealedDrive.driveSealingCancellation(drive, pingClient = false, seal = false)
                } else {
                    runHeldCollect(drive, inputs, heldKey, terminal, channel)
                }
                respond(call, inputs, drive, terminal, nativeReply)
            } finally {
                watch?.cancel()
                withContext(NonCancellable) { drive.collectPerf.publish(drive) }
            }
        }
    }

    /** Runs [drive] under a detached compaction whose collected answer outlives this call's client:
     *  join returns on clean completion; the call's cancellation detaches the channel first, so the
     *  drive finishes on its own scope and the hold answers the retry (DetachOwner.scope). */
    private suspend fun runHeldCollect(
        drive: TurnDrive,
        inputs: TurnInputs,
        key: String,
        terminal: CollectingTerminal,
        channel: ClientChannel,
    ) {
        val launcher = DetachedCompaction(
            provider.key,
            detachOwner.log,
            sealedDrive,
            detachOwner.replay,
            detachOwner.scope,
            detachOwner.context,
        )
        val job = launcher.launchCollected(
            drive,
            inputs,
            key,
            detachOwner.replay.answers.begin(key),
            HeldCollectAnswer { terminal.responseBody() },
        )
        try {
            job.join()
        } catch (cancelled: CancellationException) {
            // The call's cancellation IS the lost client: flip both handoff atomics like
            // ClientChannel.detachIfRecording does for a recorded stream — [detached] is what
            // launchCollected's keep verdict reads, and these writes were inert anyway.
            withContext(NonCancellable) {
                channel.clientGone.set(true)
                channel.detached.set(true)
            }
            throw cancelled
        }
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
            turnQuota.forSession(inputs.built.meta.scope.sessionId, drive.account)
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
