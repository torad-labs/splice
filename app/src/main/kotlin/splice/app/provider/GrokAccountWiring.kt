// NEW: v0.4.0 FEATURES.md §11 — the Grok OAuth account pool wired once, for both dialects Grok
// rides (openai-chat in ChatArm, Responses in GrokResponsesArm). The two arms carried this code
// twice (review 2026-09-14); the refresh, the head-scoped log and the discovery are one seam now.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKind
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.dialect.chat.ChatQuirks
import splice.oauth.OAuthAccountFiles
import splice.oauth.grok.GrokRefresh
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokQuirks
import java.nio.file.Path

internal class GrokAccountWiring(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val grokRefresh: GrokRefresh,
    private val accountFiles: OAuthAccountFiles = OAuthAccountFiles(),
) {
    /** Every Grok account of [primaryPath]'s pool, the primary first, each with its own refresher. */
    fun accounts(ctx: ProviderBuild, primaryPath: Path): List<WiredAccount> {
        val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
        return accountFiles.discover(
            AuthKind.GrokOAuth,
            primaryPath,
            HeadScopedLogs.headScopedLog(ctx.key, log),
        ).map { file ->
            WiredAccount(
                label = file.label,
                primary = file.primary,
                auth = auth(ctx, file.credentialFile, tokenUrl),
                quotaFile = file.quotaFile,
                credentialPresent = file.credentialPresent,
            )
        }
    }

    private fun auth(ctx: ProviderBuild, path: Path, tokenUrl: String): RefreshableAuthProvider =
        GrokAuthProvider(
            authPath = path,
            authCacheMs = ctx.cfg.authCacheMs,
            refreshCall = { refreshToken -> grokRefresh.refresh(tokenUrl, refreshToken) },
            prefetchScope = probeScope,
            // JW-03: [<headKey>] first, so [grok-auth] refresh lines reach the head's tail.
            log = HeadScopedLogs.headScopedLog(ctx.key, log),
        )
}

/** grok-oauth chat dialect profile — xAI floor, session cache prefix, usage frames, xhigh models. */
internal class GrokChatQuirks {
    fun profile(key: String, label: String): ChatQuirks = ChatQuirks(
        providerTag = key,
        sessionCacheKeyPrefix = label,
        emitUsageInStream = true,
        minImageEdgePx = GrokQuirks().defaultQuirks().minImageEdgePx,
        xhighModels = GrokQuirks().xhighModels(),
    )
}
