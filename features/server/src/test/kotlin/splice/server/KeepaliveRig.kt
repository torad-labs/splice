// What the keepalive tests stand on beside the server itself (docs/specs/server-mode.md, F4): the relay's clock in the
// test's hands, and a client that takes a streamed answer one read at a time. Neither sits through a silence. A wait
// of the relay's ends when the test says its time has passed, and what the client is waiting to hear either comes or
// fails the test at a deadline, by name.
package splice.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.fail
import splice.upstream.Waiter
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The relay's clock in a test's hands. A wait the relay asks for ends when the test says its time has passed
 *  ([pass]), or the moment it is asked for where a silence lasts any length of time ([flies]). */
internal class SteppedClock : Waiter {
    /** Each wait the relay asked for, in milliseconds, in the order it asked. */
    val asked = CopyOnWriteArrayList<Long>()

    /** Set for a silence of any length: every wait ends at once, as many as the relay asks for. */
    @Volatile
    var flies = false

    private val passed = Channel<Unit>()

    override suspend fun wait(ms: Long) {
        asked += ms
        if (!flies) passed.receive()
    }

    /** The time the relay is waiting out passes. Back once the relay has seen it pass; a relay that waits for no
     *  time to pass fails the test here. */
    fun pass() {
        val seen = runBlocking { withTimeoutOrNull(HEARD_WITHIN_MS) { passed.send(Unit) } }
        assertNotNull(seen, "the relay was not waiting for any time to pass")
    }
}

/** One turn as its client hears it: the status and type its headers carried, and its body as it comes. The body is
 *  read on a thread of [readers], so a test takes what has come and waits, to a deadline, only for what it names. */
internal class Hearing(response: HttpResponse<InputStream>, readers: Executor) {
    val status: Int = response.statusCode()
    val type: String = response.headers().firstValue("content-type").orElse("").substringBefore(';')

    // Each read of the body, and an empty one once it has ended: no read of a body is empty.
    private val reads = LinkedBlockingQueue<String>()
    private val unread = StringBuilder()
    private var ended = false

    init {
        readers.execute {
            try {
                response.body().use { body ->
                    val buffer = ByteArray(READ_BYTES)
                    generateSequence { body.read(buffer).takeIf { it >= 0 } }.forEach { reads += String(buffer, 0, it) }
                }
            } catch (cut: IOException) {
                reads += "\n[the answer was cut short: $cut]"
            } finally {
                reads += ""
            }
        }
    }

    /** The next block of the stream, an event or a comment with the blank line that ends it, or null once the
     *  stream has ended. [what] names it, for a test that never hears it. */
    fun block(what: String): String? {
        while (BLOCK_END !in unread) {
            if (!more(what)) return null.also { check(unread.isEmpty()) { "the stream ended inside a block: $unread" } }
        }
        val end = unread.indexOf(BLOCK_END) + BLOCK_END.length
        return unread.substring(0, end).also { unread.delete(0, end) }
    }

    /** What has come of a block its head has not finished, once [length] characters of it have. */
    fun part(length: Int, what: String): String {
        while (unread.length < length) check(more(what)) { "the answer ended before $what: $unread" }
        return unread.toString()
    }

    /** Everything from here to the end of the answer. */
    fun rest(what: String): String {
        var coming = more(what)
        while (coming) coming = more(what)
        return unread.toString().also { unread.clear() }
    }

    /** Takes the next read of the body, waiting for it to the deadline. False once the body has ended. */
    private fun more(what: String): Boolean {
        if (ended) return false
        val read = reads.poll(HEARD_WITHIN_MS, TimeUnit.MILLISECONDS)
            ?: fail("the client never heard $what; it had heard: $unread")
        if (read.isEmpty()) ended = true else unread.append(read)
        return !ended
    }
}

// The deadline for anything a keepalive test waits to happen. Nothing waits this long unless the test is failing.
internal const val HEARD_WITHIN_MS = 20_000L

// The blank line that ends an SSE event or comment.
private const val BLOCK_END = "\n\n"

private const val READ_BYTES = 8 * 1024
