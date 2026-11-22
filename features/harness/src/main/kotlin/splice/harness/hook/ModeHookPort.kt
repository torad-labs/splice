// NEW: the door's moments a mode may act on (docs/specs/harness.md; modes/README.md, "Hooks"). The harness offers them
// through this port, and :app adapts a mode's manifest onto it per head. The harness knows nothing of modes.
package splice.harness.hook

import kotlinx.serialization.json.JsonObject
import splice.harness.agent.AgentSession

/** A moment at the door a mode may act on. */
public enum class HookMoment(public val wire: String) {
    SESSION_START("session_start"),
    BEFORE_TOOL_CALL("before_tool_call"),
    AFTER_TOOL_CALL("after_tool_call"),
    ON_STOP("on_stop"),
    REQUEST("request"),
    RESPONSE("response"),
}

/** One hook's call: its [moment], the [session] it is for, and the moment's [body] (JSON). */
public data class HookCall(val moment: HookMoment, val session: AgentSession, val body: JsonObject)

/** What a hook answers. */
public sealed class HookAnswer {
    public data object Pass : HookAnswer()

    public data class AddContext(val text: String) : HookAnswer()

    public data class ChooseModel(val model: String) : HookAnswer()

    public data class Refuse(val reason: String) : HookAnswer()

    public data class Rewrite(val body: JsonObject) : HookAnswer()

    public data class Replace(val text: String) : HookAnswer()

    public data class Continue(val text: String) : HookAnswer()
}

/** What runs one hook: the mode's handler in-process, or a worker out of process. */
public sealed class HookWorker {
    public data class InProcess(val run: HookRun) : HookWorker()

    public data class Http(val url: String, val authEnv: String?) : HookWorker()

    public data class Mcp(val server: String, val tool: String) : HookWorker()
}

/** A mode's hook, run in-process. */
public fun interface HookRun {
    public fun run(call: HookCall): HookAnswer
}

/** One hook as its mode declares it: the [mode] it belongs to, its [moment], its [order] among the modes' hooks of that
 *  moment (ties by mode name), its time limit, whether a failure refuses the turn, and its [worker]. */
public data class HookSpec(
    val mode: String,
    val moment: HookMoment,
    val order: Int,
    val timeoutMs: Long,
    val failClosed: Boolean,
    val worker: HookWorker,
)

/** The hooks on for [session], read at each request so a switch reaches the next one. */
public fun interface ModeHookPort {
    public fun hooks(session: AgentSession): List<HookSpec>
}

/** A session no mode hooks. */
public object NoModeHooks : ModeHookPort {
    override fun hooks(session: AgentSession): List<HookSpec> = emptyList()
}
