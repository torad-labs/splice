// NEW: read and update the same ordered policy the next request's pool selection uses.
package splice.accounts.order

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.http.JsonBody

public class AccountOrderRoute(private val resolver: AccountHeadResolver) {
    private val body = JsonBody()

    public suspend fun get(call: ApplicationCall, native: List<ClaudeLoginPlaceView> = emptyList()) {
        val head = resolver.resolveOrRespond(call, call.parameters["head"].orEmpty()) ?: return
        val source = head.pool as? HeadAccountOrderSource
        if (source != null) {
            respond(call, Target(head.key, source))
            return
        }
        val signedIn = native.filter { it.head == head.key && it.credentialPresent }
        val identities = signedIn.map { it.account?.uuid?.takeIf(String::isNotBlank) }
        if (identities.distinct().singleOrNull() != null) {
            AccountReplies.respond(
                call,
                buildJsonObject {
                    put("head", head.key)
                    putJsonArray("order") { }
                    putJsonArray("effective_order") { }
                    put("single_account", true)
                }.toString(),
            )
        } else {
            unavailable(call, head.key)
        }
    }

    public suspend fun set(call: ApplicationCall) {
        val target = target(call) ?: return
        val labels = labels(call) ?: return
        if (target.source.setOrder(labels)) {
            respond(call, target)
        } else {
            AccountReplies.respondError(
                call,
                "order contains duplicate or unknown account labels",
                HttpStatusCode.BadRequest,
            )
        }
    }

    private suspend fun target(call: ApplicationCall): Target? {
        val head = resolver.resolveOrRespond(call, call.parameters["head"].orEmpty()) ?: return null
        val source = head.pool as? HeadAccountOrderSource
        if (source == null) {
            unavailable(call, head.key)
            return null
        }
        return Target(head.key, source)
    }

    private suspend fun unavailable(call: ApplicationCall, head: String) = AccountReplies.respondError(
        call,
        "head '$head' has no selectable account source",
        HttpStatusCode.Conflict,
    )

    private suspend fun labels(call: ApplicationCall): List<String>? {
        val parsed = body.parse(call) ?: return null
        val array = parsed["order"] as? JsonArray
        val labels = array?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        return if (labels == null || labels.size != array.size) {
            AccountReplies.respondError(call, "body must contain an 'order' array of labels", HttpStatusCode.BadRequest)
            null
        } else {
            labels
        }
    }

    private suspend fun respond(call: ApplicationCall, target: Target) = AccountReplies.respond(
        call,
        buildJsonObject {
            put("head", target.head)
            putJsonArray("order") { target.source.order().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("effective_order") { target.source.effectiveOrder().forEach { add(JsonPrimitive(it)) } }
        }.toString(),
    )

    private data class Target(val head: String, val source: HeadAccountOrderSource)
}
