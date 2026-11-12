// NEW: Oct 27, 2026 (docs/peers.md, "Send as the peer"): PUT /api/peers/{name}/limit, behind the management key, gives
// one peer a send limit of its own, `{"perMinute": N, "burst": B}`, or its default back, `{"default": true}`. The next
// send it makes is held to it.
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.LogSafe
import splice.http.JsonBody
import splice.sessions.peer.store.PeerLimit
import splice.sessions.peer.store.PeerLimits

internal class PeerLimitRoute(private val parts: PeerParts, private val limits: PeerLimits) {
    private val jsonBody = JsonBody()

    suspend fun set(call: ApplicationCall) {
        val name = call.parameters[PEER_NAME_PARAM].orEmpty()
        answer(name, jsonBody.parse(call)).send(call)
    }

    private fun answer(name: String, body: JsonObject?): PeerAnswer {
        val reset = (body?.get("default") as? JsonPrimitive)?.booleanOrNull == true
        val asked = if (reset) null else limitOf(body)
        val refusal = asked?.refusal()
        return when {
            !parts.store.wellFormed(name) || parts.store.get(name) == null ->
                PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
            !reset && asked == null -> PeerReplies.refuse(
                HttpStatusCode.BadRequest,
                "body must be {\"perMinute\": N, \"burst\": B} or {\"default\": true}",
            )
            refusal != null -> PeerReplies.refuse(HttpStatusCode.BadRequest, refusal)
            !limits.set(name, asked) -> PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
            else -> {
                parts.log("[peers] set the send limit of ${LogSafe.str(name)}\n")
                PeerReplies.ok(HttpStatusCode.OK, limitJson(name, limits.of(name)))
            }
        }
    }

    private fun limitOf(body: JsonObject?): PeerLimit? {
        val perMinute = (body?.get("perMinute") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val burst = (body?.get("burst") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        return if (perMinute == null || burst == null) null else PeerLimit(perMinute, burst)
    }

    private fun limitJson(name: String, limit: PeerLimit): JsonObject = buildJsonObject {
        put("name", name)
        putJsonObject("limit") {
            put("perMinute", limit.perMinute)
            put("burst", limit.burst)
        }
    }
}
