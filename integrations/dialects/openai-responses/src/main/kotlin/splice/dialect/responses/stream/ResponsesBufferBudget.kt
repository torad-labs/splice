package splice.dialect.responses.stream

import splice.dialect.responses.ResponsesTurnState
import splice.upstream.transport.BufferCapacity

/** One char budget covers rendered buffers and text retained before a presentation block opens. */
internal object ResponsesBufferBudget {
    fun exceeded(state: ResponsesTurnState, heldChars: Long): Boolean {
        val retained = state.reasoningEnvelopes.sumOf { it.length.toLong() } + state.bufferedToolArgsChars +
            (state.nativePrefix?.retainedChars ?: 0L) + heldChars + state.textBuf.length + state.thinkingBuf.length
        return BufferCapacity.over(
            state.textBuf.length,
            state.thinkingBuf.length,
            toolIndexCount = state.blocks.size,
            pendingArgsLen = minOf(Int.MAX_VALUE.toLong(), retained).toInt(),
        )
    }
}
