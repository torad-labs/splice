// NEW: Muse owns its passthrough headers, neutral quirks, and subscription account wiring.
// The shared passthrough arm remains unchanged; only minted keys authenticate Muse inference.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.MuseRefresh
import splice.core.GATEWAY_VERSION
import splice.core.util.LogSink
import splice.dialect.passthrough.PassthroughQuirks
import splice.provider.muse.MuseKeyMintCall
import splice.spi.CredentialHeaders

private const val MUSE_TOOL_NAME_CAP = 64
private val MUSE_BASE_HEADERS = mapOf("User-Agent" to "splice/$GATEWAY_VERSION")
private val SSE_HEADERS = mapOf("Accept" to "text/event-stream")

internal class MusePassthroughArm(
    log: LogSink,
    probeScope: CoroutineScope,
    mintCall: MuseKeyMintCall = MuseRefresh(),
) {
    private val passthroughAssembly = PassthroughAssembly()
    private val museOAuth = MuseOAuth(probeScope, log, mintCall)

    internal fun museOauthProvider(ctx: ProviderBuild, label: String): Wired {
        val accounts = museOAuth.museOauthAccounts(ctx)
        val default = museOAuth.providerAccount(accounts)
        val provider = passthroughAssembly.passthroughProviderFor(
            ctx = ctx,
            label = label,
            auth = default.auth,
            // V4-32: api.meta.ai rejects a tool name over 64 characters where Anthropic accepts
            // it, and Claude Code's MCP names run past 80. Muse is the only head that needs this.
            base = PassthroughQuirks(providerTag = ctx.key, toolNameCap = MUSE_TOOL_NAME_CAP),
            baseHeaders = MUSE_BASE_HEADERS,
        )
        val headers = SSE_HEADERS + MUSE_BASE_HEADERS + ctx.providerCfg.staticHeaders
        val wiredAccounts = accounts.map { account ->
            WiredAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quotaFile = account.quotaFile,
                credentialPresent = account.credentialPresent,
                extraHeaders = CredentialHeaders { headers },
            )
        }
        return Wired(provider, default.auth, wiredAccounts)
    }
}
