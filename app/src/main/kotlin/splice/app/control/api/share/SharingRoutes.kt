// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The console's Sharing page, this machine's side:
//
//   GET    /api/share                   this machine's sharing and the machines it joined (SharingView)
//   PUT    /api/share                   {enabled?, port?, heads?}: `[share]` written through the one TopologyWriter
//                                       (ShareTopologyFile), and the listener follows it at once, with no restart
//   DELETE /api/share/machines/{name}   revoke a machine that joined this one
//
// Each machine's Sessions switch has its own rows (SessionsSwitchRoutes), and the machines this one joined theirs
// (RemoteRoutes). A refusal is an answer: 200 with ok false and the
// words `splice share` would print, as the topology route answers one.
package splice.app.control.api.share

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.share.ShareTopologyFile
import splice.app.control.SharingParts
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.upstream.codemode.ProcessDispatchers

/** What the rows turn: the daemon's share listener and machines, read at each request because the control plane
 *  assigns them after the server is built; null where no daemon built them, which answers 503. */
internal fun interface SharingWired {
    fun parts(): SharingParts?
}

internal class SharingRoutes(private val wired: SharingWired) {
    private val io = ProcessDispatchers().io()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): JsonReply {
        val parts = wired.parts() ?: return unwired()
        return withContext(io) {
            val said = Said()
            val file = ShareTopologyFile(said.terminal, SHARE_VERB, parts.env).loaded()
            if (file == null) {
                said.refused()
            } else {
                JsonReply(HttpStatusCode.OK, SharingView(parts, file).json().toString())
            }
        }
    }

    /** `[share]` with the body's changes, written, then followed by the listener before the page is answered. */
    suspend fun write(call: ApplicationCall): JsonReply {
        val text = call.receiveText()
        val change = Cancellables.runCatchingCancellable { json.decodeFromString(ShareChange.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { null })
            ?: return JsonReply(HttpStatusCode.BadRequest, error("the body must be $SHARE_BODY"))
        val parts = wired.parts() ?: return unwired()
        return withContext(io) { written(parts, change) }
    }

    private suspend fun written(parts: SharingParts, change: ShareChange): JsonReply {
        val said = Said()
        val file = ShareTopologyFile(said.terminal, SHARE_VERB, parts.env)
        val current = file.loaded() ?: return said.refused()
        val wanted = current.copy(share = change.applied(current))
        if (!file.saved(wanted)) return said.refused()
        parts.listener.follow(wanted.share)
        return ok(SharingView(parts, wanted).json())
    }

    suspend fun revoke(name: String): JsonReply {
        val parts = wired.parts() ?: return unwired()
        return withContext(io) { revoked(parts, name) }
    }

    private fun revoked(parts: SharingParts, name: String): JsonReply {
        val said = Said()
        val found = Cancellables.runCatchingCancellable { parts.listener.clients.revoke(name) }.getOrElse { failure ->
            said.bad("nothing was revoked: ${SafeFailureText.render(failure)}")
            return said.refused()
        }
        if (!found) said.bad("no machine named '$name' has joined")
        return if (found) ok() else said.refused()
    }

    private fun unwired(): JsonReply = JsonReply(HttpStatusCode.ServiceUnavailable, error(SHARING_UNWIRED))

    private fun ok(view: JsonObject? = null): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("ok", true)
            view?.let { put("share", it) }
        }.toString(),
    )

    private fun error(message: String): String = buildJsonObject { put("error", message) }.toString()
}

/** What a PUT /api/share asks to change; an absent member keeps what the file says. */
@Serializable
private data class ShareChange(val enabled: Boolean? = null, val port: Int? = null, val heads: List<String>? = null) {
    /** [current]'s table with these changes. [heads] naming every command that can be offered is stored absent, which
     *  offers a command added later too. */
    fun applied(current: Topology): ShareConfig {
        val share = current.share
        val all = current.heads.keys.filter { share.unshareable(current, it) == null }.toSet()
        val named = heads?.distinct()
        return share.copy(
            enabled = enabled ?: share.enabled,
            port = port ?: share.port,
            heads = if (named == null) share.heads else named.takeUnless { it.toSet() == all },
        )
    }
}

// why: the verb `splice share` prints its lines under (ShareCommand), which the page shows without it.
internal const val SHARE_VERB = "splice share"

// why: what a PUT /api/share that the route cannot read is told it must send.
private const val SHARE_BODY = "{\"enabled\"?: true|false, \"port\"?: <port>, \"heads\"?: [\"<command key>\", …]}"

// why: the 503 of a daemon that built no share listener (a rig), named rather than an empty page.
internal const val SHARING_UNWIRED = "sharing is not wired in this daemon"
