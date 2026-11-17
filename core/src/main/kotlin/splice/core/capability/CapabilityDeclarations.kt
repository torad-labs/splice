// NEW: what a capability table must say for splice to boot (docs/specs/harness.md, decision 1). Every wrong key or
// value is a finding of its own, all listed at once. A finding names its capability and the key or value at fault,
// and a value only when it is a name (a head, a field, a built-in worker, a key): a url or a message can hold a secret.
package splice.core.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.topology.TopologyFinding

/** The built-in workers splice ships, by name. None yet: the Context Getter's is the first (decision 9). */
public object CapabilityBuiltins {
    public val names: Set<String> = emptySet()
}

/** What the capability tables declare: those that are well-formed, in file order, and a finding for each fault. */
public data class CapabilityRead(val capabilities: List<Capability>, val findings: List<TopologyFinding>)

/** Reads [tables] against the heads splice.toml declares; [heads] null leaves that check out (a file that did not
 *  decode has no heads to check against). */
public class CapabilityDeclarations(
    private val heads: Set<String>?,
    private val builtins: Set<String> = CapabilityBuiltins.names,
) {
    public fun read(tables: List<CapabilityTable>): CapabilityRead {
        val checks = tables.map { CapabilityCheck(CapabilityValues(it), heads, builtins) }
        return CapabilityRead(checks.mapNotNull { it.capability }, checks.flatMap { it.findings })
    }
}

/** One table's reading: [capability] when it is well-formed, else null, with its [findings]. */
private class CapabilityCheck(
    private val values: CapabilityValues,
    private val heads: Set<String>?,
    private val builtins: Set<String>,
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
            values.fault(TURN_KEY, EVERY_TURN_PLACES)
        }
        values.choice("call", keys["call"], CALLS, RULE)
        val capability = Capability(
            name = name,
            heads = heads(),
            rule = rule ?: CapabilityRule(),
            result = result ?: CapabilityResult(),
            notice = notice(),
            limits = limits(),
            work = CapabilityWork(worker ?: CapabilityWorker.Builtin(""), send()),
        )
        return capability.takeIf { values.findings.isEmpty() && worker != null }
    }

    private fun notice() = CapabilityNotice(
        values.text("message") ?: name,
        values.choice("color", keys["color"], COLORS, DEFAULT_COLOR) ?: DEFAULT_COLOR,
    )

    private fun limits() =
        CapabilityLimits(timeout(), values.choice("on_failure", keys["on_failure"], FAILURES, OPEN) ?: OPEN)

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

    private fun timeout(): Int {
        val value = keys["timeout_s"] ?: return DEFAULT_TIMEOUT_S
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
        val turn = values.choice(TURN_KEY, keys["turn"], TURNS, CapabilityTurn.FIRST)
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

/** The `worker` table: an http worker with its url, a tool of an MCP server, or a built-in worker splice ships. */
private class CapabilityWorkerCheck(private val values: CapabilityValues, private val builtins: Set<String>) {
    fun read(): CapabilityWorker? {
        val keys = values.table.keys["worker"] as? JsonObject
        if (keys == null) {
            values.fault(
                "worker",
                "is required: { kind = \"http\", url = \"...\" }, " +
                    "{ kind = \"mcp\", server = \"...\", tool = \"...\" } or { kind = \"builtin\", name = \"...\" }",
            )
            return null
        }
        return when (values.string(keys["kind"])) {
            HTTP -> http(keys)
            MCP -> mcp(keys)
            BUILTIN -> builtin(keys)
            else -> null.also { values.fault("worker.kind", "must be http, mcp or builtin") }
        }
    }

    private fun http(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in HTTP_KEYS }.forEach { values.fault("worker.$it", "is not a key an http worker has") }
        val url = values.string(keys["url"])?.takeIf { it.isNotBlank() }
        val authEnv = keys["auth_env"]?.let { values.string(it)?.takeIf(String::isNotBlank) ?: "" }
        if (authEnv == "") values.fault("worker.auth_env", "must name an environment variable")
        if (url == null) values.fault("worker.url", "is required for an http worker")
        return url?.let { CapabilityWorker.Http(it, authEnv) }
    }

    private fun mcp(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in MCP_KEYS }.forEach { values.fault("worker.$it", "is not a key an MCP worker has") }
        val server = values.string(keys["server"])?.takeIf { it.isNotBlank() }
        val tool = values.string(keys["tool"])?.takeIf { it.isNotBlank() }
        if (server == null) values.fault("worker.server", "is required for an MCP worker")
        if (tool == null) values.fault("worker.tool", "is required for an MCP worker")
        return if (server != null && tool != null) CapabilityWorker.Mcp(server, tool) else null
    }

    private fun builtin(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in BUILTIN_KEYS }.forEach {
            values.fault(
                "worker.$it",
                "is not a key a built-in worker has",
            )
        }
        val name = values.string(keys["name"])
        when {
            name == null -> values.fault("worker.name", "is required for a built-in worker")
            name !in builtins -> values.fault(
                "worker.name",
                "names built-in worker \"${values.safe(name)}\", which splice does not ship",
            )
        }
        return name?.takeIf { it in builtins }?.let(CapabilityWorker::Builtin)
    }
}

private val NAME = Regex("[a-z0-9-]+")
private val KEYS =
    setOf("heads", "call", "when", "place", "may", "message", "color", "timeout_s", "on_failure", "send", "worker")
private val RULE_KEYS = setOf("turn", "thread", "mode", "models")
private val HTTP_KEYS = setOf("kind", "url", "auth_env")
private val BUILTIN_KEYS = setOf("kind", "name")
private val MCP_KEYS = setOf("kind", "server", "tool")
private val TURNS = CapabilityTurn.entries.associateBy { it.wire }
private val THREADS = CapabilityThread.entries.associateBy { it.wire }
private val MODES = CapabilityMode.entries.associateBy { it.wire }
private val PLACES = CapabilityPlace.entries.associateBy { it.wire }
private val MAYS = CapabilityMay.entries.associateBy { it.wire }
private val FAILURES = CapabilityFailure.entries.associateBy { it.wire }
private val FIELDS = CapabilityField.entries.associateBy { it.wire }
private val OPEN = CapabilityFailure.OPEN

/** The status line's own colour names: the terminal's eight and gray. */
private val COLORS = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white", "gray")
    .associateWith { it }
private const val MAX_TIMEOUT_S = 3600L
private val TIMEOUT_RANGE = 1L..MAX_TIMEOUT_S
private const val DEFAULT_TIMEOUT_S = 60
private const val DEFAULT_COLOR = "cyan"
private const val TURN_KEY = "when.turn"
private const val EVERY_TURN_PLACES =
    "is any, and only a first turn places text in the first user message; set place to none"
private const val PLACE = "place"
private val ADDING = setOf(CapabilityMay.ADD, CapabilityMay.ADD_OR_REFUSE)
private const val RULE = "rule"
private val CALLS = mapOf(RULE to RULE)
private const val HTTP = "http"
private const val MCP = "mcp"
private const val BUILTIN = "builtin"
