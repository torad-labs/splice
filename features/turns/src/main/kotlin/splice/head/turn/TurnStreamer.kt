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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
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
import splice.head.turn.stream.PendingSse
import splice.head.wire.FrameRecording
import splice.head.wire.SseEmitterFactory
import splice.head.wire.SseResponse
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

    /** Drive the turn while holding HTTP status, then attach the SSE writer or return HTTP 400.
     * A detached compaction takes its slot as soon as its drive starts; [TurnInputs.markHandedOff]
     * is the durable cancellation-path signal, and this return reports the same handoff. */
    suspend fun stream(call: ApplicationCall, inputs: TurnInputs): Boolean {
        val built = inputs.built
        val perf = inputs.perf
        val replayKey = if (built.meta.compact) replay.key(built.meta, built.requestBody.toString()) else null
        // A compaction always records: the detached scope outlives a head restart, so there is
        // always a scope to detach onto (the launch below is ATOMIC, so its finally settles the slot
        // and the recording even if the scope is cancelled in between).
        val recording = if (replayKey != null) FrameRecording() else null
        // The head's quota windows ride every response as the headers Claude Code reads into its
        // rate_limits (the 5h/7d bars): the client sees the head's real plan usage, proxy or not.
        applyQuotaHeaders(call, inputs)
        return coroutineScope {
            // Headers remain uncommitted until a model frame, a non-size error, or the hold expires.
            // Structural message_start/ping are staged, not counted as sent.
            val pending = PendingSse(perf, deps.seams.clock, inputs.trace, recording)
            val channel = pending.channel
            val emitter = emitters.create(
                write = pending::model,
                progressWrite = pending::progress,
                contentReached = { (perf.snapshot().counters[PerfKeys.CONTENT_FRAMES_OUT] ?: 0L) > 0 },
                model = built.meta.originalModel,
                usagePayload = wiring.usagePayloadBuilder(
                    provider.catalog,
                    built.meta,
                    clientWindow.of(call, built.meta.sessionId),
                ),
            )
            val drive = driveFactory.assembleDrive(inputs, emitter, channel)
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
                respondPending(call, pending, running)
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

    private fun applyQuotaHeaders(call: ApplicationCall, inputs: TurnInputs) {
        val quota = deps.turnQuota.forSession(inputs.built.meta.sessionId, inputs.account)
        quota?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
    }

    private suspend fun respondPending(
        call: ApplicationCall,
        pending: PendingSse,
        running: Deferred<Boolean>,
    ) {
        when (val choice = pending.decide()) {
            PendingSse.Decision.Stream -> call.respond(
                SseResponse { out ->
                    pending.attach(out)
                    try {
                        running.await()
                    } finally {
                        pending.channel.flushQuietly()
                    }
                },
            )
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
    // CoroutineStart.ATOMIC is a delicate API, used for the reason the comment below gives.
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun driveDetachable(
        drive: TurnDrive,
        inputs: TurnInputs,
        key: String,
        recording: FrameRecording,
        pending: PendingSse,
    ) {
        val slotEnd = AutoCloseable { inputs.slot.release() }
        replay.begin(key, recording)
        inputs.markHandedOff()
        // ATOMIC: the body starts even if the scope was cancelled, so the finally below always runs;
        // the drive's first suspension then throws the cancellation and the seal writes the honest
        // error frame to the still-attached client.
        val job = detachedScope.launch(detachedContext, start = CoroutineStart.ATOMIC) {
            var completed = false
            var kept = false
            // Nested use keeps the first throwable and suppresses later cleanup failures in order.
            // The replay and permit still settle after a fatal recording or flush failure.
            slotEnd.use {
                AutoCloseable {
                    kept = keepRecording(drive, completed)
                    replay.finish(key, recording, keep = kept)
                }.use {
                    driveRecorded(drive, recording) { completed = true }
                }
            }
            if (drive.channel.detached.get()) deps.log(finishLine(drive, recording, kept))
        }
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

    private fun keepRecording(drive: TurnDrive, completed: Boolean): Boolean =
        completed && drive.channel.detached.get() && drive.emitter.endedCleanly

    private fun interface RecordingCompleted {
        operator fun invoke()
    }

    /** Record the terminal's verdict, then flush even when completion fails; neither replaces the drive failure. */
    private suspend fun driveRecorded(
        drive: TurnDrive,
        recording: FrameRecording,
        completed: RecordingCompleted,
    ) {
        AutoCloseable { drive.channel.flushQuietly() }.use {
            AutoCloseable {
                recording.complete(drive.emitter.endedCleanly)
                completed()
            }.use {
                sealedDrive.driveSealingCancellation(drive)
            }
        }
    }

    /** Head stop: a detached compaction has no head to record for — end the ones still driving
     *  (each finally releases its slot and drops its recording). The scope itself survives, because
     *  HeadServer restarts on this same streamer and a restart must be able to detach again. */
    fun stopDetached() {
        detachedScope.coroutineContext.cancelChildren()
    }

    private fun finishLine(drive: TurnDrive, recording: FrameRecording, kept: Boolean): String {
        val who = drive.sessionTag()?.let { "session $it" } ?: "no session"
        return if (kept) {
            "[${provider.key}] detached compaction finished ($who): " +
                "${recording.size} frames held for a byte-identical retry\n"
        } else {
            "[${provider.key}] detached compaction ended without a terminal frame ($who): " +
                "nothing held; a retry runs upstream\n"
        }
    }
}
