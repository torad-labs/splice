// NEW: client-login model ownership is separate from foreign-head compaction window materialization.
package splice.launch.recipe

import splice.launch.LaunchSpec

internal object LaunchModelEnvironment {
    // A small pinned window must not plant a nonsense auto-compact cap. Claude Code takes
    // min() with its client window, so this floor is harmless when that window is smaller.
    private const val AUTO_COMPACT_FLOOR = 60_000L

    fun inherited(kept: Map<String, String>): List<String> = CLIENT_PICKED_ENV.filterNot(kept::containsKey)

    fun models(spec: LaunchSpec, slots: List<Pair<String, String>>): Map<String, String> =
        buildMap {
            // V4-449: an explicit pin selects the initial model only. Without one, the client chooses.
            if (spec.models.pinnedModel.isNotBlank()) put("ANTHROPIC_MODEL", spec.models.pinnedModel)
            // Every tier must name the same client selector as the enforced allowlist, even
            // when tiers share a backend. A discovery-wrapped duplicate used to hide a picker row,
            // but the client now rejects that off-list selector before a subagent can reach us.
            slots.forEach { (slot, model) ->
                put("ANTHROPIC_DEFAULT_${slot}_MODEL", spec.models.tiers.clientId(model))
                val label = spec.models.modelLabels[model] ?: model
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_NAME", label)
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_DESCRIPTION", label)
            }
        }

    fun windows(spec: LaunchSpec): Map<String, String> {
        if (spec.gateway.forwardClientAuth) return emptyMap()
        // The pinned row's window remains the compaction target on foreign-provider heads.
        // Presented and [1m]-spelled rows must retain their client's larger context ceiling.
        val compactWindow = maxOf(
            AUTO_COMPACT_FLOOR,
            spec.models.contextWindow,
            spec.models.tiers.presentedWindow,
            spec.models.tiers.spelledWindow,
        )
        return mapOf(
            "CLAUDE_CODE_MAX_CONTEXT_TOKENS" to spec.models.contextWindow.toString(),
            "CLAUDE_CODE_AUTO_COMPACT_WINDOW" to compactWindow.toString(),
        )
    }
}

// A client-login head removes inherited selectors unless it explicitly supplies a launch default.
private val CLIENT_PICKED_ENV =
    listOf("ANTHROPIC_MODEL", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW")
