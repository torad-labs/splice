// NEW: #703 — single-request `/props` context-window read for llama-server runtimes, shared between
// LocalRuntimeProbe's initial listing and LocalProbeInputs' cached refresh so repeated ticks send
// `/props` alone without re-running runtime detection or `/v1/models`.
package splice.upstream.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.transport.LocalHttp

public class LocalPropsWindow(baseUrl: String, private val http: LocalHttp) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = baseUrl.trimEnd('/').removeSuffix("/v1")

    /** The `default_generation_settings.n_ctx` served by a llama-server `/props` endpoint, or null. */
    public fun read(): Long? = http("GET", "$root/props", null)
        ?.takeIf { it.status == HttpStatus.OK }
        ?.let { parse(it.body) }
        ?.let { it["default_generation_settings"] as? JsonObject }
        ?.let { JsonScalars.long(it, "n_ctx")?.takeIf { n -> n > 0 } }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}
