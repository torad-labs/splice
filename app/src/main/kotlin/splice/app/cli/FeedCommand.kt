// NEW: `splice feed <name>`, the visual mode's feed written from a shell (docs/wiki/modes-and-extensions.md).
// A script the model leaves running, such as the loop that watches a training log, writes a card's feed with no model
// turn: the JSON value it pipes in becomes the feed's value, and a card drawn in a conversation that watches the feed
// shows it. The command asks the running daemon with the management key it reads itself, so the script never holds or
// prints a key.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// why: a feed's value is at most 64 KiB and the daemon answers from memory; a reply this late is a daemon in trouble.
private const val FEED_TIMEOUT_MS = 10_000

// why: what is said when no daemon answered with a sentence: none running, or one from before feeds (a 404).
private const val FEED_NOT_ANSWERED =
    "the daemon is not running, or is older than this splice and keeps no feeds, so nothing changed"

// why: the key is the daemon's own file, written when it first runs.
private const val FEED_NO_KEY =
    "no management key to ask the daemon with; `splice status` says whether it is running"

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
        val url = "http://127.0.0.1:${AdminSupport.controlPort(envReader)}/api/visual/feeds/${encoded(name)}"
        return if (follow) stream(url, envReader) else once(url, envReader)
    }

    private fun once(url: String, envReader: EnvReader): Boolean {
        val value = input.readBytes().decodeToString().trim()
        if (value.isEmpty()) {
            errors.line("splice: the value is read from standard input as JSON, and none came")
            return false
        }
        val sent = send(url, value, envReader)
        sent.said?.let(output::line) ?: errors.line("splice: ${sent.why}")
        return sent.said != null
    }

    private fun stream(url: String, envReader: EnvReader): Boolean {
        var kept = true
        var silent = false
        var number = 0
        for (line in input.bufferedReader().lineSequence()) {
            number++
            if (line.isBlank()) continue
            val sent = send(url, line.trim(), envReader)
            // a daemon that does not answer is said once, and again only after it has answered in between
            if (sent.answered) silent = false
            if (sent.said == null) {
                kept = false
                if (sent.answered || !silent) errors.line("splice: line $number: ${sent.why}")
                silent = !sent.answered
            }
        }
        return kept
    }

    private fun send(url: String, value: String, envReader: EnvReader): Sent {
        val key = (AdminSupport.readMgmtKey(envReader) as? MgmtKeyRead.Present)?.key
            ?: return Sent(null, FEED_NO_KEY, answered = true)
        val answered = (ControlPlaneClient.withBody(url, "PUT", key, value, FEED_TIMEOUT_MS) as? PostReading.Answered)
            ?.reply
        val said = answered?.takeIf { it.status in ControlPlaneClient.OK_RANGE }
            ?.let { JsonScalars.str(parsed(it.body), "said") }
        val refused = JsonScalars.str(parsed(answered?.body.orEmpty()), "error")
        return Sent(said, refused ?: FEED_NOT_ANSWERED, answered != null)
    }

    private fun parsed(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun encoded(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
