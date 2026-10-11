// NEW: Muse's Responses arm keeps the minted-key account pool while using Meta's reasoning wire.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.tools.ToolDeferralPolicy
import splice.dialect.responses.tools.ToolSearchMode
import splice.oauth.muse.MuseRefresh
import splice.provider.muse.MuseKeyMintCall
import splice.upstream.CredentialHeaders
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
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
                name = ProviderName(key = ctx.key, label = label),
                catalog = ctx.catalog,
                pinnedModel = ctx.head.pinnedModel,
                auth = default.auth,
                locations = ProviderLocations(
                    baseUrl = ctx.providerCfg.baseUrl,
                    stateDir = statePaths.headsDir.resolve(ctx.key),
                ),
                watchdog = ctx.faultPlan.watchdog,
                loginCommand = ctx.faultPlan.loginCommand,
            ),
            options = MuseResponsesOptions(
                reasoning = ReasoningWiring.settingsOf(ctx.cfg),
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
            lite = ResponsesLiteQuirks(),
            backend = ResponsesBackendQuirks(
                store = false,
                promptCache = PromptCachePolicy(CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH, "24h"),
            ),
            reasoning = ResponsesReasoningQuirks(
                supportsSummary = true,
            ),
            tools = ResponsesToolQuirks(
                emitToolChoice = true,
                toolSurface = ToolDeferralPolicy(mode = ToolSearchMode.HOSTED),
            ),
        ),
        ctx.cfg,
    ).let { overlaid ->
        // QuirksConfig's non-null default would replace Muse's session key with first-message hash.
        if (ctx.providerCfg.quirks.cacheKey == "off") {
            overlaid
        } else {
            overlaid.copy(
                backend = overlaid.backend.copy(
                    promptCache = overlaid.backend.promptCache.copy(
                        key = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH,
                    ),
                ),
            )
        }
    }

    private fun wiredAccounts(accounts: List<MuseOAuthAccount>, headers: Map<String, String>): List<WiredAccount> =
        accounts.map { account ->
            WiredAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quota = WiredAccountQuota(file = account.quotaFile),
                credential = WiredAccountCredential(present = account.credentialPresent, refusal = account.refusal),
                extraHeaders = CredentialHeaders { headers },
            )
        }
}
