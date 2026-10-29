// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): POST /api/peers/codex, behind the management key, the
// route `splice peer codex attach` calls. It registers a native Codex thread as the peer `codex-<thread id>`, or brings
// the one there up to date, then starts its helper and answers once the helper's record is on the registry, as a
// register does. A Codex home with no app-server listening is refused unless a Codex process above the attach hosts
// the thread (Oct 28): that thread gets its messages at its next hook (CodexHookMailbox), and no message could reach
// one with neither.
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.peer.codex.CodexEndpoints
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.http.JsonBody
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.codex.CodexRequest
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.store.CodexAttach
import splice.sessions.peer.store.PeerRefusal
import java.nio.file.Path

private const val ATTACH_SHAPE =
    "body must be {\"thread\", \"home\", \"cwd\"}, with an optional \"owner\": {\"pid\", \"procStart\", \"pidDomain\"}"

internal class PeerCodexRoutes(private val parts: PeerParts) {
    private val jsonBody = JsonBody()
    private val names = CodexNames()
    private val endpoints = CodexEndpoints()

    /** POST /api/peers/codex. */
    suspend fun attach(call: ApplicationCall) {
        attached(jsonBody.parse(call)).send(call)
    }

    private suspend fun attached(body: JsonObject?): PeerAnswer {
        val request = request(body) ?: return PeerReplies.refuse(HttpStatusCode.BadRequest, ATTACH_SHAPE)
        val home = request.binding.home
        // A thread its Codex process hosts gets its messages at its next hook when no app-server holds it.
        if (request.binding.owner == null && endpoints.find(Path.of(home)) == null) {
            return PeerReplies.refuse(
                HttpStatusCode.Conflict,
                "no Codex app-server listens for $home and no Codex process above the attach hosts the thread, " +
                    "so no message could reach it",
            )
        }
        return when (val outcome = parts.store.attachCodex(request)) {
            is CodexAttach.Refused -> PeerReplies.refuse(
                if (outcome.why == PeerRefusal.INVALID) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
                outcome.reason,
            )
            is CodexAttach.Attached -> started(outcome, request)
        }
    }

    /** A changed peer's helper starts anew, so its record says what peer.json now says; an unchanged one starts only
     *  when none runs. */
    private suspend fun started(outcome: CodexAttach.Attached, request: CodexRequest): PeerAnswer {
        val name = outcome.config.name
        val helpers = parts.helpers
        if (outcome.fresh) {
            helpers?.stop(name)
            parts.log("[peers] attached ${LogSafe.str(name)}\n")
        }
        val pid = helpers?.start(name)
        return PeerReplies.ok(
            if (outcome.fresh) HttpStatusCode.Created else HttpStatusCode.OK,
            buildJsonObject {
                put("name", name)
                put("sessionId", outcome.config.sessionId)
                put("keyFile", outcome.keyFile.toString())
                put("socket", helpers?.running(name)?.socket)
                put("pid", pid)
                put("owner", request.binding.owner?.pid)
            },
        )
    }

    /** The request in [body], or null when a field is missing or an owner is given but not whole. */
    private fun request(body: JsonObject?): CodexRequest? {
        val owner = body?.get("owner") as? JsonObject
        val process = owner?.let(::process)
        val ownerWhole = owner == null || process != null
        val thread = PeerReplies.string(body, "thread")?.let(names::thread)
        val home = PeerReplies.string(body, "home")
        val binding = thread?.let { id -> home?.let { CodexBinding(id, it, process) } }?.takeIf { ownerWhole }
        return binding?.let { whole -> PeerReplies.string(body, "cwd")?.let { CodexRequest(whole, it) } }
    }

    private fun process(owner: JsonObject): NativeProcess? {
        val pid = JsonScalars.long(owner, "pid")?.takeIf { it > 0L }
        val start = JsonScalars.str(owner, "procStart")?.takeIf { it.isNotBlank() }
        val domain = JsonScalars.str(owner, "pidDomain")?.takeIf { it.isNotBlank() }
        return pid?.let { id -> start?.let { begun -> domain?.let { NativeProcess(id, begun, it) } } }
    }
}
