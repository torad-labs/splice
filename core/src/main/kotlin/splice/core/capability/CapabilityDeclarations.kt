// NEW: what a capability table must say for splice to boot (docs/specs/harness.md, decision 1). Every wrong key or
// value is a finding of its own, all listed at once. A finding names its capability and the key or value at fault,
// and a value only when it is a name (a head, a field, a built-in worker, a key): a url or a message can hold a secret.
package splice.core.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.topology.TopologyFinding

/** The built-in workers splice ships, by name: the Context Getter's is the first (decision 9). */
public object CapabilityBuiltins {
    /** The Context Getter: the head's fast model explores the session's folder before its first turn. */
    public const val CONTEXT_GETTER: String = "context-getter"

    public val names: Set<String> = setOf(CONTEXT_GETTER)

    /** How long a built-in worker may take when its declaration names no `timeout_s`, or null for every
     *  capability's own default. The Context Getter's is set from gathers measured on real work: 72 to 101 s on
     *  splice's own repo, on Haiku at its own level (November 17, 2026), so 180 s lets the slowest of those finish
     *  with room and ends one that is stuck within three minutes (docs/specs/harness.md, Seen with Claude Code). */
    public fun timeoutS(name: String): Int? = if (name == CONTEXT_GETTER) GATHER_TIMEOUT_S else null
}

/** why: see [CapabilityBuiltins.timeoutS]. */
private const val GATHER_TIMEOUT_S = 180

/** What the capability tables declare: those that are well-formed, in file order, and a finding for each fault. */
public data class CapabilityRead(val capabilities: List<Capability>, val findings: List<TopologyFinding>)

/** Reads [tables] against the heads splice.toml declares; [heads] null leaves that check out (a file that did not
 *  decode has no heads to check against). */
public class CapabilityDeclarations(
    private val heads: Set<String>?,
    private val builtins: Set<String> = CapabilityBuiltins.names,
    /** The heads whose dialect is Anthropic's Messages, the only ones the model may call a capability on (decision
     *  12); null leaves that check out. */
    private val messages: Set<String>? = null,
) {
    public fun read(tables: List<CapabilityTable>): CapabilityRead {
        val checks = tables.map { CapabilityCheck(CapabilityValues(it), heads, builtins, messages) }
        return CapabilityRead(checks.mapNotNull { it.capability }, checks.flatMap { it.findings })
    }
}

/** One table's reading: [capability] when it is well-formed, else null, with its [findings]. */
private class CapabilityCheck(
    private val values: CapabilityValues,
    private val heads: Set<String>?,
    private val builtins: Set<String>,
    private val messages: Set<String>?,
) {
    private val keys = values.table.keys
    private val name = values.table.name
    val capability: Capability? = read()
    val findings: List<TopologyFinding> get() = values.findings

    private fun read(): Capability? {
        shape()
        val worker = CapabilityWorkerCheck(values, builtins).read()
        val rule = CapabilityRuleCheck(values).read()
        val result = result()
        if (rule?.turn == CapabilityTurn.ANY && result?.place == CapabilityPlace.FIRST_USER_MESSAGE) {
            values.fault(WHEN_TURN_KEY, EVERY_TURN_PLACES)
        }
        val heads = heads()
        val call = CapabilityCallCheck(values, messages).read(heads).copy(rule = rule ?: CapabilityRule())
        val capability = Capability(
            name = name,
            heads = heads,
            result = result ?: CapabilityResult(),
            notice = notice(),
            limits = limits(worker),
            work = CapabilityWork(worker ?: CapabilityWorker.Builtin(""), send()),
            call = call,
        )
        return capability.takeIf { values.findings.isEmpty() && worker != null }
    }

    private fun notice() = CapabilityNotice(
        values.text("message") ?: name,
        values.choice("color", keys["color"], COLORS, DEFAULT_COLOR) ?: DEFAULT_COLOR,
    )

    private fun limits(worker: CapabilityWorker?) =
        CapabilityLimits(timeout(worker), values.choice("on_failure", keys["on_failure"], FAILURES, OPEN) ?: OPEN)

    private fun shape() {
        if (!NAME.matches(name)) values.fault("", "a capability's name is lowercase letters, digits and -")
        if (values.table.listed) values.fault("", "is a list of tables; a capability is one [capabilities.$name] table")
        if (CapabilityToml.WHOLE in keys) values.fault("", "must be a table")
        val unknown = keys.keys.filter { it !in KEYS && it != CapabilityToml.WHOLE }
        unknown.forEach { values.fault(it, "is not a key a capability has") }
    }

    /** `place` and `may`, which must fit: a place of none carries no text, so it may only refuse. */
    private fun result(): CapabilityResult? {
        val place = values.choice(PLACE, keys[PLACE], PLACES, CapabilityPlace.FIRST_USER_MESSAGE)
        val may = values.choice("may", keys["may"], MAYS, CapabilityMay.ADD)
        if (place == CapabilityPlace.NONE && may in ADDING) {
            values.fault(PLACE, "is none, which carries no text, so may must be refuse")
        }
        return if (place != null && may != null) CapabilityResult(place, may) else null
    }

    private fun heads(): List<String> {
        val names = values.names("heads", "a list of head keys") ?: return emptyList()
        val unknown = heads?.let { known -> names.filter { it !in known } }.orEmpty()
        unknown.forEach {
            values.fault(
                "heads",
                "names head \"${values.safe(it)}\", which splice.toml does not declare",
            )
        }
        return names
    }

    private fun send(): List<CapabilityField> {
        val names = values.names("send", "a list of field names") ?: return emptyList()
        return names.mapNotNull { field ->
            FIELDS[field] ?: null.also {
                values.fault(
                    "send",
                    "names \"${values.safe(field)}\", which is not a field; fields are ${FIELDS.keys.joinToString()}",
                )
            }
        }
    }

    /** `timeout_s`, or where it is absent the built-in worker's own default, else every capability's. */
    private fun timeout(worker: CapabilityWorker?): Int {
        val builtin = (worker as? CapabilityWorker.Builtin)?.name
        val default = builtin?.let(CapabilityBuiltins::timeoutS) ?: DEFAULT_TIMEOUT_S
        val value = keys["timeout_s"] ?: return default
        val seconds = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (seconds != null && seconds in TIMEOUT_RANGE) return seconds.toInt()
        values.fault(
            "timeout_s",
            "must be a whole number of seconds from ${TIMEOUT_RANGE.first} to ${TIMEOUT_RANGE.last}",
        )
        return DEFAULT_TIMEOUT_S
    }
}

/** The `when` table. */
private class CapabilityRuleCheck(private val values: CapabilityValues) {
    fun read(): CapabilityRule? {
        val keys = values.table.keys["when"] as? JsonObject ?: return absentOrWrong()
        keys.keys.filter { it !in RULE_KEYS }.forEach { values.fault("when.$it", "is not a key a rule has") }
        val turn = values.choice(WHEN_TURN_KEY, keys["turn"], TURNS, CapabilityTurn.FIRST)
        val thread = values.choice("when.thread", keys["thread"], THREADS, CapabilityThread.MAIN)
        val mode = values.choice("when.mode", keys["mode"], MODES, CapabilityMode.INTERACTIVE)
        val models = keys["models"]?.let {
            values.strings(it)?.takeIf(List<String>::isNotEmpty) ?: emptyList<String>().also { wrongModels() }
        }
        val chosen = listOf(turn, thread, mode).all { it != null } && models?.isEmpty() != true
        return if (chosen) {
            CapabilityRule(
                requireNotNull(turn),
                requireNotNull(thread),
                requireNotNull(mode),
                models,
            )
        } else {
            null
        }
    }

    private fun absentOrWrong(): CapabilityRule? {
        if (values.table.keys["when"] == null) return CapabilityRule()
        values.fault("when", "must be a table of turn, thread, mode and models")
        return null
    }

    private fun wrongModels() = values.fault("when.models", "must be a non-empty list of model ids")
}

private val NAME = Regex("[a-z0-9-]+")
private val KEYS =
    setOf(
        "heads", "call", "tool", "when", "place", "may", "message", "color",
        "timeout_s", "on_failure", "send", "worker",
    )
private val RULE_KEYS = setOf("turn", "thread", "mode", "models")
private val TURNS = CapabilityTurn.entries.associateBy { it.wire }
private val THREADS = CapabilityThread.entries.associateBy { it.wire }
private val MODES = CapabilityMode.entries.associateBy { it.wire }
private val PLACES = CapabilityPlace.entries.associateBy { it.wire }
private val MAYS = CapabilityMay.entries.associateBy { it.wire }
private val FAILURES = CapabilityFailure.entries.associateBy { it.wire }
private val FIELDS = CapabilityField.entries.associateBy { it.wire }
private val OPEN = CapabilityFailure.OPEN

/** The colour names a capability may declare: the ones the status line can draw. */
private val COLORS = CapabilityColors.escapes.keys.associateWith { it }

// why: an hour, the longest hold seen to draw no retry and no second request from Claude Code (2.1.326, a worker
// that answered after 59 min 50 s; docs/specs/harness.md, Seen with Claude Code).
private const val MAX_TIMEOUT_S = 3600L
private val TIMEOUT_RANGE = 1L..MAX_TIMEOUT_S

// why: the minute decision 1 of docs/specs/harness.md declares in its example. A capability whose worker needs
// longer names its own `timeout_s`, and a built-in worker has its own default (CapabilityBuiltins.timeoutS).
private const val DEFAULT_TIMEOUT_S = 60
private const val DEFAULT_COLOR = "cyan"
private const val WHEN_TURN_KEY = "when.turn"
private const val EVERY_TURN_PLACES =
    "is any, and only a first turn places text in the first user message; set place to none"

/** The `place` key of a capability table, which CapabilityCallCheck reads too. */
internal const val PLACE = "place"
private val ADDING = setOf(CapabilityMay.ADD, CapabilityMay.ADD_OR_REFUSE)
