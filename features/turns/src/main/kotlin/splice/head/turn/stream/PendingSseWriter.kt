// NEW: V4-446 — stage pre-commit frames and preserve compaction replay on client loss.
package splice.head.turn.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.head.wire.ClientChannel
import splice.head.wire.FrameRecording
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.StatusGate
import splice.head.wire.TurnTrace
import java.io.IOException
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean

/** The delivery half of the HTTP status gate. Undelivered frames are neither traced nor counted. */
internal class PendingSseWriter(
    private val perf: TurnPerf,
    private val clock: ElapsedClock,
    trace: TurnTrace?,
    recording: FrameRecording?,
    private val choice: CompletableDeferred<PendingSse.Decision>,
) {
    private enum class Kind { MODEL, PROGRESS, RAW }
    private data class Frame(val text: String, val kind: Kind)

    private val lock = Any()
    private val staged = ArrayList<Frame>()
    private val attached = CompletableDeferred<Unit>()
    private var pendingBytes = false

    @Volatile private var writer: Writer? = null

    val channel = ClientChannel(
        coalesced = ImmediateSseWriter(
            writeRaw = { text ->
                val out = synchronized(lock) {
                    writer.also { if (it == null) staged += Frame(text, Kind.RAW) }
                }
                out?.write(text)
                if (out != null && text.isNotEmpty()) pendingBytes = true
            },
            flushRaw = {
                writer?.let { out ->
                    out.flush()
                    if (pendingBytes) {
                        perf.firstClientByte()
                        pendingBytes = false
                    }
                }
            },
        ),
        writeMutex = Mutex(),
        clientGone = AtomicBoolean(false),
        recording = recording,
        trace = trace,
        statusGate = object : StatusGate {
            override fun open() {
                choice.complete(PendingSse.Decision.Stream)
            }
        },
    )

    fun stageModel(frame: String): Boolean = stage(frame, Kind.MODEL)

    fun stageProgress(frame: String): Boolean = stage(frame, Kind.PROGRESS)

    private fun stage(frame: String, kind: Kind): Boolean = synchronized(lock) {
        if (choice.isCompleted) {
            false
        } else {
            staged += Frame(frame, kind)
            true
        }
    }

    suspend fun writeModel(frame: String) {
        attached.await()
        channel.writeMutex.withLock { channel.timedClientWrite(frame, perf, clock) }
    }

    suspend fun writeProgress(frame: String) {
        attached.await()
        channel.writeMutex.withLock { channel.timedProgressWrite(frame, perf, clock) }
    }

    /** A cancelled ordinary call has no writer to attach; its drive must release admission. */
    fun abortClient() {
        channel.clientGone.set(true)
        choice.complete(PendingSse.Decision.Stream)
        attached.completeExceptionally(IOException("client disconnected before stream attachment"))
    }

    suspend fun attach(out: Writer) {
        try {
            flushStaged(out)
            attached.complete(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            failAttachment(failure)
        } catch (failure: IllegalStateException) {
            failAttachment(failure)
        }
    }

    /** Restore the unwritten suffix while still holding writeMutex, before detachment can drain it. */
    private suspend fun flushStaged(out: Writer) = channel.writeMutex.withLock {
        val batch = synchronized(lock) {
            writer = out
            staged.toList().also { staged.clear() }
        }
        var consumed = 0
        try {
            batch.forEach { frame ->
                // ClientChannel records a model/progress frame before attempting socket I/O.
                consumed++
                when (frame.kind) {
                    Kind.MODEL -> channel.timedClientWrite(frame.text, perf, clock)
                    Kind.PROGRESS -> channel.timedProgressWrite(frame.text, perf, clock)
                    Kind.RAW -> Unit // Stale pre-commit transport probes never replay to a socket.
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                writer = null
                staged.addAll(0, batch.subList(consumed, batch.size))
            }
            throw cancelled
        }
    }

    private fun failAttachment(failure: Exception): Nothing {
        attached.completeExceptionally(failure)
        throw failure
    }

    /** A cancelled compaction call has no response body to attach; let its recording continue. */
    suspend fun detachForRecording(): Boolean {
        if (!channel.detachIfRecording()) return false
        choice.complete(PendingSse.Decision.Stream)
        // A fully attached writer has already drained its staged frames. A writer still
        // attaching must finish or return its unwritten batch before replay is released.
        if (attached.isCompleted) return true
        try {
            channel.writeMutex.withLock {
                val batch = synchronized(lock) { staged.toList().also { staged.clear() } }
                if (choice.await() is PendingSse.Decision.Stream) {
                    batch.forEach { frame ->
                        when (frame.kind) {
                            Kind.MODEL -> channel.timedClientWrite(frame.text, perf, clock)
                            Kind.PROGRESS -> channel.timedProgressWrite(frame.text, perf, clock)
                            Kind.RAW -> Unit // SSE comments are transport only, not replay frames.
                        }
                    }
                }
            }
        } finally {
            attached.complete(Unit)
        }
        return true
    }
}
