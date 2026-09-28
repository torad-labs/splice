// NEW: Muse's Responses arm keeps the minted-key account pool while using Meta's reasoning wire.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesQuirks
import splice.oauth.muse.MuseRefresh
import splice.provider.muse.MuseKeyMintCall
import splice.upstream.CredentialHeaders
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener

// why: Meta rejects tool names longer than 64 characters (measured on Muse, V4-32).
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
        val toolNames = ToolNameShortener(ctx.providerCfg.quirks.toolNameCap ?: MUSE_TOOL_NAME_CAP, log)
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
            options = MuseResponsesOptions(
                showReasoning = ctx.cfg.showReasoning,
                replayReasoning = ctx.cfg.replayReasoning,
                configEffort = ctx.cfg.effort,
                configSummary = ctx.cfg.summary,
                quirks = quirks,
                headers = headers,
                toolNames = toolNames,
            ),
        )
        return Wired(provider, default.auth, wiredAccounts(accounts, headers))
    }

    private fun museQuirks(ctx: ProviderBuild): ResponsesQuirks = quirksOverlay.responsesQuirks(
        ctx.providerCfg,
        ResponsesQuirks(
            providerTag = "muse",
            store = false,
            promptCache = PromptCachePolicy(CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH, "24h"),
            supportsSummary = true,
            emitToolChoice = true,
        ),
        ctx.cfg,
    ).let { overlaid ->
        // QuirksConfig's non-null default would replace Muse's session key with first-message hash.
        if (ctx.providerCfg.quirks.cacheKey == "off") {
            overlaid
        } else {
            overlaid.copy(promptCache = overlaid.promptCache.copy(key = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH))
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
