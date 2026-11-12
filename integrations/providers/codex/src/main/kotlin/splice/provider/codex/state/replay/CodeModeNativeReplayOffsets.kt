package splice.provider.codex.state.replay

import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodexCodeModeHistoryCodec

/** Keeps callback-scoped replay declarations attached to their original function call. */
internal object CodeModeNativeReplayOffsets {
    /** A callback declaration belongs immediately before its function_call, even when a canonical
     *  script/output was inserted at the old offset. The next offset is after the call, so subtract
     *  one to keep the declaration attached to it. */
    fun callbackOffset(
        codec: CodexCodeModeHistoryCodec,
        input: ResponsesCodeModeInput,
        segment: ResponsesCodeModeReplay,
        offsets: IntArray,
    ): Int {
        val callbackId = segment.callbackId ?: return offsets[segment.logicalOffset]
        val callbackIndex = codec.projection.functionCallIndex(input.logicalItems, callbackId)
        return if (callbackIndex >= 0) offsets[callbackIndex + 1] - 1 else offsets[segment.logicalOffset]
    }
}
