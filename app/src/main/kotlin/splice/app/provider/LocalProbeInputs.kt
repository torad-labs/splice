// NEW: v0.4.0 FEATURES.md §10 — what the local-runtime probe carries and what it checks — shared by
// the boot refusal (ChatArm) and doctor (DoctorLocalRuntime) so both see the same runtime and the
// same rows. Headers: the provider's static extra_headers plus its bearer, because a runtime that
// guards /v1/models (vLLM --api-key) must answer the probe exactly as it answers a turn. Rows: the
// HEAD's effective catalog (head context_window override applied, picker suffixes stripped, the
// widest row per upstream id), never the raw provider table.
package splice.app.provider

import splice.app.daemon.TopologyLoader
import splice.core.model.ModelCatalog
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.provider.openai.ApiKeyAuthProvider
import splice.upstream.local.LocalRowVerdict
import splice.upstream.local.LocalRuntime
import splice.upstream.local.LocalRuntimeProbe
import java.nio.file.Paths

internal class LocalProbeInputs {

    fun headers(provider: ProviderConfig, bearer: String?): Map<String, String> =
        provider.staticHeaders + (bearer?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())

    /** The api-key a turn would send, read the way the head reads it (env, then key file / store). */
    fun bearer(key: String, provider: ProviderConfig, env: EnvReader): String? {
        if (provider.auth.kind != "api-key") return null
        return ApiKeyAuthProvider(
            envVar = provider.auth.effectiveApiKeyEnv(key),
            keyFile = provider.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
            envReader = env,
        ).keyNow()
    }

    /** Upstream id -> the window the head advertises for it; two picker rows over one id keep the wider. */
    fun effectiveRows(catalog: ModelCatalog): Map<String, Long> {
        val rows = catalog.models.map { catalog.stripSuffixes(it.id) to it.contextWindow } +
            catalog.extraWindows.map { catalog.stripSuffixes(it.id) to it.contextWindow }
        return rows.groupBy({ it.first }, { it.second }).mapValues { (_, windows) -> windows.max() }
    }

    /** Ask the runtime behind [provider] about [catalog]'s rows. ONE sequence for the boot refusal
     *  (ChatArm) and for a window edited while the daemon runs (V4-162, TopologyWindows), so the two
     *  cannot come to disagree about what the runtime allows. Blocking network: never on a request
     *  thread. */
    fun check(provider: ProviderConfig, bearer: String?, catalog: ModelCatalog): LocalRowsCheck {
        val probe = LocalRuntimeProbe(provider.baseUrl, JdkLocalHttp(headers(provider, bearer)))
        val runtime = probe.detect() ?: return LocalRowsCheck.Down
        // The HEAD's effective rows, not the provider's: a head context_window override and a picker
        // suffix ("[64k]") both change what the head advertises, and the wire sees the stripped id.
        val rows = effectiveRows(catalog)
        val listed = probe.models(runtime, rows.keys) ?: return LocalRowsCheck.Unlisted(runtime)
        return LocalRowsCheck.Checked(runtime, rows, probe.validate(rows, listed, runtime.kind).filterNot { it.ok })
    }
}

/** What asking a local runtime about a head's rows found ([LocalProbeInputs.check]). */
internal sealed class LocalRowsCheck {
    /** Nothing answers at the base URL. Boot proceeds as it always has (per-turn errors say why). */
    data object Down : LocalRowsCheck()

    /** The runtime answered and its model list did not, so the rows stay unchecked. */
    data class Unlisted(val runtime: LocalRuntime) : LocalRowsCheck()

    /** The runtime's verdict on every row; [refused] is empty when it accepts them all. */
    data class Checked(
        val runtime: LocalRuntime,
        val rows: Map<String, Long>,
        val refused: List<LocalRowVerdict>,
    ) : LocalRowsCheck()
}
