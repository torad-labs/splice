// NEW: an addressed context consumes certified statements and keeps logs until its source ends.
package splice.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.graalvm.polyglot.Value

internal class StreamingWorkerSession(
    private val control: Value,
    private val bridge: WorkerBridge,
    syntax: CodeModeStatementSyntax,
    private val sealedGlobals: Set<String> = emptySet(),
) {
    private val parser = CodeModeStatementParser(syntax)
    private var receivedBytes = 0L

    fun append(text: String, complete: Boolean, error: String?) {
        receivedBytes += text.encodeToByteArray().size
        require(receivedBytes <= CodeModeWire.maxTextBytes) { "Code-mode source exceeds its text budget" }
        parser.append(text, complete, error)
        drain()
    }

    fun drain() {
        if (finishStopped()) return
        while (canRunStatement()) {
            when (val next = parser.next()) {
                null -> return
                is StatementInput.Program -> if (!run(next)) return
                is StatementInput.Failed -> control.getMember("finish").execute(next.error)
                StatementInput.End -> control.getMember("finish").execute(null)
            }
        }
        finishStopped()
    }

    private fun run(program: StatementInput.Program): Boolean {
        val violation = CodeModeScopeSeal.violation(program.bindings, sealedGlobals)
        if (violation != null) {
            control.getMember("reject").execute(violation)
            return true
        }
        val bindings = JsonArray(
            program.bindings.map { binding ->
                buildJsonObject {
                    put("name", binding.name)
                    put("kind", binding.kind)
                }
            },
        )
        val reads = JsonArray(program.compiled.reads.map(::JsonPrimitive))
        val dependencies = kotlinx.serialization.json.JsonObject(
            program.compiled.dependencies.mapValues { (_, names) -> JsonArray(names.map(::JsonPrimitive)) },
        )
        val intrinsics = kotlinx.serialization.json.JsonObject(
            program.compiled.intrinsicReads.mapValues { (_, names) -> JsonArray(names.map(::JsonPrimitive)) },
        )
        val ready = control.getMember("ready").execute(
            reads.toString(),
            bindings.toString(),
            dependencies.toString(),
            intrinsics.toString(),
        ).asBoolean()
        val executable = ready && !program.compiled.requiresCompleteSource
        if (!parser.isComplete && !executable) {
            parser.defer(program)
            return false
        }
        control.getMember("run").execute(
            program.compiled.source,
            bindings.toString(),
            program.compiled.scopeName,
            dependencies.toString(),
            parser.isComplete,
            if (parser.isComplete) parser.terminalError() else null,
        )
        return true
    }

    private fun finishStopped(): Boolean {
        val stopped = control.getMember("stopped").execute().asBoolean()
        if (stopped && parser.hasEnded) control.getMember("finish").execute(parser.terminalError())
        return stopped
    }

    private fun canRunStatement(): Boolean = when {
        bridge.calls().isNotEmpty() -> false
        bridge.completion() != null -> false
        control.getMember("stopped").execute().asBoolean() -> false
        else -> !control.getMember("running").execute().asBoolean()
    }
}
