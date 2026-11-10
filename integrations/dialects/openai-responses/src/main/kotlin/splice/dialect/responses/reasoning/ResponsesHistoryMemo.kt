package splice.dialect.responses.reasoning

import splice.core.memory.HeapCategory
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapRelease
import splice.core.reasoning.ReasoningHistory
import splice.core.wire.AnthropicMessage
import splice.core.wire.MessageFragmentInputs
import splice.upstream.memory.JvmHeap
import java.util.concurrent.atomic.AtomicLong

/** One earlier walk over a whole message: the visible-history positions after it and the result proofs it made. */
internal data class HistoryStep(
    val message: AnthropicMessage,
    val history: ReasoningHistory,
    val legacy: ReasoningHistory,
    val proofs: List<Pair<String, String>>,
    val lease: HeapLease,
    val weight: Long,
) {
    /** The latest walk that took this step. */
    var walk: Long = 0
}

/**
 * Claude Code sends the whole transcript on every request, and every request hashes it into two SHA-256 positions,
 * base64 images and whole tool results included. A message's step depends only on the position before it and the
 * message itself, so a step an earlier request took is taken again from here, proved by comparing the message, not
 * by hashing it. One per request builder; bounded in bytes and charged to the process heap ledger.
 */
internal class ResponsesHistoryMemo {
    private val lock = Any()
    private val steps = LinkedHashMap<String, HistoryStep>(MEMO_INITIAL_STEPS, MEMO_LOAD_FACTOR, true)
    private val walks = AtomicLong()
    private var retained = 0L

    /** One request's walk. Steps it takes are never evicted to admit another of its own. */
    fun walk(): HistoryMemoWalk = HistoryMemoWalk(this, walks.incrementAndGet())

    fun find(before: String, message: AnthropicMessage, walk: Long): HistoryStep? {
        val step = synchronized(lock) { steps[before]?.also { it.walk = walk } } ?: return null
        return step.takeIf { MessageFragmentInputs.same(it.message, message) }
    }

    fun record(
        before: String,
        message: AnthropicMessage,
        after: ResponsesHistoryCursor,
        proofs: List<Pair<String, String>>,
        walk: Long,
    ) {
        val weight = HeapJson.add(MEMO_STEP_OWNER_BYTES, MessageFragmentInputs.bytes(message))
        if (weight > MEMO_MAX_STEP_BYTES) return
        val frozen = MessageFragmentInputs.freeze(message)
        val history = after.history.copy()
        val legacy = after.legacySnapshot()
        synchronized(lock) {
            steps.remove(before)?.let(::evict)
            if (!makeRoom(weight, walk)) return
            val lease = HistoryMemoHeap.view.reserve(weight, HeapCategory.TRANSCRIPT_CACHE, HeapOwnerState.ACTIVE)
                ?: return
            retained += weight
            steps[before] = HistoryStep(frozen, history, legacy, proofs, lease, weight).also { it.walk = walk }
        }
    }

    /** Evicts least recently used steps from other walks; a walk longer than the bound keeps its leading steps. */
    private fun makeRoom(weight: Long, walk: Long): Boolean {
        while (retained + weight > MEMO_MAX_BYTES || steps.size >= MEMO_MAX_STEPS) {
            val eldest = steps.entries.firstOrNull() ?: return false
            if (eldest.value.walk == walk) return false
            steps.remove(eldest.key)
            evict(eldest.value)
        }
        return true
    }

    private fun evict(step: HistoryStep) {
        retained -= step.weight
        step.lease.close(HeapRelease.EVICTED)
    }
}

/** The memo as one request's walk sees it. */
internal class HistoryMemoWalk(private val memo: ResponsesHistoryMemo, private val walk: Long) {
    fun find(before: String, message: AnthropicMessage): HistoryStep? = memo.find(before, message, walk)

    fun record(
        before: String,
        message: AnthropicMessage,
        after: ResponsesHistoryCursor,
        proofs: List<Pair<String, String>>,
    ) {
        memo.record(before, message, after, proofs, walk)
    }
}

/** Every builder's memo shares one bounded read view of the process ledger. */
private object HistoryMemoHeap {
    val view = JvmHeap.budget.readShare()
}

// why: a long transcript holds a few hundred messages; the map grows past this on its own.
private const val MEMO_INITIAL_STEPS = 256

// why: LinkedHashMap's standard density, as the message-fragment store uses.
private const val MEMO_LOAD_FACTOR = 0.75f

// why: a 400K-token transcript is about 1.6M characters; 64 MiB holds several such conversations with their images.
private const val MEMO_MAX_BYTES = 64 * 1024 * 1024L

// why: bounds bookkeeping for many tiny messages that would not reach the byte bound.
private const val MEMO_MAX_STEPS = 16_384

// why: a single message above a quarter of the bound is hashed every time rather than crowding out every other step.
private const val MEMO_MAX_STEP_BYTES = MEMO_MAX_BYTES / 4

// why: two unfinished SHA-256 states, the position key and the entry's own bookkeeping.
private const val MEMO_STEP_OWNER_BYTES = 1024L
