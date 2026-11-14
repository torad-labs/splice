// NEW (Nov 13, 2026): `splice limit-reset`, a Claude command's limit resets from a terminal. That evening every Fable
// login on one machine ran out at about 6:24 PM CT, turns waited until Sunday, and the only way to call a reset was in
// Claude Code, login by login. `splice limit-reset <command>` lists how many of each kind every login has left;
// `splice limit-reset <command> <login> <kind>` spends one of that kind on that login, and the command's turns held
// for the limits it cleared go on. splice never spends one by itself: only this verb and the console's button do.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// why: a spend asks Anthropic up to three times, the last given 25 seconds, and a list reads each login in turn.
private const val LIMIT_RESET_TIMEOUT_MS = 90_000

// why: a spend names three words: the command, the login and the kind; fewer list what is left.
internal const val LIMIT_RESET_SPEND_WORDS = 3

// why: the reply field both the list and a spend name a login by.
private const val RESET_LABEL = "label"

/** `splice limit-reset`: [output] takes what was read or spent, [errors] the one line said when nothing was. */
internal class LimitResetCommand(private val output: TerminalOutput, private val errors: TerminalOutput) {

    /** [args] is `<command>`, `<command> <login>` or `<command> <login> <kind>`. True when the daemon answered with a
     *  list, or confirmed a spend. */
    fun run(args: List<String>, envReader: EnvReader): Boolean {
        val key = (AdminSupport.readMgmtKey(envReader) as? MgmtKeyRead.Present)?.key
        if (key == null) {
            errors.line("splice: no management key to ask the daemon with; `splice status` says whether it is running")
            return false
        }
        val base = "http://127.0.0.1:${AdminSupport.controlPort(envReader)}/api/auth/${encoded(args[0])}"
        val spends = args.size == LIMIT_RESET_SPEND_WORDS
        return if (spends) spend(base, key, args[1], args[2]) else list(base, key, args.getOrNull(1))
    }

    private fun list(base: String, key: String, login: String?): Boolean {
        val url = "$base/resets" + login?.let { "?label=${encoded(it)}" }.orEmpty()
        val reply = ControlPlaneClient.send(url, "GET", key, LIMIT_RESET_TIMEOUT_MS)
        val body = reply?.takeIf { it.status in ControlPlaneClient.OK_RANGE }?.let { parsed(it.body) }
        if (body == null) {
            errors.line("splice: " + refusal(reply?.body, "the daemon gave no limit resets to list"))
            return false
        }
        objects(body["logins"]).forEach(::printLogin)
        return true
    }

    private fun printLogin(login: JsonObject) {
        val label = JsonScalars.str(login, RESET_LABEL).orEmpty()
        val none = JsonScalars.str(login, "refusal") ?: JsonScalars.str(login, "not_offered")
        val kinds = objects(login["kinds"])
        when {
            none != null -> output.line("$label: $none")
            kinds.isEmpty() -> output.line("$label: no limit resets left")
            else -> {
                output.line(label)
                kinds.forEach { kind ->
                    val left = "${JsonScalars.int(kind, "left") ?: 0} left of ${JsonScalars.int(kind, "total") ?: 0}"
                    val now = if (flag(kind, "usable_now")) "can be spent now" else JsonScalars.str(kind, "not_now")
                    val name = "${JsonScalars.str(kind, "name")} (${JsonScalars.str(kind, "kind")})"
                    output.line("  $name: $left. ${now.orEmpty()}")
                }
            }
        }
    }

    private fun spend(base: String, key: String, login: String, kind: String): Boolean {
        val json = buildJsonObject {
            put(RESET_LABEL, login)
            put("kind", kind)
        }.toString()
        return when (val reading = ControlPlaneClient.post("$base/reset", key, json, LIMIT_RESET_TIMEOUT_MS)) {
            PostReading.NotConnected -> {
                errors.line("splice: the daemon is not running, so nothing was spent")
                false
            }
            PostReading.CutAfterSending -> {
                errors.line(
                    "splice: the answer was cut after the request went up, so the reset may have been spent; " +
                        "`splice limit-reset <command> $login` says how many are left",
                )
                false
            }
            is PostReading.Answered -> spent(login, reading)
        }
    }

    private fun spent(login: String, reading: PostReading.Answered): Boolean {
        val body = parsed(reading.reply.body)
        val said = JsonScalars.str(body, "said")
        if (body == null || said == null) {
            errors.line("splice: " + refusal(reading.reply.body, "the daemon gave no answer about the reset"))
            return false
        }
        val ok = flag(body, "ok")
        val readAgain = if (flag(body, "read_again")) " Its usage was read again, so turns held for it go on." else ""
        (if (ok) output else errors).line("$login: $said$readAgain")
        return ok
    }

    private fun parsed(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    /** The daemon's own sentence for a refusal, or [otherwise] when it gave none. */
    private fun refusal(body: String?, otherwise: String): String =
        JsonScalars.str(body?.let(::parsed), "error") ?: otherwise

    private fun objects(of: JsonElement?): List<JsonObject> =
        (of as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun flag(of: JsonObject, name: String): Boolean = (of[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun encoded(part: String): String = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
}
