// NEW: detached compaction ownership from handoff through recording, replay, and permit settlement.
package splice.head.turn.detached

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.compaction.CompactionReplay
import splice.head.turn.SealedDrive
import splice.head.turn.TurnDrive
import splice.head.turn.TurnInputs
import splice.head.wire.FrameRecording
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

// why: inspect bounded coroutine-recovery cause wrappers without following a cyclic Throwable chain forever.
private const val CANCELLATION_CAUSE_DEPTH = 4

/** What a collected compaction holds for its retry: the terminal's one JSON body. A named seam
 *  rather than a lambda (HD-22, like [splice.head.wire.UsagePayloadBuilder]): any
 *  `(TurnDrive) -> JsonObject?` satisfied it under a name the call site can document. Null is a
 *  drive that ended without the clean terminal a hold may replay. */
internal fun interface HeldCollectAnswer {
    public suspend operator fun invoke(drive: TurnDrive): JsonObject?
}

internal class DetachedCompaction(
    private val providerKey: String,
    private val log: LogSink,
    private val sealedDrive: SealedDrive,
    private val replay: CompactionReplay,
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
) {
    /** Captured state is allocated before handoff; launch allocation failures still settle ownership. */
    @OptIn(DelicateCoroutinesApi::class)
    fun launch(drive: TurnDrive, inputs: TurnInputs, key: String, recording: FrameRecording): Job {
        var completed = false
        var kept = false
        var launched = false
        val claimed = AtomicBoolean(false)
        return Cancellables.withCleanup({
            if (!launched && claimed.compareAndSet(false, true)) abandonLaunch(inputs, key, recording)
        }) {
            replay.begin(key, recording)
            inputs.slot.markHandedOff()
            // ATOMIC enters the cleanup scopes even when head stop races the launch.
            val job = scope.launch(context, start = CoroutineStart.ATOMIC) {
                // Dispatch may enqueue then throw while starting a worker. Only the winning owner can drive.
                if (!claimed.compareAndSet(false, true)) return@launch
                try {
                    Cancellables.withCleanup({
                        if (drive.channel.detached.get()) log(finishLine(drive, recording, kept))
                    }) {
                        Cancellables.withCleanup({ inputs.slot.held.release() }) {
                            Cancellables.withCleanup({
                                kept = keepRecording(drive, completed)
                                replay.finish(key, recording, keep = kept)
                            }) {
                                driveRecorded(drive, recording) { completed = true }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Cancellables.withCleanup({ reportFatalCleanup(cancelled) }) { throw cancelled }
                }
            }
            launched = true
            job
        }
    }

    private fun abandonLaunch(inputs: TurnInputs, key: String, recording: FrameRecording) {
        Cancellables.withCleanup({ inputs.slot.held.release() }) {
            Cancellables.withCleanup({ replay.finish(key, recording, keep = false) }) {
                recording.complete(whole = false)
            }
        }
    }

    /** Cancellation bypasses CoroutineExceptionHandler; fatal cleanup must still have a safe diagnostic. */
    private fun reportFatalCleanup(cancelled: CancellationException) {
        generateSequence<Throwable>(cancelled) { it.cause }.take(CANCELLATION_CAUSE_DEPTH).forEach { cause ->
            cause.suppressed.filterIsInstance<Error>().forEach { fatal ->
                log("[$providerKey] detached compaction fatal cleanup: ${SafeFailureText.render(fatal)}\n")
            }
        }
    }

    /** The non-stream sibling of [launch]: a collect turn has no frames to record, so a cleanly ended
     *  drive whose client left settles its COLLECTED body into [hold] under [key]'s answer ledger
     *  (CompactionReplay.beginAnswer); a delivered turn keeps nothing — its client has the answer.
     *  The nesting mirrors [launch] exactly: the slot and the hold settle whichever way the drive ends,
     *  an abandoned launch settles null, and a crash reports through the same safe handler. */
    @OptIn(DelicateCoroutinesApi::class)
    fun launchCollected(
        drive: TurnDrive,
        inputs: TurnInputs,
        key: String,
        hold: CompletableDeferred<JsonObject?>,
        held: HeldCollectAnswer,
    ): Job {
        var completed = false
        var kept = false
        var launched = false
        val claimed = AtomicBoolean(false)
        return Cancellables.withCleanup({
            if (!launched && claimed.compareAndSet(false, true)) {
                Cancellables.withCleanup({ inputs.slot.held.release() }) {
                    Cancellables.withCleanup({ replay.answers.finish(key, hold, null, keep = false) }) { }
                }
            }
        }) {
            inputs.slot.markHandedOff()
            var answer: JsonObject? = null
            val job = scope.launch(context, start = CoroutineStart.ATOMIC) {
                if (!claimed.compareAndSet(false, true)) return@launch
                try {
                    Cancellables.withCleanup({
                        if (drive.channel.detached.get()) log(finishLineCollected(drive, kept))
                    }) {
                        Cancellables.withCleanup({ inputs.slot.held.release() }) {
                            Cancellables.withCleanup({
                                kept = keepRecording(drive, completed)
                                replay.answers.finish(key, hold, answer, keep = kept)
                            }) {
                                Cancellables.withCleanup({ drive.channel.flushQuietly() }) {
                                    Cancellables.withCleanup({ completed = true }) {
                                        sealedDrive.driveSealingCancellation(drive)
                                        // Read once the seal settled the terminal: a failed one leaves null.
                                        answer = held(drive)
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Cancellables.withCleanup({ reportFatalCleanup(cancelled) }) { throw cancelled }
                }
            }
            launched = true
            job
        }
    }

    private fun finishLineCollected(drive: TurnDrive, kept: Boolean): String {
        val who = drive.sessionTag()?.let { "session $it" } ?: "no session"
        return if (kept) {
            "[$providerKey] detached compaction finished ($who): " +
                "its collected answer held for a byte-identical retry\n"
        } else {
            "[$providerKey] detached compaction ended without a terminal frame ($who): " +
                "nothing held; a retry runs upstream\n"
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
        Cancellables.withCleanup({ drive.channel.flushQuietly() }) {
            Cancellables.withCleanup({
                recording.complete(drive.emitter.endedCleanly)
                completed()
            }) {
                sealedDrive.driveSealingCancellation(drive)
            }
        }
    }

    private fun finishLine(drive: TurnDrive, recording: FrameRecording, kept: Boolean): String {
        val who = drive.sessionTag()?.let { "session $it" } ?: "no session"
        return if (kept) {
            "[$providerKey] detached compaction finished ($who): " +
                "${recording.size} frames held for a byte-identical retry\n"
        } else {
            "[$providerKey] detached compaction ended without a terminal frame ($who): " +
                "nothing held; a retry runs upstream\n"
        }
    }
}
