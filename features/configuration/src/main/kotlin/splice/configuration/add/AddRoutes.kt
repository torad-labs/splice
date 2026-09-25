// NEW: V4-220 item 3 (2026-09-25) — the console's add over HTTP, one route per step of `splice add`:
//
//   GET    /api/add/profiles     the catalogue, with what each profile still needs from the operator
//   POST   /api/add              open an add: {profile, name?, base_url?, command?, models?: [{id, context_window?}]}
//   GET    /api/add/{id}         the add as it stands (the credential re-read, the sign-in polled)
//   POST   /api/add/{id}/login   start the sign-in, or answer the one already running
//   POST   /api/add/{id}/verify  run the checks: {live?} adds the one short turn an api-key profile allows
//   POST   /api/add/{id}/save    the checks again, the write, the wrapper, then the restart
//   DELETE /api/add/{id}         close an add that will not be saved
//
// Every answer is the session view (AddViews) or a refusal {error} whose status says which: 400 for a
// request that cannot mean an add, 404 for no such profile or add, 409 for a refusal the daemon's state
// causes (a taken key, a failed check, a file changed under the add, a head signed in another way), 503
// when the add is not wired. A failed check's 409 carries the check rows beside its sentence.
//
// THE SAVE ANSWERS BEFORE IT DRAINS. Its restart is the console button's (AddDaemonRestart), so a
// compaction in flight is waited for; with nothing to wait for, the drain is requested only after the
// answer is written, the order POST /api/daemon/restart keeps, since a daemon tearing itself down would
// otherwise race its reply out of the socket it is closing.
package splice.configuration.add

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.http.JsonBody
import splice.http.JsonReply

private const val ID_PARAM = "id"
private const val UNWIRED = "Adding a backend is not wired on this daemon."
private const val NO_SUCH_ADD = "No add with that id is open."

public class AddRoutes(
    private val console: AddConsoleSource,
    private val restart: AddDaemonRestart,
    private val log: LogSink,
) {
    private val jsonBody = JsonBody()
    private val views = AddViews()
    private val requests = AddRequestReader()

    /** GET /api/add/profiles: `{profiles: [...]}`, in the catalogue's order. */
    public suspend fun profiles(call: ApplicationCall) {
        val adds = console() ?: return refuse(call, UNWIRED, HttpStatusCode.ServiceUnavailable)
        val body = buildJsonObject { putJsonArray("profiles") { adds.profiles().forEach { add(views.profile(it)) } } }
        JsonReply(HttpStatusCode.OK, body.toString()).send(call)
    }

    /** POST /api/add: 200 with the new add's view, or the prepare step's own refusal. */
    public suspend fun open(call: ApplicationCall) {
        val adds = console() ?: return refuse(call, UNWIRED, HttpStatusCode.ServiceUnavailable)
        val args = when (val request = requests.parse(jsonBody.parse(call))) {
            is AddRequest.Invalid -> return refuse(call, request.reason, HttpStatusCode.BadRequest)
            is AddRequest.Args -> request.args
        }
        when (val opened = adds.open(args)) {
            is AddOpened.Opened -> {
                val session = opened.session
                log(
                    "[control] add ${LogSafe.str(session.candidate.key)}: " +
                        "opened from ${LogSafe.str(session.profile)}\n",
                )
                view(call, adds, session)
            }
            AddOpened.UnknownProfile -> {
                val text = "No profile named '${args.profile}'; GET /api/add/profiles lists them."
                refuse(call, text, HttpStatusCode.NotFound)
            }
            is AddOpened.Refused -> refuse(
                call,
                opened.reason,
                if (opened.conflict) HttpStatusCode.Conflict else HttpStatusCode.BadRequest,
            )
        }
    }

    /** GET /api/add/{id}. */
    public suspend fun poll(call: ApplicationCall) {
        val (adds, s) = session(call) ?: return
        view(call, adds, s)
    }

    /** POST /api/add/{id}/login: 200 with the view once a flow runs; 409 for a head that signs in another way. */
    public suspend fun signIn(call: ApplicationCall) {
        val (adds, s) = session(call) ?: return
        val key = s.candidate.key
        when (val outcome = adds.signIn(s)) {
            is AddSignInOutcome.Started -> view(call, adds, s)
            is AddSignInOutcome.ByKey -> refuse(
                call,
                "'$key' reads its key from ${outcome.env}: set it on the Keys page, then verify.",
                HttpStatusCode.Conflict,
            )
            AddSignInOutcome.NoSignIn -> refuse(
                call,
                "'$key' has no sign-in of its own: your Claude login is forwarded at launch.",
                HttpStatusCode.Conflict,
            )
            AddSignInOutcome.AlreadySaved -> refuse(call, "'$key' is already saved.", HttpStatusCode.Conflict)
        }
    }

    /** POST /api/add/{id}/verify, body `{"live": true}` optional: 200 when every check passed. */
    public suspend fun verify(call: ApplicationCall) {
        val (adds, s) = session(call) ?: return
        val live = JsonScalars.str(jsonBody.parse(call), "live") == "true"
        val rows = adds.verify(s, live)
        if (rows.all { it.ok }) view(call, adds, s) else failed(call, rows)
    }

    /** POST /api/add/{id}/save: 200 with the saved view; the drain, when there is one, after it. */
    public suspend fun save(call: ApplicationCall) {
        val (adds, s) = session(call) ?: return
        val key = s.candidate.key
        when (val outcome = adds.save(s, restart)) {
            is AddSaveOutcome.Saved -> {
                log(
                    "[control] add ${LogSafe.str(key)}: saved to ${LogSafe.str(s.candidate.path.toString())}; " +
                        "restart ${LogSafe.str(views.restartStatus(outcome.saved.restart))}\n",
                )
                view(call, adds, s)
                if (outcome.saved.restart == AddRestartTaken.Draining) restart.drain()
            }
            is AddSaveOutcome.ChecksFailed -> failed(call, outcome.checks)
            is AddSaveOutcome.Stale -> refuse(call, outcome.reason, HttpStatusCode.Conflict)
            AddSaveOutcome.AlreadySaved -> refuse(call, "'$key' is already saved.", HttpStatusCode.Conflict)
        }
    }

    /** DELETE /api/add/{id}: 200 `{discarded: id}`; a sign-in already running is not stopped. */
    public suspend fun discard(call: ApplicationCall) {
        val adds = console() ?: return refuse(call, UNWIRED, HttpStatusCode.ServiceUnavailable)
        val id = call.parameters[ID_PARAM].orEmpty()
        if (!adds.discard(id)) return refuse(call, NO_SUCH_ADD, HttpStatusCode.NotFound)
        JsonReply(HttpStatusCode.OK, buildJsonObject { put("discarded", id) }.toString()).send(call)
    }

    /** The wired console and the add the path names, or null once the refusal is written. */
    private suspend fun session(call: ApplicationCall): Pair<AddConsole, AddSession>? {
        val adds = console()
        val s = adds?.session(call.parameters[ID_PARAM].orEmpty())
        when {
            adds == null -> refuse(call, UNWIRED, HttpStatusCode.ServiceUnavailable)
            s == null -> refuse(call, NO_SUCH_ADD, HttpStatusCode.NotFound)
        }
        return if (adds != null && s != null) adds to s else null
    }

    private suspend fun view(call: ApplicationCall, adds: AddConsole, s: AddSession) {
        JsonReply(HttpStatusCode.OK, views.session(adds, s).toString()).send(call)
    }

    private suspend fun failed(call: ApplicationCall, rows: List<AddCheck>) {
        val body = buildJsonObject {
            put("error", views.failure(rows))
            put("checks", views.checks(rows))
        }
        JsonReply(HttpStatusCode.Conflict, body.toString()).send(call)
    }

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) {
        JsonReply(status, buildJsonObject { put("error", text) }.toString()).send(call)
    }
}

/** POST /api/add's body read into the CLI's own arguments, or why it cannot be. */
internal sealed class AddRequest {
    data class Args(val args: AddArgs) : AddRequest()

    data class Invalid(val reason: String) : AddRequest()
}

internal class AddRequestReader {
    /** `models` rows become the CLI's `id:window` specs, so AddModelRows reads both the same way. The
     *  window is always written: a bare id ending `:<digits>` (an ollama tag, `llama3:8`) would read as
     *  an id and a window, where the row said neither. */
    fun parse(body: JsonObject?): AddRequest {
        val profile = JsonScalars.str(body, "profile")
        val rows = (body?.get("models") as? JsonArray).orEmpty().map { it as? JsonObject }
        val specs = rows.map { row -> row?.let(::spec) }
        return when {
            body == null -> AddRequest.Invalid("The body must be a JSON object naming a profile.")
            profile.isNullOrBlank() -> AddRequest.Invalid("Name the profile to add.")
            specs.any { it == null } ->
                AddRequest.Invalid("Each model needs an id, and a context_window in whole tokens.")
            else -> AddRequest.Args(
                AddArgs(
                    profile = profile,
                    name = JsonScalars.str(body, "name"),
                    baseUrl = JsonScalars.str(body, "base_url"),
                    models = specs.filterNotNull(),
                    command = JsonScalars.str(body, "command"),
                    yes = true,
                ),
            )
        }
    }

    private fun spec(row: JsonObject): String? {
        val id = JsonScalars.str(row, "id")?.takeIf { it.isNotBlank() } ?: return null
        val window = row["context_window"] ?: return "$id:$DEFAULT_WINDOW"
        val tokens = (window as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
        return tokens?.let { "$id:$it" }
    }
}
