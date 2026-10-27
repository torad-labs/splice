package splice.provider.codex.state.replay

import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.history.CodeModeReasoningIdentity
import splice.provider.codex.state.CodeModeNativeChain

/** Callback affinity alone cannot certify a native item the client added. GPT's reasoning is matched by its identity:
 *  the client sends back the copy its envelope carried, which leaves out the empty content the stream's copy holds
 *  (ReasoningReplay), and that copy is still GPT's own. */
internal class CodeModeReplayEvidence(private val record: CodeModeRecord, private val boundary: Int) {
    private val allowed = CodeModeNativeChain.allowed(record)
        .mapTo(HashSet()) { (offset, items) -> offset to items.map(CodeModeReasoningIdentity::key) }

    fun owns(replay: ResponsesCodeModeReplay, witnessed: Set<ResponsesCodeModeReplay>): Boolean {
        if (replay.items.isEmpty() || replay in witnessed) return true
        val acknowledged = record.replayAnchors?.client?.acknowledgement
        val historical = replay.logicalOffset <= boundary && acknowledged != null &&
            replay.items.all(acknowledged::owns)
        return historical || (replay.logicalOffset to replay.items.map(CodeModeReasoningIdentity::key)) in allowed
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
