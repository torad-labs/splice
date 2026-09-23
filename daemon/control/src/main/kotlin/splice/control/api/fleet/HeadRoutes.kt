// PORT-OF: ControlServer.kt (headAction, logsJson) @ a77531a — invariants unchanged: the two
// by-name head routes that share HeadResolver. logsJson's tail clamp moved OUT to ControlServer's
// shared `tail(call, default)` helper — the value now arrives already clamped.
package splice.control.api.fleet

import io.ktor.server.application.ApplicationCall
import splice.control.api.ControlAudit
import splice.control.api.ControlPayloads
import splice.control.api.HeadResolver
import splice.heads.HeadAudit
import splice.heads.HeadOperations

internal class HeadRoutes(
    resolver: HeadResolver,
    payloads: ControlPayloads,
    audit: ControlAudit,
) {
    private val operations: HeadOperations = HeadOperations(
        HeadFeatureAdapter(resolver),
        HeadAudit { name, action -> audit.headAction(name, action) },
        payloads.errorJson("unknown action"),
    )

    suspend fun headAction(call: ApplicationCall) {
        operations.action(call)
    }

    suspend fun logsJson(call: ApplicationCall, tail: Int) {
        operations.logsJson(call, tail)
    }
}
