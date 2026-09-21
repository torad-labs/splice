// PORT-OF: ControlServer.kt (headAction, logsJson) @ a77531a — invariants unchanged: the two
// by-name head routes that share HeadResolver. logsJson's tail clamp moved OUT to ControlServer's
// shared `tail(call, default)` helper — the value now arrives already clamped.
package splice.control.api.fleet

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.control.api.ControlAudit
import splice.control.api.ControlPayloads
import splice.control.api.HeadResolver
import splice.heads.start.StartHead
import splice.heads.start.StartHeadAudit

private const val KEY = "key"

internal class HeadRoutes(
    private val resolver: HeadResolver,
    private val payloads: ControlPayloads,
    private val audit: ControlAudit,
) {
    private val startHead = StartHead(
        HeadStartAdapter(resolver),
        StartHeadAudit { name -> audit.headAction(name, "start") },
    )

    suspend fun headAction(call: ApplicationCall) {
        val action = call.parameters["action"].orEmpty()
        if (action == "start") {
            startHead.handle(call)
            return
        }
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        when (action) {
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
