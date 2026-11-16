// NEW: server mode (docs/specs/server-mode.md, F4: "long prefills keep the client's connection alive"). One answer's
// event stream as a relay reads it, a chunk at a time. A relay that keeps its client alive has to know when the answer
// has started, and the answer's response headers do not say: a model server that streams from a generator (vLLM and
// other FastAPI servers) sends them at once and then prefills in silence, where another holds them back until its
// first token. The first byte of the stream is what both have in common. So the first read may be made ahead of the
// relaying, by the relay that waits to learn whether the answer started in time, and what that read gave is then the
// first thing relayed.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import splice.core.util.Cancellables
import java.io.InputStream

/** The event stream [input], read on [io], where blocking JDK I/O runs. */
internal class AnswerReads(private val input: InputStream, private val io: CoroutineDispatcher) {
    /** Where each read leaves its bytes, until the next one. */
    val bytes = ByteArray(PUMP_BUFFER_BYTES)

    // The read made ahead, until it is given.
    private var ahead: Result<Int>? = null

    /** Makes the first read now. Back once the answer has started: its first bytes have come, or it has ended or
     *  broken off without any, which the first [next] then says. */
    suspend fun begin() {
        ahead = read()
    }

    /** How many of [bytes] the next read filled, [END_OF_STREAM] where the stream ended, or what it broke off with.
     *  [wire]'s client is kept alive while the read waits; a read made ahead is given at once. */
    suspend fun next(wire: QuietWire): Result<Int> = ahead?.also { ahead = null } ?: wire.during { read() }

    private suspend fun read(): Result<Int> =
        runInterruptible(io) { Cancellables.runCatchingCancellable { input.read(bytes) } }
}

// why: one read of the head's answer: an SSE event is a few hundred bytes, so 16 KiB carries a whole burst per flush
// while holding little memory for each relayed stream.
private const val PUMP_BUFFER_BYTES = 16 * 1024

// What InputStream.read answers at the end of the stream.
internal const val END_OF_STREAM = -1
