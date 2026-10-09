package splice.upstream.sse

/** One send as it left: the [url], the [headers] redacted, and the [body] EXACT, which is the JSON before any
 *  content-encoding. [encoding] names the content-encoding the body rode under, when any. */
public data class WireRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val encoding: String?,
)
