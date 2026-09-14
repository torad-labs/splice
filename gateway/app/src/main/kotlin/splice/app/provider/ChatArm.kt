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
import splice.dialect.chat.JdkLocalHttp
import splice.dialect.chat.LocalRuntimeProbe
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.spi.Provider
import splice.spi.ProviderTuning
import java.nio.file.Paths

internal class ChatArm(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val grokRefresh: GrokRefresh,
) {
    private val overlay = QuirksOverlay()
    private val probeInputs = LocalProbeInputs()
    private val grokAccounts = GrokAccountWiring(probeScope, log, grokRefresh)

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
        )
        val configured = providerCfg.staticHeaders.takeIf { it.isNotEmpty() }
            ?.let { StaticChatHeaders(provider, it) }
            ?: provider
        return Wired(configured, auth, accounts)
    }

    /** v0.4.0 (FEATURES.md §10): a local runtime that is UP and contradicts the row refuses the
     *  head with the runtime's own words; a runtime that is down boots as today (per-turn errors),
     *  because refusing every head whose runtime is not yet started would be below the status quo. */
    private fun refuseContradictedRows(ctx: ProviderBuild, bearer: String?) {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val probe = LocalRuntimeProbe(providerCfg.baseUrl, JdkLocalHttp(probeInputs.headers(providerCfg, bearer)))
        val runtime = probe.detect()
        if (runtime == null) {
            log(
                "[$key] local runtime at ${providerCfg.baseUrl} is not answering; " +
                    "the head boots, turns fail until it is up\n",
            )
            return
        }
        // The HEAD's effective rows, not the provider's: a head context_window override and a picker
        // suffix ("[64k]") both change what the head advertises, and the wire sees the stripped id.
        val rows = probeInputs.effectiveRows(ctx.catalog)
        val listed = probe.models(runtime, rows.keys)
        if (listed == null) {
            val where = "${runtime.kind.label} at ${providerCfg.baseUrl}"
            log("[$key] local runtime $where answered but its model list did not; the head boots, rows unchecked\n")
            return
        }
        val refused = probe.validate(rows, listed, runtime.kind).filterNot { it.ok }
        check(refused.isEmpty()) {
            "local runtime ${runtime.kind.label} at ${providerCfg.baseUrl} refuses " +
                refused.joinToString("; ") { "'${it.id}': ${it.reason}" } +
                " (fix the row, or set local = false on the provider to skip this check)"
        }
        val version = runtime.version?.let { " $it" }.orEmpty()
        log("[$key] local runtime ${runtime.kind.label}$version: ${rows.size} row(s) validated\n")
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
