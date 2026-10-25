package splice.provider.codex.state.replay

import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeNativeChain

/** Callback affinity alone cannot certify a native item the client added. */
internal class CodeModeReplayEvidence(private val record: CodeModeRecord, private val boundary: Int) {
    private val allowed = CodeModeNativeChain.allowed(record)

    fun owns(replay: ResponsesCodeModeReplay, witnessed: Set<ResponsesCodeModeReplay>): Boolean {
        if (replay.items.isEmpty() || replay in witnessed) return true
        val acknowledged = record.replayAnchors?.client?.acknowledgement
        val historical = replay.logicalOffset <= boundary && acknowledged != null &&
            replay.items.all(acknowledged::owns)
        return historical || (replay.logicalOffset to replay.items) in allowed
    }

    /** A client fragment may join the end of its acknowledged prefix to this script's first item. */
    fun current(input: ResponsesCodeModeInput): ResponsesCodeModeInput = input.copy(
        replayItems = input.replayItems.map(::current).filter { it.items.isNotEmpty() },
    )

    fun current(replay: ResponsesCodeModeReplay): ResponsesCodeModeReplay {
        val acknowledged = record.replayAnchors?.client?.acknowledgement ?: return replay
        if (replay.logicalOffset > boundary) return replay
        return replay.copy(items = replay.items.filterNot(acknowledged::owns))
    }
}
