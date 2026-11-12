// NEW: failure-handling slice 3 (2026-10-26). The provider's own sentence in a failed reply, read the one way every API
// type's reader reads it. Moved from UpstreamFailureClassifier.ownMessage and saidIn unchanged: an error object's
// message, an error that is itself a string, a top-level message, then detail, and a body that is no JSON object as it
// was sent, which ProviderWordsFilter keeps only when it is no JSON at all.
package splice.upstream.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.util.JsonScalars

/** What a provider said in a failed reply, as it said it. */
public object ErrorMessage {
    /** The provider's own message field in an HTTP body [raw], or [raw] itself when it is not a JSON object. */
    public fun of(raw: String): String? {
        val body = JsonScalars.objectOrNull(bodies, raw) ?: return raw
        return inObject(body)
    }

    /** The provider's own message field in a parsed body or event [body]: the error object's message, an error that
     *  is itself a string, a top-level message, then detail. */
    public fun inObject(body: JsonObject): String? {
        val error = body["error"]
        return JsonScalars.str(error as? JsonObject, "message")
            ?: JsonScalars.strIfString(error).takeIf(String::isNotEmpty)
            ?: JsonScalars.str(body, "message")
            ?: JsonScalars.str(body, "detail")
    }

    /** What a line may quote of [raw]: [of], through [ProviderWordsFilter]. */
    public fun said(raw: String): ProviderWords? = ProviderWordsFilter.of(of(raw))
}

/** Bodies are the provider's, so they are read leniently: a key splice does not know never fails the read. */
private val bodies = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
