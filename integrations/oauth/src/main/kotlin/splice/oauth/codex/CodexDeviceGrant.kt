// The second half of the ChatGPT device login: poll until the person approves the code, then trade the grant for
// tokens. Split out of CodexDeviceLogin, which keeps the code request and the announcement.
package splice.oauth.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.oauth.LoginIo
import splice.oauth.MS_PER_S
import splice.provider.codex.CodexOAuth
import splice.upstream.Waiter

// why: OpenAI's device codes live 15 minutes (codex-rs/login device_code_auth.rs), so polling past that is pointless.
internal const val DEVICE_CODE_MAX_WAIT_MS = 15 * 60 * MS_PER_S

internal data class UserCode(val deviceAuthId: String, val userCode: String, val intervalS: Long)

internal class CodexDeviceGrant(
    private val output: TerminalOutput,
    private val loginIo: LoginIo,
    private val maxWaitMs: Long = DEVICE_CODE_MAX_WAIT_MS,
) {
    private data class Grant(val code: String, val verifier: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val oauth = CodexOAuth()

    /** The token response body once the code is approved and exchanged, or null after saying why not. */
    suspend fun tokens(client: HttpClient, spec: CodexDeviceSpec, code: UserCode, waiter: Waiter): String? =
        pollForGrant(client, spec, code, waiter)?.let { exchange(client, spec, it) }

    /** 403 and 404 both mean "not approved yet"; anything else but 200 ends the login. Gives up after 15 minutes. */
    private suspend fun pollForGrant(
        client: HttpClient,
        spec: CodexDeviceSpec,
        code: UserCode,
        waiter: Waiter,
    ): Grant? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            // A server interval longer than the time left must not sleep past the code's life: cap the sleep,
            // then look at the clock again before asking.
            val left = deadline - System.currentTimeMillis()
            waiter.wait(minOf(code.intervalS.coerceAtLeast(0L) * MS_PER_S, left))
            if (System.currentTimeMillis() < deadline) {
                val resp = postJson(
                    client,
                    "${spec.issuer}/api/accounts/deviceauth/token",
                    buildJsonObject {
                        put("device_auth_id", code.deviceAuthId)
                        put("user_code", code.userCode)
                    },
                )
                val body = resp.bodyAsText()
                if (resp.status.isSuccess()) return grantFrom(body)
                if (resp.status.value != HttpStatus.FORBIDDEN && resp.status.value != HttpStatus.NOT_FOUND) {
                    output.line(failure("login failed", resp.status.value, body))
                    return null
                }
            }
        }
        output.line("splice: the code expired after 15 minutes; try again.")
        return null
    }

    /** What a refusal says: the HTTP status and the OAuth error code, never the body, which can carry a token or an
     *  email the person never asked to see in a log. */
    private fun failure(what: String, status: Int, body: String): String {
        val oauthError = loginIo.errorCode(body)
        return "splice: $what (HTTP $status)" + if (oauthError.isEmpty()) "" else ": ${loginIo.sanitize(oauthError)}"
    }

    private fun grantFrom(body: String): Grant? {
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        val code = JsonScalars.str(obj, "authorization_code")
        val verifier = JsonScalars.str(obj, "code_verifier")
        val complete = !code.isNullOrBlank() && !verifier.isNullOrBlank()
        if (!complete) output.line("splice: the device login answer carried no authorization code; NOT signed in.")
        return if (complete) Grant(code.orEmpty(), verifier.orEmpty()) else null
    }

    private suspend fun exchange(client: HttpClient, spec: CodexDeviceSpec, grant: Grant): String? {
        val redirect = "${spec.issuer}/deviceauth/callback"
        val resp = client.post(spec.tokenUrl) {
            header("Content-Type", "application/x-www-form-urlencoded")
            header("Accept", "application/json")
            setBody(oauth.codexCodeExchangeForm(grant.code, grant.verifier, spec.clientId, redirect))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            output.line(failure("token exchange failed", resp.status.value, body))
            return null
        }
        return body
    }

    suspend fun postJson(client: HttpClient, url: String, body: JsonObject): HttpResponse =
        client.post(url) {
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            setBody(body.toString())
        }
}
