// NEW: a bounded captured source offers a fresh cursor only for proven pre-dispatch retries.
//
// WHOLE ITEMS ONLY (Oct 23, 2026). A cursor hands the worker nothing until the response terminal certifies the
// exec item, then the whole item in one part, as Codex dispatches exec only on its completed item (codex-rs
// turn.rs:2903-3014). Statements used to run as they streamed: a script's first client
// tool calls went out before its upstream round ended, so one round outlived the client response that carried
// them, and the detached reader, switching attachments, cut claims and split billing that followed were the
// most-fixed code-mode family since Sep 1 (77 commits). The model never opens a second exec while one runs, and
// its sources are short (661 chars at p50), so the overlap this gives up is a fraction of a script's generation.
// The buffer kept the streamed path's item seal and statement certification until round 11 of #247 found them
// unreachable: a cell starts only after the cursor read the terminal, which no later failure replaces, and the capture
// already refuses a delta after its item completed.
package splice.provider.codex.stream

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

private data class SourceSnapshot(
    val text: String,
    val terminal: CodeModeSourcePart? = null,
)

/** Joins an accumulated prefix to the conversation batch before a cursor hands it to the worker. */
internal fun interface CodeModeSourceCommit {
    fun commit(text: String)
}

/** Worker startup whose executable source must remain valid until a cell is returned. */
internal fun interface CodeModeSourceStart {
    suspend operator fun invoke(): CodeModeCell
}

internal class CodeModeSourceBuffer(
    private val beforeRead: CodeModeSourceCommit = CodeModeSourceCommit {},
) {
    private val state = MutableStateFlow(SourceSnapshot(""))
    internal val waiters: Int get() = state.subscriptionCount.value
    val text: String get() = state.value.text

    @Volatile var startupRejected: Boolean = false
        private set

    /** A failed source is final: a delta its reader parsed while a disposal, cut or cancel was failing it is dropped,
     *  never dispatched, and is not the upstream's protocol fault (it raced the round's own end, outside its lock).
     *  False when it was dropped. */
    fun publish(text: String): Boolean {
        var kept = true
        state.update { previous ->
            check(previous.terminal !is CodeModeSourcePart.Complete) { "source already ended" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            kept = previous.terminal == null
            if (kept) SourceSnapshot(text) else previous
        }
        return kept
    }

    fun complete(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            SourceSnapshot(text, CodeModeSourcePart.Complete())
        }
    }

    fun fail(error: String) {
        state.update { previous ->
            if (previous.terminal == null) previous.copy(terminal = CodeModeSourcePart.Failed(error)) else previous
        }
    }

    /** Failed source cancels a pending boot. Certified source imposes no execution deadline. */
    suspend fun whileStarting(work: CodeModeSourceStart): CodeModeCell = coroutineScope {
        val produced = AtomicReference<CodeModeCell?>()
        val rejected = async { state.mapNotNull { it.terminal as? CodeModeSourcePart.Failed }.first() }
        val running = async { work().also(produced::set) }
        var adopted = false
        try {
            val cell = select {
                rejected.onAwait { rejectStartup(it) }
                running.onAwait { it }
            }
            (state.value.terminal as? CodeModeSourcePart.Failed)?.let(::rejectStartup)
            adopted = true
            cell
        } finally {
            rejected.cancel()
            withContext(NonCancellable) {
                running.cancelAndJoin()
                if (!adopted) produced.getAndSet(null)?.close()
            }
        }
    }

    private fun rejectStartup(failure: CodeModeSourcePart.Failed): Nothing {
        startupRejected = true
        throw IOException(failure.error)
    }

    /** A cursor over the source: it waits for the response terminal, then reads the whole item as one
     *  [CodeModeSourcePart.Complete], or the failure that ended it. A later read gets the empty remainder. */
    fun view(): CodeModeSource {
        var position = 0
        return object : CodeModeSource {
            override suspend fun read(): CodeModeSourcePart = part(state.first { it.terminal != null })

            private fun part(ended: SourceSnapshot): CodeModeSourcePart {
                (ended.terminal as? CodeModeSourcePart.Failed)?.let { return it }
                beforeRead.commit(ended.text)
                val remainder = ended.text.substring(position)
                position = ended.text.length
                return CodeModeSourcePart.Complete(remainder)
            }
        }
    }
}
