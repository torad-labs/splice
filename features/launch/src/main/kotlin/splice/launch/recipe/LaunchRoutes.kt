// PORT-OF: ControlServer.kt (launch, receiveLaunchRequest) @ a77531a — invariants unchanged: the
// /launch route, its body parse and the exec-recipe response, now sharing JsonBody with ConfigRoutes.
// LAYOUT-01: reads heads through the LaunchHead projection control adapts from ManagedHead.
package splice.launch.recipe

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import splice.client.wrap.WrappedLaunch
import splice.core.topology.TopologyMessages
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.launch.LaunchAudit
import splice.launch.LaunchHead
import splice.launch.LaunchHeads
import splice.launch.LaunchReplies
import splice.launch.wrap.CLAUDE_HEAD_KEY

/** [cwd]: V4-183, the shim's working directory, absent from a shim older than shim-4. */
private data class LaunchRequest(val extraArgs: List<String>, val dangerouslySkipPermissions: Boolean, val cwd: String?)

/** The head one launch runs, and [wrapped] when it came through the wrapped `claude` (V4-129). */
private data class Resolved(val head: LaunchHead, val wrapped: WrappedLaunch?)

public class LaunchRoutes(
    private val heads: LaunchHeads,
    private val launchService: LaunchService?,
    private val audit: LaunchAudit,
    private val jsonBody: JsonBody,
) {
    private val launchResponse = LaunchResponse()

    public suspend fun launch(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val targets = heads.targets(key)
        if (targets.size > 1) {
            call.respondText(
                LaunchReplies.errorJson(TopologyMessages.ambiguousHeadMessage(key, targets.map { it.head.key })),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        val resolved = resolve(key, targets)
        val target = resolved?.head
        val spec = target?.spec
        if (spec == null || launchService == null) {
            val known = heads.all().joinToString(", ") { it.head.label }
            call.respondText(
                LaunchReplies.errorJson("no launchable head named '$key' (configured: $known)"),
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return
        }
        if (!target.head.healthSnapshot().running) {
            call.respondText(
                LaunchReplies.errorJson("head is not running"),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val request = receiveLaunchRequest(call)
        // V4-162: the windows are read per LAUNCH too — splice.toml's context_window is live and the
        // spec is boot-frozen, so a launch after an edit is planted with the edited window.
        val launched = target.catalog?.let(spec::withWindows) ?: spec
        val recipe = launchResponse.withAuthWarning(
            target,
            launched,
            launchService.launch(
                launched,
                request.extraArgs,
                request.dangerouslySkipPermissions,
                // DR-81: key presence is read per LAUNCH — the spec is boot-frozen, and a stale
                // gate left the capture hook armed against a credential `splice key set` landed.
                keyPresentNow = target.keyPresence.keyPresentNow(),
                cwd = request.cwd,
                wrapped = resolved.wrapped,
            ),
        )
        audit.launched(key, recipe.argv)
        if (recipe.warning != null) audit.warned(recipe.warning)
        call.respondText(launchResponse.launchRecipeJson(recipe), ContentType.Application.Json)
    }

    /** The head [key] launches, given its key/label [targets] (at most one — several were refused).
     *
     *  V4-129 review: a wrapped `claude` IS the launch shim, which names its head by its own basename
     *  — `claude`, a name no head carries, so every wrapped `claude` 404'd until unwrap. While the
     *  wrap is in place that name is the splice-owned Claude head over the vanilla ~/.claude
     *  (WrappedHead.launchThrough); a head really keyed or labeled `claude` still wins. */
    private fun resolve(key: String, targets: List<LaunchHead>): Resolved? {
        targets.singleOrNull()?.let { return Resolved(it, wrapped = null) }
        val wrapped = launchService?.wrap?.launchThrough(key) ?: return null
        return heads.byKey(CLAUDE_HEAD_KEY)?.let { Resolved(it, wrapped) }
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
