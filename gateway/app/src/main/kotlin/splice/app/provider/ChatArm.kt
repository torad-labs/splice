// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.chatProvider) @ ed5c868. ProviderAssembly rejects
// registered incompatible kinds first; this arm selects Grok OAuth and sends unregistered
// api-key/custom kinds to generic Bearer auth. Grok rides this dialect because
// /v1/chat/completions streams the full readable CoT (`reasoning_content`) where the Responses
// summary channel stops mid-reasoning
// (measured 2026-07-18; grok CLI / OpenCode parity).
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.GrokRefresh
import splice.app.TopologyLoader
import splice.core.auth.Credentials
import splice.core.topology.AuthKind
import splice.core.util.LogSink
import splice.dialect.chat.SlotAffinity
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import java.nio.file.Paths

internal class ChatArm(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val grokRefresh: GrokRefresh,
) {
    private val overlay = QuirksOverlay()
    private val probeInputs = LocalProbeInputs()
    private val grokAccounts = GrokAccountWiring(probeScope, log, grokRefresh)
    private val slotTables = SlotTables(RuntimeSlotCount.Background(probeScope, log))

    // After compatibility validation: Grok OAuth uses refresh-capable auth; unregistered
    // api-key/custom kinds use generic Bearer auth. Grok rides this dialect because
    // /v1/chat/completions streams the full readable CoT (`reasoning_content`) where Responses
    // stops mid-reasoning (measured 2026-07-18; grok CLI / OpenCode parity).
    internal fun chatProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val accounts = if (providerCfg.auth.kind == GROK_OAUTH) {
            val primaryPath = Paths.get(
                TopologyLoader.expandHome(providerCfg.auth.file ?: AuthKind.GrokOAuth.authFile),
            )
            grokAccounts.accounts(ctx, primaryPath)
        } else {
            emptyList()
        }
        val auth = accounts.takeIf { it.isNotEmpty() }
            ?.let(WiredAccounts::providerAccount)
            ?.auth
            ?: ApiKeyAuthProvider(
                envVar = providerCfg.auth.effectiveApiKeyEnv(key),
                keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
            )
        if (providerCfg.isLocal) refuseContradictedRows(ctx, (auth as? ApiKeyAuthProvider)?.keyNow())
        val provider = OpenAiChatProvider(
            tuning = ProviderTuning(
                key = key,
                label = label,
                catalog = ctx.catalog,
                pinnedModel = ctx.head.pinnedModel,
                auth = auth,
                baseUrl = providerCfg.baseUrl,
                watchdog = ctx.watchdog,
                loginCommand = ctx.loginCommand,
            ),
            // The profile and its TOML overlay live in QuirksOverlay with the other two dialects
            // (DR-155) — this arm's job is auth selection and provider construction.
            quirks = overlay.chatQuirks(providerCfg, key, label),
            showReasoning = ctx.cfg.showReasoning,
            affinity = slotAffinity(ctx, (auth as? ApiKeyAuthProvider)?.keyNow()),
        )
        val configured = providerCfg.staticHeaders.takeIf { it.isNotEmpty() }
            ?.let { StaticChatHeaders(provider, it) }
            ?: provider
        return Wired(configured, auth, accounts)
    }

    /** V4-165: opted in by [providers.<key>.quirks] slot_affinity = true. The slot count comes from the
     *  runtime, read in the background (RuntimeSlotCount), so a head that boots before its server
     *  sends its first turns unpinned. V4-166: one table per runtime, shared by its heads (SlotTables). */
    private fun slotAffinity(ctx: ProviderBuild, bearer: String?): SlotAffinity? {
        if (ctx.providerCfg.quirks.slotAffinity != true) return null
        val http = JdkLocalHttp(probeInputs.headers(ctx.providerCfg, bearer))
        return slotTables.forRuntime(ctx.key, ctx.providerCfg.baseUrl, bearer, http)
    }

    /** v0.4.0 (FEATURES.md §10): a local runtime that is UP and contradicts the row refuses the
     *  head with the runtime's own words; a runtime that is down boots as today (per-turn errors),
     *  because refusing every head whose runtime is not yet started would be below the status quo. */
    private fun refuseContradictedRows(ctx: ProviderBuild, bearer: String?) {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        when (val found = probeInputs.check(providerCfg, bearer, ctx.catalog)) {
            LocalRowsCheck.Down -> log(
                "[$key] local runtime at ${providerCfg.baseUrl} is not answering; " +
                    "the head boots, turns fail until it is up\n",
            )
            is LocalRowsCheck.Unlisted -> {
                val where = "${found.runtime.kind.label} at ${providerCfg.baseUrl}"
                log("[$key] local runtime $where answered but its model list did not; the head boots, rows unchecked\n")
            }
            is LocalRowsCheck.Checked -> {
                val runtime = found.runtime
                check(found.refused.isEmpty()) {
                    "local runtime ${runtime.kind.label} at ${providerCfg.baseUrl} refuses " +
                        found.refused.joinToString("; ") { "'${it.id}': ${it.reason}" } +
                        " (fix the row, or set local = false on the provider to skip this check)"
                }
                val version = runtime.version?.let { " $it" }.orEmpty()
                log("[$key] local runtime ${runtime.kind.label}$version: ${found.rows.size} row(s) validated\n")
            }
        }
    }
}

/** Makes configured probe headers ride real chat turns too; credential-owned auth always wins. */
private class StaticChatHeaders(
    private val delegate: Provider,
    private val configured: Map<String, String>,
) : Provider by delegate {
    override fun extraHeaders(creds: Credentials): Map<String, String> {
        val authHeader = when (creds) {
            is Credentials.Bearer -> "Authorization"
            is Credentials.ApiKey -> creds.header
            Credentials.ClientForwarded -> null
        }
        val safeConfigured = configured.filterKeys { key ->
            authHeader == null || !key.equals(authHeader, ignoreCase = true)
        }
        return delegate.extraHeaders(creds) + safeConfigured
    }
}
