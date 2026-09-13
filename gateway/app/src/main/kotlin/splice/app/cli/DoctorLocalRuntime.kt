// NEW (v0.4.0, FEATURES.md §10): the doctor rows for local providers — the runtime's own answers
// (kind, version, listed models, context) against what each row declares, labelled "local" and
// never dressed as subscription or quota state. The live probe (one tiny streamed request with one
// tool) runs only when the operator asks with --live.
package splice.app.cli

import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.dialect.chat.LocalHttp
import splice.dialect.chat.LocalModel
import splice.dialect.chat.LocalRuntime
import splice.dialect.chat.LocalRuntimeProbe

private const val FIX_START = "start it (Ollama / LM Studio / vLLM), then re-run"
private const val FIX_TOOLS = "a model without tool calls cannot drive Claude Code's tools"

internal class DoctorLocalRuntime(private val http: LocalHttp? = null) {

    internal fun localChecks(topology: Topology, live: Boolean): List<DoctorCheck> =
        topology.providers.filterValues { it.isLocal }.flatMap { (key, provider) -> checks(key, provider, live) }

    private fun checks(key: String, provider: ProviderConfig, live: Boolean): List<DoctorCheck> {
        val name = "local:$key"
        val probe = http?.let { LocalRuntimeProbe(provider.baseUrl, it) } ?: LocalRuntimeProbe(provider.baseUrl)
        val runtime = probe.detect()
            ?: return listOf(
                DoctorCheck(name, CheckStatus.WARN, "no runtime answering at ${provider.baseUrl}", FIX_START),
            )
        // The live probe runs FIRST: its one request loads the model, and a loaded model is the only
        // one whose served window Ollama reports, so the row verdicts below read the exact number.
        val probes = if (live) provider.models.map { m -> liveCheck(name, probe, m.id) } else emptyList()
        val listed = probe.models(runtime)
        val rows = probe.validate(provider.models.associate { it.id to it.contextWindow }, listed).map { v ->
            val fix = "fix [[providers.$key.models]] to a model the runtime lists, at or under its context"
            DoctorCheck("$name/${v.id}", if (v.ok) CheckStatus.OK else CheckStatus.FAIL, v.reason, fix.takeIf { !v.ok })
        }
        return listOf(summary(name, provider, runtime, listed)) + rows + probes
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
