// NEW: a bounded captured source offers a fresh cursor only for proven pre-dispatch retries.
package splice.provider.codex.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart

private data class SourceSnapshot(val text: String, val terminal: CodeModeSourcePart? = null)

internal class CodeModeSourceBuffer {
    private val state = MutableStateFlow(SourceSnapshot(""))

    fun publish(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            SourceSnapshot(text)
        }
    }

    fun complete(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            SourceSnapshot(text, CodeModeSourcePart.Complete())
        }
    }

    fun fail(error: String) {
        state.update { previous ->
            if (previous.terminal == null) previous.copy(terminal = CodeModeSourcePart.Failed(error)) else previous
        }
    }

    fun view(): CodeModeSource {
        var position = 0
        return object : CodeModeSealedSource {
            override val sealedGlobals: Set<String> = CodeModeManual.streamingSealedGlobals

            override suspend fun read(): CodeModeSourcePart {
                val next = state.first { it.text.length > position || it.terminal != null }
                return when (val terminal = next.terminal) {
                    is CodeModeSourcePart.Failed -> terminal
                    is CodeModeSourcePart.Complete -> CodeModeSourcePart.Complete(next.text.substring(position)).also {
                        position = next.text.length
                    }
                    else -> CodeModeSourcePart.Delta(next.text.substring(position)).also { position = next.text.length }
                }
            }
        }
    }
}
