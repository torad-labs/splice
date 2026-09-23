// NEW: RFC 8628 device-authorization login — the no-loopback counterpart to OAuthLoginFlow.
// Vendor-neutral: LoginKimi / LoginMuse supply DeviceLoginSpec. POST device_authorization → print
// the user_code + verification URL, open the browser → poll the token endpoint until the user
// approves. State machine per the verified kimi contract: authorization_pending keeps polling;
// slow_down bumps the interval PERMANENTLY (+5s); expired_token restarts the WHOLE flow (bounded
// to 2 restarts); access_denied / >=500 abort; the device_authorization expires_in is the overall
// deadline. Credentials persist through the shared atomic-0600 writeCredentialFile. Its lines go
// out through the caller's LoginOutput; the bounded runBlocking bridge lives in the CLI.
package splice.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import splice.core.auth.CredentialExpiry
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter

// DeviceLoginSpec lives in DeviceLoginSpec.kt (concentration, 2026-08-19).

private const val MAX_EXPIRED_RESTARTS = 2
private const val SLOW_DOWN_INCREMENT_S = 5L
private const val MS_PER_S = 1000L

// DR-190 (DR-177's unenumerated fifth site): expires_in and interval come off the wire. A value that
// does not fit in milliseconds wrapped `now + expiresInS * MS_PER_S` negative — EXPIRED before the
// first poll — and `intervalS * MS_PER_S` negative. The deadline degrades the way DR-177's
// CredentialExpiry does (unrepresentable → the synthetic 4h ceiling, never an instant expiry) and
// the interval is capped in seconds before it is multiplied; both are no-ops for RFC 8628 values.
private const val MAX_POLL_INTERVAL_S = 3600L

/** A class, not an `object` (LAYOUT-01): every line the flow speaks goes to [output], so each caller
 *  hands in its own — the CLI a terminal, a test a recorder — and [browser] rides with it, which is
 *  what keeps a test off the real browser. */
public class DeviceLoginFlow(
    private val output: LoginOutput,
    browser: BrowserOpener = SystemBrowserOpener(output),
) {

    private val authClients = AuthHttpClientFactory()
    private val loginIo = LoginIo(output, browser)

    private enum class Outcome { SUCCESS, ABORT, EXPIRED }

    /** One poll's verdict: stop with an outcome, or keep polling at the (possibly bumped) interval. */
    private sealed class PollStep {
        data class Stop(val outcome: Outcome) : PollStep()
        data class Wait(val intervalS: Long) : PollStep()
    }

    /** Runs the device flow to completion; returns true on success.
     *
     *  HD-19: [waiter] is the RFC 8628 poll interval, threaded down to [poll] rather than reached
     *  for as a bare `delay`; the default is the production behaviour, and LoginCommand passes
     *  nothing.
     *
     *  [observer], V4-132: the console's login-id/poll seam (POST/GET /api/auth/{head}/login[/{id}]),
     *  null for the CLI. Present means this call is being WATCHED off-request, so [announce] skips
     *  its code banner and browser open and reports through the observer instead — a daemon process
     *  has no terminal to print to and no operator desktop to open a browser on. */
    public suspend fun run(
        spec: DeviceLoginSpec,
        waiter: Waiter = ProcessWaiter(),
        observer: LoginObserver? = null,
    ): Boolean = try {
        runAttempts(spec, waiter, loginIo, observer)
    } finally {
        spec.account?.releaseReservation()
    }

    private suspend fun runAttempts(
        spec: DeviceLoginSpec,
        waiter: Waiter,
        loginIo: LoginIo,
        observer: LoginObserver?,
    ): Boolean {
        var restarts = 0
        while (true) {
            when (attempt(spec, waiter, loginIo, observer)) {
                Outcome.SUCCESS -> return true
                Outcome.ABORT -> return false
                Outcome.EXPIRED -> {
                    if (restarts++ >= MAX_EXPIRED_RESTARTS) {
                        output.line("splice: login for '${spec.head}' expired too many times — try again.")
                        return false
                    }
                    output.line("splice: the code expired — requesting a fresh one…")
                }
            }
        }
    }

    private suspend fun attempt(
        spec: DeviceLoginSpec,
        waiter: Waiter,
        loginIo: LoginIo,
        observer: LoginObserver?,
    ): Outcome {
        val client = authClients.create()
        return try {
            Cancellables.runCatchingBestEffort {
                val auth = requestDeviceAuth(client, spec, loginIo) ?: return@runCatchingBestEffort Outcome.ABORT
                announce(spec, auth, loginIo, observer)
                poll(client, spec, auth, waiter, loginIo)
            }.getOrElse { e ->
                output.line("splice: login error: ${SafeFailureText.render(e)}")
                Outcome.ABORT
            }
        } finally {
            client.close()
        }
    }

    private suspend fun requestDeviceAuth(
        client: HttpClient,
        spec: DeviceLoginSpec,
        loginIo: LoginIo,
    ): DeviceAuthorization? {
        val resp = client.post(spec.deviceAuthUrl) {
            loginIo.formHeaders(this, spec.identityHeaders)
            setBody(spec.deviceAuthForm(spec.clientId))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            output.line("splice: could not start device login (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
            return null
        }
        return spec.parseDeviceAuth(body)
    }

    private fun announce(spec: DeviceLoginSpec, auth: DeviceAuthorization, loginIo: LoginIo, observer: LoginObserver?) {
        val url = auth.verificationUriComplete.ifEmpty { auth.verificationUri }
        if (observer != null) {
            // Off-request (the console started this): report the code/link through the poll seam
            // instead of a terminal nobody reads and a browser on the DAEMON's own desktop.
            observer.announced(LoginAnnouncement(userCode = auth.userCode, verificationUri = url))
            return
        }
        output.line("")
        output.line("  splice: sign in to ${spec.head} — enter this code in your browser:")
        output.line("")
        output.line("      ${auth.userCode}")
        output.line("")
        output.line("  $url")
        output.line("")
        if (!loginIo.openBrowser(url)) output.line("splice: open the URL above to finish signing in.")
    }

    private suspend fun poll(
        client: HttpClient,
        spec: DeviceLoginSpec,
        auth: DeviceAuthorization,
        waiter: Waiter,
        loginIo: LoginIo,
    ): Outcome {
        var intervalS = auth.intervalS
        val deadline = CredentialExpiry.expiryFromNowMs(System.currentTimeMillis(), auth.expiresInS)
        while (System.currentTimeMillis() < deadline) {
            waiter.wait(intervalS.coerceIn(0L, MAX_POLL_INTERVAL_S) * MS_PER_S)
            val resp = Cancellables.runCatchingBestEffort {
                postToken(client, spec, auth.deviceCode, loginIo)
            }.onFailure {
                output.line("splice: login poll did not reach the token endpoint — ${SafeFailureText.render(it)}")
            }.getOrNull()
            val step = if (resp == null) PollStep.Wait(intervalS) else classifyPoll(resp, spec, intervalS, loginIo)
            when (step) {
                is PollStep.Stop -> return step.outcome
                is PollStep.Wait -> intervalS = step.intervalS
            }
        }
        return Outcome.EXPIRED
    }

    // authorization_pending keeps the interval; slow_down bumps it permanently; the rest are terminal.
    private suspend fun classifyPoll(
        resp: HttpResponse,
        spec: DeviceLoginSpec,
        intervalS: Long,
        loginIo: LoginIo,
    ): PollStep {
        val body = resp.bodyAsText()
        if (resp.status.isSuccess()) return persistPollSuccess(spec, body, loginIo)
        if (resp.status.value >= HttpStatus.INTERNAL_SERVER_ERROR) {
            output.line("splice: login failed (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
            return PollStep.Stop(Outcome.ABORT)
        }
        return classifyPollError(body, intervalS, loginIo)
    }

    private suspend fun persistPollSuccess(spec: DeviceLoginSpec, body: String, loginIo: LoginIo): PollStep {
        // DR-172: the identical shape OAuthLoginFlow carried — a 200 was the whole test, so a
        // body with no access token ended the poll as a SUCCESS over an empty credential.
        val signedIn = loginIo.persistIfSignedIn(spec.authPath, spec.toAuthJson(body), spec.account)
        if (signedIn) runAfterPersist(spec)
        return PollStep.Stop(if (signedIn) Outcome.SUCCESS else Outcome.ABORT)
    }

    private fun classifyPollError(body: String, intervalS: Long, loginIo: LoginIo): PollStep =
        when (loginIo.errorCode(body)) {
            "authorization_pending" -> PollStep.Wait(intervalS)
            "slow_down" -> PollStep.Wait(intervalS + SLOW_DOWN_INCREMENT_S)
            "expired_token" -> PollStep.Stop(Outcome.EXPIRED)
            "access_denied" -> {
                output.line("splice: login was declined.")
                PollStep.Stop(Outcome.ABORT)
            }
            else -> {
                output.line("splice: login failed: ${loginIo.sanitize(body)}")
                PollStep.Stop(Outcome.ABORT)
            }
        }

    // One dispatch: a failed finalizer prints and leaves the just-written credential in place.
    private suspend fun runAfterPersist(spec: DeviceLoginSpec) {
        Cancellables.runCatchingBestEffort { spec.afterPersist(spec.authPath, spec.account) }.onFailure { e ->
            output.line("splice: post-login step failed: ${SafeFailureText.render(e)}")
        }
    }

    private suspend fun postToken(
        client: HttpClient,
        spec: DeviceLoginSpec,
        deviceCode: String,
        loginIo: LoginIo,
    ): HttpResponse =
        client.post(spec.tokenUrl) {
            loginIo.formHeaders(this, spec.identityHeaders)
            setBody(spec.tokenPollForm(deviceCode, spec.clientId))
        }
}
