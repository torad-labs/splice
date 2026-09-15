// NEW: Meta Muse RFC-8628 device-login spec. Mirror of LoginKimi.spec: same reservation,
// same DeviceLoginSpec shape, muse endpoints and a login-end mint so the first turn does
// not wait on POST /muse-code/key. Identity headers stay empty — LoginIo already sends
// Accept application/json, and live capture forbids x-api-version on every hop.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.app.AuthJsonFromResponse
import splice.app.DeviceAuthForm
import splice.app.DeviceAuthParse
import splice.app.DeviceAuthorization
import splice.app.DeviceLoginFinalizer
import splice.app.DeviceLoginSpec
import splice.app.MuseRefresh
import splice.app.TokenPollForm
import splice.app.auth.AUTO
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthLoginAccount
import splice.app.auth.OAuthLoginReservation
import splice.core.auth.CredentialJson
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseOAuth
import splice.provider.muse.MuseOAuthEndpoints
import java.nio.file.Files
import java.nio.file.Path

internal class LoginMuse(private val mint: MuseKeyMintCall = MuseRefresh()) {

    private val oauth = MuseOAuth()
    private val json = Json { ignoreUnknownKeys = true }
    private val accountFiles = OAuthAccountFiles()
    private val loginReservations = OAuthLoginReservation()
    private val authJson = AuthJsonFromResponse { body -> museAuthJson(body) }

    internal fun spec(head: String, authPath: Path, label: String? = null): DeviceLoginSpec {
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
        val current = readObject(target) ?: return
        val access = JsonScalars.strIfString(current["access_token"]).takeIf(String::isNotBlank) ?: return
        when (val attempt = mint(access, MuseMintMode.ONBOARD)) {
            is MuseMintAttempt.Granted -> persistMinted(target, current, access, attempt)
            is MuseMintAttempt.InvalidAccountToken ->
                println("splice: muse key mint failed: account token rejected")
            is MuseMintAttempt.SubscriptionRequired -> {
                val action = attempt.actionUrl?.let { " — $it" }.orEmpty()
                println("splice: muse subscription inactive$action")
            }
            is MuseMintAttempt.RateLimited ->
                println("splice: muse key mint rate limited — first turn will retry")
            is MuseMintAttempt.Denied ->
                println("splice: muse key mint failed: ${attempt.detail}")
        }
    }

    private fun persistMinted(
        target: Path,
        current: JsonObject,
        access: String,
        attempt: MuseMintAttempt.Granted,
    ) {
        val replacements = buildJsonObject {
            attempt.key.fields.forEach { (name, value) ->
                if (name != "splice_auth_kind" && name != "splice_account_label") put(name, value)
            }
            put("api_key", JsonPrimitive(attempt.key.apiKey))
            put("access_token", JsonPrimitive(access))
        }
        val merged = CredentialJson.mergedCredentialJson(current, replacements)
        Cancellables.runCatchingCancellable {
            SecureFile.writeAtomic0600(target, merged.toString())
        }.onFailure {
            println("splice: muse key mint succeeded but the key could not be stored")
        }
    }

    private fun writtenPath(authPath: Path, account: OAuthLoginAccount?): Path {
        if (account == null || account.primary) return authPath
        val label = account.persistedLabel()?.takeIf(String::isNotBlank) ?: return authPath
        return accountFiles.poolDir(account.kind, authPath).resolve("$label.json")
    }

    private fun readObject(path: Path): JsonObject? = Cancellables.runCatchingCancellable {
        json.parseToJsonElement(Files.readString(path)) as? JsonObject
    }.getOrNull()
}
