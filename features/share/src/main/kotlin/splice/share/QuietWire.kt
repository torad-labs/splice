// NEW: server mode (docs/specs/server-mode.md, F4: "long prefills keep the client's connection alive"). A model server
// prefilling a long prompt sends nothing for minutes, and a proxy between the client and this machine closes a
// connection that carries nothing for that long. This is the client's side of a relayed event stream that is kept
// alive through such a silence: whatever the relay waits on (the head's answer, or the next bytes of it), an SSE
// comment is written each time the wait outlasts the interval. A comment is a line every SSE reader skips, so the
// client hears only the head's events. With no interval nothing is written but the head's own bytes, which is how LAN
// share relays.
//
// The head's bytes go on an event at a time: what it wrote past its last blank line is held until the line that ends
// that event comes. An SSE reader acts on nothing before that line, so the client loses nothing by the wait, and the
// stream always stands between two events. That is what lets a keepalive go out in a silence the head fell into
// halfway through an event, and what lets the relay end a stream it must end (its server broke off, fell silent past
// `stream_idle_seconds`, or closed its connection before an event's blank line came) with an error event its client
// reads, never one glued onto half of the head's. A connection that closed is no proof that its stream ended whole.
//
// Whether a wait has outlasted its time is the relay's clock's to say (RelayClock). The relay asks it twice: for the
// second a silent head is given before its client's stream is opened (ShareRelay.forward), and for each interval
// between two keepalives. Production waits on the process clock; a test says when the time has passed, so it reads
// what a client hears after a silence without sitting through one.
package splice.share

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
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

    /** A timer, started now in [scope], that ends once [ms] have passed. */
    internal fun timer(scope: CoroutineScope, ms: Long): Job = scope.launch { waiter.wait(ms) }

    /** What [wait] answers, or null when [timer] ends first, which cancels [wait]. */
    internal suspend fun <T : Any> before(timer: Job, wait: HeadWait<T>): T? = coroutineScope {
        val pending = async { wait() }
        select {
            pending.onAwait { it }
            timer.onJoin { null }
        }.also { if (it == null) pending.cancel() }
    }
}

/** What a relay waits on while its client hears nothing: the head's answer, or the next bytes of it. */
internal fun interface HeadWait<T> {
    suspend operator fun invoke(): T
}

internal class QuietWire(private val out: ByteWriteChannel, every: Duration?, private val clock: RelayClock) {
    // A little under the interval, so a keepalive's own way to the client never makes a silence longer than it.
    private val everyMs: Long? = every?.inWholeMilliseconds?.let { it - minOf(it / AHEAD_SHARE, AHEAD_MAX_MS) }

    // What the head wrote past its last whole event: the start of the next one, held until its blank line comes.
    private var held = ByteArray(0)

    /** What [wait] answers, with a keepalive written each time it has taken one more interval. */
    suspend fun <T> during(wait: HeadWait<T>): T {
        val interval = everyMs ?: return wait()
        return coroutineScope {
            val pending = async { wait() }
            while (!clock.endedWithin(interval, pending)) keepalive()
            pending.await()
        }
    }

    /** One keepalive now. */
    suspend fun keepalive() {
        out.writeStringUtf8(KEEPALIVE)
        out.flush()
    }

    /** The next [count] of the head's [bytes]: every event they end goes to the client at once, and what follows the
     *  last of them is held for the bytes that end it. */
    suspend fun write(bytes: ByteArray, count: Int) {
        val heldBefore = held.size
        val pending = held + bytes.copyOfRange(0, count)
        val whole = wholeEvents(pending, heldBefore)
        held = pending.copyOfRange(whole, pending.size)
        if (whole == 0) return
        out.writeFully(pending, 0, whole)
        out.flush()
    }

    /** How many of [bytes] the events they end take: all of them up to and with the last blank line (`\n\n`, or
     *  `\n\r\n` where lines end in `\r\n`); 0 while they end none. The first [heldBefore] were held because they
     *  ended none, so a blank line can only end past them. */
    private fun wholeEvents(bytes: ByteArray, heldBefore: Int): Int {
        for (at in bytes.size - 1 downTo maxOf(heldBefore, 1)) {
            if (bytes[at] != LINE_FEED) continue
            val before = bytes[at - 1]
            if (before == LINE_FEED) return at + 1
            if (before == CARRIAGE_RETURN && bytes.getOrNull(at - 2) == LINE_FEED) return at + 1
        }
        return 0
    }

    /** The head's stream reached its end: true when it ended whole, at the blank line that ends its last event; false
     *  when it ended with an event that blank line never came for, a stream cut short of which nothing more is passed
     *  on: an SSE reader acts on an event only at its blank line. The relay ends that one with its error event
     *  ([error]). */
    fun end(): Boolean {
        val whole = held.all { it == LINE_FEED || it == CARRIAGE_RETURN }
        held = ByteArray(0)
        return whole
    }

    /** [event], an error as the stream's client reads one (RefusalVoice.event), which ends a stream that cannot go
     *  on: one opened before its head answered, one its server broke off, or one that fell silent past its deadline.
     *  Whatever the head left half written is dropped: no reader could have acted on it. */
    suspend fun error(event: String) {
        held = ByteArray(0)
        out.writeStringUtf8("$event\n\n")
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
internal const val LINE_FEED: Byte = 10

// The byte of `\r`, which a line's end may carry beside its `\n`: it is stepped over, and only a `\n` counts.
internal const val CARRIAGE_RETURN: Byte = 13

// why: a keepalive goes out a tenth of the interval early: enough for the write to reach the client inside the
// interval, and next to nothing more traffic than the interval asks for.
private const val AHEAD_SHARE = 10

// why: and never more than half a second early, which is all a write's way to the client takes, so a long interval
// is not shortened by a tenth of itself.
private const val AHEAD_MAX_MS = 500L

private const val STREAM_FIELD = "stream"
