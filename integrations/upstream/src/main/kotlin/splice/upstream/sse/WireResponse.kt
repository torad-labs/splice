package splice.upstream.sse

/** One send as it came back: the [status] with its [headers], and [errorText] when it was not 2xx (the 2xx body is
 *  the stream the caller consumed). A send the transport lost has a null [status], no headers and no text. */
public data class WireResponse(
    val status: Int?,
    val headers: Map<String, String>,
    val errorText: String?,
)
