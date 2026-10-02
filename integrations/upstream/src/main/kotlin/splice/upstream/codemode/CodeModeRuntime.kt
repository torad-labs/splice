// NEW: script runtime yields ordinary client tool requests without executing privileged operations.
package splice.upstream.codemode

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

    /** Incremental-capable runtimes dispatch complete statements before the source item completes. */
    public suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
    ): CodeModeCell {
        val buffered = StringBuilder()
        while (true) {
            when (val part = source.read()) {
                is CodeModeSourcePart.Delta -> buffered.append(part.text)
                is CodeModeSourcePart.Complete -> return start(
                    buffered.append(part.text).toString(),
                    tools,
                    descriptions,
                )
                is CodeModeSourcePart.Failed -> throw java.io.IOException(part.error)
            }
            require(CodeModeLimits.fitsText(buffered.toString())) { "Code-mode source exceeds its text budget" }
        }
    }
}

/** One bounded script; resumption delivers only results of previously yielded client requests. */
public interface CodeModeCell : AutoCloseable {
    public suspend fun advance(results: List<CodeModeResult> = emptyList()): CodeModeStep
}

public data class CodeModeCall(val id: String, val name: String, val arguments: JsonObject)

public data class CodeModeResult(val id: String, val output: String, val isError: Boolean = false)

public sealed class CodeModeStep {
    public data class Calls(val calls: List<CodeModeCall>) : CodeModeStep()
    public data class Completed(val output: String, val error: String? = null) : CodeModeStep()
}
