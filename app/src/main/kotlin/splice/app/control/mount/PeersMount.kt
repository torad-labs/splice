// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 3): the /api/peers routes. The management routes register, list,
// revoke and rotate peers and set their webhooks, behind the management key. The /api/peers/self routes act as one
// peer, behind that peer's own key (Door.PEER): they send under its name, and read and acknowledge its journal.
// Nothing a request carries names the sender: it is the principal the key opened, and the socket its helper runs on.
package splice.app.control.mount

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.http.JsonBody
import splice.http.JsonReply
import splice.sessions.note.PeerTarget
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.store.PeerRefusal
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: spec 1.1 and 3.4, the text cap after defusing; the route refuses a longer raw text with 413 before it is built.
private const val MAX_TEXT_BYTES = 32_768

// why: spec 3.3, a page of the inbox: 50 by default, at most 200, so one read stays a few hundred KB at most.
private const val DEFAULT_INBOX_LIMIT = 50
private const val MAX_INBOX_LIMIT = 200

// why: a webhook bearer is one token; a file longer than this is not a bearer file.
private const val MAX_BEARER_BYTES = 4_096

private val INBOX_KINDS = setOf("message", "receipt", "webhook")

private const val NAME_PARAM = "name"
private const val NO_PEER = "no peer has that name"
private const val UNWIRED = "peer messaging is not available in this build"
private const val NOT_RUNNING = "this peer's helper is not running"

/** The parts the routes act through. [outbox], [helpers] and [mail] are null until packages A, B and C are wired. */
internal data class PeerParts(
    val store: PeerStore,
    val registry: SessionSource,
    val outbox: PeerOutbox?,
    val helpers: PeerHelpers?,
    val mail: PeerMail?,
    val log: LogSink,
)

internal class PeersMount(parts: PeerParts, private val guard: ControlGuard) {
    private val rate = PeerSendRate()
    private val manage = PeerManagementRoutes(parts, rate)
    private val webhooks = PeerWebhookRoutes(parts)
    private val self = PeerSelfRoutes(parts, rate)

    fun register(route: Route) {
        route.post("/api/peers") { guard.guarded(call) { manage.register(call) } }
        route.get("/api/peers") { guard.guarded(call) { manage.list(call) } }
        route.delete("/api/peers/{name}") { guard.guarded(call) { manage.revoke(call) } }
        route.post("/api/peers/{name}/rotate") { guard.guarded(call) { manage.rotate(call) } }
        route.put("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.set(call) } }
        route.delete("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.clear(call) } }
        route.post("/api/peers/{name}/webhook/test") { guard.guarded(call) { webhooks.test(call) } }
        route.post("/api/peers/self/send") { guard.peer.guarded(call) { peer -> self.send(call, peer) } }
        route.get("/api/peers/self/inbox") { guard.peer.guarded(call) { peer -> self.inbox(call, peer) } }
        route.post("/api/peers/self/inbox/ack") { guard.peer.guarded(call) { peer -> self.ack(call, peer) } }
        route.get("/api/peers/self/sent/{msgId}") { guard.peer.guarded(call) { peer -> self.sent(call, peer) } }
    }
}

/** One route's answer: its status, its JSON body, and the `Retry-After` seconds a 429 carries. */
private data class PeerAnswer(val status: HttpStatusCode, val body: JsonObject, val retryAfter: Long? = null) {
    suspend fun send(call: ApplicationCall) {
        retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
        JsonReply(status, body.toString()).send(call)
    }
}

/** The answers both route families share. */
private object PeerReplies {
    fun refuse(status: HttpStatusCode, reason: String, retryAfter: Long? = null): PeerAnswer =
        PeerAnswer(status, buildJsonObject { put("error", reason) }, retryAfter)

    fun ok(status: HttpStatusCode, body: JsonObject): PeerAnswer = PeerAnswer(status, body)

    fun string(body: JsonObject?, key: String): String? =
        (body?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The management-key routes that make, list, revoke and rotate peers (spec 3.2). */
private class PeerManagementRoutes(private val parts: PeerParts, private val rate: PeerSendRate) {
    private val store = parts.store
    private val jsonBody = JsonBody()

    /** POST /api/peers: writes peer.json and peer.key, starts the helper, and answers once its record is on disk. */
    suspend fun register(call: ApplicationCall) {
        registered(jsonBody.parse(call)).send(call)
    }

    /** GET /api/peers: every peer, whether its helper runs, its scope, webhook and inbox counts. */
    suspend fun list(call: ApplicationCall) {
        val peers = store.list().map { config -> describe(config) }
        PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("peers", JsonArray(peers)) }).send(call)
    }

    /** DELETE /api/peers/{name}: drops the key first, then stops the helper; the journal stays unless purge=true. */
    suspend fun revoke(call: ApplicationCall) {
        val name = call.parameters[NAME_PARAM].orEmpty()
        val purge = call.request.queryParameters["purge"] == "true"
        revoked(name, purge).send(call)
    }

    /** POST /api/peers/{name}/rotate: a new peer.key; the old key fails at once. */
    suspend fun rotate(call: ApplicationCall) {
        val name = call.parameters[NAME_PARAM].orEmpty()
        val keyFile = if (store.wellFormed(name)) store.rotate(name) else null
        val answer = if (keyFile == null) {
            PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        } else {
            parts.log("[peers] rotated the key of ${LogSafe.str(name)}\n")
            PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("keyFile", keyFile.toString()) })
        }
        answer.send(call)
    }

    private suspend fun registered(body: JsonObject?): PeerAnswer {
        val name = PeerReplies.string(body, "name")
        val cwd = if (body?.containsKey("cwd") == true) PeerReplies.string(body, "cwd") else UserHome.dir().toString()
        val sendTo = sendTo(body)
        if (name == null) return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"name\": \"<name>\"}")
        if (cwd == null || sendTo == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "cwd is a path and sendTo a list of names")
        }
        val live = parts.registry.read().filter { it.availability != SessionAvailability.GONE }
        val liveNames = live.mapNotNull { it.name }.toSet()
        return when (val outcome = store.register(PeerRequest(name, cwd, sendTo), liveNames)) {
            is PeerRegistration.Refused -> PeerReplies.refuse(
                if (outcome.why == PeerRefusal.INVALID) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
                outcome.reason,
            )
            is PeerRegistration.Registered -> {
                val pid = parts.helpers?.start(name)
                parts.log("[peers] registered ${LogSafe.str(name)}\n")
                PeerReplies.ok(
                    HttpStatusCode.Created,
                    buildJsonObject {
                        put("name", name)
                        put("sessionId", outcome.config.sessionId)
                        put("keyFile", outcome.keyFile.toString())
                        put("socket", parts.helpers?.running(name)?.socket)
                        put("pid", pid)
                    },
                )
            }
        }
    }

    private suspend fun revoked(name: String, purge: Boolean): PeerAnswer {
        if (!store.wellFormed(name) || !store.revoke(name)) return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        rate.forget(name)
        parts.helpers?.stop(name)
        val mail = parts.mail
        val purged = purge && mail != null
        if (purged) mail?.purge(name)
        parts.log("[peers] revoked ${LogSafe.str(name)}\n")
        return PeerReplies.ok(
            HttpStatusCode.OK,
            buildJsonObject {
                put("revoked", name)
                put("purged", purged)
            },
        )
    }

    private fun describe(config: PeerConfig): JsonObject {
        val helper = parts.helpers?.running(config.name)
        val counts = parts.mail?.counts(config.name)
        val webhook = config.webhook
        return buildJsonObject {
            put("name", config.name)
            put("sessionId", config.sessionId)
            put("pid", helper?.pid)
            put("alive", helper != null)
            put("socket", helper?.socket)
            putJsonArray("sendTo") { config.sendTo.forEach { add(JsonPrimitive(it)) } }
            if (webhook == null) {
                put("webhook", JsonNull)
            } else {
                putJsonObject("webhook") {
                    put("url", webhook.url)
                    put("enabled", webhook.enabled)
                    put("lastOutcome", parts.mail?.lastWebhookOutcome(config.name))
                }
            }
            putJsonObject("inbox") {
                put("total", counts?.total ?: 0L)
                put("unread", counts?.unread ?: 0L)
            }
        }
    }

    /** `sendTo` as given, or `["*"]` when absent; null when it is there but not a list of strings. */
    private fun sendTo(body: JsonObject?): List<String>? {
        val raw = body?.get("sendTo") ?: return listOf("*")
        val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return items?.takeIf { list -> list.none { it == null } }?.filterNotNull()
    }
}

/** A webhook bearer read from its file: the value (null when none was asked for), or why it cannot be used. */
private data class BearerRead(val bearer: String?, val refusal: String?)

/** The management-key webhook routes (spec 3.2 and 4.4). */
private class PeerWebhookRoutes(private val parts: PeerParts) {
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
        if (cleared) parts.helpers?.reload(name)
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

/** A valid inbox query (spec 3.3). */
private data class InboxQuery(val after: Long, val limit: Int, val kinds: Set<String>)

/** The peer-key routes (spec 3.3). The principal decides whose journal and whose name; no route takes a peer name. */
private class PeerSelfRoutes(private val parts: PeerParts, private val rate: PeerSendRate) {
    private val store = parts.store
    private val targets = PeerTargets(parts.registry)
    private val jsonBody = JsonBody()

    /** POST /api/peers/self/send: resolve the target, check scope and rate, build the sender, send, record, answer. */
    suspend fun send(call: ApplicationCall, principal: PeerPrincipal) {
        sendAnswer(principal, jsonBody.parse(call)).send(call)
    }

    /** GET /api/peers/self/inbox?after&limit&kinds. */
    suspend fun inbox(call: ApplicationCall, principal: PeerPrincipal) {
        val query = inboxQuery(call)
        val mail = parts.mail
        val answer = when {
            query == null -> PeerReplies.refuse(
                HttpStatusCode.BadRequest,
                "after is a seq, limit 1 to $MAX_INBOX_LIMIT, and kinds of message, receipt and webhook",
            )
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            else -> {
                val page = mail.inbox(principal.name, query.after, query.limit, query.kinds)
                PeerReplies.ok(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("items", JsonArray(page.items))
                        put("next", page.next)
                        put("unread", page.unread)
                    },
                )
            }
        }
        answer.send(call)
    }

    /** POST /api/peers/self/inbox/ack {"through": seq}. */
    suspend fun ack(call: ApplicationCall, principal: PeerPrincipal) {
        val through = JsonScalars.long(jsonBody.parse(call), "through")?.takeIf { it >= 0 }
        val mail = parts.mail
        val answer = when {
            through == null -> PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"through\": <seq>}")
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            else -> {
                val read = mail.ack(principal.name, through)
                PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("read", read) })
            }
        }
        answer.send(call)
    }

    /** GET /api/peers/self/sent/{msgId}: the send's outcome and the receipt that came back for it, if one did. */
    suspend fun sent(call: ApplicationCall, principal: PeerPrincipal) {
        val msgId = call.parameters["msgId"].orEmpty()
        val mail = parts.mail
        val record = mail?.sent(principal.name, msgId)
        val answer = when {
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            record == null -> PeerReplies.refuse(HttpStatusCode.NotFound, "this peer sent no message with that id")
            else -> PeerReplies.ok(HttpStatusCode.OK, record)
        }
        answer.send(call)
    }

    private suspend fun sendAnswer(principal: PeerPrincipal, body: JsonObject?): PeerAnswer {
        val config = store.get(principal.name) ?: return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        val to = PeerReplies.string(body, "to")?.trim()?.takeIf { it.isNotEmpty() }
        val text = PeerReplies.string(body, "text")
        if (to == null || text == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"to\": …, \"text\": …}")
        }
        return textRefusal(text) ?: resolved(config, targets.resolve(to), text)
    }

    /** A target that is not one running session alone on its socket is refused with the resolver's reason. */
    private suspend fun resolved(config: PeerConfig, target: PeerTarget, text: String): PeerAnswer = when (target) {
        is PeerTarget.One -> deliver(config, target.record, text)
        is PeerTarget.None -> PeerReplies.refuse(HttpStatusCode.NotFound, target.reason)
        is PeerTarget.Ambiguous -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.NotRunning -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.NoSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.SharedSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
    }

    private fun textRefusal(text: String): PeerAnswer? = when {
        text.isBlank() -> PeerReplies.refuse(HttpStatusCode.BadRequest, "the text is empty")
        text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ->
            PeerReplies.refuse(HttpStatusCode.PayloadTooLarge, "a text is at most $MAX_TEXT_BYTES bytes")
        else -> null
    }

    /** Scope, then rate, then the helper: the order spec 3.3 gives, after the target resolved. */
    private suspend fun deliver(config: PeerConfig, target: SessionRecord, text: String): PeerAnswer {
        val refusal = when {
            target.sessionId == config.sessionId ->
                PeerReplies.refuse(HttpStatusCode.Conflict, "the target is this peer itself")
            !store.mayReach(config, target.name, target.sessionId) ->
                PeerReplies.refuse(HttpStatusCode.Forbidden, "the target is outside this peer's sendTo")
            else -> null
        }
        if (refusal != null) return refusal
        val wait = rate.take(config.name)
        if (wait is SendAllowance.Wait) {
            return PeerReplies.refuse(HttpStatusCode.TooManyRequests, "over the send rate", wait.seconds)
        }
        val helper = parts.helpers?.running(config.name)
        val outbox = parts.outbox
        return if (helper == null || outbox == null) {
            PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, NOT_RUNNING)
        } else {
            submit(config, target, text, PeerSender(socket = helper.socket, name = config.name), outbox)
        }
    }

    /** The sender is the principal's registration and its running helper, never anything from the request. */
    private suspend fun submit(
        config: PeerConfig,
        target: SessionRecord,
        text: String,
        sender: PeerSender,
        outbox: PeerOutbox,
    ): PeerAnswer {
        val outcome = outbox.send(target, text, sender)
        val msgId = (outcome as? SendOutcome.Submitted)?.msgId
        parts.mail?.recordSend(config.name, SentRecord(msgId, target, text.toByteArray(Charsets.UTF_8).size, outcome))
        return when (outcome) {
            is SendOutcome.Submitted -> PeerReplies.ok(
                HttpStatusCode.Accepted,
                buildJsonObject {
                    put("msgId", outcome.msgId)
                    putJsonObject("to") {
                        put("name", target.name)
                        put("sessionId", target.sessionId)
                    }
                },
            )
            is SendOutcome.Refused -> PeerReplies.refuse(HttpStatusCode.fromValue(outcome.httpStatus), outcome.reason)
            is SendOutcome.Failed -> PeerReplies.refuse(HttpStatusCode.BadGateway, outcome.reason)
        }
    }

    private fun inboxQuery(call: ApplicationCall): InboxQuery? {
        val query = call.request.queryParameters
        val after = bounded(query["after"], 0L, 0L..Long.MAX_VALUE)
        val limit = bounded(query["limit"], DEFAULT_INBOX_LIMIT.toLong(), 1L..MAX_INBOX_LIMIT.toLong())
        val kinds = query["kinds"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: INBOX_KINDS
        val known = kinds.isNotEmpty() && INBOX_KINDS.containsAll(kinds)
        return if (after == null || limit == null) null else InboxQuery(after, limit.toInt(), kinds).takeIf { known }
    }

    /** [absent] when the parameter is missing; null when it is there but not a whole number in [range]. */
    private fun bounded(text: String?, absent: Long, range: LongRange): Long? =
        if (text == null) absent else text.toLongOrNull()?.takeIf { it in range }
}
