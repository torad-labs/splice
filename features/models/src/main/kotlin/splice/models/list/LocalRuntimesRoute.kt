// NEW: v0.4.0 §9i / §10 — GET /api/models/local: what each local runtime says about itself, for the Models page.
// Per local provider in splice.toml: whether a runtime answers at its base URL, which one and its version, and per
// listed model the window it serves now, the most it allows, and whether it says the model calls tools. These are
// the runtime's own answers, the ones doctor reports in prose; the page draws them beside the command's models.
//
// ASKED PER REQUEST. A runtime loads and unloads models on its own, so a served window read at boot goes stale.
// The probe reads only lists and versions and never sends a turn (the live probe stays doctor --live's).
package splice.models.list

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

internal const val LOCAL_UNWIRED =
    "the daemon wired no local runtime reading; /api/models/local cannot ask the runtimes"

/** One model as the runtime lists it. Each fact is null when the runtime does not say it. */
public data class LocalRuntimeModel(
    val id: String,
    /** The window the runtime serves this model at now; a model not loaded often has none yet. */
    val servedWindow: Long?,
    /** The most the model allows, read from the runtime's card or its unloaded maximum. */
    val ceiling: Long?,
    /** Whether the runtime says the model calls tools. */
    val tools: Boolean?,
)

/** One local provider's runtime. [runtime] is null when nothing answers at [baseUrl]; [models] is null when a runtime
 *  answers but its model list does not, which is a runtime still starting, never one that lists nothing. */
public data class LocalRuntimeReading(
    val provider: String,
    val baseUrl: String,
    val runtime: String?,
    val version: String?,
    val models: List<LocalRuntimeModel>?,
)

/** Every local provider's reading, asked of the runtimes at call time. */
public fun interface LocalRuntimes {
    public operator fun invoke(): List<LocalRuntimeReading>
}

/** The daemon's reader, read per request because the control plane is handed it after construction. */
public fun interface LocalRuntimesSource {
    public operator fun invoke(): LocalRuntimes?
}

/** [io] runs the probe, whose HTTP client blocks, off the server's own threads. */
public class LocalRuntimesRoute(private val source: LocalRuntimesSource, private val io: CoroutineDispatcher) {

    /** `{providers: [{provider, url, runtime, version, models: [{id, served_window, ceiling, tools}] | null}]}`. */
    public suspend fun local(call: ApplicationCall) {
        val runtimes = source() ?: return refuse(call, LOCAL_UNWIRED, HttpStatusCode.ServiceUnavailable)
        val readings = withContext(io) { Cancellables.runCatchingCancellable { runtimes() } }.getOrElse { failure ->
            return refuse(
                call,
                "reading the local runtimes failed: ${SafeFailureText.render(failure)}",
                HttpStatusCode.InternalServerError,
            )
        }
        val body = buildJsonObject { putJsonArray("providers") { readings.forEach { add(json(it)) } } }
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    private fun json(reading: LocalRuntimeReading): JsonObject = buildJsonObject {
        put("provider", reading.provider)
        put("url", reading.baseUrl)
        put("runtime", reading.runtime)
        put("version", reading.version)
        put("models", reading.models?.let { models -> JsonArray(models.map(::modelJson)) } ?: JsonNull)
    }

    private fun modelJson(model: LocalRuntimeModel): JsonObject = buildJsonObject {
        put("id", model.id)
        put("served_window", model.servedWindow)
        put("ceiling", model.ceiling)
        put("tools", model.tools)
    }

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) =
        call.respondText(buildJsonObject { put("error", text) }.toString(), ContentType.Application.Json, status)
}
