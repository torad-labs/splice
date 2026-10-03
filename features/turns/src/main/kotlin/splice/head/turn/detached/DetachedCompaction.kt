// NEW: detached compaction ownership from handoff through recording, replay, and permit settlement.
package splice.head.turn.detached

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.compaction.CompactionReplay
import splice.head.turn.SealedDrive
import splice.head.turn.TurnDrive
import splice.head.turn.TurnInputs
import splice.head.wire.FrameRecording
import kotlin.coroutines.CoroutineContext

// why: inspect bounded coroutine-recovery cause wrappers without following a cyclic Throwable chain forever.
private const val CANCELLATION_CAUSE_DEPTH = 4

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
        return Cancellables.withCleanup({ if (!launched) abandonLaunch(inputs, key, recording) }) {
            replay.begin(key, recording)
            inputs.markHandedOff()
            // ATOMIC enters the cleanup scopes even when head stop races the launch.
            val job = scope.launch(context, start = CoroutineStart.ATOMIC) {
                try {
                    Cancellables.withCleanup({
                        if (drive.channel.detached.get()) log(finishLine(drive, recording, kept))
                    }) {
                        Cancellables.withCleanup({ inputs.slot.release() }) {
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
        Cancellables.withCleanup({ inputs.slot.release() }) {
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
