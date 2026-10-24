// NEW: script runtime yields ordinary client tool requests without executing privileged operations.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
}

public data class CodeModeCall(val id: String, val name: String, val arguments: JsonObject)

public data class CodeModeResult(
    val id: String,
    val output: String,
    val isError: Boolean = false,
    /** An explicitly typed native delegate value; absent values preserve [output] as client text. */
    val payload: JsonElement? = null,
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
    ) : CodeModeStep()

    public data class Completed(
        val output: String,
        val error: String? = null,
        val content: List<JsonObject> = emptyList(),
        val terminated: Boolean = false,
    ) : CodeModeStep()
}
