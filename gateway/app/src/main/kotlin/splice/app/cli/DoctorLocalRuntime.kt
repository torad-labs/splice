// NEW (v0.4.0, FEATURES.md §10): the doctor rows for local providers — the runtime's own answers
// (kind, version, listed models, context) against what each HEAD on the provider advertises (its
// context_window override applied, picker suffixes stripped — the same rows boot validates),
// labelled "local" and never dressed as subscription or quota state. The probe carries the
// provider's headers and bearer, like a turn. The live probe (one tiny streamed request with one
// tool) runs only when the operator asks with --live.
package splice.app.cli

import splice.app.provider.LocalProbeInputs
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.dialect.chat.JdkLocalHttp
import splice.dialect.chat.LocalHttp
import splice.dialect.chat.LocalModel
import splice.dialect.chat.LocalRuntime
import splice.dialect.chat.LocalRuntimeProbe

private const val FIX_START = "start it (Ollama / LM Studio / vLLM), then re-run"
private const val FIX_TOOLS = "a model without tool calls cannot drive Claude Code's tools"

internal class DoctorLocalRuntime(
    private val http: LocalHttp? = null,
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val inputs = LocalProbeInputs()

    internal fun localChecks(topology: Topology, live: Boolean): List<DoctorCheck> =
        topology.providers.filterValues { it.isLocal }.flatMap { (key, provider) ->
            checks(key, provider, effectiveRows(topology, key), live)
        }

    /** Every head on the provider contributes its effective rows; the widest window per id is checked. */
    private fun effectiveRows(topology: Topology, key: String): Map<String, Long> = topology.heads.values
        .filter { it.provider == key }
        .flatMap { head -> inputs.effectiveRows(topology.providers.getValue(key).catalogFor(head)).entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, windows) -> windows.max() }

    private fun checks(
        key: String,
        provider: ProviderConfig,
        rows: Map<String, Long>,
        live: Boolean,
    ): List<DoctorCheck> {
        val name = "local:$key"
        val transport = http ?: JdkLocalHttp(inputs.headers(provider, inputs.bearer(key, provider, env)))
        val probe = LocalRuntimeProbe(provider.baseUrl, transport)
        val runtime = probe.detect()
            ?: return listOf(
                DoctorCheck(name, CheckStatus.WARN, "no runtime answering at ${provider.baseUrl}", FIX_START),
            )
        // The live probe runs only for rows the runtime LISTS (LM Studio answers a request for an
        // unknown id with whichever model is loaded — a green live row for a refused id would lie),
        // and BEFORE the verdicts: its one request loads the model, and a loaded model is the only
        // one whose served window the runtime reports, so the verdicts read the exact number.
        val listedIds = probe.models(runtime).map { it.id }.toSet()
        val probed = if (live) rows.keys.filter { it in listedIds } else emptyList()
        val probes = probed.map { id -> liveCheck(name, probe, id) }
        val listed = probe.models(runtime)
        val verdicts = probe.validate(rows, listed).map { v ->
            val fix = "fix [[providers.$key.models]] (or the head's context_window) to a model the runtime " +
                "lists, at or under its context"
            DoctorCheck("$name/${v.id}", if (v.ok) CheckStatus.OK else CheckStatus.FAIL, v.reason, fix.takeIf { !v.ok })
        }
        return listOf(summary(name, provider, runtime, listed)) + verdicts + probes
    }

    private fun summary(
        name: String,
        provider: ProviderConfig,
        runtime: LocalRuntime,
        listed: List<LocalModel>,
    ): DoctorCheck {
        val version = runtime.version?.let { " $it" }.orEmpty()
        val models = listed.joinToString { m -> m.id + (m.contextLength?.let { " (ctx $it)" } ?: "") }
        val detail = "${runtime.kind.label}$version at ${provider.baseUrl} lists ${listed.size} model(s): $models"
        return DoctorCheck(name, CheckStatus.OK, detail)
    }

    private fun liveCheck(name: String, probe: LocalRuntimeProbe, model: String): DoctorCheck {
        val result = probe.live(model)
        val status = if (result.streams && result.toolCalls) CheckStatus.OK else CheckStatus.WARN
        return DoctorCheck("$name/$model/live", status, result.detail, FIX_TOOLS.takeIf { !result.toolCalls })
    }
}
