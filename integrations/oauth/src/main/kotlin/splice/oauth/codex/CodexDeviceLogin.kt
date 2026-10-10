// NEW: the ChatGPT device-code login the console runs (spec section 11: "the daemon runs the same device flow the
// terminal runs"; the browser loopback flow stays terminal-only). It is not RFC 8628: OpenAI's own sequence,
// read 2026-10-10 from codex-rs/login/src/device_code_auth.rs, is three calls. (1) POST
// {issuer}/api/accounts/deviceauth/usercode {client_id} answers a user code and a device_auth_id; the person
// enters the code at {issuer}/codex/device. (2) POST .../deviceauth/token {device_auth_id, user_code} answers 403
// or 404 until they do, then 200 with an authorization code and the PKCE pair the SERVER minted. (3) The usual
// /oauth/token code exchange, with redirect_uri {issuer}/deviceauth/callback. Credentials persist through the
// same LoginIo path as every other login.
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
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.oauth.AuthHttpClientFactory
import splice.oauth.AuthJsonFromResponse
import splice.oauth.BrowserOpener
import splice.oauth.LoginAnnouncement
import splice.oauth.LoginIo
import splice.oauth.LoginObserver
import splice.oauth.OAuthLoginAccount
import splice.oauth.SystemBrowserOpener
import splice.provider.codex.CodexOAuth
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Path

private const val MS_PER_S = 1000L
private const val DEFAULT_INTERVAL_S = 5L
private const val MAX_WAIT_MS = 15 * 60 * MS_PER_S

/** Everything one ChatGPT device login needs; built by [LoginCodex.deviceSpec]. */
public data class CodexDeviceSpec(
    public val head: String,
    public val issuer: String,
    public val clientId: String,
    public val tokenUrl: String,
    public val authPath: Path,
    public val account: OAuthLoginAccount?,
    public val toAuthJson: AuthJsonFromResponse,
)

public class CodexDeviceLogin(
    private val output: TerminalOutput,
    browser: BrowserOpener = SystemBrowserOpener(output),
) {
    private val loginIo = LoginIo(output, browser)
    private val json = Json { ignoreUnknownKeys = true }
    private val oauth = CodexOAuth()

    private data class Grant(val code: String, val verifier: String)

    private data class UserCode(val deviceAuthId: String, val userCode: String, val intervalS: Long)

    /** Runs the flow to completion; true when credentials were written. [observer] present means the console is
     *  watching, so the code and link go through it instead of a terminal and a browser on the daemon's desktop. */
    public suspend fun run(
        spec: CodexDeviceSpec,
        waiter: Waiter = ProcessWaiter(),
        observer: LoginObserver? = null,
    ): Boolean {
        val client = AuthHttpClientFactory().create()
        return try {
            Cancellables.runCatchingBestEffort { signIn(client, spec, waiter, observer) }.getOrElse { e ->
                output.line("splice: login error: ${SafeFailureText.render(e)}")
                false
            }
        } finally {
            client.close()
            spec.account?.releaseReservation()
        }
    }

    private suspend fun signIn(
        client: HttpClient,
        spec: CodexDeviceSpec,
        waiter: Waiter,
        observer: LoginObserver?,
    ): Boolean {
        val code = requestUserCode(client, spec) ?: return false
        announce(spec, code, observer)
        val tokens = pollForGrant(client, spec, code, waiter)?.let { exchange(client, spec, it) }
        return tokens != null && loginIo.persistIfSignedIn(spec.authPath, spec.toAuthJson(tokens), spec.account)
    }

    private suspend fun requestUserCode(client: HttpClient, spec: CodexDeviceSpec): UserCode? {
        val resp = postJson(
            client,
            "${spec.issuer}/api/accounts/deviceauth/usercode",
            buildJsonObject { put("client_id", spec.clientId) },
        )
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            output.line(startFailure(resp.status.value, body))
            return null
        }
        val obj = json.parseToJsonElement(body).jsonObject
        val id = JsonScalars.str(obj, "device_auth_id")
        val user = JsonScalars.str(obj, "user_code")
        val interval = JsonScalars.str(obj, "interval")?.toLongOrNull() ?: DEFAULT_INTERVAL_S
        val named = !id.isNullOrBlank() && !user.isNullOrBlank()
        if (!named) output.line("splice: the device login answer named no code; nothing started.")
        return if (named) UserCode(id.orEmpty(), user.orEmpty(), interval) else null
    }

    private fun startFailure(status: Int, body: String): String =
        if (status == HttpStatus.NOT_FOUND) {
            "splice: device-code login is not enabled for this ChatGPT server; use the terminal login."
        } else {
            "splice: could not start device login (HTTP $status): ${loginIo.sanitize(body)}"
        }

    private fun announce(spec: CodexDeviceSpec, code: UserCode, observer: LoginObserver?) {
        val url = "${spec.issuer}/codex/device"
        if (observer != null) {
            observer.announced(LoginAnnouncement(userCode = code.userCode, verificationUri = url))
            return
        }
        output.line("")
        output.line("  splice: to sign in to ${spec.head}, enter this code in your browser:")
        output.line("")
        output.line("      ${code.userCode}")
        output.line("")
        output.line("  $url")
        output.line("")
        if (!loginIo.openBrowser(url)) output.line("splice: open the URL above to finish signing in.")
    }

    /** 403 and 404 both mean "not approved yet"; anything else but 200 ends the login. Gives up after 15 minutes. */
    private suspend fun pollForGrant(
        client: HttpClient,
        spec: CodexDeviceSpec,
        code: UserCode,
        waiter: Waiter,
    ): Grant? {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            waiter.wait(code.intervalS.coerceAtLeast(0L) * MS_PER_S)
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
                output.line("splice: login failed (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
                return null
            }
        }
        output.line("splice: the code expired after 15 minutes; try again.")
        return null
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
            output.line("splice: token exchange failed (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
            return null
        }
        return body
    }

    private suspend fun postJson(client: HttpClient, url: String, body: JsonObject): HttpResponse =
        client.post(url) {
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            setBody(body.toString())
        }
}
