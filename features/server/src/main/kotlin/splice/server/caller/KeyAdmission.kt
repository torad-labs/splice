// Server mode (docs/specs/server-mode.md, F1). The key a request to the server listener presents, as `x-api-key` or
// as `Authorization: Bearer`, and whether it is one this server serves. A missing, unknown, revoked or expired key is
// a 401 `authentication_error` that says which and never repeats the key, and the address it came from is counted
// among the refused (RefusedCallers).
package splice.server.caller

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import splice.core.auth.BearerScheme
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.server.KeyVerdict
import splice.server.ServerKey
import splice.server.ServerKeys
import splice.share.RefusalVoice
import java.time.Instant

internal class KeyAdmission(
    private val keys: ServerKeys,
    /** Who a request came from, behind the proxies `[server] trusted_proxies` names. */
    private val addresses: ClientAddresses,
    log: LogSink,
) {
    private val refused = RefusedCallers(log)

    /** The key [call] presents, once it is a live one; else null with the 401 answered in [voice], in words that say
     *  which of missing, unknown, revoked and expired it was and never repeat the key. */
    suspend fun admitted(call: ApplicationCall, voice: RefusalVoice): ServerKey? {
        val headers = call.request.headers
        val presented = headers[API_KEY_HEADER]?.trim()?.takeIf(String::isNotEmpty)
            ?: BearerScheme.bearerToken(headers[HttpHeaders.Authorization])
        val why = when (val verdict = keys.admit(presented)) {
            is KeyVerdict.Admitted -> return verdict.key
            is KeyVerdict.Revoked -> revoked(verdict.key)
            is KeyVerdict.Expired -> expired(verdict.key)
            KeyVerdict.Unknown -> if (presented == null) NO_KEY_SENT else "this key is not one this server issued"
        }
        val forwarded = headers.getAll(HttpHeaders.XForwardedFor)?.joinToString(",")
        refused.refused(addresses.of(call.request.origin.remoteAddress, forwarded), why)
        voice.refuse(call, HttpStatus.UNAUTHORIZED, why)
        return null
    }

    private fun revoked(key: ServerKey): String = when (val newer = key.supersededBy) {
        null -> "this key was revoked; ask the server's operator for a new one"
        else ->
            "this key was replaced by a newer sign-in on the same device ($newer); " +
                "sign in again (`splice login <server>`) if this device has lost it"
    }

    private fun expired(key: ServerKey): String {
        val since = key.expiresAt?.let { " on ${Instant.ofEpochMilli(it)}" }.orEmpty()
        return "this key expired$since; ask the server's operator for a new one"
    }
}

// The header Claude Code and the Anthropic SDKs send an API key in.
private const val API_KEY_HEADER = "x-api-key"

private const val NO_KEY_SENT = "no key was sent; send yours as x-api-key or as Authorization: Bearer"
