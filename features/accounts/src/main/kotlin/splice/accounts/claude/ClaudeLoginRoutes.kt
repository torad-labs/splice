// NEW: place-addressed native login and refresh, with polling through the existing head login route.
package splice.accounts.claude

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import splice.accounts.AccountHead
import splice.accounts.AccountReplies
import splice.accounts.signin.LoginStatusJson
import splice.core.util.JsonScalars
import splice.http.JsonBody
import java.util.concurrent.TimeUnit

private data class NativeLoginRequest(val label: String?)

public class ClaudeLoginRoutes(private val source: ClaudeLoginPlacesSource) {
    private val body = JsonBody()

    public suspend fun login(call: ApplicationCall) {
        val owner = owner(call) ?: return
        val place = place(call, owner) ?: return
        val request = request(call) ?: return
        AccountReplies.respond(call, LoginStatusJson.json(owner.login(place, request.label)))
    }

    private suspend fun request(call: ApplicationCall): NativeLoginRequest? {
        val parsed = body.parse(call)
        if (parsed == null) {
            AccountReplies.respondError(call, "native login body must be a JSON object", HttpStatusCode.BadRequest)
            return null
        }
        val label = parsed["label"]
        if (label != null && label != JsonNull) {
            if (label !is JsonPrimitive || !label.isString) {
                AccountReplies.respondError(call, "native login label must be a string", HttpStatusCode.BadRequest)
                return null
            }
        }
        return NativeLoginRequest((label as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }

    public suspend fun refresh(
        call: ApplicationCall,
        providers: Map<String, String>,
        heads: Map<String, AccountHead> = emptyMap(),
    ) {
        val owner = owner(call) ?: return
        val place = place(call, owner) ?: return
        val view = owner.refresh(place)
        val provider = providers[view.head]
        if (provider == null) {
            AccountReplies.respondError(call, "native head provider is not wired", HttpStatusCode.ServiceUnavailable)
            return
        }
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        val row = ClaudeLoginRows.list(
            listOf(view),
            mapOf(view.head to provider),
            mapOf(view.head to owner.carrying(view.head)),
            heads,
            nowSeconds,
        ).single()
        AccountReplies.respond(call, row.toString())
    }

    /** False leaves an OAuth id to the existing route; a native id is answered only on its own head. */
    public suspend fun poll(call: ApplicationCall): Boolean {
        val status = source()?.poll(call.parameters["id"].orEmpty()) ?: return false
        if (call.parameters["head"] != status.head) return false
        AccountReplies.respond(call, LoginStatusJson.json(status))
        return true
    }

    public suspend fun submit(call: ApplicationCall) {
        val owner = owner(call) ?: return
        val id = call.parameters["id"].orEmpty()
        val status = owner.poll(id)
        if (status == null || status.head != call.parameters["head"]) {
            AccountReplies.respondError(call, "unknown native login id", HttpStatusCode.NotFound)
            return
        }
        val code = JsonScalars.str(body.parse(call), "code")
        if (code == null || !owner.submit(id, code)) {
            AccountReplies.respondError(call, "native login is not accepting that code", HttpStatusCode.Conflict)
            return
        }
        AccountReplies.respond(call, LoginStatusJson.json(owner.poll(id) ?: status))
    }

    private suspend fun owner(call: ApplicationCall): ClaudeLoginPlaces? {
        val owner = source()
        if (owner == null) {
            AccountReplies.respondError(call, "native Claude logins are not wired", HttpStatusCode.ServiceUnavailable)
        }
        return owner
    }

    private suspend fun place(call: ApplicationCall, owner: ClaudeLoginPlaces): ClaudeLoginPlaceId? {
        val place = ClaudeLoginPlaceId.entries.firstOrNull { it.wire == call.parameters["place"] }
        if (place == null || owner.places().none { it.id == place }) {
            AccountReplies.respondError(call, "unknown native Claude login place", HttpStatusCode.NotFound)
            return null
        }
        return place
    }
}
