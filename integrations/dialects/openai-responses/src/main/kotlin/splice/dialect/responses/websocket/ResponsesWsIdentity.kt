// NEW: connection identity + terminal observation for ResponsesWsRunner
// (concentration, 2026-08-19). The runner keeps the round attempt; this file
// owns the keys and the commit/clear that those keys protect. Same-package.
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.request.responsesRequestJson
import splice.dialect.responses.stream.ResponsesRoundEnd
import java.security.MessageDigest

internal class ResponsesWsIdentity(
    private val session: ResponsesWsSession,
    private val log: LogSink,
) {
    data class PendingCommit(val request: JsonObject, val generation: Long, val epoch: Long) {
        /** call_ids of the client-executed calls this round's response emitted, gathered from the
         *  streamed output items as they close: this backend's terminal carries an EMPTY `output`
         *  array (ResponsesItemFold.kt, and the 2026-09-05 live probe, where a terminal-only read
         *  recorded nothing and the next compaction chained into a refusal). */
        val calls: MutableSet<String> = mutableSetOf()
        val callItems: MutableMap<String, JsonObject> = mutableMapOf()
        val reasoning: MutableMap<String, String> = mutableMapOf()
        val reasoningSinceCall: MutableList<String> = mutableListOf()
        val requiredReasoning: MutableList<String> = mutableListOf()
        val assistantTexts: MutableList<String> = mutableListOf()
    }

    fun observeTerminal(chain: String, pending: PendingCommit?, event: JsonObject) {
        val type = JsonScalars.str(event[WS_FIELD_TYPE])
        if (type == OUTPUT_ITEM_DONE) {
            observeItem(pending, event["item"])
            return
        }
        if (type !in ResponsesRoundEnd.ALL) return
        val commit = pending
        if (type !in ResponsesRoundEnd.SUCCESS || commit == null) {
            session.cleared(chain)
            return
        }
        commitResponse(chain, commit, event["response"] as? JsonObject)
    }

    /** A round that ended on a failure or incomplete terminal names its socket's age and round on it. The ending's
     *  status and codes are the translator's line, in the same second. */
    fun observeEnding(key: String, event: JsonObject, pulse: WsPulse) {
        val type = JsonScalars.str(event[WS_FIELD_TYPE])
        val incomplete = type == "response.incomplete" ||
            JsonScalars.str((event["response"] as? JsonObject)?.get("status")) == "incomplete"
        if (type !in ResponsesRoundEnd.FAILED && !incomplete) return
        log("[ws] ${logKey(key)} round ended on $type: ${pulse.ageAndRound()}\n")
    }

    private fun commitResponse(chain: String, commit: PendingCommit, response: JsonObject?) {
        val output = (response?.get("output") as? JsonArray).orEmpty()
        val observedCalls = commit.callItems.ifEmpty {
            output.mapNotNull { item ->
                callIdOf(item)?.let { id -> (item as? JsonObject)?.let { id to it } }
            }.toMap()
        }
        val evidence = WsServerEvidence(
            assistantTexts = commit.assistantTexts.ifEmpty { output.mapNotNull(WsAssistantText::of) },
            calls = observedCalls.toMap(),
            requiredReasoning = if (commit.reasoning.isNotEmpty() || commit.callItems.isNotEmpty()) {
                commit.requiredReasoning.toList()
            } else if (observedCalls.isEmpty()) {
                emptyList()
            } else {
                output.mapNotNull { item ->
                    val obj = item as? JsonObject
                    if (JsonScalars.str(obj?.get(WS_FIELD_TYPE)) == REASONING_ITEM_TYPE) {
                        JsonScalars.str(obj?.get("id"))
                    } else {
                        null
                    }
                }
            },
            reasoning = commit.reasoning.ifEmpty {
                output.filter { item ->
                    JsonScalars.str((item as? JsonObject)?.get(WS_FIELD_TYPE)) == "reasoning"
                }.mapNotNull { item ->
                    val obj = item as JsonObject
                    val id = JsonScalars.str(obj["id"])
                    val cipher = JsonScalars.str(obj["encrypted_content"])
                    if (id != null && cipher != null) id to cipher else null
                }.toMap()
            },
        )
        session.completed(
            chain,
            commit.request,
            JsonScalars.str(response?.get("id")),
            commit.generation,
            commit.epoch,
            commit.calls + pendingCalls(response),
            evidence,
        )
    }

    private fun observeItem(pending: PendingCommit?, item: JsonElement?) {
        val obj = item as? JsonObject
        val kind = JsonScalars.str(obj?.get(WS_FIELD_TYPE))
        callIdOf(item)?.let { id ->
            pending?.calls?.add(id)
            obj?.let { pending?.callItems?.put(id, it) }
            if (kind == FUNCTION_CALL || kind == "custom_tool_call") {
                pending?.requiredReasoning?.addAll(pending.reasoningSinceCall)
                pending?.reasoningSinceCall?.clear()
            }
        }
        WsAssistantText.of(item)?.let { pending?.assistantTexts?.add(it) }
        if (kind == REASONING_ITEM_TYPE) {
            val id = JsonScalars.str(obj?.get("id"))
            val cipher = JsonScalars.str(obj?.get("encrypted_content"))
            if (id != null && cipher != null) {
                pending?.reasoning?.put(id, cipher)
                pending?.reasoningSinceCall?.add(id)
            }
        }
    }

    /** The call_ids the response's output leaves open — every output item that carries one
     *  (function_call, tool_search_call, any client-executed call the API grows). Type-agnostic on
     *  purpose: the server's rule is per call_id, not per item type, and the next turn's delta must
     *  answer each of them or it cannot chain (ResponsesWsSession.unansweredCalls). Read from the
     *  terminal too, for a backend that does fill `output` there. */
    private fun pendingCalls(response: JsonObject?): Set<String> =
        (response?.get("output") as? JsonArray)?.mapNotNull { callIdOf(it) }?.toSet() ?: emptySet()

    /** The id the CLIENT will answer with, or null when nothing will: a server-executed item
     *  (`execution` other than client — the backend answered it itself, ResponsesToolSearchParse)
     *  is not held, and a function_call with an empty call_id is held under the item id the fold
     *  hands the client (ResponsesItemFold), never under "" (review 2026-09-05). */
    private fun callIdOf(item: JsonElement?): String? {
        val obj = item as? JsonObject ?: return null
        val execution = JsonScalars.strOrEmpty(obj[FIELD_EXECUTION])
        if (execution.isNotEmpty() && execution != EXECUTION_CLIENT) return null
        val fallback = if (JsonScalars.str(obj[WS_FIELD_TYPE]) == FUNCTION_CALL) {
            JsonScalars.strOrEmpty(obj["id"])
        } else {
            ""
        }
        return JsonScalars.strOrEmpty(obj["call_id"]).ifEmpty { fallback }.ifEmpty { null }
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
            .onFailure { log("[ws] unparseable request body, so the round rides SSE: ${it::class.simpleName}\n") }
            .getOrNull()
}

/** Text the server actually emitted or the client echoes, never guessed from a prompt key. */
internal object WsAssistantText {
    fun of(item: JsonElement?): String? {
        val message = item as? JsonObject
        val content = message?.get("content")
        return when {
            JsonScalars.str(message?.get(FIELD_ROLE)) != "assistant" -> null
            content !is JsonArray -> JsonScalars.str(content)
            else -> {
                val parts = content.map { part ->
                    val block = part as? JsonObject
                    val type = JsonScalars.str(block?.get(WS_FIELD_TYPE))
                    if (type in setOf("text", "output_text", "input_text")) {
                        JsonScalars.str(block?.get("text"))
                    } else {
                        null
                    }
                }
                if (parts.any { it == null }) null else parts.joinToString("") { it.orEmpty() }
            }
        }
    }
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

private const val FIELD_EXECUTION = "execution"
private const val EXECUTION_CLIENT = "client"
private const val FUNCTION_CALL = "function_call"
private const val OUTPUT_ITEM_DONE = "response.output_item.done"

/** 8 bytes of SHA-256: collision-free enough to key a per-head connection pool, and short
 *  enough that the key stays readable. */
private const val DIGEST_BYTES = 8
