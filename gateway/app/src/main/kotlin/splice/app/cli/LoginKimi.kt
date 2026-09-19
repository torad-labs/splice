// NEW: Moonshot / Kimi RFC-8628 device-login spec. Split from
// LoginCommand.kt so that file is not billed for three vendor OAuth
// surfaces at once (concentration HIGH, 2026-08-19). Status table lives
// on StatusTable (V4-21); this file keeps only the device-login spec.
package splice.app.cli

import splice.app.DeviceAuthForm
import splice.app.DeviceAuthParse
import splice.app.DeviceAuthorization
import splice.app.DeviceLoginSpec
import splice.app.TokenPollForm
import splice.app.auth.AUTO
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthLoginAccount
import splice.app.auth.OAuthLoginReservation
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuth
import splice.provider.kimi.KimiOAuthEndpoints
import java.nio.file.Path

internal class LoginKimi {

    private val oauth = KimiOAuth()
    private val env: EnvReader = EnvReader(System::getenv)
    private val accountFiles = OAuthAccountFiles()
    private val loginReservations = OAuthLoginReservation()

    internal fun spec(head: String, authPath: Path, label: String? = null): DeviceLoginSpec {
        val planned = accountFiles.loginAccount(AuthKind.KimiOAuth, authPath, label)
        val poolDir = accountFiles.poolDir(AuthKind.KimiOAuth, authPath)
        val reservation = when {
            planned.primary -> null
            label == AUTO -> loginReservations.reserveOrdinal(AuthKind.KimiOAuth, poolDir)
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
            val identity = KimiDeviceIdentity(deviceIdPath = deviceIdPath(authPath, account))
            val spec = DeviceLoginSpec(
                head = head,
                clientId = KimiOAuthEndpoints.CLIENT_ID,
                deviceAuthUrl = KimiOAuthEndpoints.deviceAuthorizationUrl(env),
                tokenUrl = KimiOAuthEndpoints.tokenUrl(env),
                authPath = authPath,
                identityHeaders = identity.headers(),
                toAuthJson = { body ->
                    oauth.kimiAuthJsonFromTokenResponse(body, System.currentTimeMillis()).toString()
                },
                deviceAuthForm = DeviceAuthForm { oauth.kimiDeviceAuthorizationForm(it) },
                parseDeviceAuth = DeviceAuthParse(::deviceAuth),
                tokenPollForm = TokenPollForm { code, id -> oauth.kimiTokenPollForm(code, id) },
                account = account,
            )
            handedOff = true
            return spec
        } finally {
            if (!handedOff) {
                Cancellables.discard(
                    Cancellables.runCatchingCleanup { reservation?.close() },
                    "a Kimi ordinal reservation is released when login spec construction fails",
                )
            }
        }
    }

    private fun deviceAuth(body: String): DeviceAuthorization {
        val parsed = oauth.parseKimiDeviceAuthorization(body)
        return DeviceAuthorization(
            userCode = parsed.userCode,
            deviceCode = parsed.deviceCode,
            verificationUri = parsed.verificationUri,
            verificationUriComplete = parsed.verificationUriComplete,
            expiresInS = parsed.expiresInS,
            intervalS = parsed.intervalS,
        )
    }

    private fun deviceIdPath(authPath: Path, account: OAuthLoginAccount): Path = if (account.primary) {
        authPath.resolveSibling("device_id")
    } else {
        val label = requireNotNull(account.resolvedLabel())
        accountFiles.poolDir(account.kind, authPath).resolve("$label-device_id")
    }
}
