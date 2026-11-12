// PORT-OF: ControlServer.kt (launch, receiveLaunchRequest) @ a77531a — invariants unchanged: the
// /launch route, its body parse and the exec-recipe response, now sharing JsonBody with ConfigRoutes.
// LAYOUT-01: reads heads through the LaunchHead projection control adapts from ManagedHead.
package splice.launch.recipe

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import splice.client.wrap.WrappedLaunch
import splice.core.topology.TopologyMessages
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.launch.LaunchAudit
import splice.launch.LaunchHead
import splice.launch.LaunchHeads
import splice.launch.LaunchOutcome
import splice.launch.LaunchReplies
import splice.launch.LaunchSpec
import splice.launch.RemoteLaunch
import splice.launch.wrap.CLAUDE_HEAD_KEY

/** [cwd]: V4-183, the shim's working directory, absent from a shim older than shim-4. */
private data class LaunchRequest(
    val extraArgs: List<String>,
    val dangerouslySkipPermissions: Boolean,
    val cwd: String?,
    val inheritedConfigDir: String?,
    val launch: String? = null,
)

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
        val direct = targets.singleOrNull()?.let { Resolved(it, wrapped = null) }
        // LAN share: a name no head here carries may be a command another machine serves. A `[remote.claude]` is
        // asked before the wrap's own Claude head: the person gave that name to another machine's head.
        val remote = if (direct == null) launchService?.remotes?.launch(key) else null
        if (remote != null) remoteReply(call, key, remote) else localReply(call, key, direct ?: wrapped(key))
    }

    /** A head here, or the 404 or 503 that says why [key] launches none. */
    private suspend fun localReply(call: ApplicationCall, key: String, resolved: Resolved?) {
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
        reply(call, key, resolved, request)
    }

    private suspend fun reply(call: ApplicationCall, key: String, resolved: Resolved, request: LaunchRequest) {
        val service = requireNotNull(launchService)
        val target = resolved.head
        val spec = requireNotNull(target.spec)
        val destination = resolved.wrapped?.configDir ?: spec.trees.own
        val refusal = service.loginGuard?.refusal(destination)
        if (refusal != null) {
            call.respondText(LaunchReplies.errorJson(refusal), ContentType.Application.Json, HttpStatusCode.Conflict)
            return
        }
        // Read both live windows and the native destination hold after body reception has finished.
        val launched = target.catalog?.let(spec::withWindows) ?: spec
        val outcome = when (val launch = launchFor(target, launched, request, resolved.wrapped, service)) {
            is LaunchOutcome.Ready ->
                LaunchOutcome.Ready(launchResponse.withAuthWarning(target, launched, launch.recipe))
            is LaunchOutcome.Refused -> launch
        }
        answer(call, key, outcome)
    }

    /** A command another machine serves: refused in that machine's words, or launched from the spec it answered,
     *  through the same recipe a head here gets. Nothing here holds its credentials, so there is no key to warn of. A
     *  wrapped `claude` served elsewhere launches as the wrapped `claude` does here, over the person's own ~/.claude:
     *  only where its turns go changes. */
    private suspend fun remoteReply(call: ApplicationCall, key: String, remote: RemoteLaunch) {
        val service = requireNotNull(launchService)
        val spec = when (remote) {
            is RemoteLaunch.Refused -> {
                val refused = LaunchReplies.errorJson(remote.reason)
                call.respondText(refused, ContentType.Application.Json, HttpStatusCode.Conflict)
                return
            }
            is RemoteLaunch.Ready -> remote.spec
        }
        val request = receiveLaunchRequest(call)
        val wrapped = service.wrap.launchThrough(key)
        val refusal = service.loginGuard?.refusal(wrapped?.configDir ?: spec.trees.own)
        if (refusal != null) {
            call.respondText(LaunchReplies.errorJson(refusal), ContentType.Application.Json, HttpStatusCode.Conflict)
            return
        }
        val caller = LaunchCaller(request.cwd, wrapped, request.inheritedConfigDir, request.launch)
        val skip = request.dangerouslySkipPermissions
        val outcome = service.launchOutcome(spec, request.extraArgs, skip, caller = caller)
        answer(call, key, outcome)
    }

    /** [outcome] as the shim reads it: a refusal in words, or its recipe, audited. */
    private suspend fun answer(call: ApplicationCall, key: String, outcome: LaunchOutcome) {
        when (outcome) {
            is LaunchOutcome.Refused -> call.respondText(
                LaunchReplies.errorJson(outcome.reason),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            is LaunchOutcome.Ready -> {
                val recipe = outcome.recipe
                audit.launched(key, recipe.argv)
                recipe.warning?.let(audit::warned)
                // A shim that names the line form in Accept is answered in it; everything else keeps the JSON body.
                val lineForm = LaunchRecipeLines.RECIPE_MEDIA_TYPE
                if (call.request.headers[HttpHeaders.Accept]?.contains(lineForm) == true) {
                    call.respondText(LaunchRecipeLines.render(recipe), ContentType.parse(lineForm))
                } else {
                    call.respondText(launchResponse.launchRecipeJson(recipe), ContentType.Application.Json)
                }
            }
        }
    }

    private fun launchFor(
        target: LaunchHead,
        spec: LaunchSpec,
        request: LaunchRequest,
        wrapped: WrappedLaunch?,
        service: LaunchService,
    ): LaunchOutcome = service.launchOutcome(
        spec,
        request.extraArgs,
        request.dangerouslySkipPermissions,
        // DR-81: key presence is read per launch, not from the boot-frozen spec.
        keyPresentNow = target.keyPresence.keyPresentNow(),
        caller = LaunchCaller(request.cwd, wrapped, request.inheritedConfigDir, request.launch),
    )

    /** The head a wrapped `claude` launches when no head here carries [key] and no other machine serves it.
     *
     *  V4-129 review: a wrapped `claude` IS the launch shim, which names its head by its own basename
     *  — `claude`, a name no head carries, so every wrapped `claude` 404'd until unwrap. While the
     *  wrap is in place that name is the splice-owned Claude head over the vanilla ~/.claude
     *  (WrappedHead.launchThrough); a head really keyed or labeled `claude` still wins, and so does a
     *  `[remote.claude]`. */
    private fun wrapped(key: String): Resolved? {
        val wrapped = launchService?.wrap?.launchThrough(key) ?: return null
        return heads.byKey(CLAUDE_HEAD_KEY)?.let { Resolved(it, wrapped) }
    }

    /** The same request, in the form a POSIX shim can write without JSON escaping: curl --data-urlencode puts each
     *  field on the wire byte for byte, and every `arg` is one argv word, in order, empty ones included. */
    private suspend fun receiveFormRequest(call: ApplicationCall): LaunchRequest {
        val fields = call.receiveParameters()
        return LaunchRequest(
            fields.getAll("arg").orEmpty(),
            fields["dangerouslySkipPermissions"] == "true",
            fields["cwd"],
            fields["inheritedConfigDir"],
            fields["launch"],
        )
    }

    private suspend fun receiveLaunchRequest(call: ApplicationCall): LaunchRequest {
        if (call.request.contentType().match(ContentType.Application.FormUrlEncoded)) return receiveFormRequest(call)
        val body = jsonBody.parse(call)
        // Safe by default: the caller must explicitly opt in with {"dangerouslySkipPermissions":"true"}
        // to get the flag; a missing key, malformed body, or any other value stays safe.
        val dangerouslySkipPermissions = JsonScalars.str(body, "dangerouslySkipPermissions") == "true"
        val extraArgs = (body?.get("args") as? JsonArray)
            ?.mapNotNull { JsonScalars.str(it) } ?: emptyList()
        return LaunchRequest(
            extraArgs,
            dangerouslySkipPermissions,
            JsonScalars.str(body, "cwd"),
            JsonScalars.str(body, "inheritedConfigDir"),
            JsonScalars.str(body, "launch"),
        )
    }
}
