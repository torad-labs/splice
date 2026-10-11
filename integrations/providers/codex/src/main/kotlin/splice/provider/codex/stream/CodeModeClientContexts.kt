// NEW: each conversation's last measured context, so a code-mode step with no round of its own tells
// Claude Code the context it is in instead of zero.
package splice.provider.codex.stream

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.util.LruSizing

/**
 * Claude Code reads every assistant message's usage as the context total, and its context bar,
 * splice's statusline share and autocompact all follow it. A local step (a replayed, advanced or ended
 * script) and a turn that ends while its source round is still streaming measured no input, so they
 * report the newest round this conversation measured: its input, cache-read and cache-write buckets,
 * with no output. They report zero only before any round of the conversation has reported.
 *
 * Accounting never reads this. The step's own usage stays zero, because there was no request to bill.
 * A process that restarts knows only the rounds its records persisted, which [persisted] reads.
 */
internal class CodeModeClientContexts(private val persisted: CodeModePersistedContext) {
    private val last = object : LinkedHashMap<String, Usage>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Usage>?): Boolean =
            size > CONTEXTS_KEPT
    }

    /** Remembers [usage]'s context for [key] when it measured one: a finished source round, or a turn. */
    fun note(key: String, usage: Usage) {
        val context = contextOf(usage) ?: return
        synchronized(last) { last[key] = context }
    }

    /** [outcome] as the client should read it. A success that measured no input carries the last context. */
    fun report(key: String, outcome: TurnOutcome): TurnOutcome {
        val measured = when (outcome) {
            is TurnOutcome.Success -> outcome.usage
            is TurnOutcome.Failure -> outcome.salvagedUsage
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage
        }
        note(key, measured)
        if (outcome !is TurnOutcome.Success || outcome.usage.inputTokens > 0) return outcome
        val context = synchronized(last) { last[key] } ?: persisted(key)?.let(::contextOf) ?: return outcome
        return outcome.copy(usage = outcome.usage.copy(origin = outcome.usage.origin.copy(clientContext = context)))
    }

    private fun contextOf(usage: Usage): Usage? = usage.takeIf { it.inputTokens > 0 }?.let {
        Usage(
            inputTokens = it.inputTokens,
            cachedTokens = it.cachedTokens,
            cacheWrite = it.cacheWrite,
            reported = it.reported - UsageField.OUTPUT,
        )
    }
}

/** The newest round a conversation's records kept, read when this process has measured none for it. */
internal fun interface CodeModePersistedContext {
    operator fun invoke(key: String): Usage?
}

/** Live conversations per head are dozens; the bound only keeps a long-lived daemon from growing. */
private const val CONTEXTS_KEPT = 1024
