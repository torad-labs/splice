// NEW: RFC 8628 device-authorization login (kimi / Moonshot) — the no-loopback counterpart to
// OAuthLoginFlow. POST device_authorization → print the user_code + verification URL, open the
// browser → poll the token endpoint until the user approves. State machine per the verified kimi
// contract: authorization_pending keeps polling; slow_down bumps the interval PERMANENTLY (+5s);
// expired_token restarts the WHOLE flow (bounded to 2 restarts); access_denied / >=500 abort; the
// device_authorization expires_in is the overall deadline. Credentials persist through the shared
// atomic-0600 writeCredentialFile. :app is wall-exempt for println + a bounded runBlocking bridge.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.runCatchingCancellable
import splice.provider.kimi.KimiDeviceAuthorization
import splice.provider.kimi.kimiDeviceAuthorizationForm
import splice.provider.kimi.kimiTokenPollForm
import splice.provider.kimi.parseKimiDeviceAuthorization
import java.nio.file.Path

/** Everything the device flow needs for one provider's login (built by LoginCommand per head). */
public data class DeviceLoginSpec(
    val head: String,
    val clientId: String,
    val deviceAuthUrl: String,
    val tokenUrl: String,
    val authPath: Path,
    /** X-Msh-* device identity headers sent on both OAuth calls. */
    val identityHeaders: Map<String, String>,
    /** token-endpoint success body → the auth.json content to persist. */
    val toAuthJson: (responseBody: String) -> String,
)

public object DeviceLoginFlow {

    private const val MAX_EXPIRED_RESTARTS = 2
    private const val SLOW_DOWN_INCREMENT_S = 5L
    private const val MS_PER_S = 1000L
    private const val ERR_BODY_CAP = 300
    private const val HTTP_SERVER_ERROR_FLOOR = 500

    private enum class Outcome { SUCCESS, ABORT, EXPIRED }

    /** One poll's verdict: stop with an outcome, or keep polling at the (possibly bumped) interval. */
    private sealed class PollStep {
        data class Stop(val outcome: Outcome) : PollStep()
        data class Wait(val intervalS: Long) : PollStep()
    }

    /** Runs the device flow to completion; returns true on success. */
    public suspend fun run(spec: DeviceLoginSpec): Boolean {
        var restarts = 0
        while (true) {
            when (attempt(spec)) {
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

    private suspend fun attempt(spec: DeviceLoginSpec): Outcome {
        val client = authHttpClient()
        return try {
            runCatchingCancellable {
                val auth = requestDeviceAuth(client, spec) ?: return@runCatchingCancellable Outcome.ABORT
                announce(spec, auth)
                poll(client, spec, auth)
            }.getOrElse { e ->
                println("splice: login error: ${e.message}")
                Outcome.ABORT
            }
        } finally {
            client.close()
        }
    }

    private suspend fun requestDeviceAuth(client: HttpClient, spec: DeviceLoginSpec): KimiDeviceAuthorization? {
        val resp = client.post(spec.deviceAuthUrl) {
            formHeaders(spec.identityHeaders)
            setBody(kimiDeviceAuthorizationForm(spec.clientId))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            println("splice: could not start device login (HTTP ${resp.status.value}): ${sanitize(body)}")
            return null
        }
        return parseKimiDeviceAuthorization(body)
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
        if (!openBrowser(url)) println("splice: open the URL above to finish signing in.")
    }

    private suspend fun poll(client: HttpClient, spec: DeviceLoginSpec, auth: KimiDeviceAuthorization): Outcome {
        var intervalS = auth.intervalS
        val deadline = System.currentTimeMillis() + auth.expiresInS * MS_PER_S
        while (System.currentTimeMillis() < deadline) {
            delay(intervalS * MS_PER_S)
            val resp = runCatchingCancellable { postToken(client, spec, auth.deviceCode) }.getOrNull()
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
            writeCredentialFile(spec.authPath, spec.toAuthJson(body))
            println("splice: signed in — credentials written to ${spec.authPath}")
            return PollStep.Stop(Outcome.SUCCESS)
        }
        if (resp.status.value >= HTTP_SERVER_ERROR_FLOOR) {
            println("splice: login failed (HTTP ${resp.status.value}): ${sanitize(body)}")
            return PollStep.Stop(Outcome.ABORT)
        }
        return when (errorCode(body)) {
            "authorization_pending" -> PollStep.Wait(intervalS)
            "slow_down" -> PollStep.Wait(intervalS + SLOW_DOWN_INCREMENT_S)
            "expired_token" -> PollStep.Stop(Outcome.EXPIRED)
            "access_denied" -> {
                println("splice: login was declined.")
                PollStep.Stop(Outcome.ABORT)
            }
            else -> {
                println("splice: login failed: ${sanitize(body)}")
                PollStep.Stop(Outcome.ABORT)
            }
        }
    }

    private suspend fun postToken(client: HttpClient, spec: DeviceLoginSpec, deviceCode: String): HttpResponse =
        client.post(spec.tokenUrl) {
            formHeaders(spec.identityHeaders)
            setBody(kimiTokenPollForm(deviceCode, spec.clientId))
        }

    private fun HttpRequestBuilder.formHeaders(identityHeaders: Map<String, String>) {
        header("Content-Type", "application/x-www-form-urlencoded")
        header("Accept", "application/json")
        identityHeaders.forEach { (k, v) -> header(k, v) }
    }

    private fun errorCode(body: String): String = runCatchingCancellable {
        (deviceJson.parseToJsonElement(body) as? JsonObject)?.let { obj ->
            (obj["error"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        }
    }.getOrNull().orEmpty()

    private fun sanitize(s: String): String = s.filter { !it.isISOControl() }.take(ERR_BODY_CAP)

    private val deviceJson = Json { ignoreUnknownKeys = true }
}
