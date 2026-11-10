// NEW: Nov 9, 2026 — a long conversation's history is bounded by keeping its newest posts.
//
// Every turn adds a post, and a rewind, a compaction or an edit adds one that shares little with the post before it,
// so one busy claudex conversation's journal reached its 256 MiB bound in about 33 hours and every later turn of it
// failed (four in a row at 08:00 CT on Nov 9). Its old posts are branches no request extends any more: a request
// extends the newest kept post its history matches, and one that matches none is folded from the client's own history,
// as a new conversation is. So when the journal nears its bound it is rewritten as its newest posts that fit a budget,
// always with the post the write is about, and the older branches go.
package splice.provider.codex.history.durable

import splice.provider.codex.history.CodeModeLedgerEntry

/** The newest posts that fit, newest first as a state holds them, and what they weigh as a journal. */
internal data class CodeModeHistorySelection(val posts: List<CodeModeLedgerEntry>, val weight: Long)

internal object CodeModeHistoryCheckpoint {
    /** The newest of [posts] whose journal fits [budget], each written as a delta from the next older one kept and
     *  the oldest kept whole. The newest post and the post [keep] names stay even when they alone exceed it. */
    fun select(state: CodeModeHistoryState, keep: Long, budget: Long): CodeModeHistorySelection {
        val newest = state.posts.sortedByDescending { it.id }
        if (newest.isEmpty()) return CodeModeHistorySelection(emptyList(), 0L)
        val stub = CodeModeHistoryState(state.key, emptyList(), state.nextId)
        val must = newest.indexOfFirst { it.id == keep }.coerceAtLeast(0)
        var chained = 0L
        var last = 0
        var selected = weight(stub, newest.first(), null)
        for (index in 1 until newest.size) {
            chained += weight(stub, newest[index - 1], newest[index])
            val total = chained + weight(stub, newest[index], null)
            if (total > budget && index > must) break
            last = index
            selected = total
        }
        return CodeModeHistorySelection(newest.subList(0, last + 1), selected)
    }

    /** [posts], newest first, as a journal from revision 1: oldest first, each a delta from the one before it. The
     *  entries keep their ids, so every request that kept a post id still names the same post. */
    fun rebuild(key: String, nextId: Long, posts: List<CodeModeLedgerEntry>): List<CodeModeHistoryPrepared> {
        var base: CodeModeLedgerEntry? = null
        return posts.asReversed().mapIndexed { index, post ->
            val state = CodeModeHistoryState(key, emptyList(), nextId, revision = index + 1L)
            CodeModeHistoryDeltas.prepare(state, post, base).also { base = it.entry }
        }
    }

    private fun weight(stub: CodeModeHistoryState, post: CodeModeLedgerEntry, base: CodeModeLedgerEntry?): Long =
        CodeModeHistoryDeltas.weight(CodeModeHistoryDeltas.prepare(stub, post, base).delta)
}
