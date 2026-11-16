// NEW: server mode (docs/specs/server-mode.md, F4: "long prefills keep the client's connection alive"). One answer's
// body as it arrives, with no thread waiting on it. A relayed turn waits a long while on its model server, minutes
// through a long prefill, and a team's server holds as many of those waits at once as its people's caps add up to.
// A wait that parks a thread, as a read of an InputStream does, takes that thread from everything else that runs
// where it runs, and those threads are few: with 64 streams waiting, no stream was opened or kept alive any more, and
// the next request was not sent to its server until one of them answered. So the body is taken from the JDK client
// the way it publishes one (Flow.Publisher): a piece when the relay asks for the next, and the relay suspended until
// it has come. An answer nobody is reading holds its server back and nothing here.
//
// A relay that keeps its client alive has to know when the answer has started, and the answer's response headers do
// not say: a model server that streams from a generator (vLLM and other FastAPI servers) sends them at once and then
// prefills in silence, where another holds them back until its first token. The first byte of the body is what both
// have in common. So the first piece may be asked for ahead of the relaying, by the relay that waits to learn whether
// the answer started in time, and that piece is then the first thing relayed.
package splice.share

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Flow

/** The body of one answer, subscribed to what the JDK client publishes of it (RelayClient). */
internal class AnswerBody : Flow.Subscriber<List<ByteBuffer>>, AutoCloseable {
    private val subscribed = CompletableDeferred<Unit>()

    // The pieces that came and were not taken yet: one at most, since each is asked for when the last was taken.
    private val pieces = Channel<ByteArray>(Channel.UNLIMITED)

    // What the body is asked through while it is still arriving: null before the client has subscribed this, and
    // again once the body has ended. An exchange that is over is the client's to keep, its connection included, so
    // nothing is asked of it or cancelled on it from then.
    @Volatile
    private var arriving: Flow.Subscription? = null

    @Volatile
    private var closed = false

    // The piece asked for ahead, until it is given.
    private var ahead: Result<ByteArray?>? = null

    override fun onSubscribe(subscription: Flow.Subscription) {
        arriving = subscription
        // Closed before there was a body to hang up on: it is hung up on now.
        if (closed) subscription.cancel()
        subscribed.complete(Unit)
    }

    override fun onNext(item: List<ByteBuffer>) {
        val piece = ByteArray(item.sumOf { it.remaining() })
        var at = 0
        for (buffer in item) {
            val length = buffer.remaining()
            buffer.get(piece, at, length)
            at += length
        }
        pieces.trySend(piece)
    }

    override fun onError(throwable: Throwable) {
        arriving = null
        // Whatever broke the answer off is an I/O failure to the relay, as a read of a stream throws it.
        pieces.close(throwable as? IOException ?: IOException(throwable))
    }

    override fun onComplete() {
        arriving = null
        pieces.close()
    }

    /** Asks for the first piece now. Back once the answer has started: its first bytes have come, or it has ended or
     *  broken off without any, which the first [next] then says. */
    suspend fun begin() {
        ahead = arrived()
    }

    /** The bytes that came next, null where the answer ended, or what it broke off with. A piece asked for ahead is
     *  given first, at once. */
    suspend fun next(): Result<ByteArray?> = ahead?.also { ahead = null } ?: arrived()

    /** The whole of the answer from here on, or what it broke off with before its end. */
    suspend fun whole(): Result<ByteArray> {
        val whole = ByteArrayOutputStream()
        while (true) {
            val piece = next().getOrElse { return Result.failure(it) } ?: return Result.success(whole.toByteArray())
            whole.writeBytes(piece)
        }
    }

    /** Asks the client for one more piece and waits for it, suspended. A piece of no bytes says nothing, so the one
     *  after it is asked for. */
    private suspend fun arrived(): Result<ByteArray?> {
        while (true) {
            subscribed.await()
            arriving?.request(1)
            val came = pieces.receiveCatching()
            val piece = came.getOrNull() ?: return ended(came.exceptionOrNull())
            if (piece.isNotEmpty()) return Result.success(piece)
        }
    }

    /** How the body ended: whole, or broken off with [broken]. A body that was closed under its reader ends that
     *  reader as it is ending already, cancelled. */
    private fun ended(broken: Throwable?): Result<ByteArray?> = when (broken) {
        null -> Result.success(null)
        is IOException -> Result.failure(broken)
        else -> throw broken
    }

    /** Hangs up: the client is told that no more of the body is wanted, which closes its connection to a server
     *  that had not finished its answer. A body that has ended is left as it is. */
    override fun close() {
        closed = true
        arriving?.cancel()
        pieces.cancel()
    }
}
