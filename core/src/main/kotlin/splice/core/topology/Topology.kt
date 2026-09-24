// NEW: the TOML topology schema (shape proven by spike P0-TOML incl. @SerialName mapping;
// .dev/research/spikes/ktoml.md). Loaded once at daemon start by :app — adding a
// provider or head is an operator action and implies a restart (no hot topology). V4-162: the
// context windows are the one exception, re-read while the daemon runs (see withoutWindows).
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
import splice.core.compaction.CompactionConfig
import splice.core.config.Knob
import splice.core.model.DiscoveredModel
import splice.core.model.ExtraWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.ModelTierSuffix
import splice.core.model.WindowRule

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val claude: ClaudeSharingDefaults = ClaudeSharingDefaults(),
    val compaction: CompactionConfig = CompactionConfig(),
    val defaults: Map<String, String> = emptyMap(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
    /** V4-124: per-repo standing prompts, keyed by the project root path. Absent = today's bytes. */
    val projects: Map<String, ProjectConfig> = emptyMap(),
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
    /** V4-109: the CONTROL PLANE is a listener too, and it was invisible to this check — a head
     *  declaring the daemon's own port was reported as clean, then the two fought over the bind at
     *  start. [DaemonConfig.controlPort] is folded in under a name that cannot be mistaken for a
     *  head key.
     *
     *  The EFFECTIVE port, not just the declared one: an absent `control_port` still yields a real
     *  listener on the knob's default, so a head on that number collides in practice. What this
     *  cannot see is a port pinned later by env (`SPLICE_CONTROL_PORT`) or by state config.json —
     *  this is a pure function of the topology and deliberately reads no environment; an operator
     *  who moves the control port onto a head's number at runtime gets the bind failure, which is
     *  the same behaviour as before this check existed. */
    public fun portCollisions(): Map<Int, List<String>> {
        val controlPort = daemon.controlPort ?: (Knob.CONTROL_PORT.default as Long).toInt()
        val declared = heads.entries.map { it.value.port to it.key } + (controlPort to CONTROL_PLANE_OWNER)
        return declared.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
    }

    /** CTL-005: heads whose port is outside the valid TCP range — 0, negative, or > 65535 all
     *  parse fine as an Int and otherwise surface only at bind time, as an opaque error that
     *  never names the offending [heads.X] entry. Same idiom as [portCollisions]. */
    public fun invalidPortHeads(): Map<String, Int> =
        heads.filterValues { it.port !in validPortRange }.mapValues { it.value.port }

    /** V4-162: this topology with every context window taken out, which is the comparison that
     *  decides whether an edit to splice.toml needs a restart. Two files that differ only in windows
     *  (or only in comments, which never reach this type) compare EQUAL here, and the running daemon
     *  applies their windows live; any other difference is a restart. */
    public fun withoutWindows(): Topology = copy(
        providers = providers.mapValues { (_, provider) -> provider.withoutWindows() },
        heads = heads.mapValues { (_, head) -> head.copy(contextWindow = null) },
    )
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
    // Shared MCP hosting (v0.4.0, FEATURES.md §8). Nullable so an ABSENT key means "on" at the
    // wiring site without a literal default here that the knob layer would then have to know.
    @SerialName("mcp_hosting") val mcpHosting: Boolean? = null,
    @SerialName("mcp_hosting_exclude") val mcpHostingExclude: List<String>? = null,
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
    /** v0.4.0 (FEATURES.md §10): a user-managed local runtime (Ollama, LM Studio, vLLM) on the
     *  openai-chat dialect. Absent = auto: an openai-chat provider on a loopback base_url is local. */
    val local: Boolean? = null,
    /** 2026-09-22: where this provider publishes its model list, when that is not where its dialect
     *  says ([UpstreamRosterUrl]). Read by the daemon at start, to discover the models its heads
     *  offer, and by `splice models` — never by a turn. It exists so the one vendor whose list sits
     *  off its own base_url (DeepSeek serves `/models` at the API root while splice dials its
     *  `/anthropic` base) needs no entry in a per-vendor table: a hardcoded vendor table is precisely
     *  the hand-authored list discovery exists to retire. */
    @SerialName("models_url") val modelsUrl: String? = null,
    /** 2026-09-22: which of the models this provider's endpoint publishes join its heads' pickers
     *  beyond the [models] declared here. Absent = every published model that can run a turn. */
    val discovery: ModelDiscoveryConfig = ModelDiscoveryConfig(),
) {
    /** Whether this provider is a local runtime: what the operator said, else the loopback rule. */
    public val isLocal: Boolean
        get() = local ?: LocalProviderRule().isLocalByDefault(dialect, baseUrl)

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
        if (quirks.codeMode == true) {
            require(codeModeShape) { codeModeRefusal() }
        }
        quirks.codeModeModels?.let { models ->
            require(models.isNotEmpty() && models.none(String::isBlank)) {
                "code_mode_models must list at least one non-blank model id"
            }
        }
    }

    /** True when this provider's auth kind declares code mode on this dialect in the registry. */
    private val codeModeShape: Boolean
        get() = AuthKindRegistry.from(auth.kind)?.codeModeDialect == dialect

    /** Code mode graduated in 0.4.0 (Marcos, 2026-09-13): ON by default for the registry shape,
     *  `code_mode = false` still turns it off, and every other provider shape stays off. */
    public val codeModeEnabled: Boolean
        get() = quirks.codeMode ?: codeModeShape

    private fun codeModeRefusal(): String {
        val named = AuthKindRegistry.knownKinds()
            .filter { it.codeModeDialect != null }
            .joinToString("; ") { kind ->
                val dialectName = DialectWires.name(checkNotNull(kind.codeModeDialect))
                "auth.kind = '${kind.wire}' and dialect = '$dialectName'"
            }
        return "code_mode is only supported with $named"
    }

    public val staticHeaders: Map<String, String>
        get() = extraHeaders.mapKeys { (key, _) -> key.trim('"') }

    /** V4-162: this provider without its windows (see [Topology.withoutWindows]). extra_windows and
     *  window_rules declare windows and nothing else, so they are dropped whole: a row added to or
     *  removed from either is a window edit too. */
    public fun withoutWindows(): ProviderConfig = copy(
        models = models.map { it.copy(contextWindow = 0) },
        extraWindows = emptyList(),
        windowRules = emptyList(),
        defaultContextWindow = 0,
    )

    /** A catalog is the JOIN of this provider's models with the head's [HeadConfig.discoveryPrefix]
     *  — which is why it lives on the provider and takes the head, and why the two types stay in one
     *  file. A non-empty [HeadConfig.models] is an ordered per-head allowlist; an absent list preserves
     *  the provider-wide surface for older topologies. [contextWindowOverride] wins over the declared
     *  per-head window and, when positive, replaces the window on every selected entry.
     *
     *  2026-09-22: [discovered] is what the provider's list endpoint published at daemon start. The
     *  provider's surface is its declared rows, then every published model no declared row already
     *  covers ([rosterWith]); an allowlist may name either kind. Empty = the declared rows alone,
     *  exactly the catalog every topology produced before discovery. */
    public fun catalogFor(
        head: HeadConfig,
        contextWindowOverride: Long? = null,
        discovered: List<DiscoveredModel> = emptyList(),
    ): ModelCatalog {
        val selectedModels = withHeadRates(modelsFor(head, rosterWith(discovered)), head.rates)
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
                selectedModels.map { it.copy(contextWindow = headWindow(it, window, discovered)) }
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
            headWindow = window,
        )
    }

    /** Folds a head's own card over the provider entries it names (V4-37).
     *
     *  An OVERRIDE, never a replacement roster: an id the head does not name keeps the provider's
     *  rates untouched, so declaring one tier's markup cannot silently strip the card from every
     *  other model on the head. A null map is the no-op that keeps every existing topology
     *  byte-identical — the whole point of NEVER-BELOW-STATUS-QUO. */
    private fun withHeadRates(entries: List<ModelEntry>, rates: Map<String, ModelRates>?): List<ModelEntry> {
        if (rates == null) return entries
        return entries.map { entry -> rates[entry.id]?.let { rate -> entry.copy(rates = rate) } ?: entry }
    }

    /** The declared rows, then each [discovered] model that no declared row covers under any of its
     *  spellings (a `[1m]` row covers its bare id, an alias row covers the model it aliases) and that
     *  [discovery] admits, in the endpoint's order. A declared row always wins its model: its window,
     *  label, rates and place carry decisions no endpoint can supply. */
    private fun rosterWith(discovered: List<DiscoveredModel>): List<ModelEntry> {
        val extra = undeclared(discovered)
            .filter { discovery.admits(it.id) }
            .map { ModelEntry(id = it.id, label = it.label, contextWindow = windowFor(it), discovered = true) }
        return models + extra
    }

    /** The [discovered] models no declared row covers, once each, before [discovery] filters them —
     *  the models that filter decides about, which is how daemon.log counts what it kept out. */
    public fun undeclared(discovered: List<DiscoveredModel>): List<DiscoveredModel> {
        val covered = models.mapTo(HashSet()) { ModelTierSuffix.strip(it.id) }
        return discovered
            .filter { model -> model.id.isNotBlank() && model.spellings.none { it in covered } }
            .distinctBy { it.id }
    }

    /** A discovered row's window: what the operator declared for that exact id ([extraWindows]), else
     *  what the endpoint publishes — the model's real ceiling, the fact discovery exists to learn —
     *  else the provider's prefix rules and default, else the floor every undeclared window uses. */
    private fun windowFor(model: DiscoveredModel): Long =
        extraWindows.firstOrNull { it.id == model.id }?.contextWindow
            ?: model.contextWindow?.takeIf { it > 0 }
            ?: windowRules.firstOrNull { model.id.startsWith(it.prefix) }?.contextWindow
            ?: defaultContextWindow.takeIf { it > 0 }
            ?: DEFAULT_WINDOW_FLOOR

    /** The pinned model's own row, windowed like any undeclared id. Not a discovered row: the
     *  operator named it, so it is the head's tier model too. */
    private fun pinnedOnly(id: String): ModelEntry = ModelEntry(id = id, contextWindow = windowFor(DiscoveredModel(id)))

    /** Whether an endpoint can list this provider's models at all: a local runtime and a provider
     *  that forwards the client's own login are never asked, and `exclude = ["*"]` admits nothing.
     *  Where none can, a model no row declares is a misspelling, never an endpoint's omission. */
    private val listsModels: Boolean
        get() = !isLocal && auth.kind != AuthKind.Client.wire && "*" !in discovery.exclude

    /** [roster] with a row for [pinned] when no row serves it under its own spelling. A provider that
     *  lists no models keeps the pre-discovery rule: its declared rows are the catalog, and only an
     *  empty one gains the pinned row. */
    private fun withPinned(roster: List<ModelEntry>, pinned: String): List<ModelEntry> {
        val bare = ModelTierSuffix.strip(pinned)
        val served = roster.any { ModelTierSuffix.strip(it.id) == bare }
        val declaredOnly = roster.isNotEmpty() && !listsModels
        return if (served || declaredOnly) roster else roster + pinnedOnly(pinned)
    }

    /** A head-wide window replaces a declared row's window, which is the operator's number for this
     *  head. It never raises a model this provider does not declare past the ceiling the endpoint
     *  published for it: that ceiling is the backend's fact, and a window above it compacts past what
     *  the backend accepts (a 1M head window over a 262k model). An unpublished window takes the head's. */
    private fun headWindow(entry: ModelEntry, window: Long, discovered: List<DiscoveredModel>): Long {
        if (models.any { it.id == entry.id }) return window
        val published = discovered.firstOrNull { it.id == entry.id }?.contextWindow?.takeIf { it > 0 }
        return published?.let { minOf(window, it) } ?: window
    }

    private fun modelsFor(head: HeadConfig, roster: List<ModelEntry>): List<ModelEntry> {
        // The head's pinned model always has a row (2026-09-23): it is the one model the operator
        // named, and a catalog without it refuses every turn the head was launched to serve. With no
        // declared rows the roster is whatever the endpoint listed, which can omit the pinned id — a
        // retired model, a discovery filter, an alias the roster does not spell, a start it missed.
        val requested = head.models ?: return withPinned(roster, head.pinnedModel)
        require(requested.isNotEmpty()) { "head model list must not be empty" }
        require(requested.map { it.id }.distinct().size == requested.size) { "head model list contains duplicates" }
        val slots = requested.mapNotNull { it.slot?.lowercase() }
        require(slots.all { it in headModelSlots }) { "unknown Claude model slot" }
        require(slots.distinct().size == slots.size) { "head model slots contain duplicates" }
        val byId = roster.associateBy(ModelEntry::id)
        // A row the head's allowlist names is the operator's decision, whichever list supplied it, so
        // it is DECLARED for this head: it keeps the allowlist's order and may stand behind a tier.
        // An id the roster lacks is not served at this start. Where an endpoint could have listed it,
        // that is the endpoint's doing (retired, filtered, or not answered in time) and the row is
        // dropped, never the head; HeadBoot names it in daemon.log. Where nothing could have listed it,
        // it is a misspelling, refused as it was before discovery existed.
        val selected = requested.mapNotNull { model ->
            byId[model.id]?.copy(discovered = false) ?: run {
                require(listsModels) {
                    "head model '${model.id}' is not declared by provider '${head.provider}', which lists no models"
                }
                pinnedOnly(model.id).takeIf { model.id == head.pinnedModel }
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
// TopologySchema.kt (concentration, 2026-08-19); HeadModel / HeadConfig in HeadConfig.kt
// (concentration, 2026-09-23). Same-package FQCNs are unchanged.

private const val DEFAULT_WINDOW_FLOOR: Long = 200_000
private val headModelSlots = setOf("opus", "sonnet", "haiku", "fable")

/** What a control-plane port collision names as its owner in [Topology.portCollisions] — the
 *  dotted form [DaemonConfig.controlPort]'s own TOML key, so the report points at where to look
 *  and cannot be confused with a head key. */
private const val CONTROL_PLANE_OWNER: String = "daemon.controlPort"
