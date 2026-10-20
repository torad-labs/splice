// NEW: Oct 19, 2026, moved verbatim out of PeersMount.kt by concern (ConcentrationLawTest): the management-key
// webhook routes of docs/specs/peer-messaging.md 3.2 and 4.4.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.LogSafe
import splice.core.util.SafeFailureText
import splice.http.JsonBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: a webhook bearer is one token; a file longer than this is not a bearer file.
private const val MAX_BEARER_BYTES = 4_096

/** A webhook bearer read from its file: the value (null when none was asked for), or why it cannot be used. */
private data class BearerRead(val bearer: String?, val refusal: String?)

/** The management-key webhook routes (spec 3.2 and 4.4). */
internal class PeerWebhookRoutes(private val parts: PeerParts) {
    private val store = parts.store
    private val jsonBody = JsonBody()

    /** PUT /api/peers/{name}/webhook: a new secret, the bearer copied when given, and the helper told. */
    suspend fun set(call: ApplicationCall) {
        val name = call.parameters[NAME_PARAM].orEmpty()
        val body = jsonBody.parse(call)
        val mail = parts.mail
        val answer = if (mail == null) {
            PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
        } else {
            set(name, body, mail)
        }
        answer.send(call)
    }

    /** DELETE /api/peers/{name}/webhook. */
    suspend fun clear(call: ApplicationCall) {
        val name = call.parameters[NAME_PARAM].orEmpty()
        val cleared = store.wellFormed(name) && store.clearWebhook(name)
        if (cleared) {
            parts.mail?.webhookChanged(name)
            parts.helpers?.reload(name)
        }
        val answer = if (cleared) {
            PeerReplies.ok(HttpStatusCode.OK, JsonObject(emptyMap()))
        } else {
            PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        }
        answer.send(call)
    }

    /** POST /api/peers/{name}/webhook/test: the test body, pushed now. */
    suspend fun test(call: ApplicationCall) {
        tested(call.parameters[NAME_PARAM].orEmpty()).send(call)
    }

    private suspend fun set(name: String, body: JsonObject?, mail: PeerMail): PeerAnswer {
        val url = PeerReplies.string(body, "url")
        val bearer = bearer(body)
        val refusal = when {
            url == null -> "body must be {\"url\": \"https://…\"}"
            else -> mail.webhookUrlRefusal(url) ?: bearer.refusal
        }
        if (url == null || refusal != null) return PeerReplies.refuse(HttpStatusCode.BadRequest, refusal.orEmpty())
        val files = if (store.wellFormed(name)) store.setWebhook(name, url, bearer.bearer) else null
        if (files == null) return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        mail.webhookChanged(name)
        parts.helpers?.reload(name)
        parts.log("[peers] set the webhook of ${LogSafe.str(name)}\n")
        return PeerReplies.ok(
            HttpStatusCode.OK,
            buildJsonObject {
                put("url", url)
                put("secretFile", files.secretFile.toString())
                put("bearer", files.bearer)
            },
        )
    }

    private suspend fun tested(name: String): PeerAnswer {
        val config = store.get(name)
        val mail = parts.mail
        return when {
            config == null -> PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
            config.webhook == null -> PeerReplies.refuse(HttpStatusCode.Conflict, "this peer has no webhook")
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            else -> pushed(mail.testWebhook(name))
        }
    }

    private fun pushed(result: WebhookTestResult): PeerAnswer = PeerReplies.ok(
        HttpStatusCode.OK,
        buildJsonObject {
            put("outcome", if (result.pushed) "pushed" else "failed")
            put("httpStatus", result.httpStatus)
            if (!result.pushed) put("detail", result.detail)
        },
    )

    /** The bearer file's contents when `bearerFile` is given: an absolute path to a readable, non-empty file. */
    private fun bearer(body: JsonObject?): BearerRead {
        if (body?.containsKey("bearerFile") != true) return BearerRead(null, null)
        val path = PeerReplies.string(body, "bearerFile")?.let { Path.of(it) }?.takeIf { it.isAbsolute }
            ?: return BearerRead(null, "bearerFile must be an absolute path")
        return try {
            val text = if (Files.size(path) > MAX_BEARER_BYTES) "" else Files.readString(path).trim()
            if (text.isEmpty()) BearerRead(null, "the bearer file is empty or too large") else BearerRead(text, null)
        } catch (unreadable: IOException) {
            BearerRead(null, "cannot read the bearer file: ${SafeFailureText.render(unreadable)}")
        }
    }
}
