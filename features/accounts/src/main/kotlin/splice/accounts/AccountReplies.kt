// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (its private reply helpers) — the JSON reply shapes the
// status, sign-in, switch and edit routes share, held once so the four slices answer byte-identically.
package splice.accounts

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AccountReplies {
    // V4-132: the two body/JSON field names every login/switch/accounts route shares.
    const val LABEL_FIELD = "label"
    const val ACCOUNTS_PORT = "accounts"

    suspend fun respond(call: ApplicationCall, body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        call.respondText(body, ContentType.Application.Json, status)

    suspend fun respondError(call: ApplicationCall, message: String, status: HttpStatusCode) =
        respond(call, buildJsonObject { put("error", message) }.toString(), status)

    // NULL MEANS UNWIRED, same discipline as every other console port: a named 5xx, never a
    // payload that reads as "no accounts" (FEATURES.md §6, "did-not-run" law).
    suspend fun respondUnwired(call: ApplicationCall, what: String) = respondError(
        call,
        "the daemon wired no $what port; this route cannot answer",
        HttpStatusCode.ServiceUnavailable,
    )

    // A member, never a top-level fun (the wall bans those) or a JsonObject extension
    // (kt-no-extension-functions): JsonNull is a JsonPrimitive whose content is the literal
    // "null" (the same trap LoginIo.errorCode already steps around).
    fun stringField(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
