// NEW: the head-start operation lifted out of daemon/control HeadRoutes into its own slice — resolve
// the named head, start it, record the start, answer with its status. The three ports (resolver,
// target, audit) exist so the slice owns the SEQUENCE while the control plane keeps its records:
// nothing here imports a control-plane type (the 2026-09-21 head-start extraction).
package splice.heads.start

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import splice.heads.HeadAudit
import splice.heads.HeadResolver

/** Start a head, then audit the successful operation and return its current status. */
internal class StartHead(
    private val resolver: HeadResolver,
    private val audit: HeadAudit,
) {
    public suspend fun handle(call: ApplicationCall) {
        val name = call.parameters["head"].orEmpty()
        val target = resolver.resolveOrRespond(call, name) ?: return
        target.start()
        audit.record(name, "start")
        call.respondText(target.status().toString(), ContentType.Application.Json)
    }
}
