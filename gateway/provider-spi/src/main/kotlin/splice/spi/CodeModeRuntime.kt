// NEW: script runtime yields ordinary client tool requests without executing privileged operations.
package splice.spi

import kotlinx.serialization.json.JsonObject

/** Splice-owned script execution. A yielded request is executed by the client, never by this runtime. */
public interface CodeModeRuntime : AutoCloseable {
    public suspend fun start(source: String, tools: Set<String>): CodeModeCell
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
