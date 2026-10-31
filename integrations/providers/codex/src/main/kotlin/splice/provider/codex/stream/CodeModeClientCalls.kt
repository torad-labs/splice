package splice.provider.codex.stream

import splice.core.util.JsonWire
import splice.provider.codex.CodeModePending
import splice.upstream.codemode.CodeModeCell
import splice.upstream.sse.WireSink

/** Each client block retains the native invocation that owns its delayed result. */
internal object CodeModeClientCalls {
    suspend fun emit(calls: List<CodeModePending>, sink: WireSink, cell: CodeModeCell?) {
        calls.forEach { call ->
            if (cell == null) {
                emit(call, sink)
            } else {
                cell.withInvocationContext(call.runtimeId) { emit(call, sink) }
            }
        }
    }

    private suspend fun emit(call: CodeModePending, sink: WireSink) {
        val index = sink.openTool(call.clientId, call.name)
        sink.inputJsonDelta(index, JsonWire.string(call.arguments))
        sink.closeBlock(index)
    }
}
