// NEW: `splice feed <name>`, the visual mode's feed written from a shell (docs/wiki/modes-and-extensions.md).
// A script the model leaves running, such as the loop that watches a training log, writes a card's feed with no model
// turn: the JSON value it pipes in becomes the feed's value, and a card drawn in a conversation that watches the feed
// shows it. The command asks the running daemon with the management key it reads itself, so the script never holds or
// prints a key. It never holds more than a little over what a feed can hold: a value or a line past that is refused
// where it is read, and a stream with no newline cannot grow the command.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

// why: a feed's value is at most 64 KiB and the daemon answers from memory; a reply this late is a daemon in trouble.
private const val FEED_TIMEOUT_MS = 10_000

// why: the most a feed holds, the visual mode's own cap; the daemon says the exact size of a value just over it.
private const val FEED_MOST_BYTES = 64 * 1024

// why: room around a value of exactly the cap, for a newline, a carriage return or indentation.
private const val FEED_SLACK_BYTES = 1024

// why: the most of one value or one line this command holds; past it the value is refused and not read on.
private const val FEED_READ_MOST = FEED_MOST_BYTES + FEED_SLACK_BYTES

// why: what one read of standard input takes; a line is cut out of it at each newline.
private const val FEED_CHUNK_BYTES = 8 * 1024

private const val FEED_NEWLINE = '\n'.code.toByte()
private const val FEED_RETURN = '\r'.code.toByte()
private const val FEED_JSON_WHITESPACE = " \t\r\n"
private const val FEED_BAD_UTF8 = "the input is not valid UTF-8 JSON, so nothing was written"

private const val FEED_NO_KEY =
    "no management key to ask the daemon with; `splice status` says whether it is running"

private const val FEED_UNCONFIRMED =
    "the daemon did not confirm the write, so it is not known whether the value was kept"

private const val FEED_OLDER = "the daemon is older than this splice and keeps no feeds, so nothing was written"

private const val FEED_TOO_BIG =
    "is past 65 KiB, more than this command reads of one value (a feed holds 64 KiB of compact JSON), so nothing " +
        "was written"

/** `splice feed`: [output] takes what the daemon said it kept, [errors] what was said when something was not; the value
 *  is read from [input]. */
internal class FeedCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val input: InputStream = System.`in`,
) {
    /** What one write came to: the daemon's sentence when it kept the value, or why not, and whether it answered. */
    private data class Sent(val said: String?, val why: String?, val answered: Boolean)

    /** [name] is the feed's name. Without [follow], the whole of standard input is one JSON value, and this is true
     *  when the daemon kept it. With it, each line is one value, written as it arrives until standard input ends: a
     *  line that is not kept is said on [errors] with its number and does not end the stream, the daemon not answering
     *  is said once until it answers again, and this is true when every line was kept. */
    fun run(name: String, follow: Boolean, envReader: EnvReader): Boolean {
        val port = AdminSupport.controlPort(envReader)
        val target = Target("http://127.0.0.1:$port/api/visual/feeds/${encoded(name)}", port, envReader)
        return if (follow) Follow(target).run() else once(target)
    }

    /** Where a value goes: the feed's address on the daemon's control port, and how the key is found. */
    private data class Target(val url: String, val port: Int, val envReader: EnvReader)

    private fun once(target: Target): Boolean {
        val bytes = input.readNBytes(FEED_READ_MOST + 1)
        val value = if (bytes.size <= FEED_READ_MOST) decode(bytes) else null
        val sent = when {
            bytes.size > FEED_READ_MOST -> Sent(null, "the value $FEED_TOO_BIG", answered = true)
            value == null -> Sent(null, FEED_BAD_UTF8, answered = true)
            value.isEmpty() ->
                Sent(null, "the value is read from standard input as JSON, and none came", answered = true)
            else -> send(target, value)
        }
        sent.said?.let(output::line) ?: errors.line("splice: ${sent.why}")
        return sent.said != null
    }

    /** The lines of standard input, each written as it ends; never more than [FEED_READ_MOST] bytes of one held. */
    private inner class Follow(private val target: Target) {
        private val line = ByteArray(FEED_READ_MOST)
        private var size = 0
        private var number = 1
        private var afterReturn = false
        private var skipping = false
        private var silent = false
        private var kept = true

        fun run(): Boolean {
            val chunk = ByteArray(FEED_CHUNK_BYTES)
            var count = input.read(chunk)
            while (count >= 0) {
                cut(chunk, count)
                count = input.read(chunk)
            }
            endLine()
            return kept
        }

        /** The first [count] bytes of [chunk], cut at CR or LF; an LF after CR, even in the next chunk, is the same end. */
        private fun cut(chunk: ByteArray, count: Int) {
            var start = 0
            while (start < count) {
                if (afterReturn && chunk[start] == FEED_NEWLINE) start++
                afterReturn = false
                var end = start
                while (end < count && !endsLine(chunk[end])) end++
                take(chunk, start, end - start)
                if (end < count) {
                    endLine()
                    afterReturn = chunk[end] == FEED_RETURN
                }
                start = end + 1
            }
        }

        private fun endsLine(byte: Byte): Boolean = byte == FEED_NEWLINE || byte == FEED_RETURN

        /** A piece of the line. A line past [FEED_READ_MOST] is refused at once, and what is left of it is dropped. */
        private fun take(chunk: ByteArray, from: Int, length: Int) {
            when {
                skipping -> Unit
                size + length > line.size -> {
                    refuse("line $number $FEED_TOO_BIG")
                    skipping = true
                }
                else -> {
                    chunk.copyInto(line, size, from, from + length)
                    size += length
                }
            }
        }

        private fun endLine() {
            if (!skipping && size > 0) {
                val value = decode(line, size)
                when {
                    value == null -> refuse("line $number: $FEED_BAD_UTF8")
                    value.isNotEmpty() -> write(value)
                }
            }
            size = 0
            skipping = false
            number++
        }

        private fun write(value: String) {
            val sent = send(target, value)
            if (sent.said == null) {
                kept = false
                // a daemon that does not answer is said once, and again only after it has answered in between
                if (sent.answered || !silent) errors.line("splice: line $number: ${sent.why}")
            }
            silent = !sent.answered
        }

        private fun refuse(why: String) {
            kept = false
            errors.line("splice: $why")
        }
    }

    /** Asks the daemon to keep [value], and says only what is known: kept, refused and why, never sent (so not
     *  written), or sent with its answer lost (so not known). */
    private fun send(target: Target, value: String): Sent {
        val key = (AdminSupport.readMgmtKey(target.envReader) as? MgmtKeyRead.Present)?.key
            ?: return Sent(null, FEED_NO_KEY, answered = true)
        return when (val reading = ControlPlaneClient.withBody(target.url, "PUT", key, value, FEED_TIMEOUT_MS)) {
            is PostReading.Answered -> answered(reading.reply)
            PostReading.CutAfterSending -> Sent(null, FEED_UNCONFIRMED, answered = false)
            PostReading.NotConnected -> Sent(
                null,
                "the daemon is not running (nothing answers on port ${target.port}), so nothing was written",
                answered = false,
            )
        }
    }

    private fun answered(reply: ControlReply): Sent {
        val body = parsed(reply.body)
        if (reply.status in ControlPlaneClient.OK_RANGE) {
            val said = JsonScalars.str(body, "said")
            return Sent(said, FEED_UNCONFIRMED.takeIf { said == null }, answered = true)
        }
        val why = JsonScalars.str(body, "error")
            ?: if (reply.status == HttpURLConnection.HTTP_NOT_FOUND) FEED_OLDER else FEED_UNCONFIRMED
        return Sent(null, why, answered = true)
    }

    private fun decode(bytes: ByteArray, size: Int = bytes.size): String? = try {
        bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true).trim { it in FEED_JSON_WHITESPACE }
    } catch (_: CharacterCodingException) {
        null
    }

    private fun parsed(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun encoded(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
