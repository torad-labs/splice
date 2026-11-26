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

/** `splice feed`: [output] takes what the daemon said it kept, [errors] the one line said when nothing was; the value
 *  is read from [input]. */
internal class FeedCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val input: InputStream = System.`in`,
) {

    /** [name] is the feed's name. True when the daemon kept the value from standard input as that feed. */
    fun run(name: String, envReader: EnvReader): Boolean {
        val key = (AdminSupport.readMgmtKey(envReader) as? MgmtKeyRead.Present)?.key
        val value = input.readBytes().decodeToString().trim()
        val url = "http://127.0.0.1:${AdminSupport.controlPort(envReader)}/api/visual/feeds/${encoded(name)}"
        val reading = if (key != null && value.isNotEmpty()) {
            ControlPlaneClient.withBody(url, "PUT", key, value, FEED_TIMEOUT_MS)
        } else {
            null
        }
        val answered = (reading as? PostReading.Answered)?.reply
        val said = answered?.takeIf { it.status in ControlPlaneClient.OK_RANGE }
            ?.let { JsonScalars.str(parsed(it.body), "said") }
        val refused = JsonScalars.str(parsed(answered?.body.orEmpty()), "error")
        when {
            said != null -> output.line(said)
            key == null -> errors.line(
                "splice: no management key to ask the daemon with; `splice status` says whether it is running",
            )
            value.isEmpty() -> errors.line("splice: the value is read from standard input as JSON, and none came")
            else -> errors.line("splice: " + (refused ?: FEED_NOT_ANSWERED))
        }
        return said != null
    }

    private fun parsed(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun encoded(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
