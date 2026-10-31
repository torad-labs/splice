// NEW: a bounded captured source offers a fresh cursor only for proven pre-dispatch retries.
//
// A cursor receives one whole, durably sealed item at output_item.done. Response usage and transport failure
// settle separately; neither can revoke an accepted item while its worker is waiting to claim it.
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
    val rejected: CodeModeSourcePart.Failed? = null,
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
            kept = previous.terminal == null && previous.rejected == null
            if (kept) previous.copy(text = text) else previous
        }
        return kept
    }

    fun complete(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(previous.rejected == null) { "source was disposed" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            previous.copy(text = text, terminal = CodeModeSourcePart.Complete())
        }
    }

    /** Failure ends an incomplete source. A sealed item stays accepted independently of worker scheduling. */
    fun fail(error: String) {
        state.update { previous ->
            when (previous.terminal) {
                null -> previous.copy(terminal = CodeModeSourcePart.Failed(error))
                is CodeModeSourcePart.Complete -> previous
                is CodeModeSourcePart.Failed -> previous
            }
        }
    }

    /** Explicit disposal revokes a pending startup even after sealing. A late response failure only calls [fail]. */
    fun dispose(error: String) {
        state.update { previous -> previous.copy(rejected = previous.rejected ?: CodeModeSourcePart.Failed(error)) }
    }

    /** Incomplete-source failure or explicit disposal releases startup; sealing survives a late response failure. */
    suspend fun whileStarting(work: CodeModeSourceStart): CodeModeCell = coroutineScope {
        val produced = AtomicReference<CodeModeCell?>()
        val rejected = async { state.mapNotNull { it.rejected ?: it.terminal as? CodeModeSourcePart.Failed }.first() }
        val running = async {
            work().also {
                produced.set(it)
            }
        }
        var adopted = false
        try {
            val cell = select {
                rejected.onAwait { rejectStartup(it) }
                running.onAwait { it }
            }
            (state.value.rejected ?: state.value.terminal as? CodeModeSourcePart.Failed)?.let(::rejectStartup)
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

    /** A cursor over the source: it waits for item completion, then reads the whole item as one
     *  [CodeModeSourcePart.Complete], or the failure that ended it. A later read gets the empty remainder. */
    fun view(): CodeModeSource {
        var position = 0
        return object : CodeModeSource {
            override suspend fun read(): CodeModeSourcePart =
                part(state.first { it.terminal != null || it.rejected != null })

            private fun part(ended: SourceSnapshot): CodeModeSourcePart {
                (ended.rejected ?: ended.terminal as? CodeModeSourcePart.Failed)?.let { return it }
                beforeRead.commit(ended.text)
                // Durable admission can explicitly dispose the item while the cursor commits its bytes.
                state.value.rejected?.let { return it }
                val remainder = ended.text.substring(position)
                position = ended.text.length
                return CodeModeSourcePart.Complete(remainder)
            }
        }
    }
}
