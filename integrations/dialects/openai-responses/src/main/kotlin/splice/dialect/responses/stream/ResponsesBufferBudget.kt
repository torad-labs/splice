package splice.dialect.responses.stream

import splice.dialect.responses.ResponsesTurnState
import splice.upstream.transport.BufferCapacity

/** One char budget owns its local failure latch across pre-event and final checks before cleanup. */
internal class ResponsesBufferBudget {
    var failure: String? = null
        private set

    fun within(state: ResponsesTurnState, heldChars: Long): Boolean {
        // Native history charges independently owned data; its borrowed display text is already in textBuf.
        val retained = state.reasoningEnvelopes.sumOf { it.length.toLong() } + state.bufferedToolArgsChars +
            (state.nativePrefix?.retainedChars ?: 0L) + heldChars + state.textBuf.length + state.thinkingBuf.length
        val exceeded = BufferCapacity.over(
            state.textBuf.length,
            state.thinkingBuf.length,
            toolIndexCount = state.blocks.size,
            pendingArgsLen = minOf(Int.MAX_VALUE.toLong(), retained).toInt(),
        )
        if (exceeded) failure = RESPONSES_BUFFER_CAPACITY_MESSAGE
        return !exceeded
    }
}

// why: every budget check reports the same local runaway verdict; numeric caps remain solely in BufferCapacity.
private const val RESPONSES_BUFFER_CAPACITY_MESSAGE = "upstream: response exceeded max buffered size; aborting"
