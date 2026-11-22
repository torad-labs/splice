// NEW: the moments a mode may act on (modes/README.md, "Hooks"): what Claude Code's hooks do inside Claude Code, a mode
// does at splice's door, whatever harness sends the turn and whatever provider answers it.
package splice.modes.api

import kotlinx.serialization.json.JsonObject

/** A moment at the door a mode may declare a handler for. The list grows as each moment is built; a manifest naming
 *  one it does not hold is a finding, never a hook that silently does nothing. */
public enum class ModeHookMoment(public val wire: String) {
    /** A session's first request: add context, choose the model, or refuse the session. */
    SESSION_START("session_start"),

    /** A whole request on its way to the provider: see it and change it. */
    REQUEST("request"),
}

/** One call of a mode's hook: the [moment], the [session] it is for, the moment's [body] (JSON), and the mode's store. */
public data class ModeHookCall(
    val moment: ModeHookMoment,
    val session: ModeSession,
    val body: JsonObject,
    val store: ModeStore,
)

/** What a hook answers. A variant its moment does not take is a fault, said in splice's log, and the turn goes on. */
public sealed class ModeHookAnswer {
    /** Nothing changes. */
    public data object Pass : ModeHookAnswer()

    /** At session start: place [text] at the end of the session's first user message, once, and keep it there. */
    public data class AddContext(val text: String) : ModeHookAnswer()

    /** At session start: run the session's turns on [model] from here on. */
    public data class ChooseModel(val model: String) : ModeHookAnswer()

    /** Refuse the turn with [reason], which the person reads. */
    public data class Refuse(val reason: String) : ModeHookAnswer()

    /** The moment's body is [body]: a tool's input for before-tool, a whole request or response for request/response. */
    public data class Rewrite(val body: JsonObject) : ModeHookAnswer()

    /** The tool result, the reply note or the continued text is [text], by the moment's own rule. */
    public data class Replace(val text: String) : ModeHookAnswer()

    /** On stop: carry the turn on with [text] as the person's next message. */
    public data class Continue(val text: String) : ModeHookAnswer()
}

/** A mode that declares hooks: its handler answers the moments its manifest declares, in-process. */
public interface ModeHooks {
    /** The answer to [call], whose moment is the one the manifest declared this handler for. */
    public fun hook(call: ModeHookCall): ModeHookAnswer = ModeHookAnswer.Pass
}

/** What runs an out-of-process hook, like a capability's worker (docs/specs/harness.md, decisions 4 and 13). */
public sealed class ModeHookWorker {
    /** An HTTP endpoint, posted the call as JSON, answering one line of JSON: `{"answer": "pass"}`, `context`, `model`,
     *  `refuse`, `rewrite` (with `body`), `replace` or `continue` (with `text`). */
    public data class Http(val url: String, val authEnv: String?) : ModeHookWorker()

    /** A tool of an MCP server the hub serves, called with the moment, session and body as its arguments. */
    public data class Mcp(val server: String, val tool: String) : ModeHookWorker()
}

/** One `[hooks.<moment>]` entry: its [moment], its time limit in seconds (1 to 3600), whether a failure refuses the
 *  turn (`failClosed`) or lets it go on, and its [worker] (null: the handler answers it in-process). */
public data class ModeHook(
    val moment: ModeHookMoment,
    val timeoutS: Long,
    val failClosed: Boolean,
    val worker: ModeHookWorker?,
)
