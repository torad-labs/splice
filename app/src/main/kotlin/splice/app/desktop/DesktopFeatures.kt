// NEW: desktop-full (docs/specs/desktop-full.md, outcome 6, df-features). Each Claude Desktop feature that depends on the
// backend, in each of Desktop's modes, and whether it works on splice: a reading of what splice knows of its heads and of
// Desktop's own rules in each mode. It changes no behaviour. "standard" is the Code tab alone on the door (an older
// splice's environment-store setup), "app" is all of Desktop on splice (third-party gateway mode). What only the live
// test on the real app can settle is "unknown" until it has.
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** One feature in one mode: [state] is works, partly, unavailable or unknown, and [reason] says why wherever it is
 *  not plainly working. */
internal data class DesktopFeature(val name: String, val mode: String, val state: String, val reason: String? = null)

internal class DesktopFeatures(private val lineup: DesktopLineup, private val traits: Map<String, HeadTraits>) {
    /** Every feature, standard mode's first, in the order Desktop's spec names them. */
    fun list(): List<DesktopFeature> = MODES.flatMap { mode -> NAMES.map { name -> of(name, mode) } }

    /** [list] as `splice desktop --json` gives it. */
    fun json(): JsonArray = buildJsonArray {
        list().forEach { f ->
            addJsonObject {
                put("name", f.name)
                put("mode", f.mode)
                put("state", f.state)
                put("reason", f.reason)
            }
        }
    }

    private fun of(name: String, mode: String): DesktopFeature {
        val (state, reason) = desktopRule(name, mode == APP) ?: headRule(name)
        return DesktopFeature(name, mode, state, reason)
    }

    /** What Desktop itself decides: in app mode what it turns off, and with the Code tab alone on splice what the study
     *  of Desktop left open; null for a feature the heads decide. */
    private fun desktopRule(name: String, app: Boolean): Pair<String, String?>? = when {
        name == SSH -> UNAVAILABLE to SSH_WHY
        name in APP_OFF -> if (app) UNAVAILABLE to APP_OFF.getValue(name) else UNKNOWN to UNSETTLED
        name == TITLES -> if (app) WORKS to null else UNKNOWN to UNSETTLED
        else -> null
    }

    /** What the heads in the door decide. */
    private fun headRule(name: String): Pair<String, String?> = when (name) {
        EFFORT -> effort()
        WEB -> web()
        FILES -> files()
        USAGE -> usage()
        else -> WORKS to null
    }

    /** Partly where a head's models top out below Desktop's "max", which then runs at that head's top rung. */
    private fun effort(): Pair<String, String?> {
        val catalog = DoorCatalog(lineup)
        val low = catalog.served
            .filter { it.fact.reasoningLevels.isNotEmpty() }
            .groupBy { it.head.key }
            .mapNotNull { (head, models) ->
                val top = models.mapNotNull(catalog::maxEffort).maxByOrNull(EFFORTS::indexOf)
                "$head tops out at ${top ?: "none of Desktop's levels"}".takeIf { top != MAX_EFFORT }
            }
        if (low.isEmpty()) return WORKS to null
        return PARTLY to "${low.joinToString("; ")}, so Desktop's higher levels run at that rung there"
    }

    /** Anthropic's server web tools are answered by the provider itself, so only heads on Anthropic's own wire do. */
    private fun web(): Pair<String, String?> = split(
        lineup.heads.filterNot { anthropic(it.key) }.map { it.key },
        "not answered on %s: Anthropic's server web search and fetch run only on heads on Anthropic's own API",
    )

    /** Images reach every head; files (PDFs) reach no head on OpenAI's chat wire, which drops them. */
    private fun files(): Pair<String, String?> {
        val chat = lineup.heads.filter { traits[it.key]?.dialect == CHAT_DIALECT }.map { it.key }
        if (chat.isEmpty()) return WORKS to null
        return PARTLY to "images reach every head, but ${chat.joinToString()} drop files such as PDFs " +
            "(OpenAI's chat API carries none)"
    }

    /** Desktop's plan-usage meter reads Anthropic's rate-limit headers, which door turns carry from a head's own plan
     *  windows; a head whose provider reports none leaves it empty. */
    private fun usage(): Pair<String, String?> = split(
        lineup.heads.filter { traits[it.key]?.usage?.snapshot()?.quota == null }.map { it.key },
        "no plan windows come from %s, so Desktop's usage meter has nothing to show there",
    )

    /** Works when [lacking] is empty, unavailable when every head lacks it, else partly; [why] names [lacking]. */
    private fun split(lacking: List<String>, why: String): Pair<String, String?> = when {
        lacking.isEmpty() -> WORKS to null
        lacking.size == lineup.heads.size -> UNAVAILABLE to why.format(lacking.joinToString())
        else -> PARTLY to why.format(lacking.joinToString())
    }

    private fun anthropic(head: String): Boolean = traits[head]?.dialect == ANTHROPIC_DIALECT
}

/** Desktop's modes as `splice desktop --json` names them. */
internal const val STANDARD = "standard"
internal const val APP = "app"
private val MODES = listOf(STANDARD, APP)

private const val TITLES = "titles"
private const val EFFORT = "effort"
private const val WEB = "web search and fetch"
private const val FILES = "images and files"
private const val FAST = "fast mode"
private const val REMOTE = "Remote Control and cloud environments"
private const val CONNECTORS = "claude.ai connectors"
private const val SSH = "SSH sessions"
private const val USAGE = "usage display"

// What Claude Desktop turns off in third-party mode, and how each is said.
private val APP_OFF = mapOf(
    FAST to "Claude Desktop turns fast mode off in third-party mode",
    REMOTE to "Claude Desktop offers neither in third-party mode",
    CONNECTORS to "Claude Desktop turns them off in third-party mode",
)

private const val SSH_WHY = "the door listens on 127.0.0.1 only, which a session on another machine can't reach"

// The features, in the order the spec names them (desktop-full, df-features).
private val NAMES = listOf(
    "auto mode", EFFORT, "context window and compaction", TITLES, "subagents", WEB, FILES, "MCP",
    "scheduled tasks", FAST, REMOTE, CONNECTORS, SSH, USAGE,
)

private const val WORKS = "works"
private const val PARTLY = "partly"
private const val UNAVAILABLE = "unavailable"
private const val UNKNOWN = "unknown"

// Why a standard-mode feature is unknown: the app study left it open, and only the live test settles it.
private const val UNSETTLED = "the study of Desktop left it open with the Code tab alone on splice, and the live " +
    "test on the real app has not settled it"

// Desktop's Effort levels, lowest first, and its highest.
private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
private const val MAX_EFFORT = "max"

// The wire dialects splice.toml names that decide what a head carries.
private const val ANTHROPIC_DIALECT = "anthropic-passthrough"
private const val CHAT_DIALECT = "openai-chat"
