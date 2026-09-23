// NEW: v0.4.0 FEATURES.md §10 — the doctor rows for local providers — the runtime's own answers
// (kind, version, listed models, context) against what each HEAD on the provider advertises (its
// context_window override applied, picker suffixes stripped — the same rows boot validates),
// labelled "local" and never dressed as subscription or quota state. The probe carries the
// provider's headers and bearer, like a turn. The live probe (one tiny streamed request with one
// tool) runs only when the operator asks with --live.
package splice.diagnostics.doctor

import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.UpstreamWindows
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.EnvReader
import splice.upstream.local.LocalLiveProbe
import splice.upstream.local.LocalModel
import splice.upstream.local.LocalRowVerdict
import splice.upstream.local.LocalRuntime
import splice.upstream.local.LocalRuntimeKind
import splice.upstream.local.LocalRuntimeProbe

private const val FIX_START = "start it (Ollama / LM Studio / vLLM), then re-run"
private const val FIX_TOOLS = "a model without tool calls cannot drive Claude Code's tools"

/** The per-head context_window override the daemon applies at boot (runtime config / env layer),
 *  so doctor validates the SAME effective rows: HeadBuildInputs reads it through ConfigService.getConfig(key). */
internal fun interface HeadWindowOverride {
    operator fun invoke(topology: Topology, headKey: String): Long?
}

/** The SAME ConfigService the daemon builds (Daemon.kt): the global [defaults]/[daemon] layer AND the
 *  per-head [heads.<key>.overrides] layer, so a window override tuned for one head reaches doctor
 *  exactly as it reaches boot. */
internal class ConfigHeadWindowOverride(private val env: EnvReader) : HeadWindowOverride {
    override fun invoke(topology: Topology, headKey: String): Long? = ConfigService(
        StatePaths(envReader = env),
        headOverrides = TopologyKnobLayer(topology).configOverrides(),
        perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
        envReader = env,
    ).getConfig(headKey).contextWindowOverride
}

internal class DoctorLocalRuntime(
    private val transport: LocalRuntimeTransport,
    private val env: EnvReader = EnvReader(System::getenv),
    private val override: HeadWindowOverride = ConfigHeadWindowOverride(env),
) {

    internal fun localChecks(topology: Topology, live: Boolean): List<DoctorCheck> =
        topology.providers.filterValues { it.isLocal }.flatMap { (key, provider) ->
            checks(key, provider, effectiveRows(topology, key), live)
        }

    /** Every head on the provider contributes its effective rows — the head's TOML window under the
     *  daemon's own per-head override, exactly as boot builds the catalog; the widest window per id is checked. */
    private fun effectiveRows(topology: Topology, key: String): Map<String, Long> = topology.heads
        .filterValues { it.provider == key }
        .flatMap { (headKey, head) ->
            val catalog = topology.providers.getValue(key).catalogFor(head, override(topology, headKey))
            UpstreamWindows(catalog).byId().entries
        }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, windows) -> windows.max() }

    private fun checks(
        key: String,
        provider: ProviderConfig,
        rows: Map<String, Long>,
        live: Boolean,
    ): List<DoctorCheck> {
        val name = "local:$key"
        val probe = LocalRuntimeProbe(provider.baseUrl, transport.http(key, provider, env))
        val runtime = probe.detect()
            ?: return listOf(
                DoctorCheck(name, CheckStatus.WARN, "no runtime answering at ${provider.baseUrl}", FIX_START),
            )
        // The live probe runs only for rows the runtime LISTS (LM Studio answers a request for an
        // unknown id with whichever model is loaded — a green live row for a refused id would lie),
        // and BEFORE the verdicts: its one request loads the model, and a loaded model is the only
        // one whose served window the runtime reports, so the verdicts read the exact number.
        // V4-103: the kind is bound to a NAME here rather than reached through `runtime.kind` at each
        // use. These three types cross the :upstream -> :app boundary as members of
        // LocalRuntimeProbe's public API, and a boundary consumed only by INFERENCE is invisible both
        // to public-surface --ratchet (which measures names in another module's main sources) and to
        // a reader asking who depends on this surface. Naming them is the same code with the boundary
        // stated, which is the fix the ratchet asks for — not a baseline bump.
        val kind: LocalRuntimeKind = runtime.kind
        val listed = probe.models(runtime)
            ?: return listOf(
                DoctorCheck(
                    name,
                    CheckStatus.WARN,
                    "${kind.label} answers at ${provider.baseUrl} but its model list does not",
                    FIX_START,
                ),
            )
        val listedIds = listed.map { it.id }.toSet()
        val probed = if (live) rows.keys.filter { it in listedIds } else emptyList()
        val probes = probed.map { id -> liveCheck(name, probe, id) }
        // The probe loaded the model: read the list again so the verdicts see the window the runtime
        // actually allocated, not the pre-load snapshot (review 2026-09-14).
        val current = if (probed.isEmpty()) listed else probe.models(runtime) ?: listed
        // The type is bound on the SEAM, not on the mapped result: `map` yields DoctorChecks, and
        // annotating that as List<LocalRowVerdict> was a compile error I hit and read. Binding the
        // boundary type where it actually crosses is the same evidence with the types honest.
        val verdicts: List<LocalRowVerdict> = probe.validate(rows, current, kind)
        val checks = verdicts.map { v ->
            val fix = "fix [[providers.$key.models]] (or the head's context_window) to a model the runtime " +
                "lists, at or under its context"
            DoctorCheck("$name/${v.id}", if (v.ok) CheckStatus.OK else CheckStatus.FAIL, v.reason, fix.takeIf { !v.ok })
        }
        return listOf(summary(name, provider, runtime, current)) + checks + probes
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
        val result: LocalLiveProbe = probe.live(model)
        val status = if (result.streams && result.toolCalls) CheckStatus.OK else CheckStatus.WARN
        return DoctorCheck("$name/$model/live", status, result.detail, FIX_TOOLS.takeIf { !result.toolCalls })
    }
}
