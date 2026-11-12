// NEW: the per-turn routing/session headers codex-rs sends on every request (2026-09-05), so a
// splice turn lands where its predecessors did and reads the prompt cache warm.
//
// Measured before this file existed (claudex perf JSONL, 2026-09-05): a session's first turn after
// a reconnect read 12-21% of its prompt from the cache and its second 99% — same bytes, same
// prompt_cache_key, a different backend replica. The cache is routed by the key AND by connection
// locality, and codex-rs pins that locality with headers splice never sent: `session-id` and
// `thread-id` (codex-rs `build_session_headers`; the underscore spellings were dropped 2026-05-13)
// and `x-codex-routing-hint` (`model=<model>`, codex backend only). They ride BuiltTurn.extraHeaders,
// so the SSE POST and the WS handshake carry them alike. Because per-turn headers are part
// of the WS connection identity, they are CONSTANT for a session: the thread id derives from the
// session id, not the conversation, so a compaction (a new first message, a new chain) keeps the
// same affinity for the system-prompt + tool prefix the two conversations share.
//
// Fix 4 of the Oct 27 Responses fixes: codex-rs names the thread a second time as `x-client-request-id`, on the
// HTTP POST (codex-api/src/endpoint/responses.rs:86-89) as on the WS handshake (core/src/client.rs:1519-1520), and
// fills the body's client_metadata.thread_id from the same thread id (core/src/responses_metadata.rs). splice's body
// field was the prompt cache key since CX-03 (2026-08-13: "the conversation/session this turn belongs to", the
// identity splice had then); this header's session-derived id came on 2026-09-05 and is load-bearing, since it keys
// the WS connection and survives a compaction. So the body now names the header's thread, and the cache key stays.
package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.TurnMeta
import splice.upstream.BuiltTurn
import java.util.UUID

internal class CodexRoutingHeaders {

    /** No session id (a bare curl, the e2e probe) = the routing hint alone. */
    fun forTurn(meta: TurnMeta): Map<String, String> = buildMap {
        put(HEADER_ROUTING_HINT, "model=${meta.route.upstreamModel}")
        val session = meta.scope.sessionId?.takeIf { it.isNotBlank() } ?: return@buildMap
        val thread = threadIdFor(session)
        put(HEADER_SESSION_ID, session)
        put(HEADER_THREAD_ID, thread)
        put(HEADER_CLIENT_REQUEST_ID, thread)
    }

    /** [turn] with its body's client_metadata naming the thread its thread-id header names. A body that carries no
     *  client_metadata (a model that is not lite) gets none, and every other field, the prompt cache key included,
     *  stays as built. */
    fun oneThread(turn: BuiltTurn): BuiltTurn {
        val thread = turn.extraHeaders[HEADER_THREAD_ID] ?: return turn
        val metadata = turn.requestBody[CLIENT_METADATA] as? JsonObject ?: return turn
        val named = JsonObject(metadata + (THREAD_ID_FIELD to JsonPrimitive(thread)))
        return turn.copy(requestBody = JsonObject(turn.requestBody + (CLIENT_METADATA to named)))
    }

    /** codex-rs sends a UUID; a name-based UUID over the session id is one the backend cannot tell
     *  apart from it, stable for the session's life, and never shared by two sessions. */
    private fun threadIdFor(session: String): String =
        UUID.nameUUIDFromBytes("$THREAD_NAMESPACE:$session".toByteArray(Charsets.UTF_8)).toString()
}

private const val HEADER_SESSION_ID = "session-id"
private const val HEADER_THREAD_ID = "thread-id"
private const val HEADER_CLIENT_REQUEST_ID = "x-client-request-id"
private const val CLIENT_METADATA = "client_metadata"
private const val THREAD_ID_FIELD = "thread_id"
private const val HEADER_ROUTING_HINT = "x-codex-routing-hint"
private const val THREAD_NAMESPACE = "splice-thread"
