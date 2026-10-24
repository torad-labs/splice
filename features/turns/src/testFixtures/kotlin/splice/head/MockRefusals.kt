// The requests the mock refuses before any stream starts, and the two answers that are not a stream at all: a torn
// connection and a quota rejection.
package splice.head

import com.sun.net.httpserver.HttpExchange
import splice.core.util.Cancellables

internal const val HTTP_OK = 200
internal const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNAVAILABLE = 503
private const val HTTP_HOST_OVERLOADED = 529

/** Promised and never delivered: the length the torn scenario claims, so the client's read fails on a premature EOF. */
private const val TORN_CONTENT_LENGTH = 4096L
private const val QUOTA_RESET_SECONDS = "60"
private const val CAPACITY_BODY = """{"error":{"code":"server_is_overloaded",""" +
    """"message":"The engine is currently overloaded, please try again later"}}"""

internal class MockRefusals(private val bodies: List<Pair<String, String>>) {
    /** A refusal answered whole, with the status it carries. */
    data class Refusal(val status: Int, val body: String)

    /** The pre-stream refusal [scenario] asks for, or null when it asks for a stream. */
    fun refusalFor(scenario: String, auth: String?): Refusal? = when {
        scenario == "refresh" && auth == "Bearer tok-old" ->
            Refusal(HTTP_UNAUTHORIZED, """{"error":{"message":"token expired"}}""")
        // The host's own 529, which says "overloaded" by status and by error type, never by the ChatGPT code below.
        scenario == "overload_529" ->
            Refusal(
                HTTP_HOST_OVERLOADED,
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            )
        // Unconditional 401 (unlike "refresh" above, fires regardless of the Authorization header) so the request
        // exhausts UpstreamClient's one single-flight refresh and terminates in a real UpstreamFailed(status=401) —
        // the G19 login-hint path.
        scenario == "authfail" ->
            Refusal(HTTP_UNAUTHORIZED, """{"error":{"message":"invalid_api_key: Unauthorized"}}""")
        scenario == "overflow_http" ->
            Refusal(
                HTTP_BAD_REQUEST,
                """{"error":{"code":"context_length_exceeded",""" +
                    """"message":"prompt is too long: 210000 tokens > 200000 maximum"}}""",
            )
        else -> capacityStatus(scenario)?.let { Refusal(it, CAPACITY_BODY) }
    }

    // The ChatGPT backend's capacity signal in its PRE-STREAM shape (PR #115 classifies the code by shape):
    // "overload_503" never clears, so the head retries and then surfaces OVERLOADED; "overload_once" clears on the
    // second POST, so the head's own retry completes the turn; "overload_403" wears the same code on a 4xx, which
    // must stay a deterministic client error. The POST count in the recorded bodies is the retry proof — never the
    // verdict alone.
    private fun capacityStatus(scenario: String): Int? = when (scenario) {
        "overload_503" -> HTTP_UNAVAILABLE
        "overload_once" -> if (bodies.count { it.first == scenario } == 1) HTTP_UNAVAILABLE else null
        "overload_403" -> HTTP_FORBIDDEN
        else -> null
    }

    /** 200 committed, then a PARTIAL frame and an early socket drop: promising more bytes (fixed Content-Length)
     *  than we deliver makes the client's read fail with a premature EOF (IOException) BEFORE any complete client
     *  frame — the StreamTornBeforeClient path. */
    fun tear(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(HTTP_OK, TORN_CONTENT_LENGTH)
        val torn = runCatching {
            ex.responseBody.write("data: {\"type\":\"resp".toByteArray())
            ex.responseBody.flush()
        }
        Cancellables.discard(torn, "tear: drop mid-frame")
        ex.close()
    }

    /** ADDED (named change, NF-01): a hard 429 with a sub-ceiling Retry-After — arms the UpstreamClient's shared
     *  head-wide cooldown so restart-clears-it is testable. */
    fun quotaRejection(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Retry-After", QUOTA_RESET_SECONDS)
        val body = """{"detail":"Rate limit exceeded","resets_in_seconds":$QUOTA_RESET_SECONDS}""".toByteArray()
        ex.sendResponseHeaders(RATE_LIMITED_STATUS, body.size.toLong())
        ex.responseBody.use { it.write(body) }
        ex.close()
    }
}
