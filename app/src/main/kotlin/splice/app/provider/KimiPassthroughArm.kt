// NEW: Kimi owns its passthrough headers, Moonshot quirks base, and device-identity wiring.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.dialect.anthropic.IdentityHeaders
import splice.oauth.kimi.KimiRefresh
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiQuirks
import splice.provider.openai.ApiKeyAuthProvider
import splice.topology.TopologyLoader
import splice.upstream.CredentialHeaders
import java.nio.file.Paths

/** Kimi /coding 403s an unrecognized UA; the Anthropic wire needs its version on every request. */
private val KIMI_BASE_HEADERS = mapOf(
    "anthropic-version" to "2023-06-01",
    "User-Agent" to "KimiCLI/1.5",
)
private val SSE_HEADERS = mapOf("Accept" to "text/event-stream")

internal class KimiPassthroughArm(
    private val statePaths: StatePaths,
    probeScope: CoroutineScope,
    log: LogSink,
    kimiRefresh: KimiRefresh = KimiRefresh(log),
) {
    private val passthroughAssembly = PassthroughAssembly()
    private val kimiOAuth = KimiOAuth(probeScope, log, kimiRefresh)

    internal fun kimiOauthProvider(ctx: ProviderBuild, label: String): Wired {
        val accounts = kimiOAuth.kimiOauthAccounts(ctx)
        val default = kimiOAuth.providerAccount(accounts)
        val provider = passthroughAssembly.passthroughProviderFor(
            ctx = ctx,
            label = label,
            auth = default.auth,
            base = KimiQuirks().kimi(ctx.key),
            baseHeaders = KIMI_BASE_HEADERS,
            identityHeaders = IdentityHeaders(default.identity::headers),
        )
        val staticHeaders = KIMI_BASE_HEADERS + ctx.providerCfg.staticHeaders
        val wiredAccounts = accounts.map { account ->
            WiredAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quotaFile = account.quotaFile,
                credentialPresent = account.credentialPresent,
                extraHeaders = CredentialHeaders {
                    SSE_HEADERS + staticHeaders + account.identity.headers()
                },
            )
        }
        return Wired(provider, default.auth, wiredAccounts)
    }

    internal fun kimiApiKeyProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val auth = ApiKeyAuthProvider(
            envVar = providerCfg.auth.effectiveApiKeyEnv(key),
            keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
        )
        val identity = KimiDeviceIdentity(deviceIdPath = statePaths.stateDir.resolve("$key-device_id"))
        return Wired(
            passthroughAssembly.passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = KimiQuirks().kimi(key),
                baseHeaders = KIMI_BASE_HEADERS,
                identityHeaders = IdentityHeaders(identity::headers),
            ),
            auth,
        )
    }
}
