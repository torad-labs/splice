// How a token endpoint's failed answer reads, for the three token refreshes (KimiRefresh, GrokRefresh, CodexRefresh).
// Failure-handling slice 4 took their own loop of three POSTs away: one refresh is one exchange, and the turn's retry
// engine asks again 2 s and then 4 s later when this reads the answer as unavailable (final-design.md section 8).
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.auth.RefreshAttempt
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus

/** Reads a token endpoint's non-2xx answer: 401, 403 or an invalid_grant body is the login refused for good, even
 *  under a busy status; 429, 500, 502, 503 and 504 say the endpoint could not answer now; any other status is a
 *  denial that is no verdict on the token. A small named class the three refreshes construct (Kotlin style law). */
internal class TokenEndpointReply(private val json: Json) {
    private val unavailable = setOf(
        HttpStatus.TOO_MANY_REQUESTS,
        HttpStatus.INTERNAL_SERVER_ERROR,
        HttpStatus.BAD_GATEWAY,
        HttpStatus.SERVICE_UNAVAILABLE,
        HttpStatus.GATEWAY_TIMEOUT,
    )

    fun failed(status: Int, body: String): RefreshAttempt<Nothing> = when {
        status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN || isInvalidGrant(body) ->
            RefreshAttempt.InvalidGrant("HTTP $status")
        status in unavailable -> RefreshAttempt.Unavailable("HTTP $status")
        else -> RefreshAttempt.Denied("HTTP $status")
    }

    /** A body that will not parse is not an invalid_grant: the status alone decides it. */
    private fun isInvalidGrant(body: String): Boolean {
        val parsed = Cancellables.runCatchingCancellable { json.parseToJsonElement(body) as? JsonObject }
        return parsed.fold(
            onSuccess = { JsonScalars.str(it, "error") == "invalid_grant" },
            onFailure = { false },
        )
    }
}
