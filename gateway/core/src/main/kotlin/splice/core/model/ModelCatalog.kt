// PORT-OF: server/src/models/codex-models.mjs + grok-models.mjs @ pre-public-port-baseline — invariants:
// context windows resolve EXACT match -> ordered startsWith prefix rules -> default
// (never substring: the v29 fuzzy pass silently inherited windows and hid catalog gaps);
// discovery wrap because Claude Code drops /v1/models ids not matching /^(claude|anthropic)/i;
// stripSuffixes removes the discovery prefix and a trailing numeric tier hint ("[1m]", "[500k]" —
// the [<digits><k|m>] grammar, case-insensitive, DR-27);
// discovery rows carry display_name; availableModelIds stay UNWRAPPED (a wrapped active
// model makes Claude Code ignore the context-window env and compact early).
package splice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Claude Code returns for a "[1m]" id — the literal it hardcodes, NOT 1024*1024. */
private const val CLAUDE_CODE_ONE_MILLION = 1_000_000L

/** Claude Code's own id-keyed window hook, matched anywhere in the client id. */
// UNANCHORED on purpose, unlike the strip rule (DR-27): this predicate exists to predict what the
// CLIENT will do, and Claude Code's own detection is a containsMatch — `/\[1m\]/i` (cli 2.1.233
// `G4u`) — anywhere in the id. The anchored version disagreed with the client for a mid-string
// spelling ("k3[1m]-preview"): the client used 1e6 while usageScale corrected against the pinned
// window, a silently wrong factor. Mirror the client, never improve on it.
private val oneMillionHint = Regex("\\[1m]", RegexOption.IGNORE_CASE)

/** Claude Code honors CLAUDE_CODE_MAX_CONTEXT_TOKENS only for an active id NOT starting with this
 *  (cli 2.1.257 `kL`: `!id.startsWith("claude-") && !knownAlias(id)`); any other id resolves to
 *  the client's built-in table or its 200k default, and nothing we launch with can move it. */
private const val CLIENT_OWN_ID_PREFIX = "claude-"

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
    /** The head's pinned model (ANTHROPIC_MODEL at launch). Since 2026-09-05 it does NOT name the
     *  client's window — every launch plants [clientLaunchWindow] — so nothing in here reads it;
     *  the head assembly and the launch spec still carry it. */
    val pinnedModel: String = "",
) {
    init {
        require(models.isNotEmpty()) { "a catalog needs at least one picker model" }
        require(discoveryPrefix.isNotEmpty()) { "discovery prefix is the picker namespace — never empty" }
    }

    public val defaultModel: String get() = models.first().id

    // Numeric bracketed tier hints, not arbitrary trailing brackets: a provider that ships ONE id
    // per model (xAI — grok-4.6 IS 500k) can offer two picker rows over one upstream id, while a
    // genuine vendor id such as model[preview] still reaches that provider byte-for-byte.
    private val suffixHint = Regex("\\[\\d+[km]]$", RegexOption.IGNORE_CASE)

    // Canonical (suffix-stripped) ids — `contains` and `contextWindowFor` both strip the query the
    // same way. Storing the RAW picker id (e.g. "k3[1m]") let membership pass after the contains
    // fix while contextWindowFor("k3[1m]") looked up stripped "k3" and missed → default 256k window
    // / early autocompact (residual of the [1m] membership fix).
    private val exactWindows: Map<String, Long> =
        models.associate { stripSuffixes(it.id) to it.contextWindow } +
            extraWindows.associate { stripSuffixes(it.id) to it.contextWindow }

    // RAW picker ids, consulted BEFORE the stripped map. Two rows over one upstream id collapse to
    // one stripped key, so the stripped map alone cannot tell "grok-4.6" (capped) from
    // "grok-4.6[500k]" — whichever row was declared last would win both. Raw-first keeps each row's
    // own window while the stripped map still answers the bare upstream id every wire path uses.
    private val rawWindows: Map<String, Long> =
        models.associate { unwrap(it.id) to it.contextWindow } +
            extraWindows.associate { unwrap(it.id) to it.contextWindow }

    // Canonical (suffix-stripped) ids — `contains` strips its query the same way, so both sides
    // compare on the upstream id. Storing the RAW id here let a "[1m]" picker model (kimi k3[1m])
    // never match its own stripped upstream id "k3" → every k3 turn 400'd "proxies its own models
    // only" (regression from the contains guard's introduction; no [1m] catalog test caught it).
    private val modelIds: Set<String> = models.mapTo(HashSet()) { stripSuffixes(it.id) }

    public fun wrap(id: String): String = discoveryPrefix + id

    public fun unwrap(id: String): String = id.removePrefix(discoveryPrefix)

    /** True only for a picker model owned by this head (wrapped or upstream id). */
    public fun contains(id: String): Boolean = stripSuffixes(id) in modelIds

    /** Discovery wrapper + any valid trailing numeric tier ("[1m]", "[500k]") stripped — what the
     *  upstream actually sees. Only the [<digits><k|m>] grammar strips (DR-27): a non-numeric
     *  bracket and a malformed tier ride to the wire byte-for-byte. */
    public fun stripSuffixes(id: String): String = unwrap(id).replace(suffixHint, "")

    /** Exact -> ordered startsWith prefix rules -> default. Order is the law. */
    public fun contextWindowFor(model: String?, defaultOverride: Long? = null): Long {
        val fallback = defaultOverride?.takeIf { it > 0 } ?: defaultContextWindow
        if (model.isNullOrEmpty()) return fallback
        val id = stripSuffixes(model)
        // An UNDECLARED suffixed variant ("grok-4.6[1m]" over rows grok-4.6 + grok-4.6[500k])
        // resolves to the stripped id's OWN row, not through exactWindows — that map collapses
        // colliding rows by associate-last-wins, so the undeclared tier's denominator moved with
        // fixture declaration order (DR-24 redo). exactWindows still answers a bare id declared
        // only via suffixed rows (k3 <- k3[1m]), where no raw row exists to prefer.
        return rawWindows[unwrap(model)]
            ?: rawWindows[id]
            ?: exactWindows[id]
            ?: windowRules.firstOrNull { id.startsWith(it.prefix) }?.contextWindow
            ?: fallback
    }

    /** The ONE window every launch declares to the client (CLAUDE_CODE_MAX_CONTEXT_TOKENS): a
     *  constant, deliberately the number Claude Code hardcodes for a "[1m]" id, so the client-side
     *  denominator is fixed for the life of the process however the catalog changes. [usageScale]
     *  then carries EVERY row, the pinned one included, against its declared window — which is
     *  what lets a TOML window edit + `splice restart` reach a RUNNING session on its next turn.
     *  Before 2026-09-05 the pinned row's own window was the launch env, so editing it was
     *  invisible to every live process: the daemon computed a 1.0 factor against the new number
     *  while the client kept dividing by the old one (the 2026-09-05 272k cutover: six sessions
     *  stayed on 400k until relaunched). */
    public val clientLaunchWindow: Long get() = CLAUDE_CODE_ONE_MILLION

    /** The window the CLIENT will actually use for [id] — mirrored from cli 2.1.257 `PL()`, never
     *  improved on: `/\[1m\]/i` anywhere in the id -> exactly 1e6; an id starting with "claude-"
     *  (a discovery-wrapped tier, or a passthrough head's native model) ignores our env and
     *  resolves to the client's own table or its 200k default, so the DECLARED window is returned
     *  and the counts ride raw — a factor we cannot honestly compute is 1.0; every other id ->
     *  [clientLaunchWindow], the env the launch planted. */
    public fun clientContextWindowFor(id: String): Long = when {
        oneMillionHint.containsMatchIn(unwrap(id)) -> CLAUDE_CODE_ONE_MILLION
        id.startsWith(CLIENT_OWN_ID_PREFIX) -> contextWindowFor(id)
        else -> clientLaunchWindow
    }

    /** Multiplier for the input-token counts reported to the client, so a row compacts at ITS OWN
     *  declared window rather than the session's.
     *
     *  We are a proxy: Claude Code compacts on `(input + cache_creation + cache_read) / window`, and
     *  splice authors the NUMERATOR of that ratio even though the denominator is fixed in the
     *  client's process. Scaling the numerator by `client/declared` makes the ratio reach 1 exactly
     *  when real usage reaches the declared window — so a 500k row on a 256k session compacts at
     *  500k, live, switchable from the /model menu. With the client window a constant (see
     *  [clientLaunchWindow]) every row scales, the pinned one included; 1.0 (untouched counts) is
     *  left only where the client's window is genuinely not ours: a declared 1e6 row and the
     *  "claude-" ids [clientContextWindowFor] names. */
    public fun usageScale(id: String): Double {
        val declared = contextWindowFor(id)
        // NO "[1m]" exemption, deliberately. `contains()` strips the suffix before its membership
        // test, so an UNDECLARED tier id — `grok-4.6[1m]`, which exists in no catalog — passes the
        // "proxies its own models only" gate, and Claude Code applies its own /\[1m\]/i rule to
        // whatever string it holds. Exempting those from scaling let a 500k model run toward 1e6
        // and hard-fail upstream. Scaling them instead makes the client's 1e6 land on the stripped
        // id's real window. A DECLARED 1e6 row needs no special case: client and declared are both
        // 1e6, so this arithmetic already returns exactly 1.0.
        val client = clientContextWindowFor(id)
        if (declared <= 0 || client <= 0) return 1.0
        return client.toDouble() / declared
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
