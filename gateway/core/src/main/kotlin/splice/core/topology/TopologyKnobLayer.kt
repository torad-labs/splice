// PORT-OF: splice/core/topology/Topology.kt (Topology.configOverrides,
// Topology.putLegacyProviderOverrides, DaemonConfig.putFoldOverrides) @ a941c17 — invariants
// unchanged: every knob key string, every value spelling and the LinkedHashMap insertion order that
// decides which knob wins on conflict. Only the receiver moved, from `topology.configOverrides()` to
// `TopologyKnobLayer(topology).configOverrides()`. Split out 2026-08-18 (HD-25).
//
// WHY IT IS A PASSENGER IN THE SCHEMA FILE: this is the one place :core.topology hardcodes ANOTHER
// package's vocabulary. "controlPort", "pinnedModel", "chatgptApiBase", "grokPort" and the rest are
// splice.core.config Knob key names, carried here as bare strings with no compile-time link to the
// typed keys they must match — a coupling the schema types themselves do not have, and one that a
// reader of Topology.kt has no reason to expect to find there.
//
// WHY NOT splice.core.config, where the typed Knob keys live and this coupling could be checked by
// the compiler: ConfigService already reads Topology (it takes the headOverrides layer this
// produces), so parking the projection there would make splice.core.config and splice.core.topology
// mutually dependent. Trading a package cycle for a compile-time key check is the worse deal;
// topology placement keeps the direction one-way.
package splice.core.topology

public class TopologyKnobLayer(private val topology: Topology) {

    /**
     * Flat knob map from topology TOML for ConfigService's headOverrides layer.
     * Order: free-form [defaults] first, then explicit [daemon] fields (win on conflict).
     * Values are strings because ConfigService coerces by KnobKind.
     */
    public fun configOverrides(): Map<String, String> {
        val daemon = topology.daemon
        val out = LinkedHashMap(topology.defaults)
        daemon.controlPort?.let { out["controlPort"] = it.toString() }
        daemon.showReasoning?.let { out["showReasoning"] = it }
        daemon.summary?.let { out["summary"] = it }
        daemon.effort?.let { out["effort"] = it }
        daemon.replayReasoning?.let { out["replayReasoning"] = it.toString() }
        daemon.mirrorReasoning?.let { out["mirrorReasoning"] = it.toString() }
        putFoldOverrides(daemon, out)
        putLegacyProviderOverrides(out)
        return out
    }

    /** Reasoning-continuation fold knobs, split out so [configOverrides] stays under the complexity
     *  cap. The comma-joined model list is what the STRING knob coerces (SpliceConfig splits it
     *  back). Was a member of [DaemonConfig] until HD-25 moved the whole projection here; the
     *  receiver became the first parameter, which is the same shape the no-extension-declarations
     *  law prescribes for a receiver a function no longer sits on. */
    private fun putFoldOverrides(daemon: DaemonConfig, out: MutableMap<String, String>) {
        daemon.foldReasoningModels?.let { out["foldReasoningModels"] = it.joinToString(",") }
        daemon.foldMaxContinue?.let { out["foldMaxContinue"] = it.toString() }
        daemon.foldMarkerText?.let { out["foldMarkerText"] = it }
        daemon.foldMaxTier?.let { out["foldMaxTier"] = it.toString() }
    }

    /**
     * The management API retains the original codex/grok knob names. Seed those knobs from TOML so
     * their effective values describe the topology, then let state/env/runtime override them through
     * ConfigService's normal precedence.
     */
    private fun putLegacyProviderOverrides(out: MutableMap<String, String>) {
        val codex = topology.heads.entries.firstOrNull { (_, head) ->
            topology.providers[head.provider]?.auth?.kind == "chatgpt-oauth"
        }
        codex?.let { (_, head) ->
            val provider = topology.providers.getValue(head.provider)
            out["port"] = head.port.toString()
            out["pinnedModel"] = head.pinnedModel
            out["chatgptApiBase"] = provider.baseUrl
            provider.auth.file?.let { out["codexAuthPath"] = it }
        }

        val grok = topology.heads.entries.firstOrNull { (_, head) ->
            topology.providers[head.provider]?.auth?.kind == "grok-oauth"
        }
        grok?.let { (_, head) ->
            val provider = topology.providers.getValue(head.provider)
            out["grokPort"] = head.port.toString()
            out["grokModel"] = head.pinnedModel
            out["xaiApiBase"] = provider.baseUrl
            provider.auth.file?.let { out["grokAuthPath"] = it }
        }
    }
}
