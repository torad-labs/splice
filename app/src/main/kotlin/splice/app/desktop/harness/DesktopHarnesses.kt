// NEW: desktop-harnesses. Which program a Claude Desktop session runs on, for every model prefix splice serves: each
// family with a choice (HarnessFamily) resolved from the console's choice, else splice.toml (`[daemon] antigravity`),
// else splice's default; every other provider in splice.toml on Claude Code through Splice. The map is written to
// `<state>/desktop-harnesses.json` at the daemon's start and after each console change, so a Desktop session launcher
// reads one file for every decision, without the management key:
//
//   { "version": 1, "default_harness": "claude-code",
//     "harnesses": [{ "id", "name", "description" }, ...],
//     "families":  [{ "family", "harness", "enabled", "set_by": "default" | "splice.toml" | "console" }, ...],
//     "switches":  [{ "prefix", "provider", "family", "harness", "enabled" }, ...] }
//
// A launcher matches a session's model on the longest `prefix`, and runs it on `harness` while `enabled`; a model no
// prefix matches runs on `default_harness`. Turning Antigravity on or off takes effect when the daemon starts again,
// which is when it asks agy (AntigravityJoin); the GPT harness is read by the launcher at each session.
package splice.app.desktop.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.ClientModelNames
import splice.core.topology.gateway.GatewaySelectorPrefixes
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.antigravity.ANTIGRAVITY_KEY
import splice.provider.antigravity.ANTIGRAVITY_MODEL_PREFIX
import java.io.IOException

/** Who decided a family's harness. */
internal enum class HarnessSetBy(val wire: String) {
    DEFAULT("default"),
    TOML("splice.toml"),
    CONSOLE("console"),
}

/** A family as it resolves now: its [choice], who made it, and the choice it has with no console choice. */
internal data class FamilyState(
    val family: HarnessFamily,
    val choice: HarnessChoice,
    val setBy: HarnessSetBy,
    val default: HarnessChoice,
)

/** One model prefix and the harness a session on it runs on. [family] is null for a provider with no choice. */
internal data class HarnessSwitch(
    val prefix: String,
    val provider: String,
    val family: HarnessFamily?,
    val harness: DesktopHarness,
    val enabled: Boolean,
)

/** The harness map over [file]: [providers] is each provider in splice.toml by key with its family
 *  (ProviderFamilyRule), [antigravity] splice.toml's `[daemon] antigravity`, and what goes wrong is said to [log]. */
internal class DesktopHarnesses(
    val file: DesktopHarnessFile,
    private val providers: Map<String, String?>,
    private val antigravity: Boolean?,
    private val log: LogSink,
) {
    /** Whether the daemon asks agy, as [boot] read it at the daemon's start; null before. */
    @Volatile
    var bootedAntigravity: Boolean? = null
        private set

    /** Whether Antigravity is on as the daemon starts, read once and kept for [restartPending]. An unreadable file
     *  counts for nothing, so splice.toml and the default decide. */
    fun boot(): Boolean = enabled(states(choicesOf(file.read())), HarnessFamily.ANTIGRAVITY).also {
        bootedAntigravity = it
    }

    /** Whether [states] differ from what the daemon started with in a way only a restart applies. */
    fun restartPending(states: List<FamilyState>): Boolean =
        bootedAntigravity?.let { it != enabled(states, HarnessFamily.ANTIGRAVITY) } ?: false

    /** Every family resolved under the console's [choices]. */
    fun states(choices: Map<HarnessFamily, HarnessChoice>): List<FamilyState> = HarnessFamily.entries.map { family ->
        val (default, setBy) = based(family)
        val chosen = choices[family]
        FamilyState(family, chosen ?: default, if (chosen != null) HarnessSetBy.CONSOLE else setBy, default)
    }

    /** Every prefix splice serves under [states], longest match first being the reader's rule. */
    fun switches(states: List<FamilyState>): List<HarnessSwitch> {
        val byFamily = states.associateBy { it.family }
        val served = providers.entries.flatMap { (key, family) ->
            val chosen = HarnessFamily.entries.firstOrNull { it.family != null && it.family == family }
            val state = chosen?.let(byFamily::get)
            val canonical = GatewaySelectorPrefixes.canonical(key)
            listOf(canonical, ClientModelNames.prefixOf(canonical)).map { prefix ->
                val choice = state?.choice ?: HarnessChoice(DesktopHarness.CLAUDE_CODE, enabled = true)
                HarnessSwitch(prefix, key, state?.family, choice.harness, choice.enabled)
            }
        }
        val agy = byFamily.getValue(HarnessFamily.ANTIGRAVITY).choice
        val desktopOnly = HarnessSwitch(
            ANTIGRAVITY_MODEL_PREFIX,
            ANTIGRAVITY_KEY,
            HarnessFamily.ANTIGRAVITY,
            agy.harness,
            agy.enabled,
        )
        return (served + desktopOnly).distinctBy { it.prefix }
    }

    /** The file's whole body under [states]. */
    fun document(states: List<FamilyState>): JsonObject = buildJsonObject {
        put(HARNESS_KEY_VERSION, HARNESS_FILE_VERSION)
        put("default_harness", DesktopHarness.CLAUDE_CODE.wire)
        put("harnesses", JsonArray(DesktopHarness.entries.map(::harnessJson)))
        put(HARNESS_KEY_FAMILIES, JsonArray(states.map(::familyJson)))
        put("switches", JsonArray(switches(states).map(::switchJson)))
    }

    /** The map written again as it resolves now, at the daemon's start; a file that can't be read is left as it is. */
    fun publish() {
        when (val read = file.read()) {
            is HarnessFileRead.Unreadable -> log("[desktop] harnesses: ${read.why}; left as it is\n")
            is HarnessFileRead.Held -> saveQuietly(read.choices)
        }
    }

    /** The map under the console's [choices] written as the whole file, unless it already reads so; throws
     *  [IOException] when it could not be written. */
    fun save(choices: Map<HarnessFamily, HarnessChoice>) {
        val document = document(states(choices))
        if (file.text() == file.render(document)) return
        file.write(document)
    }

    /** [save], with a failure said to [log] rather than thrown: for the daemon's start, which serves on regardless. */
    private fun saveQuietly(choices: Map<HarnessFamily, HarnessChoice>) {
        try {
            save(choices)
        } catch (failure: IOException) {
            log("[desktop] harnesses were not written: ${SafeFailureText.render(failure)}\n")
        }
    }

    /** The console's choices in [read]; none when the file can't be read. */
    fun choicesOf(read: HarnessFileRead): Map<HarnessFamily, HarnessChoice> =
        (read as? HarnessFileRead.Held)?.choices.orEmpty()

    /** [family]'s choice with no console choice, and who made it. */
    private fun based(family: HarnessFamily): Pair<HarnessChoice, HarnessSetBy> = when {
        family == HarnessFamily.ANTIGRAVITY && antigravity != null ->
            HarnessChoice(family.default, antigravity) to HarnessSetBy.TOML
        else -> HarnessChoice(family.default, enabled = true) to HarnessSetBy.DEFAULT
    }

    private fun enabled(states: List<FamilyState>, family: HarnessFamily): Boolean =
        states.first { it.family == family }.choice.enabled

    private fun harnessJson(harness: DesktopHarness): JsonObject = buildJsonObject {
        put("id", harness.wire)
        put("name", harness.title)
        put("description", harness.description)
    }

    private fun familyJson(state: FamilyState): JsonObject = buildJsonObject {
        put(HARNESS_KEY_FAMILY, state.family.wire)
        put(HARNESS_KEY_HARNESS, state.choice.harness.wire)
        put(HARNESS_KEY_ENABLED, state.choice.enabled)
        put(HARNESS_KEY_SET_BY, state.setBy.wire)
    }

    private fun switchJson(switch: HarnessSwitch): JsonObject = buildJsonObject {
        put("prefix", switch.prefix)
        put("provider", switch.provider)
        put(HARNESS_KEY_FAMILY, switch.family?.wire?.let(::JsonPrimitive) ?: JsonNull)
        put(HARNESS_KEY_HARNESS, switch.harness.wire)
        put(HARNESS_KEY_ENABLED, switch.enabled)
    }
}
