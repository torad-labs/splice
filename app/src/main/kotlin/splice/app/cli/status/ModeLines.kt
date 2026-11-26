// NEW: the modes `splice status` lists (modes/README.md), one plain line each, read from the running daemon's
// GET /api/modes as the accounts line reads the daemon. Plain text with no escape codes: the lines are read by people
// and by scripts.
package splice.app.cli.status

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// why: a status read waits as long as the accounts line does for the daemon, then says it was not readable.
private const val MODES_READ_TIMEOUT_S = 5L

internal class ModeLines(private val client: HttpClient = HttpClient.newHttpClient()) {
    /** The lines for the daemon on [port]: one per mode, or one saying why the modes were not readable. */
    fun lines(port: Int, env: EnvReader): List<String> = when (val key = MgmtKeyFile().read(env)) {
        is MgmtKeyRead.Present -> asked(port, key.key)
        MgmtKeyRead.Absent -> unreadable("this shell's state dir holds no management key")
        is MgmtKeyRead.Unreadable -> unreadable("the management key could not be read (${key.reason})")
    }

    private fun asked(port: Int, key: String): List<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/modes"))
            .timeout(Duration.ofSeconds(MODES_READ_TIMEOUT_S))
            .header("Authorization", "Bearer $key")
            .GET()
            .build()
        val reply = Cancellables.runCatchingCancellable { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { return unreadable("the daemon's /api/modes did not answer (${SafeFailureText.render(it)})") }
        val status = reply.statusCode()
        if (status != HttpStatus.OK) return unreadable("the daemon's /api/modes answered HTTP $status")
        return parsed(reply.body())?.map(::line) ?: unreadable("the daemon's /api/modes answered something else")
    }

    /** `  mode  <name>  <on heads, for callers>  <kind>  <tools>[  not for <callers>][  not served: ...]`. A caller is
     *  named when the mode is on for it while it is off for every head, as `on_for` makes it. */
    private fun line(mode: JsonObject): String {
        val on = onText(mode)
        val tools = list(mode["tools"]).mapNotNull { JsonScalars.str(it as? JsonObject, "offered_as") }
            .joinToString(", ")
        val callers = list(mode["callers_off"]).mapNotNull(JsonScalars::str)
        val notFor = if (callers.isEmpty()) "" else "  not for ${callers.joinToString(", ")}"
        val name = JsonScalars.str(mode, "name").orEmpty()
        // a mode whose server name is one of the person's own MCP servers yields it, and is said to here
        val taken = (mode["server_taken"] as? JsonPrimitive)?.booleanOrNull == true
        val server = JsonScalars.str(mode, "server").orEmpty()
        val yours = if (taken) "  not served: your MCP server '$server' has the name" else ""
        return "  mode  $name  $on  ${JsonScalars.str(mode, "kind").orEmpty()}  $tools$notFor$yours"
    }

    private fun list(value: JsonElement?): List<JsonElement> = (value as? JsonArray).orEmpty()

    /** `off`, `on <heads>`, `on for <callers>` or `on <heads>, for <callers>`. */
    private fun onText(mode: JsonObject): String {
        val heads = onIn(mode["heads"]).joinToString(", ")
        val everyHead = ((mode["on"] as? JsonObject)?.get("value") as? JsonPrimitive)?.booleanOrNull == true
        val alone = if (everyHead) "" else onIn(mode["callers"]).joinToString(", ")
        return when {
            heads.isEmpty() && alone.isEmpty() -> "off"
            heads.isEmpty() -> "on for $alone"
            alone.isEmpty() -> "on $heads"
            else -> "on $heads, for $alone"
        }
    }

    /** The keys of a `{<key>: {on, from}}` table whose `on` is true, in its order. */
    private fun onIn(value: JsonElement?): List<String> = (value as? JsonObject).orEmpty()
        .filter { (_, state) -> ((state as? JsonObject)?.get("on") as? JsonPrimitive)?.booleanOrNull == true }
        .keys.toList()

    private fun parsed(body: String): List<JsonObject>? = try {
        ((Json.parseToJsonElement(body) as? JsonObject)?.get("modes") as? JsonArray)?.filterIsInstance<JsonObject>()
    } catch (_: SerializationException) {
        null
    }

    private fun unreadable(why: String) = listOf("  modes  not readable: $why")
}
