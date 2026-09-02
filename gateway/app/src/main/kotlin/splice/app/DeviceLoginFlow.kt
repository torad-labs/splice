// NEW: RFC 8628 device-authorization login (kimi / Moonshot) — the no-loopback counterpart to
// OAuthLoginFlow. POST device_authorization → print the user_code + verification URL, open the
// browser → poll the token endpoint until the user approves. State machine per the verified kimi
// contract: authorization_pending keeps polling; slow_down bumps the interval PERMANENTLY (+5s);
// expired_token restarts the WHOLE flow (bounded to 2 restarts); access_denied / >=500 abort; the
// device_authorization expires_in is the overall deadline. Credentials persist through the shared
// atomic-0600 writeCredentialFile. :app is wall-exempt for println + a bounded runBlocking bridge.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import splice.core.auth.CredentialExpiry
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.kimi.KimiDeviceAuthorization
import splice.provider.kimi.KimiOAuth
import splice.spi.ProcessWaiter
import splice.spi.Waiter

// DeviceLoginSpec lives in DeviceLoginSpec.kt (concentration, 2026-08-19).

public object DeviceLoginFlow {

    private val loginIo = LoginIo()
    private val kimiOAuth = KimiOAuth()
    private val authClients = AuthHttpClientFactory()

    private const val MAX_EXPIRED_RESTARTS = 2
    private const val SLOW_DOWN_INCREMENT_S = 5L
    private const val MS_PER_S = 1000L

    // DR-190 (DR-177's unenumerated fifth site): expires_in and interval come off the wire. A value that
    // does not fit in milliseconds wrapped `now + expiresInS * MS_PER_S` negative — EXPIRED before the
    // first poll — and `intervalS * MS_PER_S` negative. The deadline degrades the way DR-177's
    // CredentialExpiry does (unrepresentable → the synthetic 4h ceiling, never an instant expiry) and
    // the interval is capped in seconds before it is multiplied; both are no-ops for RFC 8628 values.
    private const val MAX_POLL_INTERVAL_S = 3600L
    private const val HTTP_SERVER_ERROR_FLOOR = 500

    private enum class Outcome { SUCCESS, ABORT, EXPIRED }

    /** One poll's verdict: stop with an outcome, or keep polling at the (possibly bumped) interval. */
    private sealed class PollStep {
        data class Stop(val outcome: Outcome) : PollStep()
        data class Wait(val intervalS: Long) : PollStep()
    }

    /** Runs the device flow to completion; returns true on success.
     *
     *  HD-19: [waiter] is the RFC 8628 poll interval, threaded down to [poll] rather than reached
     *  for as a bare `delay`. This is an `object`, so the seam rides the call instead of a
     *  constructor; the default is the production behaviour, and LoginCommand passes nothing. */
    public suspend fun run(spec: DeviceLoginSpec, waiter: Waiter = ProcessWaiter()): Boolean {
        var restarts = 0
        while (true) {
            when (attempt(spec, waiter)) {
                Outcome.SUCCESS -> return true
                Outcome.ABORT -> return false
                Outcome.EXPIRED -> {
                    if (restarts++ >= MAX_EXPIRED_RESTARTS) {
                        println("splice: login for '${spec.head}' expired too many times — try again.")
                        return false
                    }
                    println("splice: the code expired — requesting a fresh one…")
                }
            }
        }
    }

    private suspend fun attempt(spec: DeviceLoginSpec, waiter: Waiter): Outcome {
        val client = authClients.create()
        return try {
            Cancellables.runCatchingCancellable {
                val auth = requestDeviceAuth(client, spec) ?: return@runCatchingCancellable Outcome.ABORT
                announce(spec, auth)
                poll(client, spec, auth, waiter)
            }.getOrElse { e ->
                println("splice: login error: ${SafeFailureText.render(e)}")
                Outcome.ABORT
            }
        } finally {
            client.close()
        }
    }

    private suspend fun requestDeviceAuth(client: HttpClient, spec: DeviceLoginSpec): KimiDeviceAuthorization? {
        val resp = client.post(spec.deviceAuthUrl) {
            loginIo.formHeaders(this, spec.identityHeaders)
            setBody(kimiOAuth.kimiDeviceAuthorizationForm(spec.clientId))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            println("splice: could not start device login (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
            return null
        }
        return kimiOAuth.parseKimiDeviceAuthorization(body)
    }

    private fun announce(spec: DeviceLoginSpec, auth: KimiDeviceAuthorization) {
        val url = auth.verificationUriComplete.ifEmpty { auth.verificationUri }
        println("")
        println("  splice: sign in to ${spec.head} — enter this code in your browser:")
        println("")
        println("      ${auth.userCode}")
        println("")
        println("  $url")
        println("")
        if (!loginIo.openBrowser(url)) println("splice: open the URL above to finish signing in.")
    }

    private suspend fun poll(
        client: HttpClient,
        spec: DeviceLoginSpec,
        auth: KimiDeviceAuthorization,
        waiter: Waiter,
    ): Outcome {
        var intervalS = auth.intervalS
        val deadline = CredentialExpiry.expiryFromNowMs(System.currentTimeMillis(), auth.expiresInS)
        while (System.currentTimeMillis() < deadline) {
            waiter.wait(intervalS.coerceIn(0L, MAX_POLL_INTERVAL_S) * MS_PER_S)
            val resp = Cancellables.runCatchingCancellable { postToken(client, spec, auth.deviceCode) }.getOrNull()
            val step = if (resp == null) PollStep.Wait(intervalS) else classifyPoll(resp, spec, intervalS)
            when (step) {
                is PollStep.Stop -> return step.outcome
                is PollStep.Wait -> intervalS = step.intervalS
            }
        }
        return Outcome.EXPIRED
    }

    // authorization_pending keeps the interval; slow_down bumps it permanently; the rest are terminal.
    private suspend fun classifyPoll(resp: HttpResponse, spec: DeviceLoginSpec, intervalS: Long): PollStep {
        val body = resp.bodyAsText()
        if (resp.status.isSuccess()) {
            // DR-172: the identical shape OAuthLoginFlow carried — a 200 was the whole test, so a
            // body with no access token ended the poll as a SUCCESS over an empty credential.
            val signedIn = loginIo.persistIfSignedIn(spec.authPath, spec.toAuthJson(body))
            return PollStep.Stop(if (signedIn) Outcome.SUCCESS else Outcome.ABORT)
        }
        if (resp.status.value >= HTTP_SERVER_ERROR_FLOOR) {
            println("splice: login failed (HTTP ${resp.status.value}): ${loginIo.sanitize(body)}")
            return PollStep.Stop(Outcome.ABORT)
        }
        return when (loginIo.errorCode(body)) {
            "authorization_pending" -> PollStep.Wait(intervalS)
            "slow_down" -> PollStep.Wait(intervalS + SLOW_DOWN_INCREMENT_S)
            "expired_token" -> PollStep.Stop(Outcome.EXPIRED)
            "access_denied" -> {
                println("splice: login was declined.")
                PollStep.Stop(Outcome.ABORT)
            }
            else -> {
                println("splice: login failed: ${loginIo.sanitize(body)}")
                PollStep.Stop(Outcome.ABORT)
            }
        }
    }

    private suspend fun postToken(client: HttpClient, spec: DeviceLoginSpec, deviceCode: String): HttpResponse =
        client.post(spec.tokenUrl) {
            loginIo.formHeaders(this, spec.identityHeaders)
            setBody(kimiOAuth.kimiTokenPollForm(deviceCode, spec.clientId))
        }
}
