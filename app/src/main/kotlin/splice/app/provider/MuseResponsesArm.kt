// Muse's Responses arm keeps the minted-key account pool while using Meta's reasoning wire.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.core.GATEWAY_VERSION
import splice.core.auth.Credentials
import splice.core.config.StatePaths
import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.tools.MuseToolNameCodec
import splice.oauth.muse.MuseRefresh
import splice.provider.muse.MuseKeyMintCall
import splice.upstream.CredentialHeaders
import splice.upstream.ProviderTuning

private const val MUSE_TOOL_NAME_CAP = 64
private val MUSE_BASE_HEADERS = mapOf("User-Agent" to "splice/$GATEWAY_VERSION")
private val SSE_HEADERS = mapOf("Accept" to "text/event-stream")

internal class MuseResponsesArm(
    private val statePaths: StatePaths,
    private val log: LogSink,
    probeScope: CoroutineScope,
    mintCall: MuseKeyMintCall = MuseRefresh(),
) {
    private val museOAuth = MuseOAuth(probeScope, log, mintCall)
    private val quirksOverlay = QuirksOverlay()

    internal fun museOauthProvider(ctx: ProviderBuild, label: String): Wired {
        val accounts = museOAuth.museOauthAccounts(ctx)
        val default = museOAuth.providerAccount(accounts)
        val quirks = museQuirks(ctx)
        val headers = SSE_HEADERS + MUSE_BASE_HEADERS + ctx.providerCfg.staticHeaders
        val provider = MuseResponsesProvider(
            tuning = ProviderTuning(
                key = ctx.key,
                label = label,
                catalog = ctx.catalog,
                pinnedModel = ctx.head.pinnedModel,
                auth = default.auth,
                baseUrl = ctx.providerCfg.baseUrl,
                watchdog = ctx.watchdog,
                loginCommand = ctx.loginCommand,
                stateDir = statePaths.headsDir.resolve(ctx.key),
            ),
            showReasoning = ctx.cfg.showReasoning,
            replayReasoning = ctx.cfg.replayReasoning,
            configEffort = ctx.cfg.effort,
            configSummary = ctx.cfg.summary,
            quirks = quirks,
            headers = headers,
        )
        return Wired(provider, default.auth, wiredAccounts(accounts, headers))
    }

    private fun museQuirks(ctx: ProviderBuild): ResponsesQuirks = quirksOverlay.responsesQuirks(
        ctx.providerCfg,
        ResponsesQuirks(
            providerTag = "muse",
            store = false,
            cacheKeyStrategy = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH,
            supportsSummary = true,
            emitToolChoice = true,
            promptCacheRetention = "24h",
            toolNameCodec = MuseToolNameCodec(ctx.providerCfg.quirks.toolNameCap ?: MUSE_TOOL_NAME_CAP, log),
        ),
        ctx.cfg,
    ).let { overlaid ->
        // QuirksConfig's non-null default would replace Muse's session key with first-message hash.
        if (ctx.providerCfg.quirks.cacheKey == "off") {
            overlaid
        } else {
            overlaid.copy(cacheKeyStrategy = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH)
        }
    }

    private fun wiredAccounts(accounts: List<MuseOAuthAccount>, headers: Map<String, String>): List<WiredAccount> =
        accounts.map { account ->
            WiredAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quotaFile = account.quotaFile,
                credentialPresent = account.credentialPresent,
                extraHeaders = CredentialHeaders { headers },
            )
        }
}

private class MuseResponsesProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String?,
    quirks: ResponsesQuirks,
    private val headers: Map<String, String>,
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = headers
}
