// NEW: connection identity + terminal observation for ResponsesWsRunner
// (concentration, 2026-08-19). The runner keeps the round attempt; this file
// owns the keys and the commit/clear that those keys protect. Same-package.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.security.MessageDigest

internal class ResponsesWsIdentity(
    private val session: ResponsesWsSession,
    private val log: LogSink,
) {
    data class PendingCommit(val request: JsonObject, val generation: Long, val epoch: Long)

    fun observeTerminal(chain: String, pending: PendingCommit?, event: JsonObject) {
        val type = JsonScalars.str(event[FIELD_TYPE])
        if (type !in ResponsesRoundEnd.ALL) return
        val commit = pending
        if (type !in ResponsesRoundEnd.SUCCESS || commit == null) {
            session.cleared(chain)
            return
        }
        session.completed(
            chain,
            commit.request,
            JsonScalars.str((event["response"] as? JsonObject)?.get("id")),
            commit.generation,
            commit.epoch,
        )
    }

    /** Session id + first-message hash, or NULL when either is missing.
     *
     *  Null means "do not chain, and do not reuse a socket" — enforced by the callers. Substituting
     *  empty strings (the first cut, caught in review of #72) silently re-opened the exact collision
     *  the two-part key exists to close: with no session id, every conversation whose first message
     *  hashes the same shares one chain, and one conversation's server-side context answers another.
     *  A missing isolation value is not a weaker key, it is NO key. */
    fun chainKey(meta: TurnMeta): String? = ResponsesConversationIdentity.chainKey(meta.sessionId, meta.conversationKey)

    /** The chain key plus a DIGEST of the handshake header set. Headers must participate in
     *  identity (a turn needing a different set must not ride a socket opened without it), but they
     *  carry the Authorization bearer token, and this key reaches daemon.log on the busy/connect
     *  paths. Hashing keeps identity exact while making it structurally impossible for a credential
     *  to be logged — safer than remembering to redact at every call site (review of #72). */
    fun connectionKey(chain: String, meta: TurnMeta, headers: Map<String, String>): String =
        ResponsesConversationIdentity.encode(listOf(chain, meta.upstreamModel, headerDigest(headers)))

    private fun headerDigest(headers: Map<String, String>): String {
        val canonical = ResponsesConversationIdentity.encode(headers.toSortedMap().flatMap { (k, v) -> listOf(k, v) })
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** The operator-facing form: keys are long and carry raw client text, so daemon.log gets a
     *  short stable digest instead of the key itself. */
    fun logKey(key: String): String = "ws-" + Integer.toHexString(key.hashCode())

    /** A body we cannot parse is a body we must not chain: fall back to SSE, which sends the
     *  original bytes untouched. */
    fun parseRequest(bodyJson: String): JsonObject? =
        Cancellables.runCatchingCancellable { responsesRequestJson.parseToJsonElement(bodyJson) as? JsonObject }
            .onFailure { log("[ws] unparseable request body — round rides SSE: ${it::class.simpleName}\n") }
            .getOrNull()
}

/** One injective composite identity for every responses conversation consumer. */
internal object ResponsesConversationIdentity {
    fun chainKey(sessionId: String?, conversationKey: String?): String? {
        val session = sessionId?.takeIf { it.isNotEmpty() } ?: return null
        val conversation = conversationKey?.takeIf { it.isNotEmpty() } ?: return null
        return encode(listOf(session, conversation))
    }

    fun encode(parts: List<String>): String =
        parts.joinToString("") { "${it.length}:$it" }
}

private const val FIELD_TYPE = "type"

/** 8 bytes of SHA-256: collision-free enough to key a per-head connection pool, and short
 *  enough that the key stays readable. */
private const val DIGEST_BYTES = 8
