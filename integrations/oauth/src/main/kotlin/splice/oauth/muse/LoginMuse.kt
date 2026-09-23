// NEW: Meta Muse RFC-8628 device-login spec. Mirror of LoginKimi.spec: same reservation,
// same DeviceLoginSpec shape, muse endpoints and a login-end mint so the first turn does
// not wait on POST /muse-code/key. Identity headers stay empty — LoginIo already sends
// Accept application/json, and live capture forbids x-api-version on every hop.
package splice.oauth.muse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.oauth.AUTO
import splice.oauth.AuthJsonFromResponse
import splice.oauth.DeviceAuthForm
import splice.oauth.DeviceAuthParse
import splice.oauth.DeviceAuthorization
import splice.oauth.DeviceLoginFinalizer
import splice.oauth.DeviceLoginSpec
import splice.oauth.OAuthAccountFiles
import splice.oauth.OAuthLoginAccount
import splice.oauth.OAuthLoginReservation
import splice.oauth.TokenPollForm
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseMintPersistence
import splice.provider.muse.MuseOAuth
import splice.provider.muse.MuseOAuthEndpoints
import java.nio.file.Files
import java.nio.file.Path

/** [output] carries the login-end mint's operator lines (LAYOUT-01: an integration never prints). */
public class LoginMuse(
    private val output: TerminalOutput,
    private val mint: MuseKeyMintCall = MuseRefresh(),
) {

    private val oauth = MuseOAuth()
    private val json = Json { ignoreUnknownKeys = true }
    private val accountFiles = OAuthAccountFiles()
    private val loginReservations = OAuthLoginReservation()
    private val authJson = AuthJsonFromResponse { body -> museAuthJson(body) }
    private val mintPersistence = MuseMintPersistence()

    public fun spec(head: String, authPath: Path, label: String? = null): DeviceLoginSpec {
        val planned = accountFiles.loginAccount(AuthKind.MuseOAuth, authPath, label)
        val poolDir = accountFiles.poolDir(AuthKind.MuseOAuth, authPath)
        val reservation = when {
            planned.primary -> null
            label == AUTO -> loginReservations.reserveOrdinal(AuthKind.MuseOAuth, poolDir)
            else -> loginReservations.reserveLabel(poolDir, requireNotNull(planned.label))
        }
        val account = if (label == AUTO) {
            planned.copy(label = requireNotNull(reservation).label, defaultLabel = null, tokenDerivedLabel = false)
        } else {
            planned
        }
        var handedOff = false
        try {
            reservation?.let(account::holdReservation)
            val spec = DeviceLoginSpec(
                head = head,
                clientId = MuseOAuthEndpoints.CLIENT_ID,
                deviceAuthUrl = MuseOAuthEndpoints.DEVICE_AUTHORIZATION_URL,
                tokenUrl = MuseOAuthEndpoints.TOKEN_URL,
                authPath = authPath,
                identityHeaders = emptyMap(),
                toAuthJson = authJson,
                deviceAuthForm = DeviceAuthForm { oauth.museDeviceAuthorizationForm(it) },
                parseDeviceAuth = DeviceAuthParse(::deviceAuth),
                tokenPollForm = TokenPollForm { code, id -> oauth.museTokenPollForm(code, id) },
                account = account,
                afterPersist = DeviceLoginFinalizer { path, acct -> mintAfterLogin(path, acct) },
            )
            handedOff = true
            return spec
        } finally {
            if (!handedOff) {
                Cancellables.discard(
                    Cancellables.runCatchingCleanup { reservation?.close() },
                    "a Muse ordinal reservation is released when login spec construction fails",
                )
            }
        }
    }

    private fun deviceAuth(body: String): DeviceAuthorization {
        val parsed = oauth.parseMuseDeviceAuthorization(body)
        return DeviceAuthorization(
            userCode = parsed.userCode,
            deviceCode = parsed.deviceCode,
            verificationUri = parsed.verificationUri,
            verificationUriComplete = parsed.verificationUriComplete,
            expiresInS = parsed.expiresInS,
            intervalS = parsed.intervalS,
        )
    }

    private fun museAuthJson(responseBody: String): String {
        val token = oauth.parseMuseAccessToken(responseBody) ?: return "{}"
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): optional extra fields only — the access token itself was already read by parseMuseAccessToken above, and the null path writes the token alone.
        val obj = Cancellables.runCatchingCancellable {
            json.parseToJsonElement(responseBody) as? JsonObject
        }.getOrNull()
        return buildJsonObject {
            put("access_token", JsonPrimitive(token))
            keptString(obj, "token_type")?.let { put("token_type", JsonPrimitive(it)) }
            keptString(obj, "schema")?.let { put("schema", JsonPrimitive(it)) }
            keptString(obj, "schema_version")?.let { put("schema_version", JsonPrimitive(it)) }
        }.toString()
    }

    private fun keptString(obj: JsonObject?, field: String): String? =
        obj?.let { JsonScalars.strIfString(it[field]) }?.takeIf(String::isNotBlank)

    private suspend fun mintAfterLogin(authPath: Path, account: OAuthLoginAccount?) {
        val target = writtenPath(authPath, account)
        val current = readObject(target)
        if (current == null) {
            output.line("splice: muse key mint skipped: credential missing after login")
            return
        }
        val access = JsonScalars.strIfString(current["access_token"]).takeIf(String::isNotBlank)
        if (access == null) {
            output.line("splice: muse key mint skipped: account token missing")
            return
        }
        val attempt = mintAttempt(access) ?: return
        applyAttempt(target, access, attempt)
    }

    private suspend fun mintAttempt(access: String): MuseMintAttempt? {
        // runCatchingBestEffort reports EVERY non-cancellation, non-Error throwable and leaves the
        // already-persisted credential standing (RETRY DEFAULT IS TOTAL in miniature): the mint is
        // best-effort after persist, so an IllegalStateException from provider code must not abort
        // the login the way the V4-112 narrowing to runCatchingCancellable did.
        val outcome = Cancellables.runCatchingBestEffort { mint(access, MuseMintMode.ONBOARD) }
        val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
        output.line("splice: muse key mint failed: ${SafeFailureText.render(failure)}")
        return null
    }

    private fun applyAttempt(target: Path, access: String, attempt: MuseMintAttempt) {
        when (attempt) {
            is MuseMintAttempt.Granted -> persistMinted(target, access, attempt)
            is MuseMintAttempt.InvalidAccountToken ->
                output.line("splice: muse key mint failed: account token rejected")
            is MuseMintAttempt.SubscriptionRequired -> {
                val action = attempt.actionUrl?.let { " — $it" }.orEmpty()
                output.line("splice: muse subscription inactive$action")
            }
            is MuseMintAttempt.RateLimited ->
                output.line("splice: muse key mint rate limited — first turn will retry")
            is MuseMintAttempt.Denied ->
                output.line("splice: muse key mint failed: ${attempt.detail}")
        }
    }

    private fun persistMinted(target: Path, access: String, attempt: MuseMintAttempt.Granted) {
        val stored = mintPersistence.persistGranted(target, access, attempt.key, LogSink(output::line))
        if (!stored) output.line("splice: muse key mint succeeded but the key could not be stored")
    }

    private fun writtenPath(authPath: Path, account: OAuthLoginAccount?): Path {
        if (account == null || account.primary) return authPath
        val label = account.persistedLabel()?.takeIf(String::isNotBlank) ?: return authPath
        return accountFiles.poolDir(account.kind, authPath).resolve("$label.json")
    }

    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): an absent or unparsable account file is the normal pre-login state; every caller treats the null as 'no stored account' and says so.
    private fun readObject(path: Path): JsonObject? = Cancellables.runCatchingCancellable {
        json.parseToJsonElement(Files.readString(path)) as? JsonObject
    }.getOrNull()
}
