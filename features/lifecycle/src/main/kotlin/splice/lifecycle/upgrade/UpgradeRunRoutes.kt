// NEW: V4-220 item 4 (2026-09-25) — `splice upgrade` and `splice upgrade --rollback` from the console:
//
//   POST /api/upgrade       {to?: "v0.4.1", rollback?: true}: 202 once the run has started out of process
//   GET  /api/upgrade/run   the newest run: its state, its exit code once it ended, and its output so far
//
// The run restarts the daemon, so the GET the console polls is answered by whichever daemon is up: the
// run's state is on disk (UpgradeRuns). Refusals are {error}, one sentence: 400 for a body that cannot
// mean an upgrade, 409 while a run is going, 500 when the run could not be started, 503 when the
// console's upgrade is not wired.
package splice.lifecycle.upgrade

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink

private const val UPGRADE_RUNS_UNWIRED = "Upgrading from the console is not wired on this daemon."

/** Where the routes read the daemon's [UpgradeRuns] AT CALL TIME: the wiring assigns it after the routes
 *  are constructed, and a route that captured it would answer unwired forever. */
public fun interface UpgradeRunsSource {
    public operator fun invoke(): UpgradeRuns?
}

public class UpgradeRunRoutes(private val runs: UpgradeRunsSource, private val log: LogSink) {
    /** POST /api/upgrade: an empty body is the latest release. */
    public suspend fun start(call: ApplicationCall) {
        val upgrades = runs() ?: return refuse(call, UPGRADE_RUNS_UNWIRED, HttpStatusCode.ServiceUnavailable)
        val request = request(body(call)) ?: return refuse(
            call,
            "The version must be a release number like v0.4.1, and rollback true or false.",
            HttpStatusCode.BadRequest,
        )
        when (val started = upgrades.start(request)) {
            is UpgradeRunStart.Started -> {
                val run = started.run
                log("[control] upgrade: started ${LogSafe.str(run.id)} (${LogSafe.str(run.args.joinToString(" "))})\n")
                reply(call, buildJsonObject { put("run", view(run)) }, HttpStatusCode.Accepted)
            }
            is UpgradeRunStart.Busy -> refuse(call, "An upgrade is already running.", HttpStatusCode.Conflict)
            is UpgradeRunStart.Invalid -> refuse(call, started.reason, HttpStatusCode.BadRequest)
            is UpgradeRunStart.NotStarted -> refuse(
                call,
                "The upgrade could not be started: ${started.reason}.",
                HttpStatusCode.InternalServerError,
            )
        }
    }

    /** GET /api/upgrade/run: `{run: null}` before the console ever started one. */
    public suspend fun status(call: ApplicationCall) {
        val upgrades = runs() ?: return refuse(call, UPGRADE_RUNS_UNWIRED, HttpStatusCode.ServiceUnavailable)
        reply(call, buildJsonObject { put("run", upgrades.latest()?.let(::view) ?: JsonNull) }, HttpStatusCode.OK)
    }

    /** An empty body is `{}`; a body that is not a JSON object is null. */
    private suspend fun body(call: ApplicationCall): JsonObject? {
        val text = call.receiveText()
        if (text.isBlank()) return JsonObject(emptyMap())
        // A body that is not JSON is the caller's, and the 400 the caller gets says so.
        return Cancellables.runCatchingCancellable { Json.parseToJsonElement(text) }
            .fold(onSuccess = { it as? JsonObject }, onFailure = { null })
    }

    /** Null for a body that is not an object or whose fields are the wrong type: `to` a string,
     *  `rollback` a boolean. */
    private fun request(body: JsonObject?): UpgradeRequest? {
        if (body == null) return null
        val to = body["to"]
        val rollback = body["rollback"]
        val version = (to as? JsonPrimitive)?.takeIf { it.isString }?.content
        val back = (rollback as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
        return when {
            to != null && to !is JsonNull && version == null -> null
            rollback != null && back == null -> null
            else -> UpgradeRequest(version, back == true)
        }
    }

    private fun view(run: UpgradeRunView): JsonObject = buildJsonObject {
        put("id", run.id)
        putJsonArray("args") { run.args.forEach { add(JsonPrimitive(it)) } }
        put("state", run.state.wire)
        put("started_at_epoch_millis", run.startedAtMillis)
        put("exit_code", run.exitCode)
        putJsonArray("output") { run.output.forEach { add(JsonPrimitive(it)) } }
    }

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) {
        reply(call, buildJsonObject { put("error", text) }, status)
    }

    private suspend fun reply(call: ApplicationCall, body: JsonObject, status: HttpStatusCode) {
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
}
