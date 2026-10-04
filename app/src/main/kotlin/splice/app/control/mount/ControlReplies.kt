// NEW: LAYOUT-01 — the reply and query helpers more than one control mount answers with, hoisted out of
// ControlServer with the mounts that share them.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

// why: the ceiling on any ?tail a request names, so one call cannot make the daemon serialise a whole
// log or perf file into a single response.
private const val MAX_TAIL = 2_000

internal object ControlReplies {
    suspend fun respond(call: ApplicationCall, body: String) =
        call.respondText(body, ContentType.Application.Json)

    /** The query-param tail clamp both /api/perf and /api/logs/{head} apply — hoisted out of
     *  ControlPayloads.perfJson's and HeadRoutes.logsJson's original call sites. */
    fun tail(call: ApplicationCall, default: Int): Int =
        (call.request.queryParameters["tail"]?.toIntOrNull() ?: default).coerceIn(1, MAX_TAIL)
}
