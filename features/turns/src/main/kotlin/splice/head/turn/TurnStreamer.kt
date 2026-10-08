// NEW: the SSE writer open + per-turn channel/emitter assembly.
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
//
// A COMPACTION OUTLIVES ITS CLIENT (2026-09-05): Claude Code aborts an auto-compaction at 600 s of
// wall clock and retries the same bytes minutes later, and every abort used to cancel a 600 s
// upstream read. A compact stream turn is therefore driven on a scope the call's cancellation
// cannot reach, with a recording channel: a lost client detaches the channel and the turn runs on;
// its recorded answer serves the retry (CompactionReplay, LocalResponses). The admission slot goes
// with the drive and comes back when the upstream turn ends. Ordinary turns are untouched.
//
// The detached scope OUTLIVES A HEAD RESTART: HeadServer stops and starts on the same driver, so a
// stop ends the compactions still driving (cancelChildren) and never the scope. A recording is
// therefore made for every compaction, and the launch is ATOMIC so its finally settles the slot and
// the recording even when a cancellation lands between the check and the start.
package splice.head.turn

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splice.core.perf.PerfKeys
import splice.head.ClientWindowWitness
import splice.head.HeadDeps
import splice.head.compaction.CompactionReplay
import splice.head.turn.detached.DetachedCompaction
import splice.head.turn.stream.PendingSse
import splice.head.wire.ContentReached
import splice.head.wire.FrameRecording
import splice.head.wire.SseEmitter
import splice.head.wire.SseEmitterFactory
import splice.head.wire.SseResponse
import splice.head.wire.StreamWiring
import splice.head.wire.TurnWiring
import splice.upstream.LifecycleScope
import splice.upstream.Provider
import splice.upstream.codemode.ProcessDispatchers

internal class TurnStreamer(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val driveFactory: TurnDriveFactory,
    private val sealedDrive: SealedDrive,
    private val replay: CompactionReplay,
    /** Where a detached compaction runs: a NAMED owner (LifecycleScope: a supervisor, so one failing
     *  compaction never cancels a sibling) with no parent, so the call's cancellation cannot reach
     *  it, on the background lane of the runtime seam (HD-19). Injectable for the same reason the
     *  auth providers' prefetch scope is: a test can drain it before teardown. */
    private val detachedScope: CoroutineScope = LifecycleScope(ProcessDispatchers().background()),
) {
    private val emitters = SseEmitterFactory()
    private val wiring = TurnWiring()
    private val clientWindow = ClientWindowWitness(deps.stores.clientWindows)
    private val driveDispatcher = ProcessDispatchers().io()

    // The drive handles every turn failure itself; anything that still escapes a detached
    // compaction is a bug, logged by class (safe-failure-render) rather than lost to stderr.
    private val detachedContext = CoroutineName("compaction-detached") +
        CoroutineExceptionHandler { _, e ->
            deps.log("[${provider.key}] detached compaction crashed (${e::class.simpleName})\n")
        }

    /** The emitter whose pinger frames go through the pending response's progress port and whose
     *  content-reached answer reads what this turn has actually written to the client. */
    private fun streamingEmitter(call: ApplicationCall, inputs: TurnInputs, pending: PendingSse): SseEmitter {
        val meta = inputs.built.meta
        val perf = inputs.perf
        return emitters.create(
            write = pending::model,
            streaming = StreamWiring(
                pending::progress,
                ContentReached { (perf.snapshot().counters[PerfKeys.CONTENT_FRAMES_OUT] ?: 0L) > 0 },
            ),
            model = meta.originalModel,
            usagePayload = wiring.usagePayloadBuilder(provider.catalog, meta, clientWindow.of(call, meta.sessionId)),
        )
    }

    /** Drive the turn while holding HTTP status, then attach the SSE writer or return HTTP 400.
     * A detached compaction takes its slot as soon as its drive starts; [TurnInputs.markHandedOff]
     * is the durable cancellation-path signal, and this return reports the same handoff. */
    suspend fun stream(call: ApplicationCall, inputs: TurnInputs): Boolean {
        val built = inputs.built
        val perf = inputs.perf
        val replayKey = if (built.meta.compact) replay.key(built.meta, built.requestBody) else null
        // A compaction always records: the detached scope outlives a head restart, so there is
        // always a scope to detach onto (the launch below is ATOMIC, so its finally settles the slot
        // and the recording even if the scope is cancelled in between).
        val recording = if (replayKey != null) FrameRecording(deps.seams.requestMaterializationGate.heap) else null
        // The head's quota windows ride every response as the headers Claude Code reads into its
        // rate_limits (the 5h/7d bars): the client sees the head's real plan usage, proxy or not.
        return coroutineScope {
            // Headers remain uncommitted until a model frame, a non-size error, or the hold expires.
            // Structural message_start/ping are staged, not counted as sent.
            val pending = pending(inputs, recording)
            val channel = pending.channel
            val emitter = streamingEmitter(call, inputs, pending)
            val drive = driveFactory.assembleDrive(inputs, emitter, channel)
            drive.rateLimitRelay = TurnDrive.RateLimitRelay(pending::refuse)
            drive.upstreamAccepted = splice.upstream.StreamStart(pending::accepted)
            // Ktor's SseResponse body previously ran blocking Writer calls on its IO bridge.
            // The drive now starts before respond, so use the process IO adapter instead of Netty.
            val running = async(driveDispatcher) {
                try {
                    runPending(drive, inputs, replayKey, recording, pending)
                } finally {
                    pending.finish()
                }
            }
            // Staged pings cannot detect a disconnect before Ktor opens the response.
            // Netty's closeFuture is the same pre-response signal collect() already uses.
            val turnJob = requireNotNull(coroutineContext[Job])
            val watch = launch {
                ClientConnectionClose.await(call)
                channel.connectionClosed(turnJob)
            }
            try {
                respondPending(call, pending, running, drive)
                running.await()
            } catch (cancelled: CancellationException) {
                if (recording == null) {
                    pending.abortClient()
                } else {
                    withContext(NonCancellable) { pending.detachForRecording() }
                }
                throw cancelled
            } finally {
                watch.cancel()
            }
        }
    }

    private fun pending(inputs: TurnInputs, recording: FrameRecording?): PendingSse = PendingSse(
        inputs.perf,
        deps.seams.clock,
        inputs.trace,
        recording,
        holdMs = if (provider.relayRateLimitReplies) provider.watchdog.totalCap.inWholeMilliseconds else 120_000L,
        commitProgress = !provider.relayRateLimitReplies,
    )

    private fun applyQuotaHeaders(call: ApplicationCall, drive: TurnDrive) {
        drive.accountHandoff?.commit()
        drive.quota?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
    }

    private suspend fun respondPending(
        call: ApplicationCall,
        pending: PendingSse,
        running: Deferred<Boolean>,
        drive: TurnDrive,
    ) {
        when (val choice = pending.decide()) {
            PendingSse.Decision.Stream -> {
                applyQuotaHeaders(call, drive)
                call.respond(
                    SseResponse { out ->
                        pending.attach(out)
                        try {
                            running.await()
                        } finally {
                            pending.channel.flushQuietly()
                        }
                    },
                )
            }
            is PendingSse.Decision.Refused -> {
                running.await()
                choice.reply.headers.forEach { (name, values) -> values.forEach { call.response.header(name, it) } }
                call.respondText(
                    choice.reply.body,
                    ContentType.Application.Json,
                    HttpStatusCode.fromValue(choice.reply.status),
                )
            }
            is PendingSse.Decision.Overflow -> {
                running.await()
                call.response.header("x-should-retry", "false")
                call.respondText(choice.body, ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }

    private suspend fun runPending(
        drive: TurnDrive,
        inputs: TurnInputs,
        replayKey: String?,
        recording: FrameRecording?,
        pending: PendingSse,
    ): Boolean = if (replayKey == null || recording == null) {
        try {
            sealedDrive.driveSealingCancellation(drive)
        } finally {
            drive.channel.flushQuietly()
        }
        false
    } else {
        driveDetachable(drive, inputs, replayKey, recording, pending)
        true
    }

    /** Runs the drive on [detachedScope] and waits for it. Ktor cancelling THIS call (the client hung
     *  up mid-lull, no write having failed) detaches the channel and returns; the drive runs on.
     *  The slot goes with the drive (TurnInputs.slotHandedOff): released when the upstream turn
     *  ends, whichever way, not when this call does. */
    private suspend fun driveDetachable(
        drive: TurnDrive,
        inputs: TurnInputs,
        key: String,
        recording: FrameRecording,
        pending: PendingSse,
    ) {
        val job = DetachedCompaction(provider.key, deps.log, sealedDrive, replay, detachedScope, detachedContext)
            .launch(drive, inputs, key, recording)
        try {
            job.join()
        } catch (e: CancellationException) {
            // Before Ktor opens SseResponse there is no writer to attach. Unblock the detached
            // drive and transfer staged structural frames into its replay recording.
            if (job.isActive && withContext(NonCancellable) { pending.detachForRecording() }) {
                val who = drive.sessionTag()?.let { "session $it, " } ?: ""
                deps.log(
                    "[${provider.key}] client gone (${who}call cancelled); " +
                        "compaction continues detached; its answer is held for a retry\n",
                )
            }
            throw e
        }
    }

    /** Head stop: a detached compaction has no head to record for — end the ones still driving
     *  (each finally releases its slot and drops its recording). The scope itself survives, because
     *  HeadServer restarts on this same streamer and a restart must be able to detach again. */
    fun stopDetached() {
        detachedScope.coroutineContext.cancelChildren()
    }
}
