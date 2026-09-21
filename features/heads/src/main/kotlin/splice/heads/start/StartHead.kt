// NEW: the head-start operation lifted out of daemon/control HeadRoutes into its own slice — resolve
// the named head, start it, record the start, answer with its status. The three ports (resolver,
// target, audit) exist so the slice owns the SEQUENCE while the control plane keeps its records:
// nothing here imports a control-plane type (the 2026-09-21 head-start extraction).
package splice.heads.start

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject

/** Resolves the existing head or completes the call with the lookup refusal. */
public fun interface StartHeadResolver {
    public suspend fun resolveOrRespond(call: ApplicationCall, name: String): StartHeadTarget?
}

/** The resolved head and its live status projection refer to the same runtime instance. */
public interface StartHeadTarget {
    public suspend fun start(): Unit

    public fun status(): JsonObject
}

/** Records a successful start request using the name supplied by the caller. */
public fun interface StartHeadAudit {
    public fun record(name: String): Unit
}

/** Start a head, then audit the successful operation and return its current status. */
public class StartHead(
    private val resolver: StartHeadResolver,
    private val audit: StartHeadAudit,
) {
    public suspend fun handle(call: ApplicationCall): Unit {
        val name = call.parameters["head"].orEmpty()
        val target = resolver.resolveOrRespond(call, name) ?: return
        target.start()
        audit.record(name)
        call.respondText(target.status().toString(), ContentType.Application.Json)
    }
}
