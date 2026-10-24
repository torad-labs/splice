package splice.dialect.responses.stream

import splice.dialect.responses.ResponsesTurnState
import splice.upstream.transport.BufferCapacity

/** One char budget owns its local failure latch across pre-event, post-event and EOF checks. */
internal class ResponsesBufferBudget {
    var failure: String? = null
        private set

    fun within(state: ResponsesTurnState, heldChars: Long, includeBlockCount: Boolean = true): Boolean {
        val retained = state.reasoningEnvelopes.sumOf { it.length.toLong() } + state.bufferedToolArgsChars +
            (state.nativePrefix?.retainedChars ?: 0L) + heldChars + state.textBuf.length + state.thinkingBuf.length
        val exceeded = BufferCapacity.over(
            state.textBuf.length,
            state.thinkingBuf.length,
            toolIndexCount = if (includeBlockCount) state.blocks.size else 0,
            pendingArgsLen = minOf(Int.MAX_VALUE.toLong(), retained).toInt(),
        )
        if (exceeded) failure = RUNAWAY_GUARD_MESSAGE
        return !exceeded
    }
}

// why: every budget check reports the same local runaway verdict; numeric caps remain solely in BufferCapacity.
private const val RUNAWAY_GUARD_MESSAGE = "upstream: response exceeded max buffered size; aborting"
