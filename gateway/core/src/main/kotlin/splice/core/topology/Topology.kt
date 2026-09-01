// NEW: the TOML topology schema (shape proven by spike P0-TOML incl. @SerialName mapping;
// gateway/spikes/results/ktoml.md). Loaded once at daemon start by :app — adding a
// provider or head is an operator action and implies a restart (no hot topology).
// 2026-08-16 (HD-M8): the file's top-level functions were relocated without changing any body.
// The extensions on types THIS file owns became members of those types, so `provider.catalogFor(...)`
// and `topology.configOverrides()` read exactly as before; the three operator-facing diagnostics
// became members of TopologyMessages; effectiveApiKeyEnv became a member of AuthConfig, which is
// the type it interrogates.
// 2026-08-17 (HD-20): the last MEMBER extension here — `DaemonConfig.putFoldOverrides`, declared
// inside Topology — moved down onto DaemonConfig itself, the type it always read. Same body, same
// name, byte-identical call site.
// 2026-08-18 (HD-25): three passengers left this file, no schema type was shredded. QuirksConfig +
// ToolSurfaceConfig -> QuirksConfig.kt (nothing here ever read a quirk); TopologyMessages + the port
// range -> TopologyMessages.kt (no call site holds a Topology); configOverrides +
// putLegacyProviderOverrides + putFoldOverrides -> TopologyKnobLayer.kt (the one place this package
// hardcoded splice.core.config's key vocabulary). What STAYS is the schema graph and its invariants:
// Topology with the four pure folds over `heads` (three of them asserting uniqueness across it), and
// ProviderConfig/HeadConfig/AuthConfig/Dialect, which carry the referential-integrity invariant that
// HeadConfig.provider is a key into Topology.providers.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.model.ExtraWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val claude: ClaudeSharingDefaults = ClaudeSharingDefaults(),
    val defaults: Map<String, String> = emptyMap(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
) {
    /** Resolve a user-supplied head name — the topology key or the installed wrapper command
     *  (starter: head `openrouter`, command `claude-openrouter`) — to matching topology keys. A topology-KEY
     *  match is exact and wins as the sole result; otherwise ALL heads whose wrapper command equals
     *  the name (a misconfigured topology can share one command across several heads). */
    public fun resolveHeadKeys(name: String): List<String> {
        if (name in heads) return listOf(name)
        return heads.entries.filter { (key, head) -> (head.claude.command ?: key) == name }.map { it.key }
    }

    /** The single topology key for [name], or null when unknown OR ambiguous (several heads share
     *  the wrapper command). Callers that must tell those apart use [resolveHeadKeys]. */
    public fun resolveHeadKey(name: String): String? = resolveHeadKeys(name).singleOrNull()

    /** JW-13: ports mapped to the >1 heads that share them — the port analogue of the
     *  wrapper-command collision install already validates. A copy-pasted [heads.X] with an
     *  unchanged port otherwise surfaces only as an opaque per-head "Address already in use". */
    public fun portCollisions(): Map<Int, List<String>> =
        heads.entries.groupBy({ it.value.port }, { it.key }).filterValues { it.size > 1 }

    /** CTL-005: heads whose port is outside the valid TCP range — 0, negative, or > 65535 all
     *  parse fine as an Int and otherwise surface only at bind time, as an opaque error that
     *  never names the offending [heads.X] entry. Same idiom as [portCollisions]. */
    public fun invalidPortHeads(): Map<String, Int> =
        heads.filterValues { it.port !in validPortRange }.mapValues { it.value.port }
}

@Serializable
public data class DaemonConfig(
    // Nullable: an ABSENT control_port defers to env/state/knob default. A non-null default here
    // made SPLICE_CONTROL_PORT dead and let /api/config report a port nothing listens on
    // (audit 2026-07-18).
    @SerialName("control_port") val controlPort: Int? = null,
    @SerialName("state_dir") val stateDir: String? = null,
    // Reasoning display — edit these in ~/.config/splice/splice.toml (no code change).
    // Precedence: env > state config.json > [daemon] / [defaults] TOML > knob defaults.
    @SerialName("show_reasoning") val showReasoning: String? = null,
    val summary: String? = null,
    val effort: String? = null,
    @SerialName("replay_reasoning") val replayReasoning: Boolean? = null,
    @SerialName("mirror_reasoning") val mirrorReasoning: Boolean? = null,
    // Reasoning-continuation folding (codex 518n-2). A TOML array of upstream model ids that truncate
    // their chain-of-thought (default luna/terra/5.5); the caps + marker text tune the loop.
    @SerialName("fold_reasoning_models") val foldReasoningModels: List<String>? = null,
    @SerialName("fold_max_continue") val foldMaxContinue: Int? = null,
    @SerialName("fold_marker_text") val foldMarkerText: String? = null,
    @SerialName("fold_max_tier") val foldMaxTier: Int? = null,
)

@Serializable
public data class ProviderConfig(
    val dialect: Dialect,
    @SerialName("base_url") val baseUrl: String,
    val auth: AuthConfig,
    val quirks: QuirksConfig = QuirksConfig(),
    /** Static vendor headers every upstream request carries (e.g. `anthropic-version`). Operator-
     *  owned and pure TOML, so an anthropic-compatible vendor needs no provider code at all. On a
     *  head that forwards the client's own headers, a forwarded value WINS over these defaults. */
    @SerialName("extra_headers") val extraHeaders: Map<String, String> = emptyMap(),
    val models: List<ModelEntry> = emptyList(),
    @SerialName("extra_windows") val extraWindows: List<ExtraWindow> = emptyList(),
    @SerialName("window_rules") val windowRules: List<WindowRule> = emptyList(),
    @SerialName("default_context_window") val defaultContextWindow: Long = 0,
) {
    /**
     * [extraHeaders] with TOML key quoting removed — THE accessor every consumer must use.
     *
     * `extra_headers = { "anthropic-version" = "..." }` is valid TOML and the natural thing to
     * write (a header name contains a dash), but ktoml hands back the key WITH its quote
     * characters, which would put a literally malformed name on the wire. Bare keys parse clean;
     * both forms must behave identically, so the quotes are stripped here rather than in each
     * consumer. Header names never legitimately contain a double quote.
     */
    init {
        if (auth.kind == AuthKind.Client.wire) {
            require(
                extraHeaders.keys.none { raw ->
                    val header = raw.trim('"')
                    header.equals("Authorization", ignoreCase = true) ||
                        header.equals("x-api-key", ignoreCase = true)
                },
            ) { "client auth cannot configure Authorization or x-api-key in extra_headers" }
        }
    }

    public val staticHeaders: Map<String, String>
        get() = extraHeaders.mapKeys { (key, _) -> key.trim('"') }

    /** A catalog is the JOIN of this provider's models with the head's [HeadConfig.discoveryPrefix]
     *  — which is why it lives on the provider and takes the head, and why the two types stay in one
     *  file. A non-empty [HeadConfig.models] is an ordered per-head allowlist; an absent list preserves
     *  the provider-wide surface for older topologies. [contextWindowOverride] wins over the declared
     *  per-head window and, when positive, replaces the window on every selected entry. */
    public fun catalogFor(head: HeadConfig, contextWindowOverride: Long? = null): ModelCatalog {
        val selectedModels = modelsFor(head)
        head.contextWindow?.let { require(it > 0) { "head context_window must be positive" } }
        val window = contextWindowOverride?.takeIf { it > 0 } ?: head.contextWindow
        return ModelCatalog(
            // The pinned row's window IS the launch env, so the catalog needs it to know what the
            // client believes about every OTHER row (ModelCatalog.clientContextWindowFor).
            pinnedModel = head.pinnedModel,
            discoveryPrefix = head.discoveryPrefix,
            models = if (window == null) {
                selectedModels
            } else {
                selectedModels.map { it.copy(contextWindow = window) }
            },
            extraWindows = if (window == null) extraWindows else extraWindows.map { it.copy(contextWindow = window) },
            windowRules = if (window == null) windowRules else windowRules.map { it.copy(contextWindow = window) },
            defaultContextWindow = if (window != null) {
                window
            } else if (defaultContextWindow > 0) {
                defaultContextWindow
            } else {
                selectedModels.firstOrNull()?.contextWindow ?: DEFAULT_WINDOW_FLOOR
            },
        )
    }

    private fun modelsFor(head: HeadConfig): List<ModelEntry> {
        val requested = head.models ?: return models
        require(requested.isNotEmpty()) { "head model list must not be empty" }
        require(requested.map { it.id }.distinct().size == requested.size) { "head model list contains duplicates" }
        val slots = requested.mapNotNull { it.slot?.lowercase() }
        require(slots.all { it in headModelSlots }) { "unknown Claude model slot" }
        require(slots.distinct().size == slots.size) { "head model slots contain duplicates" }
        val byId = models.associateBy(ModelEntry::id)
        val selected = requested.map { model ->
            requireNotNull(byId[model.id]) {
                "head model '${model.id}' is not declared by provider '${head.provider}'"
            }
        }
        // The failing id can come from OUTSIDE the TOML: resolveHeadConfig swaps pinned_model with
        // the pinnedModel/grokModel knob for oauth heads, and env/config.json/PATCH override that
        // knob — so a self-consistent splice.toml still fails here. Name the id, the roster, and
        // the provenance, or the operator greps the TOML for a value that is not in it (DR-44a).
        require(selected.any { it.id == head.pinnedModel }) {
            "pinned model '${head.pinnedModel}' is not in the head model list " +
                "[${selected.joinToString(", ") { it.id }}] — set by pinned_model in splice.toml " +
                "unless the pinnedModel/grokModel knob (env, config.json, or PATCH) overrode it"
        }
        return selected
    }
}

// Dialect / AuthConfig / ClaudeWrapperConfig / ClaudeSharingDefaults live in
// TopologySchema.kt (concentration, 2026-08-19). Same-package FQCNs are unchanged.

@Serializable
public data class HeadModel(
    val id: String,
    val slot: String? = null,
)

@Serializable
public data class HeadConfig(
    val provider: String,
    val port: Int,
    @SerialName("discovery_prefix") val discoveryPrefix: String,
    @SerialName("pinned_model") val pinnedModel: String,
    val models: List<HeadModel>? = null,
    @SerialName("context_window") val contextWindow: Long? = null,
    val overrides: Map<String, String> = emptyMap(),
    val claude: ClaudeWrapperConfig = ClaudeWrapperConfig(),
)

private const val DEFAULT_WINDOW_FLOOR: Long = 200_000
private val headModelSlots = setOf("opus", "sonnet", "haiku", "fable")
