// NEW: failure-handling slice 3 (2026-10-26). The error object OpenAI's two APIs share, read once for both OpenAI
// dialects (final-design.md section 6): an HTTP body's {"error":{"message","type","param","code"}}, a Chat stream's
// error chunk of the same shape, and a Responses event's error, whether the event carries it as its own error object
// or as its own fields.
package splice.upstream.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** One OpenAI-shaped error: its [code] (a string code, or llama-server's numeric status as text; empty when none),
 *  its [type] (empty when none) and the provider's own [message]. */
public data class OpenAiError(val code: String, val type: String, val message: String?) {
    /** Whether the error names [word] as its code or its type, whatever the case. */
    public fun names(word: String): Boolean =
        code.equals(word, ignoreCase = true) || type.equals(word, ignoreCase = true)
}

/** Reads the OpenAI error object out of a body or an event. */
public object OpenAiErrorEnvelope {
    /** The error a reply's [body] carries, or null when the body is no JSON object (a gateway page, plain text). */
    public fun of(body: String): OpenAiError? = JsonScalars.objectOrNull(envelopes, body)?.let(::of)

    /** The error [obj] carries: its error object's code and type, else its own code (xAI writes
     *  {"code":"personal-team-blocked:spending-limit","error":"You have run out of credits."}), and the provider's own
     *  message as [ErrorMessage] reads it. An event's own `type` names the event, never the error: it is not read. */
    public fun of(obj: JsonObject): OpenAiError {
        val error = obj["error"] as? JsonObject
        val code = JsonScalars.str(error, "code") ?: JsonScalars.str(obj, "code")
        return OpenAiError(code.orEmpty(), JsonScalars.str(error, "type").orEmpty(), ErrorMessage.inObject(obj))
    }

    /** An error object read on its own, as a Responses `response.failed` carries it under `response.error`. */
    public fun error(error: JsonObject): OpenAiError = OpenAiError(
        JsonScalars.str(error, "code").orEmpty(),
        JsonScalars.str(error, "type").orEmpty(),
        JsonScalars.str(error, "message"),
    )
}

/** Bodies are the provider's, so they are read leniently. */
private val envelopes = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
