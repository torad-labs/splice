package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.util.LogSafe
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerLimits
import splice.sessions.registry.SessionAvailability

/** The management-key routes that make, list, revoke and rotate peers (spec 3.2). */
internal class PeerManagementRoutes(
    private val parts: PeerParts,
    private val rate: PeerSendRate,
    private val limits: PeerLimits,
) {
    private val store = parts.store
    private val ownerProcesses = PeerOwnerProcesses()

    /** GET /api/peers: every peer, whether its helper runs, its scope, webhook and inbox counts. */
    suspend fun list(call: ApplicationCall) {
        val peers = store.list().map { config -> describe(config) }
        PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("peers", JsonArray(peers)) }).send(call)
    }

    /** DELETE /api/peers/{name}: drops the key first, then stops the helper; the journal stays unless purge=true. */
    suspend fun revoke(call: ApplicationCall) {
        val name = call.parameters[PEER_NAME_PARAM].orEmpty()
        val purge = call.request.queryParameters["purge"] == "true"
        revoked(name, purge).send(call)
    }

    /** Resolves the thread's registered name, including an existing peer attached under its original name. */
    suspend fun detachCodex(call: ApplicationCall) {
        val thread = call.parameters["thread"]
        val name = store.codexThreads.entries.singleOrNull { it.value.thread == thread }?.key
        val answer = if (name == null) {
            PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        } else {
            revoked(name, call.request.queryParameters["purge"] == "true")
        }
        answer.send(call)
    }

    /** POST /api/peers/{name}/rotate: a new peer.key; the old key fails at once. */
    suspend fun rotate(call: ApplicationCall) {
        val name = call.parameters[PEER_NAME_PARAM].orEmpty()
        val keyFile = if (store.wellFormed(name)) store.rotate(name) else null
        val answer = if (keyFile == null) {
            PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        } else {
            parts.log("[peers] rotated the key of ${LogSafe.str(name)}\n")
            PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("keyFile", keyFile.toString()) })
        }
        answer.send(call)
    }

    private suspend fun revoked(name: String, purge: Boolean): PeerAnswer {
        if (!store.wellFormed(name) || !store.revoke(name)) return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        rate.forget(name)
        limits.forget(name)
        parts.helpers?.stop(name)
        val mail = parts.mail
        val purged = purge && mail != null
        if (purged) mail.purge(name)
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
        val counts = parts.mail?.counts(config.name)
        val webhook = config.webhook
        val (pid, alive, socket) = runtimeOf(config.name)
        return buildJsonObject {
            put("name", config.name)
            put("sessionId", config.sessionId)
            put("pid", pid)
            put("alive", alive)
            put("socket", socket)
            putJsonArray("sendTo") { config.sendTo.forEach { add(JsonPrimitive(it)) } }
            val limit = limits.of(config.name)
            putJsonObject("limit") {
                put("perMinute", limit.perMinute)
                put("burst", limit.burst)
            }
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

    private fun runtimeOf(name: String): Triple<Long?, Boolean, String?> {
        if (name !in store.nativeInboxes) {
            val helper = parts.helpers?.running(name)
            return Triple(helper?.pid, helper != null, helper?.socket)
        }
        val owner = store.nativeOwners[name]
        val record = owner?.let { o ->
            parts.registry.read().firstOrNull {
                it.availability != SessionAvailability.GONE && it.process.pid == o.pid
            }
        }
        val alive = record != null || (owner != null && ownerProcesses.live(owner))
        return Triple(owner?.pid, alive, record?.process?.messagingSocketPath)
    }
}
