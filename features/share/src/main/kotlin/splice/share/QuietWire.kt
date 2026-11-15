// NEW: server mode (docs/specs/server-mode.md, F4: "long prefills keep the client's connection alive"). A model server
// prefilling a long prompt sends nothing for minutes, and a proxy between the client and this machine closes a
// connection that carries nothing for that long. This is the client's side of a relayed event stream that is kept
// alive through such a silence: whatever the relay waits on (the head's answer, or the next bytes of it), an SSE
// comment is written each time the wait outlasts the interval. A comment is a line every SSE reader skips, so the
// client hears only the head's events. One is written only where the stream stands between two events, never inside
// an event the head has half written. With no interval nothing is written but the head's own bytes, which is how LAN
// share relays.
//
// Whether a wait has outlasted its time is the relay's clock's to say (RelayClock). The relay asks it twice: for the
// second a silent head is given before its client's stream is opened (ShareRelay.forward), and for each interval
// between two keepalives. Production waits on the process clock; a test says when the time has passed, so it reads
// what a client hears after a silence without sitting through one.
package splice.share

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import kotlin.time.Duration

/** The clock a relay that keeps its client alive goes by. The time it tells passes on [waiter]: the process clock,
 *  unless a test hands it one it steps. */
public class RelayClock(private val waiter: Waiter = ProcessWaiter()) {
    /** True once [awaited] has ended. False once [ms] have passed with it still going; it goes on, and the caller
     *  may ask again. Something that ends as the time passes has ended. */
    internal suspend fun endedWithin(ms: Long, awaited: Job): Boolean = coroutineScope {
        val passing = launch { waiter.wait(ms) }
        select {
            awaited.onJoin { true }
            passing.onJoin { false }
        }.also { passing.cancel() }
    }
}

/** What a relay waits on while its client hears nothing: the head's answer, or the next bytes of it. */
internal fun interface HeadWait<T> {
    suspend operator fun invoke(): T
}

internal class QuietWire(private val out: ByteWriteChannel, every: Duration?, private val clock: RelayClock) {
    // A little under the interval, so a keepalive's own way to the client never makes a silence longer than it.
    private val everyMs: Long? = every?.inWholeMilliseconds?.let { it - minOf(it / AHEAD_SHARE, AHEAD_MAX_MS) }

    // The line ends the stream ends with: two are the blank line that ends an event, and a stream nothing was
    // written to stands between events as well.
    private var lineEnds = EVENT_END

    /** What [wait] answers, with a keepalive written each time it has taken one more interval. */
    suspend fun <T> during(wait: HeadWait<T>): T {
        val interval = everyMs ?: return wait()
        return coroutineScope {
            val pending = async { wait() }
            while (!clock.endedWithin(interval, pending)) keepalive()
            pending.await()
        }
    }

    /** One keepalive now, where the stream stands between two events. */
    suspend fun keepalive() {
        if (lineEnds < EVENT_END) return
        out.writeStringUtf8(KEEPALIVE)
        out.flush()
    }

    /** The next [count] of the head's [bytes], to the client at once. */
    suspend fun write(bytes: ByteArray, count: Int) {
        out.writeFully(bytes, 0, count)
        out.flush()
        val tail = (count - 1 downTo 0).takeWhile { bytes[it] == LINE_FEED || bytes[it] == CARRIAGE_RETURN }
        val ends = tail.count { bytes[it] == LINE_FEED }
        // A chunk that is line ends alone adds to those the stream already ended with.
        lineEnds = if (tail.size == count) lineEnds + ends else ends
    }

    /** [envelope], an error in the Anthropic shape, as the `error` event that ends a stream opened before its head
     *  answered. */
    suspend fun error(envelope: String) {
        out.writeStringUtf8("event: error\ndata: $envelope\n\n")
        out.flush()
    }
}

/** Reads whether a request asks for its answer as an event stream: a JSON body whose `stream` is true. */
internal class StreamedBodies {
    fun asks(body: ByteArray): Boolean = try {
        (Json.parseToJsonElement(String(body)) as? JsonObject)?.get(STREAM_FIELD) == JsonPrimitive(true)
    } catch (_: IllegalArgumentException) {
        // Not JSON: whatever the head answers it with, it is not a stream to keep alive.
        false
    }
}

// An SSE comment and the blank line after it. The word is for a person reading the wire; no reader acts on it.
private const val KEEPALIVE = ": keepalive\n\n"

// The byte of `\n`, which ends an SSE line.
private const val LINE_FEED: Byte = 10

// The byte of `\r`, which a line's end may carry beside its `\n`: it is stepped over, and only a `\n` counts.
private const val CARRIAGE_RETURN: Byte = 13

// Line ends in a row that end an event.
private const val EVENT_END = 2

// why: a keepalive goes out a tenth of the interval early: enough for the write to reach the client inside the
// interval, and next to nothing more traffic than the interval asks for.
private const val AHEAD_SHARE = 10

// why: and never more than half a second early, which is all a write's way to the client takes, so a long interval
// is not shortened by a tenth of itself.
private const val AHEAD_MAX_MS = 500L

private const val STREAM_FIELD = "stream"
