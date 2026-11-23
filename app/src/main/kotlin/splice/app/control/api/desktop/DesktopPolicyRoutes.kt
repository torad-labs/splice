// NEW: desktop-policy-console. The control plane's routes for Claude Desktop's policy, behind the management key:
//
//   GET   /api/desktop/policy   the policy as Settings draws it (DesktopPolicyRouteOps.snapshot): each key the console
//                               sets, in its group, with splice's default, the value Desktop is given and the
//                               console's own; the keys splice holds itself; and whether Desktop has yet to read the
//                               last change, which it does only at launch
//   PATCH /api/desktop/policy   {"<key>": value | null, ...}: each value kept in the console's overrides, each null put
//                               back to the default, then given to every Desktop on the door at once. Any key the
//                               console does not set, or a value not of its kind, refuses the whole body with 400
//
// One change at a time: the overrides file is read, changed, written and published under one lock, off the request
// thread. No answer is kept by a cache.
package splice.app.control.api.desktop

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.desktop.setup.DesktopPolicyTarget
import splice.app.desktop.setup.PolicyOverridesRead
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException

/** The daemon's Desktop part, asked at each request; null while the daemon has not wired it. */
internal fun interface PolicyTargetSource {
    operator fun invoke(): DesktopPolicyTarget?
}

internal class DesktopPolicyRoutes(private val target: PolicyTargetSource) {
    private val ops = DesktopPolicyRouteOps()
    private val mutation = Mutex()
    private val io = ProcessDispatchers().io()

    suspend fun read(call: ApplicationCall) {
        val desktop = target() ?: return reply(call, HttpStatusCode.ServiceUnavailable, refused(POLICY_NOT_WIRED))
        val body = withContext(io) { snapshot(desktop, restart = desktop.restartPending() == true) }
        reply(call, HttpStatusCode.OK, body)
    }

    suspend fun patch(call: ApplicationCall) {
        val desktop = target() ?: return reply(call, HttpStatusCode.ServiceUnavailable, refused(POLICY_NOT_WIRED))
        val body = JsonScalars.objectOrNull(Json, call.receiveText())
            ?: return reply(call, HttpStatusCode.BadRequest, refused("expected a JSON object of policy keys"))
        when (val patch = ops.parse(body)) {
            is PolicyPatch.Refused -> {
                val keys = JsonObject(patch.keys.mapValues { JsonPrimitive(it.value) })
                reply(call, HttpStatusCode.BadRequest, JsonObject(refused(patch.why) + ("keys" to keys)))
            }
            is PolicyPatch.Changes -> {
                val answer = mutation.withLock { withContext(io) { changed(desktop, patch) } }
                reply(call, answer.status, answer.body)
            }
        }
    }

    /** [patch] kept and published, answered with the policy after it; or refused with nothing changed. */
    private suspend fun changed(desktop: DesktopPolicyTarget, patch: PolicyPatch.Changes): Answer {
        val store = desktop.policyLayers.overrides
        return when (val read = store?.read()) {
            null -> Answer(HttpStatusCode.ServiceUnavailable, refused(POLICY_NOT_WIRED))
            is PolicyOverridesRead.Unreadable -> Answer(HttpStatusCode.Conflict, refused(read.why + UNREADABLE_FIX))
            is PolicyOverridesRead.Held -> saved(desktop, ops.changed(read.values, patch), patch.reset)
        }
    }

    /** [values] written as the console's overrides and given to Desktop, [reset] put back to the default. */
    private suspend fun saved(desktop: DesktopPolicyTarget, values: JsonObject, reset: Set<String>): Answer {
        try {
            desktop.policyLayers.overrides?.write(values)
        } catch (failure: IOException) {
            val why = "the Desktop policy was not saved: ${SafeFailureText.render(failure)}"
            return Answer(HttpStatusCode.InternalServerError, refused(why))
        }
        val published = desktop.publishPolicy(reset)
        val restart = (published ?: 0) > 0 || desktop.restartPending() == true
        val count = published?.let(::JsonPrimitive) ?: JsonNull
        return Answer(HttpStatusCode.OK, JsonObject(snapshot(desktop, restart) + ("published" to count)))
    }

    private fun snapshot(desktop: DesktopPolicyTarget, restart: Boolean): JsonObject {
        val read = desktop.policyLayers.overrides?.read() ?: PolicyOverridesRead.Held(JsonObject(emptyMap()), listOf())
        return ops.snapshot(desktop.policyLayers, read, restart)
    }

    /** A status and the body it is answered with. */
    private data class Answer(val status: HttpStatusCode, val body: JsonObject)

    private fun refused(why: String): JsonObject = buildJsonObject { put("error", why) }

    private suspend fun reply(call: ApplicationCall, status: HttpStatusCode, body: JsonObject) {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
}

// What the routes answer before the daemon has wired its Desktop part.
private const val POLICY_NOT_WIRED = "splice desktop: the Desktop policy opens once the daemon's door is up"

// What a person does about an overrides file splice can't read, after why.
private const val UNREADABLE_FIX = "; fix it or move it aside, and Settings shows splice's defaults again"
