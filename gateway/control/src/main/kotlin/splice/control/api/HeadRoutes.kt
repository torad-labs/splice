// PORT-OF: ControlServer.kt (headAction, logsJson) @ a77531a — invariants unchanged: the two
// by-name head routes that share HeadResolver. logsJson's tail clamp moved OUT to ControlServer's
// shared `tail(call, default)` helper — the value now arrives already clamped.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val KEY = "key"

internal class HeadRoutes(
    private val resolver: HeadResolver,
    private val payloads: ControlPayloads,
    private val audit: ControlAudit,
) {
    suspend fun headAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        when (action) {
            "start" -> managed.head.start()
            "stop" -> managed.head.stop()
            "restart" -> managed.head.restart()
            else -> {
                call.respondText(
                    payloads.errorJson("unknown action"),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
        }
        audit.headAction(key, action)
        call.respondText(resolver.headStatus(managed).toString(), ContentType.Application.Json)
    }

    suspend fun logsJson(call: ApplicationCall, tail: Int) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        // PORT-OF server/src/control/api.mjs logs payload @ pre-public-port-baseline: {key, path, lines:[...]}
        // (webui LogsPayload) — lines is an ARRAY (tail split), not one blob.
        val lines = managed.logs.tail(tail).split("\n").filter { it.isNotEmpty() }
        call.respondText(
            buildJsonObject {
                put(KEY, key)
                put("path", managed.logs.path())
                putJsonArray("lines") { lines.forEach { add(it) } }
            }.toString(),
            ContentType.Application.Json,
        )
    }
}
