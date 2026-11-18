// NEW: a capability as splice.toml declares it (docs/specs/harness.md, decision 1): work splice runs around a turn,
// called by a rule, held while it works, its text placed in the session's first user message.
package splice.core.capability

/** One `[capabilities.<name>]` table, read and found well-formed. [heads] empty is declared and off. */
public data class Capability(
    val name: String,
    val heads: List<String>,
    val result: CapabilityResult,
    val notice: CapabilityNotice,
    val limits: CapabilityLimits,
    val work: CapabilityWork,
    /** `call`, `when` and `tool`: who may call it, the rule it is called or offered by, and the tool the model calls it
     *  through (decision 12). */
    val call: CapabilityCalling = CapabilityCalling(),
) {
    /** Whether this capability is switched on for head [key]. */
    public fun onFor(key: String): Boolean = key in heads
}

/** `place` and `may`: where a result's text goes and what a result may do. */
public data class CapabilityResult(
    val place: CapabilityPlace = CapabilityPlace.FIRST_USER_MESSAGE,
    val may: CapabilityMay = CapabilityMay.ADD,
) {
    /** Whether a refusal from the worker refuses the turn; from one that may not, a refusal is a failure. */
    public val mayRefuse: Boolean get() = may != CapabilityMay.ADD

    /** Whether a result's text is placed. */
    public val places: Boolean get() = place == CapabilityPlace.FIRST_USER_MESSAGE && may != CapabilityMay.REFUSE
}

/** `message` and `color`: what the held turn says while the capability starts (decision 7). [message] is null where the
 *  declaration names none: the harness then says a built-in worker's own message, else the capability's name. */
public data class CapabilityNotice(val message: String?, val color: String)

/** `timeout_s` and `on_failure`: how long the work may take, and what a failure does to the turn. */
public data class CapabilityLimits(val timeoutS: Int, val onFailure: CapabilityFailure)

/** What runs the work, and the fields it is sent: a worker gets nothing its declaration does not name. */
public data class CapabilityWork(val worker: CapabilityWorker, val send: List<CapabilityField>)

/** What runs the work. */
public sealed class CapabilityWorker {
    /** Posts one JSON object to [url]; [authEnv] names the key sent as a bearer, read the way a provider's key is. */
    public data class Http(val url: String, val authEnv: String?) : CapabilityWorker()

    /** A tool of an MCP server the hub runs for the session: [server] is the name the session loads it under. */
    public data class Mcp(val server: String, val tool: String) : CapabilityWorker()

    /** A worker that ships with splice, by [name]. [model] is `worker.model`: the model it runs on where it runs one
     *  (the Context Getter's fast model), null for its own default. */
    public data class Builtin(val name: String, val model: String? = null) : CapabilityWorker()

    /** How `splice status` names it: its kind and where it is. */
    public fun describe(): String = when (this) {
        is Http -> "http $url"
        is Mcp -> "mcp $server/$tool"
        is Builtin -> "builtin $name"
    }
}

/** The values each key of a capability table takes, as splice.toml spells them (the rule's are in CapabilityRule). */
public enum class CapabilityPlace(public val wire: String) { FIRST_USER_MESSAGE("first-user-message"), NONE("none") }

public enum class CapabilityMay(public val wire: String) {
    ADD("add"),
    REFUSE("refuse"),
    ADD_OR_REFUSE("add-or-refuse"),
}

public enum class CapabilityFailure(public val wire: String) { OPEN("open"), CLOSED("closed") }

/** The fields a worker may be sent, by name. */
public enum class CapabilityField(public val wire: String) {
    FIRST_USER_MESSAGE("first_user_message"),
    FOLDER("folder"),
    MODEL("model"),
    HEAD("head"),
}
