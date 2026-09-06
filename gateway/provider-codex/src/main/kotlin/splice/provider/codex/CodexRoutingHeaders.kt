// NEW: the per-turn routing/session headers codex-rs sends on every request (2026-09-05), so a
// splice turn lands where its predecessors did and reads the prompt cache warm.
//
// Measured before this file existed (claudex perf JSONL, 2026-09-05): a session's first turn after
// a reconnect read 12-21% of its prompt from the cache and its second 99% — same bytes, same
// prompt_cache_key, a different backend replica. The cache is routed by the key AND by connection
// locality, and codex-rs pins that locality with headers splice never sent: `session-id` and
// `thread-id` (codex-rs `build_session_headers`; the underscore spellings were dropped 2026-05-13)
// and `x-codex-routing-hint` (`model=<model>`, codex backend only). They ride BuiltTurn.extraHeaders,
// so the SSE POST and the WS handshake carry them alike; the WS-only `x-client-request-id` is
// derived from `thread-id` at the handshake (ResponsesWsRunner). Because per-turn headers are part
// of the WS connection identity, they are CONSTANT for a session: the thread id derives from the
// session id, not the conversation, so a compaction (a new first message, a new chain) keeps the
// same affinity for the system-prompt + tool prefix the two conversations share.
package splice.provider.codex

import splice.core.turn.TurnMeta
import java.util.UUID

internal class CodexRoutingHeaders {

    /** No session id (a bare curl, the e2e probe) = the routing hint alone. */
    fun forTurn(meta: TurnMeta): Map<String, String> = buildMap {
        put(HEADER_ROUTING_HINT, "model=${meta.upstreamModel}")
        val session = meta.sessionId?.takeIf { it.isNotBlank() } ?: return@buildMap
        put(HEADER_SESSION_ID, session)
        put(HEADER_THREAD_ID, threadIdFor(session))
    }

    /** codex-rs sends a UUID; a name-based UUID over the session id is one the backend cannot tell
     *  apart from it, stable for the session's life, and never shared by two sessions. */
    private fun threadIdFor(session: String): String =
        UUID.nameUUIDFromBytes("$THREAD_NAMESPACE:$session".toByteArray(Charsets.UTF_8)).toString()
}

private const val HEADER_SESSION_ID = "session-id"
private const val HEADER_THREAD_ID = "thread-id"
private const val HEADER_ROUTING_HINT = "x-codex-routing-hint"
private const val THREAD_NAMESPACE = "splice-thread"
