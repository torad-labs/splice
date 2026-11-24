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
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.CLAUDE_SPLICE_COMMAND
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
import java.nio.file.Paths

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
        // door-network: a `[remote.claude]` is where the person said `claude` runs, another machine's door or head, so
        // it is asked before this machine's own door and head.
        val elsewhere = launchService?.remotes?.takeIf { key == CLAUDE_COMMAND }?.launch(key)
        if (elsewhere != null) return remoteReply(call, key, elsewhere)
        val door = doorLaunch(key)
        if (door != null) return doorReply(call, key, door)
        val targets = heads.targets(key)
        if (targets.size > 1) {
            val ambiguous = TopologyMessages.ambiguousHeadMessage(key, targets.map { it.head.key })
            return refuse(call, key, ambiguous, HttpStatusCode.Conflict)
        }
        val direct = targets.singleOrNull()?.let { Resolved(it, wrapped = ownHome(it)) }
        // LAN share: a name no head here carries may be a command another machine serves. A `[remote.claude]` is
        // asked before the Claude head here, keyed `claude` too: the person gave that name to another machine's head.
        val remote = if (direct == null || key == CLAUDE_HEAD_KEY) launchService?.remotes?.launch(key) else null
        if (remote != null) remoteReply(call, key, remote) else localReply(call, key, direct ?: wrapped(key))
    }

    /** door-default: `claude` on the door runs no head here, whatever splice.toml holds; nor does a door command
     *  (custom-provider, cp-command). Null when [key] is neither, or `claude` is not on the door. */
    private suspend fun doorLaunch(key: String): DoorLaunchSpec? {
        val door = launchService?.door ?: return null
        return when (key) {
            CLAUDE_COMMAND -> door.launch()
            CLAUDE_SPLICE_COMMAND -> door.launchSideBySide()
            else -> door.launchCommand(key)
        }
    }

    /** A head here, or the 404 or 503 that says why [key] launches none. */
    private suspend fun localReply(call: ApplicationCall, key: String, resolved: Resolved?) {
        val target = resolved?.head
        val spec = target?.spec
        if (spec == null || launchService == null) {
            val known = heads.all().joinToString(", ") { it.head.label }
            val unknown = "no launchable head named '$key' (configured: $known)"
            return refuse(call, key, unknown, HttpStatusCode.NotFound)
        }
        if (!target.head.healthSnapshot().running) {
            return refuse(call, key, "head is not running", HttpStatusCode.ServiceUnavailable)
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
        if (refusal != null) return refuse(call, key, refusal, HttpStatusCode.Conflict)
        // Read both live windows and the native destination hold after body reception has finished.
        val launched = target.catalog?.let(spec::withWindows) ?: spec
        // head-catalog: every head's command starts on the Splice gateway but one that forwards the person's own Claude
        // login, which the gateway never carries
        val gateway = service.door?.takeIf { launched.startsOnGateway }?.gateway(launched.signIn.headKey)
        val caller =
            LaunchCaller(request.cwd, resolved.wrapped, request.inheritedConfigDir, request.launch, gateway, key)
        val outcome = when (val launch = launchFor(target, launched, request, caller, service)) {
            is LaunchOutcome.Ready ->
                LaunchOutcome.Ready(launchResponse.withAuthWarning(target, launched, launch.recipe))
            is LaunchOutcome.Refused -> launch
        }
        answer(call, key, outcome)
    }

    /** `claude` on the door (docs/specs/door-default.md, decision 2): the recipe [door] carries, over the person's own
     *  Claude folder. */
    private suspend fun doorReply(call: ApplicationCall, key: String, door: DoorLaunchSpec) {
        val service = requireNotNull(launchService)
        val request = receiveLaunchRequest(call)
        val caller = LaunchCaller(request.cwd, null, request.inheritedConfigDir, request.launch)
        val headDirs = heads.all().mapNotNull { it.spec?.trees?.own }
        val skip = request.dangerouslySkipPermissions
        // #791: the session outlives the longest wall of the heads its turns can run on, as a head's outlives its own
        val wall = heads.all().mapNotNull { it.spec?.gateway?.apiTimeoutMs }.maxOrNull()
        val told = door.copy(notice = inHeadFolder(request.inheritedConfigDir, service), wallMs = wall)
        answer(call, key, service.onDoor.outcome(told, request.extraArgs, skip, caller, headDirs))
    }

    /** head-catalog: a `claude` started in a head's own Claude folder ([inherited], the CLAUDE_CONFIG_DIR its shell
     *  holds) is told what that head's own command is told; null in any folder that is no head's. */
    private suspend fun inHeadFolder(inherited: String?, service: LaunchService): String? {
        val dir = inherited?.let { Paths.get(it) }?.takeIf { it.isAbsolute }?.normalize()
        val head = dir?.let { d -> heads.all().firstOrNull { it.spec?.trees?.own?.toAbsolutePath()?.normalize() == d } }
        val spec = head?.spec ?: return null
        val gateway = service.door?.takeIf { spec.startsOnGateway }?.gateway(spec.signIn.headKey)
        return HeadsDeprecated().said(head.head.label, spec, gateway, service.door)
    }

    /** A command another machine serves: refused in that machine's words, or launched from the spec it answered,
     *  through the same recipe a head here gets. Nothing here holds its credentials, so there is no key to warn of. A
     *  wrapped `claude` served elsewhere launches as the wrapped `claude` does here, over the person's own ~/.claude:
     *  only where its turns go changes. */
    private suspend fun remoteReply(call: ApplicationCall, key: String, remote: RemoteLaunch) {
        val service = requireNotNull(launchService)
        val spec = when (remote) {
            is RemoteLaunch.Refused -> return refuse(call, key, remote.reason, HttpStatusCode.Conflict)
            is RemoteLaunch.Door -> return doorReply(call, key, remote.spec)
            is RemoteLaunch.Ready -> remote.spec
        }
        val request = receiveLaunchRequest(call)
        val wrapped = service.wrap.launchThrough(key)
        val refusal = service.loginGuard?.refusal(wrapped?.configDir ?: spec.trees.own)
        if (refusal != null) return refuse(call, key, refusal, HttpStatusCode.Conflict)
        val caller = LaunchCaller(request.cwd, wrapped, request.inheritedConfigDir, request.launch)
        val skip = request.dangerouslySkipPermissions
        val outcome = service.launchOutcome(spec, request.extraArgs, skip, caller = caller)
        answer(call, key, outcome)
    }

    /** A launch splice does not give, answered [status] in the words the shim prints. It is written to the log too:
     *  a launch that never started was otherwise nowhere to be found afterwards. */
    private suspend fun refuse(call: ApplicationCall, key: String, reason: String, status: HttpStatusCode) {
        audit.refused(key, reason)
        call.respondText(LaunchReplies.errorJson(reason), ContentType.Application.Json, status)
    }

    /** [outcome] as the shim reads it: a refusal in words, or its recipe, audited. */
    private suspend fun answer(call: ApplicationCall, key: String, outcome: LaunchOutcome) {
        when (outcome) {
            is LaunchOutcome.Refused -> refuse(call, key, outcome.reason, HttpStatusCode.Conflict)
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
        caller: LaunchCaller,
        service: LaunchService,
    ): LaunchOutcome = service.launchOutcome(
        spec,
        request.extraArgs,
        request.dangerouslySkipPermissions,
        // DR-81: key presence is read per launch, not from the boot-frozen spec.
        keyPresentNow = target.keyPresence.keyPresentNow(),
        caller = caller,
    )

    /** The Claude head by its own name runs over the person's own Claude home, exactly as a wrapped `claude` does
     *  ([WrappedHead.ownHome]): one home, so one login. Every other head runs over its own. */
    private fun ownHome(target: LaunchHead): WrappedLaunch? =
        launchService?.wrap?.takeIf { target.head.key == CLAUDE_HEAD_KEY }?.ownHome()

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
