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
// stop ends the compactions still driving (cancelChildren) and never the scope — a cancelled scope
// turned the first compaction after a restart into an empty 200 with its slot handed off to
// nobody (review 2026-09-05, splice-astra). A scope that cannot launch any more (the daemon's own,
// at shutdown) is checked before the hand-off, and the launch is ATOMIC so its finally settles
// the slot and the recording even when the cancellation lands between the check and the start.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.FrameRecording
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.SseEmitterFactory
import splice.gateway.wire.TurnWiring
import splice.spi.LifecycleScope
import splice.spi.ProcessDispatchers
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean

internal class TurnStreamer(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val driveFactory: TurnDriveFactory,
    private val driver: TurnDriver,
    private val replay: CompactionReplay,
    /** Where a detached compaction runs: a NAMED owner (LifecycleScope: a supervisor, so one failing
     *  compaction never cancels a sibling) with no parent, so the call's cancellation cannot reach
     *  it, on the background lane of the runtime seam (HD-19). Injectable for the same reason the
     *  auth providers' prefetch scope is: a test can drain it before teardown. */
    private val detachedScope: CoroutineScope = LifecycleScope(ProcessDispatchers().background()),
) {
    private val emitters = SseEmitterFactory()
    private val wiring = TurnWiring()

    // The drive handles every turn failure itself; anything that still escapes a detached
    // compaction is a bug, logged by class (safe-failure-render) rather than lost to stderr.
    private val detachedContext = CoroutineName("compaction-detached") +
        CoroutineExceptionHandler { _, e ->
            deps.log("[${provider.key}] detached compaction crashed (${e::class.simpleName})\n")
        }

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, inputs: TurnInputs) {
        val built = inputs.built
        val perf = inputs.perf
        val replayKey = if (built.meta.compact) replay.key(built.meta.sessionId, built.requestBody.toString()) else null
        // No recording without a scope to detach onto: the compaction then runs attached, as every
        // turn did before 2026-09-05, instead of being handed to a launch that never starts.
        val recording = if (replayKey != null && detachedScope.isActive) FrameRecording() else null
        // The head's quota windows ride every response as the headers Claude Code reads into its
        // rate_limits (the 5h/7d bars): the client sees the head's real plan usage, proxy or not.
        deps.quota?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Flush-per-frame: a frame buffered across an upstream lull is invisible to the
            // user exactly when responsiveness matters (see ImmediateSseWriter header).
            val channel = ClientChannel(
                coalesced = ImmediateSseWriter(writeRaw = { frame -> write(frame) }, flushRaw = { flush() }),
                writeMutex = Mutex(),
                clientGone = AtomicBoolean(false),
                recording = recording,
            )
            val emitter = emitters.create(
                write = { frame ->
                    channel.writeMutex.withLock { channel.timedClientWrite(frame, perf, deps.clock) }
                },
                // The pinger's own frames (heartbeat ping, status line) — same socket, same mutex,
                // never counted as model output. See ClientChannel.timedProgressWrite.
                progressWrite = { frame ->
                    channel.writeMutex.withLock { channel.timedProgressWrite(frame, perf, deps.clock) }
                },
                model = built.meta.originalModel,
                usagePayload = wiring.usagePayloadBuilder(
                    provider.catalog,
                    built.meta,
                    deps.clientWindows.windowFor(built.meta.sessionId),
                ),
            )
            val drive = driveFactory.assembleDrive(inputs, emitter, channel)
            if (replayKey == null || recording == null) {
                try {
                    // The 200 + SSE headers are committed once respondTextWriter opens, so any failure
                    // must become an honest `event: error` frame — NOT escape and leave the client an
                    // empty/truncated 200 (the "empty or malformed response (HTTP 200)" class).
                    driver.driveSealingCancellation(drive)
                } finally {
                    // Terminal frames force-flush already; this covers abandon / exception paths.
                    // DR-93 (redo): quiet by contract — see ClientChannel.flushQuietly. A raw
                    // coalesced.flush() here is walled off (kt-turn-finally-flush-quietly): its
                    // dead-socket throw would replace the primary outcome or cancellation.
                    channel.flushQuietly()
                }
            } else {
                driveDetachable(drive, inputs, replayKey, recording)
            }
        }
    }

    /** Runs the drive on [detachedScope] and waits for it. Ktor cancelling THIS call (the client hung
     *  up mid-lull, no write having failed) detaches the channel and returns; the drive runs on.
     *  The slot goes with the drive (TurnInputs.slotHandedOff): released when the upstream turn
     *  ends, whichever way, not when this call does. */
    private suspend fun driveDetachable(drive: TurnDrive, inputs: TurnInputs, key: String, recording: FrameRecording) {
        replay.begin(key, recording)
        inputs.slotHandedOff.set(true)
        // ATOMIC: the body starts even if the scope was cancelled since the isActive check, so the
        // finally below always runs; the drive's first suspension then throws the cancellation and
        // the seal writes the honest error frame to the still-attached client.
        val job = detachedScope.launch(detachedContext, start = CoroutineStart.ATOMIC) {
            try {
                driver.driveSealingCancellation(drive)
            } finally {
                // The terminal's own verdict, not a frame literal (L3): an error frame or an
                // abandon is not an answer a retry may be handed. It goes into the recording too,
                // for a retry already following it (LocalResponses.replay seals on a false).
                val whole = drive.emitter.endedCleanly
                recording.complete(whole)
                drive.channel.flushQuietly()
                val wasDetached = drive.channel.detached.get()
                val kept = wasDetached && whole
                replay.finish(key, recording, keep = kept)
                inputs.slot.release()
                if (wasDetached) deps.log(finishLine(drive, recording, kept))
            }
        }
        try {
            job.join()
        } catch (e: CancellationException) {
            if (job.isActive && drive.channel.detachIfRecording()) {
                val who = drive.sessionTag()?.let { "session $it, " } ?: ""
                deps.log(
                    "[${provider.key}] client gone (${who}call cancelled) — " +
                        "compaction continues detached; its answer is held for a retry\n",
                )
            }
            throw e
        }
    }

    /** Head stop: a detached compaction has no head to record for — end the ones still driving
     *  (each finally releases its slot and drops its recording). The scope itself survives, because
     *  HeadServer restarts on this same streamer and a cancelled scope launches nothing (header). */
    fun stopDetached() {
        detachedScope.coroutineContext.cancelChildren()
    }

    private fun finishLine(drive: TurnDrive, recording: FrameRecording, kept: Boolean): String {
        val who = drive.sessionTag()?.let { "session $it" } ?: "no session"
        return if (kept) {
            "[${provider.key}] detached compaction finished ($who) — " +
                "${recording.size} frames held for a byte-identical retry\n"
        } else {
            "[${provider.key}] detached compaction ended without a terminal frame ($who) — " +
                "nothing held; a retry runs upstream\n"
        }
    }
}
