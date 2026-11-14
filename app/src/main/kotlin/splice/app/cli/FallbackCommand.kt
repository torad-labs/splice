// NEW (Nov 13, 2026): `splice fallback`, a session's fallback model from a terminal. That evening every Fable login on
// one machine ran out for the week and every turn waited until Sunday. `splice fallback` lists the sessions that have
// one; `splice fallback <session> <model>` gives a session one, which it runs on while every login is held on the model
// it asks for and leaves the first turn a login can serve it again; `splice fallback <session> off` takes it away. A
// session is named by its id, or by its name while it runs.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// why: a change is one small file written by the daemon; a reply this late is a daemon in trouble.
private const val FALLBACK_TIMEOUT_MS = 10_000

// why: the word that takes a session's fallback away, where a model would go.
private const val FALLBACK_OFF = "off"

// why: a change names two words, the session and the model or `off`; none lists what is set.
internal const val FALLBACK_SET_WORDS = 2

/** `splice fallback`: [output] takes what was listed or changed, [errors] the one line said when nothing was. */
internal class FallbackCommand(private val output: TerminalOutput, private val errors: TerminalOutput) {

    /** [args] is empty, or `<session> <model>`, or `<session> off`. True when the daemon listed or made the change. */
    fun run(args: List<String>, envReader: EnvReader): Boolean {
        val key = (AdminSupport.readMgmtKey(envReader) as? MgmtKeyRead.Present)?.key
        if (key == null) {
            errors.line("splice: no management key to ask the daemon with; `splice status` says whether it is running")
            return false
        }
        val base = "http://127.0.0.1:${AdminSupport.controlPort(envReader)}/api/sessions"
        val url = args.firstOrNull()?.let { "$base/${encoded(it)}/fallback" }
        return when {
            url == null -> list(base, key)
            args[1] == FALLBACK_OFF -> changed(ControlPlaneClient.send(url, "DELETE", key))
            else -> changed(put(url, key, args[1]))
        }
    }

    private fun put(url: String, key: String, model: String): ControlReply? {
        val json = buildJsonObject { put("model", model) }.toString()
        val reading = ControlPlaneClient.withBody(url, "PUT", key, json, FALLBACK_TIMEOUT_MS)
        return (reading as? PostReading.Answered)?.reply
    }

    private fun list(base: String, key: String): Boolean {
        val reply = ControlPlaneClient.send("$base/fallbacks", "GET", key)
        val sessions = reply?.takeIf { it.status in ControlPlaneClient.OK_RANGE }?.let { parsed(it.body) }
            ?.get("sessions") as? JsonObject
        if (sessions == null) {
            errors.line("splice: " + refusal(reply, "the daemon gave no session fallbacks to list"))
            return false
        }
        if (sessions.isEmpty()) output.line("No session has a fallback model.")
        sessions.keys.forEach { session -> output.line("$session: ${JsonScalars.str(sessions, session)}") }
        return true
    }

    private fun changed(reply: ControlReply?): Boolean {
        val said = reply?.takeIf { it.status in ControlPlaneClient.OK_RANGE }
            ?.let { JsonScalars.str(parsed(it.body), "said") }
        if (said == null) {
            errors.line("splice: " + refusal(reply, "the daemon is not running, so nothing changed"))
            return false
        }
        output.line(said)
        return true
    }

    private fun parsed(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    /** The daemon's own sentence for a refusal, or [otherwise] when it gave none. */
    private fun refusal(reply: ControlReply?, otherwise: String): String =
        JsonScalars.str(reply?.body?.let(::parsed), "error") ?: otherwise

    private fun encoded(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
