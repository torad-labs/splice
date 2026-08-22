// PORT-OF: ControlServer.kt (launch, receiveLaunchRequest) @ a77531a — invariants unchanged: the
// /launch route, its body parse and the exec-recipe response, now sharing JsonBody with ConfigRoutes.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import splice.control.LaunchResponse
import splice.control.LaunchService
import splice.control.ManagedHead
import splice.core.topology.TopologyMessages

private data class LaunchRequest(val extraArgs: List<String>, val dangerouslySkipPermissions: Boolean)

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
        val recipe = launchResponse.withAuthWarning(
            managed,
            spec,
            launchService.launch(spec, request.extraArgs, request.dangerouslySkipPermissions),
        )
        audit.launch(key, recipe.argv)
        if (recipe.warning != null) audit.warning(recipe.warning)
        call.respondText(launchResponse.launchRecipeJson(recipe), ContentType.Application.Json)
    }

    private suspend fun receiveLaunchRequest(call: ApplicationCall): LaunchRequest {
        val body = jsonBody.parse(call)
        // Safe by default: the caller must explicitly opt in with {"dangerouslySkipPermissions":"true"}
        // to get the flag; a missing key, malformed body, or any other value stays safe.
        val dangerouslySkipPermissions = body?.get("dangerouslySkipPermissions")?.jsonPrimitive?.content == "true"
        val extraArgs = (body?.get("args") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return LaunchRequest(extraArgs, dangerouslySkipPermissions)
    }
}
