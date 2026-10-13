// NEW: v0.4.0 section 10 under the fail-closed boot law. A local runtime that is up and refuses a row splice.toml
// declares (it serves less than the row's window, lists no such model, or says the model cannot call tools) is a
// boot finding, listed with every other one and offered to the fix session, where it used to fail only its own head
// (ChatArm), which the daemon then served as DEGRADED. A runtime that is down is no finding: the head boots and its
// turns fail until the runtime is up, as before. Same probe, transport and bearer as ChatArm (LocalProbeInputs).
package splice.app.provider

import splice.core.model.ModelTierSuffix
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.util.EnvReader
import splice.topology.LocalRowFindings
import splice.upstream.local.LocalRowVerdict
import splice.upstream.local.LocalRuntimeProbe

internal class LocalRowBootFindings(
    private val env: EnvReader,
    private val inputs: LocalProbeInputs = LocalProbeInputs(),
) : LocalRowFindings {
    override fun of(topology: Topology): List<TopologyFinding> = topology.heads.flatMap { (key, head) ->
        val provider = topology.providers[head.provider]
        if (provider?.isLocal == true && provider.models.isNotEmpty()) refusals(key, head, provider) else emptyList()
    }

    /** The head's rows by the id the runtime sees (a picker suffix stripped), each with the row it came from. */
    private fun refusals(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> {
        val served = head.models?.map { it.id }?.toSet()
        val rows = provider.models.filter { served == null || it.id in served }
            .groupBy { ModelTierSuffix.strip(it.id) }.mapValues { (_, same) -> same.first().id }
        val headers = inputs.headers(provider, inputs.bearer(key, provider, env))
        val probe = LocalRuntimeProbe(provider.baseUrl, JdkLocalHttp(headers))
        val runtime = probe.detect() ?: return emptyList()
        val listed = probe.models(runtime, rows.keys) ?: return emptyList()
        val declared = rows.keys.associateWith { provider.declaredWindowFor(it, head.contextWindow) }
        return probe.validate(declared, listed, runtime.kind).filterNot { it.ok }.map { verdict ->
            val sentence = "the ${runtime.kind.label} runtime at this provider's base_url refuses it: ${verdict.reason}"
            TopologyFinding(pathOf(key, head, rows.getValue(verdict.id), verdict), "$sentence; $FIX")
        }
    }

    /** A window the runtime refuses is fixed where it was declared: the head's own context_window when it set one,
     *  else the row's. Any other refusal names the row. */
    private fun pathOf(key: String, head: HeadConfig, rowId: String, verdict: LocalRowVerdict): String {
        val row = "providers.${head.provider}.models[$rowId]"
        return when {
            !verdict.reason.contains("context_window") -> row
            (head.contextWindow ?: 0) > 0 -> "heads.$key.context_window"
            else -> "$row.context_window"
        }
    }
}

// why: one fix sentence for every runtime refusal, the words ChatArm's head refusal already used.
private const val FIX = "fix the row, or set local = false on the provider to skip this check"
