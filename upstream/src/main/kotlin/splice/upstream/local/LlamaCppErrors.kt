// NEW: V4-164 (2026-09-19) — llama-server's own error vocabulary, named by its STRUCTURED type.
//
// A local runtime's errors reached the client as the bare text it sent. "Loading model" and
// "Context size has been exceeded." are accurate to someone who has read llama.cpp's source and
// meaningless in a Claude Code banner, and one of the three is worse than terse:
// exceed_context_size_error is a context overflow that the overflow rule could not see (its wording
// is "exceeds the available context", its code spells exceed_context), so Claude Code never received
// the "prompt is too long: N tokens > M maximum" line it compacts on, and a conversation that only
// needed compacting ended instead.
//
// Every shape below is quoted from llama.cpp's server, which is where the wording is fixed:
// format_error_response (tools/server/server-common.cpp) emits {code: <HTTP-equivalent int>,
// message, type}; server_task_result_error::to_json (server-task.cpp:1495) adds n_prompt_tokens and
// n_ctx to exceed_context_size_error only; "Loading model" is the 503 served while the weights load
// (server-http.cpp:258); and "Context size has been exceeded." is the decode failure sent to every
// in-flight slot at once when the KV cache has no free cell left (server-context.cpp:3610).
// Provenance beats wording (the classifier's own law): two are matched by their structured type,
// and the third by llama.cpp's exact literal, because its type is the generic server_error.
package splice.upstream.local

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Rewrites llama-server's error text into text that names the condition. Any other vendor's
 *  message comes back unchanged, which is what keeps every non-local head byte-identical. */
internal object LlamaCppErrors {

    /** [err] is the parsed `error` object when the caller has one (HTTP); an SSE caller passes null
     *  and loses nothing, because the one shape that needs the object's fields arrives pre-stream. */
    fun explain(err: JsonObject?, code: String, message: String): String = when {
        code == EXCEED_CONTEXT -> tooLong(err, message)
        code == UNAVAILABLE -> "$message — the local model server has not finished loading its model"
        message.trim() == DECODE_OUT_OF_CONTEXT ->
            "$message — the model server's KV cache is full: every slot draws on one shared context " +
                "pool, and the other conversations on this server hold the rest of it"
        else -> message
    }

    /** Claude Code's own wording and order ("prompt is too long: N tokens > M maximum"), because its
     *  overflow parse reads the two numbers out of exactly that line; without them it still
     *  compacts, so a body missing the counts keeps the phrase and loses only the numbers. */
    private fun tooLong(err: JsonObject?, message: String): String {
        val prompt = JsonScalars.long(err, "n_prompt_tokens")
        val window = JsonScalars.long(err, "n_ctx")
        return if (prompt != null && window != null) {
            "prompt is too long: $prompt tokens > $window maximum ($message)"
        } else {
            "prompt is too long: $message"
        }
    }

    private const val EXCEED_CONTEXT = "exceed_context_size_error"
    private const val UNAVAILABLE = "unavailable_error"
    private const val DECODE_OUT_OF_CONTEXT = "Context size has been exceeded."
}
