// PORT-OF: server/src/models/codex-models.mjs + grok-models.mjs @ pre-public-port-baseline — invariants:
// context windows resolve EXACT match -> ordered startsWith prefix rules -> default
// (never substring: the v29 fuzzy pass silently inherited windows and hid catalog gaps);
// discovery wrap because Claude Code drops /v1/models ids not matching /^(claude|anthropic)/i;
// stripSuffixes removes the discovery prefix and a trailing "[1m]" hint (case-insensitive);
// discovery rows carry display_name; availableModelIds stay UNWRAPPED (a wrapped active
// model makes Claude Code ignore the context-window env and compact early).
package splice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ModelEntry(
    val id: String,
    val label: String = "",
    val description: String = "",
    @SerialName("context_window") val contextWindow: Long,
)

@Serializable
public data class WindowRule(
    val prefix: String,
    @SerialName("context_window") val contextWindow: Long,
)

@Serializable
public data class ExtraWindow(
    val id: String,
    @SerialName("context_window") val contextWindow: Long,
)

/** One provider's model surface: picker rows, window-only ids, ordered prefix rules. */
public data class ModelCatalog(
    val discoveryPrefix: String,
    val models: List<ModelEntry>,
    val extraWindows: List<ExtraWindow> = emptyList(),
    val windowRules: List<WindowRule> = emptyList(),
    val defaultContextWindow: Long,
) {
    init {
        require(models.isNotEmpty()) { "a catalog needs at least one picker model" }
        require(discoveryPrefix.isNotEmpty()) { "discovery prefix is the picker namespace — never empty" }
    }

    public val defaultModel: String get() = models.first().id

    private val exactWindows: Map<String, Long> =
        models.associate { it.id to it.contextWindow } + extraWindows.associate { it.id to it.contextWindow }

    private val suffixHint = Regex("\\[1m]$", RegexOption.IGNORE_CASE)

    // Canonical (suffix-stripped) ids — `contains` strips its query the same way, so both sides
    // compare on the upstream id. Storing the RAW id here let a "[1m]" picker model (kimi k3[1m])
    // never match its own stripped upstream id "k3" → every k3 turn 400'd "proxies its own models
    // only" (regression from the contains guard's introduction; no [1m] catalog test caught it).
    private val modelIds: Set<String> = models.mapTo(HashSet()) { stripSuffixes(it.id) }

    public fun wrap(id: String): String = discoveryPrefix + id

    public fun unwrap(id: String): String = id.removePrefix(discoveryPrefix)

    /** True only for a picker model owned by this head (wrapped or upstream id). */
    public fun contains(id: String): Boolean = stripSuffixes(id) in modelIds

    /** Discovery wrapper + "[1m]" hint stripped — what the upstream actually sees. */
    public fun stripSuffixes(id: String): String = unwrap(id).replace(suffixHint, "")

    /** Exact -> ordered startsWith prefix rules -> default. Order is the law. */
    public fun contextWindowFor(model: String?, defaultOverride: Long? = null): Long {
        val fallback = defaultOverride?.takeIf { it > 0 } ?: defaultContextWindow
        if (model.isNullOrEmpty()) return fallback
        val id = stripSuffixes(model)
        return exactWindows[id]
            ?: windowRules.firstOrNull { id.startsWith(it.prefix) }?.contextWindow
            ?: fallback
    }

    public fun labelFor(id: String): String = models.firstOrNull { it.id == id }?.label ?: id

    /** /v1/models rows: every catalog model, wrapped, with display_name for the picker. */
    public fun discoveryRows(): List<DiscoveryRow> =
        models.map { DiscoveryRow(id = wrap(it.id), displayName = it.label) }

    /** settings.json availableModels allowlist — UNWRAPPED ids. */
    public fun availableModelIds(): List<String> = models.map { it.id }
}

@Serializable
public data class DiscoveryRow(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val type: String = "model",
    val created: Long = 0,
)
