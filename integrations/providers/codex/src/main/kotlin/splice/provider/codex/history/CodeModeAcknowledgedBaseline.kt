package splice.provider.codex.history

import splice.dialect.responses.request.ResponsesCodeModeInput

/** Split a merged assistant block at the exact acknowledged character boundary, preserving its native slots. */
internal object CodeModeAcknowledgedBaseline {
    fun locate(
        input: ResponsesCodeModeInput,
        acknowledgement: CodeModeClientAcknowledgement,
        clearedCallbacks: Set<String>,
        scripts: CodeModeScriptPlacements,
    ): Pair<ResponsesCodeModeInput, Int>? {
        val cursor = acknowledgement.consume(input, clearedCallbacks) ?: return null
        if (cursor.characters == 0) return input to cursor.item
        val items = scripts.cut(input.logicalItems, CodeModeEcho(cursor.item, cursor.characters))
        val replay = input.replayItems.map { segment ->
            if (segment.logicalOffset > cursor.item) {
                segment.copy(
                    logicalOffset = segment.logicalOffset + 1,
                )
            } else {
                segment
            }
        }
        return ResponsesCodeModeInput(items, replay) to (cursor.item + 1)
    }
}
