// PORT-OF: ControlServer.kt (launch, receiveLaunchRequest) @ a77531a — invariants unchanged: the
// /launch route, its body parse and the exec-recipe response, now sharing JsonBody with ConfigRoutes.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import splice.control.LaunchResponse
import splice.control.LaunchService
import splice.control.ManagedHead
import splice.core.topology.TopologyMessages
import splice.core.util.JsonScalars

/** [cwd]: V4-183, the shim's working directory, absent from a shim older than shim-4. */
private data class LaunchRequest(val extraArgs: List<String>, val dangerouslySkipPermissions: Boolean, val cwd: String?)

internal class LaunchRoutes(
    private val heads: Map<String, ManagedHead>,
    private val resolver: HeadResolver,
    private val launchService: LaunchService?,
    private val payloads: ControlPayloads,
    private val audit: ControlAudit,
    private val jsonBody: JsonBody,
) {
    private val launchResponse = LaunchResponse()

    suspend fun launch(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val targets = resolver.launchTargets(key)
        if (targets.size > 1) {
            call.respondText(
                payloads.errorJson(TopologyMessages.ambiguousHeadMessage(key, targets.map { it.head.key })),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        val managed = targets.firstOrNull()
        val spec = managed?.launchSpec
        if (spec == null || launchService == null) {
            val known = heads.values.joinToString(", ") { it.head.label }
            call.respondText(
                payloads.errorJson("no launchable head named '$key' (configured: $known)"),
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return
        }
        if (!managed.head.healthSnapshot().running) {
            call.respondText(
                payloads.errorJson("head is not running"),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val request = receiveLaunchRequest(call)
        // V4-162: the windows are read per LAUNCH too — splice.toml's context_window is live and the
        // spec is boot-frozen, so a launch after an edit is planted with the edited window.
        val launched = managed.catalog?.let(spec::withWindows) ?: spec
        val recipe = launchResponse.withAuthWarning(
            managed,
            launched,
            launchService.launch(
                launched,
                request.extraArgs,
                request.dangerouslySkipPermissions,
                // DR-81: key presence is read per LAUNCH — the spec is boot-frozen, and a stale
                // gate left the capture hook armed against a credential `splice key set` landed.
                keyPresentNow = managed.keyPresence.keyPresentNow(),
                cwd = request.cwd,
            ),
        )
        audit.launch(key, recipe.argv)
        if (recipe.warning != null) audit.warning(recipe.warning)
        call.respondText(launchResponse.launchRecipeJson(recipe), ContentType.Application.Json)
    }

    private suspend fun receiveLaunchRequest(call: ApplicationCall): LaunchRequest {
        val body = jsonBody.parse(call)
        // Safe by default: the caller must explicitly opt in with {"dangerouslySkipPermissions":"true"}
        // to get the flag; a missing key, malformed body, or any other value stays safe.
        val dangerouslySkipPermissions = JsonScalars.str(body, "dangerouslySkipPermissions") == "true"
        val extraArgs = (body?.get("args") as? JsonArray)
            ?.mapNotNull { JsonScalars.str(it) } ?: emptyList()
        return LaunchRequest(extraArgs, dangerouslySkipPermissions, JsonScalars.str(body, "cwd"))
    }
}
