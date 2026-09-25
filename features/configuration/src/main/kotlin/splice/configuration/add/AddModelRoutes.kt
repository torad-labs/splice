// NEW: V4-220 (2026-09-25) — `splice add-model` over HTTP:
//
//   GET  /api/add-model   each OpenRouter head with the catalogue models its roster does not reach yet
//   POST /api/add-model   {head, models: [id, ...]}: the roster edited as splice.toml stands, then the restart
//
// Refusals are {error} with the status that says which: 400 for a body that cannot mean an add, 404 for no
// such OpenRouter head, 409 for one the file causes (an id not on offer any more, a file that does not load
// or changed under the write, a roster splice cannot edit), 503 when the console's add is not wired.
//
// THE ANSWER IS WRITTEN BEFORE THE DRAIN, as the add's save does and for the same reason: a daemon tearing
// itself down would otherwise race its reply out of the socket it is closing.
package splice.configuration.add

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.http.JsonBody
import splice.http.JsonReply

private const val ADD_MODEL_UNWIRED = "Adding models is not wired on this daemon."

public class AddModelRoutes(
    private val console: AddConsoleSource,
    private val restart: AddDaemonRestart,
    private val log: LogSink,
) {
    private val jsonBody = JsonBody()
    private val views = AddViews()
    private val requests = AddModelRequestReader()

    /** GET /api/add-model: 200 with the offers, or 409 when splice.toml does not load. */
    public suspend fun list(call: ApplicationCall) {
        val adds = console() ?: return refuse(call, ADD_MODEL_UNWIRED, HttpStatusCode.ServiceUnavailable)
        when (val listed = adds.models.list()) {
            is AddModelListed.Listed -> JsonReply(HttpStatusCode.OK, views.offers(listed).toString()).send(call)
            is AddModelListed.Unloadable ->
                refuse(call, "${listed.path} does not load (${listed.detail}).", HttpStatusCode.Conflict)
        }
    }

    /** POST /api/add-model: 200 with what was added and the restart taken; the drain, when there is one, after. */
    public suspend fun add(call: ApplicationCall) {
        val adds = console() ?: return refuse(call, ADD_MODEL_UNWIRED, HttpStatusCode.ServiceUnavailable)
        val request = when (val read = requests.parse(jsonBody.parse(call))) {
            is AddModelRequest.Invalid -> return refuse(call, read.reason, HttpStatusCode.BadRequest)
            is AddModelRequest.Models -> read
        }
        when (val outcome = adds.models.add(request.head, request.ids, restart)) {
            is AddModelOutcome.Added -> {
                log(
                    "[control] add-model ${LogSafe.str(outcome.headKey)}: " +
                        "${LogSafe.str(outcome.ids.joinToString(", "))} to ${LogSafe.str(outcome.path.toString())}; " +
                        "restart ${LogSafe.str(views.restartStatus(outcome.restart))}\n",
                )
                JsonReply(HttpStatusCode.OK, views.added(outcome).toString()).send(call)
                if (outcome.restart == AddRestartTaken.Draining) restart.drain()
            }
            is AddModelOutcome.NoSuchHead ->
                refuse(call, "splice.toml has no OpenRouter head named '${outcome.headKey}'.", HttpStatusCode.NotFound)
            is AddModelOutcome.NotOffered -> refuse(
                call,
                "'${outcome.id}' is not on offer for '${outcome.headKey}': it is on its roster already, " +
                    "or not in the catalogue.",
                HttpStatusCode.Conflict,
            )
            is AddModelOutcome.Refused -> refuse(call, outcome.text, HttpStatusCode.Conflict)
        }
    }

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) {
        JsonReply(status, buildJsonObject { put("error", text) }.toString()).send(call)
    }
}

/** POST /api/add-model's body, or why it cannot be an add. */
internal sealed class AddModelRequest {
    data class Models(val head: String, val ids: List<String>) : AddModelRequest()

    data class Invalid(val reason: String) : AddModelRequest()
}

internal class AddModelRequestReader {
    fun parse(body: JsonObject?): AddModelRequest {
        val head = JsonScalars.str(body, "head")
        val rows = body?.get("models") as? JsonArray
        val ids = rows?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }
        return when {
            head.isNullOrBlank() -> AddModelRequest.Invalid("Name the head to add models to.")
            ids == null || ids.any { it == null } -> AddModelRequest.Invalid("List the models to add by id.")
            ids.isEmpty() -> AddModelRequest.Invalid("Pick at least one model to add.")
            else -> AddModelRequest.Models(head, ids.filterNotNull())
        }
    }
}
