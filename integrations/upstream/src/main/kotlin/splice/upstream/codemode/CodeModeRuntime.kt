// NEW: script runtime yields ordinary client tool requests without executing privileged operations.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** Splice-owned script execution. A yielded request is executed by the client, never by this runtime. */
public interface CodeModeRuntime : AutoCloseable {
    /** V4-388: [descriptions] (client tool name to description) fill the cell's `ALL_TOOLS`.
     *  Only [splice.upstream.failure.CodeModeStartException] proves source was never dispatched.
     *  Any other failure can follow execution, so a caller must never restart that source. */
    public suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
    ): CodeModeCell

    /** Address a runtime instance with the provider's existing conversation/session identity. */
    public suspend fun startSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
    ): CodeModeCell = start(source, tools, descriptions)

    /** A model item's source, started once its whole item has arrived, under the session's identity. */
    public suspend fun startStreamingSession(
        sessionKey: String,
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
    ): CodeModeCell = startStreaming(source, tools, descriptions)

    /** Release the session-owned engine when the provider expires its retained code-mode state. */
    public fun closeSession(sessionKey: String): Unit = Unit

    /** Waits for the whole item, then starts it as [start] does. A source that failed starts nothing. */
    public suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
    ): CodeModeCell = when (val whole = source.read()) {
        is CodeModeSourcePart.Complete -> start(whole.text, tools, descriptions)
        is CodeModeSourcePart.Failed -> throw java.io.IOException(whole.error)
    }
}

/** One bounded script; resumption delivers only results of previously yielded client requests. */
public interface CodeModeCell : AutoCloseable {
    /** Results may be any nonempty, duplicate-free subset of calls returned earlier by this cell. */
    public suspend fun advance(results: List<CodeModeResult> = emptyList()): CodeModeStep

    /** Observe the same running cell for at most [yieldTimeMs], without restarting its source. */
    public suspend fun observe(yieldTimeMs: Long): CodeModeStep = advance()

    /** Run callback delivery or its continuation under the invocation's live context, when one exists. */
    public suspend fun <T> withInvocationContext(id: String, action: CodeModeInvocationAction<T>): T = action.run()
}

public fun interface CodeModeInvocationAction<T> {
    public suspend fun run(): T
}

/** Only invocation context may accompany a buffered callback write; caller jobs and dispatchers do not. */
public interface CodeModeInvocationContext : CoroutineContext.Element {
    public val isActive: Boolean
    public suspend fun <T> within(action: CodeModeInvocationAction<T>): T
}

public object CodeModeInvocationContextKey : CoroutineContext.Key<CodeModeInvocationContext>

public data class CodeModeCall(
    val id: String,
    val name: String,
    /** Compatibility view for clients that accept object-valued function inputs. */
    val arguments: JsonObject,
    /** Complete native input; primitive, array, null and freeform values retain their wire shape. */
    val rawInput: JsonElement? = null,
    val namespace: String? = null,
    val kind: CodeModeManual.Kind = CodeModeManual.Kind.FUNCTION,
    /** Original member name before namespace composition; never reconstruct it by splitting [name]. */
    val originalName: String = name,
)

public enum class CodeModeResultDisposition { REJECT, TOOL_OUTPUT }

public enum class CodeModeResultOrigin { GENERIC, MCP }

public data class CodeModeResult(
    val id: String,
    val output: String,
    val isError: Boolean = false,
    /** An explicitly typed native delegate value; absent values preserve [output] as client text. */
    val payload: JsonElement? = null,
    val disposition: CodeModeResultDisposition = CodeModeResultDisposition.REJECT,
    val origin: CodeModeResultOrigin = CodeModeResultOrigin.GENERIC,
)

public sealed class CodeModeStep {
    /** Newly issued calls may arrive after script completion while older callbacks remain outstanding. */
    public data class Calls(
        val calls: List<CodeModeCall>,
        val output: String? = null,
        val error: String? = null,
        val content: List<JsonObject> = emptyList(),
    ) : CodeModeStep()

    /** No output means the guest is still waiting on a promise; otherwise it completed with callbacks outstanding. */
    public data class WaitingForResults(val output: String?, val error: String? = null) : CodeModeStep()

    /** Model-supplied progress delivered independently of the cell's accumulated output. */
    public data class Notified(val text: String) : CodeModeStep()

    /** The source is still running under this stable identity; a later wait observes only its new output. */
    public data class Yielded(
        val cellId: String,
        val output: String,
        val content: List<JsonObject> = emptyList(),
        val timing: NativeCodeModeTiming? = null,
    ) : CodeModeStep()

    public data class Completed(
        val output: String,
        val error: String? = null,
        val content: List<JsonObject> = emptyList(),
        val terminated: Boolean = false,
        val timing: NativeCodeModeTiming? = null,
    ) : CodeModeStep()
}
