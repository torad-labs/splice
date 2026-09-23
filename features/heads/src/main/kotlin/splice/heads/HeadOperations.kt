// NEW: the heads capability owns lifecycle sequencing and status/log payloads.
package splice.heads

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.heads.start.StartHead

private const val KEY = "key"

/** Owns the lifecycle and log-retrieval sequences for a named head route. */
public class HeadOperations(
    private val resolver: HeadResolver,
    private val audit: HeadAudit,
    private val unknownActionJson: String = buildJsonObject { put("error", "unknown action") }.toString(),
) {
    private val startHead: StartHead = StartHead(resolver, audit)

    /** Applies the requested lifecycle action to one resolved head. */
    public suspend fun action(call: ApplicationCall) {
        val action = call.parameters["action"].orEmpty()
        if (action == "start") {
            startHead.handle(call)
            return
        }

        val name = call.parameters["head"].orEmpty()
        val target = resolver.resolveOrRespond(call, name) ?: return
        when (action) {
            "stop" -> target.stop()
            "restart" -> target.restart()
            else -> {
                call.respondText(
                    unknownActionJson,
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
        }
        audit.record(name, action)
        call.respondText(target.status().toString(), ContentType.Application.Json)
    }

    /** Returns the byte-exact log payload for one resolved head. */
    public suspend fun logsJson(call: ApplicationCall, tail: Int) {
        val name = call.parameters["head"].orEmpty()
        val target = resolver.resolveOrRespond(call, name) ?: return
        val lines = target.tailLogs(tail).split("\n").filter { it.isNotEmpty() }
        call.respondText(
            buildJsonObject {
                put(KEY, name)
                put("path", target.logPath())
                putJsonArray("lines") { lines.forEach { add(it) } }
            }.toString(),
            ContentType.Application.Json,
        )
    }
}

/** Lists every registered head in registry order, preserving the control wire shape. */
public class ListHeads(
    private val listing: HeadStatusListing,
) {
    public suspend fun handle(call: ApplicationCall) {
        val body = buildJsonObject {
            putJsonArray("heads") { listing.snapshots().forEach { add(it) } }
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }
}
